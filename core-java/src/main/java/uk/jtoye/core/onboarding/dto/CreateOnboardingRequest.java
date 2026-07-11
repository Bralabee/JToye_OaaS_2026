package uk.jtoye.core.onboarding.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.onboarding.OnboardingModel;

import java.util.UUID;

/**
 * Request body for {@code POST /onboarding}. Deliberately carries NO
 * {@code tenantId}: the tenant is resolved server-side from the caller's context
 * ({@code CurrentTenant.require()}), so a client cannot create an onboarding for
 * another tenant (threat T-18-02-S). {@code companyNumber} is optional — sole
 * traders have no Companies House registration.
 */
public class CreateOnboardingRequest {

    @NotNull(message = "model is required")
    private OnboardingModel model;

    @NotNull(message = "shopId is required")
    private UUID shopId;

    // WR-02: validate at the boundary so an over-length / garbage value is a clean 400
    // (not a misleading 409 "Duplicate Entry" from the V43 VARCHAR(32) overflow) and
    // garbage never reaches the Companies House API. Optional: a blank/whitespace value
    // (or an omitted one) is a sole trader — the pattern's empty branch permits it, and
    // the service normalises blank -> null. Companies House numbers are 8 chars
    // (8 digits, or 2 letters + 6 digits); the 2-10 alphanumeric bound is deliberately
    // lenient.
    @Size(max = 32, message = "companyNumber must be at most 32 characters")
    @Pattern(regexp = "^\\s*([A-Za-z0-9]{2,10})?\\s*$",
            message = "companyNumber must be a valid Companies House number (2-10 letters or digits)")
    private String companyNumber;

    public OnboardingModel getModel() {
        return model;
    }

    public void setModel(OnboardingModel model) {
        this.model = model;
    }

    public UUID getShopId() {
        return shopId;
    }

    public void setShopId(UUID shopId) {
        this.shopId = shopId;
    }

    public String getCompanyNumber() {
        return companyNumber;
    }

    public void setCompanyNumber(String companyNumber) {
        this.companyNumber = companyNumber;
    }
}
