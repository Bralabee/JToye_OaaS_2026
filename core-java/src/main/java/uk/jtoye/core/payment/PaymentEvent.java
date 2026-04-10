package uk.jtoye.core.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event published when a payment intent succeeds or fails.
 * Consumers can use this for audit logging, analytics, notifications, or reconciliation.
 */
public record PaymentEvent(
        UUID orderId,
        UUID tenantId,
        String orderNumber,
        String paymentIntentId,
        long amountPennies,
        String currency,
        PaymentEventType type,
        String failureReason,
        OffsetDateTime occurredAt
) {
    public enum PaymentEventType {
        SUCCEEDED,
        FAILED
    }
}
