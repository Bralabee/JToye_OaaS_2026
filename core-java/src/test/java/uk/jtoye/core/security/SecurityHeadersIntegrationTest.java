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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Spring Security response headers (SEC-03).
 *
 * Asserts the ASVS 14.4.x-mandated browser security headers are emitted on every
 * Spring Boot response: X-Frame-Options, X-Content-Type-Options, Referrer-Policy.
 *
 * HSTS is profile-gated and covered in {@link SecurityHeadersProdProfileTest} and
 * {@link SecurityHeadersDevProfileTest}. The default test profile asserted here
 * does NOT emit HSTS even on .secure(true) requests.
 *
 * Threat mitigation map:
 * - T-12-01 (clickjacking)        -> X-Frame-Options: DENY
 * - T-12-02 (MIME sniff uplift)   -> X-Content-Type-Options: nosniff
 * - T-12-03 (referrer leak)       -> Referrer-Policy: strict-origin-when-cross-origin
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class SecurityHeadersIntegrationTest {

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
        // Override application-test.yml defaults (H2 + Flyway disabled) so the
        // Testcontainers PostgreSQL image is used with real Flyway migrations —
        // required because RLS policies and Postgres-specific types only exist
        // on real Postgres.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void shopsEndpointHasSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void headersPresentOn401() throws Exception {
        // Unauthenticated request: Spring Security header writers fire BEFORE the
        // authentication filter rejects, so baseline browser headers must still ship
        // on 401 responses — otherwise an unauthenticated error page is
        // clickjackable / MIME-confusable.
        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @WithMockUser
    void hstsAbsentByDefaultProfile() throws Exception {
        // .secure(true) rules out the HTTPS-gate as the reason for HSTS absence —
        // under the default (non-prod) profile, SecurityConfig explicitly disables
        // HSTS, so even a simulated HTTPS request returns no Strict-Transport-Security.
        mockMvc.perform(get("/api/v1/shops").secure(true))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @WithMockUser
    void headerSnapshotMatchesGolden() throws Exception {
        // Regression guard: the curated list of SEC-03-scoped headers must exactly
        // match the committed golden snapshot. Any add / remove / rename of one of
        // these three headers in SecurityConfig's .headers(...) DSL fails this test,
        // forcing a deliberate snapshot update rather than a silent regression.
        var result = mockMvc.perform(get("/api/v1/shops")).andReturn();
        var response = result.getResponse();

        // Curate to SEC-03-scoped headers only — ignore noise like Cache-Control,
        // Date, Content-Type, X-XSS-Protection (Spring 6 sets X-XSS-Protection: 0
        // by default, deprecated per OWASP).
        var interesting = java.util.List.of(
                "Referrer-Policy",
                "X-Content-Type-Options",
                "X-Frame-Options"
        );
        var actual = interesting.stream()
                .sorted()
                .map(h -> h + ": " + response.getHeader(h))
                .collect(java.util.stream.Collectors.joining("\n"));

        var goldenPath = java.nio.file.Path.of("src/test/resources/security-headers-snapshot.txt");
        var expected = java.nio.file.Files.readString(goldenPath).trim();

        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}
