package uk.jtoye.core.common.idempotency;

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
 * Issue #204 (AI-2) BLOCKER 1 — the RLS ENFORCEMENT proof for the
 * {@code idempotency_keys} store (V50, ENABLE+FORCE RLS). The store's
 * {@code response_body} carries serialized DTOs that for orders include
 * customer PII (customerName / customerEmail / customerPhone), so a cross-tenant
 * read of this table would be a PII disclosure — these are disclosure tests,
 * not ceremony.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses
 * even FORCE ROW LEVEL SECURITY, so this class mirrors the
 * {@code ShopPromotionsRlsPolicyIntegrationTest} house pattern EXACTLY: it
 * provisions a dedicated {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS LOGIN)
 * and, inside each RLS-sensitive transaction, downgrades with
 * {@code SET LOCAL ROLE rls_test_role} so the V50 policy actually fires. The
 * tenant GUC is driven by {@link TenantContext} via {@code TenantSetLocalAspect}
 * (the same path production code uses), applied on each JdbcTemplate call within
 * the active transaction.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class IdempotencyKeysRlsPolicyIntegrationTest {

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
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;

    private static final String RLS_TEST_ROLE = "rls_test_role";
    private static final String ENDPOINT = "orders.create";
    private static final String SHARED_KEY = "shared-tenant-key-204";
    /** A recognizable PII marker inside tenant A's stored response_body. */
    private static final String TENANT_A_MARKER_EMAIL = "tenant-a-secret@example.com";

    private UUID tenantA;
    private UUID tenantB;

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

        // tenants registry is not RLS-scoped; seed as superuser.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed a COMPLETED idempotency_keys row for tenant A (as superuser: FORCE
        // RLS bypassed here, WITH CHECK not enforced). TenantContext drives the
        // aspect's set_config so the row is written under tenant A's GUC.
        TenantContext.set(tenantA);
        try {
            jdbc.update(
                    "INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash, response_status, response_body) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    tenantA, ENDPOINT, SHARED_KEY, "hash-a",
                    201, "{\"customerEmail\":\"" + TENANT_A_MARKER_EMAIL + "\"}");
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
     * role, tenant A's completed idempotency_keys row (holding PII in
     * {@code response_body}) is entirely invisible — RLS returns 0 rows.
     */
    @Test
    void tenantB_cannotReadTenantAKey() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE endpoint = ? AND idempotency_key = ?",
                Integer.class, ENDPOINT, SHARED_KEY);

        assertThat(visible).as("tenant A's key row is hidden from tenant B").isZero();
    }

    /**
     * Same key + different tenant = FRESH reserve, never a replay of tenant A's
     * stored PII: the composite PK {@code (tenant_id, endpoint, key)} plus RLS
     * mean tenant B's identical key is a brand-new row (1 inserted).
     */
    @Test
    void sameKeyDifferentTenant_freshCreateNotReplay() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                tenantB, ENDPOINT, SHARED_KEY, "hash-b");

        assertThat(inserted).as("tenant B's same key is a fresh reserve, not tenant A's replay").isEqualTo(1);
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
                "INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                tenantA, ENDPOINT, "forged-key", "hash-x"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }
}
