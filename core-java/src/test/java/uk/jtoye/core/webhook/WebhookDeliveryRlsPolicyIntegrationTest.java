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
 * COMMS-05 (T-22-05-07) — the RLS ENFORCEMENT proof for the V56
 * {@code webhook_delivery} store (ENABLE+FORCE RLS via {@code current_tenant_id()}).
 * The {@code payload} column carries a full-entity snapshot (OrderDto etc. —
 * customer PII), so a cross-tenant read of this table would be a PII disclosure —
 * these are disclosure tests, not ceremony.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses
 * even FORCE ROW LEVEL SECURITY, so this class mirrors the
 * {@code WebhookSubscriptionRlsPolicyIntegrationTest} / {@code IdempotencyKeysRlsPolicyIntegrationTest}
 * house pattern EXACTLY: it provisions a dedicated {@code rls_test_role}
 * (NOSUPERUSER NOBYPASSRLS LOGIN) and, inside each RLS-sensitive transaction,
 * downgrades with {@code SET LOCAL ROLE rls_test_role} so the V56 policy actually
 * fires. The tenant GUC is driven by {@link TenantContext} via
 * {@code TenantSetLocalAspect} (the same path production code uses).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class WebhookDeliveryRlsPolicyIntegrationTest {

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
    /** A recognizable PII marker inside tenant A's stored payload. */
    private static final String TENANT_A_MARKER = "tenant-a-secret@example.com";

    private UUID tenantA;
    private UUID tenantB;
    private UUID subscriptionA;

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
        subscriptionA = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed one PENDING webhook_delivery for tenant A (as superuser: FORCE RLS
        // bypassed here). TenantContext drives the aspect's set_config so the row
        // is written under tenant A's GUC.
        TenantContext.set(tenantA);
        try {
            jdbc.update(
                    "INSERT INTO webhook_delivery (tenant_id, subscription_id, event_id, event_type, payload) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    tenantA, subscriptionA, UUID.randomUUID(), "order.state.changed",
                    "{\"data\":{\"customerEmail\":\"" + TENANT_A_MARKER + "\"}}");
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
     * role, tenant A's delivery row (holding customer PII in its payload) is
     * entirely invisible — RLS returns 0 rows.
     */
    @Test
    void tenantB_cannotReadTenantADelivery() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE payload LIKE ?",
                Integer.class, "%" + TENANT_A_MARKER + "%");

        assertThat(visible).as("tenant A's delivery row is hidden from tenant B").isZero();
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
                "INSERT INTO webhook_delivery (tenant_id, subscription_id, event_id, event_type, payload) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tenantA, subscriptionA, UUID.randomUUID(), "order.state.changed", "{\"forged\":true}"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }
}
