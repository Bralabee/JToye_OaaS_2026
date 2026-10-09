package uk.jtoye.core.dev;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.config.DatabaseConfigurationValidator;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-03 (D-06, RWO-004): the dev seed gives the two integration service accounts an
 * EXPLICIT, inspectable shop grant, so MCP {@code create_order} and the catalogue read keep working
 * in compose once ungranted means no access.
 *
 * <p><strong>Why the accounts need a grant at all.</strong> Their token {@code sub} is the UUID of
 * the Keycloak service-account user in the realm import
 * ({@code infra/keycloak/realm-export.template.json}): {@code integration-orders-rw} is
 * {@code 5c0c16be-…}, {@code integration-catalog-ro} is {@code 9e0bf075-…}. A UUID subject is a
 * vendor user to {@code ShopAccessService} — the machine-client allowlist only ever applies to a
 * non-UUID subject — so the accounts reach a shop exactly like a person does. Under strict-scoping
 * OFF an ungranted user is an implicit tenant-wide GROUP_ADMIN; under ON it has no access: order
 * create is a typed 403 and the product list is an EMPTY page (a 200 that looks like an empty
 * catalogue, which is why the read path is asserted on content, not status).
 *
 * <p><strong>What is granted, and what is not.</strong> One OPERATOR row per curated demo
 * storefront: {@code integration-orders-rw} at SHOP_MANAGER (what {@code OrderService.createOrder}
 * requires; it also covers the STAFF reads behind the MCP read tools), and
 * {@code integration-catalog-ro} at STAFF (read only). Never a tenant-wide GROUP_ADMIN, and never
 * the hidden archive shop — the archive is the per-shop proof here: a create on it is still
 * refused under strict ON.
 *
 * <p>Context as in {@link DemoDataSeederTraderIdentityIntegrationTest}: the real {@code dev}
 * profile on a fresh Flyway-migrated database, so the seeder runs as the {@code ApplicationRunner}
 * it is at dev startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.geo.postcode-import.enabled=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}"})
@Tag("testcontainers")
class DemoDataSeederServiceAccountGrantIntegrationTest {

    private static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** service-account-integration-orders-rw (realm import users[]). */
    private static final UUID ORDERS_RW = UUID.fromString("5c0c16be-1aa0-4181-b808-2c7575f03b95");
    /** service-account-integration-catalog-ro (realm import users[]). */
    private static final UUID CATALOG_RO = UUID.fromString("9e0bf075-b61a-408e-800e-63d2e0bcb770");
    /** The three curated, published demo storefronts, in slug order. */
    private static final List<String> CURATED_SLUGS =
            List.of("brixton-village-grill", "mama-ades-kitchen", "peckham-jollof-co");
    private static final String ARCHIVE_SLUG = "unsorted-legacy-items";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // Boot brokerless: keep the Rabbit beans but point them at a dead port, listeners off.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @MockitoBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoDataSeeder demoDataSeeder;
    @Autowired private ShopAccessService shopAccessService;

    /** The value the context booted with: restored after every test, never a literal (37-02). */
    private Object bootedStrictScoping;

