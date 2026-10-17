package uk.jtoye.core.payment.dto;

import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.tenant.SubscriptionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Current recurring billing tier, Stripe subscription linkage, and effective
 * application-fee basis points for a tenant ({@code GAP-GTM-05}, {@code PAY-02},
 * {@code B8}, {@code B9}, {@code D-B9}).
 */
public record BillingSubscriptionDto(
        UUID tenantId,
        BillingPlanMode billingPlanMode,
        SubscriptionStatus subscriptionStatus,
        String stripeCustomerId,
        String stripeSubscriptionId,
        String latestStripeInvoiceId,
        long monthlyAmountPennies,
        int effectiveFeeBps,
        long monthlyFeeCapPennies,
        long monthlyFeeChargedPennies,
        boolean walkSetupCompleted,
        boolean cancelAtPeriodEnd,
        OffsetDateTime currentPeriodEnd
) {
}
