package uk.jtoye.core.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * QA-council 20260902-134741 Cluster A — {@code POST /api/v1/sync/batch} authorization +
 * validation, proven through the REAL filter chain against Testcontainers Postgres (real
 * Flyway schema, real RLS, real {@code GlobalExceptionHandler}). Issue #648 and its
 * severity amendment (SEC-5), API-1, API-2, API-13.
 *
 * <p>Three walls, each pinned by at least one deny arm AND one allow arm (an allow arm is
 * what stops a deny arm being satisfied by a broken token or a 500):
 * <ol>
 *   <li><b>API-1, the machine half</b> — the endpoint rides {@code SCOPE_catalog:write}
 *       exactly like the nine {@code ProductController} mutations
 *       ({@code docs/security-scopes.md} §1). A {@code catalog:read}-only or scopeless token
 *       is 403; a write-scoped token passes. Token shapes mirror
 *       {@code ScopedCatalogAccessIntegrationTest}: UUID subject + {@code tenant_id} +
 *       {@code scope} claim through the real {@link JwtRolesAndScopesConverter}.</li>
 *   <li><b>SEC-5, the human half</b> — after resolving the product by SKU, the write is
 *       gated on the product's OWNING shop via {@code ShopAccessService.require(shopId,
 *       SHOP_MANAGER)}, the {@code ProductService.updateProduct} pattern (VSA-02 / D-02). A
 *       user holding a SHOP_MANAGER grant on shop A who names shop B's SKU gets the typed
 *       {@code shop-access-denied} 403 and shop B's row is untouched; the same user on shop
 *       A's SKU succeeds. Strict-scoping stays OFF (the live default, adjudication A1): once
 *       a user holds ANY explicit grant they are scoped even under OFF
 *       ({@code ShopAccessService} Javadoc; pinned by
 *       {@code StaffManagementIntegrationTest.myAccessReportsScopedGrantsForNonGroupAdmin}),
 *       so the grant is seeded directly into {@code shop_staff} exactly as
 *       {@code ShopAccessEnforcementIntegrationTest.grantShopStaff} does.</li>
 *   <li><b>API-2</b> — each batch item carries the same Jakarta constraints
 *       {@code CreateProductRequest} declares, so the RFC 7807 {@code validation} 400 names
 *       the offending field ({@code items[0].pricePennies}) and nothing is written.</li>
 * </ol>
 *
 * <p><b>API-13</b>: the shop branch of the batch is proven to actually CREATE a shop, with a
 * slug derived the way {@code ShopService.createShop} derives it — the branch previously
 * 400'd on {@code shops.slug NOT NULL} on every attempt (finding API-13: zero rows ever).
 *
 * <p>Fixtures are seeded through the real service layer as a realm-admin (implicit
 * GROUP_ADMIN) so the graph is valid; each test uses a fresh tenant. Every state assertion
 * reads the row back from Postgres — a 403 alone would not distinguish "denied" from
 * "denied after writing".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class SyncBatchAuthorizationIntegrationTest {

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
    private static final String TYPE_FORBIDDEN = "https://jtoye.uk/errors/forbidden";
    private static final String TYPE_SHOP_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String TYPE_VALIDATION = "https://jtoye.uk/errors/validation";

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
    // API-1 — the machine half: SCOPE_catalog:write gates the endpoint
    // =====================================================================

    /**
     * Baseline preserved: an unauthenticated caller is still 401 (anyRequest().authenticated()),
     * same shape as SecurityHeadersIntegrationTest.headersPresentOn401. A bare perform() is only
     * genuinely anonymous because {@link #asRealmAdmin} installs a FRESH context object — see its
     * Javadoc for the TestSecurityContextHolder trap that otherwise smuggles the seeding admin in.
     */
    @Test
    void unauthenticatedCallerIs401() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC).contentType("application/json")
                        .content(productJson(f.skuA, "anon-mutated", 0, 100)))
                .andExpect(status().isUnauthorized());
        assertThat(titleOf(f.tenant, f.skuA)).isEqualTo(originalTitle(f.skuA));
    }

    /**
     * The documented read-only machine credential ({@code integration-catalog-ro},
     * {@code catalog:read} only) must NOT write the catalogue through the batch endpoint —
     * finding API-1's headline. RED on the unfixed tree: 200 and the title mutates.
     */
    @Test
    void readOnlyScopeIsForbidden_andWritesNothing() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(scopedJwt(UUID.randomUUID(), f.tenant, "catalog:read"))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "ro-mutated", 0, 100)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE_FORBIDDEN));
        assertThat(titleOf(f.tenant, f.skuA))
                .as("a catalog:read-only token must not mutate the catalogue via /sync/batch")
                .isEqualTo(originalTitle(f.skuA));
    }

    /** Fail-closed migration posture (same as ScopedCatalogAccessIntegrationTest): no scope claim -> 403. */
    @Test
    void scopelessTokenIsForbidden() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(scopedJwt(UUID.randomUUID(), f.tenant, null))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "noscope-mutated", 0, 100)))
                .andExpect(status().isForbidden());
        assertThat(titleOf(f.tenant, f.skuA)).isEqualTo(originalTitle(f.skuA));
    }

    /** Allow arm for the machine half: a write-scoped realm-admin passes the gate and the upsert lands. */
    @Test
    void writeScopedAdminPassesTheGate_andTheUpsertLands() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "admin-updated", 3, 750)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.processedCount").value(1));
        assertThat(titleOf(f.tenant, f.skuA)).isEqualTo("admin-updated");
        assertThat(jdbc.queryForObject("SELECT price_pennies FROM products WHERE tenant_id = ? AND sku = ?",
                Long.class, f.tenant, f.skuA)).isEqualTo(750L);
    }

    // =====================================================================
    // SEC-5 — the human half: the write is gated on the product's OWNING shop
    // =====================================================================

    /**
     * #648 / SEC-5: a SHOP_MANAGER granted shop A names shop B's SKU. The batch resolves the
     * product by SKU alone (unique per tenant), so without a shop predicate this rewrites shop
     * B's title, ingredients and — the consumer-safety point — its legally-operative allergen
     * declaration. RED on the unfixed tree: 200, processedCount 1, shop B mutated.
     */
    @Test
    void shopManagerOnShopA_isDeniedWritingShopBsSku_withTheTypedShop403() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(managerJwt(f))
                        .contentType("application/json")
                        .content(productJson(f.skuB, "PWNED-by-shop-A-manager", 1, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE_SHOP_DENIED))
                .andExpect(jsonPath("$.shopId").value(f.shopB.toString()))
                .andExpect(jsonPath("$.requiredRole").value("SHOP_MANAGER"));
        assertThat(titleOf(f.tenant, f.skuB))
                .as("shop B's row must be byte-identical after the denied write")
                .isEqualTo(originalTitle(f.skuB));
        assertThat(jdbc.queryForObject("SELECT allergen_mask FROM products WHERE tenant_id = ? AND sku = ?",
                Integer.class, f.tenant, f.skuB))
                .as("the allergen declaration (V63 snapshot source) is untouched")
                .isEqualTo(0);
    }

    /** Allow arm for the human half: the same manager CAN write their own shop's SKU (the gate is scoped, not a wall). */
    @Test
    void shopManagerOnShopA_canWriteShopAsSku() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(managerJwt(f))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "manager-updated", 2, 650)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1));
        assertThat(titleOf(f.tenant, f.skuA)).isEqualTo("manager-updated");
    }

    /**
     * CR-04 write half: a brand-new SKU has no owning shop (the batch never sets shop_id), so
     * it is a tenant-wide resource and GROUP_ADMIN-only — a scoped SHOP_MANAGER must not mint
     * it. RED on the unfixed tree: 200 and a shop_id-NULL product appears.
     */
    @Test
    void shopManagerCannotMintATenantWideProduct() throws Exception {
        Fixture f = seed();
        String newSku = "NEW-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post(SYNC)
                        .with(managerJwt(f))
                        .contentType("application/json")
                        .content(productJson(newSku, "minted-by-scoped-user", 0, 100)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE_SHOP_DENIED))
                .andExpect(jsonPath("$.requiredRole").value("GROUP_ADMIN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND sku = ?",
                Long.class, f.tenant, newSku)).isZero();
    }

    /** The shop branch is gated the same way: a manager of shop A cannot rename/re-address shop B. */
    @Test
    void shopManagerCannotMutateAnotherShop() throws Exception {
        Fixture f = seed();
        String shopBName = jdbc.queryForObject("SELECT name FROM shops WHERE id = ?", String.class, f.shopB);
        mockMvc.perform(post(SYNC)
                        .with(managerJwt(f))
                        .contentType("application/json")
                        .content(shopJson(shopBName, "99 Hijacked Road")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE_SHOP_DENIED))
                .andExpect(jsonPath("$.shopId").value(f.shopB.toString()));
        assertThat(jdbc.queryForObject("SELECT address FROM shops WHERE id = ?", String.class, f.shopB))
                .isEqualTo("1 Test Street, London");
    }

    /** Shop CREATE is GROUP_ADMIN-only on the normal path (ShopService.createShop); the batch must agree. */
    @Test
    void shopManagerCannotCreateAShop() throws Exception {
        Fixture f = seed();
        String name = "Rogue Shop " + UUID.randomUUID();
        mockMvc.perform(post(SYNC)
                        .with(managerJwt(f))
                        .contentType("application/json")
                        .content(shopJson(name, "1 Rogue Lane")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE_SHOP_DENIED))
                .andExpect(jsonPath("$.requiredRole").value("GROUP_ADMIN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shops WHERE tenant_id = ? AND name = ?",
                Long.class, f.tenant, name)).isZero();
    }

    // =====================================================================
    // API-2 — validation parity with PUT /api/v1/products/{id}
    // =====================================================================

    /** RED on the unfixed tree: 200 and price_pennies = -500 is stored (then served on the public storefront). */
    @Test
    void negativePriceIs400NamingTheField_andNothingIsWritten() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "should-not-land", 0, -500)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_VALIDATION))
                .andExpect(jsonPath("$.errors['items[0].pricePennies']").value("Price must be non-negative"));
        assertThat(titleOf(f.tenant, f.skuA))
                .as("a rejected item must not have partially landed")
                .isEqualTo(originalTitle(f.skuA));
    }

    /** RED on the unfixed tree: 200 and allergen_mask = 16384 stored (outside the 14-bit UK FSA catalogue). */
    @Test
    void allergenMaskAboveTheCatalogueIs400NamingTheField() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "should-not-land", 16384, 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_VALIDATION))
                .andExpect(jsonPath("$.errors['items[0].allergenMask']")
                        .value("Allergen mask must not exceed 16383 (14 allergens max)"));
        assertThat(jdbc.queryForObject("SELECT allergen_mask FROM products WHERE tenant_id = ? AND sku = ?",
                Integer.class, f.tenant, f.skuA)).isEqualTo(0);
    }

    /** RED on the unfixed tree: 200 and allergen_mask = -1 stored. */
    @Test
    void negativeAllergenMaskIs400NamingTheField() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson(f.skuA, "should-not-land", -1, 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_VALIDATION))
                .andExpect(jsonPath("$.errors['items[0].allergenMask']").value("Allergen mask must be non-negative"));
        assertThat(titleOf(f.tenant, f.skuA)).isEqualTo(originalTitle(f.skuA));
    }

    // =====================================================================
    // API-13 — the shop branch can actually create a shop
    // =====================================================================

    /**
     * RED on the unfixed tree: 400 {@code errors/missing-field} ("slug") and zero rows. The
     * slug must be derived the way ShopService.createShop derives it (kebab-cased name plus a
     * random suffix), the row must belong to the caller's tenant, and — the sole-writer
     * invariant T-18-05-T — a brand-new shop is never born published.
     */
    @Test
    void shopCreateDerivesASlug_andIsNeverBornPublished() throws Exception {
        Fixture f = seed();
        String name = "Sync Made Shop " + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(shopJson(name, "2 Sync Street, London")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT slug, published, tenant_id::text AS tenant_id, address FROM shops WHERE tenant_id = ? AND name = ?",
                f.tenant, name);
        assertThat((String) row.get("slug"))
                .as("slug is derived from the name exactly as ShopService.generateSlug does")
                .isNotBlank()
                .startsWith("sync-made-shop-");
        assertThat((Boolean) row.get("published")).isFalse();
        assertThat(row.get("tenant_id")).isEqualTo(f.tenant.toString());
        assertThat(row.get("address")).isEqualTo("2 Sync Street, London");
    }

    // =====================================================================
    // M1 (PR #726 review) — the shop upsert key is (tenant, name), never name alone
    // =====================================================================

    /**
     * PR #726 review M1: {@code upsertShop} resolved the row with the tenant-less
     * {@code ShopRepository.findByName}. Under {@code shops_public_read} ({@code published = true
     * OR tenant_id = current_tenant_id()}) that lookup also sees every OTHER tenant's PUBLISHED
     * shop of the same name, so with the caller's own shop present too the query returns two
     * rows. RED on the unfixed tree: 500 (IncorrectResultSizeDataAccessException), the batch is
     * rolled back and the caller's shop is never updated. Fixed: the caller's own shop is the one
     * updated and the foreign shop is byte-identical afterwards.
     */
    @Test
    void sameNamedPublishedForeignShop_doesNotBlockUpdatingTheCallersOwnShop() throws Exception {
        Fixture f = seed();
        String shopAName = jdbc.queryForObject("SELECT name FROM shops WHERE id = ?", String.class, f.shopA);
        UUID foreignShop = seedForeignPublishedShop(shopAName);

        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(shopJson(shopAName, "7 Sync Updated Road")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1));

        assertThat(jdbc.queryForObject("SELECT address FROM shops WHERE id = ?", String.class, f.shopA))
                .as("the caller's OWN shop of that name is the one the batch updates")
                .isEqualTo("7 Sync Updated Road");
        assertThat(jdbc.queryForObject("SELECT address FROM shops WHERE id = ?", String.class, foreignShop))
                .as("the foreign tenant's same-named shop is untouched")
                .isEqualTo("1 Test Street, London");
    }

    /**
     * M1, the other half: the caller has NO shop of that name and only a foreign tenant's
     * PUBLISHED shop carries it. RED on the unfixed tree: {@code findByName} resolves the FOREIGN
     * shop, the SEC-5 gate then refuses it (the FC-1 tenant proof — a 404/403, never a create),
     * so the caller can never sync-create a shop of that name. Fixed: a GROUP_ADMIN sync creates
     * a NEW shop under the caller's tenant (never born published, T-18-05-T) and the foreign shop
     * is untouched.
     */
    @Test
    void sameNamedPublishedForeignShop_doesNotBlockCreatingTheCallersShop() throws Exception {
        Fixture f = seed();
        String name = "Shared Name " + UUID.randomUUID().toString().substring(0, 8);
        UUID foreignShop = seedForeignPublishedShop(name);

        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(shopJson(name, "3 Sync Street, London")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1));

        Map<String, Object> created = jdbc.queryForMap(
                "SELECT id::text AS id, published, address FROM shops WHERE tenant_id = ? AND name = ?",
                f.tenant, name);
        assertThat(created.get("id")).as("a NEW shop under the caller's tenant, not the foreign row")
                .isNotEqualTo(foreignShop.toString());
        assertThat((Boolean) created.get("published")).isFalse();
        assertThat(created.get("address")).isEqualTo("3 Sync Street, London");

        Map<String, Object> foreign = jdbc.queryForMap(
                "SELECT address, published FROM shops WHERE id = ?", foreignShop);
        assertThat(foreign.get("address")).as("the foreign shop is untouched").isEqualTo("1 Test Street, London");
        assertThat((Boolean) foreign.get("published")).isTrue();
    }

    // =====================================================================
    // Wire compatibility — the edge forwards []map[string]interface{} verbatim
    // =====================================================================

    /** Typing the item must not tighten the wire: unknown keys (and the root tenantId) are ignored, not 400. */
    @Test
    void unknownItemKeysAreIgnored() throws Exception {
        Fixture f = seed();
        String body = "{\"tenantId\":\"" + f.tenant + "\",\"items\":[{\"type\":\"PRODUCT\",\"sku\":\"" + f.skuA
                + "\",\"title\":\"extra-keys-ok\",\"ingredientsText\":\"Rice\",\"allergenMask\":0,"
                + "\"pricePennies\":120,\"edgeMeta\":{\"source\":\"pos\"},\"unknownFlag\":true}]}";
        mockMvc.perform(post(SYNC)
                        .with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1));
        assertThat(titleOf(f.tenant, f.skuA)).isEqualTo("extra-keys-ok");
    }

    // ---------------------------------------------------------------------
    // fixture
    // ---------------------------------------------------------------------

    /** One tenant, shops A and B with one product each, and a human holding SHOP_MANAGER on A only. */
    private record Fixture(UUID tenant, UUID shopA, UUID shopB, String skuA, String skuB, UUID manager) { }

    private Fixture seed() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Sync Authz Tenant " + tenant);
        String tag = tenant.toString().substring(0, 8);
        UUID shopA = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Shop A " + tag)).getId());
        UUID shopB = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Shop B " + tag)).getId());
        String skuA = "A-SKU-" + tag;
        String skuB = "B-SKU-" + tag;
        asRealmAdmin(tenant, () -> productService.createProduct(productRequest(shopA, skuA)).getId());
        asRealmAdmin(tenant, () -> productService.createProduct(productRequest(shopB, skuB)).getId());

        UUID manager = UUID.randomUUID();
        // Seeded directly, as ShopAccessEnforcementIntegrationTest.grantShopStaff does. Because the
        // manager holds an explicit grant, ShopAccessService neither JIT-provisions them nor treats
        // them as the day-one implicit GROUP_ADMIN — they are genuinely scoped under strict-scoping OFF.
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, 'SHOP_MANAGER', now())",
                UUID.randomUUID(), tenant, manager, shopA);
        return new Fixture(tenant, shopA, shopB, skuA, skuB, manager);
    }

    /**
     * A second tenant holding a PUBLISHED shop of the given name — the row {@code shops_public_read}
     * makes visible to every other tenant. Seeded through the real service (valid graph, real slug),
     * then published directly: the sole-writer invariant means createShop never publishes.
     */
    private UUID seedForeignPublishedShop(String name) {
        UUID foreignTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                foreignTenant, "Foreign Tenant " + foreignTenant);
        UUID shop = asRealmAdmin(foreignTenant, () -> shopService.createShop(shopRequest(name)).getId());
        jdbc.update("UPDATE shops SET published = true WHERE id = ?", shop);
        return shop;
    }

    private static String originalTitle(String sku) {
        return "Product " + sku;
    }

    private String titleOf(UUID tenant, String sku) {
        return jdbc.queryForObject("SELECT title FROM products WHERE tenant_id = ? AND sku = ?",
                String.class, tenant, sku);
    }

    private static CreateShopRequest shopRequest(String name) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName(name);
        req.setAddress("1 Test Street, London");
        return req;
    }

    private static CreateProductRequest productRequest(UUID shopId, String sku) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku(sku);
        req.setTitle(originalTitle(sku));
        req.setIngredientsText("Test ingredients");
        req.setAllergenMask(0);
        req.setPricePennies(500L);
        req.setShopId(shopId);
        return req;
    }

    /**
     * Run a seeding action as a fresh realm-admin under the given tenant, then clear the context.
     *
     * <p>Installs a NEW context object ({@code createEmptyContext()} + {@code setContext()}) rather
     * than {@code getContext().setAuthentication()}, and the difference decides whether the bare
     * {@code perform()} in {@link #unauthenticatedCallerIs401} is anonymous at all. spring-security-test's
     * per-test listener calls {@code TestSecurityContextHolder.getContext()} BEFORE the test body,
     * which falls back to — and CACHES — the empty object {@code SecurityContextHolder} is holding.
     * Mutating that same object in place authenticates the cached copy; {@code clearContext()} only
     * drops the holder's reference. MockMvc's default {@code testSecurityContext()} post-processor then
     * serves the cached, now-authenticated object to any request that does not carry its own
     * {@code jwt()}. Measured: the "unauthenticated" arm ran as the seeding admin (403 from the scope
     * gate; 400 missing-tenant without a header), not 401.
     */
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

    private static String productJson(String sku, String title, int allergenMask, long pricePennies) {
        return "{\"items\":[{\"type\":\"product\",\"sku\":\"" + sku + "\",\"title\":\"" + title
                + "\",\"ingredientsText\":\"Rice, tomatoes\",\"allergenMask\":" + allergenMask
                + ",\"pricePennies\":" + pricePennies + "}]}";
    }

    private static String shopJson(String name, String address) {
        return "{\"items\":[{\"type\":\"shop\",\"name\":\"" + name + "\",\"address\":\"" + address + "\"}]}";
    }

    /** A vendor-user token: UUID subject + tenant + the given scope claim (null = no scope claim at all). */
    private static RequestPostProcessor scopedJwt(UUID sub, UUID tenant, String scope) {
        return jwt().jwt(j -> {
                    j.subject(sub.toString())
                            .claim("tenant_id", tenant.toString())
                            .claim("email", "user-" + sub + "@example.com");
                    if (scope != null) {
                        j.claim("scope", scope);
                    }
                })
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** The scoped human: shop-A SHOP_MANAGER carrying the operator default scopes (core-api defaultClientScopes). */
    private static RequestPostProcessor managerJwt(Fixture f) {
        return scopedJwt(f.manager, f.tenant, "catalog:read catalog:write");
    }

    /** A write-scoped realm-admin (implicit GROUP_ADMIN) — the dashboard/operator token shape. */
    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "operator@example.com")
                        .claim("scope", "catalog:read catalog:write")
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new JwtRolesAndScopesConverter());
    }
}
