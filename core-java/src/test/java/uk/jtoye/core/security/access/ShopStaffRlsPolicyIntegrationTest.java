package uk.jtoye.core.security.access;

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
 * VSA-01 (T-23-01-01 / T-23-01-02) — the RLS ENFORCEMENT proof for the V52
 * vendor-scoped-access data layer: {@code shop_staff} (ENABLE+FORCE RLS via
 * {@code current_tenant_id()}) and the {@code user_directory} grant-target picker
 * (D-09), which holds {@code email} PII — so a cross-tenant read of that table is
 * a disclosure, and FORCE RLS is load-bearing there.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses
 * even FORCE ROW LEVEL SECURITY, so this class mirrors the
 * {@code WebhookSubscriptionRlsPolicyIntegrationTest} house pattern EXACTLY: it
 * provisions a dedicated {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS LOGIN)
 * and, inside each RLS-sensitive transaction, downgrades with
 * {@code SET LOCAL ROLE rls_test_role} so the V52 policies actually fire. The
 * tenant GUC is driven by {@link TenantContext} via {@code TenantSetLocalAspect}
 * (the same path production code uses; its {@code @Before} on JdbcTemplate re-pins
 * the GUC before every query).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ShopStaffRlsPolicyIntegrationTest {

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
    /** A recognizable PII marker inside tenant A's user_directory row. */
    private static final String TENANT_A_EMAIL = "tenant-a-pii-marker@example.com";

    private UUID tenantA;
    private UUID tenantB;
    /** The Keycloak sub seeded under tenant A — the cross-tenant probe target. */
    private UUID tenantAUserSub;

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
        tenantAUserSub = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed tenant A rows (as superuser: FORCE RLS bypassed here). TenantContext
        // drives the aspect's set_config so the rows are written under tenant A's GUC.
        TenantContext.set(tenantA);
        try {
            // A tenant-wide GROUP_ADMIN grant (shop_id NULL); created_at DEFAULTs now().
            jdbc.update(
                    "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role) "
                            + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN')",
                    UUID.randomUUID(), tenantA, tenantAUserSub);
            // A directory row carrying email PII; last_seen DEFAULTs now().
            jdbc.update(
                    "INSERT INTO user_directory (tenant_id, user_id, email, display_name) "
                            + "VALUES (?, ?, ?, ?)",
                    tenantA, tenantAUserSub, TENANT_A_EMAIL, "Tenant A Admin");
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
     * Cross-tenant read isolation: with tenant B's GUC set under the downgraded
     * role, tenant A's shop_staff grant is entirely invisible — RLS returns 0 rows.
     */
    @Test
    void tenantB_cannotSeeTenantAStaffRow() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE user_id = ?",
                Integer.class, tenantAUserSub);

        assertThat(visible).as("tenant A's shop_staff grant is hidden from tenant B").isZero();
    }

    /**
     * Cross-tenant write isolation: under tenant B's GUC, inserting a shop_staff
     * row tagged with tenant A's id violates the policy WITH CHECK and is denied.
     */
    @Test
    void tenantB_cannotForgeTenantAStaffRow() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN')",
                UUID.randomUUID(), tenantA, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    /**
     * PII disclosure proof: {@code user_directory.email} is PII, so FORCE RLS must
     * hide tenant A's directory row from tenant B entirely — a cross-tenant read of
     * the email marker returns 0 rows.
     */
    @Test
    void tenantB_cannotReadTenantADirectoryEmail() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM user_directory WHERE email = ?",
                Integer.class, TENANT_A_EMAIL);

        assertThat(visible).as("tenant A's directory email PII is hidden from tenant B").isZero();
    }
}
