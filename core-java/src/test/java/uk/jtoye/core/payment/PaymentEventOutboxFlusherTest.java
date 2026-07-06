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
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import uk.jtoye.core.config.RabbitMQConfig;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentEventOutboxFlusher}.
 *
 * <p>Verifies happy path (publish + mark SENT), transient failure path
 * (attempts incremented, last_error recorded, row stays PENDING), and the
 * terminal failure path (row flips to FAILED after MAX_ATTEMPTS).
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventOutboxFlusherTest {

    @Mock private PaymentEventOutboxRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;
    @Mock private jakarta.persistence.Query tenantQuery;

    private ObjectMapper objectMapper;
    private PaymentEventOutboxFlusher flusher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);

        // Mock tenant lookup — return a single test tenant so flushPending iterates once
        UUID testTenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        when(tenantQuery.getResultList()).thenReturn(java.util.List.of(testTenantId));

        flusher = new PaymentEventOutboxFlusher(repository, rabbitTemplate, objectMapper, entityManager, meterRegistryProvider);
    }

    private PaymentEventOutbox pendingRow() throws Exception {
        PaymentEvent event = new PaymentEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-1", "pi_1",
                2500L, "gbp", PaymentEvent.PaymentEventType.SUCCEEDED,
                null, OffsetDateTime.now()
        );
        return new PaymentEventOutbox(
                event.tenantId(),
                "SUCCEEDED",
                "payment.succeeded",
                objectMapper.writeValueAsString(event)
        );
    }

    @Test
    @DisplayName("flushPending publishes PENDING rows and marks them SENT")
    void flushPending_happyPath() throws Exception {
        PaymentEventOutbox row = pendingRow();
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of(row));

        flusher.flushPending();

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE),
                eq("payment.succeeded"),
                any(Object.class)
        );

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(captor.capture());
        PaymentEventOutbox saved = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.SENT, saved.getStatus());
        assertNotNull(saved.getSentAt());
        assertNull(saved.getLastError());
    }

    @Test
    @DisplayName("flushPending records transient broker failure and keeps row PENDING")
    void flushPending_transientFailure_keepsPending() throws Exception {
        PaymentEventOutbox row = pendingRow();
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of(row));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        flusher.flushPending();

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(captor.capture());
        PaymentEventOutbox saved = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.PENDING, saved.getStatus(),
                "Row must stay PENDING under MAX_ATTEMPTS so next tick retries");
        assertEquals(1, saved.getAttempts());
        assertEquals("broker down", saved.getLastError());
        assertNull(saved.getSentAt());
    }

    @Test
    @DisplayName("flushPending flips row to FAILED after MAX_ATTEMPTS attempts")
    void flushPending_exhaustsRetries_marksFailed() throws Exception {
        PaymentEventOutbox row = pendingRow();
        row.setAttempts(PaymentEventOutboxFlusher.MAX_ATTEMPTS - 1); // one away from dead-letter
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of(row));
        doThrow(new AmqpException("still down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        flusher.flushPending();

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(captor.capture());
        PaymentEventOutbox saved = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.FAILED, saved.getStatus());
        assertEquals(PaymentEventOutboxFlusher.MAX_ATTEMPTS, saved.getAttempts());
        assertEquals("still down", saved.getLastError());
    }

    @Test
    @DisplayName("flushPending skips work silently when no PENDING rows")
    void flushPending_noWork_noRabbitCall() {
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of());

        flusher.flushPending();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(repository, never()).save(any(PaymentEventOutbox.class));
    }

    // ---------- V36 per-row exchange routing tests (Plan 17-02 Task 1) ----------

    private PaymentEventOutbox paymentRow() throws Exception {
        PaymentEvent event = new PaymentEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-PE", "pi_pe",
                1500L, "gbp", PaymentEvent.PaymentEventType.SUCCEEDED,
                null, OffsetDateTime.now()
        );
        return new PaymentEventOutbox(
                event.tenantId(),
                "SUCCEEDED",
                "payment.succeeded",
                objectMapper.writeValueAsString(event),
                RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE
        );
    }

    private PaymentEventOutbox refundRow() throws Exception {
        RefundEvent event = new RefundEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ORD-RF", "re_test", 2500L, "gbp",
                RefundEvent.RefundEventType.REFUND_SUCCEEDED,
                "succeeded", null, OffsetDateTime.now()
        );
        return new PaymentEventOutbox(
                event.tenantId(),
                "REFUND_SUCCEEDED",
                "order.refunded",
                objectMapper.writeValueAsString(event),
                RabbitMQConfig.ORDER_EVENTS_EXCHANGE
        );
    }

    @Test
    @DisplayName("publishRow with exchange=payment.events routes to payment exchange")
    void publishRow_paymentExchangeRow_routesToPaymentExchange() throws Exception {
        PaymentEventOutbox row = paymentRow();
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of(row));

        flusher.flushPending();

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), routingCaptor.capture(), any(Object.class));
        assertEquals(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE, exchangeCaptor.getValue());
        assertEquals("payment.succeeded", routingCaptor.getValue());

        ArgumentCaptor<PaymentEventOutbox> savedCaptor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(savedCaptor.capture());
        assertEquals(PaymentEventOutbox.Status.SENT, savedCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("publishRow with exchange=order.events routes to order exchange and deserializes RefundEvent")
    void publishRow_orderExchangeRow_routesToOrderExchange() throws Exception {
        PaymentEventOutbox row = refundRow();
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of(row));

        flusher.flushPending();

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), routingCaptor.capture(), payloadCaptor.capture());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, exchangeCaptor.getValue());
        assertEquals("order.refunded", routingCaptor.getValue());
        // Payload was deserialized as RefundEvent because exchange == order.events.
        org.junit.jupiter.api.Assertions.assertInstanceOf(RefundEvent.class, payloadCaptor.getValue());

        ArgumentCaptor<PaymentEventOutbox> savedCaptor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(savedCaptor.capture());
        assertEquals(PaymentEventOutbox.Status.SENT, savedCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("publishRow with null exchange falls back to payment.events and logs warning")
    void publishRow_nullExchange_fallsBackToPaymentAndLogsWarn() throws Exception {
        // Build a row through the 4-arg constructor (default 'payment.events') then
        // null out the exchange to simulate a pre-V36 in-flight row.
        PaymentEvent event = new PaymentEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-NX", "pi_nx",
                500L, "gbp", PaymentEvent.PaymentEventType.SUCCEEDED,
                null, OffsetDateTime.now()
        );
        PaymentEventOutbox row = new PaymentEventOutbox(
                event.tenantId(), "SUCCEEDED", "payment.succeeded",
                objectMapper.writeValueAsString(event)
        );
        row.setExchange(null);

        when(repository.findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING))
                .thenReturn(List.of(row));

        flusher.flushPending();

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), eq("payment.succeeded"), any(Object.class));
        assertEquals(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE, exchangeCaptor.getValue(),
                "Null exchange must fall back to payment.events, not throw or skip");

        ArgumentCaptor<PaymentEventOutbox> savedCaptor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(savedCaptor.capture());
        assertEquals(PaymentEventOutbox.Status.SENT, savedCaptor.getValue().getStatus());
    }
}
