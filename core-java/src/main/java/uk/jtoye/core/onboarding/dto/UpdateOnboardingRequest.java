package uk.jtoye.core.onboarding.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /onboarding/company-number} (ONBD-02). Carries ONLY
 * the {@code companyNumber}: like {@link CreateOnboardingRequest} it deliberately
 * has NO {@code tenantId}, since the tenant is resolved server-side from the
 * caller's context ({@code CurrentTenant.require()}) and never read from the body.
 *
 * <p>The {@code @Size(max = 32)} + {@code @Pattern} rule is copied verbatim from
 * {@link CreateOnboardingRequest} so an update re-validates identically to create:
 * an over-length / garbage value is a clean 400 at the boundary before it can reach
 * the Companies House client (threat T-21-01-01), and a blank/whitespace value is a
 * sole trader — the service normalises it to null, matching create semantics.
 */
public class UpdateOnboardingRequest {

    @Size(max = 32, message = "companyNumber must be at most 32 characters")
    @Pattern(regexp = "^\\s*([A-Za-z0-9]{2,10})?\\s*$",
            message = "companyNumber must be a valid Companies House number (2-10 letters or digits)")
    private String companyNumber;

    public String getCompanyNumber() {
        return companyNumber;
    }

    public void setCompanyNumber(String companyNumber) {
        this.companyNumber = companyNumber;
    }
}
