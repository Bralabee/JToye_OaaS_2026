package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * HSTS absence test under the {@code dev} Spring profile (SEC-03).
 *
 * SecurityConfig's HSTS branch explicitly calls
 * {@code httpStrictTransportSecurity(hsts -> hsts.disable())} on non-prod
 * profiles, so even an HTTPS-simulated request returns no
 * {@code Strict-Transport-Security} header. This is the deterministic
 * counterpart to {@link SecurityHeadersProdProfileTest}.
 *
 * Threat mitigation: prevents HSTS leaking into developer HTTP traffic,
 * where browsers would then refuse plaintext localhost for the full
 * max-age window (a common dev-time footgun).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
// Both profiles active: "dev" makes env.getActiveProfiles() NOT contain "prod"
// (the SecurityConfig check), which exercises the hsts.disable() branch;
// "test" opts out of CacheConfig so we don't need a real Redis.
@ActiveProfiles({"dev", "test"})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class SecurityHeadersDevProfileTest {

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
        registry.add("rate-limiting.enabled", () -> "false");

        // The dev profile inherits the base application.yml issuer-uri default
        // (http://localhost:8085/realms/jtoye-dev). JwtDecoder is lazy; no JWKS
        // fetch happens during context startup with @WithMockUser tests.

        // RabbitMQ is kept in the context (RabbitTemplate is a compile-time
        // dependency of OrderEventPublisher) but redirected to port 0 and the
        // listener auto-startup is disabled so no real connection is attempted.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");

        // Redis: the "test" profile opts out of CacheConfig via @Profile("!test");
        // setting cache type to none is an extra guard.
        registry.add("spring.cache.type", () -> "none");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void hstsAbsentInDevProfile() throws Exception {
        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @WithMockUser
    void hstsAbsentEvenOverSecureInDevProfile() throws Exception {
        // .secure(true) proves the explicit hsts.disable() branch is doing the
        // work — not Spring's default secureRequestMatcher. If the non-prod
        // SecurityConfig branch were deleted, Spring's defaults would emit
        // HSTS on HTTPS requests and this assertion would fail, catching the
        // regression.
        mockMvc.perform(get("/api/v1/shops").secure(true))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
