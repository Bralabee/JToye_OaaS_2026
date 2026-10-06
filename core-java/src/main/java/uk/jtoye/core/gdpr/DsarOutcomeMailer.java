package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import uk.jtoye.core.tenant.keycloak.CustomerAccountDeletionService.AccountDeletionResult;

/**
 * Tells a data subject the outcome of their request (Phase 31.1). This plan (31.1-11, #777) adds the
 * ERASURE completion message; 31.1-16 adds the ACCESS download link here.
 *
 * <h2>What the message may and may not say</h2>
 *
 * <ul>
 *   <li><b>No vendor is named, and nothing reveals which vendors held the data</b> (T-31-09-05).
 *       The copy is the same for every subject: it describes the categories erased, not where they
 *       were. "Which vendors hold this person's address" is what the tenant wall exists to withhold,
 *       and the intake's opaque acknowledgement would be worthless if the outcome handed it back.</li>
 *   <li><b>It is honest about what was kept.</b> Order and tax records stay, with the subject's
 *       details removed (D-02): a subject told "everything is gone" who later learns the orders still
 *       exist has been misled.</li>
 * </ul>
 *
 * <h2>Non-throwing, and the address is never logged</h2>
 *
 * Shaped like {@link DsarVerificationMailer}: the same From address and the same
 * {@code notification.email.enabled} switch, a {@link MailException} logged and swallowed. The
 * request is already COMPLETED when this runs, so a failed send costs the notice and never the
 * erasure. Logs carry the request TYPE only — the recipient is personal data (ASVS V7), and this
 * deliberately does not copy {@code EmailNotificationService}, which logs recipients.
 */
@Component
public class DsarOutcomeMailer {

    private static final Logger log = LoggerFactory.getLogger(DsarOutcomeMailer.class);

    private final JavaMailSender mailSender;

    @Value("${notification.email.from:noreply@jtoye.uk}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean emailEnabled;

    /**
     * The web app origin, for the privacy-notice link (which carries the controller's contact
     * details). The same key the order emails use for their tracking links; config-injected, never
     * derived from a request.
     */
    @Value("${notification.email.tracking-base-url:http://localhost:3000}")
    private String appBaseUrl;

    public DsarOutcomeMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Tell the subject their erasure request is complete.
     *
     * @param recipient the address the subject verified — the message goes there and nowhere else
     * @param account   the sign-in account outcome; only {@code DELETED} and {@code NONE_FOUND}
     *                  accompany a completed request
     */
    public void sendErasureCompleted(String recipient, AccountDeletionResult account) {
        if (!emailEnabled) {
            log.debug("event=dsar_outcome_skipped reason=email_disabled type=ERASURE");
            return;
        }
        if (recipient == null || recipient.isBlank()
                || (account != AccountDeletionResult.DELETED && account != AccountDeletionResult.NONE_FOUND)) {
            log.warn("event=dsar_outcome_skipped reason=incomplete type=ERASURE");
            return;
        }

        String accountLine = account == AccountDeletionResult.DELETED
                ? "Your J'Toye sign-in account has been deleted, so this email address can no longer be "
                        + "used to sign in."
                : "There was no sign-in account for this email address, so there was nothing to delete.";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject("Your data erasure request is complete");
        message.setText("""
                We have completed your request to erase the personal data held for this email address.

                What we erased: your name, contact details, delivery addresses, order notes and review \
                text on orders you placed through J'Toye.

                What we kept, and why: the order and tax records themselves, with your details removed. \
                UK tax law requires a business to keep records of its sales, so the orders still exist, \
                but they no longer identify you.

                %s

                If you have a question about this, our privacy notice explains how to contact us:
                %s/legal/privacy

                — J'Toye"""
                .formatted(accountLine, appBaseUrl));

        try {
            mailSender.send(message);
            log.info("event=dsar_outcome_sent type=ERASURE");
        } catch (MailException e) {
            log.error("event=dsar_outcome_send_failed type=ERASURE error={}", e.getClass().getName());
        }
    }
}
