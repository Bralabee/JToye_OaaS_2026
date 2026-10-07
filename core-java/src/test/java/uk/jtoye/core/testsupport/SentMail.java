package uk.jtoye.core.testsupport;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Reads a {@link MimeMessage} the way its RECIPIENT would: the built message is serialised to the
 * RFC 5322 bytes an SMTP server receives and parsed back, so a header assertion is about the wire,
 * not about what a builder object happens to hold in memory. A header-injection test in particular
 * means nothing unless it parses the bytes: an injected line only becomes a header on the way back.
 *
 * <p>Note {@link MimeMessage#getReplyTo()} FALLS BACK TO FROM when there is no Reply-To header, so
 * "no Reply-To" must be asserted with {@link #header(MimeMessage, String)}, never with getReplyTo.
 */
public final class SentMail {

    private SentMail() {
    }

    /** What a mocked {@code JavaMailSender.createMimeMessage()} should return. */
    public static MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    public static MimeMessage reparse(MimeMessage built) {
        try {
            built.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            built.writeTo(out);
            return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise and re-parse the message", e);
        }
    }

    /** The plain-text body of a re-parsed single-part message. */
    public static String text(MimeMessage parsed) {
        try {
            return (String) parsed.getContent();
        } catch (Exception e) {
            throw new IllegalStateException("message has no plain-text body", e);
        }
    }

    public static String raw(MimeMessage built) {
        try {
            built.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            built.writeTo(out);
            return out.toString(java.nio.charset.StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static InternetAddress from(MimeMessage parsed) {
        try {
            Address[] from = parsed.getFrom();
            return from == null || from.length == 0 ? null : (InternetAddress) from[0];
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every value of a header, or null when the header is absent. */
    public static String[] header(MimeMessage parsed, String name) {
        try {
            return parsed.getHeader(name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> replyTo(MimeMessage parsed) {
        String[] header = header(parsed, "Reply-To");
        if (header == null) {
            return List.of();
        }
        try {
            return addresses(parsed.getReplyTo());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> recipients(MimeMessage parsed, Message.RecipientType type) {
        try {
            return addresses(parsed.getRecipients(type));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String subject(MimeMessage parsed) {
        try {
            return parsed.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> addresses(Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses).map(a -> ((InternetAddress) a).getAddress()).toList();
    }
}
