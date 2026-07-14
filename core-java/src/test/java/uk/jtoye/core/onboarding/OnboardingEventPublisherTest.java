package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit proof of {@link OnboardingEventPublisher} (Phase 21 / D-01).
 *
 * <p>Verifies the producer writes a well-formed {@link PaymentEventOutbox} row
 * for the MANUAL_REVIEW stall — the 5-arg constructor targeting the
 * {@code onboarding.events} exchange (NOT the default {@code payment.events}),
 * tenant-stamped, with a round-trippable {@link OnboardingStateChangeEvent}
 * payload — and that a serialization failure degrades to a durable poisoned
 * placeholder instead of propagating (which would roll back the recompute).
 * The flusher-side "does not poison" proof lives in
 * {@code PaymentEventOutboxFlusherTest}; the RLS/tenant-stamp-under-real-Postgres
 * proof lives in {@code OnboardingStallOutboxIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingEventPublisherTest {

    @Mock
    private PaymentEventOutboxRepository outboxRepository;

    private final ObjectMapper realMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("publishStall writes an onboarding.events outbox row (5-arg ctor, tenant-stamped, round-trippable)")
    void publishStall_writesOnboardingEventsOutboxRow() throws Exception {
        OnboardingEventPublisher publisher = new OnboardingEventPublisher(outboxRepository, realMapper);
        UUID onboardingId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();

        publisher.publishStall(onboardingId, tenantId, shopId,
                OnboardingState.VERIFYING, "One or more checks need a manual review");

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        PaymentEventOutbox row = captor.getValue();

        // Routed to the NEW exchange via the 5-arg ctor — never the payment default.
        assertEquals(RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE, row.getExchange());
        assertEquals(OnboardingEventPublisher.EVENT_TYPE, row.getEventType());
        assertEquals(OnboardingEventPublisher.MANUAL_REVIEW_ROUTING_KEY, row.getRoutingKey());
        assertEquals(tenantId, row.getTenantId());
        // Fresh row: PENDING, not a poisoned placeholder.
        assertEquals(PaymentEventOutbox.Status.PENDING, row.getStatus());
        assertFalse(row.isPoison());

        // Payload is a real OnboardingStateChangeEvent that round-trips.
        OnboardingStateChangeEvent event =
                realMapper.readValue(row.getPayload(), OnboardingStateChangeEvent.class);
        assertEquals(onboardingId, event.onboardingId());
        assertEquals(tenantId, event.tenantId());
        assertEquals(shopId, event.shopId());
        assertEquals(OnboardingState.VERIFYING, event.status());
        assertEquals("One or more checks need a manual review", event.reason());
    }

    @Test
    @DisplayName("publishStall persists a poisoned FAILED placeholder and does NOT propagate on serialization failure")
    void publishStall_serializationFailure_persistsPoisonedPlaceholder() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {});
        OnboardingEventPublisher publisher = new OnboardingEventPublisher(outboxRepository, failingMapper);

        UUID tenantId = UUID.randomUUID();
        // Must not throw — propagating would roll back the recompute + its gate writes.
        publisher.publishStall(UUID.randomUUID(), tenantId, UUID.randomUUID(),
                OnboardingState.VERIFYING, "One or more checks need a manual review");

        ArgumentCaptor<PaymentEventOutbox> captor = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        PaymentEventOutbox row = captor.getValue();
        assertEquals(PaymentEventOutbox.Status.FAILED, row.getStatus());
        assertTrue(row.isPoison(), "unserializable event must be poisoned so the flusher skips it");
        assertEquals(RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE, row.getExchange());
        assertEquals(tenantId, row.getTenantId());
    }
}
