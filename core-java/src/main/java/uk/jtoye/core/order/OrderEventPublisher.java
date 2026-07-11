package uk.jtoye.core.order;

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
 * Publishes {@link OrderStateChangeEvent}s via the transactional outbox
 * (Issue #93).
 *
 * <p>Prior to #93 this class called {@code rabbitTemplate.convertAndSend}
 * directly from inside the caller's transaction and swallowed failures, so:
 * (a) a broker outage silently dropped order events with no recovery, and
 * (b) a state change could be announced to RabbitMQ and then rolled back —
 * listeners (SSE, KDS WebSocket, customer emails) would report a transition
 * that never happened.
 *
 * <p>It now persists a {@link PaymentEventOutbox} row inside the caller's
 * transaction (every caller is {@code @Transactional}); the shared
 * {@code PaymentEventOutboxFlusher} publishes committed rows to
 * {@code order.events} with retry + backoff + resurrection. Rollback of the
 * business transaction rolls the event row back with it — nothing is ever
 * emitted for a transaction that didn't commit.
 *
 * <p>Contract preserved: callers still don't see AMQP exceptions — the
 * outbox INSERT is local to the DB. Wire format is unchanged: the flusher
 * deserializes the payload back to {@link OrderStateChangeEvent} before
 * {@code convertAndSend}, so consumers receive the same JSON + __TypeId__
 * they did when this class published directly.
 */
@Component
public class OrderEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    /**
     * Routing-key prefix for order state changes ({@code order.state.<status>}).
     * The flusher uses this prefix to pick the payload type for rows on the
     * shared {@code order.events} exchange (refund rows use {@code order.refunded}).
     */
    public static final String ORDER_STATE_ROUTING_PREFIX = "order.state.";

    static final String EVENT_TYPE = "ORDER_STATE_CHANGED";

    private final PaymentEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderEventPublisher(PaymentEventOutboxRepository outboxRepository,
                               ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist an order state-change event to the outbox in the caller's
     * transaction.
     *
     * <p>Intentionally NOT {@code @Transactional}: the contract is "joins the
     * caller's transaction" — every caller ({@code OrderService},
     * {@code PaymentService}, {@code PublicStorefrontService}) is already
     * {@code @Transactional}, so the outbox write commits and rolls back
     * together with the state change it announces.
     */
    public void publishStateChange(UUID orderId, UUID tenantId, String orderNumber,
                                   OrderStatus previousStatus, OrderStatus newStatus) {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, tenantId, orderNumber, previousStatus, newStatus, OffsetDateTime.now()
        );

        String routingKey = ORDER_STATE_ROUTING_PREFIX + newStatus.name().toLowerCase();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Fixed-shape record — serialization failure is a programmer
            // error. DO NOT propagate: throwing would roll back the order
            // state change itself. Persist a poisoned FAILED placeholder so
            // the failure is durable and visible to operators instead of a
            // swallowed log line (the flusher's dead-letter path skips it).
            log.error("Failed to serialize OrderStateChangeEvent for order {}: {} — persisting FAILED placeholder",
                    orderNumber, e.getMessage(), e);
            String placeholder = String.format(
                    "{\"error\":\"serialization_failed\",\"orderId\":\"%s\",\"orderNumber\":\"%s\"}",
                    orderId, orderNumber);
            PaymentEventOutbox failedRow = new PaymentEventOutbox(
                    tenantId, EVENT_TYPE, routingKey, placeholder,
                    RabbitMQConfig.ORDER_EVENTS_EXCHANGE);
            failedRow.setStatus(PaymentEventOutbox.Status.FAILED);
            failedRow.setPoison(true);
            failedRow.setLastError("OrderStateChangeEvent serialization failed: " + e.getMessage());
            outboxRepository.save(failedRow);
            return;
        }

        PaymentEventOutbox row = new PaymentEventOutbox(
                tenantId, EVENT_TYPE, routingKey, payloadJson,
                RabbitMQConfig.ORDER_EVENTS_EXCHANGE);
        outboxRepository.save(row);

        log.info("Persisted order state change to outbox: {} -> {} for order {}",
                previousStatus, newStatus, orderNumber);
    }
}
