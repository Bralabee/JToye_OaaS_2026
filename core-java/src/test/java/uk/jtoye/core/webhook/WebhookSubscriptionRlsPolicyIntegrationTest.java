package uk.jtoye.core.webhook;

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
 * COMMS-04 (T-22-03-02) — the RLS ENFORCEMENT proof for the V55
 * {@code webhook_subscription} store (ENABLE+FORCE RLS via {@code current_tenant_id()}).
 * The store holds {@code signing_secret} in plaintext, so a cross-tenant read of
 * this table would be a credential disclosure — these are disclosure tests, not
 * ceremony.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses
 * even FORCE ROW LEVEL SECURITY, so this class mirrors the
 * {@code IdempotencyKeysRlsPolicyIntegrationTest} house pattern EXACTLY: it
 * provisions a dedicated {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS LOGIN)
 * and, inside each RLS-sensitive transaction, downgrades with
 * {@code SET LOCAL ROLE rls_test_role} so the V55 policy actually fires. The
 * tenant GUC is driven by {@link TenantContext} via {@code TenantSetLocalAspect}
 * (the same path production code uses).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class WebhookSubscriptionRlsPolicyIntegrationTest {

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
    /** A recognizable secret marker inside tenant A's row. */
    private static final String TENANT_A_SECRET = "tenant-a-signing-secret-marker";

    private UUID tenantA;
    private UUID tenantB;

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

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed one webhook_subscription for tenant A (as superuser: FORCE RLS
        // bypassed here). TenantContext drives the aspect's set_config so the row
        // is written under tenant A's GUC.
        TenantContext.set(tenantA);
        try {
            jdbc.update(
                    "INSERT INTO webhook_subscription (tenant_id, target_url, event_types, signing_secret) "
                            + "VALUES (?, ?, '{ORDER_STATE_CHANGED}'::text[], ?)",
                    tenantA, "https://tenant-a.example.com/hook", TENANT_A_SECRET);
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
     * role, tenant A's subscription (holding the signing_secret) is entirely
     * invisible — RLS returns 0 rows.
     */
    @Test
    void tenantB_cannotListTenantASubscription() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_subscription WHERE signing_secret = ?",
                Integer.class, TENANT_A_SECRET);

        assertThat(visible).as("tenant A's subscription is hidden from tenant B").isZero();
    }

    /**
     * Cross-tenant write isolation: under tenant B's GUC, inserting a row tagged
     * with tenant A's id violates the policy WITH CHECK and is denied.
     */
    @Test
    void tenantB_cannotForgeTenantARow() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO webhook_subscription (tenant_id, target_url, event_types, signing_secret) "
                        + "VALUES (?, ?, '{ORDER_STATE_CHANGED}'::text[], ?)",
                tenantA, "https://forged.example.com/hook", "forged-secret"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }
}
