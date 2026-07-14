package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.payment.PaymentEventOutbox;
import uk.jtoye.core.payment.PaymentEventOutboxRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes {@link OnboardingStateChangeEvent}s via the shared V46
 * transactional outbox (Phase 21 / D-01), mirroring {@code OrderEventPublisher}.
 *
 * <p>Rather than {@code rabbitTemplate.convertAndSend} directly (which would
 * drop the notification on a broker outage, and could announce a stall for an
 * onboarding whose recompute later rolled back), it persists a
 * {@link PaymentEventOutbox} row inside the caller's transaction. The caller is
 * {@code GateChainRunner.runAndRecompute} — an {@code @Async @Transactional}
 * worker that has re-established the tenant GUC — so the outbox INSERT joins
 * that transaction, is tenant-stamped, RLS-safe, and commits (or rolls back)
 * atomically with the gate evaluation that produced the stall.
 *
 * <p>Intentionally NOT {@code @Transactional}: the contract is "joins the
 * caller's transaction" (the recompute worker is already transactional). The
 * shared {@code PaymentEventOutboxFlusher} drains committed rows to the
 * {@code onboarding.events} exchange; it now recognises that exchange and
 * deserializes the payload back to {@link OnboardingStateChangeEvent} instead
 * of poison-casting it to a {@code PaymentEvent} (Pitfall 1 — the flusher
 * dispatch branch ships in this same plan).
 */
@Component
public class OnboardingEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OnboardingEventPublisher.class);

    /** Outbox event-type discriminator for a manual-review stall (Phase 21 / A2). */
    static final String EVENT_TYPE = "ONBOARDING_STALLED";

    /**
     * Routing key for a manual-review stall on the {@code onboarding.events}
     * exchange. Public so tests and the future Phase 24 binding can reference
     * the single source of truth.
     */
    public static final String MANUAL_REVIEW_ROUTING_KEY = "onboarding.state.manual_review";

    private final PaymentEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OnboardingEventPublisher(PaymentEventOutboxRepository outboxRepository,
                                    ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a manual-review stall event to the outbox in the caller's
     * transaction.
     *
     * @param reason a fixed, human-readable explanation — callers MUST NOT pass
     *               raw provider/upstream text (ASVS V7; FhrsGate discipline).
     */
    public void publishStall(UUID onboardingId, UUID tenantId, UUID shopId,
                             OnboardingState status, String reason) {
        OnboardingStateChangeEvent event = new OnboardingStateChangeEvent(
                onboardingId, tenantId, shopId, status, reason, OffsetDateTime.now()
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Fixed-shape record — serialization failure is a programmer error.
            // DO NOT propagate: throwing would roll back the recompute (and with
            // it the committed gate evaluations). Persist a poisoned FAILED
            // placeholder so the failure is durable and visible to operators
            // instead of a swallowed log line (the flusher skips poison rows).
            log.error("Failed to serialize OnboardingStateChangeEvent for onboarding {}: {} — persisting FAILED placeholder",
                    onboardingId, e.getMessage(), e);
            String placeholder = String.format(
                    "{\"error\":\"serialization_failed\",\"onboardingId\":\"%s\",\"tenantId\":\"%s\"}",
                    onboardingId, tenantId);
            PaymentEventOutbox failedRow = new PaymentEventOutbox(
                    tenantId, EVENT_TYPE, MANUAL_REVIEW_ROUTING_KEY, placeholder,
                    RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE);
            failedRow.setStatus(PaymentEventOutbox.Status.FAILED);
            failedRow.setPoison(true);
            failedRow.setLastError("OnboardingStateChangeEvent serialization failed: " + e.getMessage());
            outboxRepository.save(failedRow);
            return;
        }

        PaymentEventOutbox row = new PaymentEventOutbox(
                tenantId, EVENT_TYPE, MANUAL_REVIEW_ROUTING_KEY, payloadJson,
                RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE);
        outboxRepository.save(row);

        log.info("Persisted onboarding stall event to outbox: onboarding {} ({}) for tenant {}",
                onboardingId, status, tenantId);
    }
}
