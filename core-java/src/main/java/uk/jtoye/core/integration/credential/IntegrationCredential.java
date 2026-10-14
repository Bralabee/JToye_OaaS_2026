package uk.jtoye.core.integration.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import uk.jtoye.core.security.access.ShopRole;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One integrator credential (D-22..D-24, V82): a Keycloak service-account client {@link #kcClientId} whose
 * service-account user {@link #serviceAccountUserId} holds an ordinary OPERATOR {@code shop_staff} grant of
 * {@link #role} on {@link #shopId} (every shop of the tenant when null), so it is limited exactly like a
 * person (D-23).
 *
 * <p>The secret is never held here: Keycloak generates and keeps it, and core shows it to the issuer once.
 * Not {@code @Audited}: who and when are columns, and a row is revoked, never deleted.
 */
@Entity
@Table(name = "integration_credential")
public class IntegrationCredential {

    /** What a credential is right now. Derived from {@link #revokedAt}, never stored. */
    public enum Status { ACTIVE, REVOKED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100, updatable = false)
    private String name;

    @Column(name = "kc_client_id", nullable = false, length = 64, unique = true, updatable = false)
    private String kcClientId;

    @Column(name = "kc_client_uuid", nullable = false, updatable = false)
    private UUID kcClientUuid;

    @Column(name = "service_account_user_id", nullable = false, updatable = false)
    private UUID serviceAccountUserId;

    @Column(name = "scopes", nullable = false, updatable = false, columnDefinition = "TEXT[]")
    private List<String> scopes = new ArrayList<>();

    @Column(name = "shop_id", updatable = false)
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ShopRole role;

    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "rotated_by")
    private UUID rotatedBy;

    @Column(name = "rotated_at")
    private OffsetDateTime rotatedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Written only by {@link IntegrationCredentialRepository#touchLastUsed}, never through the entity. */
    @Column(name = "last_used_at", insertable = false, updatable = false)
    private OffsetDateTime lastUsedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected IntegrationCredential() {
        // JPA
    }

    @SuppressWarnings("java:S107") // one constructor for one immutable registration
    public IntegrationCredential(UUID id, UUID tenantId, String name, String kcClientId, UUID kcClientUuid,
                                 UUID serviceAccountUserId, List<String> scopes, UUID shopId, ShopRole role,
                                 String idempotencyKey, UUID createdBy, OffsetDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.kcClientId = kcClientId;
        this.kcClientUuid = kcClientUuid;
        this.serviceAccountUserId = serviceAccountUserId;
        this.scopes = new ArrayList<>(scopes);
        this.shopId = shopId;
        this.role = role;
        this.idempotencyKey = idempotencyKey;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Status status() {
        return revokedAt == null ? Status.ACTIVE : Status.REVOKED;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Records a secret rotation. Each rotation replaces the previous who and when. */
    public void rotated(UUID by, OffsetDateTime at) {
        this.rotatedBy = by;
        this.rotatedAt = at;
    }

    /** Records the revocation. Idempotent: a second call keeps the first who and when. */
    public void revoke(UUID by, OffsetDateTime at) {
        if (revokedAt == null) {
            this.revokedBy = by;
            this.revokedAt = at;
        }
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getKcClientId() { return kcClientId; }
    public UUID getKcClientUuid() { return kcClientUuid; }
    public UUID getServiceAccountUserId() { return serviceAccountUserId; }
    public List<String> getScopes() { return List.copyOf(scopes); }
    public UUID getShopId() { return shopId; }
    public ShopRole getRole() { return role; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public UUID getRotatedBy() { return rotatedBy; }
    public OffsetDateTime getRotatedAt() { return rotatedAt; }
    public UUID getRevokedBy() { return revokedBy; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public Long getVersion() { return version; }
}
