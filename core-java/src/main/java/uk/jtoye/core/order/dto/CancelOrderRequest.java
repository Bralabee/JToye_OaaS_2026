package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.order.CancelReason;

/**
 * Optional body of {@code POST /api/v1/orders/{id}/cancel} (Phase 37-29, D-18).
 *
 * <p>The single cancel has never taken a body and still need not: a request with none cancels the
 * order and records no reason, exactly as before. With a body, {@code reasonCode} is kept on
 * {@code orders.cancel_reason_code} for the vendor's records. It is never sent to the customer: not in
 * the cancellation email, the public tracking payload, the webhook envelope or the order-state event
 * (T-37-75). A code outside {@link CancelReason} is a 400 and nothing changes.
 */
@Schema(description = "Why the order is being cancelled. Optional; kept on the order for the vendor's records and never shown to the customer.")
public record CancelOrderRequest(
        @Schema(description = "Why the order is being cancelled. Omit the body, or send null, to record no reason "
                + "(the pre-existing behaviour).", example = "CUSTOMER_REQUEST")
        CancelReason reasonCode
) {
}
