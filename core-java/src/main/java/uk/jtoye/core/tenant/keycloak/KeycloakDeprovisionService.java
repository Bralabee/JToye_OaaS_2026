package uk.jtoye.core.tenant.keycloak;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates identity-layer deprovisioning of a tenant's Keycloak users
 * (issue #102 remainder): across every configured realm it disables + logs out
 * every user carrying the tenant's {@code tenant_id} attribute, then stamps
 * {@code tenants.keycloak_deprovisioned_at} — but ONLY when every realm swept
 * cleanly. This is the identity-layer complement to
 * {@code TenantStatusInterceptor}'s request rejection.
 *
 * <p><b>Best-effort, non-throwing contract:</b> {@link #deprovision(UUID)} NEVER
 * throws from the Keycloak sweep. It is invoked from the offboard after-commit
 * hook, so a Keycloak outage must not roll back or fail the offboard — on any
 * {@link KeycloakAdminException} the run aborts, the marker STAYS NULL, an ERROR
 * is logged, and a {@code complete=false} result is returned. State/config
 * guards (not-configured, not-OFFBOARDED) live in the controller path so this
 * worker stays non-throwing for the hook.
 *
 * <p><b>Idempotent:</b> if the marker is already set the run short-circuits with
 * no Keycloak calls; and disabling an already-disabled user is a harmless no-op
 * PUT, so re-running the sweep is safe.
 *
 * <p><b>Inert when disabled (default):</b> the whole feature no-ops with a single
 * WARN and makes zero Keycloak calls until an operator sets
 * {@code jtoye.keycloak.admin.enabled=true} plus base-url + password.
 */
@Service
public class KeycloakDeprovisionService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakDeprovisionService.class);

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;
    private final TenantRepository tenantRepository;

    /** Guards the not-configured WARN so repeated offboards don't spam the log. */
    private final AtomicBoolean warnedOnce = new AtomicBoolean(false);

    public KeycloakDeprovisionService(KeycloakAdminClient keycloakAdminClient,
                                      KeycloakAdminProperties properties,
                                      TenantRepository tenantRepository) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.tenantRepository = tenantRepository;
    }

    /** True when the feature is switched on and reachable — see {@link KeycloakAdminProperties#configured()}. */
    public boolean configured() {
        return properties.configured();
    }

    /**
     * Disable + log out every Keycloak user of the tenant across all configured
     * realms, stamping the marker only on full success. Non-throwing (best-effort)
     * — see class javadoc.
     *
     * <p><b>{@code REQUIRES_NEW} is load-bearing:</b> this is invoked from the
     * offboard {@code afterCommit} hook, where the offboard transaction has
     * already committed but its synchronization is still active. A plain
     * {@code REQUIRED} call would PARTICIPATE in that dead transaction and the
     * marker {@code save()} would never be committed. A fresh, independent
     * transaction both persists the marker correctly AND keeps deprovisioning
     * off the offboard tx (a failure here can't roll back the offboard).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KeycloakDeprovisionResult deprovision(UUID tenantId) {
        if (!properties.configured()) {
            if (warnedOnce.compareAndSet(false, true)) {
                log.warn("event=tenant_keycloak_deprovision_skipped reason=not_configured "
                        + "(feature inert: set jtoye.keycloak.admin.enabled=true + base-url + password)");
            }
            return KeycloakDeprovisionResult.noop(tenantId);
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            // Non-throwing: the hook must not blow up if the row vanished.
            log.error("event=tenant_keycloak_deprovision_failed reason=tenant_not_found tenant={}", tenantId);
            return KeycloakDeprovisionResult.noop(tenantId);
        }

        // Idempotent short-circuit: already deprovisioned, no Keycloak calls.
        if (tenant.getKeycloakDeprovisionedAt() != null) {
            return new KeycloakDeprovisionResult(tenantId, 0, true, tenant.getKeycloakDeprovisionedAt());
        }

        int disabled = 0;
        String currentRealm = null;
        try {
            String token = keycloakAdminClient.obtainAdminToken();
            for (String realm : properties.getRealms()) {
                currentRealm = realm;
                List<ObjectNode> users = keycloakAdminClient.searchUsersByTenant(realm, tenantId, token);
                for (ObjectNode user : users) {
                    keycloakAdminClient.setUserEnabled(realm, user, false, token);
                    keycloakAdminClient.logoutUser(realm, user.path("id").asText(), token);
                    disabled++;
                }
            }
        } catch (KeycloakAdminException e) {
            // Best-effort: abort this run, leave the marker NULL, never rethrow.
            log.error("event=tenant_keycloak_deprovision_failed tenant={} realm={} usersDisabledSoFar={}: {}",
                    tenantId, currentRealm, disabled, e.getMessage());
            return new KeycloakDeprovisionResult(tenantId, disabled, false, null);
        }

        // Every configured realm swept cleanly -> stamp the marker.
        OffsetDateTime now = OffsetDateTime.now();
        tenant.setKeycloakDeprovisionedAt(now);
        tenantRepository.save(tenant);
        log.info("event=tenant_keycloak_deprovisioned tenant={} usersDisabled={}", tenantId, disabled);
        return new KeycloakDeprovisionResult(tenantId, disabled, true, now);
    }
}
