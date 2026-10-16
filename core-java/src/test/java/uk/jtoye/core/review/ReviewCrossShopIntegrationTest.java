package uk.jtoye.core.review;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-26 (D-21, RWO-005 / UXT-005 P0): a review is accepted only when the cited order was placed
 * at the shop being reviewed.
 *
 * <p>The persona run bought once from shop A and then left a verified review on shop B of the same
 * tenant (UXT-005, #781): {@code ReviewService.createReview} checked the reviewer's email, the
 * COMPLETED status and the one-per-order rule, but never {@code order.shopId == shop.id}, and RLS is
 * the tenant wall, not the shop wall. Every arm here goes through the real endpoint
 * ({@code POST /api/v1/public/shops/{slug}/reviews?email=...}) and reads the {@code reviews} table
 * back by SQL. The existing gates (own email, COMPLETED, one per order, unknown order) are asserted
 * unchanged beside the new one (goods P2-REG-18, P2-KYL-P4). Real Postgres (Testcontainers): the
 * V35 {@code reviews_tenant_write} policy and the V27 {@code UNIQUE(order_id)} are real DDL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ReviewCrossShopIntegrationTest {

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

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or email. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000003727");
    private static final String MISMATCH_TYPE = "https://jtoye.uk/errors/review-order-shop-mismatch";
    private static final String MISMATCH_CODE = "REVIEW_ORDER_SHOP_MISMATCH";
    private static final String INVALID_ARGUMENT_TYPE = "https://jtoye.uk/errors/invalid-argument";

    private String slugA;
    private String slugB;
    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-26 Cross-Shop Review Tenant");
        // Two PUBLISHED shops of ONE tenant: the UXT-005 shape. Fresh per test so one arm's review
        // can never satisfy or block another's one-per-order rule.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        slugA = "shop-3726-a-" + suffix;
        slugB = "shop-3726-b-" + suffix;
        shopA = seedShop(slugA, "Shop A " + suffix);
        shopB = seedShop(slugB, "Shop B " + suffix);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-005: a buyer of shop A's COMPLETED order reviewing shop B (same tenant) is refused 400 review-order-shop-mismatch and no review is written")
    void completedOrderAtShopA_cannotReviewShopB() throws Exception {
        String email = "alice-" + UUID.randomUUID() + "@example.com";
        UUID orderAtA = seedOrder(shopA, email, "COMPLETED");

        MvcResult refused = postReview(slugB, email, orderAtA);
        JsonNode problem = assertProblem(refused, 400, MISMATCH_TYPE);
        String text = responseText(refused);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(MISMATCH_CODE);
        assertThat(problem.path("detail").asString()).as(text)
                .isEqualTo("This order was not placed at this shop, so it can't be used to review it.");
        assertThat(countReviewsForOrder(orderAtA)).as("nothing was written for the cross-shop attempt").isZero();
        assertThat(countReviewsForShop(shopB)).as("shop B gained no review").isZero();
    }

    @Test
    @DisplayName("D-21 control: the same buyer reviewing shop A with that order is 201 and the review is shop A's")
    void completedOrderAtShopA_reviewsShopA() throws Exception {
        String email = "alice-" + UUID.randomUUID() + "@example.com";
        UUID orderAtA = seedOrder(shopA, email, "COMPLETED");

        MvcResult created = postReview(slugA, email, orderAtA);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        Map<String, Object> row = reviewRow(orderAtA);
        assertThat(row.get("shop_id")).isEqualTo(shopA);
        assertThat(row.get("tenant_id")).isEqualTo(TENANT_ID);
        assertThat(row.get("customer_email")).isEqualTo(email);
    }

    @Test
    @DisplayName("D-21 control: a cross-shop attempt does not spend the order's one review — the buyer can still review shop A afterwards")
    void crossShopRefusal_leavesTheOrderReviewable() throws Exception {
        String email = "alice-" + UUID.randomUUID() + "@example.com";
        UUID orderAtA = seedOrder(shopA, email, "COMPLETED");

        assertProblem(postReview(slugB, email, orderAtA), 400, MISMATCH_TYPE);
        MvcResult created = postReview(slugA, email, orderAtA);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        assertThat(countReviewsForOrder(orderAtA)).isEqualTo(1);
    }

    // ---- The existing gates, unchanged (P2-REG-18, P2-KYL-P4) ----

    @Test
    @DisplayName("unchanged: another customer's order is refused 400 invalid-argument (own orders only)")
    void otherCustomersOrder_isStillRefused() throws Exception {
        UUID orderAtA = seedOrder(shopA, "owner-" + UUID.randomUUID() + "@example.com", "COMPLETED");

        MvcResult refused = postReview(slugA, "stranger-" + UUID.randomUUID() + "@example.com", orderAtA);
        JsonNode problem = assertProblem(refused, 400, INVALID_ARGUMENT_TYPE);
        assertThat(problem.path("detail").asString()).isEqualTo("You can only review your own orders");
        assertThat(countReviewsForOrder(orderAtA)).isZero();
    }

    @Test
    @DisplayName("unchanged: an order that is not COMPLETED is refused 400 invalid-argument, at its own shop")
    void nonCompletedOrder_isStillRefused() throws Exception {
        String email = "alice-" + UUID.randomUUID() + "@example.com";
        UUID pendingAtA = seedOrder(shopA, email, "PENDING");

        MvcResult refused = postReview(slugA, email, pendingAtA);
        JsonNode problem = assertProblem(refused, 400, INVALID_ARGUMENT_TYPE);
        assertThat(problem.path("detail").asString()).isEqualTo("Can only review completed orders");
        assertThat(countReviewsForOrder(pendingAtA)).isZero();
    }

    @Test
    @DisplayName("unchanged: a second review of the same order is refused 400 invalid-argument (one per order)")
    void duplicateReview_isStillRefused() throws Exception {
        String email = "alice-" + UUID.randomUUID() + "@example.com";
        UUID orderAtA = seedOrder(shopA, email, "COMPLETED");
        assertThat(postReview(slugA, email, orderAtA).getResponse().getStatus()).isEqualTo(201);

        MvcResult refused = postReview(slugA, email, orderAtA);
        JsonNode problem = assertProblem(refused, 400, INVALID_ARGUMENT_TYPE);
        assertThat(problem.path("detail").asString()).isEqualTo("You have already reviewed this order");
        assertThat(countReviewsForOrder(orderAtA)).isEqualTo(1);
    }

    @Test
    @DisplayName("unchanged: an order that does not exist is 404 not-found (never ordered)")
    void unknownOrder_isStillNotFound() throws Exception {
        MvcResult refused = postReview(slugA, "nobody-" + UUID.randomUUID() + "@example.com", UUID.randomUUID());
        assertProblem(refused, 404, "https://jtoye.uk/errors/not-found");
    }

    // ---- HTTP helpers ----

    private MvcResult postReview(String slug, String email, UUID orderId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("foodRating", 5);
        body.put("deliveryRating", 4);
        body.put("comment", "Lovely");
        return mockMvc.perform(post("/api/v1/public/shops/" + slug + "/reviews")
                        .param("email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode assertProblem(MvcResult result, int status, String type) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        return problem;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    // ---- SQL helpers (superuser bootstrap role; tenant pinned for correctness) ----

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

    /** The ReviewsRlsPolicyIntegrationTest order shape: every NOT NULL money column supplied. */
    private UUID seedOrder(UUID shopId, String email, String status) {
        return inTenant(() -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_email, customer_name, "
                            + "total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, vat_amount_pennies) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1000, 0, 1000, 'ZERO', 0)",
                    id, TENANT_ID, shopId, "ORD-" + id.toString().substring(0, 8), status, email, "Test " + email);
            return id;
        });
    }

    private long countReviewsForOrder(UUID orderId) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reviews WHERE tenant_id = ? AND order_id = ?", Long.class, TENANT_ID, orderId)));
    }

    private long countReviewsForShop(UUID shopId) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reviews WHERE tenant_id = ? AND shop_id = ?", Long.class, TENANT_ID, shopId)));
    }

    private Map<String, Object> reviewRow(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT shop_id, tenant_id, customer_email FROM reviews WHERE tenant_id = ? AND order_id = ?",
                TENANT_ID, orderId));
    }
}
