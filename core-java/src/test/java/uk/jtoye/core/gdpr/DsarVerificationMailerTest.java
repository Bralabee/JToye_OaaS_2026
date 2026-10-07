package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The verification email's link (#839, D-04, 31.1-20).
 *
 * <p>On the canonical compose runtime the emailed link pointed at {@code localhost:8080}, where
 * nothing listens, and where it did reach the API it rendered raw JSON. So no non-technical
 * requester could ever confirm a request. The link now opens the web app's
 * {@code /data-request/confirm} page with the token in the URL FRAGMENT: a browser never sends a
 * fragment to a server, so no request line, access log or APM span ever carries the token, and the
 * page spends it only on an explicit press (a mail scanner that fetches the link confirms nothing).
 *
 * <p>Plain unit test over a mocked {@link JavaMailSender}; the captured message is the evidence.
 */
class DsarVerificationMailerTest {

    private static final String BASE = "http://localhost:3000/data-request/confirm";

    private JavaMailSender mailSender;
    private DsarVerificationMailer mailer;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailer = new DsarVerificationMailer(mailSender);
        ReflectionTestUtils.setField(mailer, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(mailer, "emailEnabled", true);
        ReflectionTestUtils.setField(mailer, "verifyBaseUrl", BASE);
    }

    private SimpleMailMessage send(String token, DsarRequest.RequestType type) {
        mailer.sendVerification("subject@example.test", token, type, 168);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void theLinkOpensTheWebConfirmPageWithTheTokenInTheFragment() {
        SimpleMailMessage message = send("abc-_1", DsarRequest.RequestType.ERASURE);

        assertThat(message.getText())
                .as("the link is the web confirm page with the token in the fragment")
                .contains("http://localhost:3000/data-request/confirm#token=abc-_1");
    }

    @Test
    void theTokenNeverTravelsInAQueryString() {
        SimpleMailMessage message = send("abc-_1", DsarRequest.RequestType.ACCESS);

        assertThat(message.getText())
                .as("a query-string token is captured by every intermediary on the path (#278)")
                .doesNotContain("?token=")
                .doesNotContain("&token=");
    }

    /**
     * The in-code default is what a runtime gets when it supplies no DSAR_VERIFY_BASE_URL. Read from
     * the field's own {@code @Value} so this cannot pass by the test injecting its own base (the
     * first version of this arm did exactly that and was green on the API-pointing tree).
     */
    @Test
    void theInCodeDefaultIsTheWebConfirmPageNotTheApi() throws Exception {
        Value value = DsarVerificationMailer.class.getDeclaredField("verifyBaseUrl").getAnnotation(Value.class);
        assertThat(value).as("verifyBaseUrl is config-injected").isNotNull();
        String expression = value.value();
        String fallback = expression.substring(expression.indexOf(':') + 1, expression.length() - 1);

        assertThat(fallback)
                .as("the API answers JSON, which a requester cannot read (#839)")
                .doesNotContain("/api/")
                .isEqualTo("http://localhost:3000/data-request/confirm");

        ReflectionTestUtils.setField(mailer, "verifyBaseUrl", fallback);
        SimpleMailMessage message = send("abc-_1", DsarRequest.RequestType.ERASURE);
        assertThat(message.getText()).contains(fallback + "#token=abc-_1").doesNotContain("/api/");
    }

    @Test
    void aBase64UrlTokenIsCarriedVerbatim() {
        // The intake mints 32 SecureRandom bytes as unpadded base64url, so the alphabet is
        // A-Z a-z 0-9 - _ and nothing needs escaping in a fragment.
        String token = "Zm9vYmFy-_0123456789abcdefghijABCDEFGHIJxyz";
        SimpleMailMessage message = send(token, DsarRequest.RequestType.ERASURE);

        assertThat(message.getText()).contains(BASE + "#token=" + token);
    }

    @Test
    void theMessageGoesOnlyToTheNamedAddress() {
        SimpleMailMessage message = send("abc-_1", DsarRequest.RequestType.ERASURE);

        assertThat(message.getTo()).containsExactly("subject@example.test");
        assertThat(message.getSubject()).isEqualTo("Confirm your data request");
    }

    @Test
    void nothingIsSentWithoutAToken() {
        mailer.sendVerification("subject@example.test", " ", DsarRequest.RequestType.ERASURE, 168);

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }
}
