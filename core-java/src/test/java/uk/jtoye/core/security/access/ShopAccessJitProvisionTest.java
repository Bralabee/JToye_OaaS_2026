package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VSA-01 close-out — the JIT lazy-provision (D-04) idempotency + strict-scoping
 * (D-12) proof against real Postgres 15 via Testcontainers.
 *
 * <p>Per RESEARCH §1-FLAG, the VSA-01 "backfill idempotency test" is really a
 * JIT-provision RACE test: two concurrent first-requests from the same
 * {@code (tenant, sub)} must produce EXACTLY ONE tenant-wide GROUP_ADMIN
 * {@code shop_staff} row (the {@code ON CONFLICT DO NOTHING} house reserve idiom
 * on {@code uq_shop_staff_tenant_user_shop}). It also proves the two directions
 * of the strict-scoping switch and the realm-admin implicit-GROUP_ADMIN bridge.
 *
 * <p>{@code shop_staff.tenant_id}/{@code user_id} carry no FK, and JIT rows use a
 * NULL {@code shop_id}, so tests drive raw random tenant/sub UUIDs without
 * seeding {@code tenants}/{@code shops}. Each test uses a fresh
 * {@code (tenant, sub)} so no {@code @Transactional} rollback is needed — the JIT
 * insert must genuinely COMMIT for the concurrency count to be observable.
 * strict-scoping is toggled per case via {@link ReflectionTestUtils} on the
 * unwrapped target bean (the {@code @Transactional} proxy is unwrapped with
 * {@link AopTestUtils}).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopAccessJitProvisionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private ShopAccessService shopAccessService;

    @Autowired
    private JdbcTemplate jdbc;

    /** The real (proxy-unwrapped) bean, so we can flip the strict-scoping field per case. */
    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }

    /** Install a JWT principal (sub + optional realm-admin authority) on the current thread. */
    private void authenticate(UUID sub, boolean realmAdmin) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", "user-" + sub + "@example.com")
                .claim("name", "Test User " + sub)
                .build();
        List<GrantedAuthority> authorities = realmAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_admin"))
                : List.of();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private long groupAdminRowCount(UUID tenant, UUID sub) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff "
                        + "WHERE tenant_id = ? AND user_id = ? AND role = 'GROUP_ADMIN' AND shop_id IS NULL",
                Long.class, tenant, sub);
        return n == null ? 0 : n;
    }

    /**
     * Two concurrent first-requests from the same ungranted {@code (tenant, sub)}
     * must JIT-provision EXACTLY ONE GROUP_ADMIN row (ON CONFLICT DO NOTHING).
     */
    @Test
    void jitProvisionIsIdempotentUnderConcurrentFirstRequests() throws Exception {
        setStrictScoping(false);
        UUID tenant = UUID.randomUUID();
        UUID sub = UUID.randomUUID();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    authenticate(sub, false);
                    TenantContext.set(tenant);
                    startGate.await();
                    // grantedShopIds() enters onRequest() → JIT provision. It never
                    // throws (unlike require()), so a read-side race can't mask the
                    // row-count assertion.
                    shopAccessService.grantedShopIds();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("both concurrent first-requests completed").isTrue();
        assertThat(errors).as("no worker threw").isEmpty();
        assertThat(groupAdminRowCount(tenant, sub))
                .as("exactly one GROUP_ADMIN row after two concurrent first-requests (ON CONFLICT DO NOTHING)")
                .isEqualTo(1);
    }

    /**
     * strict-scoping OFF (day-one): an ungranted non-admin's first request
     * auto-provisions GROUP_ADMIN and {@code require()} passes — zero regression.
     */
    @Test
    void strictScopingOffPreservesDayOne() {
        setStrictScoping(false);
        UUID tenant = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        authenticate(sub, false);
        TenantContext.set(tenant);

        // Does NOT throw: JIT provisions GROUP_ADMIN, which satisfies any floor.
        shopAccessService.require(UUID.randomUUID(), ShopRole.STAFF);

        assertThat(groupAdminRowCount(tenant, sub))
                .as("first request auto-provisioned exactly one GROUP_ADMIN row")
                .isEqualTo(1);
    }

    /**
     * strict-scoping ON: an ungranted non-admin gets NO auto-provision and
     * {@code require()} throws the typed shop-access 403.
     */
    @Test
    void strictScopingOnDeniesUngranted() {
        setStrictScoping(true);
        UUID tenant = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        authenticate(sub, false);
        TenantContext.set(tenant);

        assertThatThrownBy(() -> shopAccessService.require(shopId, ShopRole.STAFF))
                .isInstanceOf(ShopAccessDeniedException.class);

        // Deny-by-default: no shop_staff row was minted. (The require() tx rolled
        // back on the RuntimeException; assert against a fresh read either way.)
        assertThat(groupAdminRowCount(tenant, sub))
                .as("strict-scoping ON auto-provisions nothing")
                .isZero();
    }

    /**
     * A realm-admin is an implicit GROUP_ADMIN (D-03): {@code require()} passes
     * with NO {@code shop_staff} row, even under strict-scoping ON.
     */
    @Test
    void realmAdminIsImplicitGroupAdminWithoutAnyRow() {
        setStrictScoping(true);
        UUID tenant = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        authenticate(sub, true);
        TenantContext.set(tenant);

        // Passes purely on the ROLE_admin bridge.
        shopAccessService.require(UUID.randomUUID(), ShopRole.GROUP_ADMIN);
        assertThat(shopAccessService.isGroupAdmin()).isTrue();

        assertThat(groupAdminRowCount(tenant, sub))
                .as("a realm-admin needs no provisioned shop_staff row")
                .isZero();
    }
}
