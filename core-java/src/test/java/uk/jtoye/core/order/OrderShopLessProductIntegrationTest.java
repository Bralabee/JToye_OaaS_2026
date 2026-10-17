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
 * Phase 37-40 (RWO-017 / UXT-017 P0, roadmap SC-5 shop-less part): no writer can order a product that
 * belongs to no shop.
 *
 * <p>{@code OrderService.createOrder} kept a deliberate null arm (PR #726 review M2): a {@code shop_id
 * IS NULL} product was "tenant-wide by design (V20)" and orderable from any of the tenant's shops. The
 * storefront never offered such a product (UIX-05, 19), so only the vendor route took one, and with it
 * an allergen picture nobody at the ordering shop had declared: the persona run (UXT-017) put a
 * shop-less synced product with mask 0 on another shop's order. {@code POST /api/v1/orders} is the
 * route the dashboard, the REST API and the MCP {@code create_order} tool share, so one refusal here
 * covers all three. The refusal is the SAME non-disclosing 404 as another shop's product. Existing
 * shop-less products are not deleted or hidden: they stay listable.
 *
 * <p>Real filter chain and Testcontainers Postgres; rows read back by SQL. The caller is a realm admin
 * (implicit GROUP_ADMIN), so the shop gate passes and only the product rule can refuse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderShopLessProductIntegrationTest {

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
    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenant, "37-40 Tenant " + tenant);
        shopA = seedShop("a");
        shopB = seedShop("b");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-017: a vendor order with a shop-less product is 404, and no order is written")
    void shopLessProductIsNotFoundOnTheVendorRoute() throws Exception {
        UUID shopLess = seedProduct(null);
        String email = "shopless-" + UUID.randomUUID() + "@example.com";

        MvcResult result = vendorOrder(shopA, shopLess, email);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(404);
        assertThat(problem(result).path("detail").asString()).isEqualTo("Product not found: " + shopLess);
        assertThat(ordersFor(email)).as("nothing is written").isZero();
    }

    @Test
    @DisplayName("The shop-less refusal is the same non-disclosing 404 as another shop's product")
    void shopLessRefusalIsIdenticalToAnotherShopsProduct() throws Exception {
        UUID shopLess = seedProduct(null);
        UUID ofShopB = seedProduct(shopB);

        MvcResult shopLessResult = vendorOrder(shopA, shopLess, "x-" + UUID.randomUUID() + "@example.com");
        MvcResult foreignResult = vendorOrder(shopA, ofShopB, "y-" + UUID.randomUUID() + "@example.com");

        assertThat(foreignResult.getResponse().getStatus()).as("control: " + text(foreignResult)).isEqualTo(404);
        assertThat(shopLessResult.getResponse().getStatus()).as(text(shopLessResult)).isEqualTo(404);
        JsonNode a = problem(shopLessResult);
        JsonNode b = problem(foreignResult);
        assertThat(a.path("type").asString()).isEqualTo(b.path("type").asString());
        assertThat(a.path("title").asString()).isEqualTo(b.path("title").asString());
        assertThat(a.path("detail").asString().replace(shopLess.toString(), "{id}"))
                .isEqualTo(b.path("detail").asString().replace(ofShopB.toString(), "{id}"));
    }

    @Test
    @DisplayName("Control: a product of the ordered shop is accepted (201)")
    void productOfTheOrderedShopIsAccepted() throws Exception {
        UUID own = seedProduct(shopA);
        String email = "own-" + UUID.randomUUID() + "@example.com";

        MvcResult result = vendorOrder(shopA, own, email);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        assertThat(ordersFor(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("Existing shop-less products stay listable: the refusal is on ordering, not on the catalogue")
    void shopLessProductStaysListable() throws Exception {
        UUID shopLess = seedProduct(null);

        MvcResult result = mockMvc.perform(get("/api/v1/products").param("size", "100").with(admin())).andReturn();

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains(shopLess.toString());
    }

    // ---- helpers ----

    private MvcResult vendorOrder(UUID shopId, UUID productId, String email) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("quantity", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shopId);
        body.put("customerName", "Shop-less Probe");
        body.put("customerEmail", email);
        body.put("items", List.of(line));
        return mockMvc.perform(post("/api/v1/orders").with(admin())
                        .header("Idempotency-Key", "37-40-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** A realm admin of this tenant (implicit GROUP_ADMIN, D-03) holding the order and catalogue scopes. */
    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"),
                        new SimpleGrantedAuthority("SCOPE_orders:read"),
                        new SimpleGrantedAuthority("SCOPE_catalog:read"));
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
    private UUID seedShop(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "37-40 Shop " + suffix + " " + id, "shop-37-40-" + suffix + "-" + id);
        return id;
    }

    /** A product of {@code shopId}, or a shop-less (tenant-wide, legacy V20) one when null. */
    private UUID seedProduct(UUID shopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, quantity_in_stock, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, 'see label', 0, 500, 0, true, false, ?, NULL, 'STANDARD', 0)",
                id, tenant, "SKU-3740-" + id, "Product " + id, shopId);
        return id;
    }
}
