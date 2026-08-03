package uk.jtoye.core.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #93 acceptance tests against real Postgres (Testcontainers + Flyway,
 * including the V46 outbox-reliability migration):
 *
 * <ol>
 *   <li>Two flusher "replicas" running concurrently partition the PENDING set
 *       via FOR UPDATE SKIP LOCKED — no row is published twice.</li>
 *   <li>A broker outage increments attempts under an exponential-backoff
 *       schedule (next_attempt_at gates re-claims), the row never flips FAILED
 *       inside the retry budget, and it drains as soon as the broker recovers.</li>
 *   <li>Retry-exhausted FAILED rows are resurrected to PENDING and drain;
 *       poison rows (corrupt payload) stay FAILED forever.</li>
 *   <li>Order state-change events ride the outbox inside the caller's
 *       transaction: a rollback leaves no outbox row and publishes nothing;
 *       a commit publishes exactly once with the pre-#93 wire format.</li>
 * </ol>
 *
 * <p>Every {@code @Scheduled} trigger in this context is removed
 * ({@link NoScheduledTriggersTestConfig}) so the tests below are the only
 * caller of the flusher and own the whole timeline — see issue #418 and the
 * note on {@link #configureProperties} for why parking the interval was not
 * enough on its own.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class PaymentEventOutboxReliabilityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // #418: these two lines USED to be the whole story, and the comment that
        // sat here claimed the schedule "fires once at startup against an empty
        // table, then never again during the test run". The second half is true;
        // the first half is the bug. @Scheduled(fixedDelayString=...) leaves
        // initialDelay at 0, so the startup run fires at context refresh — i.e.
        // concurrently with this class's first @BeforeEach, NOT before it — and
        // that run raced the test for the very rows it was asserting on.
        //
        // They are kept as defence in depth (they bound the blast radius to a
        // single pass if the @Import above is ever dropped), but the @Import is
        // what actually makes this deterministic, and the guard test
        // schedulingIsInert_soTheTestOwnsTheFlusherTimeline is what keeps it so.
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
        registry.add("payment.outbox.backoff-base-ms", () -> "5000");
        registry.add("payment.outbox.backoff-cap-ms", () -> "300000");
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000e93");

    @Autowired private PaymentEventOutboxFlusher flusher;
    @Autowired private OrderEventPublisher orderEventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext applicationContext;

    @MockBean private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM payment_event_outbox");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "Tenant Outbox Reliability");
        reset(rabbitTemplate);
    }

    private UUID seedPaymentRow(String paymentIntentId) {
        try {
            UUID id = UUID.randomUUID();
            PaymentEvent event = new PaymentEvent(
                    UUID.randomUUID(), TENANT_ID, "ORD-" + paymentIntentId, paymentIntentId,
                    1000L, "gbp", PaymentEvent.PaymentEventType.SUCCEEDED,
                    null, OffsetDateTime.now());
            jdbcTemplate.update("""
                    INSERT INTO payment_event_outbox
                        (id, tenant_id, event_type, routing_key, exchange, payload,
                         status, attempts, next_attempt_at, created_at)
                    VALUES (?, ?, 'SUCCEEDED', 'payment.succeeded', 'payment.events', ?,
                            'PENDING', 0, now(), now())
                    """, id, TENANT_ID, objectMapper.writeValueAsString(event));
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("test seed failed", e);
        }
    }

    private Map<String, Object> rowById(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempts, poison, next_attempt_at, sent_at FROM payment_event_outbox WHERE id = ?",
                id);
    }

    // ------------------------------------------------------------------
    // #418 regression lock: nothing but this class may drive the flusher
    // ------------------------------------------------------------------

    /**
     * The premise every other test in this class rests on. Each of them calls
     * {@code flushPending()}/{@code resurrectFailed()} and then asserts an
     * exact publish count and an exact row status — which only means anything
     * if the test is the sole caller. It was not: the parked intervals in
     * {@link #configureProperties} suppress the repeat but not the
     * {@code initialDelay=0} startup run, so a
     * second, invisible flusher pass ran on the {@code scheduling-N} thread
     * over the same rows and the same {@code @MockBean RabbitTemplate}.
     *
     * <p>Measured on the amplified interleaving (2026-08-03, 300 samples),
     * that second writer produced all three of:
     * {@code TooManyActualInvocations} with both invocations at
     * {@code publishRow:305} (matching PR #415), a row still {@code PENDING}
     * at the drain assertion because the scheduler held it under
     * {@code FOR UPDATE SKIP LOCKED} (which pre-#422 surfaced as
     * {@code WantedButNotInvoked}, matching PR #417), and a row already
     * {@code SENT} at the resurrection assertion.
     *
     * <p>This asserts the cause, not a timing window: the bean
     * {@code @EnableScheduling} registers to discover {@code @Scheduled}
     * methods is absent, so no trigger of any kind exists to fire. Deleting
     * the {@code @Import} on this class fails this test.
     */
    @Test
    @DisplayName("no @Scheduled trigger is live in this context — the test owns the flusher timeline")
    void schedulingIsInert_soTheTestOwnsTheFlusherTimeline() {
        assertThat(applicationContext.containsBean(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .as("@EnableScheduling's annotation processor must be removed from this context; "
                        + "while it is present every @Scheduled method in the application — 10 of "
                        + "them — fires once at context refresh, concurrently with this class's "
                        + "first test, no matter how far out its interval is parked")
                .isFalse();
        assertThat(applicationContext.getBeanNamesForType(ScheduledTaskHolder.class))
                .as("with the processor gone there is nothing left holding scheduled tasks")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // AC (1): no duplicate publishes with multiple flusher replicas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two concurrent flushers partition the PENDING set — every row published exactly once")
    void concurrentFlushers_noDuplicatePublish() throws Exception {
        final int rows = 6;
        for (int i = 0; i < rows; i++) {
            seedPaymentRow("pi_conc_" + i);
        }

        // Slow each publish down so both flushers demonstrably overlap: one
        // transaction holds its SKIP LOCKED claims while the other runs.
        doAnswer(inv -> {
            Thread.sleep(300);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> replica = () -> {
            start.await();
            flusher.flushPending();
            return null;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> a = pool.submit(replica);
            Future<Void> b = pool.submit(replica);
            start.countDown();
            a.get(90, TimeUnit.SECONDS);
            b.get(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Pre-#93 (plain SELECT, no locking) both replicas read the same
        // PENDING batch → 2x publishes. SKIP LOCKED makes the sets disjoint.
        verify(rabbitTemplate, times(rows))
                .convertAndSend(anyString(), anyString(), any(Object.class));
        Integer sent = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_event_outbox WHERE status = 'SENT'", Integer.class);
        assertThat(sent).isEqualTo(rows);
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_event_outbox WHERE status = 'PENDING'", Integer.class);
        assertThat(pending).isZero();
    }

    // ------------------------------------------------------------------
    // AC (2): events survive a broker outage and drain on recovery
    // ------------------------------------------------------------------

    @Test
    @DisplayName("broker outage: backoff gates retries, row never dies inside the budget, drains on recovery")
    void brokerOutage_backoffGates_thenDrainsOnRecovery() {
        UUID id = seedPaymentRow("pi_outage");

        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Tick 1 — attempt fails, backoff scheduled.
        flusher.flushPending();
        Map<String, Object> row = rowById(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        OffsetDateTime nextAttempt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM payment_event_outbox WHERE id = ?", OffsetDateTime.class, id);
        assertThat(nextAttempt).isAfter(OffsetDateTime.now().plusSeconds(3));

        // Tick 2 immediately after — the pre-#93 flusher burned one attempt
        // per 5s tick (5 attempts = ~25s outage = permanent FAILED). Now the
        // backoff window gates the claim: no second publish attempt yet.
        flusher.flushPending();
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));
        assertThat(rowById(id).get("attempts")).isEqualTo(1);

        // Simulate the backoff window elapsing (multi-minute outage continues).
        jdbcTemplate.update(
                "UPDATE payment_event_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
        flusher.flushPending();
        row = rowById(id);
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(row.get("status"))
                .as("a transient outage must never flip the row FAILED inside the retry budget")
                .isEqualTo("PENDING");

        // Broker recovers (mock reverts to default no-op success).
        reset(rabbitTemplate);
        jdbcTemplate.update(
                "UPDATE payment_event_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
        flusher.flushPending();

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE), eq("payment.succeeded"), any(Object.class));
        row = rowById(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
    }

    // ------------------------------------------------------------------
    // AC (2b)/quality bar: FAILED rows resurrect and drain; poison stays dead
    // ------------------------------------------------------------------

    @Test
    @DisplayName("retry-exhausted FAILED rows resurrect to PENDING and drain; poison rows stay FAILED")
    void failedRows_resurrectAndDrain_poisonStaysDead() {
        UUID exhausted = seedPaymentRow("pi_exhausted");
        jdbcTemplate.update("""
                UPDATE payment_event_outbox
                SET status = 'FAILED', attempts = ?, poison = FALSE, last_error = 'broker down'
                WHERE id = ?
                """, PaymentEventOutboxFlusher.MAX_ATTEMPTS, exhausted);

        UUID poisoned = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_event_outbox
                    (id, tenant_id, event_type, routing_key, exchange, payload,
                     status, attempts, poison, last_error, next_attempt_at, created_at)
                VALUES (?, ?, 'SUCCEEDED', 'payment.succeeded', 'payment.events', '{corrupt',
                        'FAILED', 1, TRUE, 'payload deserialization failed: boom', now(), now())
                """, poisoned, TENANT_ID);

        flusher.resurrectFailed();

        Map<String, Object> revived = rowById(exhausted);
        assertThat(revived.get("status")).isEqualTo("PENDING");
        assertThat(revived.get("attempts")).as("fresh lease restarts the backoff ladder").isEqualTo(0);
        assertThat(rowById(poisoned).get("status"))
                .as("poison rows must never be resurrected into a deserialize-fail loop")
                .isEqualTo("FAILED");

        // Broker healthy — the resurrected row drains, the poison row is untouched.
        flusher.flushPending();

        // STATE FIRST, MOCK SECOND (#418). This line failed on CI in BOTH directions —
        // TooManyActualInvocations (#415) and WantedButNotInvoked (#417) — on branches
        // that touched neither payment nor outbox code. Because the invocation count was
        // asserted BEFORE the row state, the CI log recorded a bare Mockito error and
        // never said the one thing that would have identified the mechanism: whether the
        // row had actually drained. Ordering the observable outcome first costs nothing
        // and makes the next occurrence self-diagnosing —
        //   row SENT but count wrong  -> the publish happened; the mock is the problem
        //   row still PENDING         -> the flush genuinely did not run
        assertThat(rowById(exhausted).get("status"))
                .as("the resurrected row must actually drain — asserted before the mock so a "
                        + "failure here identifies the mechanism rather than hiding it")
                .isEqualTo("SENT");
        assertThat(rowById(poisoned).get("status")).isEqualTo("FAILED");

        // Scoped to THIS row's exchange + routing key, mirroring the verify that already
        // closes the broker-outage test above. `anyString(), anyString(), any()` counts
        // EVERY publish from ANY source, so one unrelated invocation inflates the count
        // without saying anything about the property under test. Exactly-once is
        // unchanged — this narrows what counts, it does not loosen how many.
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE), eq("payment.succeeded"), any(Object.class));
    }

    // ------------------------------------------------------------------
    // AC (3): no event is emitted for a transaction that rolls back
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rolled-back order transition leaves no outbox row and publishes nothing")
    void rolledBackTransaction_emitsNothing() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            TenantContext.set(TENANT_ID);
            try {
                orderEventPublisher.publishStateChange(
                        UUID.randomUUID(), TENANT_ID, "ORD-ROLLBACK",
                        OrderStatus.PENDING, OrderStatus.CONFIRMED);
            } finally {
                TenantContext.clear();
            }
            // Business logic fails after the publish call → whole tx rolls back.
            status.setRollbackOnly();
        });

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_event_outbox", Integer.class);
        assertThat(rows)
                .as("the outbox row must roll back with the transaction it joined")
                .isZero();

        flusher.flushPending();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("committed order transition publishes exactly once with the pre-#93 wire format")
    void committedTransaction_publishesExactlyOnce() {
        UUID orderId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            TenantContext.set(TENANT_ID);
            try {
                orderEventPublisher.publishStateChange(
                        orderId, TENANT_ID, "ORD-COMMIT",
                        OrderStatus.PENDING, OrderStatus.CONFIRMED);
            } finally {
                TenantContext.clear();
            }
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, exchange, routing_key FROM payment_event_outbox");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("exchange")).isEqualTo(RabbitMQConfig.ORDER_EVENTS_EXCHANGE);
        assertThat(row.get("routing_key")).isEqualTo("order.state.confirmed");

        flusher.flushPending();

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.ORDER_EVENTS_EXCHANGE), eq("order.state.confirmed"), payload.capture());
        // Same wire type as the old direct publish — OrderStateChangeListener's
        // @RabbitListener(OrderStateChangeEvent) contract is preserved.
        assertThat(payload.getValue()).isInstanceOf(OrderStateChangeEvent.class);
        OrderStateChangeEvent event = (OrderStateChangeEvent) payload.getValue();
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.orderNumber()).isEqualTo("ORD-COMMIT");
        assertThat(event.previousStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(event.newStatus()).isEqualTo(OrderStatus.CONFIRMED);

        List<Map<String, Object>> all = jdbcTemplate.queryForList(
                "SELECT status FROM payment_event_outbox");
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("status")).isEqualTo("SENT");
    }
}
