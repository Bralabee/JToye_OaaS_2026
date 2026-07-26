package uk.jtoye.core.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.config.BusinessMetricsService;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * QA-council H1 + N1 regression lock (run disc-20260712-010550, FIX-2).
 *
 * <p><b>H1:</b> the transactional outbox is at-least-once BY DESIGN, but
 * {@code OrderStateChangeListener} had no dedup — every redelivery re-sent the
 * customer email, re-incremented business metrics, and re-broadcast the KDS
 * STOMP topic. This is the amplifier that turned the C1 flusher storm into
 * hundreds of duplicate customer emails. The fix dedups on the semantic key
 * {@code (tenant_id, order_id, new_status)} in {@code processed_order_events}
 * (V47, FORCE RLS), mirroring the {@code processed_stripe_events} precedent.
 *
 * <p><b>N1:</b> the KDS STOMP branch called {@code orderRepository.findById}
 * BEFORE the tenant GUC was set; under enforced RLS the order was invisible,
 * {@code ifPresent} no-oped, and the {@code /topic/kitchen.…} broadcast never
 * fired (invisible on dev where KDS uses SSE). The fix sets tenant context
 * FIRST. This test runs as NOSUPERUSER (ADJ-2 lesson: a SUPERUSER test role
 * bypasses FORCE RLS and cannot see N1 at all).
 *
 * <p>Red (pre-fix): double delivery produces two emails/metric increments and
 * ZERO STOMP broadcasts. Green (post-fix): exactly one of each side effect,
 * one dedup row, and the broadcast fires.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderStateChangeListenerIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Park the outbox schedules — this class drives the listener directly.
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
    }

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000f1a2");

    /** NOSUPERUSER downgrade is one-way for the class (cannot self-re-grant). */
    private static boolean downgraded = false;

    @Autowired private OrderStateChangeListener listener;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private EmailNotificationService emailService;
    @MockBean private BusinessMetricsService metrics;
    @MockBean private SimpMessagingTemplate simpMessagingTemplate;

    private TransactionTemplate txTemplate;
    private UUID shopId;
    private UUID orderId;

    @BeforeEach
    void seed() {
        txTemplate = new TransactionTemplate(transactionManager);
        shopId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        // tenants has no RLS (V2) — insertable regardless of role.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT, "Tenant ListenerIdempotency");
        if (!downgraded) {
            // ADJ-2: a SUPERUSER test role bypasses FORCE RLS and cannot see
            // N1. Downgrade is one-way (the role cannot re-grant itself), so
            // ALL seeding below runs tenant-scoped — valid under either role.
            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }
        // Seed inside a tenant-scoped transaction: TenantSetLocalAspect applies
        // the RLS GUC to JdbcTemplate ops, satisfying the WITH CHECK policies.
        inTenantTx(() -> {
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) "
                            + "VALUES (?, ?, ?, ?, true, 0, now())",
                    shopId, TENANT, "Idempotency Shop " + shopId.toString().substring(0, 8),
                    "idem-shop-" + shopId.toString().substring(0, 8));
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                            + "item_count, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', 'Idem Customer', 'idem-customer@example.test', "
                            + "1000, 'STANDARD', 200, 1200, 0, 1, now(), now())",
                    orderId, TENANT, shopId, "ORD-IDEM-" + orderId.toString().substring(0, 8));
        });
        reset(emailService, metrics, simpMessagingTemplate);
    }

    /** Run {@code work} inside a real transaction with the tenant GUC applied. */
    private void inTenantTx(Runnable work) {
        TenantContext.set(TENANT);
        try {
            txTemplate.executeWithoutResult(status -> work.run());
        } finally {
            TenantContext.clear();
        }
    }

    private int processedRowCount() {
        TenantContext.set(TENANT);
        try {
            Integer n = txTemplate.execute(status -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM processed_order_events WHERE order_id = ?", Integer.class, orderId));
            return n == null ? -1 : n;
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // H1: redelivered event must NOT repeat side effects
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H1: the same ORDER_STATE_CHANGED delivered twice fires email/metrics/STOMP exactly once")
    void sameEventDeliveredTwice_sideEffectsFireExactlyOnce() {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, TENANT, "ORD-IDEM-DUP",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now());

        // First delivery + broker redelivery of the identical event.
        listener.handleOrderStateChange(event);
        listener.handleOrderStateChange(event);

        verify(emailService, times(1)).sendOrderConfirmation(eq(event), eq("idem-customer@example.test"));
        verify(metrics, times(1)).recordOrderCreated();
        String topic = "/topic/kitchen." + TENANT + "." + shopId;
        verify(simpMessagingTemplate, times(1)).convertAndSend(eq(topic), eq(event));
        assertThat(processedRowCount())
                .as("exactly one dedup row per (tenant, order, status)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("H1: a DIFFERENT status for the same order is a legitimate new event, not a duplicate")
    void differentStatusSameOrder_isNotTreatedAsDuplicate() {
        OrderStateChangeEvent pending = new OrderStateChangeEvent(
                orderId, TENANT, "ORD-IDEM-SEQ",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now());
        OrderStateChangeEvent confirmed = new OrderStateChangeEvent(
                orderId, TENANT, "ORD-IDEM-SEQ",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());

        listener.handleOrderStateChange(pending);
        listener.handleOrderStateChange(confirmed);

        verify(emailService, times(1)).sendOrderConfirmation(eq(pending), anyString());
        verify(emailService, times(1)).sendOrderConfirmed(eq(confirmed), anyString());
        assertThat(processedRowCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // N1: KDS STOMP broadcast must fire under enforced RLS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("N1: KDS STOMP broadcast fires under enforced RLS (tenant GUC set before the order lookup)")
    void kdsStompBroadcast_firesUnderEnforcedRls() {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, TENANT, "ORD-IDEM-STOMP",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());

        listener.handleOrderStateChange(event);

        // Pre-fix: findById ran BEFORE the tenant GUC was applied → RLS hid
        // the order → no broadcast, ever, on any RLS-enforced stack.
        String topic = "/topic/kitchen." + TENANT + "." + shopId;
        verify(simpMessagingTemplate, times(1)).convertAndSend(eq(topic), eq(event));
    }
}
