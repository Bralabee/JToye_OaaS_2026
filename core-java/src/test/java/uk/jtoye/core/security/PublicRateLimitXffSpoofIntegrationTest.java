package uk.jtoye.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.config.DatabaseConfigurationValidator;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 37, plan 37-24 (D-19, RWO-006 / UXT-006 P0): the public limiter keys on a client identity
 * the client cannot forge.
 *
 * <p><b>The regression this pins.</b> Issue #88 bounded guest abuse with an IP-keyed public bucket,
 * and its resolver trusted the FIRST {@code X-Forwarded-For} hop — a value any client can write.
 * The persona run (UXT-006) rotated that header once per request and every request landed in a
 * fresh bucket: the limiter was there, and it bounded nothing. This test drives the REAL
 * {@link RateLimitInterceptor} (the established {@code PublicRateLimitIntegrationTest} pattern:
 * {@link MockHttpServletRequest} straight into {@code preHandle}, real Redis, real bucket) from one
 * socket peer that is NOT a trusted proxy — the default {@code jtoye.security.trusted-proxies}
 * is empty, trust none — and asserts the header is inert.
 *
 * <p><b>The control proves the bucket is reachable.</b> A rotation test that never sees a 429
 * could be green because the header is ignored, or because the limiter never fires at all. The
 * control arm sends the same flood from a second untrusted peer with NO header and must hit 429
 * at exactly the same request index. Both arms assert against the one configured capacity.
 *
 * <p><strong>Profile:</strong> {@code dev}, as in {@code PublicRateLimitIntegrationTest}: the
 * {@code test} profile switches the limiter off and the real Redis-backed {@code RateLimitConfig}
 * only loads outside it. The Testcontainers bootstrap role is a SUPERUSER, so the
 * {@code DatabaseConfigurationValidator} is neutralised; this test never opens a tenant
 * transaction.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
// 36-06: no Azurite in a dev-profile context, so the boot-time storage probe is opted out here
// (test-context only; D-08 keeps it ON in every runtime). 31.1-07: a per-run random DSAR key,
// never a literal, because DsarCipher refuses to start the context without one.
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}",
        // 37-28: ClientIpDigester refuses to start without a 64-hex key, and the dev profile has none
        // (CLIENT_IP_HMAC_KEY is a runtime secret); a per-run random key, the DSAR precedent above.
        "jtoye.storefront.client-ip-hmac-key=${random.value}${random.value}"})
@Tag("testcontainers")
class PublicRateLimitXffSpoofIntegrationTest {

    /** Small, test-declared limits: capacity = requests + burst, read from config, never a literal. */
    private static final int PUBLIC_REQUESTS_PER_MINUTE = 5;
    private static final int PUBLIC_BURST = 2;
    private static final int PUBLIC_CAPACITY = PUBLIC_REQUESTS_PER_MINUTE + PUBLIC_BURST;

    /** The plan's flood size: well past capacity, so a bucket that never fills is unmistakable. */
    private static final int FLOOD = 40;

    /** Two untrusted socket peers (TEST-NET-3), one per arm, so the arms cannot share a bucket. */
    private static final String SPOOFING_PEER = "203.0.113.9";
    private static final String HONEST_PEER = "203.0.113.10";

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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.timeout", () -> "1500ms");

        registry.add("rate-limiting.enabled", () -> "true");
        registry.add("rate-limiting.public.requests-per-minute", () -> String.valueOf(PUBLIC_REQUESTS_PER_MINUTE));
        registry.add("rate-limiting.public.burst", () -> String.valueOf(PUBLIC_BURST));
        registry.add("rate-limiting.public.window-seconds", () -> "60");

        // jtoye.security.trusted-proxies is deliberately NOT set: the default (trust none) is
        // the posture under test. A peer outside every trusted CIDR is the socket address, full stop.

        // Brokerless boot: keep the Rabbit beans, point them at a dead port, listeners off.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @MockitoBean
    private DatabaseConfigurationValidator databaseConfigurationValidator;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * UXT-006: forty requests from ONE untrusted peer, each carrying a DIFFERENT
     * {@code X-Forwarded-For}, must all land in the SAME bucket and be refused once the configured
     * capacity is spent. Before 37-24 every rotated header minted a fresh bucket and the loop
     * recorded 40 allowed, 0 throttled.
     */
    @Test
    void rotatingXffFromAnUntrustedPeerSharesOneBucket_andIs429OnceTheBurstIsSpent() throws Exception {
        TenantContext.clear();

        List<Integer> statuses = new ArrayList<>(FLOOD);
        for (int i = 1; i <= FLOOD; i++) {
            MockHttpServletRequest request = publicRequest(SPOOFING_PEER);
            // A fresh, attacker-chosen client address on every request (TEST-NET-1 and -2).
            request.addHeader("X-Forwarded-For", (i % 2 == 0 ? "192.0.2." : "198.51.100.") + i);
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = rateLimitInterceptor.preHandle(request, response, new Object());
            statuses.add(allowed ? 200 : response.getStatus());
        }

        long allowed = statuses.stream().filter(s -> s == 200).count();
        long throttled = statuses.stream().filter(s -> s == 429).count();

        assertThat(statuses)
                .as("every refusal must be a 429, never another status")
                .allMatch(s -> s == 200 || s == 429);
        assertThat(allowed)
                .as("exactly the configured capacity (%d) is allowed from one untrusted peer, "
                        + "however the header is rotated", PUBLIC_CAPACITY)
                .isEqualTo(PUBLIC_CAPACITY);
        assertThat(throttled)
                .as("every request past the capacity is refused (the header rotation bought nothing)")
                .isEqualTo(FLOOD - PUBLIC_CAPACITY);
        assertThat(statuses.indexOf(429))
                .as("the first 429 lands exactly at request capacity+1 (0-based index %d)", PUBLIC_CAPACITY)
                .isEqualTo(PUBLIC_CAPACITY);
    }

    /**
     * Control: the same flood from a second untrusted peer with NO header reaches 429 at the same
     * index. This is what makes the rotation arm's "no fresh bucket" reading a measurement of the
     * header handling and not of a limiter that never fires.
     */
    @Test
    void control_noXffFromAnUntrustedPeerIs429AtTheSameCount() throws Exception {
        TenantContext.clear();

        List<Integer> statuses = new ArrayList<>(FLOOD);
        for (int i = 1; i <= FLOOD; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = rateLimitInterceptor.preHandle(publicRequest(HONEST_PEER), response, new Object());
            statuses.add(allowed ? 200 : response.getStatus());
        }

        assertThat(statuses.stream().filter(s -> s == 200).count())
                .as("control: the configured capacity (%d) is allowed with no header at all", PUBLIC_CAPACITY)
                .isEqualTo(PUBLIC_CAPACITY);
        assertThat(statuses.indexOf(429))
                .as("control: the first 429 lands at request capacity+1 (0-based index %d) — "
                        + "the same count the rotation arm must show", PUBLIC_CAPACITY)
                .isEqualTo(PUBLIC_CAPACITY);
    }

    private static MockHttpServletRequest publicRequest(String socketPeer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/shops");
        request.setRequestURI("/public/shops");
        request.setRemoteAddr(socketPeer);
        return request;
    }
}
