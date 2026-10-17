package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * ADR-0007 / GAP-GTM-04 (PAY-04): Vendor-facing shareable remaining-balance payment link
 * and pre-formatted WhatsApp message for a deposit-split order.
 */
@Schema(description = "Shareable remaining-balance payment link and WhatsApp message for a deposit order (GAP-GTM-04).")
public record BalancePaymentLinkDto(
        @Schema(description = "Order ID") UUID orderId,
        @Schema(description = "Order number") String orderNumber,
        @Schema(description = "Total order amount in pennies") Long totalAmountPennies,
        @Schema(description = "Upfront deposit already captured in pennies") Long depositAmountPennies,
        @Schema(description = "Remaining balance due in pennies") Long balanceDuePennies,
        @Schema(description = "Relative storefront tracking URL path that opens the remaining-balance checkout") String trackingPaymentPath,
        @Schema(description = "Pre-formatted message ready to send to the customer via WhatsApp, SMS, or email") String whatsappShareText,
        @Schema(description = "Encoded https://wa.me/?text=... deep-link for one-tap vendor sharing") String waMeUrl
) {
}
