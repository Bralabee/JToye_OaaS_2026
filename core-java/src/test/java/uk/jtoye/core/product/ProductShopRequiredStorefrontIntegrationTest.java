package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #959 (swarm SW-ADE-01): a dish saved with the default "All Shops" assignment was stored with
 * {@code shop_id NULL} and then appeared on no storefront, with no word of it to the vendor.
 *
 * <p>The decision is recorded in the PR: "All Shops" does NOT mean "on sale in every shop". The
 * storefront menu, the guest checkout (CR-01), the vendor order route (37-40), per-shop stock (V20),
 * the PPDS label's business identity and the CSV and sync writers all treat a product as belonging to
 * exactly one shop (UIX-05). So the create route now refuses a product with no shop, with a plain
 * RFC 7807 answer, instead of storing one that nobody can see. These tests drive the real filter chain,
 * the real service and a real Postgres (Testcontainers), then read the public menu back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ProductShopRequiredStorefrontIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopService shopService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** The #959 repro through the API: the form's default sends no shopId. RED on the unfixed tree (201). */
    @Test
    void createWithNoShopIsRefusedInPlainWordsAndStoresNothing() throws Exception {
        Fixture f = seed();
        String sku = "ALLSHOPS-" + f.tag;

        MvcResult res = mockMvc.perform(post("/api/v1/products").with(adminJwt(f.tenant))
                        .contentType("application/json").content(body(sku, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-required"))
                .andExpect(jsonPath("$.field").value("shopId"))
                .andReturn();

        String detail = jsonDetail(res);
        assertThat(detail).as("plain words: names the action, no column names, no UUID")
                .containsIgnoringCase("choose a shop")
                .doesNotContain("shop_id")
                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
        assertThat(countSku(f.tenant, sku)).as("nothing is stored for a refused create").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND shop_id IS NULL",
                Long.class, f.tenant)).as("no shop-less product exists in the tenant").isZero();
    }

    /** The positive control and the acceptance of #959: a create that names a shop is on that shop's menu. */
    @Test
    void createWithAShopAppearsOnThatShopsPublicMenuAndOnNoOther() throws Exception {
        Fixture f = seed();
        String sku = "ONSALE-" + f.tag;

        mockMvc.perform(post("/api/v1/products").with(adminJwt(f.tenant))
                        .contentType("application/json").content(body(sku, f.shopA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shopId").value(f.shopA.toString()));

        assertThat(menuTitles(f.slugA)).contains("Dish " + sku);
        assertThat(menuTitles(f.slugB)).as("the other shop of the same tenant does not list it")
                .doesNotContain("Dish " + sku);
    }

    /**
     * Cross-tenant: an existing shop-less ("All Shops") row, which the old form could create, is on no
     * storefront of its own tenant and, above all, on no storefront of ANOTHER tenant. Assigning it a
     * shop puts it on that one shop only. Passes before and after the fix (a pin on the invariant the
     * decision rests on); the red test is the create refusal above.
     */
    @Test
    void legacyShopLessProductIsOnNoStorefrontAcrossTenantsUntilAssignedAShop() throws Exception {
        Fixture a = seed();
        Fixture other = seed();
        UUID legacyId = UUID.randomUUID();
        String title = "Legacy All Shops dish " + a.tag;
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, vat_rate, available, shop_id) "
                        + "VALUES (?, ?, ?, ?, 'Rice', 0, 700, 'STANDARD', true, NULL)",
                legacyId, a.tenant, "LEGACY-" + a.tag, title);

        assertThat(menuTitles(a.slugA)).as("own tenant, shop A").doesNotContain(title);
        assertThat(menuTitles(a.slugB)).as("own tenant, shop B").doesNotContain(title);
        assertThat(menuTitles(other.slugA)).as("ANOTHER tenant's shop").doesNotContain(title);
        assertThat(menuTitles(other.slugB)).as("ANOTHER tenant's other shop").doesNotContain(title);

        mockMvc.perform(put("/api/v1/products/" + legacyId).with(adminJwt(a.tenant))
                        .contentType("application/json").content(updateBody(a.tag, a.shopA, title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value(a.shopA.toString()));

        assertThat(menuTitles(a.slugA)).as("assigned: on shop A").contains(title);
        assertThat(menuTitles(a.slugB)).as("assigned: not on shop B").doesNotContain(title);
        assertThat(menuTitles(other.slugA)).as("assigned: still not on another tenant's shop").doesNotContain(title);
        assertThat(menuTitles(other.slugB)).doesNotContain(title);
    }

    // ---------------------------------------------------------------------

    private record Fixture(UUID tenant, UUID shopA, UUID shopB, String slugA, String slugB, String tag) { }

    private Fixture seed() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Shop Required Tenant " + tenant);
        String tag = tenant.toString().substring(0, 8);
        UUID shopA = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Shop A " + tag)).getId());
        UUID shopB = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Shop B " + tag)).getId());
        jdbc.update("UPDATE shops SET published = true WHERE tenant_id = ?", tenant);
        String slugA = jdbc.queryForObject("SELECT slug FROM shops WHERE id = ?", String.class, shopA);
        String slugB = jdbc.queryForObject("SELECT slug FROM shops WHERE id = ?", String.class, shopB);
        return new Fixture(tenant, shopA, shopB, slugA, slugB, tag);
    }

    private long countSku(UUID tenant, String sku) {
        return jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND sku = ?",
                Long.class, tenant, sku);
    }

    /** Every dish title on a shop's PUBLIC menu, read through the unauthenticated storefront route. */
    private List<String> menuTitles(String slug) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/public/shops/" + slug + "/products"))
                .andExpect(status().isOk()).andReturn();
        tools.jackson.databind.JsonNode root = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString());
        java.util.ArrayList<String> titles = new java.util.ArrayList<>();
        root.forEach(group -> group.forEach(p -> titles.add(p.get("title").asString())));
        return titles;
    }

    private static String jsonDetail(MvcResult res) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString()).path("detail").asString();
    }

    private static String body(String sku, UUID shopId) {
        return "{\"sku\":\"" + sku + "\",\"title\":\"Dish " + sku + "\",\"ingredientsText\":\"Rice, **fish**\","
                + "\"allergenMask\":0,\"pricePennies\":1100,\"category\":\"Mains\",\"available\":true"
                + (shopId == null ? "" : ",\"shopId\":\"" + shopId + "\"") + "}";
    }

    private static String updateBody(String tag, UUID shopId, String title) {
        return "{\"sku\":\"LEGACY-" + tag + "\",\"title\":\"" + title + "\",\"ingredientsText\":\"Rice\","
                + "\"allergenMask\":0,\"pricePennies\":700,\"category\":\"Mains\",\"available\":true,"
                + "\"shopId\":\"" + shopId + "\"}";
    }

    private static CreateShopRequest shopRequest(String name) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName(name);
        req.setAddress("1 Test Street, London");
        return req;
    }

    private <T> T asRealmAdmin(UUID tenant, Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", "seed-admin@example.com")
                .build();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_admin"));
        SecurityContext fresh = SecurityContextHolder.createEmptyContext();
        fresh.setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        SecurityContextHolder.setContext(fresh);
        TenantContext.set(tenant);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "operator@example.com")
                        .claim("scope", "catalog:read catalog:write")
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new JwtRolesAndScopesConverter());
    }
}
