package uk.jtoye.core.integration.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.exception.CredentialClientMissingException;
import uk.jtoye.core.exception.CredentialRevokedException;
import uk.jtoye.core.exception.CredentialRoleInvalidException;
import uk.jtoye.core.exception.CredentialScopeInvalidException;
import uk.jtoye.core.exception.CredentialServiceUnavailableException;
import uk.jtoye.core.exception.CredentialsNotConfiguredException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.integration.credential.dto.CreateCredentialRequest;
import uk.jtoye.core.integration.credential.dto.CredentialDto;
import uk.jtoye.core.integration.credential.dto.CredentialSecretDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.security.access.ShopStaff;
import uk.jtoye.core.security.access.ShopStaffRepository;
import uk.jtoye.core.security.access.StaffManagementService;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminException;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminProperties;
import uk.jtoye.core.tenant.keycloak.KeycloakClientExistsException;
import uk.jtoye.core.tenant.keycloak.KeycloakClientNotFoundException;
import uk.jtoye.core.tenant.keycloak.KeycloakClientSpec;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Integrator credentials (D-22, D-23, D-24; RWO-035, UXT-035): a Group admin issues, lists, rotates and
 * revokes scoped API credentials, so a vendor gives a till or a developer access without sharing a password.
 *
 * <p><strong>What a credential is.</strong> A Keycloak service-account client in the vendor realm (37-44
 * builds it: a hard-coded {@code tenant_id} claim, {@code aud=core-api}, exactly the granted scopes), plus an
 * ordinary OPERATOR {@code shop_staff} grant for its service-account user written through
 * {@link StaffManagementService#persistNewGrant}, the same writer every operator grant uses. So
 * {@link ShopAccessService} decides for it exactly as it decides for a person (D-23): no machine path, no
 * {@code jtoye.access.machine-client-ids} entry.
 *
 * <p><strong>Limits (Q7, T-37-107).</strong> Only a Group admin may call any of this
 * ({@link ShopAccessService#requireGroupAdmin()}); a credential holds STAFF or SHOP_MANAGER, never
 * GROUP_ADMIN; it carries only the six #206 scopes. The human-only rule (no machine identity manages
 * credentials or staff) is {@code HumanCallerGuard}, at the controllers.
 *
 * <p><strong>The secret (T-37-108).</strong> Read from Keycloak once, returned once with
 * {@code Cache-Control: no-store}, never stored, never logged. A create under an {@code Idempotency-Key} runs
 * through {@link IdempotencyService#executeWithoutStoringResponse}, so {@code idempotency_keys} never holds
 * the response; a same-key replay re-reads the credential by the key recorded on its row and returns it
 * with {@code secretShown=false}.
 *
 * <p><strong>Keycloak and the database cannot share a transaction.</strong> The Keycloak client is created
 * first; when the database work then fails (or the transaction rolls back for any reason after the client
 * exists), an after-completion hook deletes the Keycloak client again and removes the grant, best-effort, so
 * no working credential is left that nobody holds the secret of. A Keycloak failure is the typed 503
 * {@code credential-service-unavailable}; nothing is written.
 *
 * <p>Inert when the Keycloak admin seam is not configured: every operation is the typed 400
 * {@code credentials-not-configured} (the #102 precedent).
 */
@Service
@Transactional
public class IntegrationCredentialService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCredentialService.class);

    /** The logical {@code Idempotency-Key} operation id; never shared with a body-storing adopter. */
    public static final String CREATE_ENDPOINT = "credentials.create";

    /** Every credential clientId starts with this; the last-used recorder keys on it. */
    public static final String CLIENT_ID_PREFIX = "jtoye-int-";

    /** The six #206 client scopes (docs/security-scopes.md), in their documented order. */
    public static final List<String> ALLOWED_SCOPES = List.of("catalog:read", "catalog:write", "orders:read",
            "orders:write", "customers:read", "customers:write");

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final int CLIENT_ID_RANDOM_CHARS = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The list response: every credential, newest first, and how coarse {@code lastUsedAt} is. */
    public record CredentialListResponse(List<CredentialDto> credentials, long lastUsedIntervalMinutes) {
    }

    private final IntegrationCredentialRepository repository;
    private final KeycloakAdminClient keycloak;
    private final KeycloakAdminProperties keycloakProperties;
    private final ShopAccessService shopAccessService;
    private final StaffManagementService staffManagementService;
    private final ShopStaffRepository shopStaffRepository;
    private final ShopRepository shopRepository;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate requiresNew;
    private final String tokenEndpoint;
    private final Duration lastUsedInterval;

    @SuppressWarnings("java:S107") // the collaborators are the feature
    public IntegrationCredentialService(IntegrationCredentialRepository repository,
                                        KeycloakAdminClient keycloak,
                                        KeycloakAdminProperties keycloakProperties,
                                        ShopAccessService shopAccessService,
                                        StaffManagementService staffManagementService,
                                        ShopStaffRepository shopStaffRepository,
                                        ShopRepository shopRepository,
                                        IdempotencyService idempotencyService,
                                        PlatformTransactionManager transactionManager,
                                        @Value("${jtoye.security.jwt.expected-issuer}") String expectedIssuer,
                                        @Value("${jtoye.credentials.last-used-interval:PT15M}") Duration lastUsedInterval) {
        this.repository = repository;
        this.keycloak = keycloak;
        this.keycloakProperties = keycloakProperties;
        this.shopAccessService = shopAccessService;
        this.staffManagementService = staffManagementService;
        this.shopStaffRepository = shopStaffRepository;
        this.shopRepository = shopRepository;
        this.idempotencyService = idempotencyService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String issuer = expectedIssuer.endsWith("/")
                ? expectedIssuer.substring(0, expectedIssuer.length() - 1) : expectedIssuer;
        this.tokenEndpoint = issuer + "/protocol/openid-connect/token";
        this.lastUsedInterval = lastUsedInterval;
    }

    // ---------------------------------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------------------------------

    /**
     * Issues a credential. With an {@code Idempotency-Key} the create runs at most once per key: a replay
     * returns the credential it created with {@code secretShown=false} and no secret.
     */
    public CredentialSecretDto create(CreateCredentialRequest request, String idempotencyKey) {
        requireConfigured();
        shopAccessService.requireGroupAdmin();
        List<String> scopes = validate(request);
        UUID tenantId = currentTenantId();
        UUID issuer = currentCaller();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return issue(tenantId, issuer, request, scopes, null);
        }
        IdempotencyOutcome<CredentialSecretDto> outcome = idempotencyService.executeWithoutStoringResponse(
                CREATE_ENDPOINT, idempotencyKey, request,
                () -> issue(tenantId, issuer, request, scopes, idempotencyKey),
                () -> replay(tenantId, idempotencyKey));
        return outcome.value();
    }

    private CredentialSecretDto issue(UUID tenantId, UUID issuer, CreateCredentialRequest request,
                                      List<String> scopes, String idempotencyKey) {
        String realm = keycloakProperties.getVendorRealm();
        String token = adminToken();
        String clientId = newClientId();
        String clientUuid;
        try {
            clientUuid = createClient(realm, new KeycloakClientSpec(clientId, tenantId, scopes), token);
        } catch (KeycloakClientExistsException collision) {
            // 60 random bits make this a retried create rather than chance; one fresh id, then give up.
            log.warn("event=credential_client_id_collision realm={} retrying once with a new clientId", realm);
            clientId = newClientId();
            try {
                clientUuid = createClient(realm, new KeycloakClientSpec(clientId, tenantId, scopes), token);
            } catch (KeycloakClientExistsException again) {
                throw unavailable("create", again);
            }
        }
        UUID kcClientUuid = UUID.fromString(clientUuid);
        CleanupOnRollback cleanup = registerCleanup(realm, clientUuid, tenantId);

        String secret;
        UUID serviceAccountUser;
        try {
            serviceAccountUser = UUID.fromString(keycloak.getServiceAccountUserId(realm, clientUuid, token));
            secret = keycloak.getClientSecret(realm, clientUuid, token);
        } catch (KeycloakAdminException e) {
            throw unavailable("create", e);
        }
        cleanup.serviceAccountUser = serviceAccountUser;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IntegrationCredential credential = repository.saveAndFlush(new IntegrationCredential(UUID.randomUUID(),
                tenantId, request.name().strip(), clientId, kcClientUuid, serviceAccountUser, scopes,
                request.shopId(), request.role(), idempotencyKey, issuer, now));

        // D-23: an ordinary OPERATOR grant, through the one writer every operator grant uses. Its own
        // transaction commits before this one; the cleanup hook removes it if this one then rolls back.
        staffManagementService.persistNewGrant(tenantId, serviceAccountUser, request.shopId(), request.role(), issuer);
        shopAccessService.evictMembershipAfterCommit(serviceAccountUser);

        log.info("event=credential_issued tenant={} id={} clientId={} role={} shop={} scopes={} by={}",
                tenantId, credential.getId(), clientId, request.role(), request.shopId(), scopes, issuer);
        return CredentialSecretDto.shown(credential, secret, tokenEndpoint);
    }

    private CredentialSecretDto replay(UUID tenantId, String idempotencyKey) {
        IntegrationCredential credential = repository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency-Key replay found no credential for its key"));
        return CredentialSecretDto.withheld(credential, tokenEndpoint);
    }

    // ---------------------------------------------------------------------------------------------
    // List
    // ---------------------------------------------------------------------------------------------

    /** Every credential of the business, newest first, revoked ones included (D-24: who and when). */
    @Transactional(readOnly = true)
    public CredentialListResponse list() {
        requireConfigured();
        shopAccessService.requireGroupAdmin();
        List<CredentialDto> credentials = repository.findByTenantIdOrderByCreatedAtDesc(currentTenantId()).stream()
                .map(CredentialDto::from)
                .toList();
        return new CredentialListResponse(credentials, lastUsedInterval.toMinutes());
    }

    // ---------------------------------------------------------------------------------------------
    // Rotate
    // ---------------------------------------------------------------------------------------------

    /**
     * Rotates the secret (D-22): Keycloak generates a new one and the old one stops working at once. The new
     * secret is returned once; who and when are stamped (D-24). A revoked credential is 409
     * {@code credential-revoked}.
     *
     * <p>Keycloak first, then the stamp: if the stamp then fails, the secret has still rotated and the caller
     * rotates again, which is safe. The other order could stamp a rotation that never happened.
     */
    public CredentialSecretDto rotate(UUID id) {
        requireConfigured();
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();
        UUID caller = currentCaller();
        IntegrationCredential credential = find(id, tenantId);
        if (credential.isRevoked()) {
            throw new CredentialRevokedException();
        }
        String realm = keycloakProperties.getVendorRealm();
        String secret;
        try {
            secret = keycloak.regenerateClientSecret(realm, credential.getKcClientUuid().toString(), adminToken());
        } catch (KeycloakClientNotFoundException e) {
            throw new CredentialClientMissingException();
        } catch (KeycloakAdminException e) {
            throw unavailable("rotate", e);
        }
        credential.rotated(caller, OffsetDateTime.now(ZoneOffset.UTC));
        repository.saveAndFlush(credential);
        log.info("event=credential_rotated tenant={} id={} clientId={} by={}",
                tenantId, credential.getId(), credential.getKcClientId(), caller);
        return CredentialSecretDto.shown(credential, secret, tokenEndpoint);
    }

    // ---------------------------------------------------------------------------------------------
    // Revoke
    // ---------------------------------------------------------------------------------------------

    /**
     * Revokes the credential (D-22, D-24): its service account's grant is deleted and its membership evicted
     * after commit, so its next request is refused; who and when are stamped; and the Keycloak client is
     * deleted, so it can mint no new token. Repeatable: revoking a revoked credential changes nothing.
     *
     * <p>The database writes are flushed first and Keycloak is called last, inside the transaction: a
     * Keycloak failure rolls the revocation back (a 503, nothing changed), and a Keycloak success followed by
     * a failed commit leaves a client that is already gone, which the next revoke finishes ({@code false}
     * from {@code deleteClient} is the goal state).
     */
    public void revoke(UUID id) {
        requireConfigured();
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenantId();
        UUID caller = currentCaller();
        IntegrationCredential credential = find(id, tenantId);
        if (credential.isRevoked()) {
            return;
        }
        UUID serviceAccountUser = credential.getServiceAccountUserId();
        List<ShopStaff> grants = shopStaffRepository.findByTenantIdAndUserId(tenantId, serviceAccountUser);
        shopStaffRepository.deleteAll(grants);
        shopStaffRepository.flush();
        shopAccessService.evictMembershipAfterCommit(serviceAccountUser);
        credential.revoke(caller, OffsetDateTime.now(ZoneOffset.UTC));
        repository.saveAndFlush(credential);

        String realm = keycloakProperties.getVendorRealm();
        boolean deleted;
        try {
            deleted = keycloak.deleteClient(realm, credential.getKcClientUuid().toString(), adminToken());
        } catch (KeycloakAdminException e) {
            throw unavailable("revoke", e);
        }
        log.info("event=credential_revoked tenant={} id={} clientId={} grantsRemoved={} keycloakClient={} by={}",
                tenantId, credential.getId(), credential.getKcClientId(), grants.size(),
                deleted ? "deleted" : "already-gone", caller);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private IntegrationCredential find(UUID id, UUID tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Credential not found: " + id));
    }

    private void requireConfigured() {
        if (!keycloakProperties.configured()) {
            throw new CredentialsNotConfiguredException();
        }
    }

    /** The role ceiling, the scope allow-list and the shop's tenancy, before any Keycloak call. */
    private List<String> validate(CreateCredentialRequest request) {
        if (request.role() == ShopRole.GROUP_ADMIN) {
            throw new CredentialRoleInvalidException();
        }
        List<String> scopes = new ArrayList<>(new LinkedHashSet<>(request.scopes()));
        List<String> invalid = scopes.stream().filter(s -> !ALLOWED_SCOPES.contains(s)).toList();
        if (!invalid.isEmpty()) {
            throw new CredentialScopeInvalidException(invalid, ALLOWED_SCOPES);
        }
        if (request.shopId() != null
                && shopRepository.findByIdAndTenantId(request.shopId(), currentTenantId()).isEmpty()) {
            // A FK cannot enforce this (RI bypasses RLS); same non-disclosing 404 as a staff grant (T-23-12-03).
            throw new ResourceNotFoundException("Shop not found in this tenant: " + request.shopId());
        }
        return List.copyOf(scopes);
    }

    private String createClient(String realm, KeycloakClientSpec spec, String token) {
        try {
            return keycloak.createClient(realm, spec, token);
        } catch (KeycloakClientExistsException e) {
            throw e;
        } catch (KeycloakAdminException e) {
            // 37-44: createClient can fail AFTER Keycloak made the client (its UUID lookup failed). Only the
            // clientId can find that client again; sweep it so no orphan holds a working secret.
            sweepByClientId(realm, spec.clientId());
            throw unavailable("create", e);
        }
    }

    private void sweepByClientId(String realm, String clientId) {
        try {
            String token = keycloak.obtainAdminToken();
            keycloak.findClientUuid(realm, clientId, token).ifPresent(uuid -> keycloak.deleteClient(realm, uuid, token));
        } catch (RuntimeException e) {
            log.error("event=credential_orphan_sweep_failed realm={} clientId={} cause={} — delete it in Keycloak by hand",
                    realm, clientId, e.getClass().getSimpleName());
        }
    }

    private String adminToken() {
        try {
            return keycloak.obtainAdminToken();
        } catch (KeycloakAdminException e) {
            throw unavailable("token", e);
        }
    }

    private CredentialServiceUnavailableException unavailable(String operation, KeycloakAdminException e) {
        log.warn("event=credential_keycloak_unavailable operation={} cause={} message={}",
                operation, e.getClass().getSimpleName(), e.getMessage());
        return new CredentialServiceUnavailableException(e);
    }

    /** {@code jtoye-int-} + 12 base32 characters (60 random bits), lower case. */
    static String newClientId() {
        StringBuilder id = new StringBuilder(CLIENT_ID_PREFIX);
        for (int i = 0; i < CLIENT_ID_RANDOM_CHARS; i++) {
            id.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return id.toString();
    }

    /**
     * Undoes a create whose transaction did not commit: deletes the Keycloak client (its service-account
     * user goes with it) and the grant, each best-effort and in its own transaction. Without it a rollback
     * after the client existed would leave a working credential whose secret nobody holds.
     */
    private CleanupOnRollback registerCleanup(String realm, String clientUuid, UUID tenantId) {
        CleanupOnRollback cleanup = new CleanupOnRollback(realm, clientUuid, tenantId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(cleanup);
        }
        return cleanup;
    }

    private final class CleanupOnRollback implements TransactionSynchronization {
        private final String realm;
        private final String clientUuid;
        private final UUID tenantId;
        private UUID serviceAccountUser;

        private CleanupOnRollback(String realm, String clientUuid, UUID tenantId) {
            this.realm = realm;
            this.clientUuid = clientUuid;
            this.tenantId = tenantId;
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
                return;
            }
            try {
                keycloak.deleteClient(realm, clientUuid, keycloak.obtainAdminToken());
                log.warn("event=credential_create_rolled_back realm={} keycloak client deleted again", realm);
            } catch (RuntimeException e) {
                log.error("event=credential_create_cleanup_failed realm={} clientUuid={} cause={} — delete it in "
                        + "Keycloak by hand", realm, clientUuid, e.getClass().getSimpleName());
            }
            if (serviceAccountUser == null) {
                return;
            }
            try {
                requiresNew.executeWithoutResult(s -> {
                    List<ShopStaff> rows = shopStaffRepository.findByTenantIdAndUserId(tenantId, serviceAccountUser);
                    shopStaffRepository.deleteAll(rows);
                });
                shopAccessService.evictMembership(serviceAccountUser);
            } catch (RuntimeException e) {
                log.error("event=credential_create_grant_cleanup_failed tenant={} user={} cause={}",
                        tenantId, serviceAccountUser, e.getClass().getSimpleName());
            }
        }
    }

    private UUID currentTenantId() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }

    /** The Group admin performing the write; a caller with no UUID subject cannot be recorded, so it is refused. */
    private UUID currentCaller() {
        return shopAccessService.currentVendorUserId()
                .orElseThrow(() -> new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN));
    }
}
