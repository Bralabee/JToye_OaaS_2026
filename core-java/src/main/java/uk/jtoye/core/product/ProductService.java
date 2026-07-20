package uk.jtoye.core.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.storage.StorageService.ImageType;
import uk.jtoye.core.storage.StorageService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for product management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 */
@Service
@Transactional
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final StorageService storageService;
    private final TenantCacheEvictor cacheEvictor;
    private final ShopAccessService shopAccessService;
    private final ProductCacheLoader productCacheLoader;

    public ProductService(ProductRepository productRepository,
                          ProductMapper productMapper,
                          StorageService storageService,
                          TenantCacheEvictor cacheEvictor,
                          ShopAccessService shopAccessService,
                          ProductCacheLoader productCacheLoader) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.storageService = storageService;
        this.cacheEvictor = cacheEvictor;
        this.shopAccessService = shopAccessService;
        this.productCacheLoader = productCacheLoader;
    }

    /**
     * Create a new product.
     * Automatically assigns tenant from context.
     * Validates required fields per Natasha's Law (ingredients_text, allergen_mask, price).
     *
     * <p>No cache eviction: a brand-new product cannot have an existing cached entry
     * under any previous id, so there is nothing to invalidate. (Previous
     * {@code @CacheEvict(allEntries=true)} nuked every tenant's cache on every create.)
     */
    public ProductDto createProduct(CreateProductRequest request) {
        // VSA-02 (D-02): catalogue create requires SHOP_MANAGER on the target shop
        // (body shopId). Additive to the controller's @PreAuthorize SCOPE_catalog:write.
        // STAFF is read + order-state only — a STAFF caller is denied here.
        shopAccessService.require(request.getShopId(), ShopRole.SHOP_MANAGER);

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating product for tenant {}: SKU={}, title={}",
                tenantId, request.getSku(), request.getTitle());

        // Create product entity via mapper (handles all fields including storefront fields)
        Product product = productMapper.toEntity(request);
        product.setTenantId(tenantId);

        // Apply defaults for storefront fields if not provided
        if (product.getAvailable() == null) product.setAvailable(true);
        if (product.getFeatured() == null) product.setFeatured(false);
        if (product.getDisplayOrder() == null) product.setDisplayOrder(0);

        // Cache the parsed allergen emphasis spans (PPDS, Issue #82). The label
        // renderer re-parses ingredients_text at render time (authoritative); this
        // persisted cache serves other consumers (e.g. a storefront allergen badge).
        product.setAllergenSpans(
                IngredientMarkupParser.parse(product.getIngredientsText()).spans());

        // Save product
        product = productRepository.save(product);

        log.info("Created product {} with SKU '{}', price: {} pennies",
                product.getId(), product.getSku(), product.getPricePennies());

        return productMapper.toDto(product);
    }

    /**
     * Get product by ID (tenant-scoped).
     *
     * <p>CR-01 (Phase 23-10): the cached data load ({@link ProductCacheLoader}) runs
     * FIRST, then the shop-access gate runs on the returned DTO's {@code shopId} — on
     * EVERY call, cache hit or miss. Because the {@code products} cache key is keyed by
     * tenant only (no user component), a product cached by one authorized user must NOT
     * be served to a different, out-of-grant user in the same tenant; running the gate
     * outside the {@code @Cacheable} boundary guarantees that. Reading a cached DTO and
     * THEN denying is correct — the authorization decision is what must run every time,
     * not the database round-trip.
     */
    @Transactional(readOnly = true)
    public Optional<ProductDto> getProductById(UUID productId) {
        // VSA-02 (D-02): a by-id product read requires at least STAFF on the owning
        // shop. Parent-lookup: the gate runs against the loaded product's shopId, so a
        // cross-shop direct hit yields the typed shop 403 (distinct from the RLS 404).
        Optional<ProductDto> dto = productCacheLoader.getProductById(productId);
        dto.ifPresent(product -> shopAccessService.require(product.getShopId(), ShopRole.STAFF));
        return dto;
    }

    /**
     * Get all products (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> getAllProducts(Pageable pageable) {
        log.debug("Fetching all products with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        // VSA-02 (D-01): read-scope to the caller's grant set at the QUERY. GROUP_ADMIN
        // sees the whole tenant; a scoped user sees only granted-shop products; a
        // fully-ungranted user sees nothing (deny-by-default).
        if (shopAccessService.isGroupAdmin()) {
            return productRepository.findAll(pageable)
                    .map(productMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return productRepository.findByShopIdIn(granted, pageable)
                .map(productMapper::toDto);
    }

    /**
     * Full-text product search (tenant-scoped via RLS), paginated.
     *
     * <p>Strategy (Issue #96): each whitespace-separated word is sanitised to
     * letters/digits and turned into a prefix lexeme ({@code chick:*}), joined
     * with {@code &} — multi-word queries must match every word, and partial
     * words still match ("chick" finds "Chicken"). SKU lookup is preserved via
     * an anchored, LIKE-escaped prefix on the raw query. Caller-provided sorts
     * are dropped: the native query owns ordering (ts_rank relevance), and
     * Spring Data would otherwise append the sort to the native SQL.
     *
     * <p>Queries that sanitise to nothing (blank, punctuation-only) return an
     * empty page rather than everything — the old LIKE path matched all rows
     * on '%', which no caller relied on.
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> search(String query, Pageable pageable) {
        log.debug("Searching products with query: {}", query);
        String tsQuery = toPrefixTsQuery(query);
        if (tsQuery.isEmpty()) {
            return Page.empty(pageable);
        }
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());
        String skuPrefix = escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        // VSA-02 (D-01): read-scope search to the caller's grant set at the QUERY,
        // mirroring getAllProducts. GROUP_ADMIN uses the tenant-wide FTS; a scoped
        // user uses the grant-set-narrowed FTS variant; ungranted → empty.
        if (shopAccessService.isGroupAdmin()) {
            return productRepository.searchFullText(tsQuery, skuPrefix, unsorted)
                    .map(productMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return productRepository.searchFullTextInShops(tsQuery, skuPrefix, granted, unsorted)
                .map(productMapper::toDto);
    }

    /**
     * Builds a prefix tsquery ("chicken curry" -> "chicken:* & curry:*").
     * Tokens are stripped to letters/digits so tsquery operators in user input
     * ({@code & | ! ( ) : *}) can never inject syntax or raise SQL errors.
     */
    private static String toPrefixTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return Arrays.stream(query.trim().split("\\s+"))
                .map(token -> token.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(token -> !token.isEmpty())
                .map(token -> token + ":*")
                .collect(Collectors.joining(" & "));
    }

    /**
     * Escapes LIKE wildcards for the SKU-prefix branch (ESCAPE '!' in the query),
     * so '%' and '_' in user input match literally instead of as wildcards.
     */
    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * Update an existing product (tenant-scoped).
     * RLS ensures we can only update products belonging to our tenant.
     * Evicts all product caches for the tenant to maintain consistency.
     */
    public ProductDto updateProduct(UUID productId, CreateProductRequest request) {
        log.debug("Updating product {}: SKU={}, title={}",
                productId, request.getSku(), request.getTitle());

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        // VSA-02 (D-02): parent-lookup — catalogue update requires SHOP_MANAGER on the
        // product's owning shop.
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);

        // Update all fields via mapper (handles both core and storefront fields)
        productMapper.updateEntity(request, product);

        // Re-parse the (possibly edited) ingredients_text and refresh the cached
        // allergen spans (PPDS, Issue #82) — keeps the persisted cache in step with
        // the text so stale offsets never point at the wrong characters.
        product.setAllergenSpans(
                IngredientMarkupParser.parse(product.getIngredientsText()).spans());

        // Save with flush to ensure immediate persistence
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Updated product {} with SKU '{}', price: {} pennies",
                product.getId(), product.getSku(), product.getPricePennies());

        return productMapper.toDto(product);
    }

    /**
     * Upload an image for a product. Replaces any existing image.
     */
    public ProductDto uploadImage(UUID productId, MultipartFile file) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): image write = SHOP_MANAGER

        // Delete old image if exists
        storageService.delete(product.getImageUrl());

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.upload(tenantId, "products", productId, file);
        product.setImageUrl(url);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Uploaded image for product {} (SKU: {})", productId, product.getSku());
        return productMapper.toDto(product);
    }

    /**
     * Remove the image from a product.
     */
    public ProductDto removeImage(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): image write = SHOP_MANAGER

        storageService.delete(product.getImageUrl());
        product.setImageUrl(null);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Removed image for product {} (SKU: {})", productId, product.getSku());
        return productMapper.toDto(product);
    }

    /**
     * Add an additional image to the product gallery (max 5).
     */
    public ProductDto addAdditionalImage(UUID productId, MultipartFile file) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): image write = SHOP_MANAGER

        if (product.getAdditionalImageUrls().size() >= 5) {
            throw new IllegalStateException("Maximum 5 additional images allowed per product");
        }

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.upload(tenantId, "products", productId, file, ImageType.PRODUCT);
        product.getAdditionalImageUrls().add(url);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Added additional image for product {} ({} total)", productId, product.getAdditionalImageUrls().size());
        return productMapper.toDto(product);
    }

    /**
     * Remove an additional image by index.
     */
    public ProductDto removeAdditionalImage(UUID productId, int index) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): image write = SHOP_MANAGER

        List<String> urls = product.getAdditionalImageUrls();
        if (index < 0 || index >= urls.size()) {
            throw new ResourceNotFoundException("Image index out of range: " + index);
        }

        String removedUrl = urls.remove(index);
        storageService.delete(removedUrl);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Removed additional image {} for product {}", index, productId);
        return productMapper.toDto(product);
    }

    /**
     * Delete product by ID (tenant-scoped).
     * RLS ensures we can only delete products belonging to our tenant.
     * Evicts ONLY this product's cache entry under the current tenant.
     */
    public void deleteProduct(UUID productId) {
        log.debug("Deleting product {}", productId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        // VSA-02 (D-02): parent-lookup — catalogue delete requires SHOP_MANAGER.
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);

        // Clean up all images from storage
        storageService.delete(product.getImageUrl());
        product.getAdditionalImageUrls().forEach(storageService::delete);

        productRepository.delete(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Deleted product {} with SKU '{}'", product.getId(), product.getSku());
    }

    /**
     * Cached by-id product loader (Phase 23-10, CR-01). Extracted onto its OWN Spring
     * bean so the {@code @Cacheable} boundary is separated from the authorization gate:
     * {@link ProductService#getProductById} delegates here for the (cached) load and
     * then runs {@code shopAccessService.require(...)} on the result, on every call.
     * Being a distinct bean, the call from {@code ProductService} crosses the Spring
     * proxy so the caching interceptor actually fires — deliberately NOT a
     * self-invocation (which would bypass the proxy and silently disable caching,
     * WR-01). The cached method keeps the name {@code getProductById} so the
     * tenant-aware cache key ({@code tenant:{tid}:getProductById:{productId}}) and every
     * existing {@code TenantCacheEvictor.evictEntity("products", "getProductById", id)}
     * eviction stay byte-for-byte unchanged (caching relocated, never deleted).
     *
     * <p>This loader holds NO authorization: callers MUST gate on the returned DTO's
     * {@code shopId} so a cache hit can never short-circuit the shop-access decision.
     */
    @Component
    public static class ProductCacheLoader {
        private static final Logger loaderLog = LoggerFactory.getLogger(ProductCacheLoader.class);

        private final ProductRepository productRepository;
        private final ProductMapper productMapper;

        public ProductCacheLoader(ProductRepository productRepository, ProductMapper productMapper) {
            this.productRepository = productRepository;
            this.productMapper = productMapper;
        }

        @Transactional(readOnly = true)
        @Cacheable(value = "products", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
        public Optional<ProductDto> getProductById(UUID productId) {
            loaderLog.debug("Fetching product by ID: {}", productId);
            return productRepository.findById(productId)
                    .map(productMapper::toDto);
        }
    }

}
