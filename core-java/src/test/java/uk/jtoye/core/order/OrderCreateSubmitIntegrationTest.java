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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-42 (RWO-036 / UXT-036, decision D-13): an order an AI agent takes reaches the kitchen.
 *
 * <p>The persona run created orders through the MCP {@code create_order} tool and none of them ever
 * appeared on the kitchen board: {@code POST /api/v1/orders} creates a DRAFT, the board lists
 * PENDING..READY, and only the dashboard has a Submit button. D-13: the agent's order is PENDING, like a
 * storefront cash order, and emits the same DRAFT -> PENDING state change, so it lands in the New lane
 * (D-11) where the vendor still accepts it (no auto-confirm, T-37-98).
 *
 * <p>The mechanism keeps the dashboard's draft capability: {@code CreateOrderRequest.submit} is optional,
 * and only {@code submit: true} runs the existing SUBMIT transition inside the create's transaction. An
 * absent or false flag is the unchanged DRAFT flow.
 *
 * <p>Real filter chain, Testcontainers Postgres; the order row and the outbox row are read back by SQL,
 * and the kitchen board is read through its own endpoint. The caller is a realm admin (implicit
 * GROUP_ADMIN), so the shop gate passes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderCreateSubmitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
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
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenant, "37-42 Tenant " + tenant);
        shop = seedShop();
        product = seedProduct(shop);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-036: submit=true creates the order PENDING, records DRAFT -> PENDING in the outbox, and the kitchen board lists it")
    void submitTrueLandsPendingOnTheKitchenBoard() throws Exception {
        MvcResult result = vendorOrder(Boolean.TRUE, "37-42-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        JsonNode body = json(result);
        UUID orderId = UUID.fromString(body.path("id").asString());
        assertThat(body.path("status").asString()).as("the response states the resulting status").isEqualTo("PENDING");
        assertThat(statusOf(orderId)).as("stored status").isEqualTo("PENDING");

        assertThat(pendingEventsFor(orderId))
                .as("the same DRAFT -> PENDING state change the storefront cash path records")
                .isEqualTo(1);

        MvcResult board = kitchenBoard();
        assertThat(board.getResponse().getStatus()).as(text(board)).isEqualTo(200);
        assertThat(board.getResponse().getContentAsString())
                .as("the order is on the kitchen board (the New lane reads PENDING)")
                .contains(orderId.toString());
    }

    @Test
    @DisplayName("T-37-98: an agent order is PENDING, never CONFIRMED - the vendor still accepts every order")
    void submitTrueStopsAtPendingForTheVendorToAccept() throws Exception {
        MvcResult result = vendorOrder(Boolean.TRUE, "37-42-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        UUID orderId = UUID.fromString(json(result).path("id").asString());
        assertThat(statusOf(orderId)).isEqualTo("PENDING");
        assertThat(eventsFor(orderId, "order.state.confirmed")).as("no confirmation is recorded").isZero();
    }

    @Test
    @DisplayName("Dashboard flow unchanged: no submit flag creates a DRAFT, records no state change, and stays off the board")
    void absentSubmitStaysDraft() throws Exception {
        MvcResult result = vendorOrder(null, "37-42-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        UUID orderId = UUID.fromString(json(result).path("id").asString());
        assertThat(json(result).path("status").asString()).isEqualTo("DRAFT");
        assertThat(statusOf(orderId)).isEqualTo("DRAFT");
        assertThat(allEventsFor(orderId)).as("a draft announces nothing").isZero();
        assertThat(kitchenBoard().getResponse().getContentAsString()).doesNotContain(orderId.toString());
    }

    @Test
    @DisplayName("submit=false is the same DRAFT as no flag")
    void explicitFalseStaysDraft() throws Exception {
        MvcResult result = vendorOrder(Boolean.FALSE, "37-42-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        UUID orderId = UUID.fromString(json(result).path("id").asString());
        assertThat(statusOf(orderId)).isEqualTo("DRAFT");
        assertThat(allEventsFor(orderId)).isZero();
    }

    @Test
    @DisplayName("Agent retry: the same Idempotency-Key replays the PENDING order once, with one state change")
    void replayWithTheSameKeyIsOneOrderAndOneEvent() throws Exception {
        String key = "37-42-" + UUID.randomUUID();
        MvcResult first = vendorOrder(Boolean.TRUE, key);
        MvcResult replay = vendorOrder(Boolean.TRUE, key);

        assertThat(first.getResponse().getStatus()).as(text(first)).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).as(text(replay)).isEqualTo(201);
        UUID orderId = UUID.fromString(json(first).path("id").asString());
        assertThat(json(replay).path("id").asString()).isEqualTo(orderId.toString());
        assertThat(json(replay).path("status").asString()).isEqualTo("PENDING");
        assertThat(ordersOfThisTenant()).isEqualTo(1);
        assertThat(pendingEventsFor(orderId)).isEqualTo(1);
    }

    // ---- helpers ----

    private MvcResult vendorOrder(Boolean submit, String idempotencyKey) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("quantity", 2);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shop);
        body.put("customerName", "Agent Probe");
        body.put("items", List.of(line));
        if (submit != null) {
            body.put("submit", submit);
        }
        return mockMvc.perform(post("/api/v1/orders").with(admin())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult kitchenBoard() throws Exception {
        return mockMvc.perform(get("/api/v1/orders/kitchen").param("shopId", shop.toString())
                        .param("size", "100").with(admin()))
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

    private long ordersOfThisTenant() {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ?", Long.class, tenant);
    }

    /** The DRAFT -> PENDING ORDER_STATE_CHANGED row for this order, as the storefront cash path writes it. */
    private long pendingEventsFor(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_event_outbox WHERE tenant_id = ? "
                        + "AND event_type = 'ORDER_STATE_CHANGED' AND routing_key = 'order.state.pending' "
                        + "AND payload LIKE '%' || ? || '%' AND payload LIKE '%\"previousStatus\":\"DRAFT\"%' "
                        + "AND payload LIKE '%\"newStatus\":\"PENDING\"%' AND payload LIKE '%' || ? || '%'",
                Long.class, tenant, orderId.toString(), shop.toString());
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
                id, tenant, "37-42 Shop " + id, "shop-37-42-" + id);
        return id;
    }

    private UUID seedProduct(UUID shopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, quantity_in_stock, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, 'see label', 0, 500, 0, true, false, ?, NULL, 'STANDARD', 0)",
                id, tenant, "SKU-3742-" + id, "Product " + id, shopId);
        return id;
    }
}
