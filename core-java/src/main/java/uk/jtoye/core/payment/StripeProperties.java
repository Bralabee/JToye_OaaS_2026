package uk.jtoye.core.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String apiKey = "";
    private String webhookSecret = "";

    /**
     * Presentment/settlement currency for PaymentIntents (issue #102 — the
     * former hardcoded {@code "gbp"} literal, now env-injected via
     * {@code STRIPE_CURRENCY}). Lowercase ISO 4217, per the Stripe API.
     */
    private String currency = "gbp";

    /**
     * Platform application fee in basis points (1 bps = 0.01%), applied to
     * MARKETPLACE destination charges only (ADR-0001 Decision 2). Fee in
     * pennies = amount * bps / 10_000, floored. {@code 0} (the default) means
     * no application fee is set — the fee is an explicit business decision
     * injected via {@code STRIPE_PLATFORM_FEE_BPS}, never a code literal.
     */
    private int platformFeeBps = 0;

    private final Connect connect = new Connect();

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getPlatformFeeBps() { return platformFeeBps; }
    public void setPlatformFeeBps(int platformFeeBps) { this.platformFeeBps = platformFeeBps; }

    public Connect getConnect() { return connect; }

    /** Stripe Connect (connected-account) settings — issue #102, ADR-0001 Decision 2. */
    public static class Connect {
        /** Country for new Express accounts (UK platform). */
        private String country = "GB";
        /** Where Stripe sends the vendor after completing Express onboarding. */
        private String returnUrl = "";
        /** Where Stripe sends the vendor when an onboarding link expires. */
        private String refreshUrl = "";

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }

        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }

        public String getRefreshUrl() { return refreshUrl; }
        public void setRefreshUrl(String refreshUrl) { this.refreshUrl = refreshUrl; }
    }

    /**
     * Redacted toString so accidental logger calls (log.info("config={}", stripeProps))
     * cannot leak the live Stripe API key or webhook secret. Both fields are
     * masked while still showing whether they are set — useful for diagnostics.
     */
    @Override
    public String toString() {
        return "StripeProperties(apiKey=" + mask(apiKey)
                + ", webhookSecret=" + mask(webhookSecret)
                + ", currency=" + currency
                + ", platformFeeBps=" + platformFeeBps
                + ", connect.country=" + connect.country
                + ", connect.returnUrl=" + connect.returnUrl
                + ", connect.refreshUrl=" + connect.refreshUrl + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }
}
