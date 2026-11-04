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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Persona close-out 2026-11-04 (#846, #813, #845): a signed-in customer reviews a COMPLETED order and
 * orders it again, WITHOUT their email in any URL.
 *
 * <p>The old review endpoint ({@code POST /public/shops/{slug}/reviews?email=}) takes the email as an
 * unauthenticated query parameter, which is why no customer surface ever called it. The new ones take the
 * customer's identity from the verified customers-realm token ({@code X-Customer-Token}, through the
 * same {@link CustomerJwtVerifier} as {@code /orders/mine}) and address the order by its number:
 * <ul>
 *   <li>{@code POST /public/shops/{slug}/orders/{orderNumber}/review} writes the review;</li>
 *   <li>{@code GET  /public/shops/{slug}/orders/{orderNumber}/reorder} returns the order's lines, for "Order again".</li>
 * </ul>
 * Every arm goes through the real endpoint and reads the {@code reviews} table back by SQL. The tenant
 * is pinned from the published shop slug (SEC-01) and the orders table is FORCE RLS: the endpoint reads
 * only what its pinned tenant allows. Another customer's order is a 404, never a 400 or 403 (D-05, the
 * VanishedRowStatus contract), so the order number cannot be used to learn whether an order exists.
 *
 * <p>Real Postgres (Testcontainers). The verifier is stubbed because the IT has no Keycloak
 * (CustomerJwtVerifierTest covers the real one): {@code OWNER_TOKEN} proves {@code OWNER_EMAIL},
 * {@code STRANGER_TOKEN} proves another address, any other token is refused with the verifier's 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CustomerOrderReviewAndReorderIntegrationTest {

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

    @MockitoBean CustomerJwtVerifier customerJwtVerifier;

    private static final String OWNER_TOKEN = "owner-customer-token";
    private static final String STRANGER_TOKEN = "stranger-customer-token";
    private static final String OWNER_EMAIL = "Grace.Persona14@Example.com";

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or email. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000004604");
    private static final String INVALID_ARGUMENT_TYPE = "https://jtoye.uk/errors/invalid-argument";
    private static final String MISMATCH_TYPE = "https://jtoye.uk/errors/review-order-shop-mismatch";

    private String slugA;
    private String slugB;
    private UUID shopA;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "46-04 Customer Review/Reorder Tenant");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        slugA = "shop-4604-a-" + suffix;
        slugB = "shop-4604-b-" + suffix;
        shopA = seedShop(slugA, "Shop A " + suffix);
        seedShop(slugB, "Shop B " + suffix);
        // anyString() does not match null, and an absent X-Customer-Token IS a null: the real bean refuses
        // it with the same 401 (CustomerJwtVerifierTest), so the stub must too, or the no-token arms would
        // meet an unstubbed null instead of the refusal under test.
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required"))
                .when(customerJwtVerifier).verifiedEmail(anyString());
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required"))
                .when(customerJwtVerifier).verifiedEmail(isNull());
        doReturn(OWNER_EMAIL).when(customerJwtVerifier).verifiedEmail(OWNER_TOKEN);
        doReturn("someone.else@example.com").when(customerJwtVerifier).verifiedEmail(STRANGER_TOKEN);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ============================== the review ==============================

    @Test
    @DisplayName("#846: the signed-in buyer reviews their COMPLETED order: 201, the row is the shop's, the URL carries no email")
    void ownerReviewsCompletedOrder() throws Exception {
        UUID order = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        String number = orderNumber(order);

        String path = "/api/v1/public/shops/" + slugA + "/orders/" + number + "/review";
        MvcResult created = review(slugA, number, OWNER_TOKEN, 5, 4, "Lovely jollof");

        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(201);
        // The point of #846: nothing identifying is in the URL, and no query string exists at all.
        assertThat(path).doesNotContain("@").doesNotContain("?").doesNotContain("email");
        Map<String, Object> row = reviewRow(order);
        assertThat(row.get("shop_id")).isEqualTo(shopA);
        assertThat(row.get("tenant_id")).isEqualTo(TENANT_ID);
        assertThat(row.get("customer_email")).isEqualTo(OWNER_EMAIL);
        assertThat(row.get("food_rating")).isEqualTo(5);
        // #813: what the response and the public list publish is first name + initial, never the stored full name.
        JsonNode body = jsonMapper.readTree(created.getResponse().getContentAsString());
        assertThat(body.path("customerName").asString()).as(text(created)).isEqualTo("Grace P.");
        assertThat(created.getResponse().getContentAsString()).doesNotContain("Persona14").doesNotContain("@");
    }

    @Test
    @DisplayName("no token: 401 and nothing written; a token that proves another address: 404 and nothing written")
    void identityComesOnlyFromTheToken() throws Exception {
        UUID order = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        String number = orderNumber(order);

        MvcResult noToken = review(slugA, number, null, 5, null, null);
        assertThat(noToken.getResponse().getStatus()).as(text(noToken)).isEqualTo(401);

        MvcResult forged = review(slugA, number, "not-a-real-token", 5, null, null);
        assertThat(forged.getResponse().getStatus()).as(text(forged)).isEqualTo(401);

        MvcResult stranger = review(slugA, number, STRANGER_TOKEN, 5, null, null);
        assertThat(stranger.getResponse().getStatus()).as("another customer's order is a 404, never a 400/403: " + text(stranger)).isEqualTo(404);
        assertThat(countReviews(order)).as("nothing was written by any refused attempt").isZero();
    }

    @Test
    @DisplayName("an order number that does not exist is the same 404 as someone else's (no existence oracle)")
    void unknownOrderIsTheSame404() throws Exception {
        MvcResult unknown = review(slugA, "ORD-DOES-NOT-EXIST", OWNER_TOKEN, 5, null, null);
        // A typed application 404 (the not-found problem), not the container's bare 404 for an unmapped path:
        // on a tree without the endpoint this arm would otherwise pass for the wrong reason.
        assertProblem(unknown, 404, "https://jtoye.uk/errors/not-found");

        UUID someoneElses = seedOrder(shopA, "owner-of-another@example.com", "COMPLETED", "ORD-4604-" + shortId());
        MvcResult notYours = review(slugA, orderNumber(someoneElses), OWNER_TOKEN, 5, null, null);
        assertProblem(notYours, 404, "https://jtoye.uk/errors/not-found");
        assertThat(jsonMapper.readTree(notYours.getResponse().getContentAsString()).path("detail").asString())
                .as("the same words for 'not yours' and 'does not exist'")
                .isEqualTo(jsonMapper.readTree(unknown.getResponse().getContentAsString()).path("detail").asString());
    }

    @Test
    @DisplayName("an order that is not COMPLETED is 400 invalid-argument, and a second review of one that is, is 400 'already reviewed' (one per order)")
    void completedOnlyAndOncePerOrder() throws Exception {
        UUID pending = seedOrder(shopA, OWNER_EMAIL, "PENDING", "ORD-4604-" + shortId());
        MvcResult refused = review(slugA, orderNumber(pending), OWNER_TOKEN, 5, null, null);
        assertProblem(refused, 400, INVALID_ARGUMENT_TYPE);
        assertThat(jsonMapper.readTree(refused.getResponse().getContentAsString()).path("detail").asString())
                .isEqualTo("Can only review completed orders");
        assertThat(countReviews(pending)).isZero();

        UUID done = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        assertThat(review(slugA, orderNumber(done), OWNER_TOKEN, 4, null, null).getResponse().getStatus()).isEqualTo(201);
        MvcResult again = review(slugA, orderNumber(done), OWNER_TOKEN, 1, null, "changed my mind");
        assertProblem(again, 400, INVALID_ARGUMENT_TYPE);
        assertThat(countReviews(done)).as("the second attempt wrote nothing: a replay never creates a second row").isEqualTo(1);
    }

    @Test
    @DisplayName("the order must have been placed at THIS shop (UXT-005 holds on the new endpoint): 400 review-order-shop-mismatch")
    void orderMustBeAtThisShop() throws Exception {
        UUID atA = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        MvcResult refused = review(slugB, orderNumber(atA), OWNER_TOKEN, 5, null, null);
        assertProblem(refused, 400, MISMATCH_TYPE);
        assertThat(countReviews(atA)).isZero();
    }

    @Test
    @DisplayName("a rating outside 1..5 is refused 400 before anything is written")
    void ratingIsBounded() throws Exception {
        UUID order = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        assertThat(review(slugA, orderNumber(order), OWNER_TOKEN, 0, null, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(review(slugA, orderNumber(order), OWNER_TOKEN, 6, null, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(review(slugA, orderNumber(order), OWNER_TOKEN, null, null, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(countReviews(order)).isZero();
    }

    // ============================== order again ==============================

    @Test
    @DisplayName("#845: the owner reads their order's lines (product, name, quantity, unit price), no-store; a stranger and a forged token get 404 / 401")
    void ownerReadsLinesForReorder() throws Exception {
        UUID order = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        UUID jollof = seedProduct(shopA, "Jollof Rice", 1000);
        UUID moin = seedProduct(shopA, "Moin Moin", 700);
        seedLine(order, jollof, "Jollof Rice", 2, 1000);
        seedLine(order, moin, "Moin Moin", 1, 700);
        String number = orderNumber(order);

        MvcResult ok = reorder(slugA, number, OWNER_TOKEN);
        assertThat(ok.getResponse().getStatus()).as(text(ok)).isEqualTo(200);
        assertThat(ok.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = jsonMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(body.path("orderNumber").asString()).isEqualTo(number);
        assertThat(body.path("shopSlug").asString()).isEqualTo(slugA);
        JsonNode lines = body.path("lines");
        assertThat(lines.size()).as(text(ok)).isEqualTo(2);
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        lines.forEach(l -> byName.put(l.path("productName").asString(), l));
        assertThat(byName.get("Jollof Rice").path("productId").asString()).isEqualTo(jollof.toString());
        assertThat(byName.get("Jollof Rice").path("quantity").asInt()).isEqualTo(2);
        assertThat(byName.get("Jollof Rice").path("unitPricePennies").asLong()).isEqualTo(1000L);
        // Nothing about the person comes back: no email, no name, no address.
        assertThat(ok.getResponse().getContentAsString()).doesNotContain("@").doesNotContain("Persona14");

        assertThat(reorder(slugA, number, STRANGER_TOKEN).getResponse().getStatus()).isEqualTo(404);
        assertThat(reorder(slugA, number, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(reorder(slugB, number, OWNER_TOKEN).getResponse().getStatus())
                .as("an order placed at shop A is not readable through shop B's slug").isEqualTo(404);
    }

    @Test
    @DisplayName("#845: a delivery order's own address and phone come back with its lines (a fresh browser can pre-fill); a collection order, and an erased one, return none")
    void deliveryDetailsComeBackOnlyForADeliveryOrderThatHasAnAddress() throws Exception {
        UUID delivered = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        seedLine(delivered, seedProduct(shopA, "Jollof Rice", 1000), "Jollof Rice", 1, 1000);
        setDelivery(delivered, "DELIVERY", "12 Coldharbour Lane", "Flat 3", "London", "SW9 8LF", "07700 900001");

        JsonNode body = jsonMapper.readTree(reorder(slugA, orderNumber(delivered), OWNER_TOKEN).getResponse().getContentAsString());
        JsonNode delivery = body.path("delivery");
        assertThat(delivery.path("addressLine1").asString()).isEqualTo("12 Coldharbour Lane");
        assertThat(delivery.path("addressLine2").asString()).isEqualTo("Flat 3");
        assertThat(delivery.path("addressCity").asString()).isEqualTo("London");
        assertThat(delivery.path("addressPostcode").asString()).isEqualTo("SW9 8LF");
        assertThat(delivery.path("phone").asString()).isEqualTo("07700 900001");

        UUID collected = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        setDelivery(collected, "COLLECTION", "12 Coldharbour Lane", null, "London", "SW9 8LF", "07700 900001");
        MvcResult collectedRes = reorder(slugA, orderNumber(collected), OWNER_TOKEN);
        assertThat(jsonMapper.readTree(collectedRes.getResponse().getContentAsString()).path("delivery").isNull())
                .as("a collection order returns no delivery details even if an address column holds something: " + text(collectedRes))
                .isTrue();

        UUID erased = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        setDelivery(erased, "DELIVERY", null, null, null, null, null);
        MvcResult erasedRes = reorder(slugA, orderNumber(erased), OWNER_TOKEN);
        assertThat(jsonMapper.readTree(erasedRes.getResponse().getContentAsString()).path("delivery").isNull())
                .as("an order whose address was erased returns none, never a half-filled one: " + text(erasedRes))
                .isTrue();
    }

    @Test
    @DisplayName("#845: the signed-in order history now says which shop each order is from (shopSlug), so a past order can link to it")
    void historyCarriesTheShopSlug() throws Exception {
        UUID order = seedOrder(shopA, OWNER_EMAIL, "COMPLETED", "ORD-4604-" + shortId());
        MvcResult mine = mockMvc.perform(get("/api/v1/public/orders/mine").header("X-Customer-Token", OWNER_TOKEN)).andReturn();
        assertThat(mine.getResponse().getStatus()).as(text(mine)).isEqualTo(200);
        JsonNode content = jsonMapper.readTree(mine.getResponse().getContentAsString()).path("content");
        JsonNode mineRow = null;
        for (JsonNode n : content) {
            if (orderNumber(order).equals(n.path("orderNumber").asString())) mineRow = n;
        }
        assertThat(mineRow).as("the order is in the history: " + text(mine)).isNotNull();
        assertThat(mineRow.path("shopSlug").asString()).isEqualTo(slugA);
    }

    // ============================== HTTP helpers ==============================

    private MvcResult review(String slug, String number, String token, Integer food, Integer delivery, String comment) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (food != null) body.put("foodRating", food);
        if (delivery != null) body.put("deliveryRating", delivery);
        if (comment != null) body.put("comment", comment);
        var req = post("/api/v1/public/shops/" + slug + "/orders/" + number + "/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (token != null) req = req.header("X-Customer-Token", token);
        return mockMvc.perform(req).andReturn();
    }

    private MvcResult reorder(String slug, String number, String token) throws Exception {
        var req = get("/api/v1/public/shops/" + slug + "/orders/" + number + "/reorder");
        if (token != null) req = req.header("X-Customer-Token", token);
        return mockMvc.perform(req).andReturn();
    }

    private void assertProblem(MvcResult result, int status, String type) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(status);
        assertThat(jsonMapper.readTree(result.getResponse().getContentAsString()).path("type").asString()).as(text(result)).isEqualTo(type);
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    // ============================== SQL helpers (superuser bootstrap role; tenant pinned) ==============================

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedShop(String slug, String name) {
        return inTenant(() -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, 'Test Address', true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, TENANT_ID, name, slug);
            return id;
        });
    }

    private UUID seedOrder(UUID shopId, String email, String status, String number) {
        return inTenant(() -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_email, customer_name, "
                            + "total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, vat_amount_pennies) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'Grace Persona14', 2700, 0, 2700, 'ZERO', 0)",
                    id, TENANT_ID, shopId, number, status, email);
            return id;
        });
    }

    private UUID seedProduct(UUID shopId, String title, long pricePennies) {
        return inTenant(() -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, "
                            + "price_pennies, display_order, available, featured, shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, '', 0, ?, 0, true, false, ?, 100, 'STANDARD', 0)",
                    id, TENANT_ID, "SKU-4604-" + id, title, pricePennies, shopId);
            return id;
        });
    }

    private void seedLine(UUID order, UUID product, String name, int quantity, long unitPennies) {
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO order_items (tenant_id, order_id, product_id, quantity, unit_price_pennies, "
                        + "total_price_pennies, product_name) VALUES (?, ?, ?, ?, ?, ?, ?)",
                TENANT_ID, order, product, quantity, unitPennies, unitPennies * quantity, name));
    }

    private void setDelivery(UUID order, String fulfilment, String line1, String line2, String city, String postcode, String phone) {
        inTenant(() -> jdbcTemplate.update(
                "UPDATE orders SET fulfilment_type = ?, address_line1 = ?, address_line2 = ?, address_city = ?, "
                        + "address_postcode = ?, customer_phone = ? WHERE id = ?",
                fulfilment, line1, line2, city, postcode, phone, order));
    }

    private String orderNumber(UUID order) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT order_number FROM orders WHERE id = ?", String.class, order)));
    }

    private long countReviews(UUID order) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reviews WHERE tenant_id = ? AND order_id = ?", Long.class, TENANT_ID, order)));
    }

    private Map<String, Object> reviewRow(UUID order) {
        return inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT shop_id, tenant_id, customer_email, food_rating FROM reviews WHERE tenant_id = ? AND order_id = ?",
                TENANT_ID, order));
    }
}
