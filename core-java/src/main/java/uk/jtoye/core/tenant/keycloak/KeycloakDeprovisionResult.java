package uk.jtoye.core.tenant.keycloak;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outcome of a Keycloak deprovisioning run (issue #102 remainder). Returned by
 * {@link KeycloakDeprovisionService#deprovision(UUID)} and surfaced as JSON by
 * the admin re-trigger endpoint.
 *
 * @param tenantId         the tenant whose users were targeted
 * @param usersDisabled    count of users disabled in this run (0 for a no-op /
 *                         idempotent short-circuit)
 * @param complete         true when the marker is (or already was) stamped —
 *                         i.e. every configured realm swept cleanly; false for a
 *                         no-op (feature off) or a partial/aborted run
 * @param deprovisionedAt  the marker timestamp when complete, else null
 */
public record KeycloakDeprovisionResult(
        UUID tenantId,
        int usersDisabled,
        boolean complete,
        OffsetDateTime deprovisionedAt) {

    /** Feature-off / not-found no-op: nothing disabled, marker untouched. */
    public static KeycloakDeprovisionResult noop(UUID tenantId) {
        return new KeycloakDeprovisionResult(tenantId, 0, false, null);
    }
}
