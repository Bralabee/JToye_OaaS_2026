package uk.jtoye.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-04 / #552, D-04 — the permanent, regression-proof half of the runtime/owner role split.
 *
 * <p>Provisions the split inside its own container: Flyway migrates as a NOSUPERUSER owner
 * ({@code jtoye_app}) so every table is owned by it, then the SHIPPED operator bootstrap
 * {@code infra/db/create-runtime-role.sql} is driven through the container's {@code psql} to create
 * the DML-only non-owner {@code jtoye_runtime}. Driving the shipped file (not an inline copy) means
 * these assertions certify the grant set that actually ships.
 *
 * <p>Four contracts, each with the fail direction encoded as a permanent CONTROL rather than a
 * one-off manual arm:
 * <ol>
 *   <li><b>The future-table contract — the highest-value assertion in the phase.</b> A table created
 *       AFTER the grants, as the owner, is SELECT-able by {@code jtoye_runtime}. The control role
 *       {@code jtoye_runtime_noforrole} was given the identical default-privilege grant but WITHOUT
 *       {@code FOR ROLE jtoye_app}, so it inherits NOTHING on that same table — which is exactly the
 *       inert form live on {@code jtoye_backup} before #629. The two {@code has_table_privilege}
 *       values on one table are the FOR-ROLE-present / FOR-ROLE-omitted directions, recorded.</li>
 *   <li><b>The non-DML contract.</b> {@code jtoye_runtime} holds {@code TRUNCATE} on
 *       {@code postcode_centroid} (PostcodeCentroidImporter.java:162) and {@code TEMPORARY} on the
 *       database (PostcodeCentroidImporter.java:141) — privileges a CRUD-only test cannot catch. The
 *       control, granted neither, is the fail direction.</li>
 *   <li><b>The negative contract.</b> {@code jtoye_runtime} owns ZERO tables and holds no
 *       {@code CREATE} on the schema — so it is a real split, not a second owner under a new name.
 *       Non-vacuity: {@code jtoye_app} owns the full set and DOES hold CREATE.</li>
 *   <li><b>Isolation as the non-owner.</b> Through a real {@code jtoye_runtime} connection, a pinned
 *       tenant sees only its own {@code customers} rows and an unpinned session sees none; a superuser
 *       control counts the sum, proving the zero is RLS and not a blind instrument.</li>
 * </ol>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class RuntimeRoleGrantContractTest {

    private static final String OWNER_ROLE = "jtoye_app";
    private static final String RUNTIME_ROLE = "jtoye_runtime";
    // The control: same DML default-privilege grant but WITHOUT FOR ROLE jtoye_app (the inert form).
    private static final String NOFORROLE_CONTROL = "jtoye_runtime_noforrole";

    private static final String APP_PW = "app" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    private static final String RUNTIME_PW = "run" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    private static final String CONTROL_PW = "ctl" + Long.toHexString(Double.doubleToLongBits(Math.random()));

    private static final String TENANT_A = "00000000-0000-0000-0000-0000000000a1";
    private static final String TENANT_B = "00000000-0000-0000-0000-0000000000b2";
    private static final int CUSTOMERS_A = 3;
    private static final int CUSTOMERS_B = 2;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        // Provision a NOSUPERUSER OWNER (jtoye_app) BEFORE the context boots, so Flyway migrates as
        // it and every table is owned by it — the precondition create-runtime-role.sql's FOR ROLE
        // clause keys against.
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            s.execute("CREATE ROLE " + OWNER_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PW + "'");
            s.execute("GRANT CONNECT, TEMPORARY ON DATABASE jtoye TO " + OWNER_ROLE);
            s.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + OWNER_ROLE);
        }

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> OWNER_ROLE);
        registry.add("spring.datasource.password", () -> APP_PW);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_ROLE);
        registry.add("spring.flyway.password", () -> APP_PW);
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    // Once-guard: @BeforeEach runs after autowiring has triggered the (lazy) context load, so Flyway
    // has migrated as jtoye_app and postcode_centroid exists — unlike @BeforeAll, which SpringExtension
    // runs before the context is loaded, so the shipped SQL's TRUNCATE grant would find no table.
    private static boolean provisioned = false;

    @BeforeEach
    void provisionRuntimeSplitAndSeed() throws Exception {
        if (provisioned) {
            return;
        }
        // Runs after the context loaded (schema migrated as jtoye_app), so postcode_centroid exists.
        // 1) The real thing: drive the SHIPPED create-runtime-role.sql (with FOR ROLE jtoye_app).
        IntegrationTestSupport.provisionRuntimeRoleFromShippedSql(postgres, RUNTIME_PW);

        try (Connection su = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            // 2) The control: identical DML default-privilege grant but WITHOUT FOR ROLE. Run as the
            //    superuser, so (exactly like the live jtoye_backup defect) the defaults register
            //    against the superuser and cover NOTHING jtoye_app creates afterwards.
            s.execute("CREATE ROLE " + NOFORROLE_CONTROL + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + CONTROL_PW + "'");
            s.execute("GRANT CONNECT ON DATABASE jtoye TO " + NOFORROLE_CONTROL);
            s.execute("GRANT USAGE ON SCHEMA public TO " + NOFORROLE_CONTROL);
            s.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public " +
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + NOFORROLE_CONTROL);
        }

        // 3) Seed two tenants' customers as the owner, under the tenant GUC (customers is FORCE RLS).
        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), OWNER_ROLE, APP_PW);
             Statement s = app.createStatement()) {
            s.execute("INSERT INTO tenants(id,created_at,name,status,plan,stripe_connect_status) " +
                    "VALUES ('" + TENANT_A + "',now(),'RRGCT A','ACTIVE','STANDARD','NONE') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO tenants(id,created_at,name,status,plan,stripe_connect_status) " +
                    "VALUES ('" + TENANT_B + "',now(),'RRGCT B','ACTIVE','STANDARD','NONE') ON CONFLICT (id) DO NOTHING");
            s.execute("SET app.current_tenant_id = '" + TENANT_A + "'");
            s.execute("INSERT INTO customers(tenant_id,name,email) VALUES " +
                    "('" + TENANT_A + "','A1','a1@example.com'),('" + TENANT_A + "','A2','a2@example.com'),('" + TENANT_A + "','A3','a3@example.com')");
            s.execute("SET app.current_tenant_id = '" + TENANT_B + "'");
            s.execute("INSERT INTO customers(tenant_id,name,email) VALUES " +
                    "('" + TENANT_B + "','B1','b1@example.com'),('" + TENANT_B + "','B2','b2@example.com')");
        }
        provisioned = true;
    }

    private Boolean bool(String sql) {
        return jdbc.queryForObject(sql, Boolean.class);
    }

    private long ownedTables(String role) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relkind='r' AND relnamespace='public'::regnamespace " +
                        "AND relowner = (SELECT oid FROM pg_roles WHERE rolname = ?)",
                Long.class, role);
        return n == null ? -1 : n;
    }

    /**
     * THE ONE THAT MATTERS. A table created AFTER the grants, by the owner, is readable by the
     * runtime role via {@code ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app}; the no-FOR-ROLE control
     * inherits nothing on the same table. Both {@code has_table_privilege} values, one table.
     */
    @Test
    void futureTableIsReadableByRuntime_butNotByTheNoForRoleControl() {
        jdbc.execute("DROP TABLE IF EXISTS future_probe");
        jdbc.execute("CREATE TABLE future_probe (id int)"); // created AS jtoye_app, after the grants

        Boolean runtimeCanSelect = bool(
                "SELECT has_table_privilege('" + RUNTIME_ROLE + "','future_probe','SELECT')");
        Boolean controlCanSelect = bool(
                "SELECT has_table_privilege('" + NOFORROLE_CONTROL + "','future_probe','SELECT')");

        assertThat(runtimeCanSelect)
                .as("FOR ROLE jtoye_app present: the runtime role inherits SELECT on a table created "
                        + "after the grants — the whole point of the split's forward-compatibility")
                .isTrue();
        assertThat(controlCanSelect)
                .as("FOR ROLE OMITTED (control): the default privileges registered against the "
                        + "superuser, so a jtoye_app-created table grants it NOTHING — the inert form "
                        + "that was live on jtoye_backup before #629")
                .isFalse();

        jdbc.execute("DROP TABLE IF EXISTS future_probe");
    }

    /** The non-DML privileges a CRUD-only test cannot reach; the control is the fail direction. */
    @Test
    void nonDmlContract_truncateOnPostcodeCentroid_and_temporaryOnDatabase() {
        assertThat(bool("SELECT has_table_privilege('" + RUNTIME_ROLE + "','postcode_centroid','TRUNCATE')"))
                .as("runtime needs TRUNCATE on postcode_centroid (PostcodeCentroidImporter.java:162)")
                .isTrue();
        assertThat(bool("SELECT has_database_privilege('" + RUNTIME_ROLE + "','jtoye','TEMPORARY')"))
                .as("runtime needs TEMPORARY on the database (PostcodeCentroidImporter.java:141 CREATE TEMP TABLE)")
                .isTrue();
        assertThat(bool("SELECT has_table_privilege('" + NOFORROLE_CONTROL + "','postcode_centroid','TRUNCATE')"))
                .as("fail direction: a role not granted TRUNCATE does not have it")
                .isFalse();
    }

    /** The runtime role is a real non-owner: owns nothing, cannot CREATE. Owner is the control. */
    @Test
    void negativeContract_ownsNoTables_andCannotCreate() throws Exception {
        assertThat(ownedTables(RUNTIME_ROLE))
                .as("the runtime role must OWN nothing — otherwise it is a second owner under a new name")
                .isZero();
        assertThat(ownedTables(OWNER_ROLE))
                .as("non-vacuity control: the owner DOES own tables, so the ownership query can return > 0")
                .isGreaterThan(0);

        assertThat(bool("SELECT has_schema_privilege('" + RUNTIME_ROLE + "','public','CREATE')"))
                .as("the runtime role must not hold CREATE on schema public")
                .isFalse();
        assertThat(bool("SELECT has_schema_privilege('" + OWNER_ROLE + "','public','CREATE')"))
                .as("non-vacuity control: the owner/migrator DOES hold CREATE")
                .isTrue();

        // FD direction: give the runtime role ownership of one table and confirm the ownership query
        // detects it (count 1), then remove it — proving the zero above is a measured absence.
        try (Connection su = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            s.execute("CREATE TABLE runtime_owned_probe (id int)");
            s.execute("ALTER TABLE runtime_owned_probe OWNER TO " + RUNTIME_ROLE);
            assertThat(ownedTables(RUNTIME_ROLE))
                    .as("with one table reassigned, the ownership query reports exactly 1 — it is not blind")
                    .isEqualTo(1L);
            s.execute("DROP TABLE runtime_owned_probe");
        }
        assertThat(ownedTables(RUNTIME_ROLE))
                .as("clean state restored: the runtime role owns nothing again")
                .isZero();
    }

    /** Isolation, measured through a real non-owner connection, with a summing superuser control. */
    @Test
    void isolationHoldsAsTheNonOwnerRole() throws Exception {
        long unpinned;
        long pinnedA;
        long pinnedB;
        try (Connection rt = DriverManager.getConnection(postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PW);
             Statement s = rt.createStatement()) {
            unpinned = count(s, "SELECT count(*) FROM customers");
            s.execute("SET app.current_tenant_id = '" + TENANT_A + "'");
            pinnedA = count(s, "SELECT count(*) FROM customers");
            s.execute("SET app.current_tenant_id = '" + TENANT_B + "'");
            pinnedB = count(s, "SELECT count(*) FROM customers");
        }

        long superuserTotal;
        try (Connection su = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            superuserTotal = count(s, "SELECT count(*) FROM customers");
        }

        assertThat(unpinned)
                .as("non-owner, RLS-subject, no tenant pinned -> zero rows")
                .isZero();
        assertThat(pinnedA).as("pinned tenant A sees only A's customers").isEqualTo(CUSTOMERS_A);
        assertThat(pinnedB).as("pinned tenant B sees only B's customers").isEqualTo(CUSTOMERS_B);
        assertThat(superuserTotal)
                .as("superuser control sums the two tenants — proving the runtime-role zero is RLS, "
                        + "not a blind instrument that cannot see any row")
                .isEqualTo(CUSTOMERS_A + CUSTOMERS_B);
    }

    /** The test drives the SHIPPED file; a missing path ERRORS rather than provisioning nothing. */
    @Test
    void drivesTheShippedSqlFile_andAMissingPathErrors() {
        Path shipped = IntegrationTestSupport.locateRepoFile("infra/db/create-runtime-role.sql");
        assertThat(shipped).exists();
        assertThat(shipped.getFileName().toString()).isEqualTo("create-runtime-role.sql");

        assertThatThrownBy(() -> IntegrationTestSupport.locateRepoFile("infra/db/does-not-exist-xyz.sql"))
                .as("a wrong path must error, never silently provision nothing")
                .isInstanceOf(IllegalStateException.class);
    }

    private static long count(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
