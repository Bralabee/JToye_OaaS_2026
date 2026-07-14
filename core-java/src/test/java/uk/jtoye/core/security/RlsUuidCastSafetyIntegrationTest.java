package uk.jtoye.core.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Issue #113 [P3-11] regression proof: no RLS policy that reads the tenant GUC
 * still crashes with 22P02 when the GUC is empty / 'default' / malformed.
 *
 * <p>V51 migrated ten policies off the raw
 * {@code current_setting('app.current_tenant_id', true)::uuid} cast onto the
 * safe helper {@code current_tenant_id()} (the same fix V39 applied to the three
 * storefront SELECT policies) AND hardened the helper's own final cast. A
 * malformed tenant GUC must now fail-<em>filtered</em> (NULL → no rows) rather
 * than fail-<em>errored</em> (22P02 → HTTP 409/500).
 *
 * <p>Runs against Testcontainers Postgres 15 (NOT H2) so the real V51 migration
 * is exercised, and downgrades to a dedicated NOSUPERUSER / NOBYPASSRLS role via
 * {@code SET LOCAL ROLE} (the postgres:15 bootstrap user is SUPERUSER and would
 * bypass every policy otherwise) — the canonical pattern shared with
 * {@code ShopPromotionsRlsPolicyIntegrationTest}.
 *
 * <p><strong>Why {@code doWork} for the malformed cases:</strong>
 * {@link uk.jtoye.core.security.TenantSetLocalAspect} advises every
 * {@code JdbcTemplate} call and, when {@link TenantContext} is empty, runs
 * {@code SET LOCAL app.current_tenant_id TO DEFAULT} — which would overwrite any
 * malformed GUC we injected before the assertion query could see it (canonising
 * everything to the empty string). To feed a genuinely non-UUID value (the case
 * that proves the {@code current_tenant_id()} cast hardening — not just the
 * empty-string policy fix), the malformed test drops to the raw transaction
 * connection via {@code Session.doWork}, which the aspect's
 * {@code JdbcTemplate} pointcut does not match. The valid-GUC isolation test
 * uses {@link TenantContext} (a UUID it can legitimately carry) so the aspect
 * applies it exactly as production does.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class RlsUuidCastSafetyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String RLS_TEST_ROLE = "rls_test_role";

    /**
     * Every table whose tenant policy V51 migrated off the raw {@code ::uuid}
     * cast. A malformed GUC must leave a {@code SELECT count(*)} on each of them
     * succeeding-with-zero, never raising 22P02.
     */
    private static final List<String> CAST_MIGRATED_TABLES = List.of(
            "payment_event_outbox",
            "reviews",
            "refunds",
            "refunds_aud",
            "vendor_onboarding",
            "vendor_onboarding_gate",
            "vendor_onboarding_aud",
            "vendor_onboarding_gate_aud",
            "processed_order_events",
            "idempotency_keys"
    );

    @BeforeEach
    void provisionRlsRole() {
        // Dedicated non-superuser role (idempotent) so RLS actually fires.
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    /**
     * The core #113 proof: under RLS, an empty / 'default' / non-UUID tenant GUC
     * both (a) makes {@code current_tenant_id()} return NULL rather than raise,
     * and (b) filters every previously raw-cast table to zero rows instead of
     * raising {@code 22P02 invalid input syntax for type uuid}. Run on the raw
     * connection so the {@code 'not-a-uuid'} value genuinely reaches the helper's
     * hardened cast guard (see class Javadoc); on the pre-V51 schema every
     * iteration was RED.
     */
    @Test
    void malformedGucFiltersInsteadOf22P02() {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (Statement role = connection.createStatement()) {
                role.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
            }

            for (String badGuc : List.of("", "default", "not-a-uuid")) {
                try (PreparedStatement setGuc = connection.prepareStatement(
                        "SELECT set_config('app.current_tenant_id', ?, true)")) {
                    setGuc.setString(1, badGuc);
                    setGuc.execute();
                }

                // (a) the hardened helper fails-filtered (NULL), never errors.
                try (Statement fn = connection.createStatement();
                     ResultSet rs = fn.executeQuery("SELECT current_tenant_id()")) {
                    rs.next();
                    rs.getObject(1);
                    assertThat(rs.wasNull())
                            .as("current_tenant_id() must return NULL for GUC '%s', not raise 22P02", badGuc)
                            .isTrue();
                }

                // (b) every migrated policy filters to zero rows without crashing.
                for (String table : CAST_MIGRATED_TABLES) {
                    try (Statement q = connection.createStatement();
                         ResultSet rs = q.executeQuery("SELECT count(*) FROM " + table)) {
                        rs.next();
                        assertThat(rs.getInt(1))
                                .as("SELECT count(*) FROM %s with tenant GUC '%s' must filter (0), not raise 22P02",
                                        table, badGuc)
                                .isZero();
                    }
                }
            }
        });
    }

    /**
     * Tenant isolation is unchanged under a VALID GUC: the recreated
     * {@code payment_event_outbox_tenant} policy still enforces tenant equality,
     * so tenant A sees its own row and tenant B sees none. Proves V51 preserved
     * semantics rather than merely suppressing the error. Drives the GUC through
     * {@link TenantContext} + {@code TenantSetLocalAspect} — the production path.
     */
    @Test
    void tenantIsolationPreservedUnderValidGuc() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Seed the registry + one outbox row for tenant A as superuser (RLS bypassed).
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "cast-safety-a-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "cast-safety-b-" + tenantB);
        jdbc.update("INSERT INTO payment_event_outbox " +
                        "(id, tenant_id, event_type, routing_key, payload) " +
                        "VALUES (?, ?, 'ORDER_PAID', 'payment.order.paid', '{}')",
                UUID.randomUUID(), tenantA);

        dropSuperuserForTransaction();

        TenantContext.set(tenantA);
        Integer aVisible = jdbc.queryForObject(
                "SELECT count(*) FROM payment_event_outbox", Integer.class);
        assertThat(aVisible).as("tenant A sees its own outbox row").isEqualTo(1);

        TenantContext.set(tenantB);
        Integer bVisible = jdbc.queryForObject(
                "SELECT count(*) FROM payment_event_outbox", Integer.class);
        assertThat(bVisible).as("tenant B sees none of tenant A's rows").isZero();
    }

    /**
     * The reviews write path (the one #113 policy that guards INSERT, not read):
     * with an empty tenant GUC and no {@code app.customer_email} set, a review
     * INSERT is denied CLEANLY by row-level security. The empty-GUC state is the
     * genuine production anonymous path — {@code TenantSetLocalAspect} resets the
     * GUC to its default (empty string) before the JdbcTemplate INSERT because
     * {@link TenantContext} is unset. Pre-V51 the same INSERT blew up with
     * {@code invalid input syntax for type uuid} before the policy could even
     * evaluate — this asserts the error is now an RLS denial, never a 22P02.
     */
    @Test
    void reviewsWriteDeniedCleanlyWithEmptyGuc() {
        dropSuperuserForTransaction();

        // All NOT NULL / CHECK columns satisfied so ExecConstraints passes and
        // the RLS WITH CHECK is the sole gate; the shop/order FKs fire as AFTER
        // triggers (after RLS), so arbitrary ids are fine — RLS denies first.
        Throwable thrown = catchThrowable(() -> jdbc.update(
                "INSERT INTO reviews (tenant_id, shop_id, order_id, customer_email, food_rating) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "nobody@example.com", 5));

        assertThat(thrown)
                .as("empty tenant GUC must deny the reviews INSERT via RLS")
                .isInstanceOf(DataAccessException.class);

        String trace = stackTraceOf(thrown);
        assertThat(trace)
                .as("denial must be a clean row-level-security rejection")
                .contains("row-level security");
        assertThat(trace)
                .as("denial must NOT be the pre-V51 22P02 uuid-cast crash")
                .doesNotContain("invalid input syntax for type uuid");
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
