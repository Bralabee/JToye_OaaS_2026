package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Issue #442 [SEC-02 / F-M7]: the other arm of the OpenAPI gate.
 *
 * This is the control that makes {@link OpenApiProdProfileGatingTest} mean
 * something. On its own, "prod returns non-200" is satisfied by any number of
 * uninteresting failures — a broken context, a typo in the matcher, the route
 * never existing. Showing that the SAME anonymous request returns 200 under dev
 * proves the difference is the profile gate and nothing else.
 *
 * It also pins the requirement in the issue's acceptance criteria that dev stays
 * open, which is what keeps local Swagger UI and the API tooling usable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({"dev", "test"})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class OpenApiDevProfileGatingTest {

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

    @Test
    void apiDocsStayAnonymousInDev() throws Exception {
        int status = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getStatus();
        assertEquals(200, status,
                "/v3/api-docs must stay anonymous outside prod — gating it everywhere would break "
                + "local Swagger UI and the API tooling the issue explicitly requires to keep working");
    }

    @Autowired
    private Environment environment;

    /**
     * INT-24 (QA council 20260902-134741, docs sweep DOC-9): the SERVED document must
     * advertise a base the service is actually listening on.
     *
     * <p>It advertised the hardcoded {@code http://localhost:8080} while the API binds
     * {@code server.port} (9090), so Swagger UI's "Try it out" - the whole reason this
     * route stays anonymous in dev, per the test above - posted at a refused port.
     *
     * <p>This is asserted on the SERVED JSON rather than on the bean, because the bean
     * test cannot see springdoc: no snapshot gate can ever catch a servers regression
     * ({@code check-openapi-snapshot-fresh.sh:146} and {@code OpenApiSnapshotTest.normalize}
     * both delete the block as environment-dependent, and the committed snapshot has
     * {@code .servers == null}).
     *
     * <p>The expected value is composed from {@code server.port} read out of the SAME
     * Environment the application binds from, so this cannot be satisfied by a literal
     * that happens to match today.
     */
    @Test
    void servedSpecAdvertisesThePortTheServiceListensOn() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString();

        String port = environment.getRequiredProperty("server.port");
        String expected = "\"url\":\"http://localhost:" + port + "\"";

        assertTrue(body.contains(expected),
                "the served spec must advertise http://localhost:" + port + " (the port this "
                + "service binds), got servers block: "
                + body.substring(Math.max(0, body.indexOf("\"servers\"")),
                                 Math.min(body.length(), body.indexOf("\"servers\"") + 200)));
        assertTrue(!body.contains("\"url\":\"http://localhost:8080\"") || "8080".equals(port),
                "http://localhost:8080 must not be advertised unless the service actually binds it");
    }
}
