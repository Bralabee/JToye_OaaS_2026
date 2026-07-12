package uk.jtoye.core.tenant;

/**
 * Platform-side cached view of a tenant's Stripe Connect connected-account
 * capability state (issue #102, ADR-0001 Decision 2). Persisted as
 * {@code @Enumerated(EnumType.STRING)} → the {@code stripe_connect_status}
 * VARCHAR + CHECK in V48. Driven by the Stripe {@code account.updated} webhook
 * (see {@code StripeConnectService#handleAccountUpdated}).
 *
 * <p>Only {@link #ENABLED} makes a MARKETPLACE tenant eligible for
 * destination-charge routing — creating a destination charge against an
 * account whose charges are not enabled fails at Stripe.
 */
public enum StripeConnectStatus {
    /** No connected account linked. */
    NONE,

    /** Account created; Express onboarding / KYC not complete yet. */
    PENDING,

    /** {@code charges_enabled=true} — destination charges may route here. */
    ENABLED,

    /** Stripe disabled the account ({@code requirements.disabled_reason} set). */
    DISABLED
}
