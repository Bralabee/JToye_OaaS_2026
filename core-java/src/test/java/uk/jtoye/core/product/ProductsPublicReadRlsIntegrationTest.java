package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V89 {@code products_public_read}: what an anonymous caller (no tenant pinned) may now read from
 * {@code products}, and what it still may not, proven under a {@code NOSUPERUSER NOBYPASSRLS} role
 * ({@code rls_test_role}). The Testcontainers default user is a SUPERUSER and bypasses FORCE RLS, so
 * every assertion here first drops to that role.
 *
 * <p>Allowed: exactly the rows {@code GET /shops/{slug}/products} already serves (an available product
 * of a published shop). Not allowed: an unavailable product, any product of an unpublished shop, any
 * write, and (the {@code current_tenant_id() IS NULL} guard) any foreign product for a caller that
 * DOES carry a tenant.
 *
 * <p>The last group pins the audit finding that came with the policy: {@link ProductService}'s
 * GROUP_ADMIN list and search branches ran an UNPREDICATED read and relied on RLS alone, so a realm
 * admin or declared system caller with no tenant would, after V89, have listed every tenant's published
 * products. They now answer an empty page when no tenant is pinned, which is what RLS answered before.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ProductsPublicReadRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
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
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductService productService;

    private UUID tenantA;
    private UUID tenantB;
    private UUID publishedShopA;
    private UUID unpublishedShopB;
    private UUID availableA;
    private UUID unavailableA;
    private UUID availableOnUnpublishedB;

    @BeforeEach
    void seed() {
        jdbc.execute("DO $$ BEGIN "
                + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN "
                + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; "
                + "  END IF; "
                + "  GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "  GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "  GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantA, "ppr-a-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantB, "ppr-b-" + tenantB);

        publishedShopA = seedShop(tenantA, true);
        unpublishedShopB = seedShop(tenantB, false);
        availableA = seedProduct(tenantA, publishedShopA, "Jollof rice", true);
        unavailableA = seedProduct(tenantA, publishedShopA, "Moi moi", false);
        availableOnUnpublishedB = seedProduct(tenantB, unpublishedShopB, "Suya", true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- reads ----

    @Test
    @DisplayName("anonymous sees an AVAILABLE product of a PUBLISHED shop")
    void anonymousSeesAvailableProductOfPublishedShop() {
        dropToAnonymousRuntimeRole();
        assertThat(visible(availableA)).isEqualTo(1);
    }

    @Test
    @DisplayName("anonymous does NOT see an unavailable product of a published shop")
    void anonymousDoesNotSeeUnavailableProduct() {
        dropToAnonymousRuntimeRole();
        assertThat(visible(unavailableA)).isZero();
    }

    @Test
    @DisplayName("anonymous does NOT see an available product of an UNPUBLISHED shop")
    void anonymousDoesNotSeeUnpublishedShopsProduct() {
        dropToAnonymousRuntimeRole();
        assertThat(visible(availableOnUnpublishedB)).isZero();
    }

    @Test
    @DisplayName("anonymous does NOT see a tenant-B product pointed at tenant A's published shop (cross-tenant shop_id)")
    void anonymousDoesNotSeeCrossTenantProductOnAPublishedShop() {
        // products.shop_id is a plain foreign key (V20): nothing in the schema stops a tenant-B row
        // naming tenant A's shop (the FC-1 class, refused only at the application layer). The menu
        // endpoint pins A's tenant and never shows such a row, so the public policy must not either.
        // Seeded as the superuser, because the application refuses to write it.
        UUID foreign = seedProduct(tenantB, publishedShopA, "Foreign egusi", true);
        dropToAnonymousRuntimeRole();
        assertThat(visible(foreign)).isZero();
    }

    @Test
    @DisplayName("anonymous sees nothing beyond those rows: the whole visible table is the one product")
    void anonymousVisibleSetIsExactlyThePublicMenu() {
        dropToAnonymousRuntimeRole();
        Long total = jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id IN (?, ?)",
                Long.class, tenantA, tenantB);
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("a caller pinned to ANOTHER tenant does not see tenant A's published products (IS NULL guard)")
    void tenantPinnedCallerDoesNotSeeForeignPublishedProducts() {
        TenantContext.set(tenantB);
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
        assertThat(visible(availableA)).isZero();
        // and its own (unpublished-shop) product stays visible to it through products_rls_policy
        assertThat(visible(availableOnUnpublishedB)).isEqualTo(1);
    }

    @Test
    @DisplayName("a MALFORMED (non-UUID) tenant setting sees no product: it fails filtered, never as anonymous")
    void malformedTenantSettingFailsFiltered() {
        // V51's current_tenant_id() returns NULL for a non-UUID setting so that it filters instead of
        // raising 22P02. A public-read branch keyed on `current_tenant_id() IS NULL` would therefore read
        // a malformed setting as "anonymous" and open the public menu to it. The branch must key on the
        // raw setting being empty / 'default' / unset, so a malformed one sees nothing.
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', 'not-a-uuid', true)");
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
        // the setting really is malformed when the read runs (not reset by anything in between)
        assertThat(jdbc.queryForObject("SELECT current_setting('app.current_tenant_id', true)", String.class))
                .isEqualTo("not-a-uuid");
        Long total = jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id IN (?, ?)",
                Long.class, tenantA, tenantB);
        assertThat(total).as("products visible under a malformed app.current_tenant_id").isZero();
    }

    // ---- writes: the policy is FOR SELECT only ----

    @Test
    @DisplayName("anonymous INSERT into products is refused by row-level security (42501)")
    void anonymousInsertRefused() {
        dropToAnonymousRuntimeRole();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, available) "
                        + "VALUES (?, ?, ?, 'ANON-1', 'Injected', 'x', true)",
                UUID.randomUUID(), tenantA, publishedShopA))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    @Test
    @DisplayName("anonymous UPDATE of a publicly readable product matches 0 rows")
    void anonymousUpdateMatchesZeroRows() {
        dropToAnonymousRuntimeRole();
        int updated = jdbc.update("UPDATE products SET price_pennies = 1 WHERE id = ?", availableA);
        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("anonymous DELETE of a publicly readable product matches 0 rows")
    void anonymousDeleteMatchesZeroRows() {
        dropToAnonymousRuntimeRole();
        int deleted = jdbc.update("DELETE FROM products WHERE id = ?", availableA);
        assertThat(deleted).isZero();
    }

    // ---- audit finding: ProductService GROUP_ADMIN branches with no tenant ----

    @Test
    @DisplayName("ProductService list as a GROUP_ADMIN-equivalent caller with NO tenant returns nothing")
    void productServiceListWithNoTenantReturnsNothing() {
        dropToAnonymousRuntimeRole();
        assertThat(SystemPrincipal.asSystem(() ->
                productService.getAllProducts(PageRequest.of(0, 50)).getTotalElements())).isZero();
        assertThat(SystemPrincipal.asSystem(() ->
                productService.getAvailableProducts(PageRequest.of(0, 50)).getTotalElements())).isZero();
    }

    @Test
    @DisplayName("ProductService search as a GROUP_ADMIN-equivalent caller with NO tenant returns nothing")
    void productServiceSearchWithNoTenantReturnsNothing() {
        dropToAnonymousRuntimeRole();
        assertThat(SystemPrincipal.asSystem(() ->
                productService.search("jollof", PageRequest.of(0, 50)).getTotalElements())).isZero();
    }

    @Test
    @DisplayName("control: the same GROUP_ADMIN-equivalent list WITH tenant A pinned returns tenant A's products")
    void productServiceListWithTenantStillWorks() {
        TenantContext.set(tenantA);
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
        assertThat(SystemPrincipal.asSystem(() ->
                productService.getAllProducts(PageRequest.of(0, 50)).getTotalElements())).isEqualTo(2);
        assertThat(SystemPrincipal.asSystem(() ->
                productService.search("jollof", PageRequest.of(0, 50)).getTotalElements())).isEqualTo(1);
    }

    // ---- helpers ----

    private void dropToAnonymousRuntimeRole() {
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', true)");
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    private long visible(UUID productId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Long.class, productId);
        return n == null ? -1 : n;
    }

    private UUID seedShop(UUID tenantId, boolean published) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, '1 Test Street', ?, 0)",
                id, tenantId, "PPR Shop " + id, "ppr-" + id, published);
        return id;
    }

    private UUID seedProduct(UUID tenantId, UUID shopId, String title, boolean available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, available) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, shopId, "PPR-" + id.toString().substring(0, 8), title, title, available);
        return id;
    }
}
