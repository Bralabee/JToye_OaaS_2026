package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT4-02 (D-02): the auto-configuration census. It measures which auto-configurations ACTIVATE in a
 * full application context, and it permanently asserts that the essential ones still do.
 *
 * <p><b>Why this exists.</b> Boot 4 ships each auto-configuration in its own module, and a missing
 * module is not an error: the context starts, every other test stays green, and a capability is gone.
 * 38-04's {@link Boot4ModuleLivenessIntegrationTest} probes the modules we knew to check. This census
 * finds the ones we did not think of: 38-13 ran it once on the explicit-starter build and once on a
 * throwaway {@code spring-boot-starter-classic} swap of the same commit, and classified every
 * auto-configuration that only the classic route activated
 * ({@code .planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census.txt}).
 *
 * <p><b>The activated set.</b> Every source in {@link ConditionEvaluationReport#getConditionAndOutcomesBySource()}
 * that is a full match, plus {@link ConditionEvaluationReport#getUnconditionalClasses()}, normalised to the
 * declaring top-level class (a {@code Outer$Inner} or {@code Outer#beanMethod} source counts for
 * {@code Outer}), kept only if that class is an auto-configuration candidate listed in some
 * {@code META-INF/spring/...AutoConfiguration.imports}. The report adds a no-match ancestor outcome to
 * every nested source whose enclosing class did not match, so a nested match never counts for an
 * auto-configuration that was skipped.
 *
 * <p><b>Writer.</b> With the environment variable {@code JTOYE_CENSUS_OUT} set, the sorted set is written to
 * that path, one class per line. Without it the test only asserts.
 *
 * <p><b>Context.</b> The {@code test} profile, Testcontainers Postgres and {@link AutoConfigureTracing} /
 * {@link AutoConfigureMetrics}, as in the liveness test. Two things the {@code test} profile switches
 * off are switched back on here, because otherwise the census is blind to their modules in BOTH arms:
 * <ul>
 *   <li>{@code application-test.yml} excludes the two Data Redis auto-configurations. The exclude list is
 *       cleared, and {@link #exclusionsAreCleared()} asserts that nothing is excluded.</li>
 *   <li>{@code CacheConfig}, the only {@code @EnableCaching} in main code, is {@code @Profile("!test")}, so a
 *       test-profile context has no caching at all. {@link CachingAsInProduction} stands in for its
 *       {@code @EnableCaching}, with {@code spring.cache.type=redis} as {@code application.yml} sets it.
 *       Nothing connects to Redis: the connection factory is lazy and this test makes no cached call.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTracing
@AutoConfigureMetrics
@Tag("testcontainers")
class AutoConfigurationCensusIntegrationTest {

    static final String CENSUS_OUT_ENV = "JTOYE_CENSUS_OUT";

    /**
     * Capability -> the Boot 4.1.1 auto-configuration that must activate for it. The class names were
     * read from each module's {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
     * in the 4.1.1 jars. A future upgrade that drops one fails here by name (38-13: with an invented
     * class added to this map the test failed listing exactly that entry, and nothing else).
     */
    static final Map<String, String> MUST_HAVE = mustHave();

    private static Map<String, String> mustHave() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("web MVC (spring-boot-webmvc)",
                "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration");
        m.put("dispatcher servlet (spring-boot-webmvc)",
                "org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration");
        m.put("security (spring-boot-security)",
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration");
        m.put("OAuth2 resource server (spring-boot-security-oauth2-resource-server)",
                "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration");
        m.put("JPA / Hibernate (spring-boot-hibernate via spring-boot-starter-data-jpa)",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration");
        m.put("Spring Data JPA repositories (spring-boot-data-jpa)",
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration");
        m.put("Flyway (spring-boot-flyway)",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
        m.put("Data Redis (spring-boot-data-redis)",
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration");
        m.put("cache (spring-boot-cache)",
                "org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration");
        m.put("RabbitMQ (spring-boot-amqp)",
                "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration");
        m.put("WebSocket messaging (spring-boot-websocket)",
                "org.springframework.boot.websocket.autoconfigure.servlet.WebSocketMessagingAutoConfiguration");
        m.put("mail (spring-boot-mail)",
                "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration");
        m.put("validation (spring-boot-validation)",
                "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration");
        m.put("actuator health endpoint (spring-boot-health)",
                "org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration");
        m.put("Prometheus export (spring-boot-micrometer-metrics)",
                "org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration");
        m.put("Brave tracing (spring-boot-micrometer-tracing-brave)",
                "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration");
        m.put("Zipkin (spring-boot-zipkin)",
                "org.springframework.boot.zipkin.autoconfigure.ZipkinAutoConfiguration");
        m.put("Zipkin span export through Brave (spring-boot-micrometer-tracing-brave)",
                "org.springframework.boot.micrometer.tracing.brave.autoconfigure.zipkin.ZipkinWithBraveTracingAutoConfiguration");
        m.put("RestClient (spring-boot-restclient)",
                "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration");
        m.put("RestTemplate builder (spring-boot-restclient)",
                "org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration");
        m.put("WebClient (spring-boot-webclient)",
                "org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration");
        return Collections.unmodifiableMap(m);
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // application-test.yml excludes the Data Redis auto-configurations; clear the list so the census
        // can see spring-boot-data-redis in either arm. exclusionsAreCleared() proves it took effect.
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("spring.cache.type", () -> "redis");
    }

    /** Stands in for {@code CacheConfig}'s {@code @EnableCaching}, which is {@code @Profile("!test")}. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableCaching
    static class CachingAsInProduction {
    }

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    @DisplayName("the census context excludes no auto-configuration, so no module is hidden by configuration")
    void exclusionsAreCleared() {
        assertThat(report().getExclusions())
                .as("an excluded auto-configuration is invisible to the census in both arms")
                .isEmpty();
    }

    @Test
    @DisplayName("every must-have auto-configuration activates (writes the activated set to $JTOYE_CENSUS_OUT when set)")
    void mustHaveAutoConfigurationsActivate() throws IOException {
        Set<String> activated = activatedAutoConfigurations();
        System.out.println("[census] activated auto-configurations: " + activated.size()
                + ", exclusions: " + report().getExclusions());

        String out = System.getenv(CENSUS_OUT_ENV);
        if (out != null && !out.isBlank()) {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, String.join("\n", activated) + "\n", StandardCharsets.UTF_8);
            System.out.println("[census] wrote " + activated.size() + " entries to " + path.toAbsolutePath());
        }

        assertThat(activated)
                .as("positive control: the census found no activated auto-configuration at all, so any "
                        + "absence below would prove nothing")
                .isNotEmpty();

        List<String> missing = MUST_HAVE.entrySet().stream()
                .filter(e -> !activated.contains(e.getValue()))
                .map(e -> e.getKey() + " -> " + e.getValue())
                .toList();
        assertThat(missing)
                .as("must-have auto-configurations that did NOT activate. Boot 4 puts each in its own module and "
                        + "a missing module is silent: add the starter that carries it (38-13 census)")
                .isEmpty();
    }

    private ConditionEvaluationReport report() {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        return ConditionEvaluationReport.get(beanFactory);
    }

    /** The activated auto-configuration classes, sorted and de-duplicated (see the class Javadoc). */
    private Set<String> activatedAutoConfigurations() {
        Set<String> candidates = new TreeSet<>();
        ImportCandidates.load(AutoConfiguration.class, context.getClassLoader()).forEach(candidates::add);
        assertThat(candidates)
                .as("positive control: no AutoConfiguration.imports candidates on the classpath")
                .isNotEmpty();

        ConditionEvaluationReport report = report();
        Set<String> activated = new TreeSet<>();
        report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            if (outcomes.isFullMatch()) {
                activated.add(declaringClass(source));
            }
        });
        report.getUnconditionalClasses().forEach(source -> activated.add(declaringClass(source)));
        activated.retainAll(candidates);
        return activated;
    }

    /** {@code a.b.Outer$Inner#method} -> {@code a.b.Outer}. */
    static String declaringClass(String source) {
        String s = source;
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int dollar = s.indexOf('$');
        if (dollar >= 0) {
            s = s.substring(0, dollar);
        }
        return s;
    }
}
