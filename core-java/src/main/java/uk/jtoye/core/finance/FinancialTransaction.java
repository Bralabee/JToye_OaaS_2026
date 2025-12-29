package uk.jtoye.core.finance;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Financial transaction entity for tracking monetary operations.
 * Includes VAT handling for tax compliance and audit trails.
 *
 * All transactions are tenant-scoped via RLS policies and audited via Envers.
 *
 * Use cases:
 * - Payment recording (order payments, refunds)
 * - Financial reconciliation
 * - VAT reporting
 * - Audit compliance
 */
@Entity
@Table(name = "financial_transactions")
@Audited
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Transaction amount in pennies/cents.
     * Using integer representation avoids floating-point precision issues.
     * Positive = income/credit, Negative = expense/debit
     */
    @Column(name = "amount_pennies", nullable = false)
    private Long amountPennies;

    /**
     * VAT rate category applied to this transaction.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_rate", nullable = false)
    private VatRate vatRate;

    /**
     * Optional reference (e.g., order number, invoice ID, payment reference).
     */
    @Column(name = "reference")
    private String reference;

    // Constructors

    public FinancialTransaction() {
    }

    public FinancialTransaction(Long amountPennies, VatRate vatRate, String reference) {
        this.amountPennies = amountPennies;
        this.vatRate = vatRate;
        this.reference = reference;
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

    public Long getAmountPennies() {
        return amountPennies;
    }

    public void setAmountPennies(Long amountPennies) {
        this.amountPennies = amountPennies;
    }

    public VatRate getVatRate() {
        return vatRate;
    }

    public void setVatRate(VatRate vatRate) {
        this.vatRate = vatRate;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    /**
     * Calculate VAT amount based on UK rates.
     * Note: Rates should be configurable per jurisdiction in production.
     */
    public long calculateVatAmount() {
        return switch (vatRate) {
            case ZERO, EXEMPT -> 0L;
            case REDUCED -> (amountPennies * 5) / 100;  // 5%
            case STANDARD -> (amountPennies * 20) / 100; // 20%
        };
    }

    /**
     * Get amount including VAT.
     */
    public long getAmountIncludingVat() {
        return amountPennies + calculateVatAmount();
    }
}
