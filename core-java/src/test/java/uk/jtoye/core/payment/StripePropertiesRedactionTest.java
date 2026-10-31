package uk.jtoye.core.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

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
        // default apiKey and webhookSecret are empty strings; issue #102 added
        // non-secret Connect fields to the rendering, so assert on the two
        // secret slots rather than the exact full string.
        String rendered = props.toString();
        assertTrue(rendered.contains("apiKey=<unset>"),
                "blank apiKey should render <unset>; got: " + rendered);
        assertTrue(rendered.contains("webhookSecret=<unset>"),
                "blank webhookSecret should render <unset>; got: " + rendered);
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

    @Test
    @DisplayName("toString exposes non-secret timeout and retry settings (#108)")
    void toString_showsTimeoutsAndRetries() {
        StripeProperties props = new StripeProperties();
        String rendered = props.toString();
        assertTrue(rendered.contains("connectTimeoutMs=5000"),
                "toString should expose connectTimeoutMs; got: " + rendered);
        assertTrue(rendered.contains("readTimeoutMs=20000"),
                "toString should expose readTimeoutMs; got: " + rendered);
        assertTrue(rendered.contains("maxNetworkRetries=2"),
                "toString should expose maxNetworkRetries; got: " + rendered);
    }

    @Test
    @DisplayName("toString masks the Connect webhook secret bound from stripe.connect-webhook-secret (PAY-03, T-30-49)")
    void toString_redactsConnectWebhookSecret() {
        // Bound through Spring's Binder, the path the application context takes, so the arm also
        // proves the property name binds at all: an unbound key would leave no slot to mask.
        StripeProperties props = new StripeProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "stripe.connect-webhook-secret", "whsec_connect_secret_value_DO_NOT_LEAK_4242")))
                .bind("stripe", Bindable.ofInstance(props));

        String rendered = props.toString();
        assertFalse(rendered.contains("whsec_connect_secret_value_DO_NOT_LEAK_4242"),
                "toString must not contain the raw Connect webhook secret; got: " + rendered);
        assertTrue(rendered.contains("connectWebhookSecret=***"),
                "toString should mask connectWebhookSecret with ***; got: " + rendered);
        assertTrue(new StripeProperties().toString().contains("connectWebhookSecret=<unset>"),
                "a blank connectWebhookSecret should render <unset>");
    }
}
