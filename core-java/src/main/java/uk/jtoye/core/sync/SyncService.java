package uk.jtoye.core.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.InvalidRequestFieldsException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;
import uk.jtoye.core.sync.dto.SyncItem;
import uk.jtoye.core.sync.dto.SyncItemResult;
import uk.jtoye.core.sync.dto.SyncItemResult.Code;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Service for handling data synchronization from Edge services.
 * Provides batch processing with upsert logic for Shops and Products.
 *
 * <p><strong>Every item is answered (Phase 37-40, RWO-037 / UXT-037).</strong> The batch used to skip an
 * item it could not use and still report {@code SUCCESS}, and one refused item (a 403 from the shop gate)
 * threw away every other item in the batch. Each item now runs in its OWN transaction and yields exactly
 * one {@link SyncItemResult}: {@code CREATED}, {@code UPDATED}, or {@code REJECTED} with a stable code.
 * A rejected item writes nothing and leaves the others alone; the batch status is derived from the
 * results ({@link BatchSyncResponse#of}), so it cannot say more than the items did. Partial success is
 * reported, not rolled back: the HTTP status is 200 with the per-item outcomes. Request-level validation
 * (the {@link SyncItem} range bounds, API-2) still refuses the whole batch with a 400 before any item runs.
 *
 * <p><strong>A synced product belongs to a shop (RWO-017 / UXT-017, P0).</strong> The batch never set
 * {@code shop_id}, so every synced product was tenant-wide: listed on every shop's menu and orderable from
 * any of them. A product item must now name its shop ({@code shopId}, else {@code SHOP_REQUIRED}), and it
 * is written through {@link ProductService#createProduct} / {@link ProductService#updateProduct}, the
 * dashboard's own path, so the allergen emphasis spans are parsed and the 37-39 stock rules apply.
 *
 * <p><strong>Authorization lives HERE, next to the lookup (QA-council 20260902 Cluster A —
 * issue #648, findings SEC-5 / API-13).</strong> {@code SyncController.batchSync} gates the
 * endpoint on {@code SCOPE_catalog:write} (the machine half); this class decides the
 * within-tenant half — WHICH shop the caller may touch. A product item is gated with
 * {@link ShopAccessService#require(UUID, ShopRole)} {@code SHOP_MANAGER} on the item's shop BEFORE the
 * SKU is looked up, so a caller without a grant learns nothing about the SKU
 * ({@code SHOP_ACCESS_DENIED}); {@code ProductService} applies the same gate again on the write.
 * {@link #upsertShop} gates an update on the shop itself and routes a CREATE through
 * {@link ShopService#createShop} — the normal path — so the batch inherits its GROUP_ADMIN
 * gate, its slug derivation (the branch used to 400 on {@code shops.slug NOT NULL} on every
 * attempt, API-13) and the sole-writer invariant that a new shop is never born published.
 * The tenant wall itself is unchanged: the tenant comes from {@link TenantContext}, never
 * from the body, and RLS still scopes every query.
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private static final String SHOP = "shop";
    private static final String PRODUCT = "product";

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final TenantCacheEvictor cacheEvictor;
    private final ShopAccessService shopAccessService;
    private final ShopService shopService;
    private final ProductService productService;
    private final TransactionTemplate perItemTransaction;

    public SyncService(ShopRepository shopRepository,
                       ProductRepository productRepository,
                       TenantCacheEvictor cacheEvictor,
                       ShopAccessService shopAccessService,
                       ShopService shopService,
                       ProductService productService,
                       PlatformTransactionManager transactionManager) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.cacheEvictor = cacheEvictor;
        this.shopAccessService = shopAccessService;
        this.shopService = shopService;
        this.productService = productService;
        // One transaction per item (the OrderService.perOrderTransaction idiom): a rejection rolls back that
        // item alone. processBatch itself is deliberately NOT @Transactional, so there is no outer
        // transaction to join or to hold a second connection; TenantSetLocalAspect pins the tenant GUC
        // inside each item's transaction before its first repository call.
        this.perItemTransaction = new TransactionTemplate(transactionManager);
        this.perItemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Processes a batch of items from the Edge service, one transaction per item, and reports each.
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
     * {@link #upsertProduct} look the row up and mutate it when found), so an
     * existing row's cached DTO really does go stale and an eviction is NECESSARY. Deleting it
     * would trade a performance defect for a correctness one. Only the RADIUS was wrong: the
     * ids actually written are evicted one by one, under this tenant only.
     *
     * <p>A newly CREATED row is deliberately not evicted — same reasoning as #287, it has no
     * prior key. Evictions are registered {@code afterCommit} of the item's own transaction, so a
     * concurrent read cannot repopulate the entry from the uncommitted old row.
     *
     * @param request the batch sync request
     * @return the per-item results, the derived status and the number of items written
     */
    public BatchSyncResponse processBatch(BatchSyncRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        List<SyncItem> items = request.getItems() != null ? request.getItems() : List.of();
        log.info("Processing batch sync for tenant {}: {} items", tenantId, items.size());

        List<SyncItemResult> results = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            results.add(processItem(index, items.get(index), tenantId));
        }

        BatchSyncResponse response = BatchSyncResponse.of(results);
        log.info("Batch sync for tenant {}: status {}, {} of {} items written",
                tenantId, response.getStatus(), response.getProcessedCount(), items.size());
        return response;
    }

    private SyncItemResult processItem(int index, SyncItem item, UUID tenantId) {
        String type = item.getType();
        String normalised = type == null ? "" : type.toLowerCase(Locale.ROOT);
        switch (normalised) {
            case SHOP:
                return inOwnTransaction(index, type, item.getName(), () -> upsertShop(index, item, tenantId));
            case PRODUCT:
                return inOwnTransaction(index, type, item.getSku(), () -> upsertProduct(index, item, tenantId));
            default:
                String key = item.getSku() != null ? item.getSku() : item.getName();
                return SyncItemResult.rejected(index, type, key, Code.UNKNOWN_TYPE, type == null
                        ? "Item has no type; send \"shop\" or \"product\""
                        : "Unknown item type '" + type + "'; send \"shop\" or \"product\"");
        }
    }

    /**
     * Runs one item in its own transaction and turns a refusal into a REJECTED result. Anything thrown
     * rolls back this item's transaction only; the items before it are already committed and the items
     * after it still run.
     */
    private SyncItemResult inOwnTransaction(int index, String type, String key, Supplier<SyncItemResult> work) {
        try {
            return perItemTransaction.execute(status -> work.get());
        } catch (ShopAccessDeniedException e) {
            return SyncItemResult.rejected(index, type, key, Code.SHOP_ACCESS_DENIED, e.getMessage());
        } catch (ResourceNotFoundException e) {
            // ShopAccessService answers a shop outside the caller's tenant (or absent) with the
            // non-disclosing "Shop not found" — passed through as is.
            return SyncItemResult.rejected(index, type, key, Code.SHOP_NOT_FOUND, e.getMessage());
        } catch (InvalidRequestFieldsException e) {
            return SyncItemResult.rejected(index, type, key, Code.INVALID_FIELDS, describe(e.getErrors()));
        } catch (DataIntegrityViolationException e) {
            log.warn("Sync item {} ({} '{}') collided with a concurrent write: {}", index, type, key,
                    e.getMostSpecificCause().getMessage());
            return SyncItemResult.rejected(index, type, key, Code.WRITE_CONFLICT,
                    "The write collided with another change to the same record; nothing was stored for this item. Retry it.");
        } catch (RuntimeException e) {
            // Reported, not thrown: earlier items are already committed, so a 500 here would hide which
            // ones landed. Logged at ERROR so the failure is never silent.
            log.error("Sync item {} ({} '{}') failed", index, type, key, e);
            return SyncItemResult.rejected(index, type, key, Code.WRITE_FAILED,
                    "The item could not be stored; nothing was written for it.");
        }
    }

    private SyncItemResult upsertShop(int index, SyncItem item, UUID tenantId) {
        String name = item.getName();
        if (name == null || name.isBlank()) {
            return SyncItemResult.rejected(index, item.getType(), name, Code.NAME_REQUIRED,
                    "A shop item must carry the shop's name");
        }

        // PR #726 review M1: the upsert key is (tenant, name) — idx_shops_tenant_name is unique per
        // TENANT — so the lookup must say so. The bare findByName ran under shops_public_read and
        // ALSO returned a foreign tenant's PUBLISHED shop of the same name.
        Optional<Shop> existing = shopRepository.findByNameAndTenantId(name, tenantId);
        if (existing.isEmpty()) {
            // API-13: created through ShopService.createShop — the path the dashboard uses — which derives
            // the slug, enforces GROUP_ADMIN-only creation (VSA-02 / D-02) and forces published=false
            // (sole-writer invariant T-18-05-T). A create has no prior cache key to evict (#483/#287).
            CreateShopRequest request = new CreateShopRequest();
            request.setName(name);
            request.setAddress(item.getAddress());
            ShopDto created = shopService.createShop(request);
            return SyncItemResult.created(index, item.getType(), name, created.getId());
        }

        Shop shop = existing.get();
        // SEC-5: an UPDATE mutates a shop the caller must manage, so it is gated on the resolved
        // shop with SHOP_MANAGER before anything is set.
        shopAccessService.require(shop.getId(), ShopRole.SHOP_MANAGER);
        shop.setName(name);
        shop.setAddress(item.getAddress());
        shopRepository.save(shop);

        // Only an UPDATE can stale a cache entry; a create has no prior key (#483/#287).
        cacheEvictor.evictEntityAfterCommit("shops", "getShopById", shop.getId());
        return SyncItemResult.updated(index, item.getType(), name, shop.getId());
    }

    private SyncItemResult upsertProduct(int index, SyncItem item, UUID tenantId) {
        String sku = item.getSku();
        if (sku == null || sku.isBlank()) {
            return SyncItemResult.rejected(index, item.getType(), sku, Code.SKU_REQUIRED,
                    "A product item must carry its sku");
        }
        UUID shopId = item.getShopId();
        if (shopId == null) {
            return SyncItemResult.rejected(index, item.getType(), sku, Code.SHOP_REQUIRED,
                    "A product item must name the shop it belongs to (shopId); a product with no shop "
                            + "would be listed and orderable at every shop");
        }

        // SEC-5 / #648: gate on the item's shop BEFORE the SKU is looked up, so a caller without
        // SHOP_MANAGER there learns nothing about the SKU.
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);

        Optional<Product> existing = productRepository.findByTenantIdAndSkuAndShopId(tenantId, sku, shopId);
        if (existing.isPresent()) {
            UUID productId = existing.get().getId();
            // PR #726 review M7: every absent field means "unchanged" — updateProduct maps IGNORE-null, and
            // the 37-39 trackInventory rule decides stock.
            productService.updateProduct(productId, toProductRequest(item));
            cacheEvictor.evictEntityAfterCommit("products", "getProductById", productId);
            return SyncItemResult.updated(index, item.getType(), sku, productId);
        }

        Map<String, String> missing = missingCreateFields(item);
        if (!missing.isEmpty()) {
            return SyncItemResult.rejected(index, item.getType(), sku, Code.INVALID_FIELDS, describe(missing));
        }
        ProductDto created = productService.createProduct(toProductRequest(item));
        return SyncItemResult.created(index, item.getType(), sku, created.getId());
    }

    /**
     * The fields {@code CreateProductRequest} requires on a create, with its own messages. On an update an
     * absent field keeps the stored value (M7), so only a create is checked.
     */
    private static Map<String, String> missingCreateFields(SyncItem item) {
        Map<String, String> missing = new LinkedHashMap<>();
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            missing.put("title", "Title is required");
        }
        if (item.getIngredientsText() == null || item.getIngredientsText().isBlank()) {
            missing.put("ingredientsText", "Ingredients text is required (Natasha's Law)");
        }
        if (item.getAllergenMask() == null) {
            missing.put("allergenMask", "Allergen mask is required (Natasha's Law)");
        }
        if (item.getPricePennies() == null) {
            missing.put("pricePennies", "Price is required");
        }
        return missing;
    }

    private static CreateProductRequest toProductRequest(SyncItem item) {
        CreateProductRequest request = new CreateProductRequest();
        request.setSku(item.getSku());
        request.setTitle(item.getTitle());
        request.setIngredientsText(item.getIngredientsText());
        request.setAllergenMask(item.getAllergenMask());
        request.setPricePennies(item.getPricePennies());
        request.setShopId(item.getShopId());
        request.setAvailable(item.getAvailable());
        request.setQuantityInStock(item.getQuantityInStock());
        request.setTrackInventory(item.getTrackInventory());
        return request;
    }

    private static String describe(Map<String, String> errors) {
        return errors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
    }
}
