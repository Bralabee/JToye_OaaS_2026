package uk.jtoye.core.payment.dto;

import uk.jtoye.core.tenant.StripeConnectStatus;

/**
 * Result of creating/resuming a tenant's Stripe Express onboarding
 * (issue #102, ADR-0001 Decision 2).
 *
 * @param stripeAccountId the connected account id ({@code acct_...})
 * @param connectStatus   the platform's cached capability view of the account
 * @param onboardingUrl   single-use Stripe-hosted Express onboarding link
 */
public record ConnectAccountDto(String stripeAccountId,
                                StripeConnectStatus connectStatus,
                                String onboardingUrl) {
}
