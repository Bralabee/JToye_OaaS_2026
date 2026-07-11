package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import uk.jtoye.core.config.RabbitMQConfig;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link OrderSseFanoutListener} (#92 / P2-1).
 *
 * <p>Together with {@code OrderEventFanoutTopologyIntegrationTest} (real broker)
 * this proves the SSE fan-out chain: every replica's per-instance queue receives
 * every order event (integration test), and the listener on that queue hands the
 * event to the local emitter registry (this test).</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderSseFanoutListenerTest {

    @Mock
    private OrderSseService sseService;

    @Test
    @DisplayName("onOrderStateChange - delegates every event to the local SSE broadcast")
    void delegatesToSseBroadcast() {
        OrderSseFanoutListener listener = new OrderSseFanoutListener(sseService);
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-FANOUT-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());

        listener.onOrderStateChange(event);

        verify(sseService).broadcast(event);
        verifyNoMoreInteractions(sseService);
    }

    @Test
    @DisplayName("wiring - listens on the per-instance fan-out queue, not the durable competing-consumer queue")
    void listensOnPerInstanceFanoutQueue() throws Exception {
        Method method = OrderSseFanoutListener.class.getMethod("onOrderStateChange", OrderStateChangeEvent.class);
        RabbitListener annotation = method.getAnnotation(RabbitListener.class);

        assertNotNull(annotation, "fan-out handler must be a @RabbitListener");
        // SpEL indirection to the AnonymousQueue bean: each JVM resolves its own
        // uniquely named queue. Listening on RabbitMQConfig.ORDER_EVENTS_QUEUE here
        // would silently reintroduce the 1-of-N competing-consumer bug at >1 replica.
        assertArrayEquals(new String[]{"#{orderEventsFanoutQueue.name}"}, annotation.queues());
    }

    @Test
    @DisplayName("wiring - the durable listener no longer resolves the SSE service (no double delivery)")
    void durableListenerHasNoSseDependency() {
        // If OrderStateChangeListener ever regains an OrderSseService dependency,
        // the replica that wins the competing consumption would broadcast the same
        // event twice to its local SSE clients (once per listener).
        for (var constructor : OrderStateChangeListener.class.getConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                if (OrderSseService.class.isAssignableFrom(parameterType)) {
                    throw new AssertionError(
                            "OrderStateChangeListener must not depend on OrderSseService — SSE fan-out lives in OrderSseFanoutListener");
                }
            }
        }
    }

    @Test
    @DisplayName("wiring - fan-out queue and durable queue share exchange and routing pattern")
    void fanoutBindingMatchesDurableBinding() {
        RabbitMQConfig config = new RabbitMQConfig();
        var fanoutBinding = config.orderEventsFanoutBinding(
                config.orderEventsFanoutQueue(), config.orderEventsExchange());
        var durableBinding = config.orderEventsBinding(
                config.orderEventsQueue(), config.orderEventsExchange());

        // Same exchange + same routing pattern => the broker duplicates each
        // published order event into BOTH queues; neither consumer starves the other.
        org.junit.jupiter.api.Assertions.assertEquals(
                durableBinding.getExchange(), fanoutBinding.getExchange());
        org.junit.jupiter.api.Assertions.assertEquals(
                durableBinding.getRoutingKey(), fanoutBinding.getRoutingKey());
    }
}
