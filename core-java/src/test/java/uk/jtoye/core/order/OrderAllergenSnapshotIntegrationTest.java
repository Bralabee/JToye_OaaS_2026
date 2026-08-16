package uk.jtoye.core.order;

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
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.PublicStorefrontService;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LGL-03 / plan 31-10 — the order's allergen set is SNAPSHOT at write time, not
 * live-joined back to {@code products} at read time.
 *
 * <h2>Why this test class exists</h2>
 *
 * <p>A live join means a vendor editing an allergen mask AFTER the order is placed silently
 * changes what the customer is recorded as having acknowledged, and what the kitchen ticket
 * shows. The customer acknowledged set A; the kitchen sees set B; no record of A exists
 * anywhere. On the one surface in this product that can physically injure someone that is the
 * wrong trade, so the mask is copied onto the order line beside the existing
 * {@code productName} snapshot (itself a UIX-03 root-cause fix for the same class of drift).
 *
 * <p>{@link #theSnapshotIsImmutableToALaterProductEdit()} is the assertion the whole migration
 * exists for, and it is written the only way that makes it evidence: the product is MUTATED
 * after the order is placed and the ORDER is re-read. Asserting that the two happen to be equal
 * at write time would pass identically under a live join.
 *
 * <h2>NULL is not zero</h2>
 *
 * <p>Rows written before V63 carry NULL, which means "not recorded". A mask of 0 means "the
 * vendor declared none of the 14 regulated allergens". Those are different statements and the
 * checkout copy for the second one is legally specific, so
 * {@link #aPreMigrationRowReadsAsNotRecordedNotAsNoAllergens()} pins the distinction at the
 * storage layer. No backfill invents a mask for historic orders: doing so would fabricate a
 * record of what a past customer was shown, which is this plan's own defect pointed backwards.
 *
 * <p>Runs against Testcontainers Postgres (application-test.yml defaults to H2, where Envers
 * against the real {@code order_items_aud} mirror and the real Flyway-managed schema would not
 * be exercised at all). No Stripe key in the test profile, so guest checkout takes the COD
 * branch and the order lands PENDING with no PaymentIntent.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// #283: the storefront path is unauthenticated by design; the JDBC seeding and the vendor-side
// re-reads below are the harness acting as the system. No authorization outcome is asserted here.
@uk.jtoye.core.testsupport.AsSystemHarness
class OrderAllergenSnapshotIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(OrderAllergenSnapshotIntegrationTest.class);

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
    @Autowired JdbcTemplate jdbcTemplate;

    /** Dedicated tenant so this class cannot collide with the phase-13/14/85 slug + SKU fixtures. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000031100000");
    private static final String SHOP_SLUG = "shop-3110-allergen-snapshot";

    // AllergenCatalog bit positions, spelled out so a reader does not have to decode the literal.
    private static final int GLUTEN = 1;          // 1 << 0
    private static final int MILK = 1 << 6;       // 64
    private static final int MUSTARD = 1 << 9;    // 512

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Phase 31-10 Tenant");
        shopId = seedShopIdempotent(TENANT_ID, SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 1. The declared mask is copied onto the line
    // ------------------------------------------------------------------
    @Test
    void declaredMaskIsSnapshottedOntoTheOrderItem() {
        UUID productId = seedProduct("SKU-3110-DECLARED", GLUTEN | MILK, "wheat flour, butter, sugar");

        UUID orderId = placeGuestOrder(productId);
        Map<String, Object> row = readSnapshot(orderId);

        assertThat(row.get("allergen_mask"))
                .as("the vendor's declared mask {Gluten, Milk} is persisted on the order line")
                .isEqualTo(GLUTEN | MILK);
        assertThat(row.get("allergen_flag_mask"))
                .as("no emphasised run in the ingredients text, so nothing is flagged")
                .isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // 2. QA council A11Y-02 verbatim — declared and flagged are stored SEPARATELY
    // ------------------------------------------------------------------
    @Test
    void reconciliationFlagIsStoredSeparatelyFromTheDeclaredMask() {
        UUID productId = seedProduct("SKU-3110-FLAG", 0, "flour, **milk**, sugar");

        UUID orderId = placeGuestOrder(productId);
        Map<String, Object> row = readSnapshot(orderId);

        // THE DANGEROUS DIRECTION, pinned in one assertion pair: the heuristic must NOT widen
        // the vendor's legally operative declaration. If a future change ORs the flag into the
        // declared mask, the first assertion fails; if it drops the flag entirely (the
        // under-declaration direction, the one that injures someone), the second fails.
        assertThat(row.get("allergen_mask"))
                .as("the declared mask stays exactly what the vendor declared — a text heuristic never widens it")
                .isEqualTo(0);
        assertThat(row.get("allergen_flag_mask"))
                .as("the emphasised **milk** run its mask omits is recorded as an ADVISORY flag, beside the declaration")
                .isEqualTo(MILK);
    }

    // ------------------------------------------------------------------
    // 3. THE ASSERTION THE MIGRATION EXISTS FOR
    // ------------------------------------------------------------------
    @Test
    void theSnapshotIsImmutableToALaterProductEdit() {
        UUID productId = seedProduct("SKU-3110-IMMUTABLE", GLUTEN | MILK, "wheat flour, butter");

        UUID orderId = placeGuestOrder(productId);
        Map<String, Object> before = readSnapshot(orderId);
        assertThat(before.get("allergen_mask")).isEqualTo(GLUTEN | MILK);

        // The vendor edits the product AFTER the order is placed, to a DISJOINT set.
        int updated = jdbcTemplate.update(
                "UPDATE products SET allergen_mask = ?, version = version + 1 WHERE id = ?",
                MUSTARD, productId);

        // VACUITY CONTROL. Read the ROW COUNT, never the exit code — a bare UPDATE against a
        // FORCE-RLS table matches zero rows and reports success (the recurring V25/V44/V57 trap).
        // Without this, a mutation that silently hit nothing would make the immutability
        // assertion below pass while proving nothing at all.
        assertThat(updated).as("the product edit must actually have hit a row").isEqualTo(1);
        Integer liveProductMask = jdbcTemplate.queryForObject(
                "SELECT allergen_mask FROM products WHERE id = ?", Integer.class, productId);
        assertThat(liveProductMask)
                .as("CONTROL: the live product really does now declare a DIFFERENT set")
                .isEqualTo(MUSTARD);

        Map<String, Object> after = readSnapshot(orderId);
        log.info("31-10 IMMUTABILITY: order line mask before={} after product edit={} (live product now={})",
                before.get("allergen_mask"), after.get("allergen_mask"), liveProductMask);

        assertThat(after.get("allergen_mask"))
                .as("the ORDER records what was true when it was placed; a later vendor edit cannot rewrite it. "
                        + "Under a live join this reads %d.", MUSTARD)
                .isEqualTo(GLUTEN | MILK);
    }

    // ------------------------------------------------------------------
    // 4. Nothing declared is a value, not an absence
    // ------------------------------------------------------------------
    @Test
    void aProductDeclaringNothingPersistsAnEmptySetNotNull() {
        // products.ingredients_text is NOT NULL since V1, so "no ingredients text" is the empty
        // string, not NULL — the real shape a vendor who filled in nothing produces.
        UUID productId = seedProduct("SKU-3110-EMPTY", 0, "");

        UUID orderId = placeGuestOrder(productId);
        Map<String, Object> row = readSnapshot(orderId);

        assertThat(row.get("allergen_mask"))
                .as("'the vendor declared none of the 14' is the value 0 — NOT NULL, which means 'not recorded'")
                .isNotNull()
                .isEqualTo(0);
        assertThat(row.get("allergen_flag_mask")).isNotNull().isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // 5. Historic rows are honest about being historic
    // ------------------------------------------------------------------
    @Test
    void aPreMigrationRowReadsAsNotRecordedNotAsNoAllergens() {
        UUID productId = seedProduct("SKU-3110-HISTORIC", GLUTEN, "wheat flour");
        UUID orderId = placeGuestOrder(productId);

        // Simulate a row written before V63: the columns exist but were never populated.
        // (V63 deliberately does NOT backfill — inventing a mask for a past order from today's
        // product rows would fabricate a record of what a past customer was shown.)
        int cleared = jdbcTemplate.update(
                "UPDATE order_items SET allergen_mask = NULL, allergen_flag_mask = NULL WHERE order_id = ?",
                orderId);
        assertThat(cleared).as("the simulated pre-V63 row must actually have been written").isEqualTo(1);

        Map<String, Object> row = readSnapshot(orderId);
        assertThat(row.get("allergen_mask"))
                .as("NULL means 'not recorded' and must stay distinguishable from 0 ('nothing declared')")
                .isNull();
        assertThat(row.get("allergen_flag_mask")).isNull();
    }

    // ------------------------------------------------------------------
    // 6. Envers — the mirror columns exist and a revision is written
    // ------------------------------------------------------------------
    @Test
    void enversMirrorsTheSnapshotColumnsOnOrderItemInsert() {
        List<String> audColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'order_items_aud' AND column_name IN ('allergen_mask', 'allergen_flag_mask') "
                        + "ORDER BY column_name",
                String.class);
        assertThat(audColumns)
                .as("OrderItem is @Audited: without these mirror columns Envers fails at RUNTIME, not at build time")
                .containsExactly("allergen_flag_mask", "allergen_mask");

        UUID productId = seedProduct("SKU-3110-ENVERS", MILK, "butter, cream");
        UUID orderId = placeGuestOrder(productId);

        UUID itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ?", UUID.class, orderId);
        List<Map<String, Object>> audRows = jdbcTemplate.queryForList(
                "SELECT allergen_mask, allergen_flag_mask FROM order_items_aud WHERE id = ? ORDER BY rev", itemId);

        assertThat(audRows)
                .as("creating an order item writes an Envers revision")
                .isNotEmpty();
        assertThat(audRows.get(0).get("allergen_mask"))
                .as("the audit mirror carries the same snapshot as the live row")
                .isEqualTo(MILK);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private UUID placeGuestOrder(UUID productId) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Guest Buyer");
        request.setCustomerEmail("guest-3110@example.com");
        request.setCustomerPhone("+447700900310");
        request.setIdempotencyKey("p3110-" + UUID.randomUUID());
        request.setItems(List.of(item));
        // COLLECTION: this class is fulfilment-agnostic and COLLECTION needs no UK address.
        request.setFulfilmentType("COLLECTION");

        TenantContext.clear();
        GuestOrderConfirmation confirmation =
                publicStorefrontService.createGuestOrder(SHOP_SLUG, request);
        TenantContext.clear();

        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", UUID.class, confirmation.getOrderNumber());
    }

    /** Reads the raw snapshot columns. Deliberately JDBC — a JPA read could serve a cached entity. */
    private Map<String, Object> readSnapshot(UUID orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT allergen_mask, allergen_flag_mask FROM order_items WHERE order_id = ?", orderId);
    }

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
            // No opening_hours -> the shop is always open (validateShopIsOpen returns early).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, tenantId, "Phase 31-10 Shop", slug);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedProduct(String sku, int allergenMask, String ingredientsText) {
        TenantContext.set(TENANT_ID);
        try {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, ?, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, TENANT_ID, sku, "Product " + sku, ingredientsText, allergenMask, shopId);
            return productId;
        } finally {
            TenantContext.clear();
        }
    }
}
