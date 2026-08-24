package uk.jtoye.core.security;

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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #647 — the TRUNCATE grant must arrive from the MIGRATION, not from an operator running a script.
 *
 * <p>This is the falsifiable sibling of {@link RuntimeRoleGrantContractTest}. That test asserts the
 * same privilege and was green for all 14 nights the nightly E2E was dead, because its
 * {@code @BeforeEach} calls {@code provisionRuntimeRoleFromShippedSql} — it runs
 * {@code infra/db/create-runtime-role.sql} itself and then checks the grant that script just made.
 * It therefore certifies the SCRIPT. It cannot notice that no deployment runs it.
 *
 * <p>The live failure: {@code e2e-nightly.yml} tears down with {@code down -v}, so every night began
 * on a fresh volume where only {@code infra/db/init/00-create-db.sql} runs. That file grants
 * {@code jtoye_runtime} DML and cannot name {@code postcode_centroid} (V61 creates it later), so
 * {@code PostcodeCentroidImporter} hit {@code permission denied for table postcode_centroid} on its
 * {@code TRUNCATE}, crash-looped every ~27s, never went healthy, and compose aborted before
 * Playwright ran.
 *
 * <p>So this test deliberately does the opposite of its sibling: it creates the runtime role BEFORE
 * the context boots — the only precondition a real deployment offers — lets Flyway migrate, and
 * then asserts the privilege <b>without running any operator script at all</b>. If V64 is deleted or
 * its role guard stops matching, this reds.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PostcodeTruncateGrantMigrationTest {

    private static final String OWNER_ROLE = "jtoye_app";
    private static final String RUNTIME_ROLE = "jtoye_runtime";

    private static final String APP_PW = "app" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    private static final String RUNTIME_PW = "run" + Long.toHexString(Double.doubleToLongBits(Math.random()));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        // Both roles exist BEFORE Flyway runs. That is the whole point: it is exactly what
        // infra/db/init/00-create-db.sql leaves behind on a fresh volume, and it is all a real
        // deployment provides. Nothing here runs create-runtime-role.sql.
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            s.execute("CREATE ROLE " + OWNER_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PW + "'");
            s.execute("GRANT CONNECT, TEMPORARY ON DATABASE jtoye TO " + OWNER_ROLE);
            s.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + OWNER_ROLE);
            s.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + RUNTIME_PW + "'");
            s.execute("GRANT CONNECT ON DATABASE jtoye TO " + RUNTIME_ROLE);
            s.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
            // The DML half, mirroring infra/db/init/00-create-db.sql verbatim — including the
            // FOR ROLE jtoye_app clause, without which the defaults register against the superuser
            // running this and cover nothing Flyway creates. This is what makes the precondition a
            // faithful fresh volume rather than a bare role: TRUNCATE is then the ONE privilege the
            // init path cannot supply, so if the assertion below passes, V64 is the only thing that
            // could have granted it.
            s.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER_ROLE + " IN SCHEMA public "
                    + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + RUNTIME_ROLE);
            s.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER_ROLE + " IN SCHEMA public "
                    + "GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + RUNTIME_ROLE);
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

    private boolean has(String role, String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)", Boolean.class, role, table, privilege));
    }

    /**
     * The assertion the nightly needed. Migration only — no operator script has run in this container.
     */
    @Test
    void migrationAloneGrantsTruncateOnPostcodeCentroidToRuntime() {
        assertThat(has(RUNTIME_ROLE, "postcode_centroid", "TRUNCATE"))
                .as("V64 must grant TRUNCATE on postcode_centroid to %s from the schema itself; "
                        + "PostcodeCentroidImporter.importIfNeeded issues TRUNCATE on a fresh or "
                        + "short table, which is every first boot (#647)", RUNTIME_ROLE)
                .isTrue();
    }

    /**
     * The fail direction, and the reason the grant is table-scoped. If someone "fixes" this by
     * widening ALTER DEFAULT PRIVILEGES, the DML-only application gains TRUNCATE on every tenant
     * table and this reds — which is the outcome the split exists to prevent.
     */
    @Test
    void runtimeDoesNotGainTruncateOnTenantTables() {
        assertThat(has(RUNTIME_ROLE, "orders", "TRUNCATE"))
                .as("the runtime role must NOT hold TRUNCATE on a tenant table — the grant is "
                        + "deliberately scoped to postcode_centroid alone")
                .isFalse();
        assertThat(has(RUNTIME_ROLE, "customers", "TRUNCATE"))
                .as("the runtime role must NOT hold TRUNCATE on a tenant table")
                .isFalse();
    }

    /**
     * Proves the probe can distinguish privileges at all — without this, both assertions above could
     * be reading a broken {@code has_table_privilege} call and agreeing with each other.
     */
    @Test
    void theProbeItselfDiscriminates() {
        assertThat(has(RUNTIME_ROLE, "postcode_centroid", "SELECT"))
                .as("positive control: the runtime role does hold SELECT")
                .isTrue();
        assertThat(has(OWNER_ROLE, "orders", "TRUNCATE"))
                .as("positive control: the OWNER does hold TRUNCATE on a tenant table")
                .isTrue();
    }
}
