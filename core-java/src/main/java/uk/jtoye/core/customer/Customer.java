package uk.jtoye.core.customer;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Customer entity for managing customer information.
 * Enables customer relationship management, order history, and preferences.
 *
 * All customers are tenant-scoped via RLS policies and audited via Envers.
 *
 * Design decisions:
 * - Separate entity from Order (normalized design)
 * - Email is unique per tenant (customers can't have duplicate emails)
 * - Phone is optional (some customers prefer email-only)
 * - Allergen preferences tracked for safety (food allergy compliance)
 */
@Entity
@Table(name = "customers", uniqueConstraints = {
    @UniqueConstraint(name = "uq_customers_tenant_email", columnNames = {"tenant_id", "email"})
})
@Audited
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    /**
     * Allergen preferences/restrictions as bitmask.
     * Matches allergen_mask pattern from Product entity.
     * Enables allergen-aware ordering and safety warnings.
     */
    @Column(name = "allergen_restrictions")
    private Integer allergenRestrictions = 0;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // Constructors

    public Customer() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Customer(String name, String email) {
        this();
        this.name = name;
        this.email = email;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
        this.updatedAt = OffsetDateTime.now();
    }

    public Integer getAllergenRestrictions() {
        return allergenRestrictions;
    }

    public void setAllergenRestrictions(Integer allergenRestrictions) {
        this.allergenRestrictions = allergenRestrictions;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Check if customer has specific allergen restriction.
     * @param allergenBit Bit position for allergen (0-31)
     */
    public boolean hasAllergenRestriction(int allergenBit) {
        if (allergenRestrictions == null) return false;
        return (allergenRestrictions & (1 << allergenBit)) != 0;
    }

    /**
     * Add allergen restriction.
     * @param allergenBit Bit position for allergen (0-31)
     */
    public void addAllergenRestriction(int allergenBit) {
        if (allergenRestrictions == null) {
            allergenRestrictions = 0;
        }
        allergenRestrictions |= (1 << allergenBit);
        this.updatedAt = OffsetDateTime.now();
    }
}
