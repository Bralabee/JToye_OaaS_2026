package uk.jtoye.core.order;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * #460 / UXT-030: checkout used to accept a DELIVERY order to any UK postcode. A shop now declares how far it
 * delivers ({@code deliveryRadiusKm}), and BOTH order writers (the storefront checkout and
 * {@code POST /api/v1/orders}, which the dashboard, the REST API and MCP share) refuse a delivery beyond it with a
 * typed 422.
 *
 * <p>The fixture is two distant places: a shop in south-east London and customers in Edinburgh (about 530 km away),
 * Croydon (about 10.7 km, just past a 10 km radius) and a few hundred metres away. Postcode centroids are seeded
 * into the real {@code postcode_centroid} table, so the distance is the one production computes.
 *
 * <p>What stays as it was, each asserted: a COLLECTION order to anywhere; a shop with no radius (every existing
 * shop); and a shop with a radius but no stored coordinate (it cannot be measured from, so it is not blocked).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class DeliveryRadiusIntegrationTest {

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

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000046001");
    private static final String OUT_OF_RANGE = "https://jtoye.uk/errors/delivery-out-of-range";
    private static final String UNRECOGNISED = "https://jtoye.uk/errors/delivery-postcode-unrecognised";

    // Shop in SE15; the postcode fixtures are real places at their real distances from it.
    private static final double SHOP_LAT = 51.4700;
    private static final double SHOP_LON = -0.0700;
    private static final String NEAR = "SE15 5EB";       // ~0.5 km
    private static final String CROYDON = "CR0 1RN";     // ~10.7 km
    private static final String EDINBURGH = "EH1 1YZ";   // ~530 km

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "460 Delivery Radius Tenant");
        centroid("SE155EB", 51.4750, -0.0650);
        centroid("CR01RN", 51.3762, -0.0982);
        centroid("EH11YZ", 55.9505, -3.1883);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void centroid(String key, double lat, double lon) {
        jdbcTemplate.update("INSERT INTO postcode_centroid (postcode, latitude, longitude) VALUES (?, ?, ?) "
                + "ON CONFLICT (postcode) DO UPDATE SET latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude",
                key, lat, lon);
    }

    // ------------------------------------------------------------------ the rule, on both writers

    @Test
    @DisplayName("#460: a 10 km radius refuses delivery to Edinburgh and just-past-the-edge Croydon with a typed 422, on BOTH writers, and writes nothing")
    void deliveryBeyondTheRadiusIsRefusedTypedOnBothWriters() throws Exception {
        Fixture f = seedShop("radius", true);
        setRadius(f, 10);

        for (String postcode : List.of(EDINBURGH, CROYDON)) {
            String sf = "sf-" + UUID.randomUUID() + "@example.com";
            JsonNode storefront = assertProblem(perform(post(storefrontUrl(f)),
                    storefrontBody(sf, "DELIVERY", postcode, f)), 422, OUT_OF_RANGE, "DELIVERY_OUT_OF_RANGE");
            assertThat(storefront.path("deliveryRadiusKm").asDouble()).isEqualTo(10.0);
            assertThat(storefront.path("distanceKm").asDouble()).as("the measured distance, to 0.1 km")
                    .isGreaterThan(10.0);
            assertThat(storefront.path("detail").asString()).contains("10 km");
            assertThat(countOrders(sf)).as("the refused storefront order was not written").isZero();

            String vendor = "vendor-" + UUID.randomUUID() + "@example.com";
            assertProblem(performVendor(vendorBody(f, vendor, "DELIVERY", postcode)), 422, OUT_OF_RANGE,
                    "DELIVERY_OUT_OF_RANGE");
            assertThat(countOrders(vendor)).as("the refused vendor order was not written").isZero();
        }
    }

    @Test
    @DisplayName("#460: delivery inside the radius is accepted on both writers (control), and so is collection to Edinburgh")
    void deliveryInsideTheRadiusAndCollectionAnywhereAreAccepted() throws Exception {
        Fixture f = seedShop("inside", true);
        setRadius(f, 10);

        String near = "near-" + UUID.randomUUID() + "@example.com";
        assertThat(perform(post(storefrontUrl(f)), storefrontBody(near, "DELIVERY", NEAR, f))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(performVendor(vendorBody(f, near, "DELIVERY", NEAR)).getResponse().getStatus()).isEqualTo(201);
        assertThat(countOrders(near)).isEqualTo(2);

        String far = "far-" + UUID.randomUUID() + "@example.com";
        assertThat(perform(post(storefrontUrl(f)), storefrontBody(far, "COLLECTION", EDINBURGH, f))
                .getResponse().getStatus()).as("collection has no delivery radius").isEqualTo(201);
        assertThat(performVendor(vendorBody(f, far, "COLLECTION", EDINBURGH)).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("#460: a shop with no radius keeps today's behaviour: delivery to Edinburgh is accepted")
    void shopWithoutARadiusDeliversAnywhere() throws Exception {
        Fixture f = seedShop("no-radius", true);

        String email = "anywhere-" + UUID.randomUUID() + "@example.com";
        assertThat(perform(post(storefrontUrl(f)), storefrontBody(email, "DELIVERY", EDINBURGH, f))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(performVendor(vendorBody(f, email, "DELIVERY", EDINBURGH)).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("#460: a shop with a radius but no stored coordinate cannot be measured from, so it is not blocked")
    void shopWithoutACoordinateIsNotBlocked() throws Exception {
        Fixture f = seedShop("no-coordinate", false);
        setRadius(f, 10);

        String email = "nocoord-" + UUID.randomUUID() + "@example.com";
        assertThat(perform(post(storefrontUrl(f)), storefrontBody(email, "DELIVERY", EDINBURGH, f))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("#460: with a radius set, a postcode the dataset does not know is refused 422 delivery-postcode-unrecognised, not guessed")
    void unknownPostcodeIsRefusedWhenARadiusIsSet() throws Exception {
        Fixture f = seedShop("unknown", true);
        setRadius(f, 10);

        String email = "unknown-" + UUID.randomUUID() + "@example.com";
        assertProblem(perform(post(storefrontUrl(f)), storefrontBody(email, "DELIVERY", "ZZ99 9ZZ", f)), 422,
                UNRECOGNISED, "DELIVERY_POSTCODE_UNRECOGNISED");
        assertProblem(performVendor(vendorBody(f, email, "DELIVERY", "ZZ99 9ZZ")), 422, UNRECOGNISED,
                "DELIVERY_POSTCODE_UNRECOGNISED");
        assertThat(countOrders(email)).isZero();
    }

    // ------------------------------------------------------------------ the shop API

    @Test
    @DisplayName("#460: the vendor sets the radius through the shop API (echoed, published to the storefront); absent keeps it, explicit null clears it; out-of-range values are a 400")
    void radiusIsSetKeptClearedAndValidated() throws Exception {
        Fixture f = seedShop("api", true);

        MvcResult set = putShop(f, Map.of("deliveryRadiusKm", 7.5));
        assertThat(set.getResponse().getStatus()).as(text(set)).isEqualTo(200);
        assertThat(jsonMapper.readTree(set.getResponse().getContentAsString()).path("deliveryRadiusKm").asDouble())
                .as(text(set)).isEqualTo(7.5);
        assertThat(publicShop(f).path("deliveryRadiusKm").asDouble()).as("the storefront can state it").isEqualTo(7.5);

        assertThat(putShop(f, Map.of()).getResponse().getStatus()).isEqualTo(200);
        assertThat(radius(f)).as("absent keeps").isEqualTo(7.5);

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("deliveryRadiusKm", null);
        assertThat(putShop(f, clear).getResponse().getStatus()).isEqualTo(200);
        assertThat(radius(f)).as("explicit null clears").isNull();

        for (double bad : new double[]{0, -3, 100.5}) {
            MvcResult refused = putShop(f, Map.of("deliveryRadiusKm", bad));
            assertThat(refused.getResponse().getStatus()).as("radius " + bad + ": " + text(refused)).isEqualTo(400);
            assertThat(jsonMapper.readTree(refused.getResponse().getContentAsString()).path("errors")
                    .has("deliveryRadiusKm")).as(text(refused)).isTrue();
        }
    }

    // ------------------------------------------------------------------ fixture and helpers

    private record Fixture(UUID shopId, String slug, UUID productId) { }

    private Fixture seedShop(String label, boolean withCoordinate) {
        return inTenant(() -> {
            UUID shopId = UUID.randomUUID();
            String slug = "shop-460-" + label + "-" + shopId.toString().substring(0, 8);
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source, latitude, longitude) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN', ?, ?)",
                    shopId, TENANT_ID, "460 " + label, slug,
                    withCoordinate ? SHOP_LAT : null, withCoordinate ? SHOP_LON : null);
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, TENANT_ID, "SKU-460-" + productId, "Jollof Rice", "see label", 2500L, shopId);
            return new Fixture(shopId, slug, productId);
        });
    }

    /** Through the shop API, as the dashboard does; on code without the field this is silently ignored. */
    private void setRadius(Fixture f, double km) throws Exception {
        MvcResult put = putShop(f, Map.of("deliveryRadiusKm", km));
        assertThat(put.getResponse().getStatus()).as(text(put)).isEqualTo(200);
        assertThat(jsonMapper.readTree(put.getResponse().getContentAsString()).path("deliveryRadiusKm").asDouble())
                .as("the radius was stored and echoed: " + text(put)).isEqualTo(km);
    }

    private Double radius(Fixture f) throws Exception {
        MvcResult get = mockMvc.perform(get("/api/v1/shops/" + f.shopId).with(vendorJwt())).andReturn();
        JsonNode node = jsonMapper.readTree(get.getResponse().getContentAsString()).path("deliveryRadiusKm");
        return node.isNull() || node.isMissingNode() ? null : node.asDouble();
    }

    private MvcResult putShop(Fixture f, Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT name FROM shops WHERE id = ?", String.class, f.shopId)));
        body.put("slug", f.slug);
        body.putAll(fields);
        return perform(put("/api/v1/shops/" + f.shopId).with(vendorJwt()), body);
    }

    private JsonNode publicShop(Fixture f) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/shops/" + f.slug)).andReturn();
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
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

    private static Map<String, Object> line(UUID productId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", 1);
        return item;
    }

    private Map<String, Object> vendorBody(Fixture f, String email, String type, String postcode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", f.shopId);
        body.put("customerName", "Radius Vendor Customer");
        body.put("customerEmail", email);
        body.put("fulfilmentType", type);
        if ("DELIVERY".equals(type)) {
            body.put("addressLine1", "1 High Street");
            body.put("addressCity", "Somewhere");
            body.put("addressPostcode", postcode);
        }
        body.put("items", List.of(line(f.productId)));
        return body;
    }

    private Map<String, Object> storefrontBody(String email, String type, String postcode, Fixture f) {
        List<Map<String, Object>> items = List.of(line(f.productId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Radius Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "+4477009" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000)));
        body.put("fulfilmentType", type);
        if ("DELIVERY".equals(type)) {
            body.put("addressLine1", "1 High Street");
            body.put("addressCity", "Somewhere");
            body.put("addressPostcode", postcode);
        }
        body.put("items", items);
        body.put("acknowledgedAllergenMask",
                GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, List.of(f.productId)));
        body.put("expectedTotalPennies",
                GuestOrderAcknowledgements.expectCurrentPrices(jdbcTemplate, TENANT_ID, type, items));
        return body;
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String text = text(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(code);
        return problem;
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long countOrders(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, TENANT_ID, email)));
    }
}
