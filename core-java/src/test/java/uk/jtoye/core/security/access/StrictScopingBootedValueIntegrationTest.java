package uk.jtoye.core.security.access;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The booted-value instrument for D-06 (Phase 37-02, RWO-004, threat T-37-02).
 *
 * <p>A suite that runs under the wrong strict-scoping value certifies nothing about the default:
 * green under OFF says nothing about ON. This class reads the value the {@code ShopAccessService}
 * bean in a freshly booted context actually holds and asserts it is the value the ENVIRONMENT
 * declares ({@code ACCESS_STRICT_SCOPING}, falling back to the {@code application.yml} default
 * {@code true}, flipped from {@code false} by 37-04 / D-06). Run with a value exported in the same
 * shell as Gradle, it proves the variable reached the bean; run with it unset, it proves the
 * default did. Either way the reading is taken from the context bean (proxy-unwrapped), never from a
 * test's belief about the default.
 *
 * <p>It was shown red by asserting the opposite value first, in both env modes (37-02 SUMMARY
 * records both failing runs: {@code expected: true but was: false} unset, and
 * {@code expected: false but was: true} with {@code ACCESS_STRICT_SCOPING=true}).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StrictScopingBootedValueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private Environment environment;

    @Test
    void bootedStrictScopingEqualsTheValueTheEnvironmentDeclares() {
        // 37-04 (D-06): the application.yml default is now true, so "unset" expects true.
        String declared = System.getenv().getOrDefault("ACCESS_STRICT_SCOPING", "true");
        boolean expected = Boolean.parseBoolean(declared);

        boolean booted = StrictScopingGuard.current(shopAccessService);

        System.out.println("event=strict_scoping_booted_value env.ACCESS_STRICT_SCOPING="
                + System.getenv("ACCESS_STRICT_SCOPING") + " expected=" + expected + " booted=" + booted
                + " property=" + environment.getProperty("jtoye.access.strict-scoping"));

        assertThat(booted)
                .as("ShopAccessService.strictScoping booted from ACCESS_STRICT_SCOPING=%s", declared)
                .isEqualTo(expected);
        // The Spring property is the bean's only source; if the two ever disagree the bean was
        // mutated after boot (a leaked test write) or bound from somewhere else.
        assertThat(environment.getProperty("jtoye.access.strict-scoping", Boolean.class))
                .as("resolved jtoye.access.strict-scoping")
                .isEqualTo(expected);
    }
}
