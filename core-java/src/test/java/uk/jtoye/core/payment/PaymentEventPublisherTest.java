package uk.jtoye.core.payment;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PaymentEventPublisher}.
 *
 * <p>The publisher no longer calls RabbitTemplate directly — it persists a
 * {@link PaymentEventOutbox} row inside the caller's transaction. These tests
 * verify that writes produce the correct outbox row and that serialization
 * survives the full PaymentEvent record.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock private PaymentEventOutboxRepository outboxRepository;
    private PaymentEventPublisher publisher;
    private ObjectMapper objectMapper;

    private UUID orderId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new PaymentEventPublisher(outboxRepository, objectMapper);
        orderId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("publishSucceeded writes SUCCEEDED outbox row in PENDING state")
    void publishSucceeded_writesOutboxRow() {
        publisher.publishSucceeded(orderId, tenantId, "ORD-123", "pi_abc", 2500L, "gbp");

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());

        PaymentEventOutbox row = captor.getValue();
        assertEquals(tenantId, row.getTenantId());
        assertEquals("SUCCEEDED", row.getEventType());
        assertEquals("payment.succeeded", row.getRoutingKey());
        assertEquals(PaymentEventOutbox.Status.PENDING, row.getStatus());
        assertEquals(0, row.getAttempts());
        assertNull(row.getSentAt());
        assertNotNull(row.getPayload());
        assertTrue(row.getPayload().contains("\"pi_abc\""));
        assertTrue(row.getPayload().contains("\"ORD-123\""));
        assertTrue(row.getPayload().contains("\"SUCCEEDED\""));
    }

    @Test
    @DisplayName("publishFailed writes FAILED event with failure reason in payload")
    void publishFailed_writesOutboxRowWithReason() {
        publisher.publishFailed(orderId, tenantId, "ORD-456", "pi_xyz", 999L, "gbp", "card_declined");

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());

        PaymentEventOutbox row = captor.getValue();
        assertEquals("FAILED", row.getEventType());
        assertEquals("payment.failed", row.getRoutingKey());
        assertTrue(row.getPayload().contains("\"card_declined\""));
        assertTrue(row.getPayload().contains("\"FAILED\""));
    }

    @Test
    @DisplayName("publish never touches RabbitMQ synchronously — broker outages cannot break webhook")
    void publish_doesNotCallRabbit() {
        // The publisher has no RabbitTemplate collaborator, so by construction a
        // broker outage cannot propagate into publish(). This test documents the
        // guarantee: a successful outbox save is the entire pre-commit path.
        publisher.publishSucceeded(orderId, tenantId, "ORD-789", "pi_def", 100L, "gbp");
        verify(outboxRepository).save(org.mockito.ArgumentMatchers.any(PaymentEventOutbox.class));
    }
}
