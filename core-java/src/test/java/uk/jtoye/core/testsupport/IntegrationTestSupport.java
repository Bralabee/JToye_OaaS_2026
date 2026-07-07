package uk.jtoye.core.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

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
 */
public final class IntegrationTestSupport {

    private IntegrationTestSupport() {
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
