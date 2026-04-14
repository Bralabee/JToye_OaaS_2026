package uk.jtoye.core.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Fix #10: StripeProperties.toString() MUST NOT contain
 * the raw Stripe API key or webhook secret, even when those fields are set.
 *
 * <p>A plain Lombok-style toString (or the implicit Object.toString inherited
 * from an earlier refactor) would happily log a live key via
 * {@code log.info("config={}", stripeProperties)}. The custom toString added
 * in this fix redacts both fields.
 */
class StripePropertiesRedactionTest {

    @Test
    @DisplayName("toString masks a configured Stripe API key")
    void toString_redactsApiKey() {
        StripeProperties props = new StripeProperties();
        props.setApiKey("sk_live_EXTREMELY_SECRET_DO_NOT_LEAK_123456");
        props.setWebhookSecret("whsec_another_secret_value_987654");

        String rendered = props.toString();

        assertFalse(rendered.contains("sk_live_EXTREMELY_SECRET_DO_NOT_LEAK_123456"),
                "toString must not contain the raw API key; got: " + rendered);
        assertFalse(rendered.contains("whsec_another_secret_value_987654"),
                "toString must not contain the raw webhook secret; got: " + rendered);
        assertTrue(rendered.contains("apiKey=***"),
                "toString should mask apiKey with ***; got: " + rendered);
        assertTrue(rendered.contains("webhookSecret=***"),
                "toString should mask webhookSecret with ***; got: " + rendered);
    }

    @Test
    @DisplayName("toString shows <unset> for blank fields")
    void toString_showsUnsetForBlankFields() {
        StripeProperties props = new StripeProperties();
        // default apiKey and webhookSecret are empty strings
        assertEquals("StripeProperties(apiKey=<unset>, webhookSecret=<unset>)", props.toString());
    }

    @Test
    @DisplayName("toString redacts partial state (apiKey set, webhook unset)")
    void toString_redactsPartial() {
        StripeProperties props = new StripeProperties();
        props.setApiKey("sk_test_partial_key");

        String rendered = props.toString();
        assertFalse(rendered.contains("sk_test_partial_key"));
        assertTrue(rendered.contains("apiKey=***"));
        assertTrue(rendered.contains("webhookSecret=<unset>"));
    }
}
