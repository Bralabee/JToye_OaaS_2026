package uk.jtoye.core.order;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-26 (D-20, RWO-042 / UXT-042): the size of an order is bounded on EVERY writer, through the
 * real HTTP endpoints, with the typed 422 and the facts read back by SQL.
 *
 * <p>The persona run placed a 1,000,000-unit line and was given a £19 billion order (UXT-042), because
 * the only bound was {@code @Min(1)} on the request DTOs. Every arm here goes through the endpoint a
 * client uses — {@code POST /api/v1/public/shops/{slug}/orders} for the storefront, and (Task 2)
 * {@code POST /api/v1/orders} for the vendor dashboard, the REST API and the MCP {@code create_order}
 * tool, which all share that route. Real Postgres (Testcontainers): the idempotency reservation the
 * storefront refusal must roll back lives in {@code INSERT ... ON CONFLICT}. The class is NOT
 * {@code @Transactional}; each request commits. The test profile has no Stripe key, so every
 * storefront order takes the COD branch.
 *
 * <p>The limits in force are the application defaults (50 per line, 200 units, 50 lines, £1,000);
 * {@code OrderLimitsTest} pins the boundaries, this class proves each writer reaches them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderLimitsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000003726");
    private static final String SHOP_SLUG = "shop-37-26-order-limits";
    private static final String STOREFRONT_ENDPOINT = "storefront.orders.create";
    private static final String PROBLEM_TYPE = "https://jtoye.uk/errors/order-limit-exceeded";
    private static final String PROBLEM_CODE = "ORDER_LIMIT_EXCEEDED";

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-26 Order Limits Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Storefront: the per-line ceiling, the typed body, the rolled-back reservation, and the
    // corrected resubmit under the SAME key (T-37-67).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-042: a storefront line of 51 is refused 422 order-limit-exceeded naming quantity/51/50 and the product, writes nothing, and the same key with 50 then succeeds 201")
    void storefrontLineAboveTheLimit_isRefusedTyped_andTheSameKeyCorrectedSucceeds() throws Exception {
        UUID jollof = seedProduct("SKU-3726-LINE", "Jollof Rice", 1000L, 100);
        String key = "limit-line-" + UUID.randomUUID();
        String email = "line-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = perform(post(ordersUrl()).header("Idempotency-Key", key),
                storefrontBody(email, List.of(line(jollof, 51))));
        JsonNode problem = assertProblem(refused, 422, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("field").asString()).as(text).isEqualTo("quantity");
        assertThat(problem.path("value").asLong()).as(text).isEqualTo(51L);
        assertThat(problem.path("limit").asLong()).as(text).isEqualTo(50L);
        assertThat(problem.path("productName").asString()).as(text).isEqualTo("Jollof Rice");
        assertThat(problem.path("detail").asString()).as(text).isEqualTo("quantity is 51, above the limit of 50.");

        assertThat(countOrdersByEmail(email)).as("the refused order was not written").isZero();
        assertThat(countReservationsForKey(key))
                .as("T-37-67: the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();

        // The customer lowers the line to the limit and the client resubmits under the same key.
        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                storefrontBody(email, List.of(line(jollof, 50))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByEmail(email)).isEqualTo(1);
        assertThat(lineQuantities(email)).containsExactly(50);
    }

    @Test
    @DisplayName("P2-CHA-16: a 20-line, 40-unit storefront basket hits no limit (201)")
    void comfortableBasket_isAccepted() throws Exception {
        List<Map<String, Object>> basket = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            basket.add(line(seedProduct("SKU-3726-CHA16-" + i, "Dish " + i, 500L, 100), 2));
        }
        String email = "cha16-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl()), storefrontBody(email, basket));
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        assertThat(lineQuantities(email)).hasSize(20).containsOnly(2);
        assertThat(totalPennies(email)).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("UXT-007 repro basket: 35 items totalling GBP 198.50 across 7 lines hits no limit (201)")
    void officeLunchBasket_isAccepted() throws Exception {
        long[] prices = {450L, 500L, 550L, 600L, 620L, 650L, 600L}; // 5 of each = 35 units, 19850p
        List<Map<String, Object>> basket = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            basket.add(line(seedProduct("SKU-3726-UXT007-" + i, "Lunch " + i, prices[i], 100), 5));
        }
        String email = "uxt007-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl()), storefrontBody(email, basket));
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        assertThat(lineQuantities(email)).hasSize(7).containsOnly(5);
        assertThat(totalPennies(email)).as("GBP 198.50").isEqualTo(19_850L);
    }

    // ------------------------------------------------------------------
    // Vendor / REST / MCP: POST /api/v1/orders is the one route the dashboard, an API client and the
    // MCP create_order tool all use (OrderService.createOrder). Theme 6: the SAME validator, the SAME
    // 422, or the £19 billion order comes back through the door the storefront fix did not close.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-042: a vendor POST /api/v1/orders with quantity 1,000,000 is refused 422 order-limit-exceeded on quantity and writes nothing; quantity 50 is 201")
    void vendorLineAboveTheLimit_isRefusedTyped_andTheLimitItselfIsAccepted() throws Exception {
        // Untracked stock (NULL), so the absurd quantity reaches the limit rule, not the stock rule.
        UUID zobo = seedProduct("SKU-3726-VENDOR", "Zobo", 1900L, null);
        String email = "vendor-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = performVendor(vendorBody(email, List.of(line(zobo, 1_000_000))));
        JsonNode problem = assertProblem(refused, 422, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("field").asString()).as(text).isEqualTo("quantity");
        assertThat(problem.path("value").asLong()).as(text).isEqualTo(1_000_000L);
        assertThat(problem.path("limit").asLong()).as(text).isEqualTo(50L);
        assertThat(problem.path("productName").asString()).as(text).isEqualTo("Zobo");
        assertThat(countOrdersByEmail(email)).as("the refused order was not written").isZero();

        MvcResult accepted = performVendor(vendorBody(email, List.of(line(zobo, 50))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(lineQuantities(email)).containsExactly(50);
        assertThat(totalPennies(email)).isEqualTo(95_000L);
    }

    @Test
    @DisplayName("D-20: a vendor order of 51 single-unit lines is refused 422 on lines (51 > 50)")
    void vendorTooManyLines_isRefusedOnLines() throws Exception {
        List<Map<String, Object>> basket = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            basket.add(line(seedProduct("SKU-3726-LINES-" + i, "Side " + i, 100L, null), 1));
        }
        String email = "lines-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = performVendor(vendorBody(email, basket));
        JsonNode problem = assertProblem(refused, 422, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("field").asString()).as(text).isEqualTo("lines");
        assertThat(problem.path("value").asLong()).as(text).isEqualTo(51L);
        assertThat(problem.path("limit").asLong()).as(text).isEqualTo(50L);
        assertThat(problem.path("productName").isNull()).as("a basket-level refusal names no line: " + text).isTrue();
        assertThat(countOrdersByEmail(email)).isZero();
    }

    @Test
    @DisplayName("D-20: a vendor order of 201 units over 5 lines (each within its own limit) is refused 422 on units (201 > 200)")
    void vendorTooManyUnits_isRefusedOnUnits() throws Exception {
        List<Map<String, Object>> basket = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            basket.add(line(seedProduct("SKU-3726-UNITS-" + i, "Tray " + i, 100L, null), 50));
        }
        basket.add(line(seedProduct("SKU-3726-UNITS-X", "Extra", 100L, null), 1));
        String email = "units-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = performVendor(vendorBody(email, basket));
        JsonNode problem = assertProblem(refused, 422, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("field").asString()).as(text).isEqualTo("units");
        assertThat(problem.path("value").asLong()).as(text).isEqualTo(201L);
        assertThat(problem.path("limit").asLong()).as(text).isEqualTo(200L);
        assertThat(countOrdersByEmail(email)).isZero();
    }

    @Test
    @DisplayName("D-20: a vendor order totalling GBP 1,000.01 over within-limit lines is refused 422 on total (100001 > 100000)")
    void vendorBasketAboveTheTotalLimit_isRefusedOnTotal() throws Exception {
        UUID platter = seedProduct("SKU-3726-TOTAL-A", "Platter", 50_000L, null);
        UUID mint = seedProduct("SKU-3726-TOTAL-B", "Mint", 1L, null);
        String email = "total-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = performVendor(vendorBody(email, List.of(line(platter, 2), line(mint, 1))));
        JsonNode problem = assertProblem(refused, 422, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("field").asString()).as(text).isEqualTo("total");
        assertThat(problem.path("value").asLong()).as(text).isEqualTo(100_001L);
        assertThat(problem.path("limit").asLong()).as(text).isEqualTo(100_000L);
        assertThat(countOrdersByEmail(email)).isZero();

        // Exactly the limit passes: the boundary is inclusive.
        MvcResult accepted = performVendor(vendorBody(email, List.of(line(platter, 2))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(totalPennies(email)).isEqualTo(100_000L);
    }

    // ------------------------------------------------------------------
    // The storefront stepper stops where checkout refuses (UI-SPEC open item 4): the public shop
    // payload carries the configured per-line ceiling.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-20: GET /api/v1/public/shops/{slug} exposes maxLineQuantity 50 from jtoye.order-limits.max-line-quantity")
    void publicShopExposesTheLineLimit() throws Exception {
        MvcResult shop = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/public/shops/" + SHOP_SLUG)).andReturn();
        String text = responseText(shop);
        assertThat(shop.getResponse().getStatus()).as(text).isEqualTo(200);
        JsonNode body = jsonMapper.readTree(shop.getResponse().getContentAsString());
        assertThat(body.path("maxLineQuantity").asInt()).as(text).isEqualTo(50);
    }

    // ---- HTTP helpers ----

    /** The vendor route with a realm-admin token for this tenant (an implicit GROUP_ADMIN, D-03) and a fresh key. */
    private MvcResult performVendor(Map<String, Object> body) throws Exception {
        return perform(post("/api/v1/orders")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_admin"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_orders:write")))
                .header("Idempotency-Key", "vendor-" + UUID.randomUUID()), body);
    }

    private Map<String, Object> vendorBody(String email, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shopId);
        body.put("customerName", "Order Limits Vendor Customer");
        body.put("customerEmail", email);
        body.put("items", items);
        return body;
    }

    private String ordersUrl() {
        return "/api/v1/public/shops/" + SHOP_SLUG + "/orders";
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> line(UUID productId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    /** A storefront body acknowledging the CURRENT allergen set of its products (D-05), as the checkout does. */
    private Map<String, Object> storefrontBody(String email, List<Map<String, Object>> items) {
        List<UUID> productIds = items.stream().map(i -> (UUID) i.get("productId")).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Order Limits Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "+447700900372");
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        body.put("acknowledgedAllergenMask", GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, productIds));
        // 37-32 (D-16): every line carries the unit price the customer saw, and the body the total.
        body.put("expectedTotalPennies", GuestOrderAcknowledgements.expectCurrentPrices(jdbcTemplate, TENANT_ID, "COLLECTION", items));
        return body;
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(code);
        return problem;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    // ---- SQL read helpers (superuser bootstrap role; tenant pinned for correctness) ----

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long countOrdersByEmail(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, TENANT_ID, email)));
    }

    private List<Integer> lineQuantities(String email) {
        return inTenant(() -> jdbcTemplate.queryForList(
                "SELECT oi.quantity FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                        + "WHERE o.tenant_id = ? AND o.customer_email = ? ORDER BY oi.quantity",
                Integer.class, TENANT_ID, email));
    }

    private long totalPennies(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT total_amount_pennies FROM orders WHERE tenant_id = ? AND customer_email = ?",
                Long.class, TENANT_ID, email)));
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, STOREFRONT_ENDPOINT, key)));
    }

    // ---- Idempotent seed helpers (the GuestOrderAllergenAckIntegrationTest pattern) ----

    private UUID seedShopIdempotent(String slug) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No hours and LEGACY_ALWAYS_OPEN (37-20, D-25): the shop takes orders at any time, and no
            // minimum order, so the only refusal an arm can meet is the one under test.
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, TENANT_ID, "37-26 Order Limits Shop", slug);
            return id;
        });
    }

    /**
     * A fresh product per call (unique SKU suffix). {@code quantityInStock} null = untracked stock, so
     * an absurd quantity reaches the limit rule rather than the stock rule (the UXT-042 shape).
     */
    private UUID seedProduct(String skuPrefix, String title, long pricePennies, Integer quantityInStock) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, ?, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, "see label", pricePennies, shopId,
                    quantityInStock);
            return productId;
        });
    }
}
