package uk.jtoye.core.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared property wiring for {@code @Tag("testcontainers")} integration tests
 * (QA-council #71). Every Testcontainers test class must combine THREE things
 * to boot reliably in any environment (CI runner, dev machine with a live
 * broker, etc.):
 *
 * <ol>
 *   <li>{@code @ActiveProfiles("test")} — loads application-test.yml, which
 *       disables the RabbitMQ listener auto-startup and Redis autoconfig.
 *       Without it the context boots against base config and dies on a
 *       live-broker AMQP auth failure (the root cause of the 8 classes that
 *       failed the 2026-07-07 baseline run).</li>
 *   <li>This helper — application-test.yml defaults to H2, so every
 *       H2-specific property must be overridden back to the Testcontainers
 *       Postgres; ddl-auto stays {@code none} so the Flyway-managed schema
 *       (with its RLS policies) is the sole source of truth.</li>
 *   <li>A per-class {@code @Container} Postgres — fresh database per class,
 *       no cross-class data pollution.</li>
 * </ol>
 *
 * <p><strong>RLS caveat:</strong> the Testcontainers bootstrap role is a
 * Postgres SUPERUSER, which bypasses even FORCE ROW LEVEL SECURITY. Tests that
 * must prove RLS <em>enforcement</em> (not just app-layer scoping) additionally
 * downgrade the role after seeding:
 * {@code jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER")}
 * — see {@code ScheduledCleanupServiceIntegrationTest} and
 * {@code ShopImageCrossTenantIntegrationTest} for the pattern, and remember the
 * tenant GUC is only applied inside an active transaction
 * (TenantSetLocalAspect no-ops otherwise).
 *
 * <p><strong>NOSUPERUSER is NOT the same property as non-OWNER (SEC-04 / #552, D-04).</strong>
 * The downgrade above ({@code ALTER ROLE ... NOSUPERUSER}) stops the bootstrap role from
 * <em>bypassing</em> RLS, but that role still <em>owns</em> the tables Flyway created — and a
 * table owner is kept off other tenants' rows only for as long as FORCE ROW LEVEL SECURITY is
 * remembered on every table. The production split repairs that: the application connects as
 * {@code jtoye_runtime}, a role that is NOSUPERUSER, NOBYPASSRLS <em>and</em> owns nothing, so
 * isolation depends on the role's privileges rather than on FORCE being remembered. A test that
 * must prove the app works as the non-owner role provisions that role from the shipped operator
 * bootstrap via {@link #provisionRuntimeRoleFromShippedSql(PostgreSQLContainer, String)} — a NEW,
 * opt-in helper that does not change {@link #registerPostgresTestProperties} or the 45 existing
 * NOSUPERUSER-downgrade callers.
 */
public final class IntegrationTestSupport {

    private IntegrationTestSupport() {
    }

    /**
     * Provisions the non-owner {@code jtoye_runtime} runtime role by driving the SHIPPED operator
     * bootstrap {@code infra/db/create-runtime-role.sql} through the container's own {@code psql}
     * (SEC-04 / #552, D-04). Driving the shipped file rather than an inline copy of the grants means
     * the test asserts exactly the grant set that ships — if a future edit weakens the file, the
     * contract test that calls this sees it.
     *
     * <p><strong>Precondition:</strong> the migrations must ALREADY be applied as the owner role
     * {@code jtoye_app} (so every table, including {@code postcode_centroid} that the file grants
     * {@code TRUNCATE} on, exists and is owned by {@code jtoye_app} for the file's
     * {@code ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app} clause to key against). The container's
     * database must be named {@code jtoye} to match the file's {@code GRANT ... ON DATABASE jtoye}.
     *
     * @param postgres        the running container, migrated as {@code jtoye_app}
     * @param runtimePassword the password to set on {@code jtoye_runtime} (never a shipped literal)
     */
    public static void provisionRuntimeRoleFromShippedSql(PostgreSQLContainer<?> postgres,
                                                          String runtimePassword) throws Exception {
        Path sql = locateRepoFile("infra/db/create-runtime-role.sql");
        postgres.copyFileToContainer(MountableFile.forHostPath(sql), "/tmp/create-runtime-role.sql");
        // psql honours the file's own \set ON_ERROR_STOP on and :'runtime_password' interpolation,
        // so the DO $$ ... $$ block and every dollar-quote survive untouched — the reason we drive
        // it through psql rather than splitting statements over JDBC.
        Container.ExecResult r = postgres.execInContainer(
                "psql",
                "-v", "ON_ERROR_STOP=1",
                "-v", "runtime_password=" + runtimePassword,
                "-U", postgres.getUsername(),
                "-d", "jtoye",
                "-f", "/tmp/create-runtime-role.sql");
        if (r.getExitCode() != 0) {
            throw new IllegalStateException(
                    "create-runtime-role.sql failed (exit " + r.getExitCode() + ")\nSTDERR:\n"
                            + r.getStderr() + "\nSTDOUT:\n" + r.getStdout());
        }
    }

    /**
     * Resolves a repository file by walking up from the test's working directory (Gradle runs the
     * {@code integrationTest} task with the module dir as cwd) until the relative path is found.
     * Throws when it is not found — so a wrong path ERRORS rather than silently provisioning
     * nothing (the "found nothing is never clean" contract).
     */
    public static Path locateRepoFile(String relativePath) {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            Path candidate = cur.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not locate repository file '" + relativePath + "' walking up from " + dir
                        + " — refusing to proceed over a missing input.");
    }

    /**
     * Registers the full Testcontainers-Postgres property set on top of the
     * {@code test} profile: datasource, H2-override trio, Flyway on,
     * Hibernate DDL off, rate limiting off, and a dead-port brokerless
     * RabbitMQ so the context never needs a live broker.
     */
    public static void registerPostgresTestProperties(DynamicPropertyRegistry registry,
                                                      PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // application-test.yml defaults to H2; point every H2-specific
        // property back at the Testcontainers Postgres.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // ddl-auto none: the Flyway-managed schema (incl. RLS policies) is the
        // sole source of truth; test-yml's create-drop would clobber it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // Belt-and-braces with application-test.yml: dead port + no listener
        // auto-startup so the context boots without a live broker even if a
        // future profile tweak re-enables it.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }
}
