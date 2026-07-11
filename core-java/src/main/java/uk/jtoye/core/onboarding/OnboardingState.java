package uk.jtoye.core.onboarding;

/**
 * Vendor-onboarding lifecycle states (VENDOR_ONBOARDING_STATE_MODEL.md §2.2).
 * Persisted as {@code @Enumerated(EnumType.STRING)} → the {@code status} VARCHAR
 * + CHECK in V43; the state machine (18-02) owns the transitions and is the sole
 * writer of {@code Shop.published}.
 *
 * <p>Constant names MUST match the V43 status CHECK strings exactly.
 */
public enum OnboardingState {
    /** Tenant/shop created; vendor building catalogue. Initial state. */
    DRAFT,

    /** Automated gates running (Companies House, FHRS, allergen check). */
    VERIFYING,

    /** A gate failed or needs vendor input; vendor fixes and resubmits. */
    ACTION_REQUIRED,

    /** All mandatory gates green; awaiting final approval. */
    PENDING_APPROVAL,

    /** Eligible to go live; awaiting the vendor's go-live action. */
    APPROVED,

    /** {@code Shop.published = true}; storefront visible. */
    LIVE,

    /** Post-approval compliance breach — delisted, reinstatable. */
    SUSPENDED,

    /** Cannot onboard. Terminal. */
    REJECTED,

    /** Vendor abandoned onboarding. Terminal. */
    WITHDRAWN
}
