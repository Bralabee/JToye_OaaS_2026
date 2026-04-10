package uk.jtoye.core.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import uk.jtoye.core.config.RabbitMQConfig;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock private RabbitTemplate rabbitTemplate;
    private PaymentEventPublisher publisher;

    private UUID orderId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        publisher = new PaymentEventPublisher(rabbitTemplate);
        orderId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("publishSucceeded sends SUCCEEDED event to payment.events exchange")
    void publishSucceeded_sendsEvent() {
        publisher.publishSucceeded(orderId, tenantId, "ORD-123", "pi_abc", 2500L, "gbp");

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE),
                eq("payment.succeeded"),
                eventCaptor.capture()
        );

        PaymentEvent event = eventCaptor.getValue();
        assertEquals(orderId, event.orderId());
        assertEquals(tenantId, event.tenantId());
        assertEquals("ORD-123", event.orderNumber());
        assertEquals("pi_abc", event.paymentIntentId());
        assertEquals(2500L, event.amountPennies());
        assertEquals("gbp", event.currency());
        assertEquals(PaymentEvent.PaymentEventType.SUCCEEDED, event.type());
        assertNull(event.failureReason());
        assertNotNull(event.occurredAt());
    }

    @Test
    @DisplayName("publishFailed sends FAILED event with failure reason")
    void publishFailed_sendsEventWithReason() {
        publisher.publishFailed(orderId, tenantId, "ORD-456", "pi_xyz", 999L, "gbp", "card_declined");

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE),
                eq("payment.failed"),
                eventCaptor.capture()
        );

        PaymentEvent event = eventCaptor.getValue();
        assertEquals(PaymentEvent.PaymentEventType.FAILED, event.type());
        assertEquals("card_declined", event.failureReason());
        assertEquals(999L, event.amountPennies());
    }

    @Test
    @DisplayName("publish swallows RabbitMQ exceptions — fire-and-forget")
    void publish_swallowsExceptions() {
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        // Must not throw — Stripe webhook must complete even if broker is down
        assertDoesNotThrow(() ->
                publisher.publishSucceeded(orderId, tenantId, "ORD-789", "pi_def", 100L, "gbp"));
    }
}
