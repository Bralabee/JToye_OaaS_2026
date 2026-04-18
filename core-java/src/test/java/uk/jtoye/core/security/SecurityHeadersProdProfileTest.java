package uk.jtoye.core.security;

import org.hamcrest.Matchers;
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
 * HSTS presence test under the {@code prod} Spring profile (SEC-03).
 *
 * Spring's HstsHeaderWriter only emits {@code Strict-Transport-Security} on
 * requests where {@code isSecure()} is true. MockMvc's default request is NOT
 * marked secure — callers must use {@code .secure(true)} to simulate HTTPS.
 * This is the "HSTS profile test trap" documented in 12-RESEARCH.md §7.1:
 * without {@code .secure(true)}, a prod-profile test will pass even when HSTS
 * is missing, giving a false sense of security.
 *
 * Threat mitigation: T-12-04 (protocol downgrade MITM) — HSTS with
 * max-age=31536000; includeSubDomains pins the browser to HTTPS for one year.
 *
 * Note on bootstrapping prod profile in tests: application-prod.yml requires
 * neither a real Keycloak (JwtDecoder is lazily constructed against the JWKS
 * endpoint) nor a real Redis (Spring Cache tolerates an unreachable broker at
 * startup). We do still override datasource properties via @DynamicPropertySource
 * so prod config (which expects a managed PostgreSQL) connects to the ephemeral
 * Testcontainers instance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
// Both profiles active: "prod" drives the HSTS-enable branch in SecurityConfig
// (which reads env.getActiveProfiles().contains("prod")); "test" opts out of
// CacheConfig (annotated @Profile("!test")) so we don't need a real Redis.
@ActiveProfiles({"prod", "test"})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class SecurityHeadersProdProfileTest {

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

        // Prod profile expects a real Keycloak; point JwtDecoder at a deterministic
        // URL. The decoder is lazily initialised — the JWKS endpoint is NOT hit
        // during context startup, only when a real Bearer token is validated
        // (@WithMockUser bypasses the JWT flow entirely).
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:18080/realms/jtoye-test");

        // Prod profile requires REDIS_PASSWORD; supply a placeholder so property
        // resolution does not fail at startup. The Redis connection itself is
        // not exercised in this test.
        registry.add("spring.data.redis.password", () -> "test-password");

        // Rate limiting prod default is enabled — disable to keep header tests
        // deterministic (they do not care about rate-limit headers).
        registry.add("rate-limiting.enabled", () -> "false");

        // RabbitMQ is kept in the context (RabbitTemplate is a compile-time
        // dependency of OrderEventPublisher) but redirected to port 0 and the
        // listener auto-startup is disabled so no real connection is attempted.
        // This matches the pattern in src/test/resources/application-test.yml.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");

        // Redis is only referenced via CacheConfig (@Profile("!test")). The
        // "test" profile in @ActiveProfiles above is what opts out, so no
        // further Redis override is strictly required — but setting the cache
        // type to none is a belt-and-braces guard against classpath scanning
        // picking up a stray RedisCacheConfiguration.
        registry.add("spring.cache.type", () -> "none");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void hstsPresentInProdProfile() throws Exception {
        // .secure(true) is REQUIRED — HstsHeaderWriter only emits HSTS when
        // isSecure() returns true. Without this flag a prod-profile test
        // passes falsely (RESEARCH.md §7.1 "HSTS profile test trap").
        mockMvc.perform(get("/api/v1/shops").secure(true))
                .andExpect(header().string("Strict-Transport-Security",
                        Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        Matchers.containsString("includeSubDomains")));
    }

    @Test
    @WithMockUser
    void hstsPresentOnSecureRequestOnly() throws Exception {
        // Documents the other half of the HSTS trap: WITHOUT .secure(true) the
        // HstsHeaderWriter sees isSecure()==false and emits nothing, even in
        // prod. This is HstsHeaderWriter's default secureRequestMatcher
        // behaviour — a deployment behind a TLS-terminating reverse proxy
        // must forward X-Forwarded-Proto for HSTS to fire on plain HTTP
        // internal traffic, or the writer must be re-configured. The assertion
        // below is a pinned-behaviour test, not a defect.
        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
