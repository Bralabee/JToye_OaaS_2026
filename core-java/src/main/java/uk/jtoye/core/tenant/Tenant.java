package uk.jtoye.core.tenant;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The cross-tenant registry row (table {@code tenants} — deliberately NO RLS;
 * see V2/V48). Issue #102 [P2-11] adds the production lifecycle (status, plan,
 * contact fields, lifecycle timestamps) and the Stripe Connect linkage
 * ({@code stripe_account_id} + {@code stripe_connect_status}) so a tenant can
 * be onboarded, suspended and offboarded via the admin API, and MARKETPLACE
 * orders can be routed as destination charges (ADR-0001 Decision 2).
 *
 * <p>Not {@code @Audited} — posture unchanged from before V48 (no _aud mirror).
 * All new NOT NULL columns default in both SQL (V48) and Java field
 * initialisers, so legacy native inserts ({@code DevTenantService}, V13 seed)
 * and JPA saves are equally safe.
 */
@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TenantStatus status = TenantStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TenantPlan plan = TenantPlan.STANDARD;

    @Column(name = "contact_name", length = 255)
    private String contactName;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    @Column(name = "offboarded_at")
    private OffsetDateTime offboardedAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** Stripe Connect connected-account id ({@code acct_...}); null when unlinked. */
    @Column(name = "stripe_account_id", length = 255)
    private String stripeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stripe_connect_status", nullable = false, length = 16)
    private StripeConnectStatus stripeConnectStatus = StripeConnectStatus.NONE;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TenantStatus getStatus() { return status; }
    public void setStatus(TenantStatus status) { this.status = status; }

    public TenantPlan getPlan() { return plan; }
    public void setPlan(TenantPlan plan) { this.plan = plan; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public OffsetDateTime getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(OffsetDateTime suspendedAt) { this.suspendedAt = suspendedAt; }

    public OffsetDateTime getOffboardedAt() { return offboardedAt; }
    public void setOffboardedAt(OffsetDateTime offboardedAt) { this.offboardedAt = offboardedAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }

    public StripeConnectStatus getStripeConnectStatus() { return stripeConnectStatus; }
    public void setStripeConnectStatus(StripeConnectStatus stripeConnectStatus) {
        this.stripeConnectStatus = stripeConnectStatus;
    }
}
