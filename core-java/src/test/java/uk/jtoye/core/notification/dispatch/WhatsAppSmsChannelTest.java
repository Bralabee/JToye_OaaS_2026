package uk.jtoye.core.notification.dispatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.jtoye.core.notification.WhatsAppProperties;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WhatsAppSmsChannel} — the INERT-by-default third channel
 * (COMMS-07). Proves: (a) with the flag OFF the channel is a WARN no-op that
 * never throws and emits at most one WARN across repeated calls; (b) enabling the
 * flag without credentials is a documented WARN no-op, not a crash; (c)
 * {@link WhatsAppProperties#toString()} masks the auth token so it can't leak.
 */
class WhatsAppSmsChannelTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger channelLogger;

    @BeforeEach
    void attachAppender() {
        channelLogger = (Logger) LoggerFactory.getLogger(WhatsAppSmsChannel.class);
        appender = new ListAppender<>();
        appender.start();
        channelLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        channelLogger.detachAppender(appender);
    }

    private NotificationMessage message() {
        return new NotificationMessage(
                UUID.randomUUID(), "+447700900000", "order.ready",
                null, "Your order is ready",
                "https://app.jtoye.uk/unsubscribe?token=abc",
                "https://api.jtoye.uk/api/v1/public/unsubscribe?token=abc");
    }

    private long warnCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    @Test
    @DisplayName("OFF by default — two deliver() calls never throw, stay a no-op, emit exactly one WARN")
    void offByDefaultIsOneTimeWarnNoOp() {
        WhatsAppProperties props = new WhatsAppProperties(); // enabled=false, blank creds
        WhatsAppSmsChannel channel = new WhatsAppSmsChannel(props);

        assertEquals("whatsapp", channel.name());
        assertFalse(channel.enabled(), "channel must be disabled when not configured");

        assertDoesNotThrow(() -> channel.deliver(message()));
        assertDoesNotThrow(() -> channel.deliver(message()));

        assertFalse(channel.enabled(), "channel remains a no-op after calls");
        assertEquals(1, warnCount(), "the not-configured WARN must fire exactly once (warnedOnce guard)");
    }

    @Test
    @DisplayName("enabled=true but blank creds — configured()==false and deliver() does not throw")
    void enabledWithoutCredsIsWarnNoOpNotCrash() {
        WhatsAppProperties props = new WhatsAppProperties();
        props.setEnabled(true); // flag ON, but no account-sid / auth-token / from-number
        WhatsAppSmsChannel channel = new WhatsAppSmsChannel(props);

        assertFalse(props.configured(), "blank creds => not configured even with the flag on");
        assertFalse(channel.enabled());
        assertDoesNotThrow(() -> channel.deliver(message()));
    }

    @Test
    @DisplayName("WhatsAppProperties.toString() masks the auth token (never cleartext)")
    void toStringMasksAuthToken() {
        WhatsAppProperties props = new WhatsAppProperties();
        props.setEnabled(true);
        props.setProvider("twilio");
        props.setAccountSid("AC_super_secret_sid");
        props.setAuthToken("tok_do_not_leak_me");
        props.setFromNumber("+447700900123");

        String rendered = props.toString();

        assertFalse(rendered.contains("tok_do_not_leak_me"), "auth token must not appear in cleartext");
        assertFalse(rendered.contains("AC_super_secret_sid"), "account sid must not appear in cleartext");
        assertTrue(rendered.contains("***"), "masked marker expected");
        assertTrue(rendered.contains("enabled=true"), "non-secret fields still shown");
    }

    @Test
    @DisplayName("configured() true only when flag on AND all creds present")
    void configuredRequiresFlagAndAllCreds() {
        WhatsAppProperties props = new WhatsAppProperties();
        props.setEnabled(true);
        props.setAccountSid("sid");
        props.setAuthToken("tok");
        props.setFromNumber("+44770090000");
        assertTrue(props.configured());

        props.setEnabled(false);
        assertFalse(props.configured(), "flag off => not configured even with creds");
    }
}
