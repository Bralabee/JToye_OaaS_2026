package uk.jtoye.core.onboarding;

/**
 * The compliance gates in the onboarding chain
 * (VENDOR_ONBOARDING_STATE_MODEL.md §3.1). Persisted as
 * {@code @Enumerated(EnumType.STRING)} → the {@code gate_type} VARCHAR + CHECK in V43.
 *
 * <p>All 8 constants are declared even though only the first-slice three
 * ({@link #BUSINESS_VERIFIED}, {@link #FOOD_HYGIENE_RATING},
 * {@link #ALLERGEN_DATA_COMPLETE}) are evaluated this phase — the remaining five
 * are pre-listed (matching the V43 CHECK) so slice 2 needs no CHECK/enum rewrite.
 * Constant names MUST match the V43 CHECK strings exactly.
 */
public enum GateType {
    /** Company is {@code active} on Companies House (Companies House API, free). */
    BUSINESS_VERIFIED,

    /** FHRS rating ≥ configured min-rating, or FHIS {@code Pass} (FSA FHRS API, free). */
    FOOD_HYGIENE_RATING,

    /** Vendor attests LA food-business registration + FSA ID (manual, spot-checked). */
    FOOD_BUSINESS_REGISTRATION,

    /** Stripe Connect account KYC complete (slice 2). */
    IDENTITY_KYC,

    /** Stripe {@code charges_enabled = true} (slice 2). */
    PAYMENTS_CONNECTED,

    /** E-sign envelope completed (slice 2). */
    AGREEMENT_SIGNED,

    /** Every product carries the allergen data its durability type requires (V41 fields). */
    ALLERGEN_DATA_COMPLETE,

    /** At least N published-eligible products (slice 2). */
    MENU_MINIMUM
}
