package uk.jtoye.core.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * The tenant's legal entity: the seller customers buy from (#789, D-10/D-11). One per tenant
 * (V71 {@code UNIQUE(tenant_id)}), so a multi-site owner enters it once and every shop of the
 * tenant resolves to this row. Per-shop contact details stay on {@code Shop.phone} /
 * {@code Shop.email}; the company number stays on {@link VendorOnboarding#getCompanyNumber()}.
 *
 * <p>House conventions mirror {@link VendorOnboarding}: hand-written accessors,
 * {@code @Audited} (Envers writes {@code trader_identity_aud}, so who published which identity
 * is on record), {@code @GeneratedValue(UUID)}, {@code @CreationTimestamp} and a primitive-long
 * {@code @Version}. Column names are V71's exact snake_case names.
 */
@Entity
@Table(name = "trader_identity")
@Audited
public class TraderIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 16)
    private TraderEntityType entityType;

    @Column(name = "address_line1", nullable = false, length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "address_city", nullable = false, length = 120)
    private String addressCity;

    @Column(name = "address_postcode", nullable = false, length = 12)
    private String addressPostcode;

    /** NULL = no VAT registration declared. Upper-case, no spaces. */
    @Column(name = "vat_number", length = 16)
    private String vatNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public TraderEntityType getEntityType() { return entityType; }
    public void setEntityType(TraderEntityType entityType) { this.entityType = entityType; }

    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }

    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }

    public String getAddressCity() { return addressCity; }
    public void setAddressCity(String addressCity) { this.addressCity = addressCity; }

    public String getAddressPostcode() { return addressPostcode; }
    public void setAddressPostcode(String addressPostcode) { this.addressPostcode = addressPostcode; }

    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public long getVersion() { return version; }
}
