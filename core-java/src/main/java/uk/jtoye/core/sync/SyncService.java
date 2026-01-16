package uk.jtoye.core.sync;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

import java.util.Map;
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

    /**
     * Processes a batch of items from the Edge service.
     * Iterates through items, identifies their types (Shop, Product), and performs upserts.
     *
     * @param request the batch sync request
     * @return response with status and processed count
     */
    @Caching(evict = {
            @CacheEvict(value = "shops", allEntries = true),
            @CacheEvict(value = "products", allEntries = true)
    })
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

        Shop shop = shopRepository.findByName(name)
                .orElse(new Shop());

        shop.setTenantId(tenantId);
        shop.setName(name);
        shop.setAddress((String) item.get("address"));

        shopRepository.save(shop);
        return true;
    }

    private boolean upsertProduct(Map<String, Object> item, UUID tenantId) {
        String sku = (String) item.get("sku");
        if (sku == null) return false;

        Product product = productRepository.findBySku(sku)
                .orElse(new Product());

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
        return true;
    }
}
