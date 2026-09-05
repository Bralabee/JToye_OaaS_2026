package uk.jtoye.core.sync;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;
import uk.jtoye.core.sync.dto.SyncItem;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling data synchronization from Edge services.
 * Provides batch processing with upsert logic for Shops and Products.
 *
 * <p><strong>Authorization lives HERE, next to the lookup (QA-council 20260902 Cluster A —
 * issue #648, findings SEC-5 / API-13).</strong> {@code SyncController.batchSync} gates the
 * endpoint on {@code SCOPE_catalog:write} (the machine half); this class decides the
 * within-tenant half — WHICH shop the caller may touch — because that can only be known after
 * the row is resolved. {@link #upsertProduct} resolves by SKU, which is unique per tenant
 * (V3), so an attacker-supplied SKU names exactly one product anywhere in the tenant; the
 * write is therefore gated on that product's OWNING shop via
 * {@link ShopAccessService#require(UUID, ShopRole)} with {@code SHOP_MANAGER} — the
 * {@code ProductService.updateProduct} pattern (VSA-02 / D-02) — BEFORE anything is mutated.
 * A brand-new SKU or a legacy {@code shop_id IS NULL} product has no shop to bind to and is a
 * tenant-wide resource: {@code require(null, …)} makes that GROUP_ADMIN-only (CR-04 write
 * half). {@link #upsertShop} gates an update on the shop itself and routes a CREATE through
 * {@link ShopService#createShop} — the normal path — so the batch inherits its GROUP_ADMIN
 * gate, its slug derivation (the branch used to 400 on {@code shops.slug NOT NULL} on every
 * attempt, API-13) and the sole-writer invariant that a new shop is never born published.
 * The tenant wall itself is unchanged: the tenant comes from {@link TenantContext}, never
 * from the body, and RLS still scopes every query.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final TenantCacheEvictor cacheEvictor;
    private final ShopAccessService shopAccessService;
    private final ShopService shopService;

    /**
     * Processes a batch of items from the Edge service.
     * Iterates through items, identifies their types (Shop, Product), and performs upserts.
     *
     * <p><strong>Tenant-scoped, per-id cache invalidation (issue #483).</strong> This method
     * used to carry {@code @Caching(evict = {@CacheEvict(value = "shops", allEntries = true),
     * @CacheEvict(value = "products", allEntries = true)})}. {@code allEntries} clears the WHOLE
     * cache region, and a region is shared by every tenant — isolation lives in the KEY
     * ({@code tenant:{tid}:getShopById:{id}}), not in the region. So one vendor's Edge sync
     * batch cold-started every other vendor's shop and catalogue reads, and it fired on the
     * normal return even for an empty batch that touched nothing.
     *
     * <p><strong>Why the eviction is NARROWED and not REMOVED.</strong> #287 could delete the
     * equivalent annotation from {@code BulkImportService} because CSV/image import is provably
     * create-only: a row that never existed has no key in the region, so nothing can be staled.
     * This path is different — it genuinely UPSERTS ({@link #upsertShop} /
     * {@link #upsertProduct} look the row up by name/sku and mutate it when found), so an
     * existing row's cached DTO really does go stale and an eviction is NECESSARY. Deleting it
     * would trade a performance defect for a correctness one. Only the RADIUS was wrong: the
     * ids actually written are accumulated and evicted one by one, under this tenant only.
     *
     * <p>A newly CREATED row is deliberately not evicted — same reasoning as #287, it has no
     * prior key. Evictions are registered {@code afterCommit} because this transaction stays
     * open for the whole batch; an inline evict on the first of many rows would leave a window,
     * as wide as the rest of the batch, for a concurrent read to repopulate the entry from the
     * uncommitted old row.
     *
     * <p>Items arrive already range-validated ({@link SyncItem} bounds via {@code @Valid} on the
     * request — API-2); a denied item throws the typed shop-access 403 and, because the whole
     * batch is one transaction, nothing from the batch is committed.
     *
     * @param request the batch sync request
     * @return response with status and processed count
     */
    public BatchSyncResponse processBatch(BatchSyncRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.info("Processing batch sync for tenant {}: {} items",
                tenantId,
                request.getItems() != null ? request.getItems().size() : 0);

        int count = 0;
        if (request.getItems() != null) {
            for (SyncItem item : request.getItems()) {
                if (processItem(item, tenantId)) {
                    count++;
                }
            }
        }

        return BatchSyncResponse.builder()
                .status("SUCCESS")
                .processedCount(count)
                .build();
    }

    private boolean processItem(SyncItem item, UUID tenantId) {
        String type = item.getType();
        if (type == null) {
            log.warn("Item missing 'type' field, skipping");
            return false;
        }

        switch (type.toLowerCase()) {
            case "shop":
                return upsertShop(item, tenantId);
            case "product":
                return upsertProduct(item, tenantId);
            default:
                log.warn("Unknown item type '{}', skipping", type);
                return false;
        }
    }

    private boolean upsertShop(SyncItem item, UUID tenantId) {
        String name = item.getName();
        if (name == null) return false;

        // PR #726 review M1: the upsert key is (tenant, name) — idx_shops_tenant_name is unique per
        // TENANT — so the lookup must say so. The bare findByName ran under shops_public_read and
        // ALSO returned a foreign tenant's PUBLISHED shop of the same name: two rows (500, whole
        // batch rolled back) when this tenant has its own, or the foreign row alone when it does
        // not — which require() below then correctly refused, so the caller could never create it.
        Optional<Shop> existing = shopRepository.findByNameAndTenantId(name, tenantId);
        if (existing.isEmpty()) {
            // API-13: a bare `shopRepository.save(new Shop())` never set `slug` (NOT NULL), so this
            // branch had failed on every attempt (zero rows ever). Creating through
            // ShopService.createShop — the path the dashboard uses — derives the slug the same
            // way, enforces GROUP_ADMIN-only creation (VSA-02 / D-02) and forces published=false
            // (sole-writer invariant T-18-05-T). A create has no prior cache key to evict (#483/#287).
            CreateShopRequest request = new CreateShopRequest();
            request.setName(name);
            request.setAddress(item.getAddress());
            shopService.createShop(request);
            return true;
        }

        Shop shop = existing.get();
        // SEC-5: an UPDATE mutates a shop the caller must manage, so it is gated on the resolved
        // shop with SHOP_MANAGER. The lookup above is already tenant-scoped, so the row is provably
        // this tenant's before the gate runs; require()'s own FC-1 tenant proof stays as the
        // second, independent layer rather than the only one.
        shopAccessService.require(shop.getId(), ShopRole.SHOP_MANAGER);
        shop.setName(name);
        shop.setAddress(item.getAddress());
        shopRepository.save(shop);

        // Only an UPDATE can stale a cache entry; a create has no prior key (#483/#287).
        cacheEvictor.evictEntityAfterCommit("shops", "getShopById", shop.getId());
        return true;
    }

    private boolean upsertProduct(SyncItem item, UUID tenantId) {
        String sku = item.getSku();
        if (sku == null) return false;

        Optional<Product> existing = productRepository.findBySku(sku);
        // SEC-5 / #648: gate on the OWNING shop of whatever the SKU resolved to, BEFORE any setter
        // runs (ProductService.updateProduct:354 pattern). An existing product with no shop and a
        // brand-new SKU (this path never assigns shop_id) both yield null -> tenant-wide resource
        // -> GROUP_ADMIN-only (CR-04 write half). Under strict-scoping OFF an ungranted day-one
        // user is the implicit GROUP_ADMIN and passes; a user holding ANY explicit grant is scoped
        // and is denied the typed shop-access 403 here.
        shopAccessService.require(existing.map(Product::getShopId).orElse(null), ShopRole.SHOP_MANAGER);

        // PR #726 review M7: every SyncItem field is optional on the wire, so on an UPDATE an absent
        // title/ingredientsText means "unchanged", exactly as allergenMask and pricePennies already
        // did — not "clear it" (title would then die on products.title NOT NULL as a 500 after the
        // batch half-ran; the Natasha's Law ingredients text would silently blank). A CREATE has no
        // prior value to keep, so a title is REQUIRED there and its absence is the typed 400
        // (IllegalArgumentException -> errors/invalid-argument) before anything is written.
        if (existing.isEmpty() && (item.getTitle() == null || item.getTitle().isBlank())) {
            throw new IllegalArgumentException(
                    "Product title is required to create a new product (sku '" + sku + "')");
        }

        Product product = existing.orElseGet(Product::new);

        product.setTenantId(tenantId);
        product.setSku(sku);

        if (item.getTitle() != null) {
            product.setTitle(item.getTitle());
        }

        if (item.getIngredientsText() != null) {
            product.setIngredientsText(item.getIngredientsText());
        }

        if (item.getAllergenMask() != null) {
            product.setAllergenMask(item.getAllergenMask());
        }

        if (item.getPricePennies() != null) {
            product.setPricePennies(item.getPricePennies());
        }

        productRepository.save(product);

        existing.map(Product::getId)
                .ifPresent(id -> cacheEvictor.evictEntityAfterCommit("products", "getProductById", id));
        return true;
    }
}
