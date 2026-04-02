package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ShopService {
    private static final Logger log = LoggerFactory.getLogger(ShopService.class);

    private final ShopRepository shopRepository;
    private final ShopMapper shopMapper;

    public ShopService(ShopRepository shopRepository, ShopMapper shopMapper) {
        this.shopRepository = shopRepository;
        this.shopMapper = shopMapper;
    }

    @CacheEvict(value = "shops", allEntries = true, beforeInvocation = false)
    public ShopDto createShop(CreateShopRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating shop '{}' for tenant {}", request.getName(), tenantId);

        Shop shop = shopMapper.toEntity(request);
        shop.setTenantId(tenantId);

        // Auto-generate slug from name if not provided
        if (shop.getSlug() == null || shop.getSlug().isBlank()) {
            shop.setSlug(generateSlug(request.getName()));
        }

        // Defaults
        if (shop.getPublished() == null) {
            shop.setPublished(false);
        }
        if (shop.getMinimumOrderPennies() == null) {
            shop.setMinimumOrderPennies(0L);
        }

        shop = shopRepository.saveAndFlush(shop);

        log.info("Created shop {} with ID {} for tenant {}", shop.getName(), shop.getId(), tenantId);

        return shopMapper.toDto(shop);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "shops", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
    public Optional<ShopDto> getShopById(UUID shopId) {
        log.debug("Fetching shop by ID: {}", shopId);
        return shopRepository.findById(shopId)
                .map(shopMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Page<ShopDto> getAllShops(Pageable pageable) {
        log.debug("Fetching shops with pagination: page {}, size {}",
                pageable.getPageNumber(), pageable.getPageSize());
        return shopRepository.findAll(pageable)
                .map(shopMapper::toDto);
    }

    @Transactional(readOnly = true)
    public List<ShopDto> search(String query) {
        log.debug("Searching shops with query: {}", query);
        return shopRepository.search(query).stream()
                .map(shopMapper::toDto)
                .toList();
    }

    @CacheEvict(value = "shops", allEntries = true, beforeInvocation = false)
    public ShopDto updateShop(UUID shopId, CreateShopRequest request) {
        log.debug("Updating shop {}", shopId);

        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        shopMapper.updateEntity(request, shop);

        // Regenerate slug if name changed and no explicit slug provided
        if (request.getSlug() == null || request.getSlug().isBlank()) {
            shop.setSlug(generateSlug(request.getName()));
        }

        shop = shopRepository.saveAndFlush(shop);

        log.info("Updated shop {} with ID {}", shop.getName(), shop.getId());

        return shopMapper.toDto(shop);
    }

    @CacheEvict(value = "shops", allEntries = true, beforeInvocation = false)
    public void deleteShop(UUID shopId) {
        log.debug("Deleting shop {}", shopId);

        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        shopRepository.delete(shop);

        log.info("Deleted shop {} with ID {}", shop.getName(), shop.getId());
    }

    private String generateSlug(String name) {
        String base = name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Append short random suffix for uniqueness
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return base + "-" + suffix;
    }
}
