package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * #868: what a customer's cancel did. Only statuses and amounts, never anything the order holds about
 * the customer.
 */
@Schema(description = "The outcome of a customer cancelling their own order.")
public record CustomerCancelResult(
        @Schema(example = "ORD-20261104-0001") String orderNumber,
        @Schema(description = "The order's status after the cancel: always CANCELLED.", example = "CANCELLED")
        String status,
        @Schema(description = "The order's payment status after the cancel: REFUNDED when a card payment was returned, "
                + "otherwise as it was (NONE for pay-on-delivery).", example = "REFUNDED")
        String paymentStatus,
        @Schema(description = "True when a card payment was refunded as part of this cancel.") boolean refunded,
        @Schema(description = "The amount refunded to the card in pennies; null when nothing was refunded.")
        Long refundAmountPennies) {
}
