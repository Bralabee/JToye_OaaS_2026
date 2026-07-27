package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-3.6 — {@link MediaQuarantineRetentionSweep} is tenant-scoped under a NOSUPERUSER role.
 *
 * <h2>Why the whole application datasource is downgraded, from the first connection</h2>
 * The Testcontainers user is a SUPERUSER, and <b>superusers bypass RLS entirely</b>, including
 * {@code FORCE ROW LEVEL SECURITY}. Run as that user this criterion passes whatever the sweep does
 * with the tenant GUC, and its break cannot fire — a vacuous criterion.
 *
 * <p>Two approaches were tried and rejected, recorded so they are not re-attempted:
 * <ol>
 *   <li>{@code SET LOCAL ROLE} inside the test (the {@code MediaProcessingWorkerIntegrationTest}
 *       pattern) — cannot work, because the sweep opens its OWN transactions on connections it
 *       takes from the pool, so a role set on the test's connection never reaches them.</li>
 *   <li>{@code ALTER ROLE … SET role} + {@code softEvictConnections()} — <b>measurably flaky</b>
 *       (1 failure in 3 runs). Soft eviction retires idle connections but does not kill in-use
 *       ones, so a connection opened BEFORE the ALTER could still be handed to the sweep. The VOID
 *       guard caught it every time rather than passing vacuously, which is exactly what a VOID
 *       guard is for — but a flaky criterion is not a criterion.</li>
 * </ol>
 *
 * <p>Instead the application datasource authenticates as {@code rls_sweep_role} (NOSUPERUSER,
 * NOBYPASSRLS) from its very first connection, while <b>Flyway</b> keeps the superuser so the
 * migrations can still create schema. {@code ALTER DEFAULT PRIVILEGES} is issued before the context
 * starts, so every table Flyway then creates is automatically granted to the downgraded role. No
 * eviction, no race.
 *
 * <p>Seeding and read-back use a SEPARATE superuser {@link JdbcTemplate} — the app datasource is
 * subject to RLS and could not insert another tenant's row even to set the fixture up.
 *
 * <p><b>The break is the interesting half.</b> Removing {@code TenantContext.set(tenantId)} from
 * the sweep leaves {@code findReclaimableQuarantine} returning ZERO rows under FORCE RLS, so the
 * failure mode is "nothing to do" — indistinguishable from a clean run without this assertion.
 *
 * <p><b>Note on which break is load-bearing.</b> Deleting the sweep's explicit
 * {@code pinTenantGuc} alone does NOT fail: {@code TenantSetLocalAspect} pins
 * {@code app.current_tenant_id} from {@link TenantContext} before every Spring Data repository call
 * inside an active transaction, so the explicit pin is defence-in-depth layered under a global
 * aspect. The property that actually determines scoping is the {@code TenantContext} set, and that
 * is what the recorded break removes.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaSweepTenantScopeIntegrationTest {

    private static final String SWEEP_ROLE = "rls_sweep_role";
    private static final String SWEEP_PW = "rls_sweep_pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Creates the downgraded role and pre-grants it, THEN points the application datasource at it
     * while leaving Flyway as the superuser. Runs before the context starts, so the app is
     * NOSUPERUSER from connection one.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        try (Connection su = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = su.createStatement()) {
            st.execute("DO $$ BEGIN "
                    + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + SWEEP_ROLE + "') THEN "
                    + "    CREATE ROLE " + SWEEP_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '"
                    + SWEEP_PW + "'; "
                    + "  END IF; "
                    + "END $$");
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + SWEEP_ROLE);
            // Every table/sequence Flyway creates next is granted automatically.
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + postgres.getUsername()
                    + " IN SCHEMA public GRANT ALL ON TABLES TO " + SWEEP_ROLE);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + postgres.getUsername()
                    + " IN SCHEMA public GRANT ALL ON SEQUENCES TO " + SWEEP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("could not provision the downgraded role", e);
        }

        // Layer ON the shared helper, never replace it: it also points the H2-defaulted
        // application-test.yml at Postgres (driver-class-name, dialect, ddl-auto none, flyway
        // enabled) and disables the broker. Registering url/user/password alone left the H2 driver
        // in place — "Driver org.h2.Driver claims to not accept jdbcUrl" — and no schema.
        // Later add() calls for the same key win, so the two overrides below take effect.
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);

        registry.add("spring.datasource.username", () -> SWEEP_ROLE);
        registry.add("spring.datasource.password", () -> SWEEP_PW);
        // Flyway keeps the SUPERUSER — the downgraded role must not own the schema, or FORCE RLS
        // would apply to it as owner and the migrations themselves would be filtered.
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired private MediaQuarantineRetentionSweep sweep;
    @SpyBean private StorageService storageService;

    /** Superuser template — the app datasource is RLS-bound and cannot seed another tenant's row. */
    private JdbcTemplate su;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seed() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        su = new JdbcTemplate(ds);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        for (UUID t : new UUID[]{tenantA, tenantB}) {
            su.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    t, "test-" + t);
        }
        Mockito.doReturn(true).when(storageService).deleteByKeyChecked(Mockito.anyString());
    }

    @AfterEach
    void cleanUp() {
        for (UUID t : new UUID[]{tenantA, tenantB}) {
            su.update("DELETE FROM media_asset_aud WHERE tenant_id = ?", t);
            su.update("DELETE FROM media_asset WHERE tenant_id = ?", t);
        }
        TenantContext.clear();
        Mockito.reset(storageService);
    }

    @Test
    @DisplayName("AC-3.6: the sweep reads through the RLS wall via its own per-tenant tenant pin")
    void sweepIsTenantScopedUnderNosuperuser() {
        UUID assetA = seedReclaimable(tenantA, tenantA + "/quarantine/a.jpg");
        UUID assetB = seedReclaimable(tenantB, tenantB + "/quarantine/b.jpg");

        assertDowngradeIsReal();   // VOID guard — without it the whole test is vacuous

        sweep.sweep();

        // Each tenant's rows were reachable ONLY because the sweep pinned that tenant. Without the
        // pin, FORCE RLS hides everything and NEITHER is stamped — "nothing to do", which is
        // exactly why this has to be asserted rather than assumed.
        assertThat(reclaimedAt(assetA))
                .as("tenant A's row must be stamped — reached THROUGH the wall, not around it")
                .isNotNull();
        assertThat(reclaimedAt(assetB))
                .as("tenant B's row is reached by ITS own pinned pass, never by tenant A's")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-3.6 (isolation): under the downgrade, a pin to A cannot see B's rows")
    void tenantGucIsolatesUnderNosuperuser() {
        seedReclaimable(tenantA, tenantA + "/quarantine/a.jpg");
        UUID assetB = seedReclaimable(tenantB, tenantB + "/quarantine/b.jpg");

        assertDowngradeIsReal();

        // One connection for the pin AND both reads: outside a transaction each JdbcTemplate call
        // takes its own pooled connection, so a session-level set_config would not apply to a
        // count issued separately.
        int[] visible = appProbe("SELECT set_config('app.current_tenant_id', '" + tenantA + "', false)",
                "SELECT count(*) FROM media_asset WHERE id = '" + assetB + "'",
                "SELECT count(*) FROM media_asset WHERE tenant_id = '" + tenantA + "'");

        assertThat(visible[0]).as("tenant B's asset is invisible under a pin to A").isZero();
        assertThat(visible[1])
                .as("tenant A's own asset IS visible — proving the wall filters rather than hiding "
                        + "everything, which a zero-everywhere result could not distinguish")
                .isEqualTo(1);
    }

    // ---- guards & helpers --------------------------------------------------

    /**
     * VOID guard. If the app datasource is not actually NOSUPERUSER, RLS is bypassed and every
     * assertion here would hold regardless of what the sweep does. "It looked clean" is not
     * evidence that a wall exists.
     */
    private void assertDowngradeIsReal() {
        String user;
        boolean superuser;
        try (Connection c = appConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT current_user, current_setting('is_superuser')::boolean")) {
            rs.next();
            user = rs.getString(1);
            superuser = rs.getBoolean(2);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(user).as("VOID: the app datasource is not the downgraded role").isEqualTo(SWEEP_ROLE);
        assertThat(superuser).as("VOID: still superuser — RLS does not apply").isFalse();

        int unpinned = appProbe("SELECT set_config('app.current_tenant_id', '', false)",
                "SELECT count(*) FROM media_asset",
                "SELECT 0")[0];
        assertThat(unpinned)
                .as("VOID: FORCE RLS is not filtering — an unpinned read must return zero rows")
                .isZero();
    }

    /** Runs a set-up statement and two counts on ONE app (downgraded) connection. */
    private int[] appProbe(String setup, String countOne, String countTwo) {
        try (Connection c = appConnection(); Statement st = c.createStatement()) {
            st.execute(setup);
            int a;
            int b;
            try (ResultSet rs = st.executeQuery(countOne)) { rs.next(); a = rs.getInt(1); }
            try (ResultSet rs = st.executeQuery(countTwo)) { rs.next(); b = rs.getInt(1); }
            return new int[]{a, b};
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), SWEEP_ROLE, SWEEP_PW);
    }

    private UUID seedReclaimable(UUID tenant, String key) {
        UUID id = UUID.randomUUID();
        su.update("""
                INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type,
                                         status, flagged, quarantine_expires_at)
                VALUES (?, ?, ?, ?, 'image/jpeg', 'FAILED', false, ?)
                """, id, tenant, key, randomSha(), OffsetDateTime.now().minusHours(1));
        return id;
    }

    private OffsetDateTime reclaimedAt(UUID assetId) {
        java.sql.Timestamp ts = su.queryForObject(
                "SELECT quarantine_reclaimed_at FROM media_asset WHERE id = ?",
                java.sql.Timestamp.class, assetId);
        return ts == null ? null : ts.toInstant().atOffset(OffsetDateTime.now().getOffset());
    }

    private static String randomSha() {
        return (UUID.randomUUID().toString().replace("-", "") + "0".repeat(64)).substring(0, 64);
    }
}
