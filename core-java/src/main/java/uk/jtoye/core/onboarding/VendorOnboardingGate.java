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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One data-driven gate result per compliance requirement, child of
 * {@link VendorOnboarding} (V43 {@code UNIQUE(onboarding_id, gate_type)}). Making
 * each gate a row rather than a code branch means adding/retiring a gate or
 * flipping {@code mandatory} is a data change, not a rewrite
 * (VENDOR_ONBOARDING_STATE_MODEL.md §1.2).
 *
 * <p>{@code evidence} stores the provider snapshot as JSONB via the same
 * {@code @JdbcTypeCode(SqlTypes.JSON)} mapping {@code Product.allergenSpans} uses.
 * Conventions mirror {@code product/Product.java} (hand-written accessors,
 * {@code @Audited}, primitive-long {@code @Version}).
 */
@Entity
@Table(name = "vendor_onboarding_gate")
@Audited
public class VendorOnboardingGate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Denormalised from the parent for RLS (V43 tenant policy predicate). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "onboarding_id", nullable = false)
    private UUID onboardingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_type", nullable = false, length = 32)
    private GateType gateType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GateStatus status = GateStatus.PENDING;

    @Column(nullable = false)
    private boolean mandatory = true;

    /** Provider snapshot (e.g. {@code {"fhrs_rating":4,"scheme":"FHRS"}}). */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> evidence;

    /** Provider key: FHRS establishment id / CH number / Stripe acct / envelope id. */
    @Column(name = "external_ref")
    private String externalRef;

    @Column
    private String reason;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

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

    public UUID getOnboardingId() { return onboardingId; }
    public void setOnboardingId(UUID onboardingId) { this.onboardingId = onboardingId; }

    public GateType getGateType() { return gateType; }
    public void setGateType(GateType gateType) { this.gateType = gateType; }

    public GateStatus getStatus() { return status; }
    public void setStatus(GateStatus status) { this.status = status; }

    public boolean isMandatory() { return mandatory; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }

    public Map<String, Object> getEvidence() { return evidence; }
    public void setEvidence(Map<String, Object> evidence) { this.evidence = evidence; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public OffsetDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(OffsetDateTime checkedAt) { this.checkedAt = checkedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
