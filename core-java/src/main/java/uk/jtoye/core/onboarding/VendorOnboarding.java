package uk.jtoye.core.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vendor-onboarding aggregate root — one per tenant (V43
 * {@code UNIQUE(tenant_id)}). Holds the lifecycle {@link OnboardingState}; the
 * per-requirement results live in child {@link VendorOnboardingGate} rows.
 *
 * <p>House conventions mirror {@code product/Product.java}: hand-written
 * accessors (no code-generation annotations on entities), {@code @Audited}
 * (Envers → {@code _aud} mirror), {@code @GeneratedValue(UUID)},
 * {@code @CreationTimestamp}, and a primitive-long {@code @Version} (V43
 * DEFAULT 0 + NOT NULL guarantee no NULLs).
 * Column names map to the exact snake_case names in V43.
 */
@Entity
@Table(name = "vendor_onboarding")
@Audited
public class VendorOnboarding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shop_id")
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OnboardingModel model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OnboardingState status = OnboardingState.DRAFT;

    /** Companies House number; nullable — sole traders have none (gate WAIVED). */
    @Column(name = "company_number", length = 32)
    private String companyNumber;

    /** Marketplace Connect account id; reserved for slice 2 (nullable). */
    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "went_live_at")
    private OffsetDateTime wentLiveAt;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }

    public OnboardingModel getModel() { return model; }
    public void setModel(OnboardingModel model) { this.model = model; }

    public OnboardingState getStatus() { return status; }
    public void setStatus(OnboardingState status) { this.status = status; }

    public String getCompanyNumber() { return companyNumber; }
    public void setCompanyNumber(String companyNumber) { this.companyNumber = companyNumber; }

    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }

    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }

    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }

    public OffsetDateTime getWentLiveAt() { return wentLiveAt; }
    public void setWentLiveAt(OffsetDateTime wentLiveAt) { this.wentLiveAt = wentLiveAt; }

    public OffsetDateTime getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(OffsetDateTime suspendedAt) { this.suspendedAt = suspendedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
