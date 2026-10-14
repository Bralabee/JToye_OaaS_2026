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
 * 37-E review: no writer can order a product its shop has switched off ({@code available = false}).
 *
 * <p>The storefront drops such a line and reports it UNAVAILABLE (37-33, D-16), but
 * {@code OrderService.createOrder} - the route the dashboard, the REST API and the MCP
 * {@code create_order} tool share - checked existence, shop and stock and never availability. Since
 * 37-42 an MCP order is submitted on creation, so a switched-off product reached the kitchen's New
 * lane as PENDING with no vendor review (shown live at the 37-47 gate). The refusal is a typed 409
 * {@code product-unavailable} naming the product, not the shop-mismatch 404: the caller holds
 * SHOP_MANAGER on this shop and can already list the product, so there is nothing to hide, and a 404
 * would tell an agent the id is wrong when it is not.
 *
 * <p>Real filter chain and Testcontainers Postgres; rows read back by SQL. The caller is a realm admin
 * (implicit GROUP_ADMIN), so the shop gate passes and only the product rule can refuse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderUnavailableProductIntegrationTest {

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

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenant, "37-E Tenant " + tenant);
        shop = seedShop();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("A switched-off product is refused 409 product-unavailable, naming it, and no order is written")
    void switchedOffProductIsRefused() throws Exception {
        UUID off = seedProduct("Jollof Rice", false);
        String email = "off-" + UUID.randomUUID() + "@example.com";

        MvcResult result = vendorOrder(off, email, null);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(409);
        JsonNode problem = problem(result);
        assertThat(problem.path("type").asString()).isEqualTo("https://jtoye.uk/errors/product-unavailable");
        assertThat(problem.path("productId").asString()).isEqualTo(off.toString());
        assertThat(problem.path("productName").asString()).isEqualTo("Jollof Rice");
        assertThat(problem.path("detail").asString()).contains("Jollof Rice").contains("switched off");
        assertThat(ordersFor(email)).as("nothing is written").isZero();
    }

    @Test
    @DisplayName("MCP's submit-on-create (37-42) is refused the same way: nothing reaches the kitchen")
    void switchedOffProductIsRefusedOnSubmitOnCreate() throws Exception {
        UUID off = seedProduct("Puff Puff", false);
        String email = "off-submit-" + UUID.randomUUID() + "@example.com";

        MvcResult result = vendorOrder(off, email, true);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(409);
        assertThat(problem(result).path("type").asString()).isEqualTo("https://jtoye.uk/errors/product-unavailable");
        assertThat(ordersFor(email)).as("no PENDING order for the kitchen").isZero();
    }

    @Test
    @DisplayName("One switched-off line refuses the whole order, even beside an available one")
    void oneSwitchedOffLineRefusesTheOrder() throws Exception {
        UUID on = seedProduct("Chin Chin", true);
        UUID off = seedProduct("Moi Moi", false);
        String email = "mixed-" + UUID.randomUUID() + "@example.com";

        MvcResult result = vendorOrder(List.of(on, off), email, true);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(409);
        assertThat(problem(result).path("productId").asString()).isEqualTo(off.toString());
        assertThat(ordersFor(email)).isZero();
    }

    @Test
    @DisplayName("Control: an available product of the shop is accepted and submitted (201, PENDING)")
    void availableProductIsAccepted() throws Exception {
        UUID on = seedProduct("Suya", true);
        String email = "on-" + UUID.randomUUID() + "@example.com";

        MvcResult result = vendorOrder(on, email, true);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        assertThat(problem(result).path("status").asString()).as("the order body").isEqualTo("PENDING");
        assertThat(ordersFor(email)).isEqualTo(1);
    }

    // ---- helpers ----

    private MvcResult vendorOrder(UUID productId, String email, Boolean submit) throws Exception {
        return vendorOrder(List.of(productId), email, submit);
    }

    private MvcResult vendorOrder(List<UUID> productIds, String email, Boolean submit) throws Exception {
        List<Map<String, Object>> lines = productIds.stream().map(id -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productId", id);
            line.put("quantity", 1);
            return line;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shop);
        body.put("customerName", "Availability Probe");
        body.put("customerEmail", email);
        body.put("items", lines);
        if (submit != null) {
            body.put("submit", submit);
        }
        return mockMvc.perform(post("/api/v1/orders").with(admin())
                        .header("Idempotency-Key", "37-E-avail-" + UUID.randomUUID())
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

    private JsonNode problem(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private long ordersFor(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?",
                Long.class, tenant, email);
    }

    /** Open at any time (LEGACY_ALWAYS_OPEN, 37-20) with no minimum, so only the product rule can refuse. */
    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "37-E Shop " + id, "shop-37-e-" + id);
        return id;
    }

    private UUID seedProduct(String title, boolean available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, quantity_in_stock, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, 'see label', 0, 500, 0, ?, false, ?, NULL, 'STANDARD', 0)",
                id, tenant, "SKU-37E-" + id, title, available, shop);
        return id;
    }
}
