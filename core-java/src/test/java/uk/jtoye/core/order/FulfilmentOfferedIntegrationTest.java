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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
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
    // Task 2: the vendor sets the fee, the threshold and the offered types through the shop API, and the
    // storefront charges and enforces what was set. Every PUT echoes the shop's slug, so these arms do not
    // depend on the slug rule Task 3 changes.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-023: a vendor PUT of fee 299 and threshold 3000 is stored and published; a DELIVERY order of 2500 is charged 299 (2799) and one of 3000 is charged 0 (3000), read by SQL")
    void vendorSetsFeeAndThreshold_andTheStorefrontChargesThem() throws Exception {
        Fixture f = seedShop("fee");
        UUID fifteen = seedProduct(f.shopId, "Puff Puff", 1500L);

        MvcResult put = putShop(f, Map.of("deliveryFeePennies", 299, "freeDeliveryThresholdPennies", 3000));
        String text = responseText(put);
        assertThat(put.getResponse().getStatus()).as(text).isEqualTo(200);
        JsonNode dto = jsonMapper.readTree(put.getResponse().getContentAsString());
        assertThat(dto.path("deliveryFeePennies").asLong()).as(text).isEqualTo(299L);
        assertThat(dto.path("freeDeliveryThresholdPennies").asLong()).as(text).isEqualTo(3000L);
        assertThat(feeAndThreshold(f.shopId)).as("stored").containsExactly(299L, 3000L);

        JsonNode pub = publicShop(f);
        assertThat(pub.path("deliveryFeePennies").asLong()).as(pub.toString()).isEqualTo(299L);
        assertThat(pub.path("freeDeliveryThresholdPennies").asLong()).as(pub.toString()).isEqualTo(3000L);

        String below = "below-" + UUID.randomUUID() + "@example.com";
        MvcResult under = perform(post(storefrontUrl(f)),
                storefrontBody(below, "DELIVERY", List.of(line(f.productId, 1)), 2799L));
        assertThat(under.getResponse().getStatus()).as(responseText(under)).isEqualTo(201);
        assertThat(totalAndFee(below)).as("2500 below the 3000 threshold pays the 299 fee").containsExactly(2799L, 299L);

        String at = "at-" + UUID.randomUUID() + "@example.com";
        MvcResult atThreshold = perform(post(storefrontUrl(f)),
                storefrontBody(at, "DELIVERY", List.of(line(fifteen, 2)), 3000L));
        assertThat(atThreshold.getResponse().getStatus()).as(responseText(atThreshold)).isEqualTo(201);
        assertThat(totalAndFee(at)).as("3000 reaches the threshold (inclusive): no fee").containsExactly(3000L, 0L);
    }

    @Test
    @DisplayName("UXT-023: a vendor PUT of offersDelivery false is stored and published; DELIVERY is then 422 on both writers and COLLECTION 201")
    void vendorSetsCollectionOnly_andBothWritersEnforceIt() throws Exception {
        Fixture f = seedShop("put-co");

        MvcResult put = putShop(f, Map.of("offersDelivery", false, "offersCollection", true));
        String text = responseText(put);
        assertThat(put.getResponse().getStatus()).as(text).isEqualTo(200);
        JsonNode dto = jsonMapper.readTree(put.getResponse().getContentAsString());
        assertThat(dto.path("offersDelivery").isBoolean()).as(text).isTrue();
        assertThat(dto.path("offersDelivery").asBoolean()).as(text).isFalse();
        assertThat(dto.path("offersCollection").asBoolean()).as(text).isTrue();
        assertThat(offeredFlags(f.shopId)).as("stored").containsExactly(false, true);

        JsonNode pub = publicShop(f);
        assertThat(pub.path("offersDelivery").isBoolean()).as(pub.toString()).isTrue();
        assertThat(pub.path("offersDelivery").asBoolean()).as(pub.toString()).isFalse();
        assertThat(pub.path("offersCollection").asBoolean()).as(pub.toString()).isTrue();

        String email = "put-co-" + UUID.randomUUID() + "@example.com";
        assertProblem(perform(post(storefrontUrl(f)),
                storefrontBody(email, "DELIVERY", List.of(line(f.productId, 1)), null)), 422);
        assertProblem(performVendor(vendorBody(f, email, "DELIVERY", List.of(line(f.productId, 1)))), 422);
        assertThat(countOrdersByEmail(email)).isZero();
        MvcResult collection = perform(post(storefrontUrl(f)),
                storefrontBody(email, "COLLECTION", List.of(line(f.productId, 1)), null));
        assertThat(collection.getResponse().getStatus()).as(responseText(collection)).isEqualTo(201);
    }

    @Test
    @DisplayName("UXT-023: neither type offered is a 400 naming the rule, whether sent together or merged with the stored flag; nothing changes")
    void neitherOffered_isRefused400_sentOrMerged() throws Exception {
        Fixture f = seedShop("neither-api");

        MvcResult both = putShop(f, Map.of("offersDelivery", false, "offersCollection", false));
        assertValidation400(both, "fulfilmentOffered");
        assertThat(offeredFlags(f.shopId)).containsExactly(true, true);

        setOffered(f.shopId, false, true);
        MvcResult merged = putShop(f, Map.of("offersCollection", false));
        assertValidation400(merged, "fulfilmentOffered");
        assertThat(offeredFlags(f.shopId)).as("the stored collection-only shop is unchanged").containsExactly(false, true);

        MvcResult create = createShop(Map.of("offersDelivery", false, "offersCollection", false));
        assertValidation400(create, "fulfilmentOffered");
    }

    @Test
    @DisplayName("T-37-116: a negative fee or threshold is a 400 naming the field; nothing changes")
    void negativeFeeOrThreshold_isRefused400() throws Exception {
        Fixture f = seedShop("negative");
        assertValidation400(putShop(f, Map.of("deliveryFeePennies", -1)), "deliveryFeePennies");
        assertValidation400(putShop(f, Map.of("freeDeliveryThresholdPennies", -1)), "freeDeliveryThresholdPennies");
        assertThat(feeAndThreshold(f.shopId)).containsExactly(0L, null);
    }

    @Test
    @DisplayName("UXT-023: an absent threshold keeps the stored one; an explicit null clears it (no free delivery), and the fee then applies above 3000")
    void threshold_absentKeeps_explicitNullClears() throws Exception {
        Fixture f = seedShop("clear");
        assertThat(putShop(f, Map.of("deliveryFeePennies", 299, "freeDeliveryThresholdPennies", 3000))
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(putShop(f, Map.of()).getResponse().getStatus()).isEqualTo(200);
        assertThat(feeAndThreshold(f.shopId)).as("absent keeps").containsExactly(299L, 3000L);

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("freeDeliveryThresholdPennies", null);
        MvcResult cleared = putShop(f, clear);
        assertThat(cleared.getResponse().getStatus()).as(responseText(cleared)).isEqualTo(200);
        assertThat(feeAndThreshold(f.shopId)).as("explicit null clears").containsExactly(299L, null);

        UUID fifteen = seedProduct(f.shopId, "Chin Chin", 1500L);
        String email = "cleared-" + UUID.randomUUID() + "@example.com";
        MvcResult order = perform(post(storefrontUrl(f)),
                storefrontBody(email, "DELIVERY", List.of(line(fifteen, 2)), 3299L));
        assertThat(order.getResponse().getStatus()).as(responseText(order)).isEqualTo(201);
        assertThat(totalAndFee(email)).containsExactly(3299L, 299L);
    }

    @Test
    @DisplayName("UXT-023: a shop created with a fee, a threshold and collection only stores them; one created without them takes both types and charges nothing")
    void createShop_storesFeeThresholdAndOfferedTypes_withDefaults() throws Exception {
        MvcResult created = createShop(Map.of("deliveryFeePennies", 199, "freeDeliveryThresholdPennies", 2000,
                "offersDelivery", false));
        String text = responseText(created);
        assertThat(created.getResponse().getStatus()).as(text).isEqualTo(201);
        UUID id = UUID.fromString(jsonMapper.readTree(created.getResponse().getContentAsString()).path("id").asString());
        assertThat(feeAndThreshold(id)).containsExactly(199L, 2000L);
        assertThat(offeredFlags(id)).containsExactly(false, true);

        MvcResult plain = createShop(Map.of());
        String plainText = responseText(plain);
        assertThat(plain.getResponse().getStatus()).as(plainText).isEqualTo(201);
        UUID plainId = UUID.fromString(jsonMapper.readTree(plain.getResponse().getContentAsString()).path("id").asString());
        assertThat(feeAndThreshold(plainId)).containsExactly(0L, null);
        assertThat(offeredFlags(plainId)).containsExactly(true, true);
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

    private UUID seedProduct(UUID shopId, String title, long pricePennies) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, TENANT_ID, "SKU-3748-" + productId, title, "see label", pricePennies, shopId);
            return productId;
        });
    }

    /** PUT /api/v1/shops/{id} with the shop's name and slug plus the given fields (a vendor dashboard save). */
    private MvcResult putShop(Fixture f, Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", shopName(f.shopId));
        body.put("slug", f.slug);
        body.putAll(fields);
        return perform(put("/api/v1/shops/" + f.shopId).with(vendorJwt()), body);
    }

    private MvcResult createShop(Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "37-48 created " + UUID.randomUUID());
        body.putAll(fields);
        return perform(post("/api/v1/shops").with(vendorJwt()), body);
    }

    private JsonNode publicShop(Fixture f) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/shops/" + f.slug)).andReturn();
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertValidation400(MvcResult result, String field) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(400);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo("https://jtoye.uk/errors/validation");
        assertThat(problem.path("errors").has(field)).as(text).isTrue();
    }

    private String shopName(UUID shopId) {
        return inTenant(() -> jdbcTemplate.queryForObject("SELECT name FROM shops WHERE id = ?", String.class, shopId));
    }

    private List<Long> feeAndThreshold(UUID shopId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT delivery_fee_pennies, free_delivery_threshold_pennies FROM shops WHERE id = ?",
                (rs, n) -> java.util.Arrays.asList(rs.getObject(1, Long.class), rs.getObject(2, Long.class)), shopId));
    }

    private List<Long> totalAndFee(String email) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT total_amount_pennies, delivery_fee_pennies FROM orders WHERE tenant_id = ? AND customer_email = ?",
                (rs, n) -> List.of(rs.getLong(1), rs.getLong(2)), TENANT_ID, email));
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
