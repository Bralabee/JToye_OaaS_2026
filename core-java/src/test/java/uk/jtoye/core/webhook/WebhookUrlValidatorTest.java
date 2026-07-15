package uk.jtoye.core.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit proof of the SSRF/HTTPS guard on a vendor-supplied webhook
 * {@code target_url} (COMMS-04, threat T-22-03-01). Hermetic: the accept case
 * uses a public IP literal (93.184.216.34) so no DNS resolution is required, and
 * every reject case is either a scheme violation or an IP literal in a
 * blocked range.
 */
class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator(true);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",          // non-HTTPS scheme
            "http://93.184.216.34",        // non-HTTPS scheme (public IP)
            "https://127.0.0.1",           // loopback
            "https://localhost",           // loopback by name
            "https://169.254.169.254",     // cloud metadata (link-local)
            "https://10.0.0.5",            // RFC1918 10/8
            "https://192.168.1.1",         // RFC1918 192.168/16
            "https://172.16.0.9",          // RFC1918 172.16/12
            "https://[::1]"                // IPv6 loopback
    })
    void rejectsNonHttpsAndPrivateOrLoopbackTargets(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPublicHttpsTarget() {
        // 93.184.216.34 is a public address — an IP literal so no DNS lookup runs.
        assertThatCode(() -> validator.validate("https://93.184.216.34/webhooks/incoming"))
                .doesNotThrowAnyException();
    }

    @Test
    void whenSsrfGuardDisabled_onlyHttpsSchemeIsEnforced() {
        WebhookUrlValidator schemeOnly = new WebhookUrlValidator(false);
        // A private host now passes (SSRF checks off) ...
        assertThatCode(() -> schemeOnly.validate("https://10.0.0.5/hook"))
                .doesNotThrowAnyException();
        // ... but a non-HTTPS scheme is still always rejected.
        assertThatThrownBy(() -> schemeOnly.validate("http://10.0.0.5/hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
