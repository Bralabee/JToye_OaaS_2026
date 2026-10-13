package uk.jtoye.core.product;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 37-42 (RWO-040 / UXT-040): "show me one shop's menu" returns that shop's menu, only what is on
 * sale, with the allergens in words.
 *
 * <p>The persona's agent asked {@code list_products} for one shop and got the tenant's whole catalogue
 * (the tool dropped {@code shopId}), sold-out products included, with allergens as a bare integer. Core
 * already narrows by {@code ?shopId=}; this adds {@code ?availableOnly=true}, applied in the QUERY so the
 * page and its {@code totalElements} describe only products on sale (a filter over one fetched page would
 * leave the total counting what it hid), and {@code ProductDto.allergens}.
 *
 * <p>Real filter chain, Testcontainers Postgres, the real {@link ShopAccessService} with strict scoping
 * ON, so the scoped manager's grant-set query is exercised too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class ProductListAvailableOnlyIntegrationTest {

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
    @Autowired ShopAccessService shopAccessService;

    private Object bootedStrictScoping;
    private UUID tenant;
    private UUID shopA;
    private UUID shopB;
    private UUID managerOfA;

    /** Shop A: on sale (Gluten + Milk), and sold out. */
    private UUID aOnSale;
    private UUID aSoldOut;
    /** Shop B: on sale (mask 0), and sold out. */
    private UUID bOnSale;
    private UUID bSoldOut;
    /** Legacy tenant-wide (shop_id NULL): on sale, and sold out. */
    private UUID legacyOnSale;
    private UUID legacySoldOut;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);

        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenant, "37-42 Tenant " + tenant);
        shopA = seedShop("a");
        shopB = seedShop("b");
        managerOfA = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, 'SHOP_MANAGER', now(), 'OPERATOR')", UUID.randomUUID(), tenant, managerOfA, shopA);

        aOnSale = seedProduct(shopA, true, 0b100_0001);
        aSoldOut = seedProduct(shopA, false, 1);
        bOnSale = seedProduct(shopB, true, 0);
        bSoldOut = seedProduct(shopB, false, 0);
        legacyOnSale = seedProduct(null, true, 0);
        legacySoldOut = seedProduct(null, false, 0);
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-040: ?shopId=A&availableOnly=true is only A's products on sale, counted in the query, with allergens named")
    void oneShopsMenuOnSaleWithNamedAllergens() throws Exception {
        JsonNode page = list(admin(), "shopId", shopA.toString(), "availableOnly", "true");

        assertThat(ids(page)).containsExactly(aOnSale);
        assertThat(page.path("totalElements").asLong())
                .as("filtered in the query: the total does not count the sold-out product")
                .isEqualTo(1);
        JsonNode product = byId(page).get(aOnSale);
        assertThat(names(product.path("allergens"))).containsExactly("Gluten", "Milk");
        assertThat(product.path("allergenMask").asInt()).isEqualTo(65);
    }

    @Test
    @DisplayName("The PAGE is filtered, not one fetched page: page 0 of size 1 is the on-sale product although newer sold-out ones sort first")
    void thePageItselfIsFilteredInTheQuery() throws Exception {
        // A filter applied to one already-fetched page cannot be told from a query filter by
        // totalElements alone on a short page: PageImpl recomputes the total from the content when
        // offset + size exceeds it (measured, break arm B6). Two NEWER sold-out products sort ahead of
        // the on-sale one under the default createdAt DESC, so a post-filter of page 0 is empty.
        UUID newerSoldOut1 = seedProduct(shopA, false, 0);
        UUID newerSoldOut2 = seedProduct(shopA, false, 0);
        jdbc.update("UPDATE products SET created_at = now() + interval '1 hour' WHERE id IN (?, ?)",
                newerSoldOut1, newerSoldOut2);

        JsonNode page = list(admin(), "shopId", shopA.toString(), "availableOnly", "true", "size", "1", "page", "0");
        JsonNode control = list(admin(), "shopId", shopA.toString(), "size", "1", "page", "0");

        assertThat(ids(control)).as("control: the newest product sorts first").containsAnyOf(newerSoldOut1, newerSoldOut2);
        assertThat(ids(page)).containsExactly(aOnSale);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("Without availableOnly the shop list is unchanged: sold-out products are still listed for the vendor")
    void withoutTheFlagTheShopListIsUnchanged() throws Exception {
        JsonNode page = list(admin(), "shopId", shopA.toString());

        assertThat(ids(page)).containsExactlyInAnyOrder(aOnSale, aSoldOut);
    }

    @Test
    @DisplayName("availableOnly=false is the same as no flag")
    void explicitFalseIsTheUnchangedList() throws Exception {
        JsonNode page = list(admin(), "shopId", shopA.toString(), "availableOnly", "false");

        assertThat(ids(page)).containsExactlyInAnyOrder(aOnSale, aSoldOut);
    }

    @Test
    @DisplayName("A Group admin's tenant-wide list with availableOnly drops every sold-out product; mask 0 reads as []")
    void groupAdminTenantListOnSaleOnly() throws Exception {
        JsonNode page = list(admin(), "availableOnly", "true");
        JsonNode control = list(admin());

        // The Testcontainers role is a SUPERUSER, which bypasses RLS, so a Group admin's tenant-wide
        // findAll also returns the rows earlier test methods seeded under their own tenants. The
        // assertions are therefore over THIS test's six rows, plus "nothing returned is sold out".
        Set<UUID> mine = Set.of(aOnSale, aSoldOut, bOnSale, bSoldOut, legacyOnSale, legacySoldOut);
        assertThat(ids(control).stream().filter(mine::contains))
                .as("control: without the flag the sold-out rows are listed")
                .containsExactlyInAnyOrder(aOnSale, aSoldOut, bOnSale, bSoldOut, legacyOnSale, legacySoldOut);
        assertThat(ids(page).stream().filter(mine::contains))
                .containsExactlyInAnyOrder(aOnSale, bOnSale, legacyOnSale);
        page.path("content").forEach(p -> assertThat(p.path("available").asBoolean())
                .as("every listed product is on sale: " + p.path("id")).isTrue());
        JsonNode bAllergens = byId(page).get(bOnSale).path("allergens");
        assertThat(bAllergens.isArray()).as(bAllergens.toString()).isTrue();
        assertThat(names(bAllergens)).isEmpty();
    }

    @Test
    @DisplayName("A shop manager's list with availableOnly is their shop plus legacy tenant-wide products on sale, never shop B's")
    void scopedManagerListOnSaleOnly() throws Exception {
        JsonNode filtered = list(manager(managerOfA), "availableOnly", "true");
        JsonNode control = list(manager(managerOfA));

        assertThat(ids(control)).as("control: the grant-set read is what it was")
                .containsExactlyInAnyOrder(aOnSale, aSoldOut, legacyOnSale, legacySoldOut);
        assertThat(ids(filtered)).containsExactlyInAnyOrder(aOnSale, legacyOnSale);
        assertThat(filtered.path("totalElements").asLong()).isEqualTo(2);
    }

    // ---- helpers ----

    private JsonNode list(RequestPostProcessor caller, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/products").with(caller);
        boolean sized = false;
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
            sized |= "size".equals(params[i]);
        }
        if (!sized) {
            request = request.param("size", "100");
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString())
                .isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Set<UUID> idSet(JsonNode page) {
        Set<UUID> out = new HashSet<>();
        page.path("content").forEach(p -> out.add(UUID.fromString(p.path("id").asString())));
        return out;
    }

    private static List<UUID> ids(JsonNode page) {
        return List.copyOf(idSet(page));
    }

    private static Map<UUID, JsonNode> byId(JsonNode page) {
        Map<UUID, JsonNode> out = new HashMap<>();
        page.path("content").forEach(p -> out.put(UUID.fromString(p.path("id").asString()), p));
        return out;
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    /** A realm admin of this tenant (implicit GROUP_ADMIN, D-03). */
    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_catalog:read"));
    }

    /** A person holding only an OPERATOR SHOP_MANAGER grant on shop A. */
    private RequestPostProcessor manager(UUID subject) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:read"));
    }

    private UUID seedShop(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "37-42 Shop " + suffix + " " + id, "shop-37-42-" + suffix + "-" + id);
        return id;
    }

    private UUID seedProduct(UUID shopId, boolean available, int mask) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, quantity_in_stock, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, 'see label', ?, 500, 0, ?, false, ?, NULL, 'STANDARD', 0)",
                id, tenant, "SKU-3742-" + id, "Product " + id, mask, available, shopId);
        return id;
    }
}
