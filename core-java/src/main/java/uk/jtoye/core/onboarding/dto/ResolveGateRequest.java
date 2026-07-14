package uk.jtoye.core.onboarding.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.onboarding.GateDecision;

/**
 * Body of {@code POST /onboarding/admin/{id}/gates/{gateType}/resolve} (ONBD-03 /
 * D-01). The {@code decision} is a REQUIRED bounded {@link GateDecision} enum
 * (ASVS V5 — an unknown value is rejected at binding). The {@code reason} is
 * bounded like {@link RejectOnboardingRequest} but is NOT {@code @NotBlank} here:
 * it is optional for PASS/WAIVE and required only for FAIL, a rule the service
 * enforces (A5) so a blank FAIL reason surfaces as a 400 while an omitted PASS
 * reason is accepted. Bean-validation failures surface as 400 via
 * {@code GlobalExceptionHandler#handleValidationErrors}.
 */
public class ResolveGateRequest {

    @NotNull(message = "decision is required")
    private GateDecision decision;

    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;

    public GateDecision getDecision() { return decision; }
    public void setDecision(GateDecision decision) { this.decision = decision; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
