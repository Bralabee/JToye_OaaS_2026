# Backend Remediation Pair
**Specialist**: Senior Backend Engineer (Spring Boot multi-tenant SaaS, 15y)
**Assistant**: Distributed Systems Reviewer
**Date**: 2026-04-27
**Total scope**: 10 findings, estimated **22 eng-hours** (3 focused days; ~14h Wave 1+2 blockers, ~8h Wave 3 hardening)

---

## Cross-cutting principles

The pair is optimising for **two outcomes simultaneously**: (a) close the multi-tenant data-leak surface so the platform is shippable to a second paying tenant without the founder having to sleep at the office, and (b) leave the codebase *less* clever than they found it — every Spring abstraction the team reached for (StateMachine, `@EnableAsync` defaults, `@CacheEvict(allEntries=true)`) became a foot-gun once tenancy entered the picture. The default move is to delete cleverness, not add it. Where a Spring abstraction earns its keep (RFC 7807 `ProblemDetail`, the per-tenant outbox, optimistic locking with `@Version`), they leave it alone.

The trade-offs they are deliberately accepting: (i) hand-rolled transition table over Spring StateMachine — loses framework-level visualisation tooling, gains testability and ~5ms per transition; (ii) `processed_stripe_events` PRIMARY-KEY-only table over a full outbox — loses replay capability, gains a 10-line guard that runs in the webhook transaction; (iii) deferring `/v1/...` URI versioning to "first breaking change + 1 sprint warning" — loses zero-downtime rollback for a hypothetical breaking change, gains four engineering hours that are better spent on the SSE leak. They are *not* trading away tenant isolation, payment idempotency, or correctness anywhere.

Sequencing matters: Finding 3 (`AsyncConfig` + `TaskDecorator`) ships before Finding 2's regression test runs, because that test exercises the async email path the webhook fans out to. Finding 1 ships first, before anything else, because it is the lowest-effort highest-blast-radius bug in the whole audit.

---

## Finding 1: OrderSseService cross-tenant broadcast

### Specialist proposal

**Files touched**: `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java`, `core-java/src/main/java/uk/jtoye/core/order/OrderController.java:43-47`, new test `core-java/src/test/java/uk/jtoye/core/order/OrderSseServiceTenantIsolationTest.java`.

The SSE service today (`OrderSseService.java:17`) holds `private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>()` — a single global list. `subscribe()` (line 19) appends without reading `TenantContext`, and `broadcast()` (line 29) iterates the whole list. `OrderStateChangeEvent` carries `tenantId` (`OrderStateChangeEvent.java:8`) so the data is *available* — it just isn't being filtered.

Replace the single list with a `ConcurrentHashMap<UUID, Set<SseEmitter>>` keyed by the tenant captured at subscribe time. Capture happens inside the controller request-scope where `TenantContext` is populated by `JwtTenantFilter`. Broadcast filters by `event.tenantId()`.

Full new file:

```java
package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderSseService {
    private static final Logger log = LoggerFactory.getLogger(OrderSseService.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

    /** Per-tenant emitter sets. Outer map is mutation-safe (CHM); inner sets are
     *  newSetFromMap(CHM) to allow concurrent add/remove during broadcast iteration. */
    private final ConcurrentHashMap<UUID, Set<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();

    public SseEmitter subscribe() {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException(
                        "SSE subscribe attempted without TenantContext — refusing to attach a tenant-less emitter"));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Set<SseEmitter> bucket = emittersByTenant.computeIfAbsent(
                tenantId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        bucket.add(emitter);

        Runnable cleanup = () -> {
            bucket.remove(emitter);
            // Free the bucket entry once empty so long-lived JVMs don't leak per-tenant maps.
            emittersByTenant.computeIfPresent(tenantId, (k, v) -> v.isEmpty() ? null : v);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.debug("SSE client subscribed for tenant {}, tenant-bucket size: {}", tenantId, bucket.size());
        return emitter;
    }

    public void broadcast(OrderStateChangeEvent event) {
        Set<SseEmitter> bucket = emittersByTenant.get(event.tenantId());
        if (bucket == null || bucket.isEmpty()) {
            log.debug("No SSE subscribers for tenant {} — skipping broadcast", event.tenantId());
            return;
        }
        log.debug("Broadcasting order state change to {} SSE clients for tenant {}",
                bucket.size(), event.tenantId());
        for (SseEmitter emitter : bucket) {
            try {
                emitter.send(SseEmitter.event()
                        .name("order-state-change")
                        .data(event));
            } catch (IOException e) {
                bucket.remove(emitter);
            }
        }
    }
}
```

Full new regression test:

```java
package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderSseServiceTenantIsolationTest {

    private final OrderSseService service = new OrderSseService();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("subscribe — refuses to attach an emitter when TenantContext is unset")
    void subscribeRequiresTenant() {
        TenantContext.clear();
        assertThrows(IllegalStateException.class, service::subscribe);
    }

    @Test
    @DisplayName("broadcast — only delivers to emitters of the event's tenant")
    @SuppressWarnings("unchecked")
    void broadcastIsTenantScoped() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        SseEmitter emitterA = service.subscribe();
        TenantContext.set(tenantB);
        SseEmitter emitterB = service.subscribe();
        TenantContext.clear();

        // Broadcast event scoped to tenantA only.
        OrderStateChangeEvent eventForA = new OrderStateChangeEvent(
                UUID.randomUUID(), tenantA, "ORD-A-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
        service.broadcast(eventForA);

        // Reach into the private map to assert isolation — each tenant has its own bucket
        // and tenantB's emitter is still registered (was never sent to and therefore never IO-failed-out).
        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        Map<UUID, Set<SseEmitter>> map = (Map<UUID, Set<SseEmitter>>) f.get(service);

        assertTrue(map.containsKey(tenantB), "tenant B bucket should exist and contain its untouched emitter");
        assertTrue(map.get(tenantB).contains(emitterB), "tenant B emitter should not have been written to");
        assertNotNull(emitterA, "tenant A emitter is the only one that should receive a payload");
    }

    @Test
    @DisplayName("broadcast — no-op when no subscribers exist for the event's tenant")
    void broadcastNoOpForUnknownTenant() {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(tenantA);
        service.subscribe();
        TenantContext.clear();

        OrderStateChangeEvent eventForC = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-C-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());

        assertDoesNotThrow(() -> service.broadcast(eventForC));
    }
}
```

The existing `OrderSseServiceTest` keeps passing (it never covered isolation, only emitter-count semantics) — but two of its tests need a `TenantContext.set(...)` shim because `subscribe()` now refuses tenant-less calls. That's a 6-line edit at lines 22-25 (`@BeforeEach`) of the existing test, plus an `@AfterEach` clearing.

**Eng-hours**: 1.5h (1h impl + 0.5h test + sanity-port of existing test).

**Rollout**:
1. Land the diff on a feature branch `feature/sse-tenant-isolation`.
2. Run `./gradlew :core-java:test --tests "OrderSseService*"` — must be green.
3. Manual smoke: spin up two tenant browser sessions on `/dashboard/orders` (port 3100), transition an order in tenant A, confirm tenant B's stream stays silent (the synthesis author's own honest critique called this out as a "should be E2E-verified" item).
4. Squash-merge, deploy.

**Rollback**: revert the merge commit; no migration, no data, no schema change. Pure code.

### Assistant deliberation

- **VALIDATE**: The data-structure choice is correct. `ConcurrentHashMap<UUID, Set<SseEmitter>>` with `Collections.newSetFromMap(new ConcurrentHashMap<>())` for the inner set is the canonical "concurrent multimap" pattern; `CopyOnWriteArraySet` would re-introduce the same per-mutation full-array-copy cost the audit already flagged for the outer list. The `computeIfPresent(... v.isEmpty() ? null : v)` cleanup is a real touch — without it a 10k-tenant SaaS leaks one map entry per ever-disconnected SSE client.

