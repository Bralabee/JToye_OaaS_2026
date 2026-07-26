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
import uk.jtoye.core.media.MediaAssetService;
import uk.jtoye.core.media.ProductMedia;
import uk.jtoye.core.media.ProductMediaRepository;
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
    private final ProductMediaRepository productMediaRepository;
    private final MediaAssetService mediaAssetService;

    public ProductService(ProductRepository productRepository,
                          ProductMapper productMapper,
                          StorageService storageService,
                          TenantCacheEvictor cacheEvictor,
                          ShopAccessService shopAccessService,
                          ProductCacheLoader productCacheLoader,
                          ProductMediaRepository productMediaRepository,
                          MediaAssetService mediaAssetService) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.storageService = storageService;
        this.cacheEvictor = cacheEvictor;
        this.shopAccessService = shopAccessService;
        this.productCacheLoader = productCacheLoader;
        this.productMediaRepository = productMediaRepository;
        this.mediaAssetService = mediaAssetService;
    }

    /**
     * Asset-first dual-read resolver (D-03a): during the migration window a product's
     * image is served from its primary ACTIVE {@code media_asset} derivative if one
     * exists, otherwise from the flat {@code products.image_url} column (kept this
     * phase, dropped later). Mutates the DTO's {@code imageUrl} in place and returns
     * it so it composes cleanly at every {@code toDto} site (list, search, by-id).
     * A missing/PENDING/FAILED primary leaves the flat URL untouched.
     */
    private ProductDto resolveAssetFirst(ProductDto dto) {
        if (dto != null && dto.getId() != null) {
            productMediaRepository.findPrimaryActiveObjectKey(dto.getId())
                    .map(storageService::urlForKey)
                    .ifPresent(dto::setImageUrl);
        }
        return dto;
    }

    /**
     * Single-DTO detail enrichment (IMG-04, 24-05): the asset-first primary URL
     * ({@link #resolveAssetFirst}, D-03a) PLUS the per-entry {@code media} list carrying
     * {@code status}/{@code flagged}/{@code failureReason} the 24-06 UI renders. Applied
     * ONLY at the single-product read/write sites (by-id, create, update, the image
     * mutations) — NOT on the list/search paths, where a per-row media query would be an
     * N+1 the web-perf contract forbids (grid cards need only the primary {@code imageUrl},
     * already resolved by {@code resolveAssetFirst}). Resolved OUTSIDE the {@code @Cacheable}
     * loader so an async {@code PENDING->ACTIVE} flip is never served stale.
     */
    private ProductDto resolveDetail(ProductDto dto) {
        resolveAssetFirst(dto);
        if (dto != null && dto.getId() != null) {
            dto.setMedia(mediaAssetService.mediaForProduct(dto.getId()));
        }
        return dto;
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

        return resolveDetail(productMapper.toDto(product));
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
        dto.ifPresent(product -> {
            // WR-08 null-shop READ half (plan 23-10, pairs with 23-08's GROUP_ADMIN-only
            // WRITE half): a shop_id IS NULL product is a tenant-wide / legacy resource,
            // readable by ANY granted scoped user — skip the gate (RLS still confines it to
            // the tenant). Previously require(null, STAFF) 403'd (or 500'd) a scoped caller,
            // making legacy catalogue rows simultaneously invisible in lists (WR-08) and
            // crash-inducing to open (CR-04). A non-null shopId is gated exactly as before.
            if (product.getShopId() != null) {
                shopAccessService.require(product.getShopId(), ShopRole.STAFF);
            }
        });
        // Asset-first dual-read (D-03a) + the per-entry media list (IMG-04) applied
        // OUTSIDE the @Cacheable loader so a resolved derivative URL / status is never
        // cached: an asset flips PENDING->ACTIVE asynchronously, so this must be resolved
        // fresh on every read.
        return dto.map(this::resolveDetail);
    }

    /**
     * Get all products (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> getAllProducts(Pageable pageable) {
        log.debug("Fetching all products with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        // VSA-02 (D-01): read-scope to the caller's grant set at the QUERY. GROUP_ADMIN
        // sees the whole tenant; a scoped user sees granted-shop products PLUS legacy
        // tenant-wide (shop_id IS NULL) products (WR-08 read half, plan 23-10); a
        // fully-ungranted user sees nothing (deny-by-default — the short-circuit below
        // is deliberately kept so the null-shop policy did NOT widen to "everyone sees
        // tenant-wide products").
        if (shopAccessService.isGroupAdmin()) {
            return productRepository.findAll(pageable)
                    .map(productMapper::toDto)
                    .map(this::resolveAssetFirst);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return productRepository.findTenantScopedInGrantSetOrTenantWide(tenantId, granted, pageable)
                .map(productMapper::toDto)
                .map(this::resolveAssetFirst);
    }

    /**
     * Get products for ONE shop of the tenant (WR-04, issue #280, plan 23-18).
     *
     * <p>Backs the explicit {@code GET /products?shopId=} narrow, replacing a client-side
     * {@code .filter(p => p.shopId === contextShopId)} applied over a single already-paginated
     * page — which produced wrong counts, a false empty state when a shop's rows began on page 2,
     * and rows past page 1 that could not be reached.
     *
     * <p>VSA-02 (D-02): an explicit shop-scoped read requires at least STAFF on that shop, exactly
     * as {@link uk.jtoye.core.order.OrderService#getOrdersByShop}. This is the authorization seam —
     * a caller without a grant on {@code shopId} gets a typed 403, NOT an empty page, so an
     * unauthorized narrow is never mistaken for "this shop has nothing".
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> getProductsByShop(UUID shopId, Pageable pageable) {
        log.debug("Fetching products for shop {} with pagination: page={}, size={}",
                shopId, pageable.getPageNumber(), pageable.getPageSize());
        shopAccessService.require(shopId, ShopRole.STAFF);
        return productRepository.findByShopId(shopId, pageable)
                .map(productMapper::toDto)
                .map(this::resolveAssetFirst);
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
        return search(query, null, pageable);
    }

    /**
     * Full-text product search, optionally narrowed to ONE shop (WR-04, issue #280, plan 23-18).
     *
     * <p>A null {@code shopId} preserves the previous behaviour exactly (grant-set + tenant-wide).
     * A non-null {@code shopId} is gated by {@code require(shopId, STAFF)} and excludes legacy
     * tenant-wide rows, mirroring {@link #getProductsByShop} so the list and search views agree
     * about what "this shop" means.
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> search(String query, UUID shopId, Pageable pageable) {
        log.debug("Searching products with query: {} (shopId={})", query, shopId);
        String tsQuery = toPrefixTsQuery(query);
        if (tsQuery.isEmpty()) {
            return Page.empty(pageable);
        }
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());
        String skuPrefix = escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        if (shopId != null) {
            // Gate FIRST, before any query runs, so an unauthorized shop can never be probed
            // for row existence via timing or result shape.
            shopAccessService.require(shopId, ShopRole.STAFF);
            return productRepository.searchFullTextByShop(tsQuery, skuPrefix, shopId, unsorted)
                    .map(productMapper::toDto)
                    .map(this::resolveAssetFirst);
        }
        // VSA-02 (D-01): read-scope search to the caller's grant set at the QUERY,
        // mirroring getAllProducts. GROUP_ADMIN uses the tenant-wide FTS; a scoped user
        // uses the grant-set + tenant-wide (shop_id IS NULL) FTS variant (WR-08 read half,
        // plan 23-10); a zero-grant user → empty (deny-by-default preserved).
        if (shopAccessService.isGroupAdmin()) {
            return productRepository.searchFullText(tsQuery, skuPrefix, unsorted)
                    .map(productMapper::toDto)
                    .map(this::resolveAssetFirst);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return productRepository.searchFullTextInGrantSetOrTenantWide(tenantId, tsQuery, skuPrefix, granted, unsorted)
                .map(productMapper::toDto)
                .map(this::resolveAssetFirst);
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

        return resolveDetail(productMapper.toDto(product));
    }

    // NOTE (Phase 24 / 24-03): `uploadImage(...)` (the synchronous store-image + return-DTO
    // path behind the retired ProductController.uploadImage handler) was removed. The single
    // safe upload path is now MediaUploadController.accept -> MediaAssetService
    // .acceptQuarantineAndQueue (reject-early + quarantine + PENDING media_asset + outbox 202).
    // The SHOP_MANAGER shop-scoped write gate (VSA-02) is preserved there.

    /**
     * Remove the image from a product.
     *
     * <p>IMG-01 delete surface (24-05): the one place a human triggers primary-image
     * deletion. It now drops the {@code is_primary} {@code product_media} row and
     * ref-count-releases its asset (a physical MinIO delete happens ONLY at ref-count 0
     * — a still-referenced shared asset is preserved) BEFORE the legacy flat cleanup, so a
     * vendor deletion never orphans the join row + {@code media_asset}. The flat
     * {@code image_url} cleanup + {@code setImageUrl(null)} are retained for the dual-read
     * window (an un-migrated / backfilled product still cleans up its flat column).
     */
    public ProductDto removeImage(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): image write = SHOP_MANAGER

        // Asset-model delete (IMG-01): drop the primary join row + ref-count-release the asset.
        releasePrimaryAsset(productId);

        // Dual-read flat cleanup (D-03a): still delete the flat object + null the column.
        storageService.delete(product.getImageUrl());
        product.setImageUrl(null);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Removed image for product {} (SKU: {})", productId, product.getSku());
        return resolveDetail(productMapper.toDto(product));
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
        return resolveDetail(productMapper.toDto(product));
    }

    /**
     * Remove an additional image by index.
     *
     * <p>IMG-01 delete surface (24-05): the gallery-image counterpart of
     * {@link #removeImage}. The {@code index} is the 0-based position in the flat
     * {@code additional_image_urls[]} list, which aligns positionally with the product's
     * non-primary {@code product_media} rows in {@code sort_order} (the V53 backfill mapped
     * the array to gallery rows preserving order). So the row for the removed gallery entry
     * is dropped and its asset ref-count-released (physical MinIO delete only at ref-count 0;
     * remaining gallery rows untouched), alongside the retained flat-array cleanup.
     */
    public ProductDto removeAdditionalImage(UUID productId, int index) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): image write = SHOP_MANAGER

        List<String> urls = product.getAdditionalImageUrls();
        if (index < 0 || index >= urls.size()) {
            throw new ResourceNotFoundException("Image index out of range: " + index);
        }

        // Asset-model delete (IMG-01): drop the matching gallery join row + release its asset.
        releaseGalleryAssetAt(productId, index);

        // Dual-read flat cleanup (D-03a): still remove the flat-array entry + delete the object.
        String removedUrl = urls.remove(index);
        storageService.delete(removedUrl);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Removed additional image {} for product {}", index, productId);
        return resolveDetail(productMapper.toDto(product));
    }

    /**
     * Drop the product's {@code is_primary} {@code product_media} row (if any) and
     * ref-count-release its asset (IMG-01): the physical MinIO delete + {@code media_asset}
     * removal happen only when no other {@code product_media} row still references the asset
     * (a shared asset is preserved). Tenant-scoped by RLS + the caller's SHOP_MANAGER gate.
     */
    private void releasePrimaryAsset(UUID productId) {
        productMediaRepository.findByProductIdAndPrimaryTrue(productId).ifPresent(pm -> {
            UUID assetId = pm.getAssetId();
            productMediaRepository.delete(pm);
            productMediaRepository.flush();          // make the drop visible to the ref-count query
            mediaAssetService.releaseAsset(assetId);
        });
    }

    /**
     * Drop the gallery {@code product_media} row at the 0-based {@code index} (over the
     * product's non-primary rows ordered by {@code sort_order}) and ref-count-release its
     * asset (IMG-01). Positional over {@code sort_order} — robust to 0- vs 1-based ordinality
     * and gaps — so the Nth flat-array entry maps to the Nth gallery row. If no join row
     * exists at that position (e.g. a gallery image added via the still-flat path during the
     * dual-read window), the flat cleanup alone runs — nothing to release.
     */
    private void releaseGalleryAssetAt(UUID productId, int index) {
        List<ProductMedia> gallery = productMediaRepository
                .findByProductIdOrderByPrimaryDescSortOrderAsc(productId).stream()
                .filter(pm -> !pm.isPrimary())
                .toList();
        if (index >= 0 && index < gallery.size()) {
            ProductMedia row = gallery.get(index);
            UUID assetId = row.getAssetId();
            productMediaRepository.delete(row);
            productMediaRepository.flush();
            mediaAssetService.releaseAsset(assetId);
        }
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
