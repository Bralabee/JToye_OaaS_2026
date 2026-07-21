package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
// issue #99 do-now: enable the Kubernetes health groups so the probe-401
// regression tests below can hit /actuator/health/liveness + /readiness. The
// base test profile does NOT enable probes, so without this property both
// endpoints 404 instead of exercising the SecurityConfig permitAll match.
@SpringBootTest(properties = "management.endpoint.health.probes.enabled=true")
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

    /**
     * Production-shaped auth: a UUID-subject Keycloak JWT with the realm-admin authority
     * (implicit GROUP_ADMIN), mirroring {@code ShopAccessEnforcementIntegrationTest}.
     * Replaces the pre-Phase-23 {@code WithMockUser}, whose non-JWT principal the
     * fail-closed {@code ShopAccessService} (23-08) now correctly denies on the shop
     * read gate — the reason {@code shopsEndpointHasSecurityHeaders} regressed to 403.
     */
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j
                        .subject(java.util.UUID.randomUUID().toString())
                        .claim("email", "operator@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    @Test
    void shopsEndpointHasSecurityHeaders() throws Exception {
        // X-Tenant-Id: a tenant-less request is now rejected 400 before the
        // controller (QA-council error-code hardening); this test asserts the
        // security HEADERS on the happy path, so supply a tenant credential.
        mockMvc.perform(get("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", java.util.UUID.randomUUID().toString()))
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
    void hstsAbsentByDefaultProfile() throws Exception {
        // .secure(true) rules out the HTTPS-gate as the reason for HSTS absence —
        // under the default (non-prod) profile, SecurityConfig explicitly disables
        // HSTS, so even a simulated HTTPS request returns no Strict-Transport-Security.
        mockMvc.perform(get("/api/v1/shops").secure(true).with(adminJwt()))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void headerSnapshotMatchesGolden() throws Exception {
        // Regression guard: the curated list of SEC-03-scoped headers must exactly
        // match the committed golden snapshot. Any add / remove / rename of one of
        // these three headers in SecurityConfig's .headers(...) DSL fails this test,
        // forcing a deliberate snapshot update rather than a silent regression.
        var result = mockMvc.perform(get("/api/v1/shops").with(adminJwt())).andReturn();
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

    @Test
    void livenessProbeIsPubliclyAccessible() throws Exception {
        // issue #99 do-now (probe-401 regression guard): kubelet's startup and
        // liveness probes hit /actuator/health/liveness UNAUTHENTICATED
        // (k8s/base/core-java-deployment.yaml:181-195). Before this fix
        // SecurityConfig permitted only the EXACT "/actuator/health", so the
        // subpath 401'd → the pod never went Ready and every rollout failed.
        // Asserting 200 here pins the /actuator/health/** permitAll contract.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    void readinessProbeIsPubliclyAccessible() throws Exception {
        // Companion guard: readinessProbe points at /actuator/health/readiness
        // (k8s/base/core-java-deployment.yaml:196-202). Unauthenticated GET must
        // be 200 — the deploy smoke test (scripts/smoke-test.sh Test 5) asserts
        // the same path, so this locks both surfaces to the same contract.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
