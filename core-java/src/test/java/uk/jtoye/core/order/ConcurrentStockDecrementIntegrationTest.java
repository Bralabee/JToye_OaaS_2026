package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.InsufficientStockException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQ-01 race-pinning integration test — the canonical regression guard for
 * the "two concurrent CONFIRMs on the last-in-stock product both succeed"
 * oversell bug.
 *
 * <p>Seeds a product with {@code quantity_in_stock = 1}, then fires two
 * {@code orderService.confirmOrder} calls concurrently against it, gated
 * by a {@link CountDownLatch} so both threads reach the transition in
 * the same JPA transaction window.
 *
 * <p>Expected outcome after the fix (Task 14-01-04):
 * <ul>
 *   <li>exactly one {@code confirmOrder} returns without exception;</li>
 *   <li>exactly one throws {@link InsufficientStockException};</li>
 *   <li>final {@code products.quantity_in_stock} is EXACTLY 0 — not -1, not
 *       0-via-{@code Math.max}-clamp.</li>
 * </ul>
 *
 * <p>Runs against Testcontainers Postgres (Phase 12 Deviation #4 pattern —
 * the {@code application-test.yml} profile defaults to H2 which would pass
 * vacuously, no real locking). RabbitMQ stubbed per Phase 12 Deviation #3
 * (port=0 + listener auto-startup disabled) so the Spring context boots
 * without a live broker — {@code OrderEventPublisher} silently swallows
 * publish failures at the end of the transition.
 *
 * <p>Phase 13 cross-class {@code TenantContext} ThreadLocal leakage lesson
 * is honoured: both {@code @BeforeEach} and {@code @AfterEach} clear, and
 * each worker thread sets + clears its own context in a try/finally.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// #283: drives orderService.createOrder/confirmOrder (gated) to reach the concurrent
