package uk.jtoye.core.boot4;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.payment.PaymentEventOutbox;
import uk.jtoye.core.payment.PaymentEventOutboxFlusher;
import uk.jtoye.core.payment.PaymentEventOutboxRepository;
import uk.jtoye.core.testsupport.BootJsonMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PENDING outbox rows written by Boot-3.5 publishers survive the Boot 4 deploy (Phase 38, plan
 * 38-08, BOOT4-06, D-01 hazard one hop earlier than the broker).
 *
 * <p>A row's payload is the 38-01 {@code jackson2-golden/outbox} fixture, the bytes
 * {@code objectMapper.writeValueAsString(event)} produced on Boot 3.5. The real flusher, built with
 * Boot's Jackson-3 {@code JsonMapper}, claims it from a mocked repository; the object it hands to
 * {@code rabbitTemplate.convertAndSend} must be the {@link GoldenSamples} event, and the row must be
 * marked SENT, not poisoned. Nothing is exposed for the test: this is the flusher's own read path.
 */
class OutboxPayloadCompatibilityTest {

    private static final UUID TENANT = GoldenSamples.TENANT_ID;

    /** Drives one row through the real flusher and returns what it published (and the saved row). */
    private static Published flush(PaymentEventOutbox row) {
        PaymentEventOutboxRepository repository = mock(PaymentEventOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query tenantQuery = mock(Query.class);
        when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        when(tenantQuery.getResultList()).thenReturn(List.of(TENANT));
        when(repository.claimPendingBatch(anyInt())).thenReturn(List.of(row));
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);

        PaymentEventOutboxFlusher flusher = new PaymentEventOutboxFlusher(repository, rabbitTemplate,
                BootJsonMapper.get(), entityManager, mock(PlatformTransactionManager.class), meters,
                5_000L, 300_000L);
        flusher.flushPending();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(row.getExchange()), eq(row.getRoutingKey()), payload.capture());
        ArgumentCaptor<PaymentEventOutbox> saved = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(saved.capture());
        return new Published(payload.getValue(), saved.getValue());
    }

    private record Published(Object event, PaymentEventOutbox row) {
        void assertSent() {
            assertEquals(PaymentEventOutbox.Status.SENT, row.getStatus(), "row status, lastError=" + row.getLastError());
            assertFalse(row.isPoison(), "a Boot-3.5 row must not be poisoned on Boot 4");
        }
    }

    @Test
    @DisplayName("outbox/OrderStateChangeEvent (Boot 3.5 row) -> PaymentEventOutboxFlusher -> the golden event")
    void orderStateChangeEvent_fromJackson2OutboxRow() {
        Published p = flush(new PaymentEventOutbox(TENANT, "ORDER_STATE_CHANGED", "order.state.confirmed",
                InFlightFixtures.outboxPayload("OrderStateChangeEvent"), RabbitMQConfig.ORDER_EVENTS_EXCHANGE));
        p.assertSent();
        InFlightFixtures.assertSameEvent(GoldenSamples.orderStateChangeEvent(), p.event());
    }

    @Test
    @DisplayName("outbox/OrderStateChangeEvent-offset (+01:00 ISO) -> PaymentEventOutboxFlusher -> the golden event")
    void orderStateChangeEventOffset_fromJackson2OutboxRow() {
        Published p = flush(new PaymentEventOutbox(TENANT, "ORDER_STATE_CHANGED", "order.state.preparing",
                InFlightFixtures.outboxPayload("OrderStateChangeEvent-offset"), RabbitMQConfig.ORDER_EVENTS_EXCHANGE));
        p.assertSent();
        InFlightFixtures.assertSameEvent(GoldenSamples.orderStateChangeEventOffset(), p.event());
    }
}
