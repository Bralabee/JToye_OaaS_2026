package uk.jtoye.core.onboarding;

import java.util.Map;

/**
 * Immutable outcome of an {@link OnboardingGate#evaluate(VendorOnboarding)} call.
 * {@link GateChainRunner} copies these fields onto the persisted
 * {@link VendorOnboardingGate} row (status/evidence/external_ref/reason + checked_at).
 * The static factories name the four outcomes a gate can report.
 *
 * @param status      the resulting gate status
 * @param evidence    provider snapshot stored as JSONB (may be {@code null})
 * @param externalRef provider key (FHRS id / CH number / Stripe acct), may be {@code null}
 * @param reason      human-readable reason, chiefly for FAILED / MANUAL_REVIEW / WAIVED
 */
public record GateResult(GateStatus status, Map<String, Object> evidence, String externalRef, String reason) {

    /** The automatic check passed; carries the provider evidence + key. */
    public static GateResult passed(Map<String, Object> evidence, String externalRef) {
        return new GateResult(GateStatus.PASSED, evidence, externalRef, null);
    }

    /** The check failed hard — vendor action required. */
    public static GateResult failed(String reason) {
        return new GateResult(GateStatus.FAILED, null, null, reason);
    }

    /** The check is inconclusive and needs a human decision. */
    public static GateResult manualReview(String reason) {
        return new GateResult(GateStatus.MANUAL_REVIEW, null, null, reason);
    }

    /** The requirement does not apply (e.g. a sole trader has no company number). */
    public static GateResult waived(String reason) {
        return new GateResult(GateStatus.WAIVED, null, null, reason);
    }
}
