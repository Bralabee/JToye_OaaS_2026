package uk.jtoye.core.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled flusher that drains PENDING rows from {@link PaymentEventOutbox}
 * and publishes them to the RabbitMQ payment events exchange.
 *
 * <p>Success → row marked SENT with {@code sent_at=now}.
 * <br>Failure → {@code attempts} incremented, {@code last_error} recorded.
 * After {@link #MAX_ATTEMPTS} attempts a row flips to {@code FAILED} and a
 * dead-letter counter is incremented so operators can alert on it.
 */
@Component
public class PaymentEventOutboxFlusher {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventOutboxFlusher.class);

    /** Stop retrying after this many attempts and flip the row to FAILED. */
    static final int MAX_ATTEMPTS = 5;

    private final PaymentEventOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Counter deadLetterCounter;

    public PaymentEventOutboxFlusher(PaymentEventOutboxRepository repository,
                                     RabbitTemplate rabbitTemplate,
                                     ObjectMapper objectMapper,
                                     EntityManager entityManager,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        MeterRegistry reg = meterRegistryProvider.getIfAvailable();
        this.deadLetterCounter = reg != null
                ? Counter.builder("payment.outbox.dead_letter")
                    .description("Payment events that exceeded retry limit and were marked FAILED")
                    .register(reg)
                : null;
    }

    /**
     * Flush PENDING payment events per-tenant.
     *
     * <p>SECURITY: Iterates per-tenant to ensure TenantContext is set before each
     * query. The payment_event_outbox table has RLS (V33), so queries without
     * TenantContext would return empty or fail. This also prevents cross-tenant
     * event leakage to RabbitMQ listeners.
     */
    @Scheduled(fixedDelayString = "${payment.outbox.flush-interval-ms:5000}")
    @Transactional
    public void flushPending() {
        @SuppressWarnings("unchecked")
        List<UUID> tenantIds = entityManager
                .createNativeQuery("SELECT id FROM tenants")
                .getResultList();

        for (UUID tenantId : tenantIds) {
            TenantContext.set(tenantId);
            try {
                List<PaymentEventOutbox> pending = repository
                        .findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status.PENDING);
                if (pending.isEmpty()) {
                    continue;
                }
                log.debug("Flushing {} pending payment events for tenant {}", pending.size(), tenantId);
                for (PaymentEventOutbox row : pending) {
                    publishRow(row);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void publishRow(PaymentEventOutbox row) {
        try {
            // V36 — per-row exchange routing. Refund rows write 'order.events',
            // payment rows write 'payment.events' (legacy default). NULL is
            // defensive: any pre-V36 in-flight row would have exchange=NULL
            // before flush; log + fall back so we don't drop the message.
            String exchange = row.getExchange();
            if (exchange == null || exchange.isBlank()) {
                log.warn("Outbox row {} has no exchange — falling back to {}",
                        row.getId(), RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE);
                exchange = RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE;
            }

            // Deserialize payload according to event family. Refund rows carry
            // RefundEvent JSON; payment rows carry PaymentEvent JSON. We pick by
            // exchange so a single flusher serves both without if-instance noise
            // in business code.
            Object event;
            if (RabbitMQConfig.ORDER_EVENTS_EXCHANGE.equals(exchange)) {
                event = objectMapper.readValue(row.getPayload(), RefundEvent.class);
            } else {
                event = objectMapper.readValue(row.getPayload(), PaymentEvent.class);
            }

            rabbitTemplate.convertAndSend(exchange, row.getRoutingKey(), event);
            row.setStatus(PaymentEventOutbox.Status.SENT);
            row.setSentAt(OffsetDateTime.now());
            row.setLastError(null);
            repository.save(row);
            log.info("Flushed outbox event {} (id={}, exchange={})",
                    row.getEventType(), row.getId(), exchange);
        } catch (JsonProcessingException e) {
            // Payload corruption — not recoverable by retry. Mark FAILED immediately.
            row.setStatus(PaymentEventOutbox.Status.FAILED);
            row.setLastError("payload deserialization failed: " + e.getMessage());
            row.setAttempts(row.getAttempts() + 1);
            repository.save(row);
            log.error("Outbox row {} is unrecoverable", row.getId(), e);
            if (deadLetterCounter != null) deadLetterCounter.increment();
        } catch (Exception e) {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(e.getMessage());
            if (attempts >= MAX_ATTEMPTS) {
                row.setStatus(PaymentEventOutbox.Status.FAILED);
                if (deadLetterCounter != null) deadLetterCounter.increment();
                log.error("Outbox event {} exhausted {} retries; marking FAILED",
                        row.getId(), MAX_ATTEMPTS);
            } else {
                log.warn("Publish attempt {}/{} failed for outbox event {}: {}",
                        attempts, MAX_ATTEMPTS, row.getId(), e.getMessage());
            }
            repository.save(row);
        }
    }
}
