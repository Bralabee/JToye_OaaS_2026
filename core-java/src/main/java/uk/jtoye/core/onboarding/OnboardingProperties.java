package uk.jtoye.core.onboarding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** Fire {@code PENDING_APPROVAL → APPROVED} without human review? */
    private boolean autoApprove = false;

    /** Minimum published-eligible products for the {@code MENU_MINIMUM} gate. */
    private int menuMinimum = 1;

    private final Fhrs fhrs = new Fhrs();
    private final CompaniesHouse companiesHouse = new CompaniesHouse();

    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }

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
