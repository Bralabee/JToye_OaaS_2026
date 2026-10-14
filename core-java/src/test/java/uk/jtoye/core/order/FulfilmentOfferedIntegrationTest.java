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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-48 (RWO-023 / UXT-023): a shop says which fulfilment types it offers, and EVERY order writer
 * refuses one it does not, through the real HTTP endpoints, with the facts read back by SQL.
 *
 * <p>The persona run found no way to run a collection-only kitchen: every shop took DELIVERY and COLLECTION on
 * both writers. The two writers are {@code POST /api/v1/public/shops/{slug}/orders} (the storefront checkout)
 * and {@code POST /api/v1/orders} (the vendor dashboard, the REST API and the MCP {@code create_order} tool,
 * which share {@code OrderService.createOrder}). Both call {@code FulfilmentPolicy.requireOffered} right after
 * the type is resolved, so the rule is the same on both doors; an arm per door proves neither was missed.
 *
 * <p>Real Postgres (Testcontainers) with every migration, V83 included; the class is NOT
 * {@code @Transactional}, each request commits. Every test seeds its OWN shop, so the 37-27 per-shop cash caps
 * never meet an arm. The test profile has no Stripe key, so every storefront order takes the COD branch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class FulfilmentOfferedIntegrationTest {

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
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000003748");
    private static final String STOREFRONT_ENDPOINT = "storefront.orders.create";
    private static final String PROBLEM_TYPE = "https://jtoye.uk/errors/fulfilment-not-offered";
    private static final String PROBLEM_CODE = "FULFILMENT_NOT_OFFERED";

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-48 Fulfilment Offered Tenant");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Task 1: the offered types are a rule on BOTH writers (set by SQL here; the shop API writes them in Task 2).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-023: a collection-only shop refuses a storefront DELIVERY order 422 fulfilment-not-offered naming COLLECTION, writes nothing, and the same key as COLLECTION is 201")
    void storefrontDelivery_toCollectionOnlyShop_isRefusedTyped_andCollectionSucceeds() throws Exception {
        Fixture f = seedShop("storefront-co");
        setOffered(f.shopId, false, true);
        String key = "offered-sf-" + UUID.randomUUID();
        String email = "sf-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = perform(post(storefrontUrl(f)).header("Idempotency-Key", key),
                storefrontBody(email, "DELIVERY", List.of(line(f.productId, 1)), null));
        JsonNode problem = assertProblem(refused, 422);
        String text = responseText(refused);
        assertThat(problem.path("fulfilmentType").asString()).as(text).isEqualTo("DELIVERY");
        assertThat(problem.path("offered").isArray()).as(text).isTrue();
        assertThat(problem.path("offered").get(0).asString()).as(text).isEqualTo("COLLECTION");
        assertThat(problem.path("detail").asString()).as(text)
                .isEqualTo("This shop doesn't offer delivery. Choose collection to continue.");
        assertThat(countOrdersByEmail(email)).as("the refused order was not written").isZero();
        assertThat(countReservationsForKey(key))
                .as("the refusal is thrown inside the reserved work, so the reservation rolled back").isZero();

        MvcResult accepted = perform(post(storefrontUrl(f)).header("Idempotency-Key", key),
                storefrontBody(email, "COLLECTION", List.of(line(f.productId, 1)), null));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(fulfilmentTypes(email)).containsExactly("COLLECTION");
    }

    @Test
    @DisplayName("UXT-023: a collection-only shop refuses a vendor/API/MCP DELIVERY order 422 fulfilment-not-offered and writes nothing; COLLECTION is 201")
    void vendorDelivery_toCollectionOnlyShop_isRefusedTyped_andCollectionSucceeds() throws Exception {
        Fixture f = seedShop("vendor-co");
        setOffered(f.shopId, false, true);
        String email = "vendor-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = performVendor(vendorBody(f, email, "DELIVERY", List.of(line(f.productId, 1))));
        JsonNode problem = assertProblem(refused, 422);
        String text = responseText(refused);
        assertThat(problem.path("fulfilmentType").asString()).as(text).isEqualTo("DELIVERY");
        assertThat(problem.path("offered").get(0).asString()).as(text).isEqualTo("COLLECTION");
        assertThat(countOrdersByEmail(email)).as("the refused order was not written").isZero();

        MvcResult accepted = performVendor(vendorBody(f, email, "COLLECTION", List.of(line(f.productId, 1))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(fulfilmentTypes(email)).containsExactly("COLLECTION");
    }

    @Test
    @DisplayName("UXT-023: a delivery-only shop refuses the vendor path's COLLECTION fallback (no type sent) 422 naming DELIVERY; an explicit DELIVERY is 201")
    void vendorFallbackCollection_toDeliveryOnlyShop_isRefusedTyped() throws Exception {
        Fixture f = seedShop("vendor-do");
        setOffered(f.shopId, true, false);
        String email = "fallback-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = performVendor(vendorBody(f, email, null, List.of(line(f.productId, 1))));
        JsonNode problem = assertProblem(refused, 422);
        String text = responseText(refused);
        assertThat(problem.path("fulfilmentType").asString()).as(text).isEqualTo("COLLECTION");
        assertThat(problem.path("offered").get(0).asString()).as(text).isEqualTo("DELIVERY");
        assertThat(countOrdersByEmail(email)).isZero();

        MvcResult accepted = performVendor(vendorBody(f, email, "DELIVERY", List.of(line(f.productId, 1))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(fulfilmentTypes(email)).containsExactly("DELIVERY");
    }

    @Test
    @DisplayName("Goods: a shop left at the V83 default (both offered) still takes DELIVERY and COLLECTION on both writers")
    void defaultShop_takesBothTypes_onBothWriters() throws Exception {
        Fixture f = seedShop("default-both");
        assertThat(offeredFlags(f.shopId)).as("V83 default").containsExactly(true, true);
        String email = "both-" + UUID.randomUUID() + "@example.com";

        for (String type : List.of("DELIVERY", "COLLECTION")) {
            MvcResult sf = perform(post(storefrontUrl(f)),
                    storefrontBody(email, type, List.of(line(f.productId, 1)), null));
            assertThat(sf.getResponse().getStatus()).as(responseText(sf)).isEqualTo(201);
            MvcResult vendor = performVendor(vendorBody(f, email, type, List.of(line(f.productId, 1))));
            assertThat(vendor.getResponse().getStatus()).as(responseText(vendor)).isEqualTo(201);
        }
        assertThat(fulfilmentTypes(email)).containsExactlyInAnyOrder("DELIVERY", "DELIVERY", "COLLECTION", "COLLECTION");
    }

    @Test
    @DisplayName("V83: the database refuses a shop that offers neither type (ck_shops_offers_some)")
    void database_refusesAShopOfferingNeither() {
        Fixture f = seedShop("neither-db");
        assertThatThrownBy(() -> setOffered(f.shopId, false, false))
                .hasMessageContaining("ck_shops_offers_some");
        assertThat(offeredFlags(f.shopId)).containsExactly(true, true);
    }

    // ------------------------------------------------------------------
    // Fixture, request and SQL helpers
    // ------------------------------------------------------------------

    private record Fixture(UUID shopId, String slug, UUID productId) { }

    private Fixture seedShop(String label) {
        return inTenant(() -> {
            UUID shopId = UUID.randomUUID();
            String slug = "shop-37-48-" + label + "-" + shopId.toString().substring(0, 8);
            // No hours and LEGACY_ALWAYS_OPEN (37-20, D-25): open at any time, no minimum order, no fee, so the
            // only refusal an arm can meet is the one under test.
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    shopId, TENANT_ID, "37-48 " + label, slug);
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, TENANT_ID, "SKU-3748-" + productId, "Jollof Rice", "see label", 2500L, shopId);
            return new Fixture(shopId, slug, productId);
        });
    }

    private void setOffered(UUID shopId, boolean delivery, boolean collection) {
        inTenant(() -> jdbcTemplate.update(
                "UPDATE shops SET offers_delivery = ?, offers_collection = ? WHERE id = ?",
                delivery, collection, shopId));
    }

    private List<Boolean> offeredFlags(UUID shopId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT offers_delivery, offers_collection FROM shops WHERE id = ?",
                (rs, n) -> List.of(rs.getBoolean(1), rs.getBoolean(2)), shopId));
    }

    private String storefrontUrl(Fixture f) {
        return "/api/v1/public/shops/" + f.slug + "/orders";
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static RequestPostProcessor vendorJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    private MvcResult performVendor(Object body) throws Exception {
        return perform(post("/api/v1/orders").with(vendorJwt())
                .header("Idempotency-Key", "vendor-" + UUID.randomUUID()), body);
    }

    private static Map<String, Object> line(UUID productId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    private static void putAddress(Map<String, Object> body) {
        body.put("addressLine1", "1 High Street");
        body.put("addressCity", "London");
        body.put("addressPostcode", "SW1A 1AA");
    }

    private static String randomPhone() {
        return "+4477009" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));
    }

    private Map<String, Object> vendorBody(Fixture f, String email, String type, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", f.shopId);
        body.put("customerName", "Fulfilment Offered Vendor Customer");
        body.put("customerEmail", email);
        if (type != null) {
            body.put("fulfilmentType", type);
        }
        if ("DELIVERY".equals(type)) {
            putAddress(body);
        }
        body.put("items", items);
        return body;
    }

    /**
     * A storefront body acknowledging the CURRENT allergen set (D-05) and carrying the prices shown (37-32). The
     * total is the caller's when given (the fee arms state the figure they expect the server to charge), otherwise
     * the shared test helper's.
     */
    private Map<String, Object> storefrontBody(String email, String type, List<Map<String, Object>> items,
                                               Long expectedTotal) {
        List<UUID> productIds = items.stream().map(i -> (UUID) i.get("productId")).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Fulfilment Offered Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", randomPhone());
        body.put("fulfilmentType", type);
        if ("DELIVERY".equals(type)) {
            putAddress(body);
        }
        body.put("items", items);
        body.put("acknowledgedAllergenMask", GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, productIds));
        long helperTotal = GuestOrderAcknowledgements.expectCurrentPrices(jdbcTemplate, TENANT_ID, type, items);
        body.put("expectedTotalPennies", expectedTotal != null ? expectedTotal : helperTotal);
        return body;
    }

    private JsonNode assertProblem(MvcResult result, int status) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(PROBLEM_TYPE);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(PROBLEM_CODE);
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

    private List<String> fulfilmentTypes(String email) {
        return inTenant(() -> jdbcTemplate.queryForList(
                "SELECT fulfilment_type FROM orders WHERE tenant_id = ? AND customer_email = ? ORDER BY created_at",
                String.class, TENANT_ID, email));
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, STOREFRONT_ENDPOINT, key)));
    }
}
