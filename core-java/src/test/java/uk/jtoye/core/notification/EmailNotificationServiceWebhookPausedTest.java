package uk.jtoye.core.notification;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.testsupport.SentMail;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * UXT-038 (37-43): the webhook auto-pause email, read the way the owner's mail server receives it.
 * UI-SPEC § Copywriting E1/E2 "Pause email", plus the two 37-43 assumptions for its unresolved rows:
 * "1 event is held" for one, and the held sentence omitted entirely at 0.
 *
 * <p>The end-to-end path (one email per transition, after commit, to the tenant contact) is
 * {@code WebhookAutoPauseIntegrationTest}; this class pins the copy's edge cases.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceWebhookPausedTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationService service;

    static final String TO = "owner@vendor.example.com";
    static final String URL = "https://hooks.vendor.example.com/jtoye";
    /** 13:32 UTC on 10 Oct 2026 is 14:32 in London (BST). */
    static final OffsetDateTime AT_BST = OffsetDateTime.of(2026, 10, 10, 13, 32, 0, 0, ZoneOffset.UTC);
    /** 14:32 UTC on 10 Dec 2026 is 14:32 in London (GMT). */
    static final OffsetDateTime AT_GMT = OffsetDateTime.of(2026, 12, 10, 14, 32, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(service, "emailEnabled", true);
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return SentMail.reparse(captor.getValue());
    }

    @Test
    @DisplayName("subject, recipient, the paused line in UK summer time, '3 events are held', and the fix line")
    void pluralBodyInBritishSummerTime() {
        service.sendWebhookPaused(TO, URL, AT_BST, 3);

        MimeMessage mail = sent();
        assertThat(SentMail.recipients(mail, Message.RecipientType.TO)).containsExactly(TO);
        assertThat(SentMail.subject(mail)).isEqualTo("Your J'Toye webhook endpoint was paused");
        assertThat(SentMail.from(mail).getPersonal()).isEqualTo("J'Toye");
        assertThat(SentMail.text(mail)).isEqualTo("""
                We paused deliveries to https://hooks.vendor.example.com/jtoye at 14:32 on 10 October 2026 \
                (UK time) because they kept failing.

                3 events are held. They'll be delivered when you resume the endpoint.

                Fix the endpoint, then resume it in J'Toye: Dashboard → Webhooks.
                """);
    }

    @Test
    @DisplayName("one held event is singular: '1 event is held'; GMT in winter")
    void singularInGreenwichMeanTime() {
        service.sendWebhookPaused(TO, URL, AT_GMT, 1);

        String body = SentMail.text(sent());
        assertThat(body).contains(" at 14:32 on 10 December 2026 (UK time) ")
                .contains("\n\n1 event is held. They'll be delivered when you resume the endpoint.\n\n")
                .doesNotContain("1 events");
    }

    @Test
    @DisplayName("nothing held: the held sentence is omitted, never '0 events are held'")
    void zeroHeldOmitsTheSentence() {
        service.sendWebhookPaused(TO, URL, AT_BST, 0);

        assertThat(SentMail.text(sent())).isEqualTo("""
                We paused deliveries to https://hooks.vendor.example.com/jtoye at 14:32 on 10 October 2026 \
                (UK time) because they kept failing.

                Fix the endpoint, then resume it in J'Toye: Dashboard → Webhooks.
                """);
    }

    @Test
    @DisplayName("a control character in the vendor's URL cannot forge a line of the body")
    void urlControlCharactersAreNeutralised() {
        service.sendWebhookPaused(TO, URL + "\r\nBcc: x@evil.example\nFix nothing", AT_BST, 2);

        MimeMessage mail = sent();
        String body = SentMail.text(mail);
        assertThat(body.lines().findFirst().orElseThrow())
                .isEqualTo("We paused deliveries to " + URL + " Bcc: x@evil.example Fix nothing at 14:32 on "
                        + "10 October 2026 (UK time) because they kept failing.");
        assertThat(SentMail.header(mail, "Bcc")).isNull();
    }

    @Test
    @DisplayName("mail disabled, or no recipient: nothing is sent")
    void disabledOrIncompleteSendsNothing() {
        service.sendWebhookPaused("  ", URL, AT_BST, 1);
        service.sendWebhookPaused(TO, null, AT_BST, 1);
        ReflectionTestUtils.setField(service, "emailEnabled", false);
        service.sendWebhookPaused(TO, URL, AT_BST, 1);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("the held sentence's three forms are the ones the dashboard banner uses")
    void heldSentenceForms() {
        assertThat(EmailNotificationService.webhookHeldSentence(0)).isNull();
        assertThat(EmailNotificationService.webhookHeldSentence(1))
                .isEqualTo("1 event is held. They'll be delivered when you resume the endpoint.");
        assertThat(EmailNotificationService.webhookHeldSentence(12))
                .isEqualTo("12 events are held. They'll be delivered when you resume the endpoint.");
    }
}
