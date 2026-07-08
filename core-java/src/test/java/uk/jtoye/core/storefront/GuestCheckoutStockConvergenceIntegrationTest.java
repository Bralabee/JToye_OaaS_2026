package uk.jtoye.core.storefront;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

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
 * Issue #85 [P1-3] — guest-checkout stock TOCTOU + apparent double-decrement.
 *
 * <p>Characterization / regression guard for the guest-checkout stock path.
 * The audit flagged the double-decrement as <em>apparent</em>, not confirmed:
 * {@code PublicStorefrontService.createGuestOrder} performs an eager naked
 * read-modify-write on {@code products.quantity_in_stock} (no {@code @Version}
 * retry), and {@code OrderService.transitionOrder}'s CONFIRMED branch calls the
 * retry-safe {@code StockService.decrementForOrder} — so a guest order that is
 * created and then vendor-CONFIRMED could decrement stock twice.
 *
 * <p><strong>VERIFY FIRST:</strong> in Task 1 these assertions pin the CURRENT
 * (pre-fix) behavior. Task 2 flips them to the post-fix single-decrement
 * invariants after the eager block is removed. The observed pre-fix delta and
 * concurrency outcome are recorded verbatim in the SUMMARY (CONFIRMED vs
 * REFUTED).
 *
 * <p>Test profile has no Stripe key ({@code STRIPE_API_KEY:} defaults empty in
 * application.yml) so {@code paymentService.isConfigured()==false} — guest
 * checkout takes the COD branch and the order lands straight in PENDING with no
 * PaymentIntent. This makes the test self-contained (no webhook simulation
 * needed): the vendor then drives PENDING -> CONFIRMED via
 * {@code orderService.confirmOrder}.
 *
 * <p>Runs against Testcontainers Postgres (application-test.yml defaults to H2,
 * which would pass vacuously with no real optimistic locking). Property wiring
 * is delegated to {@link IntegrationTestSupport#registerPostgresTestProperties}.
 * TenantContext ThreadLocal discipline (Phase 13 lesson): both {@code @BeforeEach}
 * and {@code @AfterEach} clear, and every worker thread sets/clears its own
 * context in try/finally. Guest calls start with NO upstream tenant, so
 * {@code createGuestOrder}'s {@code resolvePublicShopForSlug} sets it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestCheckoutStockConvergenceIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(GuestCheckoutStockConvergenceIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired PublicStorefrontService publicStorefrontService;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbcTemplate;

    // Dedicated tenant UUID for issue #85 to avoid slug/SKU collisions with the
    // Phase 13 (TENANT_A/B) and Phase 14 (...000000014) fixtures under a parallel
    // suite run.
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000085");
    private static final String SHOP_SLUG = "shop-issue85-guest-stock";

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Issue 85 Tenant");
        shopId = seedShopIdempotent(TENANT_ID, SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Test A — guest checkout -> vendor CONFIRM stock delta
    // ------------------------------------------------------------------
    @Test
    void guestCheckoutThenConfirm_decrementsStockOnce() throws Exception {
        int startStock = 10;
        int qty = 3;
        UUID productId = seedProductWithStock(TENANT_ID, shopId, "SKU-ISSUE85-DELTA", startStock);

        // Guest checkout: COD path (no Stripe key) -> order lands PENDING.
        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, guestRequestFor(productId, qty, "issue85-delta-" + System.nanoTime()));
        assertThat(confirmation.getStatus())
                .as("COD guest order lands PENDING (no Stripe key in test profile)")
                .isEqualTo("PENDING");

        // Vendor confirms: PENDING -> CONFIRMED (drives StockService.decrementForOrder).
        UUID orderId = lookupOrderId(confirmation.getOrderNumber());
        TenantContext.set(TENANT_ID);
        try {
            orderService.confirmOrder(orderId);
        } finally {
            TenantContext.clear();
        }

        int finalStock = readStock(productId);
        int observedDelta = startStock - finalStock;
        log.info("ISSUE-85 PRE-FIX CHARACTERIZATION: guest->confirm stock delta = {} (qty={}, => {} x qty); "
                        + "start={}, final={}",
                observedDelta, qty, observedDelta / qty, startStock, finalStock);

        // PRE-FIX CHARACTERIZATION — flips to single-decrement in Task 2.
        // Confirmed-bug code decrements twice: once eagerly in createGuestOrder
        // (lines 448-455) and once at CONFIRM (transitionOrder). Delta = 2 x qty,
        // so final stock = 10 - 6 = 4.
        assertThat(finalStock)
                .as("PRE-FIX: guest->confirm double-decrement leaves stock at %d (delta = 2 x qty)", startStock - 2 * qty)
                .isEqualTo(startStock - 2 * qty);
    }

    // ------------------------------------------------------------------
    // Test B — two concurrent guest checkouts on the last unit (TOCTOU)
    // ------------------------------------------------------------------
    @Test
    void concurrentGuestCheckout_lastUnit_surfacesContention() throws Exception {
        UUID productId = seedProductWithStock(TENANT_ID, shopId, "SKU-ISSUE85-TOCTOU", 1);

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> worker1 = () -> runGuestCheckout(
                    productId, 1, "issue85-toctou-1-" + System.nanoTime(), gate);
            Callable<Throwable> worker2 = () -> runGuestCheckout(
                    productId, 1, "issue85-toctou-2-" + System.nanoTime(), gate);

            Future<Throwable> f1 = pool.submit(worker1);
            Future<Throwable> f2 = pool.submit(worker2);
            gate.countDown();

            // Nullable-safe list — a successful checkout returns null.
            List<Throwable> results = new ArrayList<>(2);
            results.add(f1.get(30, SECONDS));
            results.add(f2.get(30, SECONDS));

            long failures = results.stream().filter(Objects::nonNull).count();
            results.stream().filter(Objects::nonNull).findFirst().ifPresent(t ->
                    log.info("ISSUE-85 PRE-FIX CHARACTERIZATION: concurrent guest checkout threw {}: {}",
                            t.getClass().getName(), t.getMessage()));
            log.info("ISSUE-85 PRE-FIX CHARACTERIZATION: concurrent-checkout failures = {} of 2", failures);

            // PRE-FIX CHARACTERIZATION — flips to 0 failures in Task 2.
            // The naked read-modify-write in createGuestOrder has no @Version retry,
            // so under contention one thread's save hits
            // ObjectOptimisticLockingFailureException and surfaces as a 500.
            assertThat(failures)
                    .as("PRE-FIX: naked RMW surfaces one 500 under contention")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Throwable runGuestCheckout(UUID productId, int qty, String idempotencyKey, CountDownLatch gate) {
        // Guest calls start with NO upstream tenant; createGuestOrder sets + clears
        // its own TenantContext via resolvePublicShopForSlug.
        TenantContext.clear();
        try {
            gate.await();
            publicStorefrontService.createGuestOrder(
                    SHOP_SLUG, guestRequestFor(productId, qty, idempotencyKey));
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            TenantContext.clear();
        }
    }

    // ---- Request builder ----

    private GuestOrderRequest guestRequestFor(UUID productId, int qty, String idempotencyKey) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Guest Buyer");
        request.setCustomerEmail("guest@example.com");
        request.setCustomerPhone("+447700900085");
        request.setNotes("issue #85 characterization");
        request.setIdempotencyKey(idempotencyKey);
        request.setItems(List.of(item));
        return request;
    }

    // ---- Read helpers (TenantContext set for correctness; Testcontainers
    //      bootstrap role is SUPERUSER so RLS is bypassed regardless) ----

    private UUID lookupOrderId(String orderNumber) {
        TenantContext.set(TENANT_ID);
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM orders WHERE order_number = ?", UUID.class, orderNumber);
        } finally {
            TenantContext.clear();
        }
    }

    private int readStock(UUID productId) {
        TenantContext.set(TENANT_ID);
        try {
            Integer stock = jdbcTemplate.queryForObject(
                    "SELECT quantity_in_stock FROM products WHERE id = ?", Integer.class, productId);
            return Objects.requireNonNull(stock, "quantity_in_stock must not be null in this test");
        } finally {
            TenantContext.clear();
        }
    }

    // ---- Idempotent seed helpers (Phase 13/14 pattern) ----

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private UUID seedShopIdempotent(UUID tenantId, String slug) {
        TenantContext.set(tenantId);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, tenantId, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No opening_hours -> shop is always open (validateShopIsOpen returns early).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, tenantId, "Issue 85 Shop", slug);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedProductWithStock(UUID tenantId, UUID shopId, String sku, int stock) {
        TenantContext.set(tenantId);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM products WHERE tenant_id = ? AND sku = ?", UUID.class, tenantId, sku);
            UUID productId;
            if (existing.isEmpty()) {
                productId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                                + "allergen_mask, price_pennies, display_order, available, featured, "
                                + "shop_id, quantity_in_stock, vat_rate, version) "
                                + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, ?, 'STANDARD', 0)",
                        productId, tenantId, sku, "Croissant", "butter, flour", shopId, stock);
            } else {
                productId = existing.get(0);
                // Reset stock + bump version so each test sees a fresh baseline.
                jdbcTemplate.update(
                        "UPDATE products SET quantity_in_stock = ?, version = version + 1 WHERE id = ?",
                        stock, productId);
            }
            return productId;
        } finally {
            TenantContext.clear();
        }
    }
}
