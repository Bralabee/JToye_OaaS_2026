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
    @Column(name = "created_at", nullable = false, updatable = false)
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

    /**
     * Owning order for order-settlement ledger rows (Issue #81 BUG 3). Nullable:
     * the admin / manual ledger path has no order. Backed by the partial unique
     * index {@code uq_fin_tx_tenant_order} so exactly one row exists per settled
     * order. Audit-mirrored on {@code financial_transactions_aud} (V40).
     */
    @Column(name = "order_id")
    private UUID orderId;

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

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    /**
     * VAT contained WITHIN this transaction's gross amount.
     *
     * <p>{@code amountPennies} is the VAT-INCLUSIVE gross (Issue #81 BUG 1 fix),
     * so VAT is the net-of-gross fraction, not an add-on. Delegates to
     * {@link VatCalculator#vatFromGross(long, VatRate)} — the single source of
     * truth (HMRC fraction method, round-down). The JPQL summary aggregates in
     * {@link FinancialTransactionRepository} mirror the same arithmetic DB-side.
     */
    public long calculateVatAmount() {
        return VatCalculator.vatFromGross(amountPennies, vatRate);
    }

    /**
     * The VAT-inclusive gross amount. Since {@code amountPennies} is already
     * gross-inclusive, this is simply that amount (Issue #81 BUG 1 reconciliation
     * — VAT is NOT added on top).
     */
    public long getAmountIncludingVat() {
        return amountPennies;
    }

    /**
     * The net (ex-VAT) amount = gross minus the extracted VAT fraction.
     */
    public long getNetAmountPennies() {
        return amountPennies - calculateVatAmount();
    }
}
