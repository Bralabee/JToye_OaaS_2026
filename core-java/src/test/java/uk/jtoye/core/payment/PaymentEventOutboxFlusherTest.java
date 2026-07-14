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
import uk.jtoye.core.onboarding.OnboardingState;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentEventOutboxFlusher} (Issue #93 hardened).
 *
 * <p>Verifies happy path (publish + mark SENT), transient failure path
 * (attempts incremented, exponential backoff scheduled, row stays PENDING),
 * the ops-signal failure path (row flips to FAILED after MAX_ATTEMPTS but is
 * NOT poisoned), the poison path (payload corruption is never retryable),
 * per-family payload dispatch (payment / refund / order-state), the
 * resurrection pass, and the pure backoff maths.
 *
 * <p>Concurrency (FOR UPDATE SKIP LOCKED) and backoff *gating* live in SQL,
 * so they are covered by PaymentEventOutboxReliabilityIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventOutboxFlusherTest {

    private static final long BASE_MS = 5_000L;
    private static final long CAP_MS = 300_000L;

    @Mock private PaymentEventOutboxRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;
    @Mock private jakarta.persistence.Query tenantQuery;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;
    private PaymentEventOutboxFlusher flusher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);

        // Mock tenant lookup — return a single test tenant so flushPending
        // iterates once. lenient(): the pure backoff-math tests never touch
        // the tenant query and would otherwise trip strict-stub checking.
        UUID testTenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        lenient().when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        lenient().when(tenantQuery.getResultList()).thenReturn(java.util.List.of(testTenantId));

        // Mocked PlatformTransactionManager: TransactionTemplate.execute runs
        // the callback with a null status and the mocked getTransaction/commit
        // are no-ops — the unit tests exercise flusher logic, not tx wiring
        // (that lives in the Testcontainers integration tests).
        flusher = new PaymentEventOutboxFlusher(repository, rabbitTemplate, objectMapper,
                entityManager, transactionManager, meterRegistryProvider, BASE_MS, CAP_MS);
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
    @DisplayName("flushPending publishes claimed rows and marks them SENT")
    void flushPending_happyPath() throws Exception {
        PaymentEventOutbox row = pendingRow();
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
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
    @DisplayName("transient broker failure keeps row PENDING and schedules exponential backoff")
    void flushPending_transientFailure_keepsPendingWithBackoff() throws Exception {
        PaymentEventOutbox row = pendingRow();
        OffsetDateTime before = OffsetDateTime.now();
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
                .thenReturn(List.of(row));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        flusher.flushPending();

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(captor.capture());
        PaymentEventOutbox saved = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.PENDING, saved.getStatus(),
                "Row must stay PENDING under MAX_ATTEMPTS so a later tick retries");
        assertEquals(1, saved.getAttempts());
        assertEquals("broker down", saved.getLastError());
        assertNull(saved.getSentAt());
        assertFalse(saved.isPoison());
        // attempt 1 → base * 2^0 = 5s backoff from "now"
        assertTrue(saved.getNextAttemptAt().isAfter(before.plusSeconds(4)),
                "next_attempt_at must be pushed ~base ms into the future, was " + saved.getNextAttemptAt());
        assertTrue(saved.getNextAttemptAt().isBefore(before.plusSeconds(30)),
                "attempt 1 backoff must be near the base, not the cap");
    }

    @Test
    @DisplayName("row flips to FAILED after MAX_ATTEMPTS but stays resurrectable (poison=false)")
    void flushPending_exhaustsRetries_marksFailedNotPoisoned() throws Exception {
        PaymentEventOutbox row = pendingRow();
        row.setAttempts(PaymentEventOutboxFlusher.MAX_ATTEMPTS - 1); // one away from the ops signal
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
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
        assertFalse(saved.isPoison(),
                "Retry exhaustion is transient — resurrection must be able to re-lease this row");
    }

    @Test
    @DisplayName("corrupt payload flips row to FAILED and poisons it — never resurrected")
    void flushPending_corruptPayload_poisonsRow() {
        PaymentEventOutbox row = new PaymentEventOutbox(
                UUID.randomUUID(), "SUCCEEDED", "payment.succeeded", "{not json");
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
                .thenReturn(List.of(row));

        flusher.flushPending();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(captor.capture());
        PaymentEventOutbox saved = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.FAILED, saved.getStatus());
        assertTrue(saved.isPoison(), "Payload corruption is unrecoverable — must be poisoned");
        assertTrue(saved.getLastError().startsWith("payload deserialization failed"));
    }

    @Test
    @DisplayName("flushPending skips work silently when nothing is claimable")
    void flushPending_noWork_noRabbitCall() {
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
                .thenReturn(List.of());

        flusher.flushPending();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(repository, never()).save(any(PaymentEventOutbox.class));
    }

    @Test
    @DisplayName("resurrectFailed re-leases non-poison FAILED rows per tenant")
    void resurrectFailed_delegatesToRepositoryPerTenant() {
        when(repository.resurrectFailed()).thenReturn(3);

        flusher.resurrectFailed();

        verify(repository).resurrectFailed();
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

    private PaymentEventOutbox orderStateRow() throws Exception {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-ST",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now()
        );
        return new PaymentEventOutbox(
                event.tenantId(),
                "ORDER_STATE_CHANGED",
                "order.state.confirmed",
                objectMapper.writeValueAsString(event),
                RabbitMQConfig.ORDER_EVENTS_EXCHANGE
        );
    }

    @Test
    @DisplayName("publishRow with exchange=payment.events routes to payment exchange")
    void publishRow_paymentExchangeRow_routesToPaymentExchange() throws Exception {
        PaymentEventOutbox row = paymentRow();
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
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
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
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
    @DisplayName("publishRow with routing key order.state.* deserializes OrderStateChangeEvent (#93)")
    void publishRow_orderStateRow_deserializesOrderStateChangeEvent() throws Exception {
        PaymentEventOutbox row = orderStateRow();
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
                .thenReturn(List.of(row));

        flusher.flushPending();

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), routingCaptor.capture(), payloadCaptor.capture());
        assertEquals(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, exchangeCaptor.getValue());
        assertEquals("order.state.confirmed", routingCaptor.getValue());
        // The wire object must be the same type the direct publish used, so
        // OrderStateChangeListener's @RabbitListener signature keeps working.
        OrderStateChangeEvent sent = org.junit.jupiter.api.Assertions
                .assertInstanceOf(OrderStateChangeEvent.class, payloadCaptor.getValue());
        assertEquals(OrderStatus.CONFIRMED, sent.newStatus());
        assertEquals(OrderStatus.PENDING, sent.previousStatus());

        ArgumentCaptor<PaymentEventOutbox> savedCaptor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(savedCaptor.capture());
        assertEquals(PaymentEventOutbox.Status.SENT, savedCaptor.getValue().getStatus());
    }

    private PaymentEventOutbox onboardingRow() throws Exception {
        OnboardingStateChangeEvent event = new OnboardingStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OnboardingState.VERIFYING, "One or more checks need a manual review",
                OffsetDateTime.now()
        );
        return new PaymentEventOutbox(
                event.tenantId(),
                "ONBOARDING_STALLED",
                "onboarding.state.manual_review",
                objectMapper.writeValueAsString(event),
                RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE
        );
    }

    @Test
    @DisplayName("publishRow with exchange=onboarding.events deserializes OnboardingStateChangeEvent (not PaymentEvent) and marks SENT — no poison (Pitfall 1)")
    void publishRow_onboardingExchangeRow_deserializesOnboardingEvent_notPoisoned() throws Exception {
        PaymentEventOutbox row = onboardingRow();
        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
                .thenReturn(List.of(row));

        flusher.flushPending();

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(exchangeCaptor.capture(), routingCaptor.capture(), payloadCaptor.capture());
        assertEquals(RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE, exchangeCaptor.getValue());
        assertEquals("onboarding.state.manual_review", routingCaptor.getValue());
        // The row was routed through the onboarding branch, NOT the final
        // PaymentEvent else-arm: the wire object is an OnboardingStateChangeEvent.
        OnboardingStateChangeEvent sent = org.junit.jupiter.api.Assertions
                .assertInstanceOf(OnboardingStateChangeEvent.class, payloadCaptor.getValue());
        assertEquals(OnboardingState.VERIFYING, sent.status());
        assertEquals("One or more checks need a manual review", sent.reason());

        // Marked SENT, not poison-FAILED — the shared flusher published the new
        // exchange without poisoning it.
        ArgumentCaptor<PaymentEventOutbox> savedCaptor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(savedCaptor.capture());
        PaymentEventOutbox saved = savedCaptor.getValue();
        assertEquals(PaymentEventOutbox.Status.SENT, saved.getStatus());
        assertFalse(saved.isPoison(), "onboarding.events row must NOT be poisoned");
        assertNull(saved.getLastError());
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

        when(repository.claimPendingBatch(PaymentEventOutboxFlusher.BATCH_SIZE))
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

    // ---------- Backoff maths (#93) ----------

    @Test
    @DisplayName("computeBackoffMillis doubles per attempt: base * 2^(attempts-1)")
    void backoff_doublesPerAttempt() {
        assertEquals(5_000L, PaymentEventOutboxFlusher.computeBackoffMillis(1, BASE_MS, CAP_MS));
        assertEquals(10_000L, PaymentEventOutboxFlusher.computeBackoffMillis(2, BASE_MS, CAP_MS));
        assertEquals(20_000L, PaymentEventOutboxFlusher.computeBackoffMillis(3, BASE_MS, CAP_MS));
        assertEquals(40_000L, PaymentEventOutboxFlusher.computeBackoffMillis(4, BASE_MS, CAP_MS));
        assertEquals(80_000L, PaymentEventOutboxFlusher.computeBackoffMillis(5, BASE_MS, CAP_MS));
        assertEquals(160_000L, PaymentEventOutboxFlusher.computeBackoffMillis(6, BASE_MS, CAP_MS));
    }

    @Test
    @DisplayName("computeBackoffMillis clamps at the cap")
    void backoff_clampsAtCap() {
        // attempt 7 → 320s raw, clamped to 300s cap
        assertEquals(CAP_MS, PaymentEventOutboxFlusher.computeBackoffMillis(7, BASE_MS, CAP_MS));
        assertEquals(CAP_MS, PaymentEventOutboxFlusher.computeBackoffMillis(50, BASE_MS, CAP_MS));
    }

    @Test
    @DisplayName("computeBackoffMillis survives shift overflow at huge attempt counts")
    void backoff_survivesOverflow() {
        assertEquals(CAP_MS, PaymentEventOutboxFlusher.computeBackoffMillis(63, BASE_MS, CAP_MS));
        assertEquals(CAP_MS, PaymentEventOutboxFlusher.computeBackoffMillis(Integer.MAX_VALUE, BASE_MS, CAP_MS));
    }

    @Test
    @DisplayName("computeBackoffMillis guards attempts < 1")
    void backoff_guardsNonPositiveAttempts() {
        assertEquals(BASE_MS, PaymentEventOutboxFlusher.computeBackoffMillis(0, BASE_MS, CAP_MS));
        assertEquals(BASE_MS, PaymentEventOutboxFlusher.computeBackoffMillis(-5, BASE_MS, CAP_MS));
    }
}
