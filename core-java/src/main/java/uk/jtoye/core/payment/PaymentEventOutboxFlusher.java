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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled flusher that drains PENDING rows from {@link PaymentEventOutbox}
 * and publishes them to RabbitMQ (Issue #93 hardened).
 *
 * <p><b>Multi-replica safety:</b> rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED} inside the flusher transaction, so N
 * replicas running the same schedule partition the PENDING set — no row is
 * ever published by two replicas concurrently. The guarantee is at-least-once
 * (a crash between AMQP publish and commit re-publishes that row), which is
 * the standard transactional-outbox contract; consumers must be idempotent.
 *
 * <p><b>Backoff + resurrection:</b> a failed publish reschedules the row via
 * {@code next_attempt_at = now + base * 2^(attempts-1)} (capped), so a broker
 * outage no longer burns one attempt per 5s tick. After {@link #MAX_ATTEMPTS}
 * the row flips to FAILED as an operator signal (dead-letter counter), but a
 * scheduled resurrection pass returns non-poison FAILED rows to PENDING so
 * events always drain once the broker recovers. Only {@code poison} rows
 * (unrecoverable payload corruption) stay FAILED forever.
 *
 * <p><b>Success</b> → row marked SENT with {@code sent_at=now}.
 */
@Component
public class PaymentEventOutboxFlusher {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventOutboxFlusher.class);

    /**
     * Attempts before a row flips FAILED. This is an ops-visibility valve,
     * not a kill switch: the resurrection pass re-leases non-poison FAILED
     * rows, so no transiently-failing event is ever permanently dropped.
     * With 5s base / 5min cap backoff, 10 attempts spans roughly 20 minutes
     * of continuous outage before the first FAILED flip.
     */
    static final int MAX_ATTEMPTS = 10;

    /** Max rows claimed per tenant per tick — keeps each tick bounded. */
    static final int BATCH_SIZE = 100;

    private final PaymentEventOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Counter deadLetterCounter;
    private final Counter resurrectedCounter;
    private final long backoffBaseMs;
    private final long backoffCapMs;

    public PaymentEventOutboxFlusher(PaymentEventOutboxRepository repository,
                                     RabbitTemplate rabbitTemplate,
                                     ObjectMapper objectMapper,
                                     EntityManager entityManager,
                                     PlatformTransactionManager transactionManager,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider,
                                     @Value("${payment.outbox.backoff-base-ms:5000}") long backoffBaseMs,
                                     @Value("${payment.outbox.backoff-cap-ms:300000}") long backoffCapMs) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        // Programmatic per-tenant transactions (QA-council C1 fix). A plain
        // @Transactional on a private per-tenant method would be silently
        // ignored (Spring self-invocation proxy trap), so the template is the
        // idiomatic shape — same pattern as DemoDataSeeder/ScheduledCleanup.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.backoffBaseMs = backoffBaseMs;
        this.backoffCapMs = backoffCapMs;
        MeterRegistry reg = meterRegistryProvider.getIfAvailable();
        this.deadLetterCounter = reg != null
                ? Counter.builder("payment.outbox.dead_letter")
                    .description("Payment events that exceeded retry limit and were marked FAILED")
                    .register(reg)
                : null;
        this.resurrectedCounter = reg != null
                ? Counter.builder("payment.outbox.resurrected")
                    .description("FAILED outbox events returned to PENDING by the resurrection pass")
                    .register(reg)
                : null;
    }

    /**
     * Exponential backoff with a cap: {@code base * 2^(attempts-1)}, clamped
     * to {@code capMs}. Pure function so the maths is unit-testable.
     *
     * @param attempts number of attempts already made (>= 1)
     */
    static long computeBackoffMillis(int attempts, long baseMs, long capMs) {
        if (attempts < 1 || baseMs <= 0) {
            return Math.min(Math.max(baseMs, 0), capMs);
        }
        // Double until the cap is reached. The loop exits as soon as backoff
        // >= capMs, so it runs at most ~log2(cap/base) iterations and never
        // hits Java's shift-wraparound (a single `base << (attempts-1)` wraps
        // to garbage — including exactly 0 — for large attempt counts).
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
     * Flush publishable payment events per-tenant.
     *
     * <p>SECURITY: Iterates per-tenant to ensure TenantContext is set before
     * each query. The payment_event_outbox table has RLS (V33), so queries
     * without TenantContext would return empty or fail. This also prevents
     * cross-tenant event leakage to RabbitMQ listeners.
     *
     * <p>TRANSACTIONS (QA-council C1 fix): each tenant is drained in its OWN
     * transaction. The RLS tenant GUC ({@code app.current_tenant_id}) is
     * transaction-scoped, so a single whole-method transaction spanning all
     * tenants made Hibernate auto-flush tenant A's dirty {@code SENT} updates
     * at tenant B's native claim query — under B's GUC. FORCE RLS (V33) hid
     * A's rows from that UPDATE → {@code StaleStateException} → rollback of
     * the entire pass including the failure-path writeback (attempts stayed 0,
     * backoff never engaged) → the same rows re-published every tick, forever.
     * Per-tenant transactions guarantee every flush/commit runs under the GUC
     * of the tenant that owns the dirty rows, regardless of tenant iteration
     * order, and one tenant's failure can neither roll back nor starve the
     * other tenants' events.
     *
     * <p>CONCURRENCY: {@code claimPendingBatch} uses FOR UPDATE SKIP LOCKED,
     * so a second replica ticking at the same moment claims a disjoint set of
     * rows (usually none) instead of double-publishing this one's batch. The
     * locks are now held for one tenant's batch rather than the whole pass.
     */
    @Scheduled(fixedDelayString = "${payment.outbox.flush-interval-ms:5000}")
    public void flushPending() {
        for (UUID tenantId : listTenantIds()) {
            try {
                flushTenant(tenantId);
            } catch (Exception e) {
                log.error("Outbox flush failed for tenant {} — continuing with remaining tenants",
                        tenantId, e);
            }
        }
    }

    /** Claim → publish → writeback for ONE tenant, in its own transaction. */
    private void flushTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                List<PaymentEventOutbox> claimed = repository.claimPendingBatch(BATCH_SIZE);
                if (claimed.isEmpty()) {
                    return;
                }
                log.debug("Claimed {} publishable payment events for tenant {}", claimed.size(), tenantId);
                for (PaymentEventOutbox row : claimed) {
                    publishRow(row);
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Resurrection pass (Issue #93): return retry-exhausted FAILED rows to
     * PENDING so they drain once the broker recovers. Poison rows (payload
     * corruption) are excluded — see {@link PaymentEventOutbox#isPoison()}.
     * Runs far less often than the flusher; multiple replicas racing here is
     * harmless because the UPDATE is idempotent.
     *
     * <p>Same per-tenant transaction shape as {@link #flushPending()} (C1 fix
     * symmetry): the bulk UPDATE was never bitten by the auto-flush anomaly
     * (no dirty entities between iterations), but the whole-method transaction
     * had the same cross-tenant rollback coupling — one tenant's failure would
     * discard every other tenant's resurrection.
     */
    @Scheduled(fixedDelayString = "${payment.outbox.resurrect-interval-ms:300000}")
    public void resurrectFailed() {
        for (UUID tenantId : listTenantIds()) {
            try {
                resurrectTenant(tenantId);
            } catch (Exception e) {
                log.error("Outbox resurrection failed for tenant {} — continuing with remaining tenants",
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
                    log.warn("Resurrected {} FAILED outbox events for tenant {} — returning to PENDING for retry",
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

    private void publishRow(PaymentEventOutbox row) {
        try {
            // V36 — per-row exchange routing. Refund + order-state rows write
            // 'order.events', payment rows write 'payment.events' (legacy
            // default). NULL is defensive: any pre-V36 in-flight row would
            // have exchange=NULL before flush; log + fall back so we don't
            // drop the message.
            String exchange = row.getExchange();
            if (exchange == null || exchange.isBlank()) {
                log.warn("Outbox row {} has no exchange — falling back to {}",
                        row.getId(), RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE);
                exchange = RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE;
            }

            // Deserialize payload according to event family so the AMQP
            // message carries the same __TypeId__ the listeners expect:
            //   order.events + 'order.state.*'  → OrderStateChangeEvent (#93)
            //   order.events (order.refunded)   → RefundEvent (V36)
            //   onboarding.events               → OnboardingStateChangeEvent (Phase 21)
            //   payment.events                  → PaymentEvent (V31)
            //
            // The onboarding branch MUST precede the final else: that else is a
            // poison sink — it casts anything unrecognised to PaymentEvent,
            // which for an onboarding payload throws JsonProcessingException →
            // the row is marked poison-FAILED and dead-lettered (Pitfall 1).
            Object event;
            if (RabbitMQConfig.ORDER_EVENTS_EXCHANGE.equals(exchange)) {
                if (row.getRoutingKey() != null
                        && row.getRoutingKey().startsWith(OrderEventPublisher.ORDER_STATE_ROUTING_PREFIX)) {
                    event = objectMapper.readValue(row.getPayload(), OrderStateChangeEvent.class);
                } else {
                    event = objectMapper.readValue(row.getPayload(), RefundEvent.class);
                }
            } else if (RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE.equals(exchange)) {
                event = objectMapper.readValue(row.getPayload(), OnboardingStateChangeEvent.class);
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
            // Payload corruption — not recoverable by retry. Mark FAILED and
            // poison it so the resurrection pass never re-leases it.
            row.setStatus(PaymentEventOutbox.Status.FAILED);
            row.setPoison(true);
            row.setLastError("payload deserialization failed: " + e.getMessage());
            row.setAttempts(row.getAttempts() + 1);
            repository.save(row);
            log.error("Outbox row {} is unrecoverable (poisoned)", row.getId(), e);
            if (deadLetterCounter != null) deadLetterCounter.increment();
        } catch (Exception e) {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(e.getMessage());
            long backoffMs = computeBackoffMillis(attempts, backoffBaseMs, backoffCapMs);
            row.setNextAttemptAt(OffsetDateTime.now().plusNanos(backoffMs * 1_000_000L));
            if (attempts >= MAX_ATTEMPTS) {
                // Ops signal only — the resurrection pass will re-lease this
                // row (poison=false), so the event still drains on recovery.
                row.setStatus(PaymentEventOutbox.Status.FAILED);
                if (deadLetterCounter != null) deadLetterCounter.increment();
                log.error("Outbox event {} exhausted {} attempts; marking FAILED pending resurrection",
                        row.getId(), MAX_ATTEMPTS);
            } else {
                log.warn("Publish attempt {}/{} failed for outbox event {} (retry in {} ms): {}",
                        attempts, MAX_ATTEMPTS, row.getId(), backoffMs, e.getMessage());
            }
            repository.save(row);
        }
    }
}
