package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Delivers the verification token to the address that was named — the entire proof of control the
 * DSAR gate rests on (Phase 31, plan 31-09; closes the stub 31-05 handed forward).
 *
 * <h2>Why an email is the mechanism and not a formality</h2>
 *
 * 31-05 created every row {@code PENDING_VERIFICATION} and could not move one to {@code VERIFIED},
 * because doing so needs something that can prove the requester controls the address. It recorded
 * the alternative it rejected — defaulting to {@code VERIFIED} — as arming an <em>unverified</em>
 * erasure request, which is threat T-31-05-02 itself: a destructive action anybody on the internet
 * can aim at anybody else, at any vendor, with nothing but a guessed address. The token in this
 * message is the only thing standing between the intake and that.
 *
 * <p>So the token goes to the ADDRESS THAT WAS NAMED and nowhere else. It is never returned in the
 * intake's response (that response is a constant, deliberately — see {@code DsarIntakeService}), it
 * is never logged, and only its digest is stored.
 *
 * <h2>Non-throwing, like every other mail path here</h2>
 *
 * {@code EmailChannel} and {@code EmailNotificationService} both log and swallow a
 * {@link MailException} rather than rethrowing, and this follows them: an SMTP outage must not turn
 * a lodged request into a 500. The row is already committed when this runs, so a failed send leaves
 * a recoverable state (the subject can lodge again) rather than a lost one.
 *
 * <h2>Synchronous, deliberately</h2>
 *
 * The sibling order-notification path is {@code @Async}; this one is not. The send is bounded by
 * the intake's own per-IP bucket (5 requests per hour — {@code DsarIntakeRateLimiter}), so a slow
 * SMTP server cannot be turned into a request-thread exhaustion lever at any meaningful rate, and a
 * synchronous send keeps the delivery observable from a test without a scheduler in the way. If the
 * bucket is ever widened, revisit this first.
 *
 * <h2>The token travels in the link, and that is unavoidable</h2>
 *
 * Issue #278 moved the unsubscribe token out of the query string because a request line is captured
 * verbatim by every intermediary on the path. The same concern applies here and the same exception
 * does too: a link in an email can only be followed with a GET, which has no body slot. So the
 * canonical machine contract is a JSON POST ({@code DsarVerificationController}) and the emailed
 * link is the GET companion, exactly as {@code PublicUnsubscribeController} keeps both. The
 * mitigation is the token's short, config-injected lifetime and its single use.
 */
@Component
public class DsarVerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(DsarVerificationMailer.class);

    private final JavaMailSender mailSender;

    @Value("${notification.email.from:noreply@jtoye.uk}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean emailEnabled;

    /**
     * Where the emailed link points. Config-injected rather than derived from the request, because
     * a verification link built from an attacker-supplied {@code Host} header is a redirect to an
     * attacker-controlled collector for a live bearer token.
     */
    @Value("${jtoye.gdpr.dsar.verify-base-url:http://localhost:8080/api/v1/public/gdpr/dsar/verify}")
    private String verifyBaseUrl;

    public DsarVerificationMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Send the readable verification token to the subject.
     *
     * @param recipientEmail the address the subject named — the token goes here and nowhere else
     * @param token          the readable, single-use token; never logged, never stored
     * @param requestType    what the subject asked for, so the message can say what will happen
     * @param ttlHours       how long the subject has, quoted from the same injected value the
     *                       expiry column was written from
     */
    public void sendVerification(String recipientEmail, String token,
                                 DsarRequest.RequestType requestType, long ttlHours) {
        if (!emailEnabled) {
            // Never log the address or the token. The request type is not personal data.
            log.debug("event=dsar_verification_skipped reason=email_disabled type={}", requestType);
            return;
        }
        if (recipientEmail == null || recipientEmail.isBlank() || token == null || token.isBlank()) {
            log.warn("event=dsar_verification_skipped reason=incomplete type={}", requestType);
            return;
        }

        String link = verifyBaseUrl + (verifyBaseUrl.contains("?") ? "&" : "?")
                + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipientEmail);
        message.setSubject("Confirm your data request");
        message.setText("""
                Somebody asked us to %s the personal data held for this email address.

                If that was you, confirm it by opening the link below. We will not act on the \
                request until you do.

                %s

                The link expires in %d hours. If you did not make this request, ignore this \
                message — nothing will happen and no data will be changed.

                — J'Toye"""
                .formatted(describe(requestType), link, ttlHours));

        try {
            mailSender.send(message);
            // The address is PII (ASVS V7) and the token is a credential — neither is logged.
            log.info("event=dsar_verification_sent type={}", requestType);
        } catch (MailException e) {
            log.error("event=dsar_verification_send_failed type={}: {}", requestType, e.getMessage());
        }
    }

    private static String describe(DsarRequest.RequestType requestType) {
        return requestType == DsarRequest.RequestType.ERASURE
                ? "erase"
                : "provide a copy of";
    }
}
