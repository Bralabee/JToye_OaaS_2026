package uk.jtoye.core.boot4;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import zipkin2.reporter.BytesMessageSender;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT4-02 / BOOT4-03 (D-02): hard liveness probes for every module the explicit Boot-4 starter
 * set must bring.
 *
 * <p><b>Why this exists.</b> Boot 4 split auto-configuration into one module per technology, and
 * the third-party library alone no longer triggers it: {@code flyway-core} without
 * {@code spring-boot-flyway} runs ZERO migrations, {@code micrometer-tracing-bridge-brave} without
 * {@code spring-boot-micrometer-tracing-brave} leaves tracing off, and so on. None of that is an
 * error. The context starts, every other test stays green, and a capability is simply gone — for
 * Flyway that means no RLS policies at all. A green suite cannot show the difference; a probe that
 * goes red when the module is removed can. Each probe names the module it guards.
 *
 * <p><b>It can fail</b> (38-04 evidence, {@code evidence/38-04-suite-and-liveness.txt}): excluding
 * {@code spring-boot-flyway}, {@code spring-boot-zipkin} + {@code spring-boot-micrometer-tracing-brave},
 * or {@code spring-boot-restclient} from the test runtime classpath turns the matching probe (or
 * the context) red.
 *
 * <p>{@link AutoConfigureTracing} and {@link AutoConfigureMetrics} are required: a Boot test
 * context otherwise disables tracing and metrics export, and the Tracer/registry probes would be
 * measuring the test harness rather than the starter set.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTracing
@AutoConfigureMetrics
@Tag("testcontainers")
class Boot4ModuleLivenessIntegrationTest {

    private static final String MIGRATION_PATTERN = "classpath*:db/migration/V*.sql";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway applied every classpath:db/migration/V*.sql script (module spring-boot-flyway)")
    void flywayAppliedEveryVersionedMigration() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN);
        TreeSet<String> scripts = new TreeSet<>(Arrays.stream(resources)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .toList());
        assertThat(scripts)
                .as("positive control: no %s resources found, so an applied-count comparison would prove nothing",
                        MIGRATION_PATTERN)
                .isNotEmpty();

        TreeSet<String> applied = new TreeSet<>(appliedVersionedScripts());

        assertThat(applied.size())
                .as("Flyway applied %d of the %d V*.sql migrations on the classpath. 0 means the Flyway "
                                + "auto-configuration never ran: spring-boot-flyway (spring-boot-starter-flyway) "
                                + "is missing, and with it every RLS policy", applied.size(), scripts.size())
                .isEqualTo(scripts.size());
        assertThat(applied)
                .as("the applied scripts must be exactly the V*.sql resources")
                .isEqualTo(scripts);
        assertThat(context.getBeanProvider(Flyway.class).getIfAvailable())
                .as("no org.flywaydb.core.Flyway bean: spring-boot-flyway is missing")
                .isNotNull();
    }

    @Test
    @DisplayName("a Brave Tracer and a Zipkin sender exist (modules spring-boot-micrometer-tracing-brave, spring-boot-zipkin)")
    void braveTracerAndZipkinSenderExist() {
        Tracer tracer = context.getBeanProvider(Tracer.class).getIfAvailable();
        assertThat(tracer)
                .as("no io.micrometer.tracing.Tracer bean: spring-boot-micrometer-tracing-brave "
                        + "(via spring-boot-starter-zipkin) is missing, so tracing is silently off")
                .isNotNull();
        assertThat(tracer.getClass().getName())
                .as("the Tracer must be the Brave bridge, not a no-op: spring-boot-micrometer-tracing-brave")
                .contains("Brave");

        BytesMessageSender sender = context.getBeanProvider(BytesMessageSender.class).getIfAvailable();
        assertThat(sender)
                .as("no zipkin2.reporter.BytesMessageSender bean: spring-boot-zipkin is missing, so no "
                        + "span is ever exported")
                .isNotNull();
    }

    @Test
    @DisplayName("a PrometheusMeterRegistry exists (module spring-boot-micrometer-metrics, Prometheus export)")
    void prometheusMeterRegistryExists() {
        assertThat(context.getBeanProvider(PrometheusMeterRegistry.class).getIfAvailable())
                .as("no PrometheusMeterRegistry bean: Prometheus export auto-configuration "
                        + "(spring-boot-micrometer-metrics via spring-boot-starter-actuator) is missing")
                .isNotNull();
    }

    @Test
    @DisplayName("RestClient.Builder, RestTemplateBuilder and WebClient.Builder exist (modules spring-boot-restclient, spring-boot-webclient)")
    void httpClientBuildersExist() {
        assertThat(context.getBeanProvider(RestClient.Builder.class).getIfAvailable())
                .as("no RestClient.Builder bean: spring-boot-restclient (spring-boot-starter-restclient) is missing")
                .isNotNull();
        assertThat(context.getBeanProvider(RestTemplateBuilder.class).getIfAvailable())
                .as("no RestTemplateBuilder bean: spring-boot-restclient (spring-boot-starter-restclient) is missing")
                .isNotNull();
        assertThat(context.getBeanProvider(WebClient.Builder.class).getIfAvailable())
                .as("no WebClient.Builder bean: spring-boot-webclient (spring-boot-starter-webclient) is missing")
                .isNotNull();
    }

    @Test
    @DisplayName("both state-machine factories exist (order and onboarding)")
    @SuppressWarnings("rawtypes")
    void bothStateMachineFactoriesExist() {
        Map<String, StateMachineFactory> factories = context.getBeansOfType(StateMachineFactory.class);
        assertThat(factories.size())
                .as("expected the order and onboarding StateMachineFactory beans, found %s", factories.keySet())
                .isGreaterThanOrEqualTo(2);
        assertThat(factories)
                .as("spring-statemachine 4.0.2 must register both named factories")
                .containsKeys("orderStateMachineFactory", "onboardingStateMachineFactory");
    }

    /**
     * Successful, versioned rows of {@code flyway_schema_history}, read with plain SQL so the probe
     * does not depend on the Flyway bean it is checking for. A schema with no history table means
     * Flyway never ran: that is 0 applied, not an error.
     */
    private List<String> appliedVersionedScripts() {
        Integer historyTables = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = 'flyway_schema_history' "
                        + "AND relkind = 'r' AND relnamespace = 'public'::regnamespace",
                Integer.class);
        if (historyTables == null || historyTables == 0) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success AND version IS NOT NULL",
                String.class);
    }
}
