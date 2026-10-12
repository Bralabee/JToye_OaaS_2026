package uk.jtoye.core.sync;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 37-40 (RWO-017 / UXT-017 P0, RWO-037 / UXT-037): a synced product belongs to a shop, and
 * {@code POST /api/v1/sync/batch} says what it did with every item.
 *
 * <p>UXT-017: the batch never set {@code shop_id}, so every synced product was a tenant-wide
 * (shop_id NULL) row, listed on every shop's menu and orderable from any of them, and it was
 * written with direct setters, so its allergen emphasis spans were never parsed. UXT-037: an
 * unknown type or a product without a SKU was skipped, and the batch still answered
 * {@code SUCCESS}. Both are asserted here through the real filter chain (scope gate, the real
 * {@code ShopAccessService}, Testcontainers Postgres) and by reading the rows back.
 *
 * <p>The caller in the mixed batch is a SHOP_MANAGER of shop A only, seeded straight into
 * {@code shop_staff} (strict scoping OFF, the explicit grant makes them scoped), exactly as
 * {@link SyncBatchAuthorizationIntegrationTest} does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class SyncBatchShopRequiredIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String SYNC = "/api/v1/sync/batch";
    private static final String EMPHASISED = "Wheat flour, **milk**, sugar, **egg**";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopService shopService;
    @Autowired private ProductService productService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // =====================================================================
    // UXT-017 + UXT-037: the mixed batch is reported item by item
    // =====================================================================

    /**
     * The UXT-017/UXT-037 repro. Four items from a manager of shop A: a product for shop A, a
     * product with no shop, an item of type "widget", and a product for shop B (no grant). RED on
     * the unfixed tree: 200 {@code SUCCESS} with processedCount 2, the shop-A product stored with
     * shop_id NULL and the shop-less product stored too (or the whole batch 403s on the first
     * tenant-wide create). Fixed: 200 {@code PARTIAL}, one result per item, only the shop-A
     * product written, and it belongs to shop A.
     */
    @Test
    void mixedBatchReportsEveryItemAndOnlyTheShopScopedProductLands() throws Exception {
        Fixture f = seed();
        String tag = f.tag;
        String skuA = "SYNC-A-" + tag;
        String skuNoShop = "SYNC-NOSHOP-" + tag;
        String skuB = "SYNC-B-" + tag;
        String body = "{\"items\":["
                + product(skuA, f.shopA, "Rice, tomatoes") + ","
                + product(skuNoShop, null, "Rice, tomatoes") + ","
                + "{\"type\":\"widget\",\"sku\":\"W-" + tag + "\"},"
                + product(skuB, f.shopB, "Rice, tomatoes")
                + "]}";

        mockMvc.perform(post(SYNC).with(managerJwt(f)).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.processedCount").value(1))
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[0].key").value(skuA))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[1].index").value(1))
                .andExpect(jsonPath("$.results[1].key").value(skuNoShop))
                .andExpect(jsonPath("$.results[1].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].code").value("SHOP_REQUIRED"))
                .andExpect(jsonPath("$.results[2].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.results[2].code").value("UNKNOWN_TYPE"))
                .andExpect(jsonPath("$.results[3].key").value(skuB))
                .andExpect(jsonPath("$.results[3].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.results[3].code").value("SHOP_ACCESS_DENIED"));

        assertThat(shopOf(f.tenant, skuA))
                .as("UXT-017: the synced product belongs to the shop the item named")
                .isEqualTo(f.shopA.toString());
        assertThat(countSku(f.tenant, skuNoShop)).as("a shop-less product is refused, not stored").isZero();
        assertThat(countSku(f.tenant, skuB)).as("nothing is written into an ungranted shop").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND shop_id IS NULL",
                Long.class, f.tenant)).as("the batch created no tenant-wide product").isZero();
    }

    /**
     * The synced product goes through the same create rules as the dashboard: its emphasis spans
     * are parsed from the ingredients text exactly as {@code ProductService.createProduct} parses
     * them. RED on the unfixed tree: the direct-setter path never parsed them (NULL), and the row
     * has no shop.
     */
    @Test
    void syncedProductHasItsShopAndTheSameSpansAsADashboardCreate() throws Exception {
        Fixture f = seed();
        String dashboardSku = "DASH-" + f.tag;
        String syncSku = "SYNCSPAN-" + f.tag;
        asRealmAdmin(f.tenant, () -> productService.createProduct(
                productRequest(f.shopA, dashboardSku, EMPHASISED)).getId());

        mockMvc.perform(post(SYNC).with(adminJwt(f.tenant)).contentType("application/json")
                        .content("{\"items\":[" + product(syncSku, f.shopA, EMPHASISED) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"));

        String dashboardSpans = spansOf(f.tenant, dashboardSku);
        assertThat(dashboardSpans).as("control: the dashboard create parsed two emphasised allergens (two offset spans)")
                .isNotNull();
        assertThat(dashboardSpans.split("\"start\"", -1).length - 1).isEqualTo(2);
        assertThat(spansOf(f.tenant, syncSku))
                .as("the synced product's spans are byte-identical to the dashboard create's")
                .isEqualTo(dashboardSpans);
        assertThat(shopOf(f.tenant, syncSku)).isEqualTo(f.shopA.toString());
    }

    // =====================================================================
    // Updates: the shop must match, and availability and stock go through the 37-39 rules
    // =====================================================================

    /**
     * An existing SKU belongs to shop A; an item names it with shop B. The SKU is unique per tenant, so
     * this can only be a request to move or overwrite another shop's product. RED after the first
     * GREEN: the (sku, shop) lookup misses and the create collides with the unique index
     * (WRITE_CONFLICT), which tells the integrator nothing useful. Fixed: REJECTED SHOP_MISMATCH and
     * the product is byte-identical (shop, title, price).
     */
    @Test
    void updateNamingADifferentShopIsRejectedShopMismatchAndChangesNothing() throws Exception {
        Fixture f = seed();
        String sku = "MOVE-" + f.tag;
        asRealmAdmin(f.tenant, () -> productService.createProduct(productRequest(f.shopA, sku, "Rice")).getId());

        mockMvc.perform(post(SYNC).with(adminJwt(f.tenant)).contentType("application/json")
                        .content("{\"items\":[" + product(sku, f.shopB, "Rice") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("SHOP_MISMATCH"))
                .andExpect(jsonPath("$.results[0].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.processedCount").value(0))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT shop_id::text AS shop_id, title, price_pennies FROM products WHERE tenant_id = ? AND sku = ?",
                f.tenant, sku);
        assertThat(row.get("shop_id")).isEqualTo(f.shopA.toString());
        assertThat(row.get("title")).isEqualTo("Product " + sku);
        assertThat(((Number) row.get("price_pennies")).longValue()).isEqualTo(500L);
    }

    /**
     * Same shop, {@code available false} and {@code quantityInStock 3}: UPDATED and stored, and the
     * allergen declaration and spans are not touched by an item that does not mention them.
     */
    @Test
    void updateInTheSameShopStoresAvailabilityAndStock() throws Exception {
        Fixture f = seed();
        String sku = "STOCK-" + f.tag;
        asRealmAdmin(f.tenant, () -> productService.createProduct(productRequest(f.shopA, sku, EMPHASISED)).getId());
        String spansBefore = spansOf(f.tenant, sku);

        mockMvc.perform(post(SYNC).with(managerJwt(f)).contentType("application/json")
                        .content("{\"items\":[{\"type\":\"product\",\"sku\":\"" + sku + "\",\"shopId\":\""
                                + f.shopA + "\",\"available\":false,\"quantityInStock\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.results[0].outcome").value("UPDATED"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT available, quantity_in_stock, allergen_mask, ingredients_text FROM products "
                        + "WHERE tenant_id = ? AND sku = ?", f.tenant, sku);
        assertThat((Boolean) row.get("available")).isFalse();
        assertThat(((Number) row.get("quantity_in_stock")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("allergen_mask")).intValue()).isZero();
        assertThat(row.get("ingredients_text")).isEqualTo(EMPHASISED);
        assertThat(spansOf(f.tenant, sku)).isEqualTo(spansBefore);
    }

    // =====================================================================
    // The status is the truth about the items
    // =====================================================================

    @Test
    void allItemsWrittenIsSuccess() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC).with(managerJwt(f)).contentType("application/json")
                        .content("{\"items\":[" + product("OK1-" + f.tag, f.shopA, "Rice") + ","
                                + product("OK2-" + f.tag, f.shopA, "Rice") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.processedCount").value(2))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.results[1].outcome").value("CREATED"));
    }

    /**
     * UXT-037: nothing was written, so the batch must not say SUCCESS or PARTIAL. RED after the first
     * GREEN: PARTIAL.
     */
    @Test
    void noItemWrittenIsRejected() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC).with(managerJwt(f)).contentType("application/json")
                        .content("{\"items\":[" + product("BAD1-" + f.tag, null, "Rice") + ","
                                + "{\"type\":\"widget\"},"
                                + product("BAD2-" + f.tag, f.shopB, "Rice") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.processedCount").value(0))
                .andExpect(jsonPath("$.results.length()").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ?", Long.class, f.tenant))
                .isZero();
    }

    // ---------------------------------------------------------------------
    // fixture
    // ---------------------------------------------------------------------

    /** One tenant, shops A and B, and a human holding SHOP_MANAGER on A only. */
    private record Fixture(UUID tenant, UUID shopA, UUID shopB, UUID manager, String tag) { }

    private Fixture seed() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Sync Shop Tenant " + tenant);
        String tag = tenant.toString().substring(0, 8);
        UUID shopA = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Shop A " + tag)).getId());
        UUID shopB = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Shop B " + tag)).getId());
        UUID manager = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, 'SHOP_MANAGER', now())",
                UUID.randomUUID(), tenant, manager, shopA);
        return new Fixture(tenant, shopA, shopB, manager, tag);
    }

    private long countSku(UUID tenant, String sku) {
        return jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND sku = ?",
                Long.class, tenant, sku);
    }

    private String shopOf(UUID tenant, String sku) {
        return jdbc.queryForObject("SELECT shop_id::text FROM products WHERE tenant_id = ? AND sku = ?",
                String.class, tenant, sku);
    }

    private String spansOf(UUID tenant, String sku) {
        return jdbc.queryForObject("SELECT allergen_spans::text FROM products WHERE tenant_id = ? AND sku = ?",
                String.class, tenant, sku);
    }

    private static CreateShopRequest shopRequest(String name) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName(name);
        req.setAddress("1 Test Street, London");
        return req;
    }

    private static CreateProductRequest productRequest(UUID shopId, String sku, String ingredients) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku(sku);
        req.setTitle("Product " + sku);
        req.setIngredientsText(ingredients);
        req.setAllergenMask(0);
        req.setPricePennies(500L);
        req.setShopId(shopId);
        return req;
    }

    /** Same fresh-context discipline as {@code SyncBatchAuthorizationIntegrationTest.asRealmAdmin}. */
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

    // ---------------------------------------------------------------------
    // request bodies + principals
    // ---------------------------------------------------------------------

    /** A product item; {@code shopId} null omits the key entirely (the shop-less case). */
    private static String product(String sku, UUID shopId, String ingredients) {
        return "{\"type\":\"product\",\"sku\":\"" + sku + "\",\"title\":\"Synced " + sku + "\","
                + (shopId == null ? "" : "\"shopId\":\"" + shopId + "\",")
                + "\"ingredientsText\":\"" + ingredients + "\",\"allergenMask\":0,\"pricePennies\":650}";
    }

    private static RequestPostProcessor scopedJwt(UUID sub, UUID tenant, String scope) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("scope", scope))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private static RequestPostProcessor managerJwt(Fixture f) {
        return scopedJwt(f.manager, f.tenant, "catalog:read catalog:write");
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
