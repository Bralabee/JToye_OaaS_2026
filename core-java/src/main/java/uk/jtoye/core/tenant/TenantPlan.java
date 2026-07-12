package uk.jtoye.core.tenant;

/**
 * Commercial plan / rate-limit tier (issue #102 [P2-11]). Persisted as
 * {@code @Enumerated(EnumType.STRING)} → the {@code plan} VARCHAR + CHECK in V48.
 *
 * <p>Vocabulary deliberately matches the tier names sketched in
 * {@code RateLimitInterceptor.getTenantTier} (STANDARD/PREMIUM/INTERNAL) so a
 * future per-tier rate limit can read this column without a rename migration.
 * Billing/metering entities are out of scope for #102 and can follow.
 */
public enum TenantPlan {
    /** Default tier — standard rate limits. */
    STANDARD,

    /** Paid tier — higher rate limits (future enhancement). */
    PREMIUM,

    /** Service-to-service / internal tenants (future enhancement). */
    INTERNAL
}
