package uk.jtoye.core.payment;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefundEventPublisher}.
 *
 * <p>Verifies that publish methods persist exactly one outbox row each, that
 * the row targets {@code order.events} exchange with routing key
 * {@code order.refunded}, and that ObjectMapper failures persist a FAILED
 * placeholder row WITHOUT propagating (WR-05) so the caller's
 * processed_stripe_events dedup row stays committed and Stripe does not
 * retry into the same serialization failure forever.
 */
@ExtendWith(MockitoExtension.class)
class RefundEventPublisherTest {

    @Mock private PaymentEventOutboxRepository outboxRepository;

    private ObjectMapper objectMapper;
    private RefundEventPublisher publisher;

    private UUID refundId;
    private UUID orderId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new RefundEventPublisher(outboxRepository, objectMapper);
        refundId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("publishRefundSucceeded persists outbox row with order.events + order.refunded + REFUND_SUCCEEDED")
    void publishRefundSucceeded_persistsOutboxRowWithOrderExchangeAndOrderRefundedRoutingKey() throws Exception {
        publisher.publishRefundSucceeded(
                refundId, orderId, tenantId, "ORD-001",
                "re_test123", 2500L, "gbp", "succeeded"
        );

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());

        PaymentEventOutbox row = captor.getValue();
        assertEquals(tenantId, row.getTenantId());
        assertEquals("REFUND_SUCCEEDED", row.getEventType());
        assertEquals("order.refunded", row.getRoutingKey());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, row.getExchange(),
                "Exchange MUST be order.events — refund events must not route to payment.events");
        assertEquals(PaymentEventOutbox.Status.PENDING, row.getStatus());
        assertEquals(0, row.getAttempts());
        assertNull(row.getSentAt());
        assertNotNull(row.getPayload());

        // Round-trip the payload — confirms the persisted JSON deserializes back
        // to an equal RefundEvent and that the flusher will be able to pick it
        // up by deserializing as RefundEvent.
        RefundEvent deserialized = objectMapper.readValue(row.getPayload(), RefundEvent.class);
        assertEquals(refundId, deserialized.refundId());
        assertEquals(orderId, deserialized.orderId());
        assertEquals(tenantId, deserialized.tenantId());
        assertEquals("ORD-001", deserialized.orderNumber());
        assertEquals("re_test123", deserialized.stripeRefundId());
        assertEquals(2500L, deserialized.amountPennies());
        assertEquals("gbp", deserialized.currency());
        assertEquals(RefundEvent.RefundEventType.REFUND_SUCCEEDED, deserialized.type());
        assertEquals("succeeded", deserialized.status());
        assertNull(deserialized.failureReason());
        assertNotNull(deserialized.occurredAt());
    }

    @Test
    @DisplayName("publishRefundFailed records failure reason in payload")
    void publishRefundFailed_recordsFailureReason() throws Exception {
        publisher.publishRefundFailed(
                refundId, orderId, tenantId, "ORD-002",
                "re_failed456", 1000L, "gbp", "lost_or_stolen_card"
        );

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());

        PaymentEventOutbox row = captor.getValue();
        assertEquals("REFUND_FAILED", row.getEventType());
        assertEquals("order.refunded", row.getRoutingKey());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, row.getExchange());

        RefundEvent deserialized = objectMapper.readValue(row.getPayload(), RefundEvent.class);
        assertEquals(RefundEvent.RefundEventType.REFUND_FAILED, deserialized.type());
        assertEquals("failed", deserialized.status());
        assertEquals("lost_or_stolen_card", deserialized.failureReason());
    }

    @Test
    @DisplayName("publishRefundUpdated records status update with REFUND_UPDATED type")
    void publishRefundUpdated_recordsStatusUpdate() throws Exception {
        publisher.publishRefundUpdated(
                refundId, orderId, tenantId, "ORD-003",
                "re_pending789", 750L, "gbp", "pending"
        );

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());

        PaymentEventOutbox row = captor.getValue();
        assertEquals("REFUND_UPDATED", row.getEventType());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, row.getExchange());

        RefundEvent deserialized = objectMapper.readValue(row.getPayload(), RefundEvent.class);
        assertEquals(RefundEvent.RefundEventType.REFUND_UPDATED, deserialized.type());
        assertEquals("pending", deserialized.status());
        assertNull(deserialized.failureReason());
    }

    @Test
    @DisplayName("WR-05: persist on JsonProcessingException records FAILED placeholder and does NOT propagate")
    void persist_objectMapperThrows_persistsFailedPlaceholder() throws JsonProcessingException {
        ObjectMapper throwingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(throwingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new JsonProcessingException("simulated") {});

        RefundEventPublisher throwingPublisher = new RefundEventPublisher(outboxRepository, throwingMapper);

        // No exception escapes — caller's @Transactional (and the
        // processed_stripe_events dedup row) must not roll back.
        throwingPublisher.publishRefundSucceeded(
                refundId, orderId, tenantId, "ORD-X",
                "re_x", 100L, "gbp", "succeeded"
        );

        // Exactly one FAILED placeholder row was persisted with a non-empty
        // last_error so the flusher can dead-letter it on the next tick.
        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        PaymentEventOutbox failedRow = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.FAILED, failedRow.getStatus());
        assertNotNull(failedRow.getLastError());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, failedRow.getExchange());
        // Placeholder payload includes refundId/orderId for forensic traceability.
        assertNotNull(failedRow.getPayload());
    }
}
