package uk.jtoye.core.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.CoreApplication;
import uk.jtoye.core.config.DatabaseConfigurationValidator.SecurityConfigurationException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SEC-04 / #552, D-03: the application must refuse to boot when its database role OWNS the tables
 * it reads, and must boot cleanly when it is a non-owner runtime role — proving the boot-time
 * ownership fail-fast added to {@link DatabaseConfigurationValidator#validateNotTableOwner()}.
 *
 * <p><b>THE LINE THAT MAKES THIS CLASS CAPABLE OF FAILING.</b> {@link DatabaseConfigurationValidator}
 * is {@code @Profile("!test")}. This class therefore boots the application under the <em>default</em>
 * (empty) profile — {@link #ACTIVE_PROFILES} — which is NOT {@code test}, so the validator bean is
 * created and its {@code @EventListener(ApplicationReadyEvent.class)} actually runs. Set
 * {@code ACTIVE_PROFILES} to {@code {"test"}} instead and the owner-direction assertion flips: the
 * validator is no longer created, the owner boot succeeds, and
 * {@link #ownerRoleRefusesStartupWithNamedReason()} goes from GREEN to RED. That flip is the
 * evidence the pass is produced by the validator running, not by a context that never checked —
 * without it, a green class is indistinguishable from a validator that never executed. The empty
 * default profile also keeps the {@code @Profile("dev")} {@code DemoDataSeeder} inactive, so the
 * boot exercises the validator without seeding.
 *
 * <p><b>Owner / non-owner split, provisioned in the container.</b> The bootstrap role {@code test}
 * is a superuser; a superuser would trip {@link DatabaseConfigurationValidator#validateNotSuperuser()}
 * first and never reach the ownership check. So {@link #provisionRoles()} creates a NOSUPERUSER
 * owner ({@code jtoye_app}) that runs Flyway and therefore owns every table, and a NOSUPERUSER
 * non-owner ({@code jtoye_runtime}) that inherits DML through
 * {@code ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app} and owns nothing. Flyway always migrates as
 * the owner ({@code spring.flyway.user}); only the datasource role changes between the two boots.
 */
@Testcontainers
@Tag("testcontainers")
class DatabaseConfigurationValidatorOwnershipTest {

    /**
     * Empty = the Spring default profile (allowed by {@code ActiveProfileValidator}). NOT
     * {@code test}, so the {@code @Profile("!test")} validator runs. See the class javadoc: flip
     * this to {@code {"test"}} to prove the owner-direction depends on the validator executing.
     */
    private static final String[] ACTIVE_PROFILES = {};

    private static final String OWNER_ROLE = "jtoye_app";
    private static final String RUNTIME_ROLE = "jtoye_runtime";
    // Test-local role passwords. Never a shipped credential; generated per JVM so they are not a
    // literal anyone could reuse. Kept off the exception messages this class asserts on (ASVS V7).
    private static final String OWNER_PW = "own_" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    private static final String RUNTIME_PW = "run_" + Long.toHexString(Double.doubleToLongBits(Math.random()));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye")
            .withUsername("test")
            .withPassword("test");

    // The default profile keeps CacheConfig active (it is @Profile("!test")), so a real Redis is
    // required — the same reason PublicRateLimitIntegrationTest runs one. A container, not the live
    // stack, so the test is self-contained in CI.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @BeforeAll
    static void provisionRoles() throws Exception {
        // As the bootstrap superuser: create the NOSUPERUSER owner + non-owner split BEFORE any
        // application boot, so the ALTER DEFAULT PRIVILEGES is in place when Flyway (as the owner)
        // creates the tables and jtoye_runtime inherits DML on each one.
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            // uuid-ossp is pre-provisioned by the superuser in every real environment
            // (infra/db/init/00-create-db.sql), so V1's `CREATE EXTENSION IF NOT EXISTS` is a no-op
            // an unprivileged owner may run. Mirror that here or the NOSUPERUSER owner's V1 fails.
            s.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            s.execute("CREATE ROLE " + OWNER_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + OWNER_PW + "'");
            s.execute("GRANT CONNECT, TEMPORARY ON DATABASE jtoye TO " + OWNER_ROLE);
            s.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + OWNER_ROLE);

            s.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + RUNTIME_PW + "'");
            s.execute("GRANT CONNECT, TEMPORARY ON DATABASE jtoye TO " + RUNTIME_ROLE);
            s.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);

            s.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER_ROLE + " IN SCHEMA public " +
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + RUNTIME_ROLE);
            s.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER_ROLE + " IN SCHEMA public " +
                    "GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + RUNTIME_ROLE);
        }
    }

    private static String[] bootArgs(String dsUser, String dsPassword) {
        // Passed as COMMAND-LINE args (not SpringApplicationBuilder.properties, which sets
        // lowest-precedence default properties that application.yml overrides — that override is
        // exactly what sent Flyway to a default localhost:5432 and a ConnectException). Command-line
        // property source outranks application.yml, so these actually take effect.
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", postgres.getJdbcUrl());
        p.put("spring.datasource.username", dsUser);
        p.put("spring.datasource.password", dsPassword);
        p.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        p.put("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect");
        p.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        p.put("spring.jpa.hibernate.ddl-auto", "none");
        // Flyway ALWAYS migrates as the owner, even when the datasource is the non-owner runtime
        // role — the production split. Keeping spring.flyway.url present builds Flyway its own
        // non-pooling datasource (#517), so the migration user does not leak into the app pool.
        p.put("spring.flyway.enabled", "true");
        p.put("spring.flyway.url", postgres.getJdbcUrl());
        p.put("spring.flyway.user", OWNER_ROLE);
        p.put("spring.flyway.password", OWNER_PW);
        p.put("rate-limiting.enabled", "false");
        // Boot without live infra: brokerless Rabbit, Redis excluded, cache off, an unreachable
        // (lazy) issuer, and the two always-on ApplicationRunners disabled so the boot is about
        // the validator and nothing else.
        p.put("spring.rabbitmq.host", "localhost");
        p.put("spring.rabbitmq.port", "0");
        p.put("spring.rabbitmq.listener.simple.auto-startup", "false");
        // Real (container) Redis — CacheConfig is active under the default profile and needs one.
        p.put("spring.data.redis.host", redis.getHost());
        p.put("spring.data.redis.port", redis.getMappedPort(6379).toString());
        p.put("spring.data.redis.timeout", "1500ms");
        p.put("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "http://localhost:18080/realms/jtoye-test");
        p.put("jtoye.geo.postcode-import.enabled", "false");
        p.put("jtoye.geo.coordinate-backfill.enabled", "false");
        p.put("server.port", "0");
        p.put("spring.main.banner-mode", "off");
        return p.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
    }

    private static ConfigurableApplicationContext boot(String dsUser, String dsPassword) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(CoreApplication.class)
                .web(WebApplicationType.SERVLET);
        if (ACTIVE_PROFILES.length > 0) {
            builder.profiles(ACTIVE_PROFILES);
        }
        return builder.run(bootArgs(dsUser, dsPassword));
    }

    private static SecurityConfigurationException findSecurityException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SecurityConfigurationException sce) {
                return sce;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return null;
    }

    /**
     * OWNER direction: booting as a role that owns the tables must fail startup with an actionable,
     * named reason — the role, the reason, the remedy, and the three files to edit, and no credential.
     */
    @Test
    void ownerRoleRefusesStartupWithNamedReason() {
        Throwable thrown = catchThrowable(() -> {
            try (ConfigurableApplicationContext ctx = boot(OWNER_ROLE, OWNER_PW)) {
                // Unreachable if the validator does its job; closing here only matters if it does not.
            }
        });

        assertThat(thrown)
                .as("booting as the table-owner role must abort startup — the validator refuses it")
                .isNotNull();

        SecurityConfigurationException sce = findSecurityException(thrown);
        assertThat(sce)
                .as("startup failure must be the ownership SecurityConfigurationException, "
                        + "not some unrelated boot error")
                .isNotNull();

        String msg = sce.getMessage();
        assertThat(msg)
                .as("the message must name the role, the reason, and the three files to update")
                .contains(OWNER_ROLE)
                .contains("OWNS")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains(RUNTIME_ROLE)
                .contains(".env")
                .contains("docker-compose.full-stack.yml")
                .contains("k8s/base/secrets-template.yaml.example");
        // ASVS V7: the fail-fast names the reason WITHOUT leaking a credential value.
        assertThat(msg)
                .as("the message must not contain a role password (ASVS V7)")
                .doesNotContain(OWNER_PW)
                .doesNotContain(RUNTIME_PW);
    }

    /**
     * NON-OWNER direction: booting as the DML-only runtime role that owns nothing must start
     * cleanly. The validator ran (default profile, not {@code test}) and did NOT reject it — the
     * context is up.
     */
    @Test
    void nonOwnerRoleStartsCleanly() {
        try (ConfigurableApplicationContext ctx = boot(RUNTIME_ROLE, RUNTIME_PW)) {
            assertThat(ctx.isRunning())
                    .as("the non-owner runtime role must boot the application — it owns no tables")
                    .isTrue();
            // The profile-gated validator bean is present under the default profile: proof the
            // clean start above was checked, not skipped. Under a "test" profile it would be absent.
            assertThat(ctx.getBeanNamesForType(DatabaseConfigurationValidator.class))
                    .as("the @Profile(\"!test\") validator must be an active bean on this boot")
                    .isNotEmpty();
        }
    }
}
