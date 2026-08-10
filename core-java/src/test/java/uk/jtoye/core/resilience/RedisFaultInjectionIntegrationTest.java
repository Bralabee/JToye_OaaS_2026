package uk.jtoye.core.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
import uk.jtoye.core.security.RateLimitInterceptor;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.ShopDto;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Fault-injection integration test for issue #86 [P1-4]: a Redis outage must NOT
 * become a full-platform outage. Runs against real Postgres + real Redis
 * (Testcontainers), warms the {@code @Cacheable} read, then STOPS Redis mid-test
 * and proves both resilience paths:
 *
 * <ul>
 *   <li><b>ASSERT A</b> — a {@code @Cacheable} read still returns the entity from
 *       the source-of-truth (no exception, no 500) within a bounded time. Proves
 *       {@code RedisCacheErrorHandler} degraded the failed Redis GET/PUT to
 *       log-and-continue (AC1).</li>
 *   <li><b>ASSERT B</b> — {@code RateLimitInterceptor.preHandle} returns true
 *       within a bounded time (no ~60s hang, no 500). Proves the explicit Lettuce
 *       command timeout + fail-open-with-alarm (AC2).</li>
 * </ul>
 *
 * <p><strong>Profile:</strong> runs under {@code dev} (a known profile — the
 * {@code ActiveProfileValidator} added in issue #78 fail-fasts on any unknown
 * profile, so a bespoke "redisfault" profile cannot be used). {@code dev} is
 * behaviour-neutral and, crucially, is NOT {@code test}, so {@code CacheConfig}
 * ({@code @Profile("!test")}) loads and caching + the custom
 * {@code CacheErrorHandler} are actually active. Everything else is supplied via
 * {@code @DynamicPropertySource}.
 *
 * <p>This test proves resilience, not tenant isolation, so it keeps the
 * Testcontainers SUPERUSER role (no NOSUPERUSER downgrade) and drives
 * {@link TenantContext} directly for seeding and reads. Note
 * {@code assertTimeoutPreemptively} runs its body on a SEPARATE thread, so
 * {@link TenantContext} (a ThreadLocal) is set INSIDE each lambda.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
@Tag("testcontainers")
// #283: drives a gated service read to exercise cache degradation under a Redis outage; the
// subject is the fallback-to-source-of-truth behaviour, not the gate.
@uk.jtoye.core.testsupport.AsSystemHarness
class RedisFaultInjectionIntegrationTest {

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
        // Postgres (mirror IntegrationTestSupport, but WITHOUT rate-limiting.enabled=false —
        // this test needs the rate limiter ACTIVE).
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");

        // Redis — the AUTO-configured cache factory AND the hand-rolled rate-limit
        // client both read these. Short command timeout so the down-path fails FAST.
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.timeout", () -> "1500ms");

        // Rate limiter ACTIVE (the whole point of ASSERT B).
        registry.add("rate-limiting.enabled", () -> "true");

        // Boot brokerless. NOTE: we deliberately do NOT exclude RabbitAutoConfiguration
        // here — RabbitTemplate/ConnectionFactory are hard constructor dependencies of
        // OrderEventPublisher/OrderService and RabbitMQConfig.rabbitListenerContainerFactory,
        // so excluding the autoconfig fails context startup with a missing-RabbitTemplate
        // error. Instead we keep the autoconfig beans but point them at a dead port with
        // listener auto-startup disabled; Lettuce/Rabbit connections are lazy, so the
        // context boots without a live broker (the CrossTenantSpoofIntegrationTest pattern).
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private ShopService shopService;
    @Autowired private ShopRepository shopRepository;
    @Autowired private RateLimitInterceptor rateLimitInterceptor;
    @Autowired private JdbcTemplate jdbcTemplate;

    // The DatabaseConfigurationValidator (@Profile("!test"), @EventListener on
    // ApplicationReadyEvent) fail-fasts when the DB user is a SUPERUSER. The
    // Testcontainers bootstrap role IS a superuser, and this test deliberately
    // keeps it (it proves resilience, not RLS isolation). Every other integration
    // test dodges the validator via @ActiveProfiles("test"), but we need a non-test
    // profile for CacheConfig to load — so neutralise the validator here instead.
    @MockBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000086f1");
    private UUID shopId;

    @BeforeEach
    void seed() {
        // Unique tenant name (tenants.name is UNIQUE; ON CONFLICT (id) can't arbitrate a name clash).
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT, "Redis Fault Tenant");

        TenantContext.set(TENANT);
        try {
            Shop shop = new Shop();
            shop.setTenantId(TENANT);
            shop.setName("Resilience Shop");
            shop.setSlug("redis-fault-" + UUID.randomUUID().toString().substring(0, 8));
            shop.setPublished(true);
            // saveAndFlush commits the seed so the post-outage DB read (ASSERT A) sees it.
            shopId = shopRepository.saveAndFlush(shop).getId();
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void redisOutage_cachedReadDegradesToSourceOfTruth_andRateLimiterFailsOpen_bounded() {
        // (1)+(2) Warm the Redis-backed cache while Redis is UP and assert the read works.
        TenantContext.set(TENANT);
        try {
            Optional<ShopDto> warm = shopService.getShopById(shopId);
            assertThat(warm).as("warm cached read returns the seeded shop").isPresent();
            assertThat(warm.get().getName()).isEqualTo("Resilience Shop");
        } finally {
            TenantContext.clear();
        }

        // (3) Redis goes DOWN mid-test.
        redis.stop();

        // (4) ASSERT A — the @Cacheable read still returns from source-of-truth, no
        // throw, within a bounded time (CacheErrorHandler degraded the failed GET).
        // TenantContext is set INSIDE the lambda because assertTimeoutPreemptively
        // runs the body on a separate thread (ThreadLocal does not propagate).
        // #283: the SystemPrincipal declaration is made inside for exactly the same reason —
        // it is a plain ThreadLocal and is deliberately not inherited, so the class-level
        // @AsSystemHarness declaration on the test thread does not reach this body.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            TenantContext.set(TENANT);
            try {
                Optional<ShopDto> degraded = SystemPrincipal.asSystem(() -> shopService.getShopById(shopId));
                assertThat(degraded)
                        .as("Redis down ⇒ cached read must fall back to source-of-truth, not 500")
                        .isPresent();
                assertThat(degraded.get().getName()).isEqualTo("Resilience Shop");
            } finally {
                TenantContext.clear();
            }
        });

        // (5) ASSERT B — preHandle returns true within a bounded time (bounded
        // fail-open, no ~60s hang, no 500) for a non-excluded path.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            TenantContext.set(TENANT);
            try {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
                request.setRequestURI("/api/v1/products");
                MockHttpServletResponse response = new MockHttpServletResponse();

                boolean allowed = rateLimitInterceptor.preHandle(request, response, new Object());

                assertThat(allowed)
                        .as("rate limiter must fail OPEN (bounded) when Redis is down")
                        .isTrue();
                assertThat(response.getStatus())
                        .as("no 429/500 on the bounded fail-open path")
                        .isEqualTo(200);
            } finally {
                TenantContext.clear();
            }
        });
    }
}
