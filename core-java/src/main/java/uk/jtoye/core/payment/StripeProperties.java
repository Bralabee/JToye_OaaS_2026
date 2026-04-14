package uk.jtoye.core.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String apiKey = "";
    private String webhookSecret = "";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    /**
     * Redacted toString so accidental logger calls (log.info("config={}", stripeProps))
     * cannot leak the live Stripe API key or webhook secret. Both fields are
     * masked while still showing whether they are set — useful for diagnostics.
     */
    @Override
    public String toString() {
        return "StripeProperties(apiKey=" + mask(apiKey)
                + ", webhookSecret=" + mask(webhookSecret) + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }
}
