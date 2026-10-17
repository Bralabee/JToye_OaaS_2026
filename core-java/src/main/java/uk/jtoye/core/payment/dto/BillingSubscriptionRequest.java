package uk.jtoye.core.payment.dto;

import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.tenant.BillingPlanMode;

/**
 * Request payload to subscribe or switch a tenant's recurring billing tier
 * ({@code GAP-GTM-05}, {@code PAY-02}, {@code D-B9}).
 */
public record BillingSubscriptionRequest(
        @NotNull(message = "planMode is required")
        BillingPlanMode planMode,
        String paymentMethodId,
        String testClockId
) {
}