- **CHALLENGE**: `subscribe()` throwing `IllegalStateException` on missing TenantContext is functionally correct but routes through `GlobalExceptionHandler.handleIllegalState` (line 55-61) which returns **400 Bad Request**. For an SSE endpoint, the browser's `EventSource` will silently retry the 400 forever — the user gets no error, just no events. Should be `401 Unauthorized` (tenant comes from JWT) or thrown earlier in the controller as `AuthenticationException`. Recommend: keep the throw inside the service (defence-in-depth), but add an `Authentication`-derived guard in `OrderController.streamOrderEvents()` that returns a 401 ProblemDetail if `TenantContext` is empty *before* calling `subscribe()`. **Specialist accepts.**

- **RISK**: The proposed test uses reflection to inspect `emittersByTenant`. That's a knowingly fragile test — the day someone renames the field, the test silently breaks (or worse, no longer asserts anything). The test should also assert *positively* that tenant A's emitter received the event. Reading from a `SseEmitter` after `send()` requires either (a) wiring a `MockHttpServletResponse` (verbose) or (b) using `ResponseBodyEmitter`'s `getRegisteredCallbacks` indirectly. Neither is ideal. Pragmatic: keep the reflection assertion **and** add a `Mockito.spy(emitter)` on tenant A's side, verifying `send(...)` is called exactly once for tenant A's event and zero times for tenant B's emitter. That's the test that catches a future regression.

- **ALTERNATIVE**: A "more Spring-native" path is to bin SSE entirely and route everything via STOMP, which the team already correctly tenant-segments in `OrderStateChangeListener.java:51-58` and `TenantChannelInterceptor.java:110-140`. The SSE endpoint is a parallel pipeline that exists because of the dashboard-vs-storefront UI split. Long-term recommendation is "delete SSE, use STOMP", but that's a Day-2 frontend refactor — out of scope for this pre-prod blocker.

### Reconciled position

Ship the structural fix as proposed, with two amendments from the assistant:
1. Add a guard in `OrderController.streamOrderEvents()` that returns 401 when `TenantContext.get().isEmpty()` — `IllegalStateException` from the service stays as a defence-in-depth tripwire but should never fire under correct routing.
2. Replace pure-reflection assertions in the regression test with `Mockito.spy(SseEmitter)` verifying tenant A's emitter receives exactly one `send()` and tenant B's receives zero.

Day-2 follow-up issue: "Evaluate replacing SSE with the existing STOMP topic; SSE then deletes ~50 LOC and one parallel pipeline."

---

## Finding 2: Stripe webhook idempotency

### Specialist proposal

**Files touched**: new migration `core-java/src/main/resources/db/migration/V35__processed_stripe_events.sql`, new entity `core-java/src/main/java/uk/jtoye/core/payment/ProcessedStripeEvent.java`, new repository `ProcessedStripeEventRepository.java`, modified `PaymentService.java:113-132`, new test `PaymentWebhookIdempotencyIntegrationTest.java`.

V35 migration in full:

```sql
-- V35: Stripe webhook idempotency guard.
--
-- Stripe retries successful webhooks if our handler is slow or returns 5xx,
-- and retries failed webhooks for up to 72h. Without an event-id guard, every
-- retry of payment_intent.succeeded re-runs handlePaymentIntentSucceeded,
-- which (a) double-publishes order state-change events, (b) fans-out a second
-- customer email, and (c) writes a duplicate financial_transactions row.
--
-- This table records every Stripe event_id we have begun processing. The
-- application inserts ON CONFLICT DO NOTHING at the top of the webhook
-- handler; if the insert affected zero rows, the event is a duplicate and
-- the handler returns 200 immediately so Stripe stops retrying.
--
-- event_id is globally unique across Stripe accounts; no tenant scoping needed.
-- The table is intentionally NOT row-level-secured: it is an
-- infrastructure-level idempotency log, not tenant data, and the webhook
-- handler runs before any TenantContext is established.

CREATE TABLE processed_stripe_events (
    event_id      VARCHAR(255) PRIMARY KEY,
    event_type    VARCHAR(100) NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Captured from PaymentIntent.metadata for forensic linkage; nullable for
    -- non-PI events we may handle in future (e.g. charge.dispute.created).
    tenant_id     UUID         NULL,
    order_id      UUID         NULL
);

-- 90-day retention TTL is enforced by ScheduledCleanupService (separate ticket);
-- the index supports it cheaply.
CREATE INDEX idx_processed_stripe_events_received_at
    ON processed_stripe_events (received_at);
```

Entity:

```java
package uk.jtoye.core.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_stripe_events")
public class ProcessedStripeEvent {

    @Id
    @Column(name = "event_id", length = 255, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "order_id")
    private UUID orderId;

    protected ProcessedStripeEvent() {} // JPA

    public ProcessedStripeEvent(String eventId, String eventType, UUID tenantId, UUID orderId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.receivedAt = OffsetDateTime.now();
        this.tenantId = tenantId;
        this.orderId = orderId;
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOrderId() { return orderId; }
}
```

Repository:

```java
package uk.jtoye.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {
    boolean existsByEventId(String eventId);
}
```

Modified `PaymentService.handleWebhookEvent` — only the body changes; the constructor gains a `ProcessedStripeEventRepository` dependency:

```java
@Transactional
public void handleWebhookEvent(String payload, String sigHeader) {
    Event event;
    try {
        event = Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
    } catch (SignatureVerificationException e) {
        log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
        throw new IllegalArgumentException("Invalid Stripe signature");
    } catch (Exception e) {
        log.error("Failed to parse Stripe webhook event", e);
        throw new IllegalArgumentException("Invalid webhook payload");
    }

    // IDEMPOTENCY GUARD — must run BEFORE any side-effect.
    // existsByEventId is a cheap PK lookup (~1ms). The unique-constraint race
    // is also caught at insert time below by DataIntegrityViolationException.
    if (processedStripeEventRepository.existsByEventId(event.getId())) {
        log.info("Stripe event {} ({}) already processed — skipping", event.getId(), event.getType());
        return;
    }

    // Capture tenant + order from PI metadata (best-effort, for audit).
    UUID tenantId = null;
    UUID orderId = null;
    try {
        if (event.getDataObjectDeserializer().getObject().orElse(null) instanceof PaymentIntent pi) {
            String t = pi.getMetadata().get("tenant_id");
            String o = pi.getMetadata().get("order_id");
            if (t != null) tenantId = UUID.fromString(t);
            if (o != null) orderId = UUID.fromString(o);
        }
    } catch (Exception ignore) { /* metadata best-effort only */ }

    try {
        processedStripeEventRepository.saveAndFlush(
                new ProcessedStripeEvent(event.getId(), event.getType(), tenantId, orderId));
    } catch (DataIntegrityViolationException race) {
        // Concurrent retry inserted the same event_id between our existsByEventId
        // check and this insert. Treat as already-processed.
        log.info("Stripe event {} race-detected at insert — already processed", event.getId());
        return;
    }

    log.info("Received Stripe event: {} ({})", event.getType(), event.getId());

    switch (event.getType()) {
        case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event);
        case "payment_intent.payment_failed" -> handlePaymentIntentFailed(event);
        default -> log.debug("Unhandled Stripe event type: {}", event.getType());
    }
}
```

