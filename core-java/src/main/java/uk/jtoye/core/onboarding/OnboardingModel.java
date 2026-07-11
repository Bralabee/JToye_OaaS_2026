package uk.jtoye.core.onboarding;

/**
 * The commercial model a vendor onboards under. Persisted as
 * {@code @Enumerated(EnumType.STRING)} → the {@code model} VARCHAR + CHECK in V43.
 * Which gates are <em>mandatory</em> differs by model
 * (VENDOR_ONBOARDING_STATE_MODEL.md §3.1).
 */
public enum OnboardingModel {
    /** J'Toye-hosted storefront; J'Toye handles payments (Stripe Connect). */
    MARKETPLACE,

    /** Vendor's own storefront/PSP; J'Toye never touches their funds. */
    WHITE_LABEL
}
