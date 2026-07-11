package uk.jtoye.core.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.payment.PaymentEventOutbox;
import uk.jtoye.core.payment.PaymentEventOutboxRepository;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the outbox-backed {@link OrderEventPublisher} (Issue #93).
 *
 * <p>Before #93 this publisher fired {@code convertAndSend} mid-transaction
 * and swallowed AMQP failures — a broker outage dropped order events, and a
 * rolled-back state change could still be announced. These tests pin the new
 * contract: the event is PERSISTED (durable, joins the caller's transaction)
 * and never touches RabbitMQ directly.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock private PaymentEventOutboxRepository outboxRepository;

    private ObjectMapper objectMapper;
    private OrderEventPublisher publisher;

    private final UUID orderId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new OrderEventPublisher(outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("publishStateChange persists a PENDING outbox row targeting order.events")
    void publishStateChange_persistsOutboxRow() throws Exception {
        publisher.publishStateChange(orderId, tenantId, "ORD-42",
                OrderStatus.PENDING, OrderStatus.CONFIRMED);

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        PaymentEventOutbox row = captor.getValue();

        assertEquals(PaymentEventOutbox.Status.PENDING, row.getStatus());
        assertEquals(tenantId, row.getTenantId());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, row.getExchange(),
                "Order events must ride the order.events exchange, not the payment default");
        assertEquals("order.state.confirmed", row.getRoutingKey());
        assertEquals(OrderEventPublisher.EVENT_TYPE, row.getEventType());
        assertFalse(row.isPoison());

        // Payload must round-trip to the exact event the listeners expect.
        OrderStateChangeEvent event = objectMapper.readValue(row.getPayload(), OrderStateChangeEvent.class);
        assertEquals(orderId, event.orderId());
        assertEquals(tenantId, event.tenantId());
        assertEquals("ORD-42", event.orderNumber());
        assertEquals(OrderStatus.PENDING, event.previousStatus());
        assertEquals(OrderStatus.CONFIRMED, event.newStatus());
    }

    @Test
    @DisplayName("routing key follows order.state.<lowercase status>")
    void publishStateChange_routingKeyPerStatus() {
        publisher.publishStateChange(orderId, tenantId, "ORD-43",
                OrderStatus.CONFIRMED, OrderStatus.CANCELLED);

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("order.state.cancelled", captor.getValue().getRoutingKey());
        assertTrue(captor.getValue().getRoutingKey()
                .startsWith(OrderEventPublisher.ORDER_STATE_ROUTING_PREFIX));
    }

    @Test
    @DisplayName("serialization failure persists a poisoned FAILED placeholder and does not throw")
    void publishStateChange_serializationFailure_persistsPoisonPlaceholder() throws Exception {
        // A broken ObjectMapper stands in for an impossible-in-practice
        // serialization failure; the publisher must not let it roll back the
        // caller's order transition, but must leave a durable trace.
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {});
        OrderEventPublisher failing = new OrderEventPublisher(outboxRepository, broken);

        assertDoesNotThrow(() -> failing.publishStateChange(orderId, tenantId, "ORD-44",
                OrderStatus.PENDING, OrderStatus.CONFIRMED));

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        PaymentEventOutbox row = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.FAILED, row.getStatus());
        assertTrue(row.isPoison(), "Placeholder payload is not deserializable — must never be resurrected");
        assertTrue(row.getLastError().contains("serialization failed"));
        assertTrue(row.getPayload().contains("ORD-44"));
    }
}
