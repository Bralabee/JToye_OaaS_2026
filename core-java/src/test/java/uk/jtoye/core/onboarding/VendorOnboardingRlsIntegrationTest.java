package uk.jtoye.core.onboarding;

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
 * Proves RLS <em>enforcement</em> (not just app-layer scoping) on the V43
 * vendor-onboarding tables, on real Postgres 15 (Testcontainers). Mirrors the
 * canonical NOSUPERUSER pattern of {@code ReviewsRlsPolicyIntegrationTest}:
 *
 * <ul>
 *   <li>seed two tenants A + B (one {@code vendor_onboarding} + one
 *       {@code vendor_onboarding_gate} each) as the bootstrap SUPERUSER, which
 *       bypasses RLS — so the FK targets exist before privileges drop;</li>
 *   <li>{@code SET LOCAL ROLE} to a freshly-provisioned
 *       {@code NOSUPERUSER NOBYPASSRLS} role for each RLS-sensitive statement, so
 *       {@code FORCE ROW LEVEL SECURITY} is actually applied (a SUPERUSER would
 *       bypass even FORCE);</li>
 *   <li>drive the tenant GUC through {@link TenantContext} so the production
 *       {@code TenantSetLocalAspect} issues
 *       {@code set_config('app.current_tenant_id', ?, true)} — the exact runtime
 *       path the app uses.</li>
 * </ul>
 *
 * <p>Three behaviours, one {@code @Test} each: (1) cross-tenant read isolation,
 * (2) forged-tenant write rejected by {@code WITH CHECK}, (3) the {@code _aud}
 * {@code tenant_id IS NULL} predicate makes Envers NULL-tenant rows readable
 * under any tenant GUC.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class VendorOnboardingRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";

    @Autowired private JdbcTemplate jdbc;

    private UUID tenantA;
    private UUID tenantB;
    private UUID onboardingA;
    private UUID onboardingB;

    @BeforeEach
    void seed() {
        // Provision the non-superuser role (idempotent). Runs inside the test
        // transaction and rolls back with it, so each method re-creates it.
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
        onboardingA = UUID.randomUUID();
        onboardingB = UUID.randomUUID();

        // Registry rows (tenants has no RLS). Seeded as SUPERUSER, RLS bypassed.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        // One onboarding + one gate per tenant (shop_id NULL — FK is nullable).
        jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, model) VALUES (?, ?, 'MARKETPLACE')",
                onboardingA, tenantA);
        jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, model) VALUES (?, ?, 'MARKETPLACE')",
                onboardingB, tenantB);
        jdbc.update("INSERT INTO vendor_onboarding_gate (id, tenant_id, onboarding_id, gate_type) " +
                        "VALUES (?, ?, ?, 'FOOD_HYGIENE_RATING')",
                UUID.randomUUID(), tenantA, onboardingA);
        jdbc.update("INSERT INTO vendor_onboarding_gate (id, tenant_id, onboarding_id, gate_type) " +
                        "VALUES (?, ?, ?, 'FOOD_HYGIENE_RATING')",
                UUID.randomUUID(), tenantB, onboardingB);

        // An Envers-style _aud row with tenant_id NULL (needs a revinfo FK target).
        jdbc.update("INSERT INTO revinfo (rev, revtstmp) VALUES (?, ?)", 987654, System.currentTimeMillis());
        jdbc.update("INSERT INTO vendor_onboarding_aud (id, rev, revtype, tenant_id) VALUES (?, ?, 0, NULL)",
                UUID.randomUUID(), 987654);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Drop SUPERUSER for the rest of the transaction so FORCE RLS is applied. */
    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    /**
     * (1) Information-disclosure guard (T-18-01-I): under tenant A's GUC a SELECT
     * over vendor_onboarding returns only A's row — B's compliance state is invisible.
     */
    @Test
    void crossTenantReadReturnsOnlyCallerTenantRows() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        Integer visibleOnboardings = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding", Integer.class);
        assertThat(visibleOnboardings).isEqualTo(1);

        Integer bRowsLeaked = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding WHERE tenant_id = ?", Integer.class, tenantB);
        assertThat(bRowsLeaked).isZero();

        // The child gate table is tenant-isolated too.
        Integer visibleGates = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_gate", Integer.class);
        assertThat(visibleGates).isEqualTo(1);
    }

    /**
     * (2) Tampering guard (T-18-01-T): an INSERT that forges tenant_id = B while
     * the GUC = A is rejected by the WITH CHECK clause. Uses gate_type
     * BUSINESS_VERIFIED on A's (visible) onboarding to avoid the
     * UNIQUE(onboarding_id, gate_type) collision — the only failure is RLS.
     */
    @Test
    void forgedTenantInsertIsRejectedByWithCheck() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO vendor_onboarding_gate (id, tenant_id, onboarding_id, gate_type) " +
                        "VALUES (?, ?, ?, 'BUSINESS_VERIFIED')",
                UUID.randomUUID(), tenantB, onboardingA))
                .isInstanceOf(DataAccessException.class)
                // Postgres reports "new row violates row-level security policy";
                // Spring wraps it, so match the wrapped chain, not the outer message.
                .hasStackTraceContaining("row-level security");
    }

    /**
     * (3) Envers NULL-tenant predicate (trust boundary: audit writer → _aud): a
     * vendor_onboarding_aud row with tenant_id NULL is readable under any tenant
     * GUC — proving the {@code tenant_id IS NULL OR tenant_id = current_setting(...)}
     * branch — while still tenant-filtering non-NULL audit reads.
     */
    @Test
    void auditNullTenantRowVisibleUnderAnyTenantGuc() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();
        Integer underA = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_aud WHERE tenant_id IS NULL", Integer.class);
        assertThat(underA).isEqualTo(1);

        // Switch the tenant GUC to B — the NULL-tenant audit row stays visible.
        TenantContext.set(tenantB);
        Integer underB = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_aud WHERE tenant_id IS NULL", Integer.class);
        assertThat(underB).isEqualTo(1);
    }
}
