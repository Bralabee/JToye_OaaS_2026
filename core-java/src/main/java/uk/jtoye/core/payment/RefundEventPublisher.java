package uk.jtoye.core.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbox-backed publisher for {@link RefundEvent}s. Persists rows in the
 * caller's @Transactional; the shared {@link PaymentEventOutboxFlusher}
 * flushes them to {@code RabbitMQConfig.ORDER_EVENTS_EXCHANGE} via the
 * V36 {@code exchange} column.
 *
 * <p>Mirrors {@link PaymentEventPublisher}'s shape exactly — only the
 * destination exchange ({@code order.events}) and routing key
 * ({@code order.refunded}) differ. The 5-arg outbox constructor is the
 * load-bearing contract: a 4-arg constructor would default to
 * {@code payment.events} and route refund events to the wrong exchange.
 */
@Component
public class RefundEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RefundEventPublisher.class);
    private static final String REFUND_ROUTING_KEY = "order.refunded";

    private final PaymentEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public RefundEventPublisher(PaymentEventOutboxRepository outboxRepository,
                                ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void publishRefundSucceeded(UUID refundId, UUID orderId, UUID tenantId, String orderNumber,
                                       String stripeRefundId, long amountPennies, String currency,
                                       String status) {
        persist(new RefundEvent(refundId, orderId, tenantId, orderNumber, stripeRefundId,
                amountPennies, currency, RefundEvent.RefundEventType.REFUND_SUCCEEDED,
                status, null, OffsetDateTime.now()));
    }

    public void publishRefundFailed(UUID refundId, UUID orderId, UUID tenantId, String orderNumber,
                                    String stripeRefundId, long amountPennies, String currency,
                                    String failureReason) {
        persist(new RefundEvent(refundId, orderId, tenantId, orderNumber, stripeRefundId,
                amountPennies, currency, RefundEvent.RefundEventType.REFUND_FAILED,
                "failed", failureReason, OffsetDateTime.now()));
    }

    public void publishRefundUpdated(UUID refundId, UUID orderId, UUID tenantId, String orderNumber,
                                     String stripeRefundId, long amountPennies, String currency,
                                     String status) {
        persist(new RefundEvent(refundId, orderId, tenantId, orderNumber, stripeRefundId,
                amountPennies, currency, RefundEvent.RefundEventType.REFUND_UPDATED,
                status, null, OffsetDateTime.now()));
    }

    /**
     * Persist a refund event to the outbox in the caller's transaction.
     *
     * <p><b>WR-02:</b> intentionally NOT annotated {@code @Transactional}.
     * This method is invoked exclusively via {@code this.publishRefund*}
     * (same class, same instance), which means Spring's AOP proxy never
     * intercepts the call and any {@code @Transactional} here would be a
     * no-op. The contract is "joins the caller's transaction" — every
     * caller in the codebase ({@link RefundService}) is already
     * {@code @Transactional}, so the outbox write rides on that
     * transaction and rolls back together with it.
     *
     * <p>Made {@code private} so future callers cannot reach this method
     * across the proxy boundary and accidentally rely on a non-existent
     * transactional contract.
     */
    private void persist(RefundEvent event) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // WR-05 — DO NOT propagate. Throwing here would roll back the
            // caller's @Transactional including the processed_stripe_events
            // dedup row, and Stripe would retry the same event into the same
            // failure forever. Instead persist a FAILED placeholder so the
            // dedup row commits, the flusher dead-letters the placeholder
            // row, and operators see exactly one alert per failure.
            //
            // The placeholder payload is a JSON string literal (no
            // ObjectMapper involvement) so this branch cannot itself throw
            // JsonProcessingException. The flusher's payload-deserialization
            // catch flips it to FAILED on the next tick (no retry loop).
            log.error("Failed to serialize RefundEvent for refund {}: {} — persisting FAILED placeholder",
                    event.refundId(), e.getMessage(), e);
            String placeholder = String.format(
                    "{\"error\":\"serialization_failed\",\"refundId\":\"%s\",\"orderId\":\"%s\"}",
                    event.refundId(), event.orderId());
            PaymentEventOutbox failedRow = new PaymentEventOutbox(
                    event.tenantId(),
                    event.type().name(),
                    REFUND_ROUTING_KEY,
                    placeholder,
                    RabbitMQConfig.ORDER_EVENTS_EXCHANGE
            );
            failedRow.setStatus(PaymentEventOutbox.Status.FAILED);
            // Poisoned (#93): the placeholder payload is not a RefundEvent, so
            // the resurrection pass must never re-lease it into a
            // deserialize-fail loop.
            failedRow.setPoison(true);
            failedRow.setLastError("RefundEvent serialization failed: " + e.getMessage());
            outboxRepository.save(failedRow);
            return;
        }

        PaymentEventOutbox row = new PaymentEventOutbox(
                event.tenantId(),
                event.type().name(),
                REFUND_ROUTING_KEY,
                payloadJson,
                RabbitMQConfig.ORDER_EVENTS_EXCHANGE
        );
        outboxRepository.save(row);

        log.info("Persisted refund event {} to outbox: refund={} order={} stripe={}",
                event.type(), event.refundId(), event.orderNumber(), event.stripeRefundId());
    }
}