Test (sketch — the test infra mirrors the existing `PaymentServiceTest`'s `MockedStatic<Webhook>` pattern but moves to a Testcontainers-backed slice so the `processed_stripe_events` row is real):

```java
package uk.jtoye.core.payment;

import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import uk.jtoye.core.finance.FinancialTransactionRepository;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class PaymentWebhookIdempotencyIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private ProcessedStripeEventRepository processedRepo;
    @Autowired private FinancialTransactionRepository financialRepo;
    @MockBean private com.stripe.model.Charge chargeStub; // avoid network on retrieve

    @Test
    void doubleDeliveredPaymentIntentSucceededIsProcessedOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String eventId = "evt_test_" + UUID.randomUUID();

        // Build a stub Stripe Event whose data object is a PaymentIntent with
        // tenant_id + order_id metadata pointing at an order seeded by your
        // test fixture (omitted for brevity; mirror PaymentServiceTest fixture).
        Event stubEvent = TestStripeEventFactory.paymentIntentSucceeded(
                eventId, orderId, tenantId, /* amountPennies */ 1500L);

        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubEvent);

            paymentService.handleWebhookEvent("payload", "sig");
            paymentService.handleWebhookEvent("payload", "sig"); // duplicate
            paymentService.handleWebhookEvent("payload", "sig"); // triplicate
        }

        assertTrue(processedRepo.existsByEventId(eventId), "event should be recorded");
        long financialRows = financialRepo.findAll().stream()
                .filter(t -> t.getDescription() != null
                        && t.getDescription().contains("Order"))
                .count();
        assertEquals(1, financialRows,
                "exactly one financial transaction must result from a triple-delivered webhook");
    }
}
```

**Eng-hours**: 3h (0.5h migration, 0.5h entity+repo, 0.5h handler, 1h test fixture, 0.5h CI run + verify).

**Rollout**:
1. Land migration V35 first on a feature branch — Flyway picks it up at next boot.
2. Land Java code in same PR (entity, repo, handler change, test).
3. Verify in staging: replay a `payment_intent.succeeded` event from Stripe CLI three times, observe one `financial_transactions` row, observe three `processed_stripe_events` rows? — **No: one `processed_stripe_events` row, but `received_at` is the first one's timestamp.** Confirm via SQL.
4. Add a Grafana panel querying `count(*) FROM processed_stripe_events WHERE received_at > now() - interval '1 hour'` so the team has visibility on webhook volume.

**Rollback**: revert merge → V35 cannot be auto-rolled back by Flyway (table now exists with rows after first webhook). Manual `DROP TABLE processed_stripe_events;` + Flyway repair if a true rollback is needed. Acceptable risk: the table is additive; leaving it in a DB whose code no longer uses it is harmless.

### Assistant deliberation

- **CHALLENGE**: The `existsByEventId` check + later insert is a TOCTOU race window. Two parallel webhook deliveries hitting two pods at the same millisecond both pass the `exists` check, both attempt the insert, one wins, the loser hits `DataIntegrityViolationException` from the unique PK. The specialist's code handles this correctly (the `try/catch DataIntegrityViolationException` after `saveAndFlush`) — but the order is "check, then insert". A purer pattern is to skip the existence check and rely solely on `saveAndFlush` + catch. That's one fewer DB round-trip in the happy path. **Specialist counters**: keeping the explicit `existsByEventId` is cheaper for the *retry* path (the common case) — Stripe retries 3-5 times for slow handlers; the existence check short-circuits before the insert + flush + exception unwinding. Net: keep both, document why.

- **VALIDATE**: Parking the `processed_stripe_events` table outside RLS is correct. The webhook handler runs before `TenantContext` is set (the handler *derives* tenant from PI metadata mid-flight), so an RLS policy keyed on `app.current_tenant_id` would either reject the insert or require setting context for an infrastructure-level table. Cleaner to mark it explicitly tenant-agnostic with a comment, which the migration does.

- **RISK**: Stripe `event.getId()` is unique per *delivery attempt* of an event in some Stripe API versions (older API), and per *event* in newer (`>= 2019-02-11`). The team's stack is current Stripe Java SDK 28.2.0 → API version sufficient → safe. But pin the assumption: add an integration test asserting that `Webhook.constructEvent` returns the same `event.getId()` on a re-delivery payload. (Trivial: the team already mocks `Webhook.constructEvent` in `PaymentServiceTest`.)

- **ALTERNATIVE**: Instead of a separate table, repurpose `payment_event_outbox` (V31) with a new `direction` column. **Rejected**: outbox is *outbound* event reliability; processed-events is *inbound* idempotency. Conflating them couples two different durability guarantees. A separate table is the cheaper conceptual purchase.

### Reconciled position

Ship the migration + handler change as proposed, including the dual-guard (`existsByEventId` + `try/catch DataIntegrityViolationException`) — the redundancy is paying its rent because of the multi-pod retry race. Add the assistant's API-version-pin test to the suite. Add a one-line Grafana note to the rollout. Defer the 90-day TTL cleanup to `ScheduledCleanupService` as a follow-up ticket (~30 min, separate PR).

---

## Finding 3: `@EnableAsync` with no executor + no TaskDecorator

### Specialist proposal

**Files touched**: new `core-java/src/main/java/uk/jtoye/core/config/AsyncConfig.java`, new test `core-java/src/test/java/uk/jtoye/core/config/AsyncConfigTaskDecoratorIntegrationTest.java`. `CoreApplication.java:12` keeps `@EnableAsync` — it now picks up the bean.

```java
package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for {@code @Async} methods, with a TaskDecorator that
 * snapshots TenantContext on the submitting thread and re-applies it on the
 * worker thread for the duration of the task.
 *
 * <p>Without this configuration, {@code @EnableAsync} on CoreApplication uses
 * SimpleAsyncTaskExecutor — unbounded thread spawning, no propagation of any
 * thread-local. Today only EmailNotificationService is @Async and it does not
 * touch the DB; the moment any @Async method calls a tenant-scoped repository,
 * RLS would silently bypass on the worker thread and cross-tenant data could
 * be read or written.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(200);
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("jtoye-async-");
        // CALLER_RUNS: when the queue is full and pool maxed, the submitting
        // request thread runs the task itself — gives back-pressure that
        // Tomcat will translate into request latency rather than a silent drop.
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setTaskDecorator(tenantPropagatingDecorator());
        exec.initialize();
        log.info("AsyncConfig: bounded executor up — core=4, max=16, queue=200, decorator=tenantPropagating");
        return exec;
    }

    /**
     * Snapshots the submitter's TenantContext and re-applies it on the worker
     * thread. Worker thread pool is reused across tasks, so the {@code finally}
     * block MUST clear or a stale tenant id from a previous task leaks into
     * the next one (the same class of bug that caused the SSE leak, just on
     * a different thread pool).
     */
    @Bean
    public TaskDecorator tenantPropagatingDecorator() {
        return runnable -> {
            UUID tenantSnapshot = TenantContext.get().orElse(null);
            return () -> {
                UUID priorTenant = TenantContext.get().orElse(null);
                if (tenantSnapshot != null) {
                    TenantContext.set(tenantSnapshot);
                }
                try {
                    runnable.run();
                } finally {
                    if (priorTenant != null) {
                        TenantContext.set(priorTenant);
                    } else {
                        TenantContext.clear();
                    }
                }
            };
        };
    }
}
```

Test:

```java
package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = { AsyncConfigTaskDecoratorIntegrationTest.TestAsyncBean.class, AsyncConfig.class })
class AsyncConfigTaskDecoratorIntegrationTest {

    @Autowired private TestAsyncBean bean;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void asyncMethodSeesSubmitterTenantId() throws Exception {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(tenantA);

        CompletableFuture<UUID> future = bean.captureTenant();

        UUID seenOnWorker = future.get();
        assertEquals(tenantA, seenOnWorker, "task decorator must propagate submitter's tenant to worker");
    }

    @Test
    void workerThreadDoesNotLeakTenantToNextTask() throws Exception {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(tenantA);
        bean.captureTenant().get();

        // Now submit from a tenant-less context — worker pool reuses threads;
        // without the finally-clear, the worker would still see tenantA.
        TenantContext.clear();
        UUID seen = bean.captureTenant().get();

        assertNull(seen, "worker thread must not retain prior submitter's tenant id");
    }

    @Component
    static class TestAsyncBean {
        @Async
        public CompletableFuture<UUID> captureTenant() {
            return CompletableFuture.completedFuture(TenantContext.get().orElse(null));
        }
    }
}
```

**Eng-hours**: 2h (0.75h impl + 1h test + 0.25h smoke).

**Rollout**: Land in PR. Boot logs assert the bean wired ("AsyncConfig: bounded executor up..."). No data migration. Existing `@Async` methods in `EmailNotificationService` start using the new pool transparently.

**Rollback**: revert. Falls back to `SimpleAsyncTaskExecutor` — same pre-fix behaviour.

### Assistant deliberation

- **VALIDATE**: `CallerRunsPolicy` is the right rejection policy for a SaaS where back-pressure should manifest as latency rather than data loss. The alternative (`AbortPolicy`) would surface as `RejectedExecutionException` → 500 to the caller, which for an email-fanout in a payment webhook would mean retrying the *entire* webhook just because the email queue was full. CallerRunsPolicy avoids that.

- **CHALLENGE**: The decorator's "restore prior tenant in finally" pattern is correct, but the worker thread pool itself has no "submitter" — the only way `priorTenant != null` on a worker thread is if a previous task already leaked. So the `if (priorTenant != null) TenantContext.set(priorTenant)` branch is dead code on first iteration and only triggers in the leak-already-happened case. Cleaner: always `TenantContext.clear()` in finally; document that the worker pool is invariant-clear-at-rest. **Specialist counters**: the assistant is right that on workers it's effectively dead code, but this same `TaskDecorator` pattern is what people copy-paste into `ScheduledExecutorService` and reactive contexts where prior-context restoration *does* matter. Keep it for portability + correctness across submitter classes. Document the rationale in the Javadoc.

- **RISK**: `ThreadPoolTaskExecutor` with core=4 is fine for email burst but if a future `@Async` method does something CPU-bound (e.g. PDF generation in `OpenPDF` which the project depends on), 4 cores will be a bottleneck. Recommend: add a second named executor `pdfExecutor` (`@Async("pdfExecutor")`) with its own bounded pool when that workload arrives. Don't pre-build it. **Accepted as backlog note.**

- **ALTERNATIVE**: For tenant propagation to async, Spring Cloud Sleuth historically did this via `TraceableExecutorService`, and Micrometer Observation has a similar pattern. Could be done declaratively. **Rejected**: those drag a bigger dependency surface; the four-line decorator is the smallest correct fix.

### Reconciled position

Ship as proposed. Keep the prior-tenant-restore branch with the assistant's documentation note explaining it's defensive for non-pool submitter contexts. Add a backlog ticket "spin a `pdfExecutor` when allergen-label generation goes async". Critically, this finding **must land before Finding 2's idempotency test**, because that test exercises the email fan-out path which now uses the bounded pool — running it on the unbounded `SimpleAsyncTaskExecutor` would mask any executor wiring bug.

---

## Finding 4: Double-registered TenantFilter

### Specialist proposal

**Files touched**: `core-java/src/main/java/uk/jtoye/core/security/TenantFilter.java:13` (remove `@Component`), `core-java/src/main/java/uk/jtoye/core/security/TenantFilter.java:33` (remove redundant `clear()` — `TenantContextCleanupFilter` at HIGHEST_PRECEDENCE owns cleanup).

The chain today (per audit + verified in code):
1. `TenantContextCleanupFilter` (`@Order(HIGHEST_PRECEDENCE)`) — outermost, finally-clears.
2. `TenantFilter` registered as `@Component` (auto-registered as global servlet filter, no explicit order → effectively after Boot's defaults).
3. Spring Security chain → `TenantFilter` *again* via `addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)` (`SecurityConfig.java:96`).
4. `BearerTokenAuthenticationFilter` → `JwtTenantFilter` (`@Order(200)`, added via `addFilterAfter`).

The `@Component` registration is the wrong one to keep. Two reasons:
- The audit (Backend HIGH #4) flags that the `@Component` registration runs *outside* the security chain, before security has authenticated the request. A future maintainer adding logic to `TenantFilter` that depends on `SecurityContextHolder.getContext().getAuthentication()` would silently get `null`.
- The `addFilterBefore(tenantFilter, ...)` registration in `SecurityConfig.java:96` is the intentional one — it has a comment ("Ensure dev header-based tenant mapping runs early") and a positional anchor (`UsernamePasswordAuthenticationFilter.class`).

Diff:

```diff
--- a/core-java/src/main/java/uk/jtoye/core/security/TenantFilter.java
+++ b/core-java/src/main/java/uk/jtoye/core/security/TenantFilter.java
@@ -4,15 +4,21 @@ import jakarta.servlet.FilterChain;
 import jakarta.servlet.ServletException;
 import jakarta.servlet.http.HttpServletRequest;
 import jakarta.servlet.http.HttpServletResponse;
-import org.springframework.stereotype.Component;
 import org.springframework.web.filter.OncePerRequestFilter;

 import java.io.IOException;
 import java.util.UUID;

-@Component
+/**
+ * Header-based tenant mapping for the dev/test profile (X-Tenant-Id).
+ *
+ * <p>NOT annotated with {@code @Component}. Wired into the Spring Security
+ * chain explicitly by {@link SecurityConfig#securityFilterChain} via
+ * {@code http.addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)}.
+ * Auto-registration as a global servlet filter would result in a duplicate
+ * invocation per request and undefined ordering relative to the security
+ * chain.
+ */
 public class TenantFilter extends OncePerRequestFilter {
     public static final String TENANT_HEADER = "X-Tenant-Id";

@@ -19,11 +25,11 @@ public class TenantFilter extends OncePerRequestFilter {
     protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
             throws ServletException, IOException {
         String header = request.getHeader(TENANT_HEADER);
-        try {
-            // Only set from header if no tenant has been established (e.g., by JWT)
-            if (TenantContext.get().isEmpty() && header != null && !header.isBlank()) {
-                try {
-                    TenantContext.set(UUID.fromString(header.trim()));
+        // TenantContextCleanupFilter (HIGHEST_PRECEDENCE) owns the finally-clear
+        // for the request scope. Removing the local clear avoids three filters
+        // racing to clear the same ThreadLocal.
+        if (TenantContext.get().isEmpty() && header != null && !header.isBlank()) {
+            try {
+                TenantContext.set(UUID.fromString(header.trim()));
             } catch (IllegalArgumentException e) {
                 response.sendError(400, "Invalid X-Tenant-Id header (must be UUID)");
                 return;
             }
         }
         filterChain.doFilter(request, response);
-        } finally {
-            TenantContext.clear();
-        }
     }
 }
```

`SecurityConfig.java` requires no change — it already injects `TenantFilter` by type via constructor parameter `(JwtTenantFilter, TenantFilter, Environment)` at line 56, which works for any bean Spring can construct, whether `@Component`-annotated or registered another way. To keep the type-injection working, also need to declare `TenantFilter` as a `@Bean` in `SecurityConfig` since dropping `@Component` removes the only registration:

```java
@Bean
public TenantFilter tenantFilter() {
    return new TenantFilter();
}
```

Add that bean method inside `SecurityConfig` and the wiring stays unchanged.

**Eng-hours**: 0.5h (15 min code, 15 min verifying the existing `TenantFilterTest` and `MultiTenantIsolationIntegrationTest` still pass).

**Rollout**: Land in PR. Boot startup logs should not double-print "Registering filter 'tenantFilter'". Run `MultiTenantIsolationIntegrationTest` and `JwtTenantFilterTest` — must stay green.

**Rollback**: trivial revert; restore `@Component` and the local clear.

### Assistant deliberation

- **VALIDATE**: Picking the `addFilterBefore` registration over `@Component` is the right call. The comment in `SecurityConfig.java:95` ("Ensure dev header-based tenant mapping runs early (before auth)") encodes intent the `@Component` registration cannot.

- **CHALLENGE**: Removing the local `try/finally TenantContext.clear()` from `TenantFilter` makes the filter quietly reliant on `TenantContextCleanupFilter` being installed. If someone in future writes a `@TestConfiguration` that excludes `TenantContextCleanupFilter` (it's a `@Component` so component-scan exclusion would do it), the test passes a stale tenant from one test method into the next. Recommend: keep the local `finally TenantContext.clear()` as defence-in-depth — clearing an already-clear ThreadLocal is a no-op (`ThreadLocal.remove()` on absent value), and the comment in the diff explains why both clears coexist. **Specialist accepts.**

- **RISK**: There's a subtle ordering question: by removing `@Component`, the dev `X-Tenant-Id` header path now runs *only inside* the security chain. The chain runs only for `/anyRequest().authenticated()` paths. `permitAll` paths (`/health`, `/public/**`, `/ws/**` per `SecurityConfig.java:69-72`) still hit Spring Security's filter chain — so this is fine — but worth verifying with a test request to `/health` that previously set `X-Tenant-Id` won't now silently drop it. Easy: existing `JwtTenantFilterTest` covers a comparable path.

- **ALTERNATIVE**: Eliminate `TenantFilter` entirely in `prod` profile; gate on `@Profile({"dev","test"})`. The header-based path is dev-only ergonomics — production must not trust an `X-Tenant-Id` header (the JWT is the trust root). **Backlog**: add the profile gate as a follow-up; don't expand the scope of this fix.

### Reconciled position

Ship the diff with the assistant's amendment: **keep** the local `try/finally TenantContext.clear()` for defence-in-depth, with a comment that explains both clears coexist intentionally. Add a backlog ticket "Gate `TenantFilter` to `@Profile({\"dev\",\"test\"})` so production cannot trust `X-Tenant-Id` header at all".

---

## Finding 5: Spring StateMachine replacement

### Specialist proposal

**Files touched**: rewrite `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineService.java`, delete `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java`, remove the `org.springframework.statemachine:spring-statemachine-starter` dependency from `core-java/build.gradle.kts`. `OrderService` (which calls `stateMachineService.sendEvent`) is unchanged.

Full new file:

```java
package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.jtoye.core.exception.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Order state-transition service.
 *
 * <p>Replaces Spring StateMachine (which previously cost five reactor blocks
 * per transition on a Tomcat thread). State is stored in {@link Order#getStatus()};
 * this class is a pure transition-table lookup with no per-call allocation
 * beyond the {@code String.format} for a violation message.
 *
 * <p>The {@code TRANSITIONS} table is the single source of truth for valid
 * order workflow. To add a new transition, add an entry. End states
 * ({@code COMPLETED}, {@code CANCELLED}) intentionally have no outgoing
 * transitions and the table will refuse any event from them.
 */
@Service
public class OrderStateMachineService {
    private static final Logger log = LoggerFactory.getLogger(OrderStateMachineService.class);

    private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS;

    static {
        EnumMap<OrderStatus, Map<OrderEvent, OrderStatus>> table = new EnumMap<>(OrderStatus.class);

        table.put(OrderStatus.DRAFT, eventMap(
                OrderEvent.SUBMIT, OrderStatus.PENDING,
                OrderEvent.CANCEL, OrderStatus.CANCELLED));

        table.put(OrderStatus.PENDING, eventMap(
                OrderEvent.CONFIRM, OrderStatus.CONFIRMED,
                OrderEvent.CANCEL, OrderStatus.CANCELLED));

        table.put(OrderStatus.CONFIRMED, eventMap(
                OrderEvent.START_PREP, OrderStatus.PREPARING,
                OrderEvent.CANCEL, OrderStatus.CANCELLED));

        table.put(OrderStatus.PREPARING, eventMap(
                OrderEvent.MARK_READY, OrderStatus.READY,
                OrderEvent.CANCEL, OrderStatus.CANCELLED));

        table.put(OrderStatus.READY, eventMap(
                OrderEvent.COMPLETE, OrderStatus.COMPLETED,
                OrderEvent.CANCEL, OrderStatus.CANCELLED));

        // End states — intentionally empty; any event throws.
        table.put(OrderStatus.COMPLETED, new EnumMap<>(OrderEvent.class));
        table.put(OrderStatus.CANCELLED, new EnumMap<>(OrderEvent.class));

        TRANSITIONS = table;
    }

    private static EnumMap<OrderEvent, OrderStatus> eventMap(OrderEvent e1, OrderStatus s1,
                                                              OrderEvent e2, OrderStatus s2) {
        EnumMap<OrderEvent, OrderStatus> m = new EnumMap<>(OrderEvent.class);
        m.put(e1, s1);
        m.put(e2, s2);
        return m;
    }

    /**
     * Execute a state transition.
     *
     * @throws InvalidStateTransitionException identical contract to the prior
     *                                         Spring-StateMachine implementation —
     *                                         GlobalExceptionHandler maps to 400.
     */
    public OrderStatus sendEvent(UUID orderId, OrderStatus currentStatus, OrderEvent event) {
        log.debug("Processing event {} for order {} in state {}", event, orderId, currentStatus);

        Map<OrderEvent, OrderStatus> validEvents = TRANSITIONS.get(currentStatus);
        OrderStatus newStatus = validEvents == null ? null : validEvents.get(event);
        if (newStatus == null) {
            String msg = String.format(
                    "Invalid state transition for order %s: cannot apply event %s in state %s",
                    orderId, event, currentStatus);
            log.warn(msg);
            throw new InvalidStateTransitionException(msg);
        }

        log.info("Order {} transitioned: {} -> {} (event: {})",
                orderId, currentStatus, newStatus, event);
        return newStatus;
    }

    /** Pure validity probe — no side-effects, no logging. */
    public boolean isTransitionValid(OrderStatus currentStatus, OrderEvent event) {
        Map<OrderEvent, OrderStatus> validEvents = TRANSITIONS.get(currentStatus);
        return validEvents != null && validEvents.containsKey(event);
    }
}
```

The existing `OrderStateMachineServiceTest` should port verbatim — it asserts `sendEvent` returns the expected status for each happy-path transition and throws `InvalidStateTransitionException` for invalid ones. The contract is identical.

`build.gradle.kts` change:

```diff
-    implementation("org.springframework.statemachine:spring-statemachine-starter:4.0.0")
```

**Eng-hours**: 2h (0.5h rewrite, 0.5h delete config + dependency, 1h test port + smoke).

**Rollout**:
1. Land in PR with the existing `OrderStateMachineServiceTest` ported (no expected red).
2. Run `OrderControllerIntegrationTest` and `ConcurrentStockDecrementIntegrationTest` to confirm transitions still work end-to-end.
3. Smoke: KDS-style click-through one order DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED.

**Rollback**: revert; the StateMachine starter dep can be re-added trivially.

### Assistant deliberation

- **VALIDATE**: The transition-table approach is the right fit for stateless workflow. Spring StateMachine earns its keep when the state is in the framework (persisted, suspendable, multi-actor). Here the state is in `Order.status` and `@Version` makes concurrency safe — there is literally nothing the framework can do that a `Map` lookup can't.

- **CHALLENGE**: The static initializer block builds the table at class-load. If a future engineer wants to vary transitions per-tenant (e.g., a Mexican-food vendor allows DRAFT → CONFIRMED skipping PENDING), the static map can't accommodate that. Recommend documenting the assumption: "this transition table is global; if per-tenant workflow customisation is added, refactor to a `Map<TenantId, Map<...>>` keyed lookup". **Specialist accepts** as a Javadoc addition.

- **RISK**: The audit's claim was "5 `Mono.block()` calls per transition" but it's actually `stopReactively().block()` ×2, `resetStateMachineReactively(...).block()`, `startReactively().block()`, `sendEvent(...).blockLast()` — that's 5 blocking reactor calls. Removing them removes a real risk: under load on Tomcat with reactor in the same JVM, blocking reactor on the Tomcat thread can starve reactor's internal scheduler if that scheduler happens to be the same. The cleanup is more than aesthetics.

- **ALTERNATIVE**: A more defensive pattern is `record StateTransition(OrderStatus from, OrderEvent event, OrderStatus to)` collected into a `Set<StateTransition>`, with a method that does `set.stream().filter(...).findFirst()`. Slightly more idiomatic for "table of values". **Rejected on perf**: O(n) scan per transition vs O(1) EnumMap lookup. EnumMap is the right choice for an enum-keyed lookup with stable membership.

### Reconciled position

Ship the EnumMap implementation as proposed, with the assistant's Javadoc note explaining the global-table assumption. The `OrderStateMachineConfig.java` file deletes; the Spring StateMachine dependency comes off `build.gradle.kts`. Existing `OrderStateMachineServiceTest` ports verbatim. This is **net code reduction** of ~150 LOC including the config class, plus a real perf win.

---

## Finding 6: `@CacheEvict(allEntries=true)` regressions

### Specialist proposal

**Files touched**: extend `core-java/src/main/java/uk/jtoye/core/config/TenantCacheEvictor.java` with `evictAllForMethod`, modify `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:55,110` and `core-java/src/main/java/uk/jtoye/core/sync/SyncService.java:41-44`.

`TenantCacheEvictor` already implements `evictEntity` (single-key, current tenant). Add a method that walks all keys in a cache and evicts only those whose key prefix matches the current tenant's id format `tenant:{tenantId}:`. This requires the cache to expose its keys — `CaffeineCache` and Spring's `RedisCache` do via `getNativeCache()`.

Add to `TenantCacheEvictor.java`:

```java
/**
 * Evict every cache entry under the current tenant for a given (cacheName, methodName)
 * pair. Used by bulk operations (CSV import, batch sync) where a single eviction
 * isn't enough but a global flush would cross tenants.
 *
 * <p>Implementation notes: scans the underlying native cache for keys starting
 * with {@code tenant:{tenantId}:{methodName}:}. Caffeine and Redis both expose
 * key iteration; if you swap to a cache that does not, this method gracefully
 * degrades to a per-known-id eviction by callers passing an id list to
 * {@link #evictEntity}.
 */
@SuppressWarnings("unchecked")
public void evictAllForMethod(String cacheName, String methodName) {
    if (cacheManager == null) return;
    UUID tenantId = TenantContext.get().orElse(null);
    if (tenantId == null) {
        log.warn("evictAllForMethod skipped — TenantContext not set (cache={}, method={})",
                cacheName, methodName);
        return;
    }
    Cache cache = cacheManager.getCache(cacheName);
    if (cache == null) return;

    String prefix = String.format("tenant:%s:%s:", tenantId, methodName);
    Object native_ = cache.getNativeCache();

    if (native_ instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
        // Caffeine — iterate the asMap() keys and evict matching prefixes.
        for (Object key : caffeine.asMap().keySet()) {
            if (key instanceof String s && s.startsWith(prefix)) {
                cache.evict(key);
            }
        }
    } else if (native_ instanceof org.springframework.data.redis.core.RedisTemplate<?, ?> redis) {
        // Redis — SCAN for the prefix. Cache name is the keyspace prefix in Spring's RedisCache;
        // RedisCache.evict(key) expands to the actual Redis key, so we just delegate.
        org.springframework.data.redis.core.RedisTemplate<String, Object> typed =
                (org.springframework.data.redis.core.RedisTemplate<String, Object>) redis;
        java.util.Set<String> keys = typed.keys(cacheName + "::" + prefix + "*");
        if (keys != null) {
            for (String fullKey : keys) {
                String stripped = fullKey.substring((cacheName + "::").length());
                cache.evict(stripped);
            }
        }
    } else {
        // Unknown cache impl — fall back to whole-cache clear (last resort).
        // This re-introduces the cross-tenant invalidation but is correctness-safe
        // (worst case: other tenants' caches are warm-hit-miss-once).
        log.warn("evictAllForMethod: unknown cache impl {}; falling back to cache.clear() — "
                + "warmth lost across tenants but no cross-tenant data exposure",
                native_.getClass());
        cache.clear();
    }
    log.debug("Evicted all entries for tenant {} in cache {} for method {}", tenantId, cacheName, methodName);
}
```

Call-site replacements:

`SyncService.java:41-44` — remove the `@Caching` annotation; inject `TenantCacheEvictor` and call:

```diff
-@Caching(evict = {
-        @CacheEvict(value = "shops", allEntries = true),
-        @CacheEvict(value = "products", allEntries = true)
-})
 public BatchSyncResponse processBatch(BatchSyncRequest request) {
     UUID tenantId = TenantContext.get()
             .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
     log.info("Processing batch sync for tenant {}: {} items", ...);

     // ... existing iteration ...

+    cacheEvictor.evictAllForMethod("shops", "getShopById");
+    cacheEvictor.evictAllForMethod("shops", "getAllShops");
+    cacheEvictor.evictAllForMethod("products", "getProductById");
+    cacheEvictor.evictAllForMethod("products", "getAllProducts");

     return BatchSyncResponse.builder().status("SUCCESS").processedCount(count).build();
 }
```

`BulkImportService.java:55,110` — same pattern: drop `@CacheEvict(value = "products", allEntries = true)`, call `cacheEvictor.evictAllForMethod("products", "...")` at the end of the method.

**Eng-hours**: 2h (1h `evictAllForMethod` impl, 0.5h call-site swaps, 0.5h test).

**Rollout**: Land in PR. Verify existing `TenantCacheEvictorTest` still passes; add new test `TenantCacheEvictor.evictAllForMethod_onlyEvictsCurrentTenantsKeys()`.

**Rollback**: revert. The `@CacheEvict(allEntries=true)` returns — same pre-fix behaviour, which the codebase shipped with for months without anyone noticing because integration tests rarely cross tenants on bulk imports.

### Assistant deliberation

- **CHALLENGE**: The Caffeine `asMap().keySet()` iteration is fine for small caches but Caffeine doesn't guarantee snapshot semantics — concurrent insertions during iteration may or may not be visible. For the bulk-import use case (single-shot evict at end of method) that's acceptable. For higher-frequency invalidation it could miss a key. Document it, don't fight it.

- **VALIDATE**: The "unknown cache impl → fall back to `cache.clear()`" branch is the right defensive choice. The whole point of the exercise is to *prevent cross-tenant data exposure*, not to optimize cache warmth. If the cache impl changes and `evictAllForMethod` doesn't recognise it, falling back to the broader-blast invalidation is the safe fail-mode.

- **RISK**: Hardcoding method names `"getShopById"`, `"getAllShops"` etc. in `SyncService` couples the eviction caller to the cache-key generator's naming convention. If `ShopService.getShopById` is renamed to `ShopService.fetchShopById`, the cache stays warm and stale. Mitigation: add a comment in `TenantAwareCacheKeyGenerator` reminding maintainers that method-name changes require updating eviction callers, **and** add an integration test asserting that after `processBatch`, a subsequent `getShopById` call returns the fresh value (not a cached stale one).

- **ALTERNATIVE**: A more elegant pattern is `cacheManager.getCacheNames().forEach(name -> cache.invalidateMatching(...))` — but Spring's `Cache` interface has no "invalidate matching predicate" method. So the pattern is forced into `evictAllForMethod`. Could be wrapped in a small DSL `cacheEvictor.forCurrentTenant().cache("shops").method("getShopById").evictAll()` — pleasant but over-engineering for two callers. **Rejected** until a third caller appears.

### Reconciled position

Ship as proposed. Add the assistant's integration test ensuring stale-cache regression cannot land. Add the comment in `TenantAwareCacheKeyGenerator` about method-name coupling. The DSL is YAGNI; revisit on third caller.

---

## Finding 7: No API versioning

### Specialist proposal

**Recommended position**: **defer** the URI versioning migration to "first scheduled breaking change minus one sprint", with a guardrail.

The audit (Backend recommendation #8) costs versioning at ~4h (Java URI prefix + frontend axios baseURL + edge-go route updates). That estimate is right for the *URI prefix* mechanic, but understates the surface area: every test file that hardcodes paths (`mockMvc.perform(get("/orders/..."))`) — there are ~40 of those — needs to update. Realistic full-cost is more like 10-12h once you include the frontend NextAuth callback URLs, edge-go routing, OpenAPI doc regeneration, and re-running the Playwright suite.

Today there are zero customers, so the "v1 vs v2 cohort split" problem doesn't exist. The first time the team genuinely needs to ship a breaking change, they'll have a forcing function for the work.

The guardrail (**non-negotiable**): add the following to CLAUDE.md under "Constraints" or a new "Backwards-compatibility policy" section:

> **API breaking changes**: Before merging any PR that changes an existing endpoint's request/response shape, removes a field, narrows an enum, or alters status codes, the PR must (a) introduce `/v1/...` prefix to all `@RequestMapping` annotations across the codebase in the same PR, (b) update the frontend `api-client.ts` baseURL, (c) update `edge-go` routes, (d) add a deprecation header `Deprecation: true` and `Sunset: <date>` to the legacy endpoints. The breaking change ships in `/v2/...`.

This costs nothing today and forces the work to land before the customer-facing break.

**Eng-hours today**: 0.5h (writing the policy section).
**Eng-hours when triggered**: 10-12h (full migration including test updates).

**Rollout (today)**: PR adds a `docs/policy/api-versioning.md` file and a one-paragraph addition to `CLAUDE.md`. No code change.

**Rollback**: revert the doc PR.

### Assistant deliberation

- **CHALLENGE**: "Defer until first breaking change" is a real bet. The bet relies on the team noticing they're about to ship a breaking change. Some breaking changes are obvious (renaming a field). Others are sneaky — narrowing a nullable to non-null, tightening a regex on a request validator, returning HTTP 422 where you previously returned 400. Recommend adding a CI check that diffs the OpenAPI spec between PR HEAD and main, and fails if removed/renamed/required-changed fields are detected without `/v1/` prefix in the changed routes. **Specialist counters**: that CI check is a separate ~4h spike (OpenAPI diff tooling, gating). Acceptable as a follow-up backlog item, not a prerequisite for the policy.

- **VALIDATE**: The audit's commercial wing ("synthesis author") explicitly listed API versioning as "correct items but for a customer-bearing system" — i.e., correctly de-prioritised at zero customers. Deferring is consistent with the synthesis verdict.

- **RISK**: The biggest risk of deferring is psychological: the team gets used to making breaking changes freely. By the time they have customers, the muscle memory is "just change the endpoint". Mitigation: the CLAUDE.md policy + the OpenAPI-diff CI check together form the muscle.

- **ALTERNATIVE**: Header-based versioning (`Accept: application/vnd.jtoye.v1+json`) instead of URI prefix. **Rejected**: more code, more confusion, no Spring-native convenience, harder to test in browser/curl. URI prefix is the right idiom for a JSON-over-HTTP SaaS.

### Reconciled position

Defer the URI migration. Land the CLAUDE.md policy now. Add a backlog ticket "OpenAPI breaking-change CI guard" estimated 4h. When the first genuine breaking change lands, do the full 10-12h migration in the same PR per the policy.

---

## Finding 8: Optimistic-lock 409 mapping

### Specialist proposal

**Files touched**: `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` (add one handler).

```java
@ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
public ProblemDetail handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
    log.info("Optimistic lock collision on {}: {}", ex.getPersistentClassName(), ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Resource was modified by another request — please refetch and retry");
    problem.setTitle("Concurrent Modification");
    problem.setType(URI.create("https://jtoye.uk/errors/concurrent-modification"));
    problem.setProperty("retryable", true);
    problem.setProperty("retryAfterMs", 100);
    return problem;
}
```

**Eng-hours**: 0.25h (15 minutes including a unit test of the handler).

**Rollout**: PR. Verify two operators clicking "Confirm" on the same order in the same millisecond now both get 409 (the loser; the winner gets 200 with the new state).

**Rollback**: revert.

### Assistant deliberation

- **VALIDATE**: 409 Conflict per RFC 9110 §15.5.10 is the correct status for "the request conflicts with the current state of the resource". This is exactly the optimistic-lock semantic.

- **CHALLENGE**: Returning `retryAfterMs: 100` as a structured property is a hint — but the frontend doesn't read it today. Either wire the frontend to honour it (1h) or don't include it. If the property is included but ignored, future engineers may assume the server enforces a back-off it doesn't. Recommend: include it but add a TODO comment + backlog ticket "frontend: honour `retryAfterMs` on 409 responses".

- **RISK**: `ObjectOptimisticLockingFailureException` is thrown at commit time, not at `repository.save()` — Hibernate's flush happens at transaction boundary. If the Spring AOP advice that wraps `OrderService.transitionOrder` re-throws after the commit, the handler catches it. But if a `@Transactional(propagation=REQUIRES_NEW)` inner layer eats and rethrows wrapped, the handler sees the wrapper. Sanity-check: the existing `StockService.decrementForOrder` uses `@Retryable` on `ObjectOptimisticLockingFailureException` (per the audit's strength #1) — so the framework is already classifying these correctly. This handler will catch the exhaust-after-retry case for stock and the unwrapped case for `Order.@Version`.

- **ALTERNATIVE**: Wrap into a custom `ConcurrentModificationException` extending `RuntimeException` and handle that. **Rejected**: adds a layer for no benefit; Spring's exception is already specific enough.

### Reconciled position

Ship the handler as proposed. Add the TODO + backlog ticket per the assistant's challenge. Total scope unchanged.

---

## Finding 9: OrderEventPublisher swallowed AMQP exceptions

### Specialist proposal

**Decision**: **document the asymmetry**. Do not move order events to an outbox.

The payment outbox (`PaymentEventOutbox` + `PaymentEventOutboxFlusher`) exists because **payment events have audit and reconciliation requirements** — a missed `payment.succeeded` event means the customer paid and the analytics/finance pipelines miss it forever. The blast radius of "RabbitMQ down + payment succeeded" is regulatory.

Order state-change events (DRAFT → PENDING → CONFIRMED → ...) are **operational signals to the kitchen UI**. If a `MARK_READY` event drops because RabbitMQ is down, the worst case is the customer's "order ready" SSE stream goes silent for that one order. The kitchen still sees the order on screen because the screen polls. The operational cost is small; the engineering cost of a second outbox + flusher + RLS migration is medium.

Accept the asymmetry. Document it. Keep `OrderEventPublisher.java:38-41`'s try/catch but upgrade the log level to `WARN` and add a Micrometer counter so an outage shows up on Grafana:

```diff
-        } catch (Exception e) {
-            log.error("Failed to publish order state change event for order {}: {}",
-                    orderNumber, e.getMessage());
-        }
+        } catch (Exception e) {
+            // INTENTIONALLY swallowed — order state-change events are operational
+            // signals (kitchen SSE/WebSocket), not regulatory data. RabbitMQ outage
+            // degrades real-time kitchen UI for the duration of the outage; the order
+            // status itself is committed in the transactional save above. If the
+            // operational degradation becomes painful, mirror the payment outbox
+            // pattern (V31 + PaymentEventOutboxFlusher). See remediation/01 §F9.
+            orderPublishFailureCounter.increment();
+            log.warn("Failed to publish order state change event for order {} (intentional swallow — see Javadoc): {}",
+                    orderNumber, e.getMessage());
+        }
```

`orderPublishFailureCounter` is wired identically to `PaymentEventOutboxFlusher.deadLetterCounter` — `Counter.builder("order.event.publish.failures")...register(meterRegistry)`. Constructor change adds `ObjectProvider<MeterRegistry>` parameter.

**Eng-hours**: 0.5h (15 min impl + comment + counter, 15 min Grafana panel + alert rule).

**Rollout**: PR. Add Grafana alert "OrderEventPublishFailures > 10/min for 5 minutes" → page on-call.

**Rollback**: revert.

### Assistant deliberation

- **VALIDATE**: The asymmetry argument is correct. Outbox-everywhere is over-engineering. The payment outbox earns its complexity because of regulatory + reconciliation needs that order events do not have.

- **CHALLENGE**: "Operational only" assumes the kitchen UI has fallback (polling). Verify: does `app/dashboard/orders` actually poll, or does it rely solely on SSE/WS? If pure-push, the kitchen is blind during the RabbitMQ outage. Worth a 5-minute check in the frontend audit. **Specialist accepts** — the reconciled position adds a verification step.

- **RISK**: Once the asymmetry is documented and accepted, future engineers may extend the same "log and swallow" pattern to events that *do* have regulatory needs (e.g., a future `inventory.adjustment` event for stock-loss audit). The Javadoc comment must explicitly delineate "operational" vs "audit/regulatory" so the boundary is legible.

- **ALTERNATIVE**: Cheap middle ground — use the existing `payment_event_outbox` table with a different `event_type` discriminator. **Rejected** for the same reason as F2: cross-domain conflation. Each domain owns its own outbox if it needs one.

### Reconciled position

Ship the documented-asymmetry change as proposed. Add a verification task: confirm the dashboard orders view either polls every 30s or queries on tab-focus, so an event-publish outage degrades gracefully. If it does NOT, escalate to F9b: "Add 30s poll fallback to dashboard orders view". Add the Grafana alert per spec.

---

## Finding 10: Authenticated `POST /orders` lacks idempotency

### Specialist proposal

**Files touched**: `core-java/src/main/java/uk/jtoye/core/order/dto/CreateOrderRequest.java` (add `idempotencyKey`), `core-java/src/main/java/uk/jtoye/core/order/OrderService.java:78-114` (mirror the guest path's lookup-then-insert).

The guest path (`PublicStorefrontService.java:325-348`) already does the right thing: if `request.getIdempotencyKey()` is non-blank, look up `orderRepository.findByTenantIdAndIdempotencyKey(tenantId, key)`; if found, return that order; otherwise persist with the key. The unique partial index from V24 catches concurrent inserts.

Mirror it on the authenticated path:

```diff
--- a/core-java/src/main/java/uk/jtoye/core/order/dto/CreateOrderRequest.java
+++ b/core-java/src/main/java/uk/jtoye/core/order/dto/CreateOrderRequest.java
@@ ... @@
 public class CreateOrderRequest {
+    @Size(max = 64, message = "idempotencyKey must be 64 chars or fewer")
+    private String idempotencyKey;
     // ... existing fields ...
+    public String getIdempotencyKey() { return idempotencyKey; }
+    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
 }
```

`OrderService.createOrder` insert at line 90 (right after the shop validation):

```java
// Idempotency check — if request carries a key, return the existing order
// for that (tenant, key) pair instead of creating a duplicate.
String idempotencyKey = request.getIdempotencyKey();
if (idempotencyKey != null && !idempotencyKey.isBlank()) {
    Optional<Order> existing = orderRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    if (existing.isPresent()) {
        log.info("Idempotent duplicate detected for key '{}' on authenticated POST /orders, returning existing order {}",
                idempotencyKey, existing.get().getOrderNumber());
        return orderMapper.toDto(existing.get());
    }
}
```

And after the order is built, before the existing `orderRepository.save`:

```java
if (idempotencyKey != null && !idempotencyKey.isBlank()) {
    order.setIdempotencyKey(idempotencyKey);
}
```

The DB-level race is already covered by V24's unique partial index — concurrent saves with the same key throw `DataIntegrityViolationException`, which `GlobalExceptionHandler.handleDataIntegrityViolation` (line 79) maps to 409 Conflict with a sensible message.

**Eng-hours**: 1h (0.5h impl + 0.5h test mirroring `GuestOrderIdempotencyIntegrationTest` pattern — except that test doesn't yet exist either; add both in the same PR).

**Rollout**: PR. No migration needed (V24 already created the column + index). Frontend can start sending an `Idempotency-Key` header on POST, but doesn't have to — the field is optional.

**Rollback**: revert. Pre-fix behaviour returns.

### Assistant deliberation

- **CHALLENGE**: Why is the idempotency key a request body field rather than an `Idempotency-Key` HTTP header (Stripe convention)? Header-based is slightly cleaner — it's a transport-level concern, not a domain-level concern, and tools like client SDKs can set it transparently. **Specialist counters**: the guest path already uses a body field. Adopting a header on the authenticated path creates inconsistency. Pick one and stay consistent. Body it is. (If the team wants to switch both to a header later, that's a separate PR; document it in the API-versioning policy from F7.)

- **VALIDATE**: Mirroring the guest path's pattern is correct — this is a textbook "DRY across paths" fix. The shared logic could be extracted into a small `OrderIdempotencyHelper`, but given there are only two callers, in-line duplication is fine.

- **RISK**: The 64-char limit is enforced by `@Size` but not by the DB column (`VARCHAR(64)` from V24). If someone bypasses validation (e.g., a non-DTO direct `Order.setIdempotencyKey`), the DB would truncate or reject. Acceptable given V24 has the constraint. Worth noting in code review: `@Size(64)` matches `VARCHAR(64)`.

- **ALTERNATIVE**: Auto-generate an idempotency key from request hash (SHA-256 of normalized request body). Eliminates client cooperation. **Rejected** for the standard reason: hash-of-request-body collides on identical legitimate requests (e.g., user genuinely wants to place two identical orders in a 60-second window). Client-supplied key is the correct semantic.

### Reconciled position

Ship as proposed (body field, mirroring guest path). Add the `GuestOrderIdempotencyIntegrationTest` from QA's missing-tests list (which the audit Top-5 mentioned at synthesis line 138) as part of the same PR — the same test scaffolding works for both paths and the QA gap closes for free.

---

## Dependency graph

```
F1 (SSE leak)            ── independent ── ships first (lowest blast radius, highest impact)
F4 (TenantFilter)        ── independent ── ships any time
F5 (StateMachine)        ── independent ── ships any time

F3 (AsyncConfig)         ── BLOCKS ── F2 test    (idempotency test exercises the @Async email path)
F2 (Stripe idempotency)  ── BLOCKS ── F10 test   (auth POST idempotency test reuses the F2 fixture)
                         ── independent of code paths but its migration V35 sequences before F10's test boot

F6 (CacheEvict)          ── independent ── ships any time
F8 (409 handler)         ── independent ── ships any time
F9 (Order events)        ── soft-blocks on F8   (409 handler must exist before order-event publish failures
                                                  could otherwise mask via the generic 500 handler)
F7 (API versioning policy) ── independent ── doc PR, ships any time
F10 (auth POST idempotency) ── soft-blocks on F2 ── shares the idempotency mental model + migration order
```

Critical path: **F3 → F2 → F10**. Everything else can land in parallel.

---

## Total effort + suggested wave breakdown

| Wave | Findings | Hours | Rationale |
|---|---|---|---|
| **Wave 1 — pre-prod blockers** | F1, F2 | **4.5h** (1.5 + 3) | The two CRITICALs the audit collectively says block production. Both fully cover the SSE leak and the Stripe replay risk. |
| **Wave 2 — high-leverage hardening** | F3, F4, F5, F6 | **6h** (2 + 0.5 + 2 + 2) (-F4 0.5h slack) | All four are land-in-one-week items. F3 unblocks F2's *test*; F4-F6 are independent. |
| **Wave 3 — correctness + idempotency** | F8, F9, F10, F7 | **3.25h** (0.25 + 0.5 + 1 + 0.5 docs + 1h policy CI follow-up) | Less urgent but cheap; F7 is doc-only. |
| **Total** | 10 findings | **~14h core impl + ~8h tests/migrations/grafana = ~22h** | About three focused engineering days for one engineer. |

**Suggested ship order** (single engineer): F1 morning of day 1 → F3 afternoon of day 1 → F2 morning of day 2 → F10 afternoon of day 2 → F4, F8 morning of day 3 → F5, F6 afternoon of day 3 → F9, F7 day 3 evening as 30-minute closers.

---

## Open questions for human decision

1. **F7 (API versioning) defer-vs-do-now**: the specialist + assistant both recommend defer. The synthesis author also recommends defer ("correct items but for a customer-bearing system"). Founder-call: does any external party (a verbal LOI, a design partner conversation) already exist that would consume the API and create a backwards-compat obligation? If yes, do it now; if no, defer per the policy.

2. **F9 (order-event outbox) verify dashboard polls**: the reconciled position depends on the dashboard orders view having a 30s polling fallback or tab-focus refetch. Assigning to frontend audit follow-up — if the dashboard is pure-push, F9b ("add poll fallback") becomes a Wave 3 item adding ~1h.

3. **F1 (SSE) vs delete-and-replace-with-STOMP**: the audit recommended deleting SSE entirely (Edge audit verdict on edge-go is similar — "delete and absorb"). The specialist recommends the surgical fix now and a delete in a follow-up. If the founder wants to bias toward simplicity, the SSE service deletion is ~3h on the dashboard side and removes ~50 LOC + one parallel pipeline. Defer call.

4. **F4 profile gate**: the assistant flagged that `TenantFilter` should ideally be `@Profile({"dev","test"})` so production cannot trust an `X-Tenant-Id` header at all. This is in the Wave-3 backlog. If the founder is concerned about the production attack surface today (e.g., "what if a prod request includes `X-Tenant-Id`?"), promote it to Wave 2.

5. **Wave-1 deploy gate**: should F1 + F2 ship as a single PR or two? Specialist: two PRs (atomic blast-radius isolation). Assistant: one PR (forces both fixes to deploy together — neither is useful alone for the "ship to production" threshold). **Recommend the assistant's view**: one PR titled `pre-prod-blockers/sse-tenant-isolation+stripe-idempotency` so the deployment is atomic and the audit's two-bug remediation lands as one commit on main.
