package uk.jtoye.core.security.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An invitation to join a tenant with exactly one grant (D-07, V77): {@link #role} on {@link #shopId},
 * or on every shop of the tenant when {@code shopId} is null (always null for GROUP_ADMIN).
 *
 * <p>The link token is never held here, only its SHA-256 ({@link #tokenSha256}). The status a person
 * sees is derived from {@link #acceptedAt}, {@link #revokedAt} and {@link #expiresAt} at read time
 * ({@link #statusAt(OffsetDateTime)}), never stored. Not {@code @Audited}: who and when are columns.
 */
@Entity
@Table(name = "staff_invite")
public class StaffInvite {

    /** What an invitation is right now. Derived, never stored. */
    public enum Status { OPEN, EXPIRED, ACCEPTED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "email_normalised", nullable = false, length = 320, updatable = false)
    private String emailNormalised;

    @Column(name = "shop_id", updatable = false)
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ShopRole role;

    @Column(name = "token_sha256", nullable = false, length = 64, unique = true, updatable = false)
    private String tokenSha256;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    protected StaffInvite() {
        // JPA
    }

    public StaffInvite(UUID id, UUID tenantId, String emailNormalised, UUID shopId, ShopRole role,
                       String tokenSha256, OffsetDateTime expiresAt, UUID createdBy, OffsetDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.emailNormalised = emailNormalised;
        this.shopId = shopId;
        this.role = role;
        this.tokenSha256 = tokenSha256;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** The status at {@code now}: accepted and cancelled are final; otherwise open until it expires. */
    public Status statusAt(OffsetDateTime now) {
        if (acceptedAt != null) {
            return Status.ACCEPTED;
        }
        if (revokedAt != null) {
            return Status.CANCELLED;
        }
        return now.isBefore(expiresAt) ? Status.OPEN : Status.EXPIRED;
    }

    /** Neither accepted nor revoked: the link would still be honoured if it has not expired. */
    public boolean isLive() {
        return acceptedAt == null && revokedAt == null;
    }

    /** Stops the link working. Idempotent: a second call keeps the first who and when. */
    public void revoke(UUID by, OffsetDateTime at) {
        if (revokedAt == null) {
            revokedAt = at;
            revokedBy = by;
        }
    }

    /** True when this invitation offers the same grant to the same address. */
    public boolean offers(String email, UUID shop, ShopRole offeredRole) {
        return emailNormalised.equals(email)
                && role == offeredRole
                && (shopId == null ? shop == null : shopId.equals(shop));
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getEmailNormalised() { return emailNormalised; }
    public UUID getShopId() { return shopId; }
    public ShopRole getRole() { return role; }
    public String getTokenSha256() { return tokenSha256; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public UUID getAcceptedUserId() { return acceptedUserId; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public UUID getRevokedBy() { return revokedBy; }
    public Long getVersion() { return version; }
}
