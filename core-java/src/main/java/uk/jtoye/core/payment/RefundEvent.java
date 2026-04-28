package uk.jtoye.core.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain event published when a Refund changes state. Consumed via the
 * {@code order.events} AMQP exchange with routing key {@code order.refunded}.
 *
 * <p>Persisted to {@code payment_event_outbox} (UC-2 LOCKED — single
 * outbox, per-row {@code exchange} column added by V36) and flushed by
 * {@link PaymentEventOutboxFlusher}.
 */
public record RefundEvent(
        UUID refundId,
        UUID orderId,
        UUID tenantId,
        String orderNumber,
        String stripeRefundId,
        long amountPennies,
        String currency,
        RefundEventType type,
        String status,            // RefundStatus.name() — wire format
        String failureReason,
        OffsetDateTime occurredAt
) {
    public enum RefundEventType {
        REFUND_SUCCEEDED,
        REFUND_FAILED,
        REFUND_UPDATED
    }
}
