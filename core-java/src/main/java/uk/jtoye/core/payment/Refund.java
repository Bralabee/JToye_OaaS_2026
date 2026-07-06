package uk.jtoye.core.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Refund entity — one row per refund attempt, persisted BEFORE the Stripe
 * call so the {@code idempotency_key} is durable across client retries.
 *
 * <p>Multi-tenant via {@code tenant_id}; protected by {@code refunds_tenant_policy}
 * RLS (V36) using canonical {@code app.current_tenant_id} GUC.
 *
 * <p>Stored-first idempotency strategy (UC-1 LOCKED in Phase 17 CONTEXT):
 * RefundService inserts with {@code status = CREATING} and a server-generated
 * {@code idempotency_key}; that same key is sent to Stripe's
 * {@code Idempotency-Key} header. On retry the row is reused and Stripe's
 * 24h dedup window returns the original response.
 */
@Entity
@Table(
        name = "refunds",
        uniqueConstraints = @UniqueConstraint(
                name = "refunds_idem_unique",
                columnNames = {"tenant_id", "idempotency_key"}
        )
)
@Audited
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "payment_intent_id", nullable = false, length = 255)
    private String paymentIntentId;

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "amount_pennies", nullable = false)
    private Long amountPennies;

    @Column(nullable = false, length = 3)
    private String currency = "gbp";

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private RefundReason reason;

    @Column(name = "reason_note", columnDefinition = "TEXT")
    private String reasonNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundStatus status = RefundStatus.CREATING;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Version
    @Column(nullable = false)
    private Long version;

    public Refund() {
    }

    /**
     * Convenience constructor — pre-Stripe state. Status defaults to CREATING.
     */
    public Refund(UUID tenantId,
                  UUID orderId,
                  String paymentIntentId,
                  String idempotencyKey,
                  Long amountPennies,
                  RefundReason reason,
                  String reasonNote) {
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.paymentIntentId = paymentIntentId;
        this.idempotencyKey = idempotencyKey;
        this.amountPennies = amountPennies;
        this.reason = reason;
        this.reasonNote = reasonNote;
        this.status = RefundStatus.CREATING;
        this.updatedAt = OffsetDateTime.now();
    }

    // Getters & setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public void setPaymentIntentId(String paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
    }

    public String getStripeRefundId() {
        return stripeRefundId;
    }

    public void setStripeRefundId(String stripeRefundId) {
        this.stripeRefundId = stripeRefundId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getAmountPennies() {
        return amountPennies;
    }

    public void setAmountPennies(Long amountPennies) {
        this.amountPennies = amountPennies;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public RefundReason getReason() {
        return reason;
    }

    public void setReason(RefundReason reason) {
        this.reason = reason;
    }

    public String getReasonNote() {
        return reasonNote;
    }

    public void setReasonNote(String reasonNote) {
        this.reasonNote = reasonNote;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public void setStatus(RefundStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(OffsetDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** JPA-managed optimistic-lock version. Null until flushed. */
    public Long getVersion() {
        return version;
    }
}
