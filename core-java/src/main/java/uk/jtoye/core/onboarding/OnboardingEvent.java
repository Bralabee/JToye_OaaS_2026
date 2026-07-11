package uk.jtoye.core.onboarding;

/**
 * Events that drive the vendor-onboarding state machine
 * (VENDOR_ONBOARDING_STATE_MODEL.md §2.3). Mirrors the Order state machine's
 * {@code OrderEvent}. The state machine wiring lands in 18-02; this vocabulary
 * is defined here so the 18-01 data layer and the machine share one source.
 */
public enum OnboardingEvent {
    /** {@code DRAFT → VERIFYING} — vendor submits; kicks off the auto gate chain. */
    SUBMIT,

    /** {@code VERIFYING → PENDING_APPROVAL} — all mandatory gates PASSED/WAIVED. */
    GATES_PASSED,

    /** {@code VERIFYING → ACTION_REQUIRED} — a mandatory gate FAILED / needs input. */
    GATE_FAILED,

    /** {@code ACTION_REQUIRED → VERIFYING} — vendor re-triggers after fixing. */
    RESUBMIT,

    /** {@code PENDING_APPROVAL → APPROVED} — auto (policy) or admin. Guarded. */
    APPROVE,

    /** {@code {VERIFYING,ACTION_REQUIRED,PENDING_APPROVAL} → REJECTED}. */
    REJECT,

    /** {@code APPROVED → LIVE} — vendor publishes. Guarded; flips published=true. */
    GO_LIVE,

    /** {@code LIVE → SUSPENDED} — compliance monitor or admin; flips published=false. */
    SUSPEND,

    /** {@code SUSPENDED → LIVE} — issue resolved. Guarded; re-flips published=true. */
    REINSTATE,

    /** {@code {DRAFT,VERIFYING,ACTION_REQUIRED,PENDING_APPROVAL,APPROVED} → WITHDRAWN}. */
    WITHDRAW
}
