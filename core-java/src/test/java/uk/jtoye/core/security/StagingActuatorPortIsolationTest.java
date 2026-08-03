package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Issue #442 [SEC-02 / F-M7]: staging must not serve actuator on the public port.
 *
 * The finding said the permitAll entries were "not profile-gated, therefore they
 * apply in prod". Prod turned out to be the one profile already mitigated — it
 * binds actuator to a separate management port. <b>Staging was the profile that
 * actually served metrics, env and configprops on the published application
 * port</b>, and the finding never mentioned it.
 *
 * This asserts the fix the same way {@link ManagementPortMetricsIntegrationTest}
 * asserts it for prod: not by reading the YAML back (which would pass on any file
 * containing the right string) but by showing the running context does not serve
 * the endpoint on the application port.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({"staging", "test"})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class StagingActuatorPortIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:18080/realms/jtoye-test");
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.cache.type", () -> "none");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * MockMvc serves the APPLICATION context only. With a distinct
     * management.server.port configured, actuator is bound elsewhere and these
     * paths are simply not routable here — which is exactly the property we want.
     */
    @Test
    void prometheusNotServedOnAppPortInStaging() throws Exception {
        int status = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(200, status,
                "/actuator/prometheus answered 200 on the APPLICATION port under staging — "
                + "the scrape surface is on the port the k8s Service publishes");
    }

    /**
     * NOT LOAD-BEARING, and labelled so deliberately. Measured in the break arm:
     * with the management port removed this assertion still PASSED, because
     * {@code /actuator/configprops} was never in the permitAll list and so is
     * covered by {@code anyRequest().authenticated()} whichever port it is on.
     * Only {@link #prometheusNotServedOnAppPortInStaging()} actually detects the
     * regression. Kept as a boundary assertion, not counted as proof — a test that
     * passes in both arms proves nothing and should not be presented as if it did.
     */
    @Test
    void configpropsNotServedOnAppPortInStaging() throws Exception {
        int status = mockMvc.perform(get("/actuator/configprops"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(200, status,
                "/actuator/configprops answered 200 on the APPLICATION port under staging");
    }

    /**
     * Issue #442: staging explicitly enables springdoc ("Enable Swagger in staging
     * for API testing"), so it is the profile where the OpenAPI gate has to hold —
     * a {@code !isProd} condition would have left the whole API surface anonymous
     * on a deployed environment while looking fixed.
     */
    @Test
    void apiDocsNotAnonymousInStaging() throws Exception {
        int status = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(200, status,
                "/v3/api-docs answered 200 to an UNAUTHENTICATED caller under the staging profile — "
                + "staging enables springdoc, so this is a real anonymous read of the API surface");
    }

    /**
     * Non-vacuity control, on the application port where MockMvc can see it.
     * Without this, "actuator is not routable" would also be satisfied by a
     * context that routes nothing at all.
     */
    @Test
    void theApplicationItselfStillServesItsPublicRoutes() throws Exception {
        int status = mockMvc.perform(get("/public/shops"))
                .andReturn().getResponse().getStatus();
        assertEquals(200, status,
                "/public/shops must still answer on the application port. If it does not, the "
                + "assertions above are vacuous — they would pass over a dead context.");
    }
}
