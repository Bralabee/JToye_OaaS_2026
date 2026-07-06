package uk.jtoye.core.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.payment.RefundReason;

/**
 * Request payload for {@code POST /api/v1/orders/{id}/refund} (Plan 17-03).
 *
 * @param amountPennies optional — null means refund the full remaining balance
 *                      ({@code order.totalAmountPennies − sumLiveAmountByOrderId}).
 *                      Must be positive when supplied; server enforces
 *                      {@code amountPennies ≤ remaining} BEFORE calling Stripe.
 * @param reason        required — drives Stripe's {@code reason} field.
 * @param note          optional vendor-supplied note, persisted on the
 *                      Refund row but NOT forwarded to Stripe metadata
 *                      (free-text, not part of Stripe's enum).
 */
public record CreateRefundRequest(
        @Positive Long amountPennies,
        @NotNull RefundReason reason,
        @Size(max = 500) String note
) {
}
