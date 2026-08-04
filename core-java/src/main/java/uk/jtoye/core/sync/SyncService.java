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
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling data synchronization from Edge services.
 * Provides batch processing with upsert logic for Shops and Products.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final TenantCacheEvictor cacheEvictor;

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
            for (Map<String, Object> item : request.getItems()) {
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

    private boolean processItem(Map<String, Object> item, UUID tenantId) {
        String type = (String) item.get("type");
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

    private boolean upsertShop(Map<String, Object> item, UUID tenantId) {
        String name = (String) item.get("name");
        if (name == null) return false;

        Optional<Shop> existing = shopRepository.findByName(name);
        Shop shop = existing.orElseGet(Shop::new);

        shop.setTenantId(tenantId);
        shop.setName(name);
        shop.setAddress((String) item.get("address"));

        shopRepository.save(shop);

        // Only an UPDATE can stale a cache entry; a create has no prior key (#483/#287).
        existing.map(Shop::getId)
                .ifPresent(id -> cacheEvictor.evictEntityAfterCommit("shops", "getShopById", id));
        return true;
    }

    private boolean upsertProduct(Map<String, Object> item, UUID tenantId) {
        String sku = (String) item.get("sku");
        if (sku == null) return false;

        Optional<Product> existing = productRepository.findBySku(sku);
        Product product = existing.orElseGet(Product::new);

        product.setTenantId(tenantId);
        product.setSku(sku);
        product.setTitle((String) item.get("title"));
        product.setIngredientsText((String) item.get("ingredientsText"));

        Object allergenMask = item.get("allergenMask");
        if (allergenMask instanceof Integer) {
            product.setAllergenMask((Integer) allergenMask);
        }

        Object pricePennies = item.get("pricePennies");
        if (pricePennies instanceof Number) {
            product.setPricePennies(((Number) pricePennies).longValue());
        }

        productRepository.save(product);

        existing.map(Product::getId)
                .ifPresent(id -> cacheEvictor.evictEntityAfterCommit("products", "getProductById", id));
        return true;
    }
}
