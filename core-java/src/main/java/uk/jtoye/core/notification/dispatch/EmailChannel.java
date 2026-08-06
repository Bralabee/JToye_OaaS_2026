package uk.jtoye.core.notification.dispatch;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import uk.jtoye.core.notification.template.RenderedEmail;

/**
 * The NEW multipart/alternative email transport for Phase-22 event types
 * (COMMS-02). Sends a branded HTML body plus a plain-text alternative in one
 * {@code multipart/alternative} MimeMessage (D-01) and stamps the RFC 8058
 * one-click unsubscribe headers so bulk-sender deliverability rules are met.
 *
 * <p><b>Additive, not a replacement (Pitfall 5, path A):</b> the working order
 * path ({@code EmailNotificationService} + its {@code SimpleMailMessage} test) is
 * left completely untouched; all NEW events ride this channel. Reuses the
 * existing {@code notification.email.*} config keys ({@code from}/{@code enabled})
 * via {@code @Value} rather than introducing parallel ones.
 *
 * <p><b>Non-throwing contract (NotificationChannel):</b> a
 * {@link MailException}/{@link MessagingException} on send is logged and
 * swallowed — never rethrown — exactly like the order path, so an SMTP outage
 * can't break a dispatcher fanning one event out to multiple channels.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final JavaMailSender mailSender;

    @Value("${notification.email.from:noreply@jtoye.uk}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean emailEnabled;

    public EmailChannel(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return emailEnabled;
    }

    @Override
    public void deliver(NotificationMessage message) {
        if (!emailEnabled) {
            log.debug("event=email_skipped reason=disabled");
            return;
        }
        if (message == null || message.email() == null) {
            log.debug("event=email_skipped reason=no_rendered_email");
            return;
        }
        String to = message.recipient();
        if (to == null || to.isBlank()) {
            log.debug("event=email_skipped reason=no_recipient eventType={}", message.eventType());
            return;
        }

        RenderedEmail email = message.email();
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(email.subject());
            // Two-arg overload => multipart/alternative: plain text first, HTML second.
            helper.setText(email.text(), email.html());

            // Issue #516 — the header target must ACCEPT A POST. RFC 8058 §3.1 has
            // the provider POST "List-Unsubscribe=One-Click" to this URL, and the
            // clickable page URL is a Next.js page that answers 405 to a POST. So
            // one-click is advertised ONLY at the API origin, and only when one is
            // configured; otherwise the header still carries the page URL as a plain
            // RFC 2369 link (mail clients open it with a GET, which works) and the
            // One-Click capability is deliberately NOT claimed. Advertising a
            // one-click target that cannot honour the POST is worse than not
            // advertising it — that is precisely the state #516 found in production.
            String oneClickUrl = message.oneClickUnsubscribeUrl();
            boolean oneClickAvailable = oneClickUrl != null && !oneClickUrl.isBlank();
            String headerUrl = oneClickAvailable ? oneClickUrl : message.unsubscribeUrl();
            if (headerUrl != null && !headerUrl.isBlank()) {
                mime.setHeader("List-Unsubscribe", "<" + headerUrl + ">");
                if (oneClickAvailable) {
                    mime.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                }
            }

            mailSender.send(mime);
            log.info("event=email_sent eventType={} to={}", message.eventType(), to);
        } catch (MailException | MessagingException e) {
            // Swallow like the order path — an SMTP failure must not reach the dispatcher.
            log.error("event=email_send_failed eventType={} to={}: {}",
                    message.eventType(), to, e.getMessage());
        }
    }
}
