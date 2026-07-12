package uk.jtoye.core.onboarding;

/**
 * A single compliance requirement in the data-driven onboarding gate chain
 * (VENDOR_ONBOARDING_STATE_MODEL.md §1.2). Each concrete gate is a Spring bean;
 * {@link GateChainRunner} auto-collects them all as a {@code List<OnboardingGate>}
 * registry, so 18-03/04/05 add a gate by adding a bean — with NO edit to the
 * runner. This slice ships zero concrete gates; the registry stands ready.
 *
 * <p>{@link #type()} maps the gate to its {@link GateType} row,
 * {@link #mandatory(OnboardingModel)} decides whether it blocks approval,
 * {@link #isAutomatic()} marks gates the async runner evaluates itself (vs.
 * webhook/manual gates that only get materialised), and
 * {@link #evaluate(VendorOnboarding)} performs the automatic check.
 */
public interface OnboardingGate {

    /** The gate row type this bean owns (unique per onboarding). */
    GateType type();

    /** {@code true} if the async runner should call {@link #evaluate} itself. */
    boolean isAutomatic();

    /**
     * {@code true} if this gate must be PASSED/WAIVED before approval for an
     * onboarding under {@code model}. IN-09: which gates are mandatory differs by
     * model (VENDOR_ONBOARDING_STATE_MODEL.md §3.1 — e.g. the slice-2
     * PAYMENTS_CONNECTED gate binds MARKETPLACE only), so the model is part of the
     * contract even though the three first-slice gates bind both models.
     */
    boolean mandatory(OnboardingModel model);

    /** Perform the automatic check for this onboarding, returning its result. */
    GateResult evaluate(VendorOnboarding onboarding);
}
