package uk.jtoye.core.customer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.jtoye.core.notification.dispatch.WhatsAppSmsChannel;
import uk.jtoye.core.order.OrderRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 30-17 / PAY-04 / T-30-69 (PR #1018 review finding 2): the OTP test seam
 * ({@code jtoye.phone-verification.expose-otp-for-test=true}, which returns the one-time code in the
 * API response) starts ONLY where {@code stripe.environment-tag} is declared as exactly one of
 * {@code local}, {@code test} or {@code staging}. A missing, blank, misspelt, wrongly-cased or
 * production tag refuses startup — an allow-list, because the earlier design refused only the literal
 * {@code production} and so failed OPEN on a tag nobody declared or somebody mistyped.
 *
 * <p>Keyed on the tag, not the Spring profile: staging runs the {@code prod} profile too (#999), so a
 * profile-based refusal would refuse the one environment the owner chose to expose (30-16 Task 2).
 *
 * <p>With the seam off nothing is checked: every tag, blank and production included, constructs.
 */
@ExtendWith(MockitoExtension.class)
class PhoneVerificationProductionRefusalTest {

    @Mock private PhoneVerificationChallengeRepository challengeRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private WhatsAppSmsChannel whatsAppSmsChannel;

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(PhoneVerificationService.class)).detachAppender(appender);
        }
    }

    private PhoneVerificationService construct(boolean exposeOtpForTest, String environmentTag) {
        return new PhoneVerificationService(challengeRepository, customerRepository, orderRepository,
                whatsAppSmsChannel, 10, 5, exposeOtpForTest, environmentTag);
    }

    private void assertRefused(String environmentTag, String foundFragment) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> construct(true, environmentTag),
                "the OTP seam must not start with stripe.environment-tag = " + environmentTag);
        String message = ex.getMessage();
        assertTrue(message.contains("jtoye.phone-verification.expose-otp-for-test=true"), message);
        assertTrue(message.contains("stripe.environment-tag"), message);
        assertTrue(message.contains("[local, staging, test]"), "names the allowed values: " + message);
        assertTrue(message.contains(foundFragment), "names what was found: " + message);
    }

    // ------------------------------------------------------------------ seam on: refused

    @Test
    @DisplayName("seam on + production: startup refuses")
    void seamOn_production_refuses() {
        assertRefused("production", "found 'production'");
    }

    @Test
    @DisplayName("seam on + tag not declared (null): startup refuses — an undeclared runtime is not a test runtime")
    void seamOn_missingTag_refuses() {
        assertRefused(null, "(not declared)");
    }

    @Test
    @DisplayName("seam on + blank tag (the @Value fallback when nothing is declared): startup refuses")
    void seamOn_blankTag_refuses() {
        assertRefused("", "(not declared)");
    }

    @Test
    @DisplayName("seam on + whitespace-only tag: startup refuses")
    void seamOn_whitespaceTag_refuses() {
        assertRefused("   ", "(not declared)");
    }

    @Test
    @DisplayName("seam on + misspelt tag 'stagng': startup refuses")
    void seamOn_misspeltTag_refuses() {
        assertRefused("stagng", "found 'stagng'");
    }

    @Test
    @DisplayName("seam on + wrongly-cased tag 'Staging': startup refuses (case-sensitive)")
    void seamOn_wrongCaseTag_refuses() {
        assertRefused("Staging", "found 'Staging'");
    }

    @Test
    @DisplayName("seam on + 'prod' (the Spring profile name, not a declared environment): startup refuses")
    void seamOn_prodTag_refuses() {
        assertRefused("prod", "found 'prod'");
    }

    // ------------------------------------------------------------------ seam on: allowed

    @ParameterizedTest(name = "seam on + ''{0}'': constructs")
    @ValueSource(strings = {"local", "test", "staging"})
    @DisplayName("seam on + a declared local, test or staging tag: constructs")
    void seamOn_allowListedTag_constructs(String environmentTag) {
        assertNotNull(construct(true, environmentTag));
    }

    @Test
    @DisplayName("seam on: a WARN at startup says OTPs are returned in API responses, naming the environment")
    void seamOn_logsWarnNamingTheEnvironment() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(PhoneVerificationService.class)).addAppender(appender);

        construct(true, "staging");

        long warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("phone verification OTPs are returned in API responses")
                        && e.getFormattedMessage().contains("staging"))
                .count();
        assertEquals(1, warns, "exactly one startup WARN: " + appender.list);
    }

    // ------------------------------------------------------------------ seam off: nothing to guard

    @ParameterizedTest(name = "seam off + ''{0}'': constructs")
    @NullSource
    @ValueSource(strings = {"", "   ", "production", "prod", "stagng", "Staging", "staging", "local", "test"})
    @DisplayName("seam off: every tag, blank and production included, constructs")
    void seamOff_anyTag_constructs(String environmentTag) {
        assertNotNull(construct(false, environmentTag));
    }
}