    @BeforeEach
    void captureStrictScoping() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
    }

    @AfterEach
    void restore() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    @Test
    void ordersRwHoldsAnOperatorShopManagerGrantOnEveryCuratedShopAndNothingTenantWide() {
        assertGrants(ORDERS_RW, "SHOP_MANAGER");
        assertDirectory(ORDERS_RW, "integration-orders-rw");
    }

    @Test
    void catalogRoHoldsAnOperatorStaffGrantOnEveryCuratedShopAndNothingTenantWide() {
        assertGrants(CATALOG_RO, "STAFF");
        assertDirectory(CATALOG_RO, "integration-catalog-ro");
    }

    @Test
    void reRunningTheSeederCreatesNoDuplicateGrantOrDirectoryRow() throws Exception {
        String before = serviceAccountSnapshot();
        assertThat(before).as("vacuity control: the snapshot holds the seeded rows").contains("SHOP_MANAGER");

        demoDataSeeder.run(null);

        assertThat(serviceAccountSnapshot()).isEqualTo(before);
    }

    @Test
    void underStrictScopingOnOrdersRwCreatesAnOrderOnAGrantedShopAndIsRefusedOnAnUngrantedOne()
            throws Exception {
        StrictScopingGuard.set(shopAccessService, true);

        UUID granted = shopId("mama-ades-kitchen");
        UUID product = jdbc.queryForObject(
                "SELECT id FROM products WHERE shop_id = ? AND available = true "
                        + "AND (quantity_in_stock IS NULL OR quantity_in_stock >= 1) ORDER BY sku LIMIT 1",
                UUID.class, granted);
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .with(ordersRwToken())
                        .contentType("application/json")
                        .content(orderJson(granted, product)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as("create on a granted shop: %s", created.getResponse().getContentAsString())
                .isEqualTo(201);

        // The archive shop is seeded in the same tenant and deliberately NOT granted: the gate
        // refuses before any product lookup, so a random product id is enough.
        UUID ungranted = shopId(ARCHIVE_SLUG);
        MvcResult refused = mockMvc.perform(post("/api/v1/orders")
                        .with(ordersRwToken())
                        .contentType("application/json")
                        .content(orderJson(ungranted, UUID.randomUUID())))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
                .as("create on an ungranted shop: %s", refused.getResponse().getContentAsString())
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("https://jtoye.uk/errors/shop-access-denied");
    }

    @Test
    void underStrictScopingOnCatalogRoStillReadsTheCuratedCatalogue() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);

        MvcResult list = mockMvc.perform(get("/api/v1/products").param("size", "200")
                        .with(catalogRoToken()))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        // A zero-grant caller gets 200 with an EMPTY page under strict ON, so the status alone
        // cannot tell the two apart: assert the curated products are actually in the body.
        Integer curatedProducts = jdbc.queryForObject(
                "SELECT count(*) FROM products p JOIN shops s ON s.id = p.shop_id "
                        + "WHERE s.tenant_id = ? AND s.slug IN ('brixton-village-grill','mama-ades-kitchen','peckham-jollof-co')",
                Integer.class, DEMO_TENANT);
        assertThat(curatedProducts).as("vacuity control: the seeder created curated products").isPositive();
        Integer total = com.jayway.jsonpath.JsonPath.read(list.getResponse().getContentAsString(), "$.totalElements");
        assertThat(total).as("catalog-ro sees the curated catalogue under strict ON").isEqualTo(curatedProducts);
    }

    // ---- assertions ---------------------------------------------------------

    private void assertGrants(UUID user, String role) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.slug, ss.role, ss.grant_source FROM shop_staff ss JOIN shops s ON s.id = ss.shop_id "
                        + "WHERE ss.tenant_id = ? AND ss.user_id = ? ORDER BY s.slug", DEMO_TENANT, user);
        assertThat(rows).extracting(r -> r.get("slug")).as("one grant per curated shop for %s", user)
                .containsExactlyElementsOf(CURATED_SLUGS);
        assertThat(rows).extracting(r -> r.get("role")).as("role").containsOnly(role);
        assertThat(rows).extracting(r -> r.get("grant_source")).as("an operator grant, honoured under strict ON")
                .containsOnly("OPERATOR");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ? AND shop_id IS NULL",
                Integer.class, DEMO_TENANT, user)).as("no tenant-wide grant for %s", user).isZero();
    }

    private void assertDirectory(UUID user, String displayName) {
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM user_directory WHERE tenant_id = ? AND user_id = ?",
                String.class, DEMO_TENANT, user)).isEqualTo(displayName);
    }

    /** Every service-account row the seeder owns, ids included, as one comparable string. */
    private String serviceAccountSnapshot() {
        List<String> grants = jdbc.queryForList(
                "SELECT id || '|' || user_id || '|' || coalesce(shop_id::text, '<null>') || '|' || role "
                        + "|| '|' || grant_source FROM shop_staff WHERE tenant_id = ? AND user_id IN (?, ?) ORDER BY id",
                String.class, DEMO_TENANT, ORDERS_RW, CATALOG_RO);
        List<String> directory = jdbc.queryForList(
                "SELECT user_id || '|' || coalesce(display_name, '<null>') FROM user_directory "
                        + "WHERE tenant_id = ? AND user_id IN (?, ?) ORDER BY user_id",
                String.class, DEMO_TENANT, ORDERS_RW, CATALOG_RO);
        return String.join("\n", grants) + "\n--\n" + String.join("\n", directory);
    }

    // ---- fixtures -----------------------------------------------------------

    private UUID shopId(String slug) {
        return jdbc.queryForObject("SELECT id FROM shops WHERE tenant_id = ? AND slug = ?",
                UUID.class, DEMO_TENANT, slug);
    }

    private static String orderJson(UUID shopId, UUID productId) {
        return "{\"shopId\":\"" + shopId + "\",\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}";
    }

    /** The integration-orders-rw client-credentials token shape: its service-account sub and scopes. */
    private static RequestPostProcessor ordersRwToken() {
        return jwt()
                .jwt(j -> j.subject(ORDERS_RW.toString())
                        .claim("tenant_id", DEMO_TENANT.toString())
                        .claim("azp", "integration-orders-rw")
                        .claim("scope", "orders:write customers:write catalog:read"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** The integration-catalog-ro client-credentials token shape: its service-account sub, read only. */
    private static RequestPostProcessor catalogRoToken() {
        return jwt()
                .jwt(j -> j.subject(CATALOG_RO.toString())
                        .claim("tenant_id", DEMO_TENANT.toString())
                        .claim("azp", "integration-catalog-ro")
                        .claim("scope", "catalog:read"))
                .authorities(new JwtRolesAndScopesConverter());
    }
}
