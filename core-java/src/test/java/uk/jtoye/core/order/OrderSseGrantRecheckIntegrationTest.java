package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #281 / D-09 — the arm the mocked {@code OrderSseGrantRecheckTest} structurally CANNOT
 * provide: the per-emit grant re-check must resolve a REAL grant, out of a REAL
 * {@code shop_staff} table under FORCE RLS, on a cache MISS, from a thread carrying no
 * {@code SecurityContext}, no {@code TenantContext} and no tenant GUC.
 *
 * <p>This is the liveness half of T-28-14, and it is the measurement that decided the
 * pin SHAPE. {@code broadcast()} runs on {@code OrderSseFanoutListener}'s
 * {@code @RabbitListener} thread. If the tenant pin there is insufficient, the
 * {@code shop_staff} read returns zero rows for every subscriber, every emit is skipped,
 * and the KDS is dead for everyone — while every security assertion in the change passes
 * perfectly. A mock cannot catch that, because a mock answers regardless of RLS.
 *
 * <p><strong>Two non-vacuity controls, both asserted rather than assumed:</strong>
 * <ol>
 *   <li><strong>The role is genuinely not a superuser.</strong> The Testcontainers
 *       bootstrap role IS a superuser, and a superuser bypasses even FORCE ROW LEVEL
 *       SECURITY — so without the downgrade this test would pass with the tenant pin
 *       removed entirely, proving nothing at all. {@link #seed()} runs
 *       {@code ALTER ROLE ... NOSUPERUSER} and {@link #theInstrumentIsValid()} reads
 *       {@code usesuper} back to confirm it took, per the
 *       {@code IntegrationTestSupport} RLS caveat.</li>
 *   <li><strong>The resolve is genuinely a cache MISS.</strong> {@code CacheConfig} is
 *       {@code @Profile("!test")}, so under this profile there is no {@code CacheManager}
 *       at all and {@code @Cacheable} is inert — every {@code resolveMembership} call
 *       executes its body against the database. That is a STRONGER guarantee than
 *       clearing a cache before the call (a clear can be undone by any intervening
 *       read), and {@link #theInstrumentIsValid()} asserts the absence rather than
 *       trusting the profile comment.</li>
 * </ol>
 *
 * <p>Break arm run and recorded: with {@code TenantContext.set(event.tenantId())}
 * neutralised in {@code broadcast()} — NOT the aspect's {@code set_config}, which is
 * defence in depth under a global aspect ({@code trap_tenant_pin_is_under_a_global_aspect})
 * — {@link #grantResolvesOffThreadOnACacheMiss()} goes RED.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderSseGrantRecheckIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private OrderSseService sseService;
    @Autowired private JdbcTemplate jdbc;
    /** Absent by construction under the {@code test} profile — see control 2. */
    @Autowired(required = false) private CacheManager cacheManager;

    /**
     * Fixture and downgrade are done ONCE for the class, and the fixture is therefore
     * static. The downgrade is deliberately NOT undone in an {@code @AfterEach}: once the
     * bootstrap role is NOSUPERUSER it can no longer grant itself SUPERUSER back, so a
     * restore attempt fails with a bad-grammar error that masks the real assertions (this
     * was measured, not guessed — the first version of this class did exactly that and
     * both tests reported a failure that had nothing to do with the behaviour under
     * test). Seeding therefore happens BEFORE the downgrade, once, and never again.
     */
    private static boolean prepared = false;
    private static UUID tenant;
    private static UUID shop;
    private static UUID userId;

    @BeforeEach
    void seed() {
        if (prepared) {
            return;
        }
        tenant = UUID.randomUUID();
        shop = UUID.randomUUID();
        userId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shop, tenant, "shop-" + shop, "slug-" + shop, "Test Address");
        // A SCOPED grant: an explicit per-shop STAFF row, not a tenant-wide GROUP_ADMIN.
        // This is the shape whose revocation #281 is about, and the shape whose re-check
        // reads shop_staff on every emit.
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role) "
                        + "VALUES (?, ?, ?, ?, 'STAFF')",
                UUID.randomUUID(), tenant, userId, shop);

        // Control 1: drop the superuser bit so FORCE RLS actually fires for every
        // connection taken from the pool after this point — including the broadcast
        // thread's. Without this the arm is vacuous.
        jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
        prepared = true;
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * The denominator half of this method needs an ACTIVE TRANSACTION, and that is not a
     * detail: {@code TenantSetLocalAspect} no-ops outside one, so an unpinned GUC would
     * make RLS answer the count with 0 on a correctly-seeded table — a zero that says
     * nothing about the data and everything about the instrument
     * ({@code trap_rls_blinds_the_verification_query}).
     */
    @Test
    @Transactional
    @DisplayName("instrument validity — the role is NOT a superuser and there is NO cache to hit")
    void theInstrumentIsValid() {
        Boolean isSuperuser = jdbc.queryForObject(
                "SELECT usesuper FROM pg_user WHERE usename = CURRENT_USER", Boolean.class);
        assertThat(isSuperuser)
                .as("the NOSUPERUSER downgrade must have taken, or FORCE RLS is bypassed and "
                        + "the off-thread arm would pass with no tenant pin at all")
                .isFalse();

        assertThat(cacheManager)
                .as("CacheConfig is @Profile(\"!test\"), so @Cacheable is inert here and every "
                        + "resolveMembership is structurally a MISS that must reach the database")
                .isNull();

        // Denominator: the instrument can SEE the seeded grant when the tenant IS pinned.
        // A zero here would make the off-thread arm's success unreadable.
        TenantContext.set(tenant);
        Integer visible = countGrantsPinned();
        assertThat(visible).as("the seeded shop_staff grant must be visible under its own tenant").isEqualTo(1);
    }

    @Test
    @DisplayName("off-thread cache MISS — the grant resolves with no SecurityContext, TenantContext or GUC")
    void grantResolvesOffThreadOnACacheMiss() throws Exception {
        // Subscribe on a REQUEST-shaped thread: a JWT principal and a pinned tenant, exactly
        // as OrderController's /orders/stream would have.
        authenticateAs(userId);
        TenantContext.set(tenant);
        SseEmitter spy = subscribeAndSpy();

        // Now become the fan-out thread: no principal, no tenant, no GUC.
        SecurityContextHolder.clearContext();
        TenantContext.clear();

        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), tenant, "ORD-OFFTHREAD-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now(), shop);

        // A genuinely different thread, so the ThreadLocals are not merely cleared but
        // never set — the real @RabbitListener condition.
        Thread fanout = new Thread(() -> sseService.broadcast(event), "sse-fanout-probe");
        fanout.start();
        fanout.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(fanout.isAlive()).as("the fan-out probe thread must have finished").isFalse();

        verify(spy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private Integer countGrantsPinned() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE user_id = ?", Integer.class, userId);
    }

    private void authenticateAs(UUID sub) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", "vendor-" + sub + "@example.com")
                .claim("name", "Vendor " + sub)
                .build();
        List<GrantedAuthority> authorities = List.of();   // NOT a realm admin — the scoped path
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SseEmitter subscribeAndSpy() throws Exception {
        SseEmitter real = sseService.subscribe();
        SseEmitter spy = Mockito.spy(real);

        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        Map<UUID, Map> map = (Map<UUID, Map>) f.get(sseService);
        Map bucket = map.get(tenant);
        assertThat(bucket).as("tenant bucket must exist after subscribe").isNotNull();
        Object scope = bucket.remove(real);
        bucket.put(spy, scope);
        return spy;
    }
}
