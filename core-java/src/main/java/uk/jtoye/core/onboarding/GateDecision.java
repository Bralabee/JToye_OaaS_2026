package uk.jtoye.core.onboarding;

/**
 * An admin's decision when resolving a stuck onboarding gate (ONBD-03 / D-01),
 * the body of {@code POST /onboarding/admin/{id}/gates/{gateType}/resolve}. A
 * bounded enum is inherently input-validated (ASVS V5) — an unknown value is
 * rejected at binding. Each decision maps to the {@link GateStatus} the gate row
 * is overridden to; the state machine then advances via the existing recompute
 * (this decision NEVER writes {@code status}/{@code Shop.published} directly).
 */
public enum GateDecision {
    /** The requirement is satisfied — override the gate row to {@link GateStatus#PASSED}. */
    PASS,

    /** The requirement is deliberately waived — override to {@link GateStatus#WAIVED}. */
    WAIVE,

    /** The requirement is not met — override to {@link GateStatus#FAILED} (a reason is required). */
    FAIL
}
