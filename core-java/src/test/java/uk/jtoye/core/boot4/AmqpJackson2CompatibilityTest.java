package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.media.MediaProcessingEvent;
import uk.jtoye.core.order.OrderStateChangeEvent;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-flight AMQP messages survive the Boot 4 deploy, in both directions of a rolling update
 * (Phase 38, plan 38-08, BOOT4-06, D-01 hazard).
 *
 * <p>DELIBERATE-JACKSON2: emulates Boot-3.5 pods during a rolling deploy
 *
 * <p><b>Boot 3.5 -> Boot 4.</b> Each case rebuilds a message exactly as the Boot-3.5 converter left
 * it on the broker (the 38-01 {@code jackson2-golden/amqp} body bytes, its {@code contentType} /
 * {@code contentEncoding} and every header including {@code __TypeId__}) and hands it to the
 * converter the application now builds, {@link RabbitMQConfig#jsonMessageConverter()}. The result
 * must be the {@link GoldenSamples} event the fixture was captured from, dates compared by instant.
 *
 * <p><b>Boot 4 -> Boot 3.5.</b> Each sample is written by that same Boot-4 converter and read back
 * by a {@code Jackson2JsonMessageConverter} built with the same trusted packages, which is exactly
 * the bean a Boot-3.5 pod still running mid-deploy holds. The deprecated Jackson-2 converter is
 * deliberate cross-version test code here, never production code.
 *
 * <p><b>How each payload is consumed.</b> Five of the six types reach a class-level
 * {@code @RabbitListener} ({@code WebhookFanoutListener}), which selects a handler from
 * {@code __TypeId__} against the trusted packages, so those cases convert from the header alone,
 * the strict path. {@code MediaProcessingEvent} has one consumer, {@code MediaProcessingWorker},
 * a typed single-method listener: the container sets the inferred argument type and the type
 * mapper uses it ahead of the header. Its package is deliberately NOT trusted, so the media cases
 * carry the inferred type as the real consumer does, and {@link #mediaTypeIdAloneIsRefused()}
 * pins that the trust boundary did not widen to make them pass.
 *
 * <p>The Boot-3.5 converter wrote {@code java.time} values as epoch decimals and dropped the
 * offset ({@code "timestamp":1791117296.123456789}), so the first direction also answers RESEARCH
 * assumption A2: whether the Jackson-3 converter reads the numeric form.
 */
class AmqpJackson2CompatibilityTest {

    private static final String MEDIA = "MediaProcessingEvent";

    /** The Boot-4 converter, as the application builds it. */
    private final MessageConverter converter = new RabbitMQConfig().jsonMessageConverter();

    /** The Boot-3.5 pod's converter: what {@code jsonMessageConverter()} returned on Boot 3.5.16. */
    @SuppressWarnings({"deprecation", "removal"})
    private final MessageConverter boot35Converter = new Jackson2JsonMessageConverter(trustedPackages());

    static Stream<String> names() {
        return InFlightFixtures.names();
    }

    /** {@code RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES} (package-private), read as the constant itself. */
    static String[] trustedPackages() {
        try {
            Field field = RabbitMQConfig.class.getDeclaredField("TRUSTED_PAYLOAD_PACKAGES");
            field.setAccessible(true);
            return ((String[]) field.get(null)).clone();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES", e);
        }
    }

    /** What the listener container does before conversion for the payload's real consumer. */
    private static Message asConsumed(String name, Message message) {
        if (MEDIA.equals(name)) {
            message.getMessageProperties().setInferredArgumentType(MediaProcessingEvent.class);
        }
        return message;
    }

    // ----------------------------------------------------------------- Boot 3.5 -> Boot 4

    @ParameterizedTest(name = "amqp/{0} (Boot 3.5 bytes) -> the Boot-4 converter -> the golden event")
    @MethodSource("names")
    void jackson2Message_isReadByTheBoot4Converter(String name) {
        Object event = converter.fromMessage(asConsumed(name, InFlightFixtures.amqpMessage(name)));
        InFlightFixtures.assertSameEvent(InFlightFixtures.sample(name), event);
    }

    // ----------------------------------------------------------------- Boot 4 -> Boot 3.5

    @ParameterizedTest(name = "{0} written by the Boot-4 converter -> a Boot-3.5 Jackson2JsonMessageConverter -> the golden event")
    @MethodSource("names")
    void boot4Message_isReadByABoot35Converter(String name) {
        Object sample = InFlightFixtures.sample(name);
        Message written = converter.toMessage(sample, new MessageProperties());

        assertEquals(sample.getClass().getName(), written.getMessageProperties().getHeaders().get("__TypeId__"),
                "the Boot-4 converter must keep writing the __TypeId__ a Boot-3.5 class-level listener dispatches on");
        assertEquals(MessageProperties.CONTENT_TYPE_JSON, written.getMessageProperties().getContentType());

        Object read = boot35Converter.fromMessage(asConsumed(name, written));
        InFlightFixtures.assertSameEvent(sample, read);
    }

    // ----------------------------------------------------------------- coverage and boundary

    @Test
    @DisplayName("the six cases are exactly the amqp fixtures the 38-01 MANIFEST lists (none unchecked)")
    void everyAmqpFixtureIsCovered() {
        assertEquals(InFlightFixtures.manifestNames("amqp"), new java.util.TreeSet<>(InFlightFixtures.SAMPLES.keySet()));
    }

    @Test
    @DisplayName("trust boundary unchanged: media's __TypeId__ alone is refused by both converters")
    void mediaTypeIdAloneIsRefused() {
        for (MessageConverter c : new MessageConverter[] {converter, boot35Converter}) {
            Exception e = assertThrows(Exception.class, () -> c.fromMessage(InFlightFixtures.amqpMessage(MEDIA)),
                    c.getClass().getSimpleName() + " must not resolve an untrusted __TypeId__");
            assertTrue(chainContains(e, "not in the trusted packages"), "refusal reason was: " + e);
        }
    }

    @Test
    @DisplayName("the trusted list read reflectively is the three exact packages (the reverse converter mirrors Boot 3.5)")
    void trustedPackagesAreTheExactList() {
        assertEquals(Set.of("uk.jtoye.core.order", "uk.jtoye.core.payment", "uk.jtoye.core.onboarding"),
                Set.of(trustedPackages()));
    }

    // ---------------------------------------------------- negative controls of the comparison

    @Test
    @DisplayName("negative control: instant comparison fails on a one-nanosecond shift")
    void sameEvent_rejectsAOneNanosecondShift() {
        OrderStateChangeEvent golden = GoldenSamples.orderStateChangeEvent();
        OrderStateChangeEvent shifted = new OrderStateChangeEvent(golden.orderId(), golden.tenantId(),
                golden.orderNumber(), golden.previousStatus(), golden.newStatus(),
                golden.timestamp().plusNanos(1), golden.shopId());
        assertThrows(AssertionError.class, () -> InFlightFixtures.assertSameEvent(golden, shifted));
    }

    @Test
    @DisplayName("negative control: a differing non-date component fails (shopId lost)")
    void sameEvent_rejectsALostComponent() {
        OrderStateChangeEvent golden = GoldenSamples.orderStateChangeEvent();
        OrderStateChangeEvent lost = new OrderStateChangeEvent(golden.orderId(), golden.tenantId(),
                golden.orderNumber(), golden.previousStatus(), golden.newStatus(), golden.timestamp());
        assertThrows(AssertionError.class, () -> InFlightFixtures.assertSameEvent(golden, lost));
    }

    @Test
    @DisplayName("negative control: the reverse check can fail — a millisecond-timestamp Boot-4 writer loses the nanos")
    void reverseCheck_rejectsALossyWriter() {
        JsonMapper millis = JsonMapper.builder()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build();
        MessageConverter lossy = new JacksonJsonMessageConverter(millis, trustedPackages());
        Object sample = GoldenSamples.orderStateChangeEvent();
        Object read = boot35Converter.fromMessage(lossy.toMessage(sample, new MessageProperties()));
        assertThrows(AssertionError.class, () -> InFlightFixtures.assertSameEvent(sample, read));
    }

    private static boolean chainContains(Throwable t, String marker) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (String.valueOf(c.getMessage()).contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
