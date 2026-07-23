package uk.jtoye.core.security.access;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23 gap-closure (23-17) — regression proof for the V57 {@code grant_source}
 * backfill on a <em>non-fresh</em> database, the exact scenario every fresh
 * Testcontainers DB silently skips.
 *
 * <p><strong>The bug this pins.</strong> {@code shop_staff} carries ENABLE + FORCE
 * ROW LEVEL SECURITY (V52). V57 v1 backfilled provenance with a bare
 * {@code UPDATE shop_staff SET grant_source = ... WHERE grant_source IS NULL} — no
 * tenant GUC. In production Flyway runs as the RLS-bound app role
 * ({@code jtoye_app}, NOSUPERUSER, no {@code spring.flyway.user} override), so
 * {@code current_tenant_id()} returns NULL, the policy hides every row, and the
 * UPDATE touches ZERO rows. V57 then does {@code ALTER COLUMN grant_source SET NOT
 * NULL}, whose internal validation scan bypasses RLS, sees the still-NULL rows, and
 * FAILS — bricking boot on the canonical Compose dev DB and any staging/prod where
 * V52 shipped and a user has since made a write (creating a JIT {@code shop_staff}
 * row). The fix (23-17 Task 1) rewrites the backfill as V44's tenant-loop
 * {@code set_config} pattern so every row is reached under the policy.
 *
 * <p><strong>Why fresh Testcontainers DBs never caught it, and how this test does.</strong>
 * A fresh DB runs V52 then V57 back-to-back on an empty table, so there is nothing
 * to backfill and {@code SET NOT NULL} passes trivially. Worse, the Testcontainers
 * bootstrap role is a Postgres SUPERUSER, which bypasses even FORCE RLS — so running
 * V57 as it would MASK the bug (the bare UPDATE would see every row). This test
 * therefore does two things fresh DBs don't:
 *
 * <ol>
 *   <li>migrates to V56 (as the container superuser — the robust path; V1 needs an
 *       extension, V44 a superuser-only LEAKPROOF), stopping AFTER V52 creates
 *       {@code shop_staff} but BEFORE V57;</li>
 *   <li>seeds pre-V57 {@code shop_staff} rows across TWO tenants on the superuser
 *       connection (which bypasses RLS, so the rows land for both tenants);</li>
 *   <li>provisions {@code rls_migrator} — NOSUPERUSER NOBYPASSRLS, mirroring
 *       {@code jtoye_app} — hands it OWNERSHIP of the two {@code shop_staff} tables
 *       (so it can run V57's ALTERs) while it stays SUBJECT to FORCE RLS on the
 *       backfill UPDATE, and applies V57 as that role.</li>
 * </ol>
 *
 * <p>Against V57 v1 this reproduces the production failure: the no-GUC UPDATE sees
 * zero rows and {@code migrate()} throws a {@code FlywayException} at
 * {@code SET NOT NULL}. Against the tenant-loop V57 it applies cleanly and every
 * seeded row is backfilled with the correct provenance for BOTH tenants — proving
 * the loop reached rows across the whole registry, not just the first tenant.
 *
 * <p>Plain JUnit + Flyway API on purpose (mirrors {@code
 * FlywayV44OutOfOrderIntegrationTest}) — the scenario is about Flyway's behaviour
 * under an RLS-bound role, not the Spring context.
 */
@Testcontainers
@Tag("testcontainers")
class V57GrantSourceBackfillIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_migrator_pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_v57")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v57BackfillsPreExistingRowsAcrossTenantsUnderRls() throws Exception {
        // --- 1. Migrate to V56 as the container superuser (robust path). Stops AFTER
        //        V52 creates shop_staff (FORCE RLS) but BEFORE V57. ------------------
        Flyway toV56 = baseConfig(postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("56"))
                .load();
        toV56.migrate();
        assertThat(appliedVersions(toV56))
                .as("V52 shop_staff must be applied and V57 must NOT be yet")
                .contains("52")
                .doesNotContain("57");

        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        // Per tenant: one JIT row (created_by NULL) + one OPERATOR row (created_by set).
        UUID t1Jit = UUID.randomUUID();
        UUID t1Op = UUID.randomUUID();
        UUID t2Jit = UUID.randomUUID();
        UUID t2Op = UUID.randomUUID();
        UUID operatorSub = UUID.randomUUID();

        try (Connection su = connect(postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            // --- 2. Provision the RLS-bound migration role mirroring jtoye_app, and hand
            //        it ownership of the two shop_staff tables so it can run V57's ALTERs
            //        while STILL being subject to FORCE RLS on the backfill UPDATE. -----
            s.execute("DROP ROLE IF EXISTS " + RLS_MIGRATOR);
            s.execute("CREATE ROLE " + RLS_MIGRATOR
                    + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("ALTER TABLE shop_staff OWNER TO " + RLS_MIGRATOR);
            s.execute("ALTER TABLE shop_staff_aud OWNER TO " + RLS_MIGRATOR);

            // --- 3. Seed pre-V57 rows across TWO tenants (superuser bypasses RLS, so no
            //        per-tenant GUC needed). shop_id NULL = tenant-wide GROUP_ADMIN shape,
            //        which avoids needing a shops FK parent. ---------------------------
            s.execute("INSERT INTO tenants (id, name) VALUES "
                    + "('" + tenant1 + "', 'V57 Tenant 1'), ('" + tenant2 + "', 'V57 Tenant 2')");
            seedStaff(su, t1Jit, tenant1, null);         // created_by NULL -> JIT
            seedStaff(su, t1Op, tenant1, operatorSub);   // created_by set  -> OPERATOR
            seedStaff(su, t2Jit, tenant2, null);         // created_by NULL -> JIT
            seedStaff(su, t2Op, tenant2, operatorSub);   // created_by set  -> OPERATOR

            // grant_source does not exist yet (V57 adds it) — prove we are genuinely pre-V57.
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'shop_staff' AND column_name = 'grant_source'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("pre-V57: grant_source column must not exist yet").isZero();
            }
        }

        // --- 4. Apply V57 as the RLS-bound migrator. Pre-fix (bare UPDATE) this THROWS a
        //        FlywayException at SET NOT NULL — the no-GUC UPDATE saw zero rows under
        //        FORCE RLS, leaving NULLs the validation scan then rejects. Post-fix
        //        (tenant loop) it applies cleanly. ---------------------------------------
        Flyway toV57 = baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("57"))
                .load();
        var result = toV57.migrate();
        assertThat(result.migrationsExecuted)
                .as("exactly V57 applied on top of the V56 database")
                .isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("57");

        // --- 5. Every seeded row backfilled with the correct provenance for BOTH tenants
        //        — the loop reached rows across the whole registry, not just the first.
        //        Read as superuser to bypass RLS and see all rows. ----------------------
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            assertThat(grantSource(su, t1Jit)).as("tenant1 created_by NULL -> JIT").isEqualTo("JIT");
            assertThat(grantSource(su, t1Op)).as("tenant1 created_by set -> OPERATOR").isEqualTo("OPERATOR");
            assertThat(grantSource(su, t2Jit)).as("tenant2 created_by NULL -> JIT").isEqualTo("JIT");
            assertThat(grantSource(su, t2Op)).as("tenant2 created_by set -> OPERATOR").isEqualTo("OPERATOR");

            try (Statement s = su.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM shop_staff WHERE grant_source IS NULL")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("no pre-V57 row left NULL — the whole point of the backfill")
                        .isZero();
            }
        }
    }

    private static void seedStaff(Connection c, UUID id, UUID tenantId, UUID createdBy) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_by) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, UUID.randomUUID());
            if (createdBy == null) {
                ps.setNull(4, Types.OTHER);              // JIT provenance: no granting sub
            } else {
                ps.setObject(4, createdBy);              // OPERATOR provenance: granting GROUP_ADMIN's sub
            }
            ps.executeUpdate();
        }
    }

    private static String grantSource(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT grant_source FROM shop_staff WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("seeded row must still exist").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static FluentConfiguration baseConfig(String user, String password) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), user, password)
                .locations("classpath:db/migration");
    }

    private static Connection connect(String user, String password) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
