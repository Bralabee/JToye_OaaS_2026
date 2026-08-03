package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.config.TenantAwareCacheKeyGenerator;
import uk.jtoye.core.product.dto.BulkImportResult;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #287 regression proof — a bulk import run by ONE tenant must not invalidate
 * ANOTHER tenant's cached products.
 *
 * <p>The defect: {@code BulkImportService.importFromCsv} / {@code importFromImages}
 * carried {@code @CacheEvict(value = "products", allEntries = true)}. {@code allEntries}
 * clears the ENTIRE {@code products} cache region, and that region is shared by every
 * tenant (isolation lives in the KEY — {@code tenant:{tid}:getProductById:{pid}} —
 * not in the region). So one vendor importing a CSV cold-started every other vendor's
 * catalogue reads. Phase 23 removed exactly this blast from
 * {@code ProductService.createProduct} / {@code ShopService.createShop}; the two bulk
 * paths were missed.
 *
 * <p><strong>Defeating the {@code @Profile("!test")} blindness.</strong> The production
 * {@link uk.jtoye.core.config.CacheConfig} is {@code @Profile("!test")}, so under
 * {@code @ActiveProfiles("test")} there is NO cache manager and NO
 * {@code tenantAwareCacheKeyGenerator} bean — caching, and therefore this whole bug
 * class, is invisible to the normal suite by construction. This class re-supplies both
 * beans locally (same device as {@code ShopAccessCacheBypassIntegrationTest}) so the
 * caching interceptor genuinely runs.
 *
 * <p><strong>Why these assertions can fail.</strong> Every case first asserts the cache
 * entry was POPULATED by the real {@code @Cacheable} read path before asserting it
 * survives. That is the instrument check: {@code @Cacheable} and {@code @CacheEvict}
 * are served by the SAME {@code CacheInterceptor}, so a populated entry proves the
 * interceptor is live and a "survived" result cannot be explained by caching silently
 * being off. Against the unfixed tree these cases fail on the survival assertion
 * (entry is {@code null}), which is what makes them evidence rather than decoration.
 *
 * <p>The tenant is driven through {@link TenantContext#set} at the request boundary —
 * the way {@code JwtTenantFilter} does it — deliberately NOT by poking the Postgres
 * tenant GUC, which {@code TenantSetLocalAspect} re-pins before every repository call
 * (a lower-level pin would measure the aspect, not the cache key).
 */
@SpringBootTest(properties = "ai.enabled=false")
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class BulkImportTenantCacheScopeIntegrationTest {

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
     * Locally re-supplies the caching beans {@code CacheConfig} withholds under the
     * {@code test} profile: a real {@link ConcurrentMapCacheManager} (no Redis needed)
     * and a bean named EXACTLY {@code tenantAwareCacheKeyGenerator}, without which
     * {@code @Cacheable(keyGenerator = "tenantAwareCacheKeyGenerator")} fails to
     * resolve. No bean conflict arises precisely because CacheConfig is
     * profile-excluded here.
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

    private static final String CSV = """
            title,sku,price_pounds,ingredients,category
            Jollof Rice,IMPORT-JOLLOF-1,8.99,"Rice, tomatoes, peppers",Mains
            Puff Puff,IMPORT-PUFF-1,2.50,"Flour, sugar, yeast",Snacks
            """;

    @Autowired private BulkImportService bulkImportService;
    @Autowired private ProductService productService;
    @Autowired private ShopService shopService;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        cacheManager.getCache("products").clear();
        cacheManager.getCache("shops").clear();
        cacheManager.getCache("shopMembership").clear();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- cases ----------------------------------------------------------------

    /**
     * THE cross-tenant case. Tenant A warms its own product into the shared
     * {@code products} region; tenant B then bulk-imports a CSV. A's entry must
     * still be there.
     */
    @Test
    void csvImportByOneTenantMustNotEvictAnotherTenantsCachedProduct() {
        UUID tenantA = newTenant();
        UUID productA = seedProductFor(tenantA, "A-SKU-1");
        String keyA = productKey(tenantA, productA);

        warmProductCache(tenantA, productA);
        assertThat(products().get(keyA))
                .as("tenant A's product must be cached after its read — proves the caching "
                        + "interceptor is genuinely live, so a later 'survived' is not vacuous")
                .isNotNull();

        UUID tenantB = newTenant();
        BulkImportResult result = asRealmAdmin(tenantB, () -> bulkImportService.importFromCsv(csvFile()));
        assertThat(result.getSuccessCount())
                .as("tenant B's import must really have created rows — an import that did "
                        + "nothing would evict nothing and prove nothing")
                .isEqualTo(2);

        assertThat(products().get(keyA))
                .as("tenant A's cached product must SURVIVE tenant B's bulk import "
                        + "(issue #287: allEntries=true cleared the whole shared region)")
                .isNotNull();
    }

    /**
     * The same blast radius on the AI-image path — and here the import does NO work at
     * all ({@code ai.enabled=false} short-circuits before a single product is created),
     * yet {@code @CacheEvict} still fires on the normal return. A zero-effect import
     * must not cost another tenant its cache.
     */
    @Test
    void imageImportByOneTenantMustNotEvictAnotherTenantsCachedProduct() {
        UUID tenantA = newTenant();
        UUID productA = seedProductFor(tenantA, "A-SKU-2");
        String keyA = productKey(tenantA, productA);

        warmProductCache(tenantA, productA);
        assertThat(products().get(keyA))
                .as("tenant A's product must be cached before tenant B acts")
                .isNotNull();

        UUID tenantB = newTenant();
        MultipartFile[] images = { new MockMultipartFile("files", "dish.jpg", "image/jpeg", new byte[] { 1, 2, 3 }) };
        BulkImportResult result = asRealmAdmin(tenantB, () -> bulkImportService.importFromImages(images));
        assertThat(result.getCreated())
                .as("AI is disabled in this context, so the import creates nothing — "
                        + "it is the ANNOTATION, not the work, that is under test")
                .isEmpty();

        assertThat(products().get(keyA))
                .as("tenant A's cached product must SURVIVE tenant B's (no-op) image import")
                .isNotNull();
    }

    /**
     * The same-tenant half of #287. Creating brand-new rows cannot stale an existing
     * by-id entry (the {@code products} region holds only
     * {@code getProductById} keyed by product id, and every imported row gets a fresh
     * generated id), so the importing tenant's OWN unrelated entries must survive too.
     * This is the assertion that distinguishes "eviction removed" from "eviction merely
     * narrowed to the tenant" — a tenant-wide region clear would still fail here.
     */
    @Test
    void csvImportMustNotEvictTheImportingTenantsOwnUnrelatedProduct() {
        UUID tenant = newTenant();
        UUID existing = seedProductFor(tenant, "OWN-SKU-1");
        String key = productKey(tenant, existing);

        warmProductCache(tenant, existing);
        assertThat(products().get(key))
                .as("the importing tenant's own product must be cached first")
                .isNotNull();

        BulkImportResult result = asRealmAdmin(tenant, () -> bulkImportService.importFromCsv(csvFile()));
        assertThat(result.getSuccessCount()).isEqualTo(2);

        assertThat(products().get(key))
                .as("a create-only import cannot make an existing by-id entry stale, so the "
                        + "importing tenant's own cached product must survive as well")
                .isNotNull();
    }

    /**
     * Incremental-betterment guard: dropping the eviction must not cost the good it was
     * (over-)protecting. Every freshly imported product must be readable by id straight
     * after the import — i.e. no stale/negative cache entry shadows a new row.
     */
    @Test
    void freshlyImportedProductsAreReadableByIdImmediately() {
        UUID tenant = newTenant();
        BulkImportResult result = asRealmAdmin(tenant, () -> bulkImportService.importFromCsv(csvFile()));
        assertThat(result.getSuccessCount()).isEqualTo(2);

        for (ProductDto created : result.getCreated()) {
            Optional<ProductDto> readBack = asRealmAdmin(tenant, () -> productService.getProductById(created.getId()));
            assertThat(readBack)
                    .as("imported product %s must be readable immediately after the import", created.getSku())
                    .isPresent();
            assertThat(products().get(productKey(tenant, created.getId())))
                    .as("and that read must populate its own cache entry as usual")
                    .isNotNull();
        }
    }

    /** Instrument check — a no-op cache manager would make every case above vacuous. */
    @Test
    void cachingIsActuallyEnabledInThisContext() {
        assertThat(cacheManager)
                .as("the injected CacheManager must be a real cache, not a no-op")
                .isNotInstanceOf(NoOpCacheManager.class);
        assertThat(cacheManager.getCache("products"))
                .as("the 'products' cache must resolve")
                .isNotNull();
    }

    // --- helpers --------------------------------------------------------------

    private Cache products() {
        return cacheManager.getCache("products");
    }

    private static String productKey(UUID tenantId, UUID productId) {
        return "tenant:" + tenantId + ":getProductById:" + productId;
    }

    private static MultipartFile csvFile() {
        return new MockMultipartFile("file", "import.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
    }

    /** Read through the real (cached) service path so the entry is written by {@code @Cacheable}. */
    private void warmProductCache(UUID tenant, UUID productId) {
        asRealmAdmin(tenant, () -> productService.getProductById(productId));
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "BulkImport Cache Scope Tenant " + tenant);
        return tenant;
    }

    private UUID seedProductFor(UUID tenant, String sku) {
        return asRealmAdmin(tenant, () -> {
            UUID shopId = shopService.createShop(shopRequest("Shop for " + sku)).getId();
            return productService.createProduct(productRequest(shopId, sku)).getId();
        });
    }

    /**
     * Run {@code action} as a realm admin (which bridges to GROUP_ADMIN, so the bulk
     * paths' {@code requireGroupAdmin} / no-shop_id rows are permitted) with the tenant
     * pinned at the boundary via {@link TenantContext#set} — exactly what
     * {@code JwtTenantFilter} does per request.
     */
    private <T> T asRealmAdmin(UUID tenant, Supplier<T> action) {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", "admin-" + tenant + "@example.com")
                .claim("name", "Realm Admin")
                .build();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_admin"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        TenantContext.set(tenant);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

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
}
