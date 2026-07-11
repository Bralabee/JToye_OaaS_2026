package uk.jtoye.core.onboarding;

/**
 * Per-gate evaluation outcome (VENDOR_ONBOARDING_STATE_MODEL.md §3.2). Persisted
 * as {@code @Enumerated(EnumType.STRING)} → the gate {@code status} VARCHAR + CHECK
 * in V43. Constant names MUST match the V43 CHECK strings exactly.
 */
public enum GateStatus {
    /** Not yet evaluated. Initial state. */
    PENDING,

    /** Requirement satisfied. */
    PASSED,

    /** Requirement not met (e.g. rating below threshold). */
    FAILED,

    /** Fuzzy/no match or attestation — needs a human decision (never hard-fails a vendor). */
    MANUAL_REVIEW,

    /** Deliberately waived (e.g. sole trader with no Companies House record). */
    WAIVED
}
