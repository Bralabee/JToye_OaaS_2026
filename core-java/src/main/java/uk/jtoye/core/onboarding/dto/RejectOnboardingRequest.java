package uk.jtoye.core.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /onboarding/admin/{id}/reject} (#178 slice 2). The human
 * reason is REQUIRED — a rejection with no recorded rationale is not auditable —
 * and bounded so it cannot blow past the {@code rejection_reason TEXT} column in
 * a single unreadable blob. Bean-validation failures surface as 400 via
 * {@code GlobalExceptionHandler#handleValidationErrors}.
 */
public class RejectOnboardingRequest {

    @NotBlank(message = "reason is required")
    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
