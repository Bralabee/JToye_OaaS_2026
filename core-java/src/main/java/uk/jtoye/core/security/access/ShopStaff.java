package uk.jtoye.core.security.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user &lt;-&gt; shop &lt;-&gt; role grant within a tenant (V52 {@code shop_staff}).
 *
 * <p>A {@code null} {@link #shopId} means the grant is tenant-wide — the
 * GROUP_ADMIN shape (all shops). Uniqueness is enforced by the V52 functional
 * index {@code uq_shop_staff_tenant_user_shop} over
 * {@code (tenant_id, user_id, COALESCE(shop_id, zero-uuid))}, which is also the
 * ON CONFLICT target for the race-safe JIT insert (23-02).
 *
 * <p>House conventions mirror {@code onboarding/VendorOnboarding.java}:
 * hand-written accessors (no Lombok / code-gen on entities), {@code @Audited}
 * (Envers → {@code shop_staff_aud} mirror), {@code @GeneratedValue(UUID)},
 * {@code @CreationTimestamp}. Column names map to the exact snake_case names in
 * V52. NOTE: the {@code user_directory} sibling is deliberately NOT audited (D-09).
 */
@Entity
@Table(name = "shop_staff")
@Audited
public class ShopStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Keycloak {@code sub} of the granted user. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Granted shop; {@code null} = tenant-wide (GROUP_ADMIN shape). */
    @Column(name = "shop_id")
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShopRole role;

    /**
     * Provenance of this grant (V57): {@link GrantSource#JIT} for an auto-provisioned
     * day-one GROUP_ADMIN row, {@link GrantSource#OPERATOR} for a deliberate operator
     * grant. Load-bearing for the strict-scoping switch (CR-07) — a JIT tenant-wide
     * GROUP_ADMIN is de-honoured under strict-scoping ON while an OPERATOR one is not.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "grant_source", nullable = false, length = 16)
    private GrantSource grantSource;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Keycloak {@code sub} of the GROUP_ADMIN who created the grant; nullable (JIT rows have none). */
    @Column(name = "created_by")
    private UUID createdBy;

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }

    public ShopRole getRole() { return role; }
    public void setRole(ShopRole role) { this.role = role; }

    public GrantSource getGrantSource() { return grantSource; }
    public void setGrantSource(GrantSource grantSource) { this.grantSource = grantSource; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
