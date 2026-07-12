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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * QA-council C1 regression lock (run disc-20260712-010550, FIX-1).
 *
 * <p>Live defect: {@code PaymentEventOutboxFlusher.flushPending()} iterated
 * ALL tenants inside ONE whole-method transaction. The RLS tenant GUC
 * ({@code app.current_tenant_id}) is transaction-scoped, so when the loop
 * advanced from tenant A to tenant B, Hibernate's auto-flush before B's
 * native claim query flushed A's dirty {@code status=SENT} UPDATEs under
 * <em>B's</em> GUC. The V33 FORCE-RLS policy hid A's rows from that UPDATE
 * (0 rows matched) → {@code StaleStateException} → the whole transaction
 * rolled back, INCLUDING the failure-path writeback (attempts++/backoff), so
 * rows stayed PENDING with attempts=0 and were re-published every 5s tick,
 * forever — the customer-facing duplicate-email storm.
 *
 * <p>Why the pre-existing {@code PaymentEventOutboxReliabilityIntegrationTest}
 * could not see this (ADJ-2): it runs as the Testcontainers bootstrap role — a
 * Postgres SUPERUSER, which bypasses even FORCE RLS — and it seeds a single
 * tenant. This class removes BOTH masks: it seeds PENDING rows for TWO
 * tenants and downgrades the role to NOSUPERUSER (the repo's canonical
 * pattern, see {@link IntegrationTestSupport}) before driving the flusher.
 *
 * <p>Red (pre-fix): both tests fail — flushPending throws, rows stay PENDING
 * with attempts=0. Green (post-fix): each tenant is drained in its OWN
 * transaction, so every flush/commit happens under the GUC of the tenant that
 * owns the dirty rows, regardless of tenant iteration order.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PaymentEventOutboxFlusherCrossTenantIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Park the schedules a day out so each test drives flushPending() by hand.
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
        registry.add("payment.outbox.backoff-base-ms", () -> "5000");
        registry.add("payment.outbox.backoff-cap-ms", () -> "300000");
    }

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000c1aa");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000c1bb");

    /** The role can only be downgraded once — a NOSUPERUSER role cannot re-grant itself. */
    private static boolean downgraded = false;

    @Autowired private PaymentEventOutboxFlusher flusher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private RabbitTemplate rabbitTemplate;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void seed() {
        txTemplate = new TransactionTemplate(transactionManager);
        // tenants has no RLS (V2) — insertable regardless of role.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A CrossTenant");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "Tenant B CrossTenant");
        if (!downgraded) {
            // ADJ-2: a SUPERUSER bypasses FORCE RLS entirely, masking the bug.
            // Downgrade BEFORE any outbox row exists so every statement in this
            // class runs under genuinely-enforced RLS — the live precondition.
            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }
        // Under FORCE RLS each tenant's rows are only visible/deletable with
        // that tenant's GUC applied — clean both partitions explicitly.
        inTenantTx(TENANT_A, () -> jdbcTemplate.update("DELETE FROM payment_event_outbox"));
        inTenantTx(TENANT_B, () -> jdbcTemplate.update("DELETE FROM payment_event_outbox"));
        reset(rabbitTemplate);
    }

    /** Run {@code work} inside a real transaction with the tenant GUC applied. */
    private void inTenantTx(UUID tenantId, Runnable work) {
        TenantContext.set(tenantId);
        try {
            txTemplate.executeWithoutResult(status -> work.run());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedOrderStateRow(UUID tenantId, String orderNumber) {
        try {
            UUID id = UUID.randomUUID();
            OrderStateChangeEvent event = new OrderStateChangeEvent(
                    UUID.randomUUID(), tenantId, orderNumber,
                    OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now());
            String payload = objectMapper.writeValueAsString(event);
            inTenantTx(tenantId, () -> jdbcTemplate.update("""
                    INSERT INTO payment_event_outbox
                        (id, tenant_id, event_type, routing_key, exchange, payload,
                         status, attempts, next_attempt_at, created_at)
                    VALUES (?, ?, 'ORDER_STATE_CHANGED', 'order.state.pending', 'order.events', ?,
                            'PENDING', 0, now(), now())
                    """, id, tenantId, payload));
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("test seed failed", e);
        }
    }

    private Map<String, Object> rowById(UUID tenantId, UUID id) {
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(status -> jdbcTemplate.queryForMap(
                    "SELECT status, attempts, next_attempt_at, sent_at FROM payment_event_outbox WHERE id = ?",
                    id));
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // C1 repro: happy path across two tenants under enforced RLS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1: two tenants with PENDING rows under enforced RLS — one tick drains both, exactly once")
    void crossTenantFlush_underEnforcedRls_drainsAllTenantsExactlyOnce() {
        UUID rowA = seedOrderStateRow(TENANT_A, "ORD-C1-A");
        UUID rowB = seedOrderStateRow(TENANT_B, "ORD-C1-B");

        // Pre-fix: Hibernate auto-flushes tenant A's dirty SENT update at
        // tenant B's claim query, under B's GUC → RLS hides A's rows →
        // StaleStateException (Batch update returned unexpected row count [0]).
        assertThatCode(() -> flusher.flushPending())
                .as("cross-tenant flush must not explode under enforced RLS")
                .doesNotThrowAnyException();

        assertThat(rowById(TENANT_A, rowA).get("status"))
                .as("tenant A row drained").isEqualTo("SENT");
        assertThat(rowById(TENANT_B, rowB).get("status"))
                .as("tenant B row drained").isEqualTo("SENT");
        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(Object.class));

        // Second tick: nothing left to publish — the storm is the absence of this.
        flusher.flushPending();
        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // ------------------------------------------------------------------
    // C1 corollary: failure-path writeback must survive (attempts=0 forever
    // on the live stack was the reason backoff never engaged)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1 failure path: one tenant's publish failure persists attempts=1 + backoff AND cannot starve the other tenant")
    void publishFailureWriteback_persistsPerTenant_andDoesNotStarveOtherTenants() {
        UUID rowA = seedOrderStateRow(TENANT_A, "ORD-FAIL-A");
        UUID rowB = seedOrderStateRow(TENANT_B, "ORD-OK-B");

        // Publish fails ONLY for tenant A's event; tenant B's succeeds.
        doAnswer(inv -> {
            Object event = inv.getArgument(2);
            if (event instanceof OrderStateChangeEvent osc && "ORD-FAIL-A".equals(osc.orderNumber())) {
                throw new AmqpException("broker rejected A");
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> flusher.flushPending())
                .as("a single tenant's publish failure must not abort the whole pass")
                .doesNotThrowAnyException();

        // Pre-fix: the whole-method tx rolled back → attempts stayed 0 and the
        // backoff design never engaged (every stuck live row showed attempts=0).
        Map<String, Object> failed = rowById(TENANT_A, rowA);
        assertThat(failed.get("status")).isEqualTo("PENDING");
        assertThat(failed.get("attempts"))
                .as("failure-path writeback (attempts++) must COMMIT")
                .isEqualTo(1);
        assertThat(queryNextAttempt(TENANT_A, rowA))
                .as("exponential backoff must push next_attempt_at out")
                .isAfter(OffsetDateTime.now().plusSeconds(3));

        // Tenant B must drain despite A's failure — no cross-tenant starvation.
        assertThat(rowById(TENANT_B, rowB).get("status")).isEqualTo("SENT");
    }

    private OffsetDateTime queryNextAttempt(UUID tenantId, UUID id) {
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(status -> jdbcTemplate.queryForObject(
                    "SELECT next_attempt_at FROM payment_event_outbox WHERE id = ?",
                    OffsetDateTime.class, id));
        } finally {
            TenantContext.clear();
        }
    }
}
