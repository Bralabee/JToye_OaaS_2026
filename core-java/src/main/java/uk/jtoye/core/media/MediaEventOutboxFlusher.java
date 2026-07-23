package uk.jtoye.core.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled flusher that drains PENDING rows from {@link MediaEventOutbox} and
 * publishes them to the {@code media.events} exchange (IMG-02). A near-clone of
 * {@code payment/PaymentEventOutboxFlusher} — same {@code FOR UPDATE SKIP LOCKED}
 * multi-replica safety, per-tenant transaction (the C1 self-invocation/auto-flush
 * fix), exponential backoff, MAX_ATTEMPTS FAILED valve, and resurrection pass.
 *
 * <p><b>The one deliberate simplification vs. the payment analog:</b>
 * {@link #publishRow} has NO closed-set exchange dispatch. A dedicated media
 * outbox has exactly one destination exchange, so publishing is a single
 * {@code objectMapper.readValue(payload, MediaProcessingEvent.class)} +
 * {@code convertAndSend(MEDIA_EVENTS_EXCHANGE, MEDIA_EVENTS_ROUTING_KEY, event)}.
 * This is precisely why the dedicated table sidesteps the
 * {@code outbox_flusher_dispatch_trap} — there is no else-branch that could
 * poison-cast a media payload, and the hardened PaymentEventOutboxFlusher stays
 * untouched.
 */
@Component
public class MediaEventOutboxFlusher {
    private static final Logger log = LoggerFactory.getLogger(MediaEventOutboxFlusher.class);

    /**
     * Attempts before a row flips FAILED. An ops-visibility valve, not a kill
     * switch: the resurrection pass re-leases non-poison FAILED rows, so no
     * transiently-failing event is ever permanently dropped.
     */
    static final int MAX_ATTEMPTS = 10;

    /** Max rows claimed per tenant per tick — keeps each tick bounded. */
    static final int BATCH_SIZE = 100;

    private final MediaEventOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Counter deadLetterCounter;
    private final Counter resurrectedCounter;
    private final long backoffBaseMs;
    private final long backoffCapMs;

    public MediaEventOutboxFlusher(MediaEventOutboxRepository repository,
                                   RabbitTemplate rabbitTemplate,
                                   ObjectMapper objectMapper,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager,
                                   ObjectProvider<MeterRegistry> meterRegistryProvider,
                                   @Value("${media.outbox.backoff-base-ms:5000}") long backoffBaseMs,
                                   @Value("${media.outbox.backoff-cap-ms:300000}") long backoffCapMs) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        // Programmatic per-tenant transactions: a plain @Transactional on a private
        // per-tenant method would be silently ignored (self-invocation proxy trap),
        // so the template is the idiomatic shape (same as PaymentEventOutboxFlusher).
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.backoffBaseMs = backoffBaseMs;
        this.backoffCapMs = backoffCapMs;
        MeterRegistry reg = meterRegistryProvider.getIfAvailable();
        this.deadLetterCounter = reg != null
                ? Counter.builder("media.outbox.dead_letter")
                    .description("Media events that exceeded retry limit and were marked FAILED")
                    .register(reg)
                : null;
        this.resurrectedCounter = reg != null
                ? Counter.builder("media.outbox.resurrected")
                    .description("FAILED media outbox events returned to PENDING by the resurrection pass")
                    .register(reg)
                : null;
    }

    /**
     * Exponential backoff with a cap: {@code base * 2^(attempts-1)}, clamped to
     * {@code capMs}. Pure function so the maths is unit-testable (copied verbatim
     * from the payment flusher — overflow-safe doubling loop).
     */
    static long computeBackoffMillis(int attempts, long baseMs, long capMs) {
        if (attempts < 1 || baseMs <= 0) {
            return Math.min(Math.max(baseMs, 0), capMs);
        }
        long backoff = baseMs;
        for (int i = 1; i < attempts && backoff < capMs; i++) {
            backoff <<= 1;
            if (backoff <= 0) { // overflow guard for absurdly large caps
                return capMs;
            }
        }
        return Math.min(backoff, capMs);
    }

    /**
     * Flush publishable media events per-tenant. Iterates per-tenant so the RLS
     * tenant GUC is pinned before each claim (media_event_outbox is FORCE RLS), and
     * so one tenant's failure can neither roll back nor starve another's events.
     */
    @Scheduled(fixedDelayString = "${media.outbox.flush-interval-ms:5000}")
    public void flushPending() {
        for (UUID tenantId : listTenantIds()) {
            try {
                flushTenant(tenantId);
            } catch (Exception e) {
                log.error("Media outbox flush failed for tenant {} — continuing with remaining tenants",
                        tenantId, e);
            }
        }
    }

    /** Claim -> publish -> writeback for ONE tenant, in its own transaction. */
    private void flushTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                List<MediaEventOutbox> claimed = repository.claimPendingBatch(BATCH_SIZE);
                if (claimed.isEmpty()) {
                    return;
                }
                log.debug("Claimed {} publishable media events for tenant {}", claimed.size(), tenantId);
                for (MediaEventOutbox row : claimed) {
                    publishRow(row);
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Resurrection pass: return retry-exhausted non-poison FAILED rows to PENDING so
     * they drain once the broker recovers. Same per-tenant transaction shape as the
     * flush (cross-tenant rollback isolation).
     */
    @Scheduled(fixedDelayString = "${media.outbox.resurrect-interval-ms:300000}")
    public void resurrectFailed() {
        for (UUID tenantId : listTenantIds()) {
            try {
                resurrectTenant(tenantId);
            } catch (Exception e) {
                log.error("Media outbox resurrection failed for tenant {} — continuing with remaining tenants",
                        tenantId, e);
            }
        }
    }

    /** Resurrect ONE tenant's non-poison FAILED rows, in its own transaction. */
    private void resurrectTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                int resurrected = repository.resurrectFailed();
                if (resurrected > 0) {
                    log.warn("Resurrected {} FAILED media outbox events for tenant {} — returning to PENDING for retry",
                            resurrected, tenantId);
                    if (resurrectedCounter != null) {
                        resurrectedCounter.increment(resurrected);
                    }
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        return entityManager
                .createNativeQuery("SELECT id FROM tenants")
                .getResultList();
    }

    /**
     * Publish ONE row. Single exchange, single payload type — NO closed-set dispatch
     * (that is the whole point of a dedicated outbox: the {@code outbox_flusher_dispatch_trap}
     * simply cannot arise here).
     */
    private void publishRow(MediaEventOutbox row) {
        try {
            MediaProcessingEvent event = objectMapper.readValue(row.getPayload(), MediaProcessingEvent.class);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.MEDIA_EVENTS_EXCHANGE, RabbitMQConfig.MEDIA_EVENTS_ROUTING_KEY, event);
            row.setStatus(MediaEventOutbox.Status.SENT);
            row.setSentAt(OffsetDateTime.now());
            row.setLastError(null);
            repository.save(row);
            log.info("Flushed media outbox event (id={}, asset={})", row.getId(), row.getAssetId());
        } catch (JsonProcessingException e) {
            // Payload corruption — not recoverable by retry. Mark FAILED + poison so
            // the resurrection pass never re-leases it.
            row.setStatus(MediaEventOutbox.Status.FAILED);
            row.setPoison(true);
            row.setLastError("payload deserialization failed: " + e.getMessage());
            row.setAttempts(row.getAttempts() + 1);
            repository.save(row);
            log.error("Media outbox row {} is unrecoverable (poisoned)", row.getId(), e);
            if (deadLetterCounter != null) deadLetterCounter.increment();
        } catch (Exception e) {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(e.getMessage());
            long backoffMs = computeBackoffMillis(attempts, backoffBaseMs, backoffCapMs);
            row.setNextAttemptAt(OffsetDateTime.now().plusNanos(backoffMs * 1_000_000L));
            if (attempts >= MAX_ATTEMPTS) {
                row.setStatus(MediaEventOutbox.Status.FAILED);
                if (deadLetterCounter != null) deadLetterCounter.increment();
                log.error("Media outbox event {} exhausted {} attempts; marking FAILED pending resurrection",
                        row.getId(), MAX_ATTEMPTS);
            } else {
                log.warn("Publish attempt {}/{} failed for media outbox event {} (retry in {} ms): {}",
                        attempts, MAX_ATTEMPTS, row.getId(), backoffMs, e.getMessage());
            }
            repository.save(row);
        }
    }
}
