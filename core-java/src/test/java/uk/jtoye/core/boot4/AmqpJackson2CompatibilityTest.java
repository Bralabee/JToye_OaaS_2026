package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.converter.MessageConverter;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.order.OrderStateChangeEvent;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * In-flight AMQP messages survive the Boot 4 deploy (Phase 38, plan 38-08, BOOT4-06, D-01 hazard).
 *
 * <p>Each case rebuilds a message exactly as the Boot-3.5 converter left it on the broker (the
 * 38-01 {@code jackson2-golden/amqp} body bytes, its {@code contentType}/{@code contentEncoding}
 * and every header including {@code __TypeId__}) and hands it to the converter the application
 * now builds, {@link RabbitMQConfig#jsonMessageConverter()}. The result must be the
 * {@link GoldenSamples} event the fixture was captured from, with dates compared by instant.
 *
 * <p>The Boot-3.5 converter wrote {@code java.time} values as epoch decimals and dropped the
 * offset ({@code "timestamp":1791117296.123456789}), so these cases also answer RESEARCH
 * assumption A2: whether the Jackson-3 converter reads the numeric form.
 */
class AmqpJackson2CompatibilityTest {

    private final MessageConverter converter = new RabbitMQConfig().jsonMessageConverter();

    @Test
    @DisplayName("amqp/OrderStateChangeEvent (Boot 3.5 bytes) -> the Boot-4 converter -> the golden event")
    void orderStateChangeEvent_fromJackson2Message() {
        Object event = converter.fromMessage(InFlightFixtures.amqpMessage("OrderStateChangeEvent"));
        InFlightFixtures.assertSameEvent(GoldenSamples.orderStateChangeEvent(), event);
    }

    @Test
    @DisplayName("amqp/OrderStateChangeEvent-offset: the +01:00 instant survives the offset-dropping epoch form")
    void orderStateChangeEventOffset_fromJackson2Message() {
        Object event = converter.fromMessage(InFlightFixtures.amqpMessage("OrderStateChangeEvent-offset"));
        InFlightFixtures.assertSameEvent(GoldenSamples.orderStateChangeEventOffset(), event);
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
}
