package uk.jtoye.core.storefront;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COR-9 (QA-council 20260902-134741) — <b>this file fixes nothing.</b> It supplies the
 * REPRESENTATIVE EXECUTION two correct money paths have never had.
 *
 * <p>The finding is not "the code is wrong"; the free-delivery waiver
 * ({@code PublicStorefrontService}) and the VAT ledger write at COMPLETED
 * ({@code OrderService.transitionOrder}) both read correctly. The finding is that on the live
 * runtime the waiver had fired on <b>0 of 60</b> orders and {@code financial_transactions} held
 * <b>1</b> row from <b>1</b> COMPLETED order. A branch that has never executed is untested, not
 * green — and a single ledger row cannot distinguish "the ledger works" from "one row happened to
 * exist".
 *
 * <p>So each test below is written to fail if the denominator is trivial:
 * <ul>
 *   <li>the waiver arm asserts the fee is charged BELOW the threshold and waived AT it, so a
 *       hardcoded £0 would fail the first half and a hardcoded fee the second;</li>
 *   <li>the ledger arm completes MORE THAN ONE order, at DIFFERENT VAT rates, and asserts the
 *       row count grows and each row carries its own order's rate — which a single-row fixture
 *       could never have shown.</li>
 * </ul>
 *
 * <p><b>What COR-9 still cannot close, stated rather than hidden:</b> the CARD half. The test
 * profile has no Stripe key, so every order here takes the COD branch. Proving the captured-card
 * path needs real test-mode keys (issue #461) and is out of reach of any fixture.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MoneyPathExecutionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000909");
    private static final String SHOP_SLUG = "shop-cor9-money-path";

    /** The live brixton configuration, so the arithmetic here is the arithmetic that ships. */
    private static final long DELIVERY_FEE_PENNIES = 399L;
    private static final long FREE_DELIVERY_THRESHOLD_PENNIES = 2000L;

    @Autowired PublicStorefrontService publicStorefrontService;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "COR-9 Money Path Tenant");
        shopId = seedShop();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // A — the free-delivery waiver, executed in BOTH directions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("COR-9: below the threshold the shop's delivery fee IS charged (the non-vacuity half)")
    void belowTheThresholdTheFeeIsCharged() {
        // 1 x 1000p = 1000p, below the 2000p threshold.
        UUID productId = seedProduct("SKU-COR9-BELOW", 1000L, "STANDARD");

        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, deliveryRequest(productId, 1, "cor9-below-" + System.nanoTime()));

        assertThat(confirmation.getDeliveryFeePennies())
                .as("without this half, a hardcoded 0 would satisfy the waiver assertion below")
                .isEqualTo(DELIVERY_FEE_PENNIES);
        assertThat(confirmation.getTotalAmountPennies()).isEqualTo(1000L + DELIVERY_FEE_PENNIES);
    }

    @Test
    @DisplayName("COR-9: at the free-delivery threshold the fee is waived — 0 of 60 live orders had ever reached here")
    void atTheThresholdTheFeeIsWaived() {
        // 2 x 1000p = 2000p, exactly the threshold. The rule is >=, so the boundary itself
        // must waive; testing only well above it would leave the comparison operator unpinned.
        UUID productId = seedProduct("SKU-COR9-WAIVE", 1000L, "STANDARD");

        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, deliveryRequest(productId, 2, "cor9-waive-" + System.nanoTime()));

        assertThat(confirmation.getDeliveryFeePennies())
                .as("COR-9: the free-delivery waiver, executed for the first time")
                .isZero();
        assertThat(confirmation.getTotalAmountPennies())
                .as("the waived fee must not reappear in the total")
                .isEqualTo(2000L);

        // Read the persisted row, not only the DTO: the DTO could be right while the column
        // that reaches the HMRC-facing ledger is not.
        assertThat(readOrderLong(confirmation.getOrderNumber(), "delivery_fee_pennies")).isZero();
    }

    // ------------------------------------------------------------------
    // B — the VAT ledger, with a denominator greater than one
    // ------------------------------------------------------------------

    @Test
    @DisplayName("COR-9: completing TWO orders at DIFFERENT VAT rates writes TWO ledger rows, each at its own rate")
    void theLedgerGrowsAndCarriesEachOrdersOwnRate() {
        long before = ledgerRowCount();

        String standardNumber = completeAnOrder("SKU-COR9-LEDGER-STD", "STANDARD",
                "cor9-ledger-std-" + System.nanoTime());
        String zeroNumber = completeAnOrder("SKU-COR9-LEDGER-ZERO", "ZERO",
                "cor9-ledger-zero-" + System.nanoTime());

        long after = ledgerRowCount();
        assertThat(after - before)
                .as("COR-9: the live runtime had ONE ledger row from ONE completed order, which "
                        + "cannot tell a working ledger from a coincidence")
                .isEqualTo(2L);

        // Each row carries its OWN order's resolved rate — not a hardcoded STANDARD, which is
        // the defect Issue #81 BUG 2 fixed and which a single-rate fixture could never detect.
        assertThat(ledgerVatRateFor(standardNumber)).isEqualTo("STANDARD");
        assertThat(ledgerVatRateFor(zeroNumber)).isEqualTo("ZERO");

        // And the money reconciles: the ledger amount is the order's own total.
        assertThat(ledgerAmountFor(standardNumber))
                .isEqualTo(readOrderLong(standardNumber, "total_amount_pennies"));
        assertThat(ledgerAmountFor(zeroNumber))
                .isEqualTo(readOrderLong(zeroNumber, "total_amount_pennies"));
    }

    @Test
    @DisplayName("COR-9: a ZERO-rated completed order books ZERO VAT — the arm an all-STANDARD catalogue cannot produce")
    void aZeroRatedOrderBooksZeroVat() {
        String orderNumber = completeAnOrder("SKU-COR9-ZERO-VAT", "ZERO",
                "cor9-zero-vat-" + System.nanoTime());

        assertThat(readOrderLong(orderNumber, "vat_amount_pennies"))
                .as("a zero-rated basket carries no VAT; every seeded dev product is STANDARD, so "
                        + "this arm exists only because the fixture creates one")
                .isZero();
        assertThat(readOrderString(orderNumber, "vat_rate")).isEqualTo("ZERO");
        assertThat(ledgerVatRateFor(orderNumber)).isEqualTo("ZERO");
    }

    // ---- helpers -----------------------------------------------------

    /** Places a COLLECTION order and drives it PENDING -> CONFIRMED -> PREPARING -> READY -> COMPLETED. */
    private String completeAnOrder(String sku, String vatRate, String idempotencyKey) {
        UUID productId = seedProduct(sku, 1200L, vatRate);
        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, collectionRequest(productId, 1, idempotencyKey));

        UUID orderId = lookupOrderId(confirmation.getOrderNumber());
        TenantContext.set(TENANT_ID);
        try {
            // VSA-02: every KDS transition requires at least STAFF on the order shop, and this
            // test has no vendor JWT. SystemPrincipal.asSystem is the DECLARED internal-caller
            // scope the repo already uses for exactly this (ConcurrentStockDecrementIntegrationTest
            // drives confirmOrder the same way). It is a declaration, not a bypass: the scope is
            // restored on exit, so nothing outside these four calls runs privileged.
            SystemPrincipal.asSystem(() -> {
                orderService.confirmOrder(orderId);
                orderService.startPreparation(orderId);
                orderService.markOrderReady(orderId);
                orderService.completeOrder(orderId);
            });
        } finally {
            TenantContext.clear();
        }
        return confirmation.getOrderNumber();
    }

    private GuestOrderRequest baseRequest(UUID productId, int qty, String idempotencyKey) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("COR-9 Buyer");
        request.setCustomerEmail("cor9-buyer@example.com");
        request.setCustomerPhone("+447700900909");
        request.setIdempotencyKey(idempotencyKey);
        request.setItems(List.of(item));
        return request;
    }

    private GuestOrderRequest deliveryRequest(UUID productId, int qty, String idempotencyKey) {
        GuestOrderRequest request = baseRequest(productId, qty, idempotencyKey);
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("12 Coldharbour Lane");
        request.setAddressCity("London");
        request.setAddressPostcode("SW9 8LF");
        return request;
    }

    private GuestOrderRequest collectionRequest(UUID productId, int qty, String idempotencyKey) {
        GuestOrderRequest request = baseRequest(productId, qty, idempotencyKey);
        request.setFulfilmentType("COLLECTION");
        return request;
    }

    private UUID seedShop() {
        TenantContext.set(TENANT_ID);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?",
                    UUID.class, TENANT_ID, SHOP_SLUG);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No opening_hours -> always open. minimum_order_pennies 0 so the WR-01 gate is not
            // what this test is measuring.
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, free_delivery_threshold_pennies, "
                            + "minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, ?, ?, 0, 0)",
                    id, TENANT_ID, "COR-9 Money Path Shop", SHOP_SLUG,
                    DELIVERY_FEE_PENNIES, FREE_DELIVERY_THRESHOLD_PENNIES);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedProduct(String sku, long pricePennies, String vatRate) {
        TenantContext.set(TENANT_ID);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM products WHERE tenant_id = ? AND sku = ?",
                    UUID.class, TENANT_ID, sku);
            if (!existing.isEmpty()) {
                UUID id = existing.get(0);
                jdbcTemplate.update(
                        "UPDATE products SET quantity_in_stock = 999, price_pennies = ?, "
                                + "vat_rate = ?, version = version + 1 WHERE id = ?",
                        pricePennies, vatRate, id);
                return id;
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, 999, ?, 0)",
                    id, TENANT_ID, sku, "COR-9 Item", "flour, water", pricePennies, shopId, vatRate);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID lookupOrderId(String orderNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", UUID.class, orderNumber);
    }

    private Long readOrderLong(String orderNumber, String column) {
        // Column name is a test-local constant, never caller input.
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM orders WHERE order_number = ?", Long.class, orderNumber);
    }

    private String readOrderString(String orderNumber, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM orders WHERE order_number = ?", String.class, orderNumber);
    }

    private long ledgerRowCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_transactions WHERE tenant_id = ?", Long.class, TENANT_ID);
        return count == null ? 0L : count;
    }

    private String ledgerVatRateFor(String orderNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT ft.vat_rate FROM financial_transactions ft "
                        + "JOIN orders o ON o.id = ft.order_id WHERE o.order_number = ?",
                String.class, orderNumber);
    }

    private Long ledgerAmountFor(String orderNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT ft.amount_pennies FROM financial_transactions ft "
                        + "JOIN orders o ON o.id = ft.order_id WHERE o.order_number = ?",
                Long.class, orderNumber);
    }
}
