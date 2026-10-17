package uk.jtoye.core.tenant;

/**
 * Cached Stripe subscription status on {@link Tenant} (V88, {@code PAY-02} / {@code GAP-GTM-05}).
 */
public enum SubscriptionStatus {
    NONE,
    ACTIVE,
    TRIALING,
    PAST_DUE,
    CANCELED
}
