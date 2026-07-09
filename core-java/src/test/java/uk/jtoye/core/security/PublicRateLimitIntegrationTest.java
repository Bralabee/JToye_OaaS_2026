package uk.jtoye.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.config.DatabaseConfigurationValidator;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Testcontainers integration test for issue #88 [P1-6]: tenant-less {@code /public/**}
 * requests must be rate-limited by client IP at the Core layer, independently of tenant
 * buckets, while preserving the issue #86 fail-open-with-alarm semantics.
 *
 * <p>Runs against real Postgres + real Redis (Testcontainers) and drives
 * {@link RateLimitInterceptor#preHandle} directly with
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse} — the established pattern
 * from {@code RedisFaultInjectionIntegrationTest} (NOT MockMvc). Three proofs in one flow:
 *
 * <ul>
 *   <li><b>ASSERT A — FLOOD:</b> a tenant-less {@code /public/shops} flood from a single IP
 *       is allowed up to (publicRequestsPerMinute + publicBurst) then returns 429 with a
 *       non-null {@code Retry-After} header (T-88-01).</li>
 *   <li><b>ASSERT B — TENANT UNAFFECTED:</b> with the public IP bucket exhausted, a
 *       tenant-scoped {@code /api/v1/products} request is still allowed — the public and
 *       tenant Redis keyspaces are independent (T-88-03).</li>
 *   <li><b>ASSERT C — FAIL-OPEN:</b> with Redis stopped, a fresh tenant-less
 *       {@code /public/shops} request fails OPEN (returns true, status 200, no 500/hang)
 *       within a bounded time — issue #86 semantics hold for the public path (T-88-04).</li>
 * </ul>
 *
 * <p><strong>Profile:</strong> {@code dev} (a known profile — the {@code ActiveProfileValidator}
 * fail-fasts on unknown profiles, and {@code dev} is NOT {@code test}, so the real
 * {@code RateLimitConfig} ProxyManager bean loads). The Testcontainers bootstrap role is a
 * SUPERUSER, so the {@code DatabaseConfigurationValidator} is neutralised via {@code @MockBean}
 * (this test proves rate limiting, not RLS isolation, and never opens a tenant DB transaction).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
@Tag("testcontainers")
class PublicRateLimitIntegrationTest {

    // Small deterministic limits so the flood assertion is fast and unambiguous. These MUST
    // match the @DynamicPropertySource overrides below (capacity = requests + burst).
    private static final int PUBLIC_REQUESTS_PER_MINUTE = 5;
    private static final int PUBLIC_BURST = 2;
    private static final int PUBLIC_CAPACITY = PUBLIC_REQUESTS_PER_MINUTE + PUBLIC_BURST;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Postgres (context boot + Flyway).
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");

        // Redis — the hand-rolled rate-limit client reads these. Short command timeout so the
        // down-path (ASSERT C) fails FAST and bounded.
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.timeout", () -> "1500ms");

        // Rate limiter ACTIVE, with tiny public limits for a deterministic flood.
        registry.add("rate-limiting.enabled", () -> "true");
        registry.add("rate-limiting.public.requests-per-minute", () -> String.valueOf(PUBLIC_REQUESTS_PER_MINUTE));
        registry.add("rate-limiting.public.burst", () -> String.valueOf(PUBLIC_BURST));
        registry.add("rate-limiting.public.window-seconds", () -> "60");

        // Boot brokerless — keep the Rabbit autoconfig beans (hard deps of OrderService et al.)
        // but point them at a dead port with listener auto-startup off (lazy connections).
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    // The DatabaseConfigurationValidator (@Profile("!test")) fail-fasts on a SUPERUSER DB user;
    // the Testcontainers bootstrap role IS a superuser and we deliberately keep it (this test
    // proves rate limiting, not RLS). Neutralise the validator so the dev-profile context boots.
    @MockBean
    private DatabaseConfigurationValidator databaseConfigurationValidator;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void publicFloodIs429_tenantUnaffected_andFailsOpenWhenRedisDown() throws Exception {
        // Guarantee a clean (tenant-less) state on this thread.
        TenantContext.clear();

        // ---- ASSERT A — FLOOD: allowed up to capacity, then 429 + Retry-After ----
        String floodIp = "203.0.113.7";
        for (int i = 1; i <= PUBLIC_CAPACITY; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            boolean allowed = rateLimitInterceptor.preHandle(publicRequest(floodIp), resp, new Object());
            assertThat(allowed)
                    .as("public call %d/%d (within capacity) should be allowed", i, PUBLIC_CAPACITY)
                    .isTrue();
        }

        MockHttpServletResponse floodResp = new MockHttpServletResponse();
        boolean overLimit = rateLimitInterceptor.preHandle(publicRequest(floodIp), floodResp, new Object());
        assertThat(overLimit)
                .as("the call past (requests + burst) must be throttled")
                .isFalse();
        assertThat(floodResp.getStatus())
                .as("throttled public request returns HTTP 429")
                .isEqualTo(429);
        assertThat(floodResp.getHeader("Retry-After"))
                .as("429 must carry a Retry-After header")
                .isNotNull();

        // ---- ASSERT B — TENANT UNAFFECTED by the exhausted public bucket ----
        // The tenant bucket is a separate Redis keyspace (rate_limit:: vs rl:public::).
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        try {
            MockHttpServletRequest tenantReq = new MockHttpServletRequest("GET", "/api/v1/products");
            tenantReq.setRequestURI("/api/v1/products");
            MockHttpServletResponse tenantResp = new MockHttpServletResponse();
            boolean tenantAllowed = rateLimitInterceptor.preHandle(tenantReq, tenantResp, new Object());
            assertThat(tenantAllowed)
                    .as("tenant request must be unaffected by the exhausted public IP bucket")
                    .isTrue();
            assertThat(tenantResp.getStatus())
                    .as("tenant request is not throttled (independent keyspace)")
                    .isEqualTo(200);
        } finally {
            TenantContext.clear();
        }

        // ---- ASSERT C — FAIL-OPEN on Redis outage for the public path ----
        redis.stop();

        // assertTimeoutPreemptively runs on a SEPARATE thread; the public path is tenant-less
        // so no ThreadLocal setup is needed. A distinct IP gives a fresh bucket.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            boolean allowed = rateLimitInterceptor.preHandle(publicRequest("198.51.100.42"), resp, new Object());
            assertThat(allowed)
                    .as("public path must fail OPEN (bounded) when Redis is down — no 500")
                    .isTrue();
            assertThat(resp.getStatus())
                    .as("no 429/500 on the bounded fail-open path")
                    .isEqualTo(200);
        });
    }

    private static MockHttpServletRequest publicRequest(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/shops");
        request.setRequestURI("/public/shops");
        request.addHeader("X-Forwarded-For", clientIp);
        return request;
    }
}
