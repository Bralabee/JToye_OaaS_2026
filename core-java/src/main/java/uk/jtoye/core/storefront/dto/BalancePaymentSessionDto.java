package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ADR-0007 / GAP-GTM-04 (PAY-04): Customer-facing Stripe PaymentIntent session for
 * settling the remaining balance on a deposit-split order after verifying ownership
 * via (orderNumber, email).
 */
@Schema(description = "Stripe PaymentIntent session for paying the remaining balance on a deposit order (GAP-GTM-04).")
public record BalancePaymentSessionDto(
        @Schema(description = "Order number") String orderNumber,
        @Schema(description = "Total order amount in pennies") Long totalAmountPennies,
        @Schema(description = "Upfront deposit amount in pennies") Long depositAmountPennies,
        @Schema(description = "Remaining balance due in pennies") Long balanceDuePennies,
        @Schema(description = "Stripe PaymentIntent ID for the remaining balance") String paymentIntentId,
        @Schema(description = "Stripe PaymentIntent client secret for Stripe Elements") String clientSecret,
        @Schema(description = "Connected Stripe account that owns the PaymentIntent; pass as stripeAccount to "
                + "Stripe.js. Null for platform and destination charges.") String stripeAccountId
) {
}
