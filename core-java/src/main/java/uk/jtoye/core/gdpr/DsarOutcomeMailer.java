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
 * Tells a data subject the outcome of their request (Phase 31.1). 31.1-11 (#777) added the ERASURE
 * completion message; 31.1-16 (#778, D-01) adds the ACCESS download link,
 * {@link #sendAccessExportReady(String, String, long)}.
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
 * erasure request is already COMPLETED when its notice is sent, so a failed send costs the notice and
 * never the erasure. The ACCESS link is different: the link IS the fulfilment, so that method reports
 * whether the send succeeded and the worker completes the request only when it did. Logs carry the
 * request TYPE only — the recipient is personal data (ASVS V7), and this deliberately does not copy
 * {@code EmailNotificationService}, which logs recipients.
 *
 * <h2>The ACCESS email carries a link and nothing else of the subject's (T-31.1-58)</h2>
 *
 * The export names every vendor that holds the subject (Article 15(1)(c)), so it is delivered only to
 * the verified address and only behind a single-use, expiring token. The email itself is the same
 * text for every subject: no order, no name, no vendor, no item. The token travels in the URL
 * FRAGMENT ({@code #token=}), which a browser never sends to a server in a request line or a
 * Referer header, so it does not land in access logs on the way to the download page (T-31.1-59).
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

    /**
     * Where the ACCESS link points (the frontend download page, plan 31.1-17). Config-injected and
     * NEVER derived from a request: a link built from an attacker-supplied Host header would hand a
     * live bearer token for a personal-data document to a collector of their choosing.
     */
    @Value("${jtoye.gdpr.dsar.export-download-base-url:http://localhost:3000/data-request/download}")
    private String exportDownloadBaseUrl;

    public DsarOutcomeMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Send the verified subject the link to their Article 15 export (D-01).
     *
     * @param recipient the address the subject verified — the message goes there and nowhere else
     * @param token     the readable single-use token; only its SHA-256 is stored
     * @param ttlHours  how long the link lives, quoted to the subject from the same value that stamped
     *                  {@code expires_at}, so the promise and the column cannot disagree
     * @return {@code true} only when the mail sender accepted the message; {@code false} when mail is
     *         switched off, the input is unusable, or the send failed — the request must then stay open
     */
    public boolean sendAccessExportReady(String recipient, String token, long ttlHours) {
        if (!emailEnabled) {
            // The link is the fulfilment: with mail off nobody receives it, so this is NOT a success.
            log.warn("event=dsar_outcome_skipped reason=email_disabled type=ACCESS");
            return false;
        }
        if (recipient == null || recipient.isBlank() || token == null || token.isBlank() || ttlHours <= 0) {
            log.warn("event=dsar_outcome_skipped reason=incomplete type=ACCESS");
            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject("Your copy of your personal data is ready");
        message.setText("""
                You asked for a copy of the personal data held about this email address. It is ready.

                Download it here:
                %s#token=%s

                The link works once and expires in %s. After you have downloaded the file, or once \
                the link has expired, the copy we prepared is deleted. If the link has expired, you can \
                make a new request.

                The file lists each shop that holds your data, who runs it, what they hold and why. It \
                is a JSON file, which the download page shows in a readable form.

                If you did not ask for this, you can ignore this email: nothing is shared unless the \
                link is opened.

                If you have a question about this, our privacy notice explains how to contact us:
                %s/legal/privacy

                — J'Toye"""
                .formatted(exportDownloadBaseUrl, token, describeTtl(ttlHours), appBaseUrl));

        try {
            mailSender.send(message);
            log.info("event=dsar_outcome_sent type=ACCESS");
            return true;
        } catch (MailException e) {
            log.error("event=dsar_outcome_send_failed type=ACCESS error={}", e.getClass().getName());
            return false;
        }
    }

    /** "7 days" for 168, "1 day" for 24, "36 hours" for anything that is not whole days. */
    static String describeTtl(long ttlHours) {
        if (ttlHours % 24 == 0) {
            long days = ttlHours / 24;
            return days == 1 ? "1 day" : days + " days";
        }
        return ttlHours == 1 ? "1 hour" : ttlHours + " hours";
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
