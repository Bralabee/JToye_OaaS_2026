package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductMapper;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopMapper;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.ShopDto;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #484 — a by-id lookup that finds NOTHING must not leave an entry behind in the
 * shared cache region.
 *
 * <p>The two by-id cache loaders ({@code ProductCacheLoader.getProductById},
 * {@code ShopCacheLoader.getShopById}) return {@code Optional<...>} and carry
 * {@code unless = "#result == null"}. The concern in #484 is that a miss returns
 * {@code Optional.empty()}, which is not {@code null}, so the guard never fires and a
 * 404 lookup populates the region for the full TTL — unbounded growth driven by
 * crawlers and stale links, with no eviction pressure other than expiry.
 *
 * <p><strong>Defeating the {@code @Profile("!test")} blindness.</strong> The production
 * {@link CacheConfig} is {@code @Profile("!test")}, so the normal suite has NO cache
 * manager and NO {@code tenantAwareCacheKeyGenerator} bean — caching, and therefore this
 * entire bug class, is invisible by construction. This class re-supplies both beans
 * locally (the same device as {@code BulkImportTenantCacheScopeIntegrationTest} and
 * {@code ShopAccessCacheBypassIntegrationTest}) so the caching interceptor genuinely
 * runs. {@link #cachingIsActuallyEnabledInThisContext()} and the POSITIVE control in
 * every negative case are what stop these assertions being vacuous: a tree with caching
 * switched off would satisfy "nothing was cached" trivially, so each negative case first
 * proves a FOUND lookup DOES populate the region through the very same interceptor.
 *
 * <p><strong>Why a mocked repository is the right instrument here.</strong> What is under
 * test is the Spring cache interceptor's evaluation of the {@code unless} SpEL expression
 * against the method's return value. That mechanism is entirely independent of the
 * database — no RLS, no tenancy decision, no SQL is involved in deciding whether a put
 * happens. Mocking the repository is what lets each case pin the loader's return to
 * exactly "found" or "not found"; a real Postgres would add a 47-minute Testcontainers
 * dependency and measure nothing extra. (The tenant-KEY dimension is separately proven
 * against real Postgres by {@code BulkImportTenantCacheScopeIntegrationTest}.)
 *
 * <p><strong>Sensitivity.</strong> The {@link ConcurrentMapCacheManager} here keeps its
 * default {@code allowNullValues = true} deliberately. That makes the instrument MORE
 * sensitive than production Redis (which is built with {@code disableCachingNullValues()}):
 * if the interceptor attempts to store a negative result at all, this cache accepts it and
 * {@code cache.get(key)} returns a non-null wrapper around {@code null}. So "no entry" here
 * means no put was even attempted — it cannot be an artifact of the cache refusing nulls.
 */
@SpringJUnitConfig(NegativeCachingOptionalEmptyTest.CacheProof.class)
class NegativeCachingOptionalEmptyTest {

    /**
     * Re-supplies the caching beans {@link CacheConfig} withholds under the {@code test}
     * profile: a real cache manager (no Redis needed) and a bean named EXACTLY
     * {@code tenantAwareCacheKeyGenerator}, without which
     * {@code @Cacheable(keyGenerator = "tenantAwareCacheKeyGenerator")} cannot resolve.
     * The two loaders are registered as real beans over mocked repositories/mappers so the
     * {@code @Cacheable} annotation on the production classes is the thing being exercised.
     */
    @Configuration
    @EnableCaching
    static class CacheProof {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("shops", "products");
        }

        @Bean
        KeyGenerator tenantAwareCacheKeyGenerator() {
            return new TenantAwareCacheKeyGenerator();
        }

        @Bean
        ProductRepository productRepository() {
            return Mockito.mock(ProductRepository.class);
        }

        @Bean
        ProductMapper productMapper() {
            return Mockito.mock(ProductMapper.class);
        }

        @Bean
        ShopRepository shopRepository() {
            return Mockito.mock(ShopRepository.class);
        }

        @Bean
        ShopMapper shopMapper() {
            return Mockito.mock(ShopMapper.class);
        }

        @Bean
        ProductService.ProductCacheLoader productCacheLoader(ProductRepository repo, ProductMapper mapper) {
            return new ProductService.ProductCacheLoader(repo, mapper);
        }

        @Bean
        ShopService.ShopCacheLoader shopCacheLoader(ShopRepository repo, ShopMapper mapper) {
            return new ShopService.ShopCacheLoader(repo, mapper);
        }
    }

    @Autowired private CacheManager cacheManager;
    @Autowired private ProductService.ProductCacheLoader productCacheLoader;
    @Autowired private ShopService.ShopCacheLoader shopCacheLoader;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductMapper productMapper;
    @Autowired private ShopRepository shopRepository;
    @Autowired private ShopMapper shopMapper;

    private UUID tenant;

    @BeforeEach
    void setUp() {
        Mockito.reset(productRepository, productMapper, shopRepository, shopMapper);
        cacheManager.getCache("products").clear();
        cacheManager.getCache("shops").clear();
        tenant = UUID.randomUUID();
        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        cacheManager.getCache("products").clear();
        cacheManager.getCache("shops").clear();
        TenantContext.clear();
    }

    // --- instrument check -----------------------------------------------------

    /** A no-op cache manager would make every assertion below vacuous. */
    @Test
    @DisplayName("instrument - the cache in this context is real, not a no-op")
    void cachingIsActuallyEnabledInThisContext() {
        assertThat(cacheManager)
                .as("the injected CacheManager must be a real cache, not a no-op")
                .isNotInstanceOf(NoOpCacheManager.class);
        assertThat(cacheManager.getCache("products")).as("'products' region must resolve").isNotNull();
        assertThat(cacheManager.getCache("shops")).as("'shops' region must resolve").isNotNull();
    }

    // --- products -------------------------------------------------------------

    /**
     * THE #484 case for products. {@code ProductService.getProductById} delegates to this
     * loader with NO gate in front of it, so a lookup of an id that does not exist really
     * does reach the loader on every request — this is the reachable half of the issue.
     */
    @Test
    @DisplayName("products - a lookup that finds nothing must leave NO entry behind")
    void missingProductMustNotPopulateTheProductsRegion() {
        // CONTROL: prove a FOUND lookup populates the region through this very interceptor.
        UUID foundId = UUID.randomUUID();
        Product entity = new Product();
        ProductDto dto = new ProductDto();
        dto.setId(foundId);
        Mockito.when(productRepository.findById(foundId)).thenReturn(Optional.of(entity));
        Mockito.when(productMapper.toDto(entity)).thenReturn(dto);

        assertThat(productCacheLoader.getProductById(foundId)).isPresent();
        assertThat(products().get(key("getProductById", foundId)))
                .as("CONTROL: a found product MUST be cached. If this is null the caching "
                        + "interceptor is not running and the negative assertion below would "
                        + "pass vacuously on a tree with caching entirely disabled")
                .isNotNull();

        // THE ASSERTION: a miss must leave nothing behind.
        UUID missingId = UUID.randomUUID();
        Mockito.when(productRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThat(productCacheLoader.getProductById(missingId)).isEmpty();
        assertThat(products().get(key("getProductById", missingId)))
                .as("issue #484: a lookup of a nonexistent product id must NOT populate the "
                        + "shared 'products' region. Present state was: %s",
                        describe(products(), key("getProductById", missingId)))
                .isNull();
    }

    /**
     * The growth claim in #484 stated directly: N distinct 404 lookups must add N times
     * nothing. A single-lookup assertion could in principle be satisfied by an
     * off-by-one; this measures the region's actual occupancy.
     */
    @Test
    @DisplayName("products - N lookups of nonexistent ids leave the region holding zero of them")
    void repeatedMissingProductLookupsMustNotGrowTheRegion() {
        // CONTROL first: the region CAN hold an entry, so "zero" below is a real result.
        UUID foundId = UUID.randomUUID();
        Product entity = new Product();
        ProductDto dto = new ProductDto();
        dto.setId(foundId);
        Mockito.when(productRepository.findById(foundId)).thenReturn(Optional.of(entity));
        Mockito.when(productMapper.toDto(entity)).thenReturn(dto);
        productCacheLoader.getProductById(foundId);
        assertThat(products().get(key("getProductById", foundId)))
                .as("CONTROL: the region must be able to hold an entry at all")
                .isNotNull();

        int cached = 0;
        for (int i = 0; i < 25; i++) {
            UUID missingId = UUID.randomUUID();
            Mockito.when(productRepository.findById(missingId)).thenReturn(Optional.empty());
            productCacheLoader.getProductById(missingId);
            if (products().get(key("getProductById", missingId)) != null) {
                cached++;
            }
        }

        assertThat(cached)
                .as("issue #484: 25 lookups of nonexistent product ids must leave 25 x nothing "
                        + "in the shared region — this is the unbounded-growth claim measured directly")
                .isZero();
    }

    // --- shops ----------------------------------------------------------------

    /**
     * The same defect class on the shops loader — the site #484 does NOT name, found by
     * sweeping {@code unless=} across every {@code @Cacheable} returning {@code Optional}.
     */
    @Test
    @DisplayName("shops - a lookup that finds nothing must leave NO entry behind")
    void missingShopMustNotPopulateTheShopsRegion() {
        // CONTROL: prove a FOUND lookup populates the region through this very interceptor.
        UUID foundId = UUID.randomUUID();
        Shop entity = new Shop();
        ShopDto dto = new ShopDto();
        dto.setId(foundId);
        Mockito.when(shopRepository.findByIdAndTenantId(foundId, tenant)).thenReturn(Optional.of(entity));
        Mockito.when(shopMapper.toDto(entity)).thenReturn(dto);

        assertThat(shopCacheLoader.getShopById(foundId)).isPresent();
        assertThat(shops().get(key("getShopById", foundId)))
                .as("CONTROL: a found shop MUST be cached, otherwise the negative assertion "
                        + "below is vacuous")
                .isNotNull();

        // THE ASSERTION: a miss must leave nothing behind.
        UUID missingId = UUID.randomUUID();
        Mockito.when(shopRepository.findByIdAndTenantId(missingId, tenant)).thenReturn(Optional.empty());

        assertThat(shopCacheLoader.getShopById(missingId)).isEmpty();
        assertThat(shops().get(key("getShopById", missingId)))
                .as("issue #484 (swept site): a lookup of a nonexistent shop id must NOT "
                        + "populate the shared 'shops' region. Present state was: %s",
                        describe(shops(), key("getShopById", missingId)))
                .isNull();
    }

    // --- helpers --------------------------------------------------------------

    private Cache products() {
        return cacheManager.getCache("products");
    }

    private Cache shops() {
        return cacheManager.getCache("shops");
    }

    private String key(String method, UUID id) {
        return "tenant:" + tenant + ":" + method + ":" + id;
    }

    /**
     * Distinguishes "no entry at all" from "an entry holding null". Both read as a cache
     * MISS to a caller, but only the latter means the interceptor really did write a
     * negative entry — which is the difference between #484 being real and being a
     * misreading. Without this the failure message cannot tell the two apart.
     */
    private static String describe(Cache cache, String key) {
        Cache.ValueWrapper wrapper = cache.get(key);
        if (wrapper == null) {
            return "absent (no put was attempted)";
        }
        Object value = wrapper.get();
        return value == null
                ? "PRESENT holding null (a negative entry WAS written)"
                : "PRESENT holding " + value;
    }
}
