package uk.jtoye.core.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes {@link PaymentEvent} messages via a transactional outbox.
 *
 * <p>Prior to V31 this class called {@code rabbitTemplate.convertAndSend}
 * directly and swallowed failures in a try/catch, so a RabbitMQ outage
 * silently dropped audit / analytics events with zero recovery. It now
 * persists a {@link PaymentEventOutbox} row inside the caller's transaction;
 * {@link PaymentEventOutboxFlusher} picks up PENDING rows and publishes them
 * asynchronously with retry + failure tracking.
 *
 * <p>Contract preserved: callers still don't see AMQP exceptions — the outbox
 * INSERT is local to the DB, so publish() is effectively fire-and-forget from
 * the Stripe webhook's perspective. Broker outages now survive a restart.
 */
@Component
public class PaymentEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final PaymentEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventPublisher(PaymentEventOutboxRepository outboxRepository,
                                 ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void publishSucceeded(UUID orderId, UUID tenantId, String orderNumber,
                                 String paymentIntentId, long amountPennies, String currency) {
        persist(new PaymentEvent(
                orderId, tenantId, orderNumber, paymentIntentId,
                amountPennies, currency, PaymentEvent.PaymentEventType.SUCCEEDED,
                null, OffsetDateTime.now()
        ));
    }

    public void publishFailed(UUID orderId, UUID tenantId, String orderNumber,
                              String paymentIntentId, long amountPennies, String currency,
                              String failureReason) {
        persist(new PaymentEvent(
                orderId, tenantId, orderNumber, paymentIntentId,
                amountPennies, currency, PaymentEvent.PaymentEventType.FAILED,
                failureReason, OffsetDateTime.now()
        ));
    }

    /**
     * Persist an event to the outbox in the current transaction. Uses
     * REQUIRED propagation (default) so it joins the Stripe webhook
     * transaction; if the webhook rolls back, the event row rolls back too.
     */
    @Transactional
    protected void persist(PaymentEvent event) {
        String routingKey = "payment." + event.type().name().toLowerCase();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Serialization failure is a programmer error (record is fixed shape);
            // loudly surface it rather than silently drop the event.
            log.error("Failed to serialize PaymentEvent for order {}: {}",
                    event.orderNumber(), e.getMessage(), e);
            throw new IllegalStateException("PaymentEvent serialization failed", e);
        }

        PaymentEventOutbox row = new PaymentEventOutbox(
                event.tenantId(),
                event.type().name(),
                routingKey,
                payloadJson
        );
        outboxRepository.save(row);

        log.info("Persisted payment event {} to outbox: order={} pi={}",
                event.type(), event.orderNumber(), event.paymentIntentId());
    }
}
