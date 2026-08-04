package uk.jtoye.core.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #517 — first boot against a <em>fresh</em> database must reach head on the
 * FIRST attempt, as the RLS-bound migration role, in strict version order.
 *
 * <p><strong>The bug.</strong> A fresh chain applies V44 <em>before</em> V46 (version
 * order). V44 ends with {@code PERFORM set_config('app.current_tenant_id', '', true)},
 * and a Postgres <em>placeholder</em> GUC that has been touched resets to the EMPTY
 * STRING rather than to unset — measured on postgres:15: a virgin session reads NULL,
 * the same session after a committed txn-local {@code set_config} reads {@code ''}.
 * V46 then runs a bare {@code UPDATE payment_event_outbox}; the policy live at that
 * point in the chain is still V33's RAW cast
 * {@code current_setting('app.current_tenant_id', true)::uuid} (V51 repoints it at the
 * safe {@code current_tenant_id()} helper — five migrations too late to protect V46),
 * so {@code ''::uuid} raises 22P02 and boot dies. It self-heals on restart, which is
 * why no environment can be provisioned cleanly on the first try. It is intermittent
 * because it only fires when V44 and V46 land on the SAME physical connection, and it
 * is invisible on every long-lived database because {@code out-of-order=true} applied
 * V46 <em>before</em> V44 there.
 *
 * <p><strong>The fix, and why these tests are not vacuous.</strong> The fix is config
 * only (V33/V44/V46 are applied on live databases, so none may be edited and no new
 * migration may reorder them): {@code spring.flyway.init-sqls} pins a valid sentinel
 * tenant GUC for the whole migration run, and {@code spring.flyway.url} makes Spring
 * Boot hand Flyway a dedicated non-pooling {@code SimpleDriverDataSource} so that
 * session-scoped GUC can never ride a Hikari connection back into application
 * requests. Both halves are pinned here <em>with their control arm</em>:
 *
 * <ol>
 *   <li>{@link #freshChainMigratesToHeadOnTheFirstAttemptAsAnRlsBoundRole()} runs the
 *       whole chain with the sentinel the shipped {@code application.yml} declares;</li>
 *   <li>{@link #withoutTheSentinelTheSameFreshChainStillDiesAtV46()} runs the identical
 *       chain <em>without</em> it and REQUIRES the 22P02 — the permanently-encoded fail
 *       direction, so a green (1) can never be mistaken for a check incapable of
 *       failing;</li>
 *   <li>{@link #flywayGetsItsOwnDataSourceSoTheSentinelNeverReachesTheAppPool()} boots
 *       the real autoconfiguration on the shipped {@code spring.flyway.*} keys and reads
 *       the GUC off connections borrowed from the application's Hikari pool;</li>
 *   <li>{@link #sharingTheAppPoolWouldLeakTheSentinelIntoRequestConnections()} is (3)'s
 *       control: drop only {@code spring.flyway.url} and the sentinel demonstrably
 *       leaks into the pool — which is why the dedicated DataSource is load-bearing and
 *       not decoration;</li>
 *   <li>{@link #stagingAndProdProfilesInheritTheFix()} proves the deploy profiles get
 *       it (they deliberately do not restate the keys).</li>
 * </ol>
 *
 * <p>Every arm runs as {@code rls_migrator} — NOSUPERUSER NOBYPASSRLS, mirroring
 * {@code jtoye_app} — for the chain tests. That is not optional: the identical chain
 * passes cleanly as the Testcontainers bootstrap SUPERUSER, which bypasses even FORCE
 * RLS, so a default Testcontainers migration test would be vacuous and would go green
 * on the broken tree. Plain JUnit + Flyway API on purpose, mirroring
 * {@code V57GrantSourceBackfillIntegrationTest} and
 * {@code MediaBackfillMigrationIntegrationTest}.
 */
@Testcontainers
@Tag("testcontainers")
class FreshChainMigrationIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_migrator_pw";

    /** Set by application.yml's init-sqls; asserted, never hardcoded as the source of truth. */
    private static final String EXPECTED_SENTINEL = "00000000-0000-0000-0000-000000000000";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_fresh")
            .withUsername("test")
            .withPassword("test");

    // ------------------------------------------------------------------------
    // 1. The fix: fresh chain, version order, RLS-bound role, ONE attempt.
    // ------------------------------------------------------------------------

    @Test
    void freshChainMigratesToHeadOnTheFirstAttemptAsAnRlsBoundRole() throws Exception {
        String db = "fresh_chain";
        provisionFreshDatabaseForRlsMigrator(db);
        assertRoleIsRlsBound();

        String initSql = shippedFlywayInitSql();
        assertThat(initSql)
                .as("application.yml must declare spring.flyway.init-sqls — it is the fix")
                .isNotBlank()
                .contains("app.current_tenant_id")
                .contains(EXPECTED_SENTINEL);

        Flyway flyway = chainConfig(db).initSql(initSql).load();
        MigrateResult result = flyway.migrate();

        assertThat(result.success).as("first attempt must succeed — no rolled-back migration").isTrue();
        assertThat(result.migrationsExecuted)
                .as("every V*.sql on the classpath applied in a single pass")
                .isEqualTo(migrationFileCount());
        assertThat(result.targetSchemaVersion).isEqualTo(headVersion());
        assertThat(appliedVersions(flyway))
                .as("V46 (the one that used to blow up) and head are both applied")
                .contains("46", headVersion());

        // The head schema is genuinely there, not just a green result object.
        try (Connection su = connect(db, postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            assertThat(scalarInt(s, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'payment_event_outbox' AND column_name IN ('poison', 'next_attempt_at')"))
                    .as("V46's two columns exist").isEqualTo(2);
            assertThat(scalarInt(s, "SELECT count(*) FROM flyway_schema_history WHERE success = false"))
                    .as("no failed row in flyway_schema_history").isZero();
        }
    }

    // ------------------------------------------------------------------------
    // 2. Control arm — the identical chain WITHOUT the sentinel must still fail.
    //    Without this, (1) could be a check incapable of failing.
    // ------------------------------------------------------------------------

    @Test
    void withoutTheSentinelTheSameFreshChainStillDiesAtV46() throws Exception {
        String db = "fresh_chain_control";
        provisionFreshDatabaseForRlsMigrator(db);
        assertRoleIsRlsBound();

        Flyway noSentinel = chainConfig(db).load();   // no initSql — the pre-fix configuration

        assertThatThrownBy(noSentinel::migrate)
                .as("the defect must still be reproducible: ''::uuid under V33's raw-cast policy")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V46")
                .hasMessageContaining("invalid input syntax for type uuid");

        // And it died mid-chain, exactly as a first boot does.
        try (Connection su = connect(db, postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            assertThat(scalarInt(s, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'payment_event_outbox' AND column_name = 'poison'"))
                    .as("V46 rolled back, so its column is absent").isZero();
        }
    }

    // ------------------------------------------------------------------------
    // 3. The chosen variant: Flyway on its OWN DataSource, app pool untouched.
    // ------------------------------------------------------------------------

    @Test
    void flywayGetsItsOwnDataSourceSoTheSentinelNeverReachesTheAppPool() throws Exception {
        String db = "pool_clean";
        createDatabase(db);

        runnerWithShippedFlywayConfig(db, true).run(context -> {
            assertThat(context).hasNotFailed();

            DataSource appPool = context.getBean(DataSource.class);
            assertThat(appPool).isInstanceOf(HikariDataSource.class);

            DataSource migrationDataSource = context.getBean(Flyway.class).getConfiguration().getDataSource();
            assertThat(migrationDataSource)
                    .as("spring.flyway.url makes Boot build Flyway a dedicated DataSource")
                    .isNotSameAs(appPool);
            assertThat(migrationDataSource)
                    .as("and it is NOT a pool, so no connection Flyway touched can be borrowed later")
                    .isNotInstanceOf(HikariDataSource.class);

            // Flyway really did run against this database through that DataSource.
            assertThat(appliedVersions(context.getBean(Flyway.class))).contains("46", headVersion());

            // Every connection the application can borrow is clean.
            List<String> gucs = gucOnEveryPooledConnection(appPool, 4);
            assertThat(gucs)
                    .as("no application connection carries the migration sentinel")
                    .isNotEmpty()
                    .allSatisfy(v -> assertThat(v).isEqualTo("<null>"));
        });
    }

    // ------------------------------------------------------------------------
    // 4. Control arm for (3): sharing the app pool DOES leak. This is why the
    //    dedicated DataSource is load-bearing, not decoration.
    // ------------------------------------------------------------------------

    @Test
    void sharingTheAppPoolWouldLeakTheSentinelIntoRequestConnections() throws Exception {
        String db = "pool_leak";
        createDatabase(db);

        runnerWithShippedFlywayConfig(db, false).run(context -> {
            assertThat(context).hasNotFailed();

            DataSource appPool = context.getBean(DataSource.class);
            assertThat(context.getBean(Flyway.class).getConfiguration().getDataSource())
                    .as("without spring.flyway.url Flyway borrows from the application pool")
                    .isSameAs(appPool);

            List<String> gucs = gucOnEveryPooledConnection(appPool, 4);
            assertThat(gucs)
                    .as("the hazard is real: a pooled connection comes back carrying the sentinel, "
                            + "because Hikari does not reset custom GUCs on return")
                    .contains(EXPECTED_SENTINEL);
        });
    }

    // ------------------------------------------------------------------------
    // 5. The deploy profiles inherit it (they deliberately do not restate it).
    // ------------------------------------------------------------------------

    @Test
    void stagingAndProdProfilesInheritTheFix() throws Exception {
        for (String profileFile : List.of("application-staging.yml", "application-prod.yml")) {
            ConfigurableEnvironment env = environmentFor(profileFile);

            assertThat(env.getProperty("spring.flyway.init-sqls[0]"))
                    .as("%s must inherit the sentinel init-sql", profileFile)
                    .isNotNull()
                    .contains("app.current_tenant_id")
                    .contains(EXPECTED_SENTINEL);
            assertThat(env.getProperty("spring.flyway.url"))
                    .as("%s must inherit the dedicated-DataSource url (placeholder resolved)", profileFile)
                    .isNotNull()
                    .startsWith("jdbc:postgresql://");
            assertThat(env.getProperty("spring.flyway.user"))
                    .as("%s must inherit a migration user", profileFile)
                    .isNotBlank();
            assertThat(env.getProperty("spring.flyway.out-of-order"))
                    .as("%s keeps out-of-order (unchanged by this fix)", profileFile)
                    .isEqualTo("true");
        }
    }

    // ---- shipped-config readers ---------------------------------------------

    /**
     * The init-sql exactly as {@code application.yml} ships it. Read rather than
     * hardcoded so deleting the property makes the chain test fail rather than pass.
     */
    private static String shippedFlywayInitSql() throws IOException {
        ConfigurableEnvironment env = environmentFor();
        List<String> statements = new ArrayList<>();
        for (int i = 0; ; i++) {
            String statement = env.getProperty("spring.flyway.init-sqls[" + i + "]");
            if (statement == null) {
                break;
            }
            statements.add(statement);
        }
        return String.join("\n", statements);
    }

    /** Every {@code spring.flyway.*} key application.yml declares, RAW (placeholders intact). */
    private static Map<String, String> shippedFlywayProperties() throws IOException {
        Map<String, String> shipped = new LinkedHashMap<>();
        for (PropertySource<?> source : loadYaml("application.yml")) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("spring.flyway.")) {
                        shipped.put(name, String.valueOf(enumerable.getProperty(name)));
                    }
                }
            }
        }
        assertThat(shipped)
                .as("application.yml must declare a spring.flyway block")
                .containsKeys("spring.flyway.url", "spring.flyway.init-sqls[0]");
        return shipped;
    }

    private static ConfigurableEnvironment environmentFor(String... profileFiles) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Boot precedence: profile-specific documents override the base document.
        for (String profileFile : profileFiles) {
            loadYaml(profileFile).forEach(env.getPropertySources()::addLast);
        }
        loadYaml("application.yml").forEach(env.getPropertySources()::addLast);
        return env;
    }

    private static List<PropertySource<?>> loadYaml(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource(name);
        assertThat(resource.exists()).as("%s must be on the classpath", name).isTrue();
        return new YamlPropertySourceLoader().load(name, resource);
    }

    // ---- Spring-slice runner -------------------------------------------------

    /**
     * The real {@link FlywayAutoConfiguration} + {@link DataSourceAutoConfiguration} on
     * the shipped {@code spring.flyway.*} keys, migrating {@code db} on startup exactly
     * as the application does.
     *
     * @param dedicatedDataSource {@code false} drops only url/user/password, which is
     *                            precisely the "init-sqls on the shared pool" variant
     *                            this fix rejects.
     */
    private ApplicationContextRunner runnerWithShippedFlywayConfig(String db, boolean dedicatedDataSource)
            throws IOException {
        List<String> properties = new ArrayList<>(List.of(
                "spring.datasource.url=" + urlFor(db),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "spring.datasource.hikari.maximum-pool-size=4",
                "spring.datasource.hikari.minimum-idle=0"));
        shippedFlywayProperties().forEach((key, value) -> {
            if (!dedicatedDataSource
                    && (key.equals("spring.flyway.url")
                        || key.equals("spring.flyway.user")
                        || key.equals("spring.flyway.password"))) {
                return;
            }
            properties.add(key + "=" + value);
        });
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withPropertyValues(properties.toArray(new String[0]));
    }

    /**
     * Borrows {@code count} connections CONCURRENTLY (so they are distinct physical
     * connections, not the same one handed out repeatedly) and reads the tenant GUC off
     * each. {@code "<null>"} means the GUC was never touched on that session.
     */
    private static List<String> gucOnEveryPooledConnection(DataSource pool, int count) throws Exception {
        List<Connection> held = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                held.add(pool.getConnection());
            }
            for (Connection c : held) {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery(
                             "SELECT current_setting('app.current_tenant_id', true)")) {
                    assertThat(rs.next()).isTrue();
                    String value = rs.getString(1);
                    values.add(value == null ? "<null>" : value);
                }
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        return values;
    }

    // ---- database / role plumbing -------------------------------------------

    /**
     * A brand-new database plus the RLS-bound migration role that mirrors
     * {@code jtoye_app}. {@code uuid-ossp} is installed by the superuser first because
     * only the extension needs superuser — V1's {@code CREATE EXTENSION IF NOT EXISTS}
     * then no-ops for {@code rls_migrator}, and every other statement in the chain runs
     * as the RLS-bound role.
     */
    private static void provisionFreshDatabaseForRlsMigrator(String db) throws Exception {
        createDatabase(db);
        try (Connection su = connect(db, postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
        }
    }

    private static void createDatabase(String db) throws Exception {
        try (Connection su = connect(postgres.getDatabaseName(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM pg_roles WHERE rolname = '" + RLS_MIGRATOR + "'")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    s.execute("CREATE ROLE " + RLS_MIGRATOR
                            + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
                }
            }
            s.execute("DROP DATABASE IF EXISTS " + db);
            s.execute("CREATE DATABASE " + db);
        }
    }

    /** Non-vacuity guard: the chain arms must NOT be running as a role that bypasses RLS. */
    private static void assertRoleIsRlsBound() throws Exception {
        try (Connection su = connect(postgres.getDatabaseName(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = '" + RLS_MIGRATOR + "'")) {
            assertThat(rs.next()).as("the migration role must exist").isTrue();
            assertThat(rs.getBoolean("rolsuper"))
                    .as("a SUPERUSER migration role bypasses FORCE RLS and makes this whole test vacuous")
                    .isFalse();
            assertThat(rs.getBoolean("rolbypassrls"))
                    .as("BYPASSRLS would equally mask the defect")
                    .isFalse();
        }
    }

    private static FluentConfiguration chainConfig(String db) {
        return Flyway.configure()
                .dataSource(urlFor(db), RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .locations("classpath:db/migration")
                .outOfOrder(true);                       // mirrors application.yml
    }

    private static String urlFor(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + db;
    }

    private static Connection connect(String db, String user, String password) throws Exception {
        return DriverManager.getConnection(urlFor(db), user, password);
    }

    private static int scalarInt(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private static int migrationFileCount() throws IOException {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql").length;
    }

    /** Highest V-number on the classpath — read, so a new migration does not break this test. */
    private static String headVersion() throws IOException {
        return Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/migration/V*.sql"))
                .map(r -> Objects.requireNonNull(r.getFilename()))
                .map(n -> n.substring(1, n.indexOf("__")))
                .max((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
                .orElseThrow();
    }
}
