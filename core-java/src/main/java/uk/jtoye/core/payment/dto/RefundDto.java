package uk.jtoye.core.payment.dto;

import uk.jtoye.core.payment.RefundReason;
import uk.jtoye.core.payment.RefundStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only DTO for Refund entities.
 *
 * <p>Wire format mirrors the entity 1:1 except {@code paymentIntentId} is
 * omitted (internal Stripe reference, not vendor-facing) and {@code version}
 * is omitted (JPA-managed, not part of the API contract).
 */
public record RefundDto(
        UUID id,
        UUID tenantId,
        UUID orderId,
        String stripeRefundId,
        String idempotencyKey,
        Long amountPennies,
        String currency,
        RefundReason reason,
        String reasonNote,
        RefundStatus status,
        String failureReason,
        OffsetDateTime requestedAt,
        OffsetDateTime updatedAt
) {
}
