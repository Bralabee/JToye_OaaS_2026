package uk.jtoye.core.marketing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit follow-up (LOW): confirm the vendor-marketing table
 * {@code shop_promotions} is genuinely tenant-isolated at the database layer —
 * the V33 policies read {@code app.current_tenant_id} (the GUC that
 * {@link uk.jtoye.core.security.TenantSetLocalAspect} sets) and V35
 * {@code FORCE ROW LEVEL SECURITY} closes the owner-bypass hole.
 *
 * <p>Runs against Testcontainers PostgreSQL (NOT H2) so real RLS policies are
 * exercised. Mirrors the established {@code ReviewsRlsPolicyIntegrationTest}
 * pattern: a dedicated NOSUPERUSER / NOBYPASSRLS role is provisioned so RLS
 * actually fires (the postgres:15 test user is SUPERUSER and would bypass
 * every policy otherwise).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ShopPromotionsRlsPolicyIntegrationTest {

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
        // Flyway-managed schema is the sole source of truth for RLS state.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;

    private static final String RLS_TEST_ROLE = "rls_test_role";

    private UUID tenantA;
    private UUID tenantB;
    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void seed() {
        // Provision a dedicated non-superuser role (idempotent) so RLS fires.
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        shopA = UUID.randomUUID();
        shopB = UUID.randomUUID();

        // tenants registry is not RLS-scoped; seed as superuser.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed a shop + one promotion for each tenant. TenantContext drives the
        // aspect's set_config('app.current_tenant_id', ?, true) — the same path
        // production code uses — so the WITH CHECK passes on insert.
        TenantContext.set(tenantA);
        try {
            seedShop(shopA, tenantA, "promo-shop-a-" + shopA.toString().substring(0, 8), true);
            seedPromotion(tenantA, shopA, "10% off A");
        } finally {
            TenantContext.clear();
        }
        // Tenant B's shop is UNPUBLISHED so the cross-tenant read assertion below
        // exercises the tenant-id clause of the V33 policy rather than the
        // public-storefront OR-branch (a published shop is deliberately readable
        // across tenants — see publishedShopPromotionsAreReadableAcrossTenants).
        TenantContext.set(tenantB);
        try {
            seedShop(shopB, tenantB, "promo-shop-b-" + shopB.toString().substring(0, 8), false);
            seedPromotion(tenantB, shopB, "20% off B");
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    /**
     * Cross-tenant read isolation: with tenant A's GUC set, only tenant A's
     * promotion is visible — tenant B's row (on an <em>unpublished</em> shop)
     * is filtered out by the V33 {@code shop_promotions_read} SELECT policy
     * (the V27→V33 fix that replaced the old {@code USING(true)} hole). The
     * tenant GUC is driven by {@link TenantContext} via
     * {@link uk.jtoye.core.security.TenantSetLocalAspect}, the same path the
     * production storefront/marketing services use.
     */
    @Test
    void tenantAOnlySeesOwnPromotions() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        Integer own = jdbc.queryForObject(
                "SELECT count(*) FROM shop_promotions WHERE tenant_id = ?", Integer.class, tenantA);
        Integer other = jdbc.queryForObject(
                "SELECT count(*) FROM shop_promotions WHERE tenant_id = ?", Integer.class, tenantB);
        Integer all = jdbc.queryForObject(
                "SELECT count(*) FROM shop_promotions", Integer.class);

        assertThat(own).isEqualTo(1);
        assertThat(other).isZero();   // tenant B's promotion is invisible to tenant A
        assertThat(all).isEqualTo(1); // RLS filters the read to A's row only
    }

    /**
     * Cross-tenant write isolation: under tenant A's GUC, attempting to insert
     * a promotion tagged with tenant B's tenant_id violates the
     * {@code shop_promotions_write} WITH CHECK clause and is denied.
     */
    @Test
    void crossTenantInsertIsBlocked() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shop_promotions (id, tenant_id, shop_id, label, discount_percent, " +
                        "  valid_from, valid_until, active, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '30 days', true, NOW())",
                UUID.randomUUID(), tenantB, shopB, "Spoofed promo", 50))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    /**
     * Public-storefront read path (documented V33 behaviour): promotions on a
     * <em>published</em> shop are intentionally readable across tenants so
     * anonymous storefront pages can render them. This is the OR-branch of the
     * V33 {@code shop_promotions_read} policy — it complements, rather than
     * contradicts, the tenant isolation asserted in
     * {@link #tenantAOnlySeesOwnPromotions()} (which covers unpublished shops).
     */
    @Test
    void publishedShopPromotionsAreReadableAcrossTenants() {
        // Tenant B publishes a shop with a promotion (seeded as superuser).
        UUID publishedShopB = UUID.randomUUID();
        TenantContext.set(tenantB);
        try {
            seedShop(publishedShopB, tenantB,
                    "promo-shop-b-pub-" + publishedShopB.toString().substring(0, 8), true);
            seedPromotion(tenantB, publishedShopB, "Public 15% off B");
        } finally {
            TenantContext.clear();
        }

        // Tenant A (a different tenant) can still read tenant B's PUBLISHED-shop
        // promotion via the storefront OR-branch of the policy.
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();
        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM shop_promotions WHERE shop_id = ?", Integer.class, publishedShopB);
        assertThat(visible).isEqualTo(1);
    }

    // --- helpers ---

    private void seedShop(UUID id, UUID tenant, String slug, boolean published) {
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, " +
                        "  delivery_fee_pennies) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, tenant, "shop-" + id, slug, "Test Address", published);
    }

    private void seedPromotion(UUID tenant, UUID shop, String label) {
        jdbc.update("INSERT INTO shop_promotions (id, tenant_id, shop_id, label, discount_percent, " +
                        "  valid_from, valid_until, active, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '30 days', true, NOW())",
                UUID.randomUUID(), tenant, shop, label, 10);
    }
}
