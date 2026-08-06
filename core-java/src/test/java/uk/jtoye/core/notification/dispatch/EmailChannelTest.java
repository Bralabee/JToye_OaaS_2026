package uk.jtoye.core.notification.dispatch;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.notification.template.RenderedEmail;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailChannel} — the NEW multipart/alternative sender for
 * new event types. Verifies the RFC 8058 one-click unsubscribe headers, the
 * MimeMessage send path, and the swallow-on-failure contract (never rethrows),
 * matching the order path's {@code MailException} handling.
 */
@ExtendWith(MockitoExtension.class)
class EmailChannelTest {

    @Mock
    private JavaMailSender mailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> mimeCaptor;

    private EmailChannel channel;

    private static final String FROM = "noreply@jtoye.uk";

    @BeforeEach
    void setUp() {
        channel = new EmailChannel(mailSender);
        ReflectionTestUtils.setField(channel, "fromAddress", FROM);
        ReflectionTestUtils.setField(channel, "emailEnabled", true);
    }

    private NotificationMessage message(String unsubscribeUrl) {
        // Default shape: a fully configured environment, so BOTH URLs are present
        // and they sit on different origins exactly as production composes them.
        return message(unsubscribeUrl, "https://api.jtoye.uk/api/v1/public/unsubscribe?token=abc");
    }

    private NotificationMessage message(String unsubscribeUrl, String oneClickUrl) {
        RenderedEmail email = new RenderedEmail(
                "Refund processed",
                "<html><body>Refund for ORD-900</body></html>",
                "Refund for ORD-900");
        return new NotificationMessage(
                UUID.randomUUID(), "customer@example.com", "refund.processed",
                email, "Refund for ORD-900", unsubscribeUrl, oneClickUrl);
    }

    @Test
    @DisplayName("name/enabled — channel identifies as email and reports the config flag")
    void nameAndEnabled() {
        assertEquals("email", channel.name());
        assertTrue(channel.enabled());
    }

    @Test
    @DisplayName("deliver — sends a MimeMessage with the RFC 8058 one-click unsubscribe headers")
    void deliverSendsMimeWithOneClickHeaders() throws Exception {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        channel.deliver(message("https://app.jtoye.uk/unsubscribe?token=abc"));

        verify(mailSender).send(mimeCaptor.capture());
        MimeMessage sent = mimeCaptor.getValue();

        String[] post = sent.getHeader("List-Unsubscribe-Post");
        assertNotNull(post, "List-Unsubscribe-Post header must be set");
        assertEquals("List-Unsubscribe=One-Click", post[0]);

        String[] listUnsub = sent.getHeader("List-Unsubscribe");
        assertNotNull(listUnsub, "List-Unsubscribe header must be set");
        assertTrue(listUnsub[0].contains("token=abc"));
    }

    @Test
    @DisplayName("#516 — List-Unsubscribe carries the POST-capable API target, not the clickable page URL")
    void listUnsubscribeHeaderTargetsTheApiNotThePage() throws Exception {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        String pageUrl = "https://app.jtoye.uk/unsubscribe?token=abc";
        String oneClickUrl = "https://api.jtoye.uk/api/v1/public/unsubscribe?token=abc";
        channel.deliver(message(pageUrl, oneClickUrl));

        verify(mailSender).send(mimeCaptor.capture());
        String header = mimeCaptor.getValue().getHeader("List-Unsubscribe")[0];

        // RFC 8058 §3.1 POSTs to this URL. The page URL is a Next.js page and
        // answers 405 to a POST, so it must NOT be the advertised one-click target.
        assertEquals("<" + oneClickUrl + ">", header);
        assertFalse(header.contains(pageUrl), "the one-click target must not be the browser page URL");
    }

    @Test
    @DisplayName("#516 — with NO one-click origin configured, the header links the page and does NOT claim One-Click")
    void withoutOneClickOrigin_headerFallsBackToThePage_andDoesNotClaimOneClick() throws Exception {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        String pageUrl = "https://app.jtoye.uk/unsubscribe?token=abc";
        channel.deliver(message(pageUrl, null));

        verify(mailSender).send(mimeCaptor.capture());
        MimeMessage sent = mimeCaptor.getValue();

        // Still a working opt-out: a plain RFC 2369 link the mail client opens with a GET.
        assertEquals("<" + pageUrl + ">", sent.getHeader("List-Unsubscribe")[0]);
        // But never a one-click PROMISE the target cannot honour — the #516 failure mode.
        assertNull(sent.getHeader("List-Unsubscribe-Post"),
                "One-Click must not be advertised without a POST-capable target");
    }

    @Test
    @DisplayName("deliver — a MailException from the sender does NOT propagate (swallow contract)")
    void deliverSwallowsMailException() {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> channel.deliver(message("https://jtoye.uk/unsub")));

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("deliver — disabled channel does not send")
    void deliverSkipsWhenDisabled() {
        ReflectionTestUtils.setField(channel, "emailEnabled", false);

        channel.deliver(message("https://jtoye.uk/unsub"));

        verifyNoInteractions(mailSender);
    }
}
