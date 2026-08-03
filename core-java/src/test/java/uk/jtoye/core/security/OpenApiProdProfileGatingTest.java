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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Issue #442 [SEC-02 / F-M7]: the API description must not be anonymous in prod.
 *
 * This is the half of that finding which genuinely reached a deployed instance.
 * The finding's headline claim — that the metrics endpoint is unauthenticated in
 * production — is FALSIFIED and deliberately not "fixed" here: prod binds actuator
 * to a separate management port and the k8s Service publishes only the application
 * port, which {@link ManagementPortMetricsIntegrationTest} already proves in both
 * directions. The OpenAPI matchers had no such mitigation: they were permitAll with
 * no profile condition, on the port the Service does publish.
 *
 * Deliberately asserted WITHOUT {@code @WithMockUser} — the question is precisely
 * what an unauthenticated caller gets. A test that authenticated first would pass
 * on the unfixed tree and prove nothing.
 *
 * Profile bootstrapping mirrors {@link SecurityHeadersProdProfileTest}: "prod"
 * drives the gating branch in SecurityConfig, "test" opts out of CacheConfig so no
 * real Redis is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({"prod", "test"})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class OpenApiProdProfileGatingTest {

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
     * Asserted as "not 2xx" rather than a specific 401. The security chain may
     * answer 401 or 403 depending on entry-point wiring, and pinning the exact
     * code would make this test fail on a change that is still secure — a gate
     * that cries wolf gets deleted. What must never happen is a successful
     * anonymous read of the API surface.
     */
    @Test
    void apiDocsNotAnonymousInProd() throws Exception {
        int status = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(200, status,
                "/v3/api-docs answered 200 to an UNAUTHENTICATED caller under the prod profile — "
                + "the full API surface is readable without a credential on the published app port");
    }

    @Test
    void swaggerUiNotAnonymousInProd() throws Exception {
        int status = mockMvc.perform(get("/swagger-ui/index.html"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(200, status,
                "/swagger-ui/index.html answered 200 to an UNAUTHENTICATED caller under the prod profile");
    }

    /**
     * Non-vacuity control. If the application answered non-200 to everything
     * anonymous — a broken context, a misrouted chain — the two assertions above
     * would pass while proving nothing.
     *
     * {@code /public/shops} is used rather than the more obvious
     * {@code /actuator/health}, and the reason is itself evidence for this issue:
     * under the prod profile {@code /actuator/health} returns <b>404</b> here,
     * because prod binds the whole actuator surface to a separate management port
     * that MockMvc's application context does not serve. That was measured, not
     * assumed — the first version of this control asserted 200 and failed at 404.
     * It independently corroborates why the finding's metrics claim is falsified,
     * and it is why the kubelet probes in k8s/base/core-java-deployment.yaml
     * target the management port rather than 9090.
     */
    @Test
    void aGenuinelyPublicRouteIsStillAnonymousInProd() throws Exception {
        int status = mockMvc.perform(get("/public/shops"))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertEquals(200, status,
                "/public/shops must remain anonymous — it is the storefront's unauthenticated read. "
                + "If this is non-200 the other assertions in this class are vacuous.");
    }
}
