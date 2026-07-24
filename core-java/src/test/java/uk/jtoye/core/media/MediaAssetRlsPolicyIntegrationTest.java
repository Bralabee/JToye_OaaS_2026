package uk.jtoye.core.media;

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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMG-01 (T-24-05) — the RLS ENFORCEMENT proof for the V53 media_asset CoW layer:
 * {@code media_asset} and the {@code product_media} join both carry ENABLE+FORCE
 * RLS via the safe {@code current_tenant_id()} helper, so a cross-tenant read of an
 * asset or a link is impossible and a cross-tenant forge is denied by the policy
 * WITH CHECK — the tenant wall behind safe vendor image sharing.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses even
 * FORCE ROW LEVEL SECURITY, so this class mirrors {@code ShopStaffRlsPolicyIntegrationTest}
 * EXACTLY: it provisions a dedicated {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS
 * LOGIN) and, inside each RLS-sensitive transaction, downgrades with
 * {@code SET LOCAL ROLE rls_test_role} so the V53 policies actually fire. The tenant
 * GUC is driven by {@link TenantContext} via {@code TenantSetLocalAspect} (the same
 * path production code uses).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class MediaAssetRlsPolicyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private JdbcTemplate jdbc;

    private static final String RLS_TEST_ROLE = "rls_test_role";
    /** A 64-char hex sha256 marker for tenant A's asset — the cross-tenant probe target. */
    private static final String TENANT_A_SHA = "a".repeat(64);

    private UUID tenantA;
    private UUID tenantB;
    private UUID tenantAProductId;
    private UUID tenantAAssetId;
    private UUID tenantAProductMediaId;

    @BeforeEach
    void seed() {
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
        tenantAProductId = UUID.randomUUID();
        tenantAAssetId = UUID.randomUUID();
        tenantAProductMediaId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed tenant A rows as superuser (FORCE RLS bypassed here). TenantContext drives
        // the aspect's set_config so the rows are written under tenant A's GUC.
        TenantContext.set(tenantA);
        try {
            jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    tenantAProductId, tenantA, "MEDIA-RLS-1", "RLS Product", "Yam (100%)");
            jdbc.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                    + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE')",
                    tenantAAssetId, tenantA, tenantA + "/media/" + tenantAAssetId + ".webp", TENANT_A_SHA);
            jdbc.update("INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order) "
                    + "VALUES (?, ?, ?, ?, true, 0)",
                    tenantAProductMediaId, tenantA, tenantAProductId, tenantAAssetId);
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

    @Test
    void tenantB_cannotSeeTenantAMediaAsset() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE sha256 = ?", Integer.class, TENANT_A_SHA);

        assertThat(visible).as("tenant A's media_asset is hidden from tenant B").isZero();
    }

    @Test
    void tenantB_cannotSeeTenantAProductMedia() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM product_media WHERE id = ?", Integer.class, tenantAProductMediaId);

        assertThat(visible).as("tenant A's product_media link is hidden from tenant B").isZero();
    }

    @Test
    void tenantB_cannotForgeTenantAMediaAsset() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE')",
                UUID.randomUUID(), tenantA, "forged/key.webp", "b".repeat(64)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void tenantB_cannotForgeTenantAProductMedia() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        // Uses tenant A's REAL product/asset ids so the FK checks (which bypass RLS)
        // pass and the policy WITH CHECK on tenant_id is the sole reason for denial.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, false, 1)",
                UUID.randomUUID(), tenantA, tenantAProductId, tenantAAssetId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }
}
