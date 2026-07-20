package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
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
import uk.jtoye.core.config.TenantAwareCacheKeyGenerator;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-01 regression proof — a warm read cache must NOT short-circuit the shop-access
 * gate. The defect: {@code @Cacheable} on {@code getShopById}/{@code getProductById}
 * wrapped a method body that contained the {@code require()} call, so once one
 * authorized user populated the (per-tenant, user-agnostic) cache entry, every OTHER
 * user in the tenant was served that entry WITHOUT the gate ever running.
 *
 * <p><strong>Defeating the {@code @Profile("!test")} blindness.</strong> The production
 * {@link uk.jtoye.core.config.CacheConfig} is {@code @Profile("!test")}, so under
 * {@code @ActiveProfiles("test")} there is NO cache manager and NO
 * {@code tenantAwareCacheKeyGenerator} bean — the entire bug class is invisible to the
 * normal test suite by construction, and a fix "verified" under the test profile would
 * prove nothing. This suite supplies BOTH beans locally via the nested
 * {@link CacheProof @TestConfiguration} ({@code @EnableCaching}) so caching is genuinely
 * ON, and asserts the cache is actually populated before asserting denial.
 *
 * <p>Two GENUINELY DIFFERENT scoped principals are used (userX on shop A, userY on shop
 * B) — never one user with a strict-scoping toggle flipped between reads — so the second
 * caller's denial cannot be explained away as a configuration change.
 *
 * <p>The cache key is {@code tenant:{tid}:getShopById:{shopId}} /
 * {@code tenant:{tid}:getProductById:{productId}} — the fix keeps those cached method
 * names on the extracted loader beans, so this key is identical before and after the
 * fix (this is why the same assertions demonstrate RED pre-fix and GREEN post-fix).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopAccessCacheBypassIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /**
     * Locally re-supplies the caching beans that {@code CacheConfig} withholds under the
     * {@code test} profile: a real {@link ConcurrentMapCacheManager} (no Redis container
     * needed) and a bean named EXACTLY {@code tenantAwareCacheKeyGenerator} — both are
     * required, or {@code @Cacheable(keyGenerator = "tenantAwareCacheKeyGenerator")}
     * fails to resolve. There is no bean conflict precisely because {@code CacheConfig}
     * is profile-excluded.
     */
    @TestConfiguration
    @EnableCaching
    static class CacheProof {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("shops", "products", "shopMembership");
        }

        @Bean
        KeyGenerator tenantAwareCacheKeyGenerator() {
            return new TenantAwareCacheKeyGenerator();
        }
    }

    @Autowired private ShopService shopService;
    @Autowired private ProductService productService;
    @Autowired private ShopAccessService shopAccessService;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        cacheManager.getCache("shops").clear();
        cacheManager.getCache("products").clear();
        cacheManager.getCache("shopMembership").clear();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- cases ----------------------------------------------------------------

    @Test
    void warmCacheDoesNotBypassShopGate() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        UUID userX = UUID.randomUUID();
        UUID userY = UUID.randomUUID();
        grantShopStaff(tenant, userX, shopA, "SHOP_MANAGER");
        grantShopStaff(tenant, userY, shopB, "SHOP_MANAGER");

        setStrictScoping(true);

        // userX reads shop A -> authorized, and POPULATES the read cache.
        authenticateAs(userX, tenant);
        assertThat(shopService.getShopById(shopA))
                .as("userX (SHOP_MANAGER on shop A) can read shop A")
                .isPresent();

        // The cache entry MUST now exist — if it does not, caching is not really on and
        // every later assertion is meaningless.
        String shopKey = "tenant:" + tenant + ":getShopById:" + shopA;
        assertThat(cacheManager.getCache("shops").get(shopKey))
                .as("shop A must be cached after userX's read (caching genuinely enabled)")
                .isNotNull();

        // userY (scoped to shop B, NOT A) reads the SAME, already-cached shop A. The
        // cache hit must NOT short-circuit the gate: userY is denied.
        authenticateAs(userY, tenant);
        assertThatThrownBy(() -> shopService.getShopById(shopA))
                .as("a warm cache entry for shop A must NOT be served to out-of-grant userY")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    @Test
    void warmCacheDoesNotBypassProductGate() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        UUID productA = seedProduct(tenant, shopA, "A-SKU-1");
        UUID userX = UUID.randomUUID();
        UUID userY = UUID.randomUUID();
        grantShopStaff(tenant, userX, shopA, "SHOP_MANAGER");
        grantShopStaff(tenant, userY, shopB, "SHOP_MANAGER");

        setStrictScoping(true);

        // userX reads product A -> authorized, populates the products cache.
        authenticateAs(userX, tenant);
        assertThat(productService.getProductById(productA))
                .as("userX (SHOP_MANAGER on shop A) can read a shop-A product")
                .isPresent();

        String productKey = "tenant:" + tenant + ":getProductById:" + productA;
        assertThat(cacheManager.getCache("products").get(productKey))
                .as("product A must be cached after userX's read (caching genuinely enabled)")
                .isNotNull();

        // userY reads the SAME, already-cached product A -> denied despite the cache hit.
        authenticateAs(userY, tenant);
        assertThatThrownBy(() -> productService.getProductById(productA))
                .as("a warm cache entry for product A must NOT be served to out-of-grant userY")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    @Test
    void authorizedCallerStillServedFromCache() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID userX = UUID.randomUUID();
        grantShopStaff(tenant, userX, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticateAs(userX, tenant);

        // First read: miss -> loads -> populates the cache.
        assertThat(shopService.getShopById(shopA)).isPresent();
        String shopKey = "tenant:" + tenant + ":getShopById:" + shopA;
        Object cachedAfterFirst = cacheManager.getCache("shops").get(shopKey);
        assertThat(cachedAfterFirst)
                .as("cache populated on the first authorized read")
                .isNotNull();

        // Second read by the SAME authorized user must still succeed (the performance
        // good is preserved — this fails if Task 1 fixed the bypass by breaking caching
        // via the self-invocation trap instead of restructuring it), and the cache entry
        // must be unchanged.
        assertThatCode(() -> shopService.getShopById(shopA))
                .as("a second authorized read is still served (caching not broken)")
                .doesNotThrowAnyException();
        assertThat(cacheManager.getCache("shops").get(shopKey))
                .as("the cache entry is unchanged after the second authorized read")
                .isNotNull();
    }

    @Test
    void cachingIsActuallyEnabledInThisContext() {
        assertThat(cacheManager)
                .as("the injected CacheManager must be a real cache, not a no-op")
                .isNotInstanceOf(NoOpCacheManager.class);
        assertThat(cacheManager.getCache("shops"))
                .as("the 'shops' cache must resolve")
                .isNotNull();
        assertThat(cacheManager.getCache("products"))
                .as("the 'products' cache must resolve")
                .isNotNull();
    }

    // --- seeding helpers (run as a realm-admin: implicit GROUP_ADMIN, bypasses the gate) ---

    private UUID seedShop(UUID tenant, String name) {
        return asRealmAdmin(tenant, () -> shopService.createShop(shopRequest(name)).getId());
    }

    private UUID seedProduct(UUID tenant, UUID shopId, String sku) {
        return asRealmAdmin(tenant, () -> productService.createProduct(productRequest(shopId, sku)).getId());
    }

    private <T> T asRealmAdmin(UUID tenant, Supplier<T> action) {
        boolean prevStrict = currentStrictScoping();
        authenticate(UUID.randomUUID(), true);
        TenantContext.set(tenant);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            setStrictScoping(prevStrict);
        }
    }

    /** shops/products carry an FK to {@code tenants}; seed the (RLS-free) tenant row first. */
    private void ensureTenant(UUID tenant) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Cache Bypass Test Tenant " + tenant);
    }

    private void grantShopStaff(UUID tenant, UUID userId, UUID shopId, String role) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenant, userId, shopId, role);
    }

    // --- request builders -----------------------------------------------------

    private CreateShopRequest shopRequest(String name) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName(name);
        req.setAddress("1 Test Street, London");
        return req;
    }

    private CreateProductRequest productRequest(UUID shopId, String sku) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku(sku);
        req.setTitle("Product " + sku);
        req.setIngredientsText("Test ingredients");
        req.setAllergenMask(0);
        req.setPricePennies(500L);
        req.setShopId(shopId);
        return req;
    }

    // --- auth + strict-scoping plumbing (mirrors ShopAccessEnforcementIntegrationTest) ---

    /** Authenticate as a scoped (non-realm-admin) vendor user and pin the tenant. */
    private void authenticateAs(UUID sub, UUID tenant) {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        authenticate(sub, false);
        TenantContext.set(tenant);
    }

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

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }

    private boolean currentStrictScoping() {
        return Boolean.TRUE.equals(ReflectionTestUtils.getField(target(), "strictScoping"));
    }
}
