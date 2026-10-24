package uk.jtoye.core.payment;

import com.stripe.Stripe;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 30 Plan 30-10 (issue #108, Stripe half): verify that configured Stripe
 * connect/read timeouts and maxNetworkRetries are applied to the Stripe SDK
 * static configuration in {@link PaymentService#init()}, announced in a single
 * startup log line with the key redacted, and validated fail-fast on invalid
 * values.
 */
@ExtendWith(OutputCaptureExtension.class)
class StripeClientTimeoutsTest {

    private static final int SDK_DEFAULT_CONNECT_TIMEOUT_MS = 30_000;
    private static final int SDK_DEFAULT_READ_TIMEOUT_MS = 80_000;
    private static final int SDK_DEFAULT_MAX_NETWORK_RETRIES = 2;

    @BeforeEach
    void resetBefore() {
        restoreStripeSdkDefaults();
    }

    @AfterEach
    void resetAfter() {
        restoreStripeSdkDefaults();
    }

    private static void restoreStripeSdkDefaults() {
        PaymentService.STRIPE_INITIALIZED.set(false);
        Stripe.apiKey = null;
        Stripe.setConnectTimeout(SDK_DEFAULT_CONNECT_TIMEOUT_MS);
        Stripe.setReadTimeout(SDK_DEFAULT_READ_TIMEOUT_MS);
        Stripe.setMaxNetworkRetries(SDK_DEFAULT_MAX_NETWORK_RETRIES);
    }

    private static String runtimeTestKey() {
        return String.join("_", "sk", "test", "timeout", "unit", "probe");
    }

    private static PaymentService newService(StripeProperties props) {
        ObjectProvider<MeterRegistry> emptyRegistry = new ObjectProvider<>() {
            @Override public MeterRegistry getObject(Object... args) { return null; }
            @Override public MeterRegistry getIfAvailable() { return null; }
            @Override public MeterRegistry getIfUnique() { return null; }
            @Override public MeterRegistry getObject() { return null; }
        };
        return new PaymentService(props, null, null, null, null, null, null, null, emptyRegistry);
    }

    @Test
    @DisplayName("init applies default 5000/20000/2 to Stripe SDK statics and logs one redacted startup line")
    void init_appliesConfiguredTimeoutsAndLogsStartupLine(CapturedOutput output) {
        StripeProperties props = new StripeProperties();
        String key = runtimeTestKey();
        props.setApiKey(key);

        PaymentService service = newService(props);
        service.init();

        assertThat(Stripe.getConnectTimeout()).isEqualTo(5000);
        assertThat(Stripe.getReadTimeout()).isEqualTo(20000);
        assertThat(Stripe.getMaxNetworkRetries()).isEqualTo(2);
        assertThat(output.getOut() + output.getErr())
                .contains("Stripe client configured (key redacted) connectTimeoutMs=5000 readTimeoutMs=20000 maxNetworkRetries=2")
                .doesNotContain(key);
    }

    @Test
    @DisplayName("init applies custom timeout and retry values to Stripe SDK statics")
    void init_appliesCustomTimeoutsAndRetries(CapturedOutput output) {
        StripeProperties props = new StripeProperties();
        props.setApiKey(runtimeTestKey());
        props.setConnectTimeoutMs(3500);
        props.setReadTimeoutMs(15000);
        props.setMaxNetworkRetries(1);

        PaymentService service = newService(props);
        service.init();

        assertThat(Stripe.getConnectTimeout()).isEqualTo(3500);
        assertThat(Stripe.getReadTimeout()).isEqualTo(15000);
        assertThat(Stripe.getMaxNetworkRetries()).isEqualTo(1);
        assertThat(output.getOut() + output.getErr())
                .contains("Stripe client configured (key redacted) connectTimeoutMs=3500 readTimeoutMs=15000 maxNetworkRetries=1");
    }

    @Test
    @DisplayName("init refuses non-positive readTimeoutMs with IllegalStateException naming stripe.read-timeout-ms")
    void init_refusesNonPositiveReadTimeout() {
        StripeProperties props = new StripeProperties();
        props.setApiKey(runtimeTestKey());
        props.setReadTimeoutMs(0);

        PaymentService service = newService(props);

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe.read-timeout-ms");
    }

    @Test
    @DisplayName("init refuses non-positive connectTimeoutMs with IllegalStateException naming stripe.connect-timeout-ms")
    void init_refusesNonPositiveConnectTimeout() {
        StripeProperties props = new StripeProperties();
        props.setApiKey(runtimeTestKey());
        props.setConnectTimeoutMs(0);

        PaymentService service = newService(props);

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe.connect-timeout-ms");
    }

    @Test
    @DisplayName("init refuses connectTimeoutMs > readTimeoutMs with IllegalStateException naming the property")
    void init_refusesConnectGreaterThanReadTimeout() {
        StripeProperties props = new StripeProperties();
        props.setApiKey(runtimeTestKey());
        props.setConnectTimeoutMs(30_000);
        props.setReadTimeoutMs(20_000);

        PaymentService service = newService(props);

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe.connect-timeout-ms")
                .hasMessageContaining("stripe.read-timeout-ms");
    }

    @Test
    @DisplayName("init refuses out-of-range maxNetworkRetries with IllegalStateException naming stripe.max-network-retries")
    void init_refusesInvalidMaxNetworkRetries() {
        StripeProperties props = new StripeProperties();
        props.setApiKey(runtimeTestKey());
        props.setMaxNetworkRetries(-1);

        PaymentService service = newService(props);

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe.max-network-retries");
    }
}
