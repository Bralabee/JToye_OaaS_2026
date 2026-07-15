package uk.jtoye.core.notification.consent;

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
 * Phase 22 COMMS-03 — the RLS ENFORCEMENT proof for BOTH V54 consent tables
 * ({@code notification_suppression} and {@code marketing_opt_in}, ENABLE+FORCE
 * RLS via the {@code current_tenant_id()} helper). {@code recipient} is an email
 * address (PII), so a cross-tenant read of either table would be a PII
 * disclosure — these are disclosure tests, not ceremony.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses
 * even FORCE ROW LEVEL SECURITY, so this class mirrors
 * {@code IdempotencyKeysRlsPolicyIntegrationTest} EXACTLY: it provisions a
 * dedicated {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS LOGIN) and, inside
 * each RLS-sensitive transaction, downgrades with {@code SET LOCAL ROLE
 * rls_test_role} so the V54 policies actually fire. The tenant GUC is driven by
 * {@link TenantContext} via {@code TenantSetLocalAspect} (the same path
 * production code uses) on each JdbcTemplate call within the active transaction.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ConsentTablesRlsPolicyIntegrationTest {

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
    /** A recognizable PII marker inside tenant A's rows. */
    private static final String TENANT_A_RECIPIENT = "tenant-a-secret@example.com";
    private static final String CATEGORY = "ORDERS";

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

        // tenants registry is not RLS-scoped; seed as superuser.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // Seed one suppression + one marketing_opt_in row for tenant A (as
        // superuser: FORCE RLS bypassed here). TenantContext drives the aspect's
        // set_config so the rows are written under tenant A's GUC.
        TenantContext.set(tenantA);
        try {
            jdbc.update("INSERT INTO notification_suppression (id, tenant_id, recipient, category) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), tenantA, TENANT_A_RECIPIENT, CATEGORY);
            jdbc.update("INSERT INTO marketing_opt_in (id, tenant_id, recipient) VALUES (?, ?, ?)",
                    UUID.randomUUID(), tenantA, TENANT_A_RECIPIENT);
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

    // ---------- notification_suppression ----------

    @Test
    void tenantB_cannotReadTenantASuppression() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM notification_suppression WHERE recipient = ?",
                Integer.class, TENANT_A_RECIPIENT);

        assertThat(visible).as("tenant A's suppression row is hidden from tenant B").isZero();
    }

    @Test
    void sameRecipientDifferentTenant_freshSuppressionInsert() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        int inserted = jdbc.update(
                "INSERT INTO notification_suppression (id, tenant_id, recipient, category) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, recipient, category) DO NOTHING",
                UUID.randomUUID(), tenantB, TENANT_A_RECIPIENT, CATEGORY);

        assertThat(inserted).as("tenant B's same recipient/category is a fresh row, not tenant A's").isEqualTo(1);
    }

    @Test
    void tenantB_cannotForgeTenantASuppressionRow() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification_suppression (id, tenant_id, recipient, category) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tenantA, "forged@example.com", CATEGORY))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    // ---------- marketing_opt_in ----------

    @Test
    void tenantB_cannotReadTenantAMarketingOptIn() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM marketing_opt_in WHERE recipient = ?",
                Integer.class, TENANT_A_RECIPIENT);

        assertThat(visible).as("tenant A's marketing opt-in row is hidden from tenant B").isZero();
    }

    @Test
    void sameRecipientDifferentTenant_freshMarketingOptInInsert() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        int inserted = jdbc.update(
                "INSERT INTO marketing_opt_in (id, tenant_id, recipient) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, recipient) DO NOTHING",
                UUID.randomUUID(), tenantB, TENANT_A_RECIPIENT);

        assertThat(inserted).as("tenant B's same recipient is a fresh opt-in, not tenant A's").isEqualTo(1);
    }

    @Test
    void tenantB_cannotForgeTenantAMarketingOptInRow() {
        TenantContext.set(tenantB);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO marketing_opt_in (id, tenant_id, recipient) VALUES (?, ?, ?)",
                UUID.randomUUID(), tenantA, "forged@example.com"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }
}
