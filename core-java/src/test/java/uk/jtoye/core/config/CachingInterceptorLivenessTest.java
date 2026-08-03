package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
import uk.jtoye.testsupport.cache.LiveCacheTestSlice;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #498 — <b>the caching interceptor, asserted behaviourally.</b>
 *
 * <h2>What is different here, and why it is the point</h2>
 *
 * Every pre-existing cache assertion in this codebase is <i>structural</i>: it reads
 * {@code cacheManager.getCache(region).get(key)} and checks whether an entry is present.
 * ({@code NegativeCachingOptionalEmptyTest}, {@code ShopAccessCacheBypassIntegrationTest},
 * {@code BulkImportTenantCacheScopeIntegrationTest} — all three.) That is a real assertion and it
 * catches real bugs, but it does not answer the question the cache exists to answer: <b>does a
 * second identical call skip the database?</b>
 *
 * <p>The two come apart, and not hypothetically. Change {@code @Cacheable} to {@code @CachePut} on
 * either by-id loader and:
 * <ul>
 *   <li>the region is still populated, at exactly the expected tenant-scoped key, so every
 *       "CONTROL: a found product MUST be cached" assertion stays GREEN; and</li>
 *   <li>{@code unless = "#result == null"} still suppresses the negative entry, so every #484
 *       "a miss must leave nothing behind" assertion stays GREEN; while</li>
 *   <li>every single read goes to the database, because {@code @CachePut} never serves.</li>
 * </ul>
 * The cache would be entirely dead and the whole existing suite would be green over it. The
 * assertions here fail on that tree, because they count repository invocations rather than
 * inspecting region contents.
 *
 * <h2>How each assertion is falsifiable, stated up front</h2>
 *
 * <ul>
 *   <li>A <b>disabled or no-op</b> cache makes {@link #aSecondIdenticalProductLookupMustNotReachTheRepository()}
 *       and its shops twin fail: with nothing serving hits, the loader body runs twice and the
 *       repository is invoked twice. This is the specific direction #484's "zero entries"
 *       assertions could not distinguish — a dead cache and a working-but-empty cache are
 *       identical to them, and opposite to these.</li>
 *   <li>Deleting {@code unless = "#result == null"} makes
 *       {@link #aRepeatedMissingProductLookupMustReachTheRepositoryEveryTime()} fail: the negative
 *       result gets cached and the second miss is served from the region, so the repository is
 *       invoked once instead of twice.</li>
 *   <li>Breaking the tenant dimension of the key generator makes
 *       {@link #theSameIdUnderADifferentTenantMustReachTheRepositoryAgain()} fail — and that test
 *       is a cross-tenant data-leak assertion, not a performance one.</li>
 * </ul>
 *
 * <h2>Why a mocked repository is the right instrument</h2>
 *
 * What is under test is the caching interceptor's decision to invoke or skip the method body. That
 * decision involves no SQL, no RLS and no tenancy check — the tenant appears only inside the cache
 * KEY, which this slice generates with the real production {@code TenantAwareCacheKeyGenerator}.
 * Mocking the repository is what makes "the repository was not reached" observable at all; against
 * a real Postgres the same property could only be inferred. The tenant dimension is separately
 * proven against real Postgres by {@code BulkImportTenantCacheScopeIntegrationTest}.
 */
@SpringJUnitConfig(CachingInterceptorLivenessTest.Beans.class)
class CachingInterceptorLivenessTest {

    /**
     * The two by-id cache loaders over mocked repositories, on top of the shared
     * {@link LiveCacheTestSlice}. The loaders are the real production classes, so the
     * {@code @Cacheable} annotations being exercised are the ones that ship.
     */
    @Configuration
    @Import(LiveCacheTestSlice.class)
    static class Beans {

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
        for (String region : LiveCacheTestSlice.REGIONS) {
            cacheManager.getCache(region).clear();
        }
        tenant = UUID.randomUUID();
        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        for (String region : LiveCacheTestSlice.REGIONS) {
            cacheManager.getCache(region).clear();
        }
        TenantContext.clear();
    }

    // --- instrument -----------------------------------------------------------

    @Test
    @DisplayName("instrument - every region this slice declares resolves to a real cache")
    void theSliceSuppliesARealCache() {
        assertThat(cacheManager)
                .as("a no-op cache manager would make the hit assertions below fail rather than "
                        + "pass vacuously, but it would still mean the slice is misconfigured")
                .isNotInstanceOf(NoOpCacheManager.class);
        for (String region : LiveCacheTestSlice.REGIONS) {
            assertThat(cacheManager.getCache(region))
                    .as("region '%s' must resolve", region)
                    .isNotNull();
        }
    }

    // --- THE assertion: a hit does not reach the repository --------------------

    @Test
    @DisplayName("products - a second identical lookup must NOT reach the repository")
    void aSecondIdenticalProductLookupMustNotReachTheRepository() {
        UUID productId = UUID.randomUUID();
        Product entity = new Product();
        ProductDto dto = new ProductDto();
        dto.setId(productId);
        Mockito.when(productRepository.findById(productId)).thenReturn(Optional.of(entity));
        Mockito.when(productMapper.toDto(entity)).thenReturn(dto);

        Optional<ProductDto> first = productCacheLoader.getProductById(productId);
        Optional<ProductDto> second = productCacheLoader.getProductById(productId);

        assertThat(first).contains(dto);
        assertThat(second)
                .as("the cached call must return the SAME value, not merely avoid the repository — "
                        + "a cache that skips the database and answers wrongly is worse than none")
                .contains(dto);
        verify(productRepository, times(1).description(
                "The second identical lookup must be served from the cache. Two invocations here "
                        + "means the caching interceptor is not serving hits at all: caching is "
                        + "disabled, the region is misnamed, @Cacheable became @CachePut, or the "
                        + "call no longer crosses the Spring proxy. Under any of those, every 'the "
                        + "region holds no entry' assertion elsewhere in this codebase is vacuous — "
                        + "which is issue #498."))
                .findById(productId);
    }

    @Test
    @DisplayName("shops - a second identical lookup must NOT reach the repository")
    void aSecondIdenticalShopLookupMustNotReachTheRepository() {
        UUID shopId = UUID.randomUUID();
        Shop entity = new Shop();
        ShopDto dto = new ShopDto();
        dto.setId(shopId);
        Mockito.when(shopRepository.findByIdAndTenantId(shopId, tenant)).thenReturn(Optional.of(entity));
        Mockito.when(shopMapper.toDto(entity)).thenReturn(dto);

        assertThat(shopCacheLoader.getShopById(shopId)).contains(dto);
        assertThat(shopCacheLoader.getShopById(shopId)).contains(dto);

        verify(shopRepository, times(1)).findByIdAndTenantId(shopId, tenant);
    }

    // --- the tenant dimension of the key (a leak assertion, not a perf one) ----

    @Test
    @DisplayName("products - the same id under a DIFFERENT tenant must reach the repository again")
    void theSameIdUnderADifferentTenantMustReachTheRepositoryAgain() {
        UUID productId = UUID.randomUUID();
        Product entity = new Product();
        ProductDto dto = new ProductDto();
        dto.setId(productId);
        Mockito.when(productRepository.findById(productId)).thenReturn(Optional.of(entity));
        Mockito.when(productMapper.toDto(entity)).thenReturn(dto);

        // Tenant A warms the region, and is proven warm by its own hit.
        productCacheLoader.getProductById(productId);
        productCacheLoader.getProductById(productId);
        verify(productRepository, times(1).description(
                "control: tenant A's second lookup must be a cache HIT, otherwise the cross-tenant "
                        + "assertion below is satisfied trivially by a dead cache"))
                .findById(productId);

        // Tenant B asks for the SAME id. A tenant-blind key would serve A's entry.
        TenantContext.set(UUID.randomUUID());
        productCacheLoader.getProductById(productId);

        verify(productRepository, times(2).description(
                "tenant B must NOT be served tenant A's cached entry. One invocation here means the "
                        + "cache key has lost its tenant dimension and one tenant is reading "
                        + "another tenant's row out of a shared region — a data leak that RLS "
                        + "cannot catch, because no query is issued at all"))
                .findById(productId);
    }

    // --- the #484 property, stated behaviourally -------------------------------

    @Test
    @DisplayName("products - a repeated MISSING lookup must reach the repository every time")
    void aRepeatedMissingProductLookupMustReachTheRepositoryEveryTime() {
        // POSITIVE CONTROL FIRST. Without it, "the repository was reached twice" is equally true of
        // a completely dead cache, and this test would be the very shape #498 objects to.
        UUID foundId = UUID.randomUUID();
        Product entity = new Product();
        ProductDto dto = new ProductDto();
        dto.setId(foundId);
        Mockito.when(productRepository.findById(foundId)).thenReturn(Optional.of(entity));
        Mockito.when(productMapper.toDto(entity)).thenReturn(dto);
        productCacheLoader.getProductById(foundId);
        productCacheLoader.getProductById(foundId);
        verify(productRepository, times(1).description(
                "CONTROL: a FOUND product must be served from cache on the second call. If this is "
                        + "2, the cache is dead and the 'must reach the repository twice' assertion "
                        + "below would pass for the wrong reason"))
                .findById(foundId);

        // THE ASSERTION: a miss is not cached, so it is re-attempted rather than served.
        UUID missingId = UUID.randomUUID();
        Mockito.when(productRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThat(productCacheLoader.getProductById(missingId)).isEmpty();
        assertThat(productCacheLoader.getProductById(missingId)).isEmpty();

        verify(productRepository, times(2).description(
                "issue #484: a lookup that finds nothing must NOT be cached, so the second identical "
                        + "miss must reach the repository again. One invocation here means "
                        + "unless=\"#result == null\" has stopped suppressing the negative entry and "
                        + "404 lookups are populating the shared region for the full TTL"))
                .findById(missingId);
    }

    @Test
    @DisplayName("shops - a repeated MISSING lookup must reach the repository every time")
    void aRepeatedMissingShopLookupMustReachTheRepositoryEveryTime() {
        // POSITIVE CONTROL FIRST, same reasoning as the products case.
        UUID foundId = UUID.randomUUID();
        Shop entity = new Shop();
        ShopDto dto = new ShopDto();
        dto.setId(foundId);
        Mockito.when(shopRepository.findByIdAndTenantId(foundId, tenant)).thenReturn(Optional.of(entity));
        Mockito.when(shopMapper.toDto(entity)).thenReturn(dto);
        shopCacheLoader.getShopById(foundId);
        shopCacheLoader.getShopById(foundId);
        verify(shopRepository, times(1)).findByIdAndTenantId(foundId, tenant);

        UUID missingId = UUID.randomUUID();
        Mockito.when(shopRepository.findByIdAndTenantId(missingId, tenant)).thenReturn(Optional.empty());

        assertThat(shopCacheLoader.getShopById(missingId)).isEmpty();
        assertThat(shopCacheLoader.getShopById(missingId)).isEmpty();

        verify(shopRepository, times(2)).findByIdAndTenantId(missingId, tenant);
    }

    // --- region isolation ------------------------------------------------------

    @Test
    @DisplayName("regions - a product lookup must not be served from, or write into, the shops region")
    void theTwoLoadersMustNotShareARegion() {
        UUID id = UUID.randomUUID();
        Product entity = new Product();
        ProductDto dto = new ProductDto();
        dto.setId(id);
        Mockito.when(productRepository.findById(id)).thenReturn(Optional.of(entity));
        Mockito.when(productMapper.toDto(entity)).thenReturn(dto);

        productCacheLoader.getProductById(id);

        verify(shopRepository, never()).findByIdAndTenantId(any(), any());
        assertThat(cacheManager.getCache("shops").get("tenant:" + tenant + ":getShopById:" + id))
                .as("a product load must leave the shops region untouched — the two loaders share a "
                        + "key SHAPE, so a shared region would cross-serve a ShopDto for a ProductDto")
                .isNull();
    }
}
