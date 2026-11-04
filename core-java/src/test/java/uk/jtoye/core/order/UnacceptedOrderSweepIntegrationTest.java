package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.notification.CustomerEmailContext;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #821 (owner ruling 2026-11-04, persona close-out): an order left PENDING past the configured threshold
 * raises a vendor alert (the {@code unaccepted_notified_at} stamp the kitchen board and dashboard read) and
 * sends the customer ONE email. No auto-cancel. Once per order. Voided and unpaid orders are not touched.
 *
 * <p>Two tenants under a NOSUPERUSER role: {@code orders} is FORCE RLS, so an unpinned sweep would read and
 * stamp nothing and report success. Each arm has a control that would have been red under that failure.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@uk.jtoye.core.testsupport.AsSystemHarness
class UnacceptedOrderSweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("jtoye.orders.unaccepted-alert.enabled", () -> "true");
        registry.add("jtoye.orders.unaccepted-alert.threshold", () -> "10m");
        // The scheduler must not race the test: sweep() is driven by hand.
        registry.add("jtoye.orders.unaccepted-alert.sweep-interval-ms", () -> "3600000");
    }

    @MockitoBean EmailNotificationService emailService;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UnacceptedOrderSweep sweep;
    @Autowired OrderService orderService;
    @Autowired PlatformTransactionManager txManager;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000821a");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000821b");
    private static final String APP_ROLE = "test";
    private static final java.util.concurrent.atomic.AtomicBoolean DOWNGRADED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            jdbcTemplate.update(
                    "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                    tenant, "821 Tenant " + tenant.toString().substring(35));
        }
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername()).isEqualTo(APP_ROLE);
            jdbcTemplate.execute("ALTER ROLE \"" + APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, APP_ROLE))
                .as("PRECONDITION: FORCE RLS applies to the role under test").isFalse();
        shopA = seedShopIdempotent(TENANT_A, "shop-821-a");
        shopB = seedShopIdempotent(TENANT_B, "shop-821-b");
        // Earlier arms leave stamped / old PENDING rows behind in the same container; every arm starts clean.
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            inTenant(tenant, () -> jdbcTemplate.update(
                    "UPDATE orders SET status = 'COMPLETED' WHERE tenant_id = ? AND status = 'PENDING'", tenant));
        }
        reset(emailService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("#821: an overdue PENDING order is stamped in tenants A and B under NOSUPERUSER; young, confirmed, voided and unpaid orders are not")
    void sweepStampsOnlyTheOverdueAcceptableOrders_inEveryTenant() {
        UUID overdueA = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 12, false);
        UUID youngA = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 5, false);
        UUID confirmedA = seedOrder(TENANT_A, shopA, "CONFIRMED", "NONE", null, 30, false);
        UUID voidedA = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 30, true);
        UUID unpaidA = seedOrder(TENANT_A, shopA, "PENDING", "PENDING", "pi_x", 30, false);
        UUID overdueB = seedOrder(TENANT_B, shopB, "PENDING", "NONE", null, 20, false);
        assertThat(stampOf(TENANT_A, overdueA)).as("PRECONDITION: seeded unstamped").isNull();

        int alerted = sweep.sweep();

        assertThat(alerted).as("one overdue order per tenant").isEqualTo(2);
        assertThat(stampOf(TENANT_A, overdueA)).as("tenant A overdue").isNotNull();
        assertThat(stampOf(TENANT_B, overdueB)).as("tenant B overdue: the sweep reached the second tenant").isNotNull();
        assertThat(stampOf(TENANT_A, youngA)).as("5 minutes is inside the threshold").isNull();
        assertThat(stampOf(TENANT_A, confirmedA)).as("a CONFIRMED order is not waiting for acceptance").isNull();
        assertThat(stampOf(TENANT_A, voidedA)).as("a voided order is not touched").isNull();
        assertThat(stampOf(TENANT_A, unpaidA)).as("an order awaiting payment cannot be accepted yet").isNull();
        assertThat(statusOf(TENANT_A, overdueA)).as("NO auto-cancel: the order stays PENDING").isEqualTo("PENDING");
        assertThat(statusOf(TENANT_B, overdueB)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("#821: each order is notified once - the second sweep alerts nothing and emails nobody")
    void sweepIsIdempotent_andEmailsTheCustomerExactlyOnce() {
        UUID overdue = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 15, false);

        assertThat(sweep.sweep()).isEqualTo(1);
        OffsetDateTime first = stampOf(TENANT_A, overdue);
        assertThat(first).isNotNull();
        assertThat(sweep.sweep()).as("the stamp is the ledger: a rerun finds nothing to do").isZero();
        assertThat(stampOf(TENANT_A, overdue)).as("the stamp is written once").isEqualTo(first);

        ArgumentCaptor<OrderStateChangeEvent> event = ArgumentCaptor.forClass(OrderStateChangeEvent.class);
        verify(emailService, times(1)).sendOrderAwaitingAcceptance(event.capture(), eq(emailOf(TENANT_A, overdue)),
                any(CustomerEmailContext.class));
        assertThat(event.getValue().orderId()).isEqualTo(overdue);
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("#821: no email address means a vendor alert and no email")
    void anOrderWithoutAnEmailIsStillAlertedButNotEmailed() {
        UUID overdue = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 15, false, false);

        assertThat(sweep.sweep()).isEqualTo(1);

        assertThat(stampOf(TENANT_A, overdue)).as("the vendor alert is raised regardless").isNotNull();
        verify(emailService, never()).sendOrderAwaitingAcceptance(any(), any(), any());
    }

    @Test
    @DisplayName("#821: the stamp reaches the vendor DTOs the kitchen board and dashboard read")
    void theAlertStampIsOnTheOrderDetailDto() {
        UUID overdue = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 15, false);
        UUID young = seedOrder(TENANT_A, shopA, "PENDING", "NONE", null, 2, false);
        sweep.sweep();

        TenantContext.set(TENANT_A);
        try {
            OrderDetailDto alerted = SystemPrincipal.asSystem(() -> orderService.getOrderDetailById(overdue).orElseThrow());
            OrderDetailDto notAlerted = SystemPrincipal.asSystem(() -> orderService.getOrderDetailById(young).orElseThrow());
            assertThat(alerted.getUnacceptedNotifiedAt()).isNotNull();
            assertThat(notAlerted.getUnacceptedNotifiedAt()).isNull();
        } finally {
            TenantContext.clear();
        }
    }

    // ---- SQL helpers ----

    private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
        TenantContext.set(tenant);
        try {
            return new TransactionTemplate(txManager).execute(status -> {
                jdbcTemplate.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());
                return work.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    private OffsetDateTime stampOf(UUID tenant, UUID orderId) {
        return inTenant(tenant, () -> {
            List<OffsetDateTime> rows = jdbcTemplate.query(
                    "SELECT unaccepted_notified_at FROM orders WHERE tenant_id = ? AND id = ?",
                    (rs, i) -> rs.getObject(1, OffsetDateTime.class), tenant, orderId);
            assertThat(rows).as("the pinned session sees the row").hasSize(1);
            return rows.get(0);
        });
    }

    private String statusOf(UUID tenant, UUID orderId) {
        return inTenant(tenant, () -> jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE tenant_id = ? AND id = ?", String.class, tenant, orderId));
    }

    private String emailOf(UUID tenant, UUID orderId) {
        return inTenant(tenant, () -> jdbcTemplate.queryForObject(
                "SELECT customer_email FROM orders WHERE tenant_id = ? AND id = ?", String.class, tenant, orderId));
    }

    private UUID seedOrder(UUID tenant, UUID shop, String status, String paymentStatus, String paymentReference,
                           int minutesAgo, boolean voided) {
        return seedOrder(tenant, shop, status, paymentStatus, paymentReference, minutesAgo, voided, true);
    }

    /**
     * An order whose status last changed {@code minutesAgo} minutes ago (updated_at is the "pending since").
     * Everything is written in the INSERT: update_orders_updated_at (V5) resets updated_at on any UPDATE, which
     * would silently turn an "old" fixture into a young one and make a negative arm pass for the wrong reason.
     */
    private UUID seedOrder(UUID tenant, UUID shop, String status, String paymentStatus, String paymentReference,
                           int minutesAgo, boolean voided, boolean withEmail) {
        return inTenant(tenant, () -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "customer_phone, subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                            + "fulfilment_type, total_amount_pennies, item_count, payment_status, payment_reference, "
                            + "payment_method, created_at, updated_at, version, voided_at, voided_by, void_reason) "
                            + "VALUES (?, ?, ?, ?, ?, 'Sweep Buyer', ?, '07700 900000', 1000, 'ZERO', 0, 0, "
                            + "'COLLECTION', 1000, 1, ?, ?, 'Unpaid', now() - make_interval(mins => ?), "
                            + "now() - make_interval(mins => ?), 0, "
                            + (voided ? "now(), 'tester', 'test')" : "NULL, NULL, NULL)"),
                    id, tenant, shop, "ORD-821-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                    status, withEmail ? "sweep-" + id + "@example.com" : null, paymentStatus, paymentReference,
                    minutesAgo, minutesAgo);
            return id;
        });
    }

    private UUID seedShopIdempotent(UUID tenant, String slug) {
        return inTenant(tenant, () -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, tenant, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, tenant, "821 Shop " + slug, slug);
            return id;
        });
    }
}
