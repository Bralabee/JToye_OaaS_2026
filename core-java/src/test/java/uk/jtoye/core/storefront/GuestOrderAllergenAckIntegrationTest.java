package uk.jtoye.core.storefront;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 31.1 plan 03 (#784 P0, #785 P0; decisions D-05, D-06, D-07): the allergen acknowledgement
 * is a SERVER-enforced, RECORDED fact on a storefront order.
 *
 * <p>Before this plan the checkout checkbox gated the client only. The POST carried no
 * acknowledgement, so a direct API call bypassed it, and a vendor edit between render and submit
 * recorded a set the customer was never shown (persona run: acknowledged Gluten/Fish/Peanuts,
 * recorded Gluten/Eggs/Fish/Peanuts/Milk).
 *
 * <p>Every arm goes through the real HTTP endpoint ({@code POST /api/v1/public/shops/{slug}/orders})
 * with a JSON map body, and every persisted fact is read back by SQL rather than through a DTO,
 * so the assertion is about what the database holds. Real Postgres (Testcontainers): the
 * reservation semantics live in {@code INSERT ... ON CONFLICT} and the V69 columns are real DDL.
 * The class is NOT {@code @Transactional}; each request commits. The test profile has no Stripe
 * key, so every storefront order takes the COD branch.
 *
 * <p>RLS note: the Testcontainers bootstrap role is a SUPERUSER, so these arms prove the
 * application contract, not RLS enforcement. V69 adds columns only and no policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestOrderAllergenAckIntegrationTest {

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
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000311031");
    private static final String SHOP_SLUG = "shop-311-03-allergen-ack";
    private static final String ENDPOINT = "storefront.orders.create";

    /** AllergenCatalog bits, spelled out so a reader can check the expected sets by eye. */
    private static final int GLUTEN = 1;       // bit 0
    private static final int FISH = 1 << 3;    // bit 3
    private static final int PEANUTS = 1 << 4; // bit 4

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "31.1-03 Allergen Ack Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // #784 / D-05: the rule is on the SERVER. A body without the acknowledgement is refused,
    // with a typed problem, and leaves no order, item or reservation behind.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Issue 784: a storefront order without acknowledgedAllergenMask is refused 422 allergen-acknowledgement-required and writes nothing")
    void missingAcknowledgement_isRefused422_andWritesNothing() throws Exception {
        UUID product = seedProduct("SKU-31103-MISSING", "Fish Pie", GLUTEN | FISH);
        String key = "ack-missing-" + UUID.randomUUID();
        String email = "missing-" + UUID.randomUUID() + "@example.com";
        long ordersBefore = countOrders();

        MvcResult keyed = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(product, 1)), null));
        assertProblem(keyed, 422, "https://jtoye.uk/errors/allergen-acknowledgement-required",
                "ALLERGEN_ACKNOWLEDGEMENT_REQUIRED");

        // The keyless path (no reservation at all) is refused the same way.
        MvcResult keyless = perform(post(ordersUrl()), body(email, List.of(line(product, 1)), null));
        assertProblem(keyless, 422, "https://jtoye.uk/errors/allergen-acknowledgement-required",
                "ALLERGEN_ACKNOWLEDGEMENT_REQUIRED");

        assertThat(countOrders()).as("no order row was written").isEqualTo(ordersBefore);
        assertThat(countOrdersByEmail(email)).as("nothing under this customer").isZero();
        assertThat(countReservationsForKey(key))
                .as("the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();
    }

    // ------------------------------------------------------------------
    // #784 / D-06: an accepted order records the acknowledged mask, when it was acknowledged,
    // and the channel. The mask equals the OR of the V63 line snapshot BY CONSTRUCTION.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Issue 784: an acknowledged storefront order records allergen_ack_mask, allergen_ack_at and placed_via STOREFRONT; the mask equals OR(order_items.allergen_mask)")
    void acknowledgedOrder_recordsMaskTimeAndChannel() throws Exception {
        UUID fishPie = seedProduct("SKU-31103-ACCEPT-A", "Fish Pie", GLUTEN | FISH);
        UUID satay = seedProduct("SKU-31103-ACCEPT-B", "Satay", PEANUTS);
        int shown = renderedMask(List.of(fishPie, satay));
        assertThat(shown).as("the rendered set is Gluten, Fish, Peanuts").isEqualTo(GLUTEN | FISH | PEANUTS);
        String email = "accept-" + UUID.randomUUID() + "@example.com";

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        MvcResult created = perform(post(ordersUrl()),
                body(email, List.of(line(fishPie, 1), line(satay, 2)), shown));
        OffsetDateTime after = OffsetDateTime.now().plusSeconds(1);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        Map<String, Object> row = orderRowByEmail(email);
        assertThat(row.get("allergen_ack_mask")).as("the acknowledged mask is recorded").isEqualTo(shown);
        assertThat(row.get("placed_via")).isEqualTo("STOREFRONT");
        OffsetDateTime ackAt = readAckAt(email);
        assertThat(ackAt).as("acknowledged-at falls inside the request window").isBetween(before, after);

        Integer snapshotUnion = snapshotUnion((UUID) row.get("id"));
        assertThat(snapshotUnion)
                .as("D-06: the acknowledged mask equals the OR of the V63 line snapshot read by SQL")
                .isEqualTo((Integer) row.get("allergen_ack_mask"));

        // orders is @Audited: the Envers mirror carries the same facts (V69 added the _aud columns).
        Map<String, Object> aud = jdbcTemplate.queryForMap(
                "SELECT allergen_ack_mask, placed_via FROM orders_aud WHERE id = ? ORDER BY rev LIMIT 1",
                row.get("id"));
        assertThat(aud.get("allergen_ack_mask")).isEqualTo(shown);
        assertThat(aud.get("placed_via")).isEqualTo("STOREFRONT");
    }

    // ------------------------------------------------------------------
    // D-07: vendor, API and MCP orders (OrderService.createOrder) take no acknowledgement:
    // NULL ("not recorded") and placed_via VENDOR. CreateOrderRequest is unchanged.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-07: a vendor POST /api/v1/orders persists allergen_ack_mask NULL, allergen_ack_at NULL and placed_via VENDOR")
    void vendorOrder_recordsVendorChannelAndNoAcknowledgement() throws Exception {
        UUID product = seedProduct("SKU-31103-VENDOR", "Fish Pie", GLUTEN | FISH);
        String email = "vendor-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", shopId);
        request.put("customerEmail", email);
        request.put("items", List.of(Map.of("productId", product, "quantity", 1)));

        MvcResult created = perform(post("/api/v1/orders")
                .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                .claim("tenant_id", TENANT_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                                new SimpleGrantedAuthority("SCOPE_orders:write")))
                .header("Idempotency-Key", "vendor-" + UUID.randomUUID()), request);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        Map<String, Object> row = orderRowByEmail(email);
        assertThat(row.get("allergen_ack_mask")).as("not recorded, never 0").isNull();
        assertThat(readAckAt(email)).isNull();
        assertThat(row.get("placed_via")).isEqualTo("VENDOR");
    }

    // ---- HTTP helpers ----

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

    /** A guest-order body. {@code ack == null} OMITS the field, which is what an old client sends. */
    private static Map<String, Object> body(String email, List<Map<String, Object>> items, Integer ack) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Allergen Ack Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "+447700900311");
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        if (ack != null) {
            body.put("acknowledgedAllergenMask", ack);
        }
        return body;
    }

    /** The set the storefront RENDERS for these products: the public menu endpoint, as the checkout reads it. */
    private int renderedMask(List<UUID> productIds) throws Exception {
        MvcResult menu = mockMvc.perform(get("/api/v1/public/shops/" + SHOP_SLUG + "/products")).andReturn();
        assertThat(menu.getResponse().getStatus()).isEqualTo(200);
        JsonNode categories = jsonMapper.readTree(menu.getResponse().getContentAsString());
        int mask = 0;
        int found = 0;
        for (JsonNode products : categories) {
            for (JsonNode product : products) {
                if (productIds.contains(UUID.fromString(product.get("id").asString()))) {
                    mask |= product.get("allergenMask").asInt();
                    found++;
                }
            }
        }
        assertThat(found).as("every basket product is on the rendered menu").isEqualTo(productIds.size());
        return mask;
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

    private long countOrders() {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ?", Long.class, TENANT_ID)));
    }

    private long countOrdersByEmail(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, TENANT_ID, email)));
    }

    private Map<String, Object> orderRowByEmail(String email) {
        return inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT id, allergen_ack_mask, placed_via FROM orders WHERE tenant_id = ? AND customer_email = ?",
                TENANT_ID, email));
    }

    private OffsetDateTime readAckAt(String email) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT allergen_ack_at FROM orders WHERE tenant_id = ? AND customer_email = ?",
                OffsetDateTime.class, TENANT_ID, email));
    }

    private Integer snapshotUnion(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT bit_or(allergen_mask) FROM order_items WHERE order_id = ?", Integer.class, orderId));
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, ENDPOINT, key)));
    }

    // ---- Idempotent seed helpers (the GuestCheckoutIdempotencyIntegrationTest pattern) ----

    private UUID seedShopIdempotent(String slug) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No opening_hours -> the shop is always open (validateShopIsOpen returns early).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, TENANT_ID, "31.1-03 Allergen Ack Shop", slug);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix), so an arm that edits a mask cannot leak into another. */
    private UUID seedProduct(String skuPrefix, String title, int allergenMask) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, ?, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, "see label", allergenMask, shopId);
            return productId;
        });
    }
}
