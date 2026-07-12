package uk.jtoye.core.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V44 reserved-slot migration (#96 tail) actually reaches deployed
 * databases. V44 was filled AFTER V45/V46 shipped, so every live DB is already
 * stamped past it; this test reproduces that exact state on real Postgres and
 * pins Flyway's behaviour both ways:
 *
 * <ol>
 *   <li>migrate everything EXCEPT V44 — the schema history tops out at the
 *       latest version with a hole at 44, i.e. the pre-PR deployed state;</li>
 *   <li>with Flyway's default {@code outOfOrder=false} the full migration set
 *       REFUSES to run ("resolved migration not applied to database"), which
 *       would brick boot — hence the config change;</li>
 *   <li>with {@code outOfOrder=true} (what application.yml now sets for every
 *       profile) Flyway applies exactly V44 and nothing else, and both V44
 *       effects hold: ts_match_vq LEAKPROOF (Flyway runs as the container
 *       superuser here) and the NULL search_vector backfill.</li>
 * </ol>
 *
 * <p>Plain JUnit + Flyway API on purpose — no Spring context; the deployed
 * out-of-order scenario is about Flyway's schema history, not the app.
 */
@Testcontainers
@Tag("testcontainers")
class FlywayV44OutOfOrderIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000aa";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_ooo")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v44AppliesOutOfOrderToDatabaseAlreadyStampedPastIt() throws Exception {
        // --- 1. Deployed state: every migration EXCEPT V44 applied. ---------
        Path withoutV44 = Files.createTempDirectory("migrations-without-v44");
        boolean v44Existed = false;
        for (Resource migration : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String name = migration.getFilename();
            if (name == null) {
                continue;
            }
            if (name.startsWith("V44__")) {
                v44Existed = true;
                continue;
            }
            try (InputStream in = migration.getInputStream()) {
                Files.copy(in, withoutV44.resolve(name));
            }
        }
        assertThat(v44Existed).as("V44 migration must exist on the classpath").isTrue();

        Flyway preV44 = baseConfig().locations("filesystem:" + withoutV44).load();
        preV44.migrate();
        assertThat(appliedVersions(preV44))
                .as("schema history must already be stamped past the V44 hole")
                .doesNotContain("44")
                .contains("43", "45", "46");

        // A trigger-bypassed NULL-vector row, like the 24/25 found in live dev
        // (superuser bypasses FORCE RLS, so no tenant GUC needed here).
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'OOO Tenant')");
            s.execute("ALTER TABLE products DISABLE TRIGGER trg_products_search_vector");
            s.execute("INSERT INTO products (tenant_id, sku, title, ingredients_text) "
                    + "VALUES ('" + TENANT + "', 'OOO-1', 'Chicken Curry', 'chicken, spices')");
            s.execute("ALTER TABLE products ENABLE TRIGGER trg_products_search_vector");
        }

        // --- 2. Default outOfOrder=false refuses the pending lower version. --
        Flyway strict = baseConfig().locations("classpath:db/migration").outOfOrder(false).load();
        assertThatThrownBy(strict::migrate)
                .as("without out-of-order the V44 hole must fail validation — "
                        + "this is why application*.yml now sets out-of-order: true")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("44");

        // --- 3. outOfOrder=true (mirrors application.yml) applies exactly V44.
        Flyway outOfOrder = baseConfig().locations("classpath:db/migration").outOfOrder(true).load();
        var result = outOfOrder.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("44");
        assertThat(appliedVersions(outOfOrder)).contains("44");

        // Both V44 effects hold on the previously-stamped database.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT proleakproof FROM pg_proc WHERE proname = 'ts_match_vq'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                        .as("V44 ran as superuser here, so the LEAKPROOF alter must have applied")
                        .isTrue();
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT search_vector IS NOT NULL, search_vector = setweight(to_tsvector('english', title), 'A') || "
                            + "setweight(to_tsvector('english', ''), 'B') || "
                            + "setweight(to_tsvector('english', ''), 'C') || "
                            + "setweight(to_tsvector('english', ingredients_text), 'C') || "
                            + "setweight(to_tsvector('english', ''), 'D') "
                            + "FROM products WHERE sku = 'OOO-1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("V44 must backfill the NULL search_vector").isTrue();
                assertThat(rs.getBoolean(2))
                        .as("backfilled vector must equal the V25 trigger expression")
                        .isTrue();
            }
        }
    }

    private static FluentConfiguration baseConfig() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
