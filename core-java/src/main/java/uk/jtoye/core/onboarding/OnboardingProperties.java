package uk.jtoye.core.onboarding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Grouped configuration for vendor onboarding, bound from the {@code onboarding.*}
 * keys in {@code application.yml} (VENDOR_ONBOARDING_STATE_MODEL.md §6). No literals
 * live in code paths — thresholds and endpoints are injected here so a value can be
 * overridden per environment via {@code ${ENV:default}} without a redeploy.
 *
 * <p>Mirrors {@code payment/StripeProperties}: nested config objects, secret fields
 * default to empty (never null), and a redacted {@link #toString()} masks the
 * Companies House API key so an accidental {@code log.info("{}", props)} cannot leak it.
 * The Java defaults ({@code fhrs.min-rating=2}, {@code fhrs.api-version="2"}) match the
 * design contract so a missing yaml value never silently picks a different threshold.
 */
@Component
@ConfigurationProperties(prefix = "onboarding")
public class OnboardingProperties {

    /**
     * GLOBAL force-on override for auto-approval. When {@code true}, EVERY model
     * auto-approves once its mandatory gates are green (skips the human review step,
     * never the APPROVE guard). When {@code false} (the default), per-model policy in
     * {@link #autoApproveModels} applies — so WHITE_LABEL still auto-approves while
     * MARKETPLACE parks at {@code PENDING_APPROVAL} for a human. See ADR-0001 / #178.
     */
    private boolean autoApprove = false;

    /**
     * Per-model auto-approve policy (#178 item 1). A model in this list auto-approves
     * on green gates even when the global {@link #autoApprove} force-on flag is false;
     * a model NOT in this list always requires human approval. Default {@code [WHITE_LABEL]}:
     * white-label vendors run their own storefront/PSP so a fully-green application needs
     * no human gate, while MARKETPLACE stays manual until the admin approve/reject queue
     * ships (#178 slice 2). Mutable {@link ArrayList} so Spring relaxed-binding of a
     * comma-separated {@code ONBOARDING_AUTO_APPROVE_MODELS} value can rebind it.
     */
    private List<OnboardingModel> autoApproveModels =
            new ArrayList<>(List.of(OnboardingModel.WHITE_LABEL));

    /** Minimum published-eligible products for the {@code MENU_MINIMUM} gate. */
    private int menuMinimum = 1;

    private final Fhrs fhrs = new Fhrs();
    private final CompaniesHouse companiesHouse = new CompaniesHouse();

    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }

    public List<OnboardingModel> getAutoApproveModels() { return autoApproveModels; }
    public void setAutoApproveModels(List<OnboardingModel> autoApproveModels) {
        this.autoApproveModels = autoApproveModels;
    }

    /**
     * Per-model auto-approve decision. Deliberately a SEPARATE public method that does
     * NOT read {@link #isAutoApprove()} internally: {@code GateChainRunner} evaluates the
     * global override and this per-model check as two distinct external calls on the bean,
     * so a Mockito {@code @SpyBean} stub on {@code isAutoApprove()} still governs the
     * global-force path in the Phase 18 E2E test (self-invocations bypass a spy).
     *
     * @return {@code true} iff {@code model} is non-null and configured for auto-approval.
     */
    public boolean autoApprovesModel(OnboardingModel model) {
        return model != null && autoApproveModels.contains(model);
    }

    public int getMenuMinimum() { return menuMinimum; }
    public void setMenuMinimum(int menuMinimum) { this.menuMinimum = menuMinimum; }

    public Fhrs getFhrs() { return fhrs; }
    public CompaniesHouse getCompaniesHouse() { return companiesHouse; }

    /** FSA Food Hygiene Rating Scheme API settings (free, no key; needs x-api-version). */
    public static class Fhrs {
        private String baseUrl;
        /** DECIDED = 2 (Deliveroo/Uber parity); env-overridable via FHRS_MIN_RATING. */
        private int minRating = 2;
        /** Mandatory {@code x-api-version} header — omit it and the API returns no data. */
        private String apiVersion = "2";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getMinRating() { return minRating; }
        public void setMinRating(int minRating) { this.minRating = minRating; }

        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }

    /** Companies House API settings (free; HTTP Basic with the API key as username). */
    public static class CompaniesHouse {
        private String baseUrl;
        /** Secret defaults empty (never null) — house convention (StripeProperties). */
        private String apiKey = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    /**
     * Redacted toString so an accidental logger call cannot leak the Companies
     * House API key. Mirrors {@code StripeProperties.mask}.
     */
    @Override
    public String toString() {
        return "OnboardingProperties(autoApprove=" + autoApprove
                + ", autoApproveModels=" + autoApproveModels
                + ", menuMinimum=" + menuMinimum
                + ", fhrs.baseUrl=" + fhrs.baseUrl
                + ", fhrs.minRating=" + fhrs.minRating
                + ", fhrs.apiVersion=" + fhrs.apiVersion
                + ", companiesHouse.baseUrl=" + companiesHouse.baseUrl
                + ", companiesHouse.apiKey=" + mask(companiesHouse.apiKey) + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }
}
