package uk.jtoye.core.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.config.TenantAwareCacheKeyGenerator;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #483 part 1 — an Edge sync batch run by ONE tenant must not cold-start every OTHER
 * tenant's catalogue and shop reads.
 *
 * <p>The defect: {@code SyncService.processBatch} carried
 * {@code @Caching(evict = {@CacheEvict(value = "shops", allEntries = true),
 * @CacheEvict(value = "products", allEntries = true)})}. {@code allEntries} clears the WHOLE
 * cache region, and a region is shared by every tenant — isolation lives in the KEY
 * ({@code tenant:{tid}:getProductById:{pid}}), not in the region. This is the identical blast
 * #287 fixed in {@code BulkImportService} and Phase 23 fixed in
 * {@code ProductService.createProduct} / {@code ShopService.createShop}.
 *
 * <p><b>Why the #287 fix is the WRONG fix here, and this class proves it.</b> #287 could simply
 * DELETE the eviction, because bulk import is provably create-only — a row that never existed
 * has no cache key to stale. {@code SyncService} genuinely upserts
 * ({@code shopRepository.findByName(...)} / {@code productRepository.findBySku(...)} then save),
 * so existing rows really are mutated and an eviction is NECESSARY. Only its RADIUS was wrong.
 * {@link #anUpdatedProductsOwnEntryIsStillInvalidated()} and
 * {@link #anUpdatedShopsOwnEntryIsStillInvalidated()} are the arms that go red if a future
 * change reaches for the #287 shape and deletes the eviction outright: they would then ship a
 * stale read, which is a worse defect than the one being fixed.
 *
 * <p><b>Defeating the {@code @Profile("!test")} blindness (the #484 lesson).</b> The production
 * {@link uk.jtoye.core.config.CacheConfig} is {@code @Profile("!test")}, so a normal test
 * context has NO cache manager and NO {@code tenantAwareCacheKeyGenerator} — under which a
 * DISABLED cache satisfies "the entry survived" exactly as well as a correct one, and the whole
 * class would be green over a broken tree. Two devices prevent that here:
 * <ol>
 *   <li>{@link CacheProof} re-supplies real caching beans and imports {@code SyncService} as a
 *       Spring bean, so the caching interceptor genuinely proxies it (this is what makes the
 *       unfixed tree fail these assertions);</li>
 *   <li>{@link #theCachingInterceptorIsLiveInThisContext()} independently proves the
 *       interceptor is running, via a {@code @Cacheable} probe that counts its own underlying
 *       invocations. If caching were off, that arm goes red first.</li>
 * </ol>
 *
 * <p><b>Why no Testcontainers.</b> Nothing here is about tenancy at the DATABASE layer — the
 * cache key is built from {@link TenantContext} at the request boundary, exactly where
 * {@code JwtTenantFilter} sets it, and never from the Postgres tenant GUC (which
 * {@code TenantSetLocalAspect} re-pins before every repository call, so a GUC-level fixture
 * would measure the aspect rather than the cache). The repositories are mocked because their
 * only role is to say whether a row already existed. RLS behaviour is unchanged by this fix.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SyncServiceTenantCacheScopeTest.CacheProof.class)
class SyncServiceTenantCacheScopeTest {

    /**
     * Re-supplies the caching beans {@code CacheConfig} withholds under the {@code test}
     * profile, and registers {@code SyncService} through Spring (never {@code new}) so the
     * caching interceptor actually wraps it. {@code @Import} rather than an explicit
     * {@code @Bean} factory method deliberately: Spring resolves the constructor, so this
     * source compiles and runs unchanged against BOTH the unfixed two-argument
     * {@code SyncService} and the fixed three-argument one — which is what let the fail
     * direction of every assertion below be observed on the real unfixed tree.
     */
    @Configuration
    @EnableCaching
    @Import(SyncService.class)
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
        TenantCacheEvictor tenantCacheEvictor(ObjectProvider<CacheManager> cacheManagerProvider) {
            return new TenantCacheEvictor(cacheManagerProvider);
        }

        @Bean
        ShopRepository shopRepository() {
            return Mockito.mock(ShopRepository.class);
        }

        @Bean
        ProductRepository productRepository() {
            return Mockito.mock(ProductRepository.class);
        }

        @Bean
        CacheProbe cacheProbe() {
            return new CacheProbe();
        }
    }

    /**
     * Instrument for {@link #theCachingInterceptorIsLiveInThisContext()}: a genuinely
     * {@code @Cacheable} method that counts how often its BODY runs. Two calls with the same
     * argument must produce one body execution — if they produce two, caching is off and every
     * "survived" assertion in this class would be vacuous.
     */
    static class CacheProbe {
        /**
         * Static on purpose. This bean is CGLIB-proxied (it carries {@code @Cacheable}), and a
         * Spring CGLIB proxy is instantiated WITHOUT running the constructor, so reading an
         * INSTANCE field off the injected reference yields {@code null} — only method calls are
         * delegated to the target. A counter reached through a field would NPE; reached through
         * a static it is correct either way.
         */
        private static final AtomicInteger BODY_INVOCATIONS = new AtomicInteger();

        @Cacheable(value = "products", keyGenerator = "tenantAwareCacheKeyGenerator")
        public String load(UUID id) {
            BODY_INVOCATIONS.incrementAndGet();
            return "loaded:" + id;
        }
    }

    @Autowired private SyncService syncService;
    @Autowired private CacheManager cacheManager;
    @Autowired private ShopRepository shopRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CacheProbe cacheProbe;

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        Mockito.reset(shopRepository, productRepository);
        products().clear();
        shops().clear();
        CacheProbe.BODY_INVOCATIONS.set(0);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 1. The cross-tenant blast (the headline criterion)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A sync batch by tenant B must NOT evict tenant A's cached product")
    void crossTenantProductEntrySurvivesASyncBatch() {
        String keyA = productKey(TENANT_A, UUID.randomUUID());
        products().put(keyA, "tenant-A-product-dto");

        UUID productB = UUID.randomUUID();
        stubExistingProduct("B-SKU-1", productB);

        TenantContext.set(TENANT_B);
        BatchSyncResponse response = syncService.processBatch(batchOf(productItem("B-SKU-1")));

        assertThat(response.getProcessedCount())
                .as("the batch must really have upserted a row — a batch that did nothing would "
                        + "evict nothing and prove nothing")
                .isEqualTo(1);
        assertThat(products().get(keyA))
                .as("issue #483: allEntries=true cleared the whole shared 'products' region, so "
                        + "one vendor's Edge sync cold-started every other vendor's catalogue")
                .isNotNull();
    }

    @Test
    @DisplayName("A sync batch by tenant B must NOT evict tenant A's cached shop")
    void crossTenantShopEntrySurvivesASyncBatch() {
        String keyA = shopKey(TENANT_A, UUID.randomUUID());
        shops().put(keyA, "tenant-A-shop-dto");

        stubExistingShop("B Shop", UUID.randomUUID());

        TenantContext.set(TENANT_B);
        BatchSyncResponse response = syncService.processBatch(batchOf(shopItem("B Shop")));

        assertThat(response.getProcessedCount()).isEqualTo(1);
        assertThat(shops().get(keyA))
                .as("the 'shops' region is shared across tenants for the same reason")
                .isNotNull();
    }

    @Test
    @DisplayName("An EMPTY sync batch must not evict anything at all")
    void anEmptyBatchEvictsNothing() {
        String keyA = productKey(TENANT_A, UUID.randomUUID());
        String keyB = productKey(TENANT_B, UUID.randomUUID());
        products().put(keyA, "tenant-A-product-dto");
        products().put(keyB, "tenant-B-product-dto");

        TenantContext.set(TENANT_B);
        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder().items(List.of()).build());

        assertThat(response.getProcessedCount()).isZero();
        assertThat(products().get(keyA))
                .as("a no-op batch cannot have staled anything — the old annotation fired on the "
                        + "normal return regardless of whether a single row was touched")
                .isNotNull();
        assertThat(products().get(keyB)).isNotNull();
    }

    @Test
    @DisplayName("A sync batch must not evict the syncing tenant's OWN untouched entries")
    void untouchedEntriesOfTheSyncingTenantSurvive() {
        UUID untouched = UUID.randomUUID();
        String untouchedKey = productKey(TENANT_B, untouched);
        products().put(untouchedKey, "tenant-B-unrelated-product-dto");

        stubExistingProduct("B-SKU-2", UUID.randomUUID());

        TenantContext.set(TENANT_B);
        syncService.processBatch(batchOf(productItem("B-SKU-2")));

        assertThat(products().get(untouchedKey))
                .as("this is what distinguishes 'narrowed to the touched ids' from merely "
                        + "'narrowed to one tenant's whole region' — the latter still throws away "
                        + "cache the batch never invalidated")
                .isNotNull();
    }

    @Test
    @DisplayName("A create-only sync batch evicts nothing (there is no prior key to stale)")
    void createOnlyBatchEvictsNothing() {
        String existingKey = productKey(TENANT_B, UUID.randomUUID());
        products().put(existingKey, "tenant-B-product-dto");

        Mockito.when(productRepository.findBySku("NEW-SKU")).thenReturn(Optional.empty());
        Mockito.when(productRepository.save(Mockito.any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TenantContext.set(TENANT_B);
        BatchSyncResponse response = syncService.processBatch(batchOf(productItem("NEW-SKU")));

        assertThat(response.getProcessedCount()).isEqualTo(1);
        assertThat(products().get(existingKey))
                .as("a row that did not previously exist has no cache key, so creating it cannot "
                        + "make anything stale (the same reasoning #287 recorded on BulkImportService)")
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // 2. The eviction that must SURVIVE the narrowing — this is why #483 is
    //    NOT #287. Deleting the eviction would ship a stale read.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PASSES BOTH TREES BY DESIGN: an UPDATED product's own cached entry is still invalidated")
    void anUpdatedProductsOwnEntryIsStillInvalidated() {
        UUID productB = UUID.randomUUID();
        String keyB = productKey(TENANT_B, productB);
        products().put(keyB, "stale-tenant-B-product-dto");

        stubExistingProduct("B-SKU-3", productB);

        TenantContext.set(TENANT_B);
        syncService.processBatch(batchOf(productItem("B-SKU-3")));

        assertThat(products().get(keyB))
                .as("SyncService UPSERTS (findBySku + save), so an existing row really is mutated "
                        + "and its cached DTO really is stale. This arm passes on the unfixed tree "
                        + "too (allEntries also removes it) — it is not evidence for the fix, it is "
                        + "the guard that stops the fix being 'delete the eviction' as in #287")
                .isNull();
    }

    @Test
    @DisplayName("PASSES BOTH TREES BY DESIGN: an UPDATED shop's own cached entry is still invalidated")
    void anUpdatedShopsOwnEntryIsStillInvalidated() {
        UUID shopB = UUID.randomUUID();
        String keyB = shopKey(TENANT_B, shopB);
        shops().put(keyB, "stale-tenant-B-shop-dto");

        stubExistingShop("B Shop 2", shopB);

        TenantContext.set(TENANT_B);
        syncService.processBatch(batchOf(shopItem("B Shop 2")));

        assertThat(shops().get(keyB))
                .as("same argument as the product arm: findByName + save genuinely mutates an "
                        + "existing shop")
                .isNull();
    }

    @Test
    @DisplayName("A mixed batch invalidates every touched id, and ONLY those")
    void aMixedBatchInvalidatesEveryTouchedIdAndOnlyThose() {
        UUID shopB = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID untouched = UUID.randomUUID();
        shops().put(shopKey(TENANT_B, shopB), "stale-shop");
        products().put(productKey(TENANT_B, productB), "stale-product");
        products().put(productKey(TENANT_B, untouched), "keep-me");
        products().put(productKey(TENANT_A, untouched), "keep-me-too");

        stubExistingShop("B Shop 3", shopB);
        stubExistingProduct("B-SKU-4", productB);

        TenantContext.set(TENANT_B);
        BatchSyncResponse response = syncService.processBatch(
                batchOf(shopItem("B Shop 3"), productItem("B-SKU-4")));

        assertThat(response.getProcessedCount()).isEqualTo(2);
        // The two "invalidated" halves pass on the unfixed tree too (allEntries removes
        // everything); the two "survives" halves are what make this arm fail there.
        assertThat(shops().get(shopKey(TENANT_B, shopB))).isNull();
        assertThat(products().get(productKey(TENANT_B, productB))).isNull();
        assertThat(products().get(productKey(TENANT_B, untouched))).isNotNull();
        assertThat(products().get(productKey(TENANT_A, untouched))).isNotNull();
    }

    // ------------------------------------------------------------------
    // 3. Instrument check — without this every "survived" above could be
    //    explained by caching simply being off (the #484 failure mode).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("INSTRUMENT: the caching interceptor is genuinely live in this context")
    void theCachingInterceptorIsLiveInThisContext() {
        TenantContext.set(TENANT_A);
        UUID id = UUID.randomUUID();

        assertThat(cacheProbe.load(id)).isEqualTo("loaded:" + id);
        assertThat(cacheProbe.load(id)).isEqualTo("loaded:" + id);

        assertThat(CacheProbe.BODY_INVOCATIONS.get())
                .as("two calls, one body execution — proves @EnableCaching, the CacheManager and "
                        + "the tenantAwareCacheKeyGenerator are all wired. A disabled cache would "
                        + "make every 'entry survived' assertion in this class vacuously true")
                .isEqualTo(1);
        assertThat(products().get("tenant:" + TENANT_A + ":load:" + id))
                .as("and the entry lands under the tenant-scoped key format the evictions target")
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Cache products() {
        return cacheManager.getCache("products");
    }

    private Cache shops() {
        return cacheManager.getCache("shops");
    }

    private static String productKey(UUID tenantId, UUID productId) {
        return "tenant:" + tenantId + ":getProductById:" + productId;
    }

    private static String shopKey(UUID tenantId, UUID shopId) {
        return "tenant:" + tenantId + ":getShopById:" + shopId;
    }

    /** The upsert's UPDATE branch: the row already exists under {@code id}. */
    private void stubExistingProduct(String sku, UUID id) {
        Product existing = new Product();
        ReflectionTestUtils.setField(existing, "id", id);
        existing.setSku(sku);
        Mockito.when(productRepository.findBySku(sku)).thenReturn(Optional.of(existing));
        Mockito.when(productRepository.save(Mockito.any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** The upsert's UPDATE branch for shops. */
    private void stubExistingShop(String name, UUID id) {
        Shop existing = new Shop();
        existing.setId(id);
        existing.setName(name);
        Mockito.when(shopRepository.findByName(name)).thenReturn(Optional.of(existing));
        Mockito.when(shopRepository.save(Mockito.any(Shop.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @SafeVarargs
    private static BatchSyncRequest batchOf(Map<String, Object>... items) {
        return BatchSyncRequest.builder().items(List.of(items)).build();
    }

    private static Map<String, Object> productItem(String sku) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "product");
        item.put("sku", sku);
        item.put("title", "Synced " + sku);
        item.put("ingredientsText", "Rice, tomatoes");
        item.put("allergenMask", 0);
        item.put("pricePennies", 899);
        return item;
    }

    private static Map<String, Object> shopItem(String name) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "shop");
        item.put("name", name);
        item.put("address", "1 Test Street, London");
        return item;
    }
}
