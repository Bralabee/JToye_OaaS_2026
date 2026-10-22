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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 30-05, issue #958: an order that is waiting for the customer's money cannot be submitted,
 * accepted or prepared.
 *
 * <p>The staging swarm submitted and confirmed unpaid card DRAFTs from the dashboard, and the customer was
 * emailed "Received" and "Confirmed" for orders nobody had paid for. Those emails are sent from the
 * ORDER_STATE_CHANGED outbox rows that {@code OrderService.transitionOrder} writes, and the only payment guard
 * sat on CONFIRMED -> PREPARING. The gate now refuses SUBMIT, CONFIRM and START_PREP for an order that
 * {@code isAwaitingUpfrontOrDepositPayment} says is unpaid, before anything is mutated, with the typed
 * RFC 7807 problem {@code https://jtoye.uk/errors/awaiting-payment} (HTTP 409).
 *
 * <p>D-01: a vendor, REST or MCP order (paymentStatus NONE, no payment reference) is NOT payment-gated in
 * Phase 30 and keeps its flow exactly; the regression arms here go through the same API to prove it.
 *
 * <p>Real filter chain, Testcontainers Postgres. Orders are created through {@code POST /api/v1/orders}
 * (DRAFT, paymentStatus NONE); the card state a storefront checkout leaves behind (paymentStatus PENDING
 * with a {@code pi_} reference) is written by SQL as the test superuser. The outbox, the stored status and
 * the product's stock are read back by SQL, never from the response alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderAwaitingPaymentGateIntegrationTest {

    static final String AWAITING_PAYMENT_TYPE = "https://jtoye.uk/errors/awaiting-payment";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    private UUID tenant;
    private UUID shop;
    private UUID product;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenant, "30-05 Tenant " + tenant);
        shop = seedShop();
        product = seedProduct(shop, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- SUBMIT (DRAFT -> PENDING) ----

    @Test
    @DisplayName("#958: submitting an unpaid card DRAFT is refused 409 awaiting-payment; it stays DRAFT and announces nothing")
    void unpaidCardDraftSubmitIsRefused() throws Exception {
        UUID orderId = draftOrder();
        markCardAwaitingPayment(orderId, "PENDING");

        MvcResult result = transition(orderId, "submit");

        assertAwaitingPaymentProblem(result, orderId);
        assertThat(statusOf(orderId)).as("a refusal changes nothing").isEqualTo("DRAFT");
        assertThat(paymentStatusOf(orderId)).isEqualTo("PENDING");
        assertThat(allEventsFor(orderId))
                .as("no outbox row, so no 'Received' email can be sent for an unpaid order")
                .isZero();
    }

    @Test
    @DisplayName("D-01 regression: a vendor DRAFT (paymentStatus NONE, no reference) still submits to PENDING with one state change")
    void vendorDraftSubmitStillMoves() throws Exception {
        UUID orderId = draftOrder();
        assertThat(paymentStatusOf(orderId)).as("precondition: a vendor order is NONE").isEqualTo("NONE");
        assertThat(paymentReferenceOf(orderId)).as("precondition: no payment reference").isNull();

        MvcResult result = transition(orderId, "submit");

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(json(result).path("status").asString()).isEqualTo("PENDING");
        assertThat(statusOf(orderId)).isEqualTo("PENDING");
        assertThat(pendingEventsFor(orderId)).as("the DRAFT -> PENDING row, exactly as before").isEqualTo(1);
    }

    // ---- CANCEL stays allowed (D-02 relies on it) ----

    @Test
    @DisplayName("D-02: an unpaid card DRAFT can still be cancelled")
    void unpaidCardDraftCanStillBeCancelled() throws Exception {
        UUID orderId = draftOrder();
        markCardAwaitingPayment(orderId, "PENDING");

        MvcResult result = transition(orderId, "cancel");

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(json(result).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
    }

    // ---- helpers ----

    /** The refusal is the typed problem, word for word the UI-SPEC sentence, with no issue number in it. */
    private void assertAwaitingPaymentProblem(MvcResult result, UUID orderId) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(409);
        assertThat(result.getResponse().getContentType())
                .as("RFC 7807 media type")
                .startsWith("application/problem+json");
        JsonNode problem = json(result);
        assertThat(problem.path("type").asString()).isEqualTo(AWAITING_PAYMENT_TYPE);
        assertThat(problem.path("title").asString()).isEqualTo("Awaiting payment");
        assertThat(problem.path("status").asInt()).isEqualTo(409);
        String orderNumber = orderNumberOf(orderId);
        assertThat(problem.path("detail").asString()).isEqualTo("Order " + orderNumber
                + " is awaiting payment. It can't be submitted, accepted or prepared until the customer's payment completes.");
        assertThat(problem.path("detail").asString()).as("no issue number reaches staff").doesNotContain("#");
    }

    private UUID draftOrder() throws Exception {
        MvcResult created = vendorOrder("30-05-" + UUID.randomUUID());
        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(201);
        UUID orderId = UUID.fromString(json(created).path("id").asString());
        assertThat(statusOf(orderId)).isEqualTo("DRAFT");
        return orderId;
    }

    /** The state a storefront card checkout leaves: a PaymentIntent exists, the money has not arrived. */
    private void markCardAwaitingPayment(UUID orderId, String paymentStatus) {
        int n = jdbc.update("UPDATE orders SET payment_status = ?, payment_reference = ? WHERE tenant_id = ? AND id = ?",
                paymentStatus, "pi_test_958_" + UUID.randomUUID(), tenant, orderId);
        assertThat(n).as("the card state was written").isEqualTo(1);
    }

    private MvcResult transition(UUID orderId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + orderId + "/" + action).with(admin())).andReturn();
    }

    private MvcResult vendorOrder(String idempotencyKey) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("quantity", 2);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shop);
        body.put("customerName", "Gate Probe");
        body.put("items", List.of(line));
        return mockMvc.perform(post("/api/v1/orders").with(admin())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** A realm admin of this tenant (implicit GROUP_ADMIN, D-03) holding the order scopes. */
    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"),
                        new SimpleGrantedAuthority("SCOPE_orders:read"));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE tenant_id = ? AND id = ?",
                String.class, tenant, orderId);
    }

    private String paymentStatusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT payment_status FROM orders WHERE tenant_id = ? AND id = ?",
                String.class, tenant, orderId);
    }

    private String paymentReferenceOf(UUID orderId) {
        return jdbc.queryForObject("SELECT payment_reference FROM orders WHERE tenant_id = ? AND id = ?",
                String.class, tenant, orderId);
    }

    private String orderNumberOf(UUID orderId) {
        return jdbc.queryForObject("SELECT order_number FROM orders WHERE tenant_id = ? AND id = ?",
                String.class, tenant, orderId);
    }

    /** The DRAFT -> PENDING ORDER_STATE_CHANGED row for this order. */
    private long pendingEventsFor(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_event_outbox WHERE tenant_id = ? "
                        + "AND event_type = 'ORDER_STATE_CHANGED' AND routing_key = 'order.state.pending' "
                        + "AND payload LIKE '%' || ? || '%' AND payload LIKE '%\"previousStatus\":\"DRAFT\"%' "
                        + "AND payload LIKE '%\"newStatus\":\"PENDING\"%'",
                Long.class, tenant, orderId.toString());
    }

    private long eventsFor(UUID orderId, String routingKey) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_event_outbox WHERE tenant_id = ? "
                        + "AND routing_key = ? AND payload LIKE '%' || ? || '%'",
                Long.class, tenant, routingKey, orderId.toString());
    }

    private long allEventsFor(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_event_outbox WHERE tenant_id = ? "
                        + "AND payload LIKE '%' || ? || '%'",
                Long.class, tenant, orderId.toString());
    }

    /** Open at any time (LEGACY_ALWAYS_OPEN, 37-20) with no minimum. */
    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "30-05 Shop " + id, "shop-30-05-" + id);
        return id;
    }

    /** @param stock {@code quantity_in_stock}, or null for an untracked product */
    private UUID seedProduct(UUID shopId, Integer stock) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, quantity_in_stock, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, 'see label', 0, 500, 0, true, false, ?, ?, 'STANDARD', 0)",
                id, tenant, "SKU-3005-" + id, "Product " + id, shopId, stock);
        return id;
    }
}
