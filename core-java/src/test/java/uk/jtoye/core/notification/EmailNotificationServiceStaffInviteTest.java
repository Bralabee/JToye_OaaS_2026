package uk.jtoye.core.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.testsupport.SentMail;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * D-07 (Phase 37-07): the invitation email, read the way the invitee's mail server receives it.
 *
 * <p>The copy is UI-SPEC § Copywriting B1-B3 ("Invitation email (plain text)"): subject
 * "{Inviter} invited you to {business} on J'Toye"; body naming role and shop, the link, "works once",
 * the expiry in UK time, and "Nothing happens unless you accept". Plain text only. The address and the
 * link (a bearer credential) never reach a log line.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceStaffInviteTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    static final String TO = "new.person@example.com";
    static final String LINK = "https://app.jtoye.test/invite#token=6f318039-1f48-40f2-b7a0-1132ce4bb369."
            + "Q2hlY2tPbmx5VGhpc0lzQVRlc3RUb2tlblRoYXRJc0xvbmc";
    /** 13:32 UTC on 10 Oct 2026 is 14:32 in London (BST). */
    static final OffsetDateTime EXPIRES_BST = OffsetDateTime.of(2026, 10, 10, 13, 32, 0, 0, ZoneOffset.UTC);
    /** 14:32 UTC on 10 Dec 2026 is 14:32 in London (GMT). */
    static final OffsetDateTime EXPIRES_GMT = OffsetDateTime.of(2026, 12, 10, 14, 32, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(service, "emailEnabled", true);
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
        logger = (Logger) LoggerFactory.getLogger(EmailNotificationService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return SentMail.reparse(captor.getValue());
    }

    @Test
    @DisplayName("D-07: subject and plain-text body carry the UI-SPEC copy, the link and the expiry in UK time")
    void inviteEmail_hasTheCopy_theLink_andTheUkExpiry() {
        service.sendStaffInvite(TO, "Ada Admin", "Mama Ade's Kitchen", "Shop manager", "Mama Ade's Kitchen Digbeth",
                LINK, EXPIRES_BST);

        MimeMessage mail = sent();
        assertThat(SentMail.recipients(mail, Message.RecipientType.TO)).containsExactly(TO);
        assertThat(SentMail.from(mail).getAddress()).isEqualTo("noreply@jtoye.uk");
        assertThat(SentMail.subject(mail)).isEqualTo("Ada Admin invited you to Mama Ade's Kitchen on J'Toye");
        String text = SentMail.text(mail);
        assertThat(text)
                .contains("Ada Admin has invited you to help run Mama Ade's Kitchen on J'Toye as Shop manager "
                        + "for Mama Ade's Kitchen Digbeth.")
                .contains("Accept the invitation: " + LINK)
                .contains("This link works once and expires on 10 October 2026, 14:32 (UK time).")
                .contains("If you weren't expecting this, you can ignore this email. Nothing happens unless you accept.");
    }

    @Test
    @DisplayName("D-07: the expiry is UK wall-clock time in winter too (GMT), and 'all shops' reads as such")
    void inviteEmail_ukTimeInWinter_andAllShops() {
        service.sendStaffInvite(TO, "Ada Admin", "Mama Ade's Kitchen", "Group admin", "all shops", LINK, EXPIRES_GMT);

        String text = SentMail.text(sent());
        assertThat(text)
                .contains("as Group admin for all shops.")
                .contains("expires on 10 December 2026, 14:32 (UK time).");
    }

    @Test
    @DisplayName("D-07: the message is plain text: a single text/plain part with no HTML tag")
    void inviteEmail_isPlainText() throws Exception {
        service.sendStaffInvite(TO, "Ada Admin", "Mama Ade's Kitchen", "Staff", "Shop A", LINK, EXPIRES_BST);

        MimeMessage mail = sent();
        assertThat(mail.getContentType()).startsWith("text/plain");
        assertThat(SentMail.text(mail)).doesNotContainPattern("<[a-zA-Z/][^>]*>");
    }

    @Test
    @DisplayName("T-31.1-85: a line break in a tenant-controlled name cannot forge a header or a body line")
    void inviteEmail_tenantControlledNamesAreSanitised() {
        service.sendStaffInvite(TO, "Ada\r\nBcc: victim@example.com", "Mama\nAde", "Staff", "Shop\r\nA", LINK,
                EXPIRES_BST);

        MimeMessage mail = sent();
        assertThat(SentMail.header(mail, "Bcc")).as("no injected header").isNull();
        assertThat(SentMail.subject(mail)).isEqualTo("Ada Bcc: victim@example.com invited you to Mama Ade on J'Toye");
        assertThat(SentMail.text(mail)).contains("as Staff for Shop A.");
    }

    @Test
    @DisplayName("T-37-17: the address and the link never reach a log line, sent or failed")
    void inviteEmail_neverLogsTheAddressOrTheLink() {
        service.sendStaffInvite(TO, "Ada Admin", "Biz", "Staff", "Shop A", LINK, EXPIRES_BST);
        doThrow(new MailSendException("550 rejected <" + TO + "> " + LINK)).when(mailSender).send(any(MimeMessage.class));
        service.sendStaffInvite(TO, "Ada Admin", "Biz", "Staff", "Shop A", LINK, EXPIRES_BST);

        assertThat(logs.list).as("the send and the failure are both logged").hasSizeGreaterThanOrEqualTo(2);
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertThat(line).doesNotContain(TO).doesNotContain(LINK).doesNotContain("Q2hlY2tPbmx5");
        }
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("event=staff_invite_email_sent"))
                .anyMatch(m -> m.contains("event=staff_invite_email_failed"));
    }

    @Test
    @DisplayName("D-07: with email switched off nothing is sent")
    void inviteEmail_disabled_sendsNothing() {
        ReflectionTestUtils.setField(service, "emailEnabled", false);
        service.sendStaffInvite(TO, "Ada Admin", "Biz", "Staff", "Shop A", LINK, EXPIRES_BST);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