// stock-decrement race under test.
@uk.jtoye.core.testsupport.AsSystemHarness
class ConcurrentStockDecrementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Phase 12 Deviation #4 — override H2 defaults in application-test.yml so
        // the Testcontainers Postgres image is used with real Flyway migrations
        // (V34 adds products.version — required for optimistic-lock semantics).
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        // Override application-test.yml's ddl-auto=create-drop — otherwise Hibernate
        // recreates the schema from entity metadata (skipping enums/types and
        // known Envers schema drift in order_items_aud) and INSERTs fail with
        // "type order_status does not exist" or Schema-validation errors.
        // Flyway is the source of truth — Hibernate should do nothing.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("rate-limiting.enabled", () -> "false");
        // RabbitMQ stubs (Phase 12 Deviation #3).
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbcTemplate;

    // Use a dedicated tenant UUID for Phase 14 so parallel suite runs with Phase 13's
    // TENANT_A/TENANT_B fixtures can't collide on the shops.slug UNIQUE constraint.
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private UUID shopId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Phase 14 Tenant");
        shopId = seedShopIdempotent(TENANT_ID, "shop-cq01-race");
        productId = seedProductWithStock(TENANT_ID, shopId, /*stock=*/ 1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void concurrentConfirm_oneWins_oneThrowsInsufficientStock() throws Exception {
        UUID orderA = seedPendingOrder(TENANT_ID, shopId, productId, /*qty=*/ 1, "ORD-A");
        UUID orderB = seedPendingOrder(TENANT_ID, shopId, productId, /*qty=*/ 1, "ORD-B");

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> workerA = () -> runConfirm(orderA, gate);
            Callable<Throwable> workerB = () -> runConfirm(orderB, gate);

            Future<Throwable> fA = pool.submit(workerA);
            Future<Throwable> fB = pool.submit(workerB);
            gate.countDown();

            // Collect results into a nullable-safe ArrayList — List.of() forbids nulls,
            // which in the RED phase is the expected state (both threads succeed = both null).
            List<Throwable> results = new ArrayList<>(2);
            results.add(fA.get(30, SECONDS));
            results.add(fB.get(30, SECONDS));

            long successes = results.stream().filter(Objects::isNull).count();
            long failures  = results.stream().filter(Objects::nonNull).count();

            assertThat(successes).as("exactly one CONFIRM succeeds").isEqualTo(1);
            assertThat(failures).as("exactly one CONFIRM throws").isEqualTo(1);
            assertThat(results.stream().filter(Objects::nonNull).findFirst().orElseThrow())
                    .isInstanceOf(InsufficientStockException.class);

            // Post-conditions: final stock is exactly 0, no clamp masking an oversell.
            TenantContext.set(TENANT_ID);
            Integer finalStock;
            try {
                finalStock = jdbcTemplate.queryForObject(
                        "SELECT quantity_in_stock FROM products WHERE id = ?",
                        Integer.class, productId);
            } finally {
                TenantContext.clear();
            }
            assertThat(finalStock).as("final quantity_in_stock").isEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * #283: the system declaration is made HERE, on the worker thread, for the same reason
     * {@link TenantContext} is — {@code SystemPrincipal} is a plain {@link ThreadLocal} and is
     * deliberately NOT inherited by a spawned thread, so a declaration made by the class-level
     * {@code @AsSystemHarness} on the test thread does not (and must not) reach these workers.
     * Without it both workers are denied at the gate and the race under test never runs, which
     * is exactly how this surfaced: "exactly one CONFIRM succeeds" saw ZERO succeed.
     */
    private Throwable runConfirm(UUID orderId, CountDownLatch gate) {
        TenantContext.set(TENANT_ID);
        try {
            gate.await();
            SystemPrincipal.asSystem(() -> orderService.confirmOrder(orderId));
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            TenantContext.clear();
        }
    }

    // ---- Idempotent seed helpers (Phase 13 pattern — survive repeated @BeforeEach) ----

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private UUID seedShopIdempotent(UUID tenantId, String slug) {
        TenantContext.set(tenantId);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?",
                    UUID.class, tenantId, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, tenantId, "CQ01 Shop", slug);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedProductWithStock(UUID tenantId, UUID shopId, int stock) {
        TenantContext.set(tenantId);
        try {
            // Re-seed on every @BeforeEach — short-circuit if SKU already exists.
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM products WHERE tenant_id = ? AND sku = ?",
                    UUID.class, tenantId, "SKU-CQ01-RACE");
            UUID productId;
            if (existing.isEmpty()) {
                productId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                                + "allergen_mask, price_pennies, display_order, available, featured, "
                                + "shop_id, quantity_in_stock, version) "
                                + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, ?, 0)",
                        productId, tenantId, "SKU-CQ01-RACE", "Croissant",
                        "butter, flour", shopId, stock);
            } else {
                productId = existing.get(0);
                // Reset stock + bump version so each test sees a fresh race.
                jdbcTemplate.update(
                        "UPDATE products SET quantity_in_stock = ?, version = version + 1 WHERE id = ?",
                        stock, productId);
            }
            return productId;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedPendingOrder(UUID tenantId, UUID shopId, UUID productId, int qty, String orderNumber) {
        TenantContext.set(tenantId);
        try {
            UUID orderId = UUID.randomUUID();
            // V6 dropped the order_status enum and made orders.status VARCHAR(20)
            // with a CHECK constraint — insert a plain string, no CAST needed.
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, "
                            + "subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                            + "total_amount_pennies, item_count, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0, now(), now())",
                    orderId, tenantId, shopId, orderNumber + "-" + System.nanoTime(), "PENDING",
                    (long) (qty * 1000), "ZERO", (long) (qty * 1000), qty);

            UUID itemId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO order_items (id, tenant_id, order_id, product_id, quantity, "
                            + "unit_price_pennies, total_price_pennies, product_name, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 1000, ?, ?, now())",
                    itemId, tenantId, orderId, productId, qty,
                    (long) (qty * 1000), "Croissant");

            return orderId;
        } finally {
            TenantContext.clear();
        }
    }
}
