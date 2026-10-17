package uk.jtoye.core.product;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Phase 37-13 (RWO-026 / UXT-026): a CSV menu import lands in the shop the vendor chose.
 *
 * <p>The persona run: the vendor picked a shop in the switcher and imported a menu. The import took only
 * the file, so every row without its own {@code shop_id} cell was created with {@code shop_id NULL}. The
 * storefront reads products strictly by shop (UIX-05, {@code findAvailableByShopOrderedByCategory}), so the
 * whole menu was invisible to customers while the import reported success.
 *
 * <p>The rule this pins:
 * <ul>
 *   <li>{@code POST /api/v1/products/bulk/csv?shopId=A} needs SHOP_MANAGER on A, and every row without its
 *       own {@code shop_id} is created in A, so A's storefront shows it.</li>
 *   <li>A row with neither a {@code shop_id} cell nor a {@code shopId} parameter is a per-row error
 *       "shop required"; no shop-less product is created, for a Group admin too.</li>
 *   <li>A {@code shopId} the caller does not manage is a typed 403 and nothing is written.</li>
 *   <li>A row naming a shop the caller does not manage is a per-row error, never a write; the other rows
 *       still import.</li>
 * </ul>
 *
 * <p>Real Postgres 15 (Testcontainers). The bootstrap role is a SUPERUSER, so these arms prove the
 * application contract and the shop gate, not RLS. Every assertion about where a product landed reads
 * {@code products.shop_id} by SQL, and the storefront arm reads the public endpoint a customer uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class BulkImportShopDefaultIntegrationTest {

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
    @Autowired ShopAccessService shopAccessService;

    private static final String IMPORT = "/api/v1/products/bulk/csv";
    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";

    /** Three rows, no shop_id column: the shape the dashboard's template users actually upload. */
    private static final String CSV_NO_SHOP = """
            title,sku,price_pounds,ingredients,category,allergen_mask
            Jollof Rice,%1$s-JOLLOF,8.99,"Rice, tomatoes, peppers",Mains,0
            Puff Puff,%1$s-PUFF,2.50,"Flour, **milk**, sugar",Snacks,64
            Zobo,%1$s-ZOBO,3.00,"Hibiscus, ginger",Drinks,0
            """;

    private Object bootedStrictScoping;
    private UUID tenantId;
    private String suffix;
    private UUID shopA;
    private String shopASlug;
    private UUID shopA2;
    private UUID shopB;
    private UUID managerOfA;
    private UUID managerOfAandA2;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        // The shipped default (D-06): an ungranted user is NOT an implicit Group admin.
        StrictScopingGuard.set(shopAccessService, true);

        tenantId = UUID.randomUUID();
        suffix = tenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "37-13 Import Tenant " + suffix);
        shopASlug = "import-a-3713-" + suffix;
        shopA = seedShop(shopASlug, "37-13 Shop A " + suffix);
        shopA2 = seedShop("import-a2-3713-" + suffix, "37-13 Shop A2 " + suffix);
        shopB = seedShop("import-b-3713-" + suffix, "37-13 Shop B " + suffix);

        managerOfA = UUID.randomUUID();
        grant(managerOfA, shopA, "SHOP_MANAGER");
        managerOfAandA2 = UUID.randomUUID();
        grant(managerOfAandA2, shopA, "SHOP_MANAGER");
        grant(managerOfAandA2, shopA2, "SHOP_MANAGER");
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // UXT-026's repro: import with a shop chosen, then look at the storefront.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-026: a Group admin importing into shop A creates all three rows in A, and A's storefront shows them")
    void groupAdminImportIntoShopA_showsOnShopAsStorefront() throws Exception {
        MvcResult result = mockMvc.perform(upload(csvNoShop()).param("shopId", shopA.toString()).with(adminJwt()))
                .andReturn();
        JsonNode body = json(result, 200);
        assertThat(body.path("successCount").asInt()).as(text(result)).isEqualTo(3);
        assertThat(body.path("errorCount").asInt()).as(text(result)).isZero();

        assertThat(shopIdsOfImportedRows()).as("every imported row is in shop A, by SQL")
                .hasSize(3).containsOnly(shopA.toString());
        assertThat(storefrontTitles(shopASlug)).as("shop A's public storefront lists the imported menu")
                .containsExactlyInAnyOrder("Jollof Rice", "Puff Puff", "Zobo");
    }

    @Test
    @DisplayName("UXT-026: the manager of shop A imports into A with ?shopId=A, and the rows land in A")
    void managerOfA_importsIntoShopA() throws Exception {
        MvcResult result = mockMvc.perform(upload(csvNoShop()).param("shopId", shopA.toString())
                .with(userJwt(managerOfA))).andReturn();
        JsonNode body = json(result, 200);
        assertThat(body.path("successCount").asInt()).as(text(result)).isEqualTo(3);
        assertThat(shopIdsOfImportedRows()).as("every imported row is in shop A, by SQL")
                .hasSize(3).containsOnly(shopA.toString());
    }

    @Test
    @DisplayName("No shop anywhere: three per-row 'shop required' errors and nothing created, for a Group admin too")
    void noShopAnywhere_isAPerRowErrorAndCreatesNothing() throws Exception {
        MvcResult result = mockMvc.perform(upload(csvNoShop()).with(adminJwt())).andReturn();
        JsonNode body = json(result, 200);
        assertThat(body.path("successCount").asInt()).as(text(result)).isZero();
        assertThat(body.path("errorCount").asInt()).as(text(result)).isEqualTo(3);
        List<String> messages = new ArrayList<>();
        body.path("errors").forEach(e -> messages.add(e.path("message").asString()));
        assertThat(messages).as(text(result)).hasSize(3).allMatch(m -> m.equals("shop required"));
        assertThat(shopIdsOfImportedRows()).as("no shop-less product was written").isEmpty();
    }

    @Test
    @DisplayName("No shop anywhere, as the manager of A: per-row 'shop required', not a whole-batch 403")
    void noShopAnywhere_asManager_isAPerRowError() throws Exception {
        MvcResult result = mockMvc.perform(upload(csvNoShop()).with(userJwt(managerOfA))).andReturn();
        JsonNode body = json(result, 200);
        assertThat(body.path("errorCount").asInt()).as(text(result)).isEqualTo(3);
        assertThat(body.path("errors").path(0).path("message").asString()).as(text(result))
                .isEqualTo("shop required");
        assertThat(shopIdsOfImportedRows()).isEmpty();
    }

    @Test
    @DisplayName("T-37-35: ?shopId=B by the manager of A only is a typed 403 and writes nothing")
    void managerOfA_importingIntoShopB_isRefused() throws Exception {
        MvcResult result = mockMvc.perform(upload(csvNoShop()).param("shopId", shopB.toString())
                .with(userJwt(managerOfA))).andReturn();
        assertThat(json(result, 403).path("type").asString()).as(text(result)).isEqualTo(SHOP_ACCESS_DENIED);
        assertThat(shopIdsOfImportedRows()).as("nothing was written").isEmpty();
    }

    @Test
    @DisplayName("T-37-35: a row naming a shop the caller does not manage is a per-row error; the other rows import into the default")
    void rowNamingAForeignShop_isAPerRowError() throws Exception {
        String csv = """
                title,sku,price_pounds,ingredients,shop_id
                Jollof Rice,%1$s-JOLLOF,8.99,Rice,
                Suya,%1$s-SUYA,9.00,Beef,%2$s
                """.formatted(suffix, shopB);
        MvcResult result = mockMvc.perform(upload(file(csv)).param("shopId", shopA.toString())
                .with(userJwt(managerOfA))).andReturn();
        JsonNode body = json(result, 200);
        assertThat(body.path("successCount").asInt()).as(text(result)).isEqualTo(1);
        assertThat(body.path("errorCount").asInt()).as(text(result)).isEqualTo(1);
        assertThat(body.path("errors").path(0).path("row").asInt()).as(text(result)).isEqualTo(3);
        assertThat(body.path("errors").path(0).path("field").asString()).as(text(result)).isEqualTo("shop_id");
        assertThat(shopIdsOfImportedRows()).as("only the Jollof row, in shop A; nothing in shop B")
                .containsExactly(shopA.toString());
    }

    @Test
    @DisplayName("A row's own shop_id wins over the default when the caller manages it")
    void rowShopIdWinsOverTheDefault() throws Exception {
        String csv = """
                title,sku,price_pounds,ingredients,shop_id
                Jollof Rice,%1$s-JOLLOF,8.99,Rice,
                Suya,%1$s-SUYA,9.00,Beef,%2$s
                """.formatted(suffix, shopA2);
        MvcResult result = mockMvc.perform(upload(file(csv)).param("shopId", shopA.toString())
                .with(userJwt(managerOfAandA2))).andReturn();
        assertThat(json(result, 200).path("successCount").asInt()).as(text(result)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT shop_id::text FROM products WHERE tenant_id = ? AND title = 'Suya'",
                String.class, tenantId)).isEqualTo(shopA2.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT shop_id::text FROM products WHERE tenant_id = ? AND title = 'Jollof Rice'",
                String.class, tenantId)).isEqualTo(shopA.toString());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MockMultipartFile csvNoShop() {
        return file(CSV_NO_SHOP.formatted(suffix));
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "menu.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartHttpServletRequestBuilder upload(MockMultipartFile file) {
        return multipart(IMPORT).file(file);
    }

    /** shop_id of every product in this test's tenant, as text ("null" never appears: NULL rows read as null). */
    private List<String> shopIdsOfImportedRows() {
        return jdbcTemplate.queryForList("SELECT shop_id::text FROM products WHERE tenant_id = ? ORDER BY title",
                String.class, tenantId);
    }

    private List<String> storefrontTitles(String slug) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/shops/" + slug + "/products")).andReturn();
        JsonNode byCategory = json(result, 200);
        List<String> titles = new ArrayList<>();
        byCategory.forEach(products -> products.forEach(p -> titles.add(p.path("title").asString())));
        return titles;
    }

    private UUID seedShop(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenantId, name, slug);
        return id;
    }

    private void grant(UUID userId, UUID shop, String role) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), 'OPERATOR')", UUID.randomUUID(), tenantId, userId, shop, role);
    }

    /** A realm admin of the tenant (implicit Group admin) holding the catalogue write scope. */
    private RequestPostProcessor adminJwt() {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_catalog:write"));
    }

    /** A vendor user with no realm role: what they may write comes from shop_staff alone. */
    private RequestPostProcessor userJwt(UUID subject) {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:write"));
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
