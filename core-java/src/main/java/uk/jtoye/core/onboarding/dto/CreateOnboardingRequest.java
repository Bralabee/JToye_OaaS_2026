package uk.jtoye.core.onboarding.dto;

import jakarta.validation.constraints.NotNull;
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
