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

import java.util.Optional;
import java.util.UUID;

/**
 * Service for shop management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 */
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

    /**
     * Create a new shop.
     * Automatically assigns tenant from context.
     * Evicts all shop caches for the tenant to maintain consistency.
     */
    @CacheEvict(value = "shops", allEntries = true, beforeInvocation = false)
    public ShopDto createShop(CreateShopRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating shop '{}' for tenant {}", request.getName(), tenantId);

        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setName(request.getName());
        shop.setAddress(request.getAddress());

        // Use saveAndFlush to ensure creation timestamp is populated for the response
        shop = shopRepository.saveAndFlush(shop);

        log.info("Created shop {} with ID {} for tenant {}", shop.getName(), shop.getId(), tenantId);

        return shopMapper.toDto(shop);
    }

    /**
     * Get shop by ID (tenant-scoped).
     * Results are cached with tenant-aware key generation (TTL: 15 minutes).
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "shops", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
    public Optional<ShopDto> getShopById(UUID shopId) {
        log.debug("Fetching shop by ID: {}", shopId);
        return shopRepository.findById(shopId)
                .map(shopMapper::toDto);
    }

    /**
     * Get all shops (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<ShopDto> getAllShops(Pageable pageable) {
        log.debug("Fetching shops with pagination: page {}, size {}",
                pageable.getPageNumber(), pageable.getPageSize());
        return shopRepository.findAll(pageable)
                .map(shopMapper::toDto);
    }

    /**
     * Update an existing shop (tenant-scoped).
     * Evicts all shop caches for the tenant to maintain consistency.
     */
    @CacheEvict(value = "shops", allEntries = true, beforeInvocation = false)
    public ShopDto updateShop(UUID shopId, CreateShopRequest request) {
        log.debug("Updating shop {}", shopId);

        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        shop.setName(request.getName());
        shop.setAddress(request.getAddress());

        shop = shopRepository.saveAndFlush(shop);

        log.info("Updated shop {} with ID {}", shop.getName(), shop.getId());

        return shopMapper.toDto(shop);
    }

    /**
     * Delete shop by ID (tenant-scoped).
     * Evicts all shop caches for the tenant to maintain consistency.
     */
    @CacheEvict(value = "shops", allEntries = true, beforeInvocation = false)
    public void deleteShop(UUID shopId) {
        log.debug("Deleting shop {}", shopId);

        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        shopRepository.delete(shop);

        log.info("Deleted shop {} with ID {}", shop.getName(), shop.getId());
    }

    /**
     * Convert Shop entity to DTO.
     * @deprecated Use {@link ShopMapper#toDto(Shop)} instead.
     * TODO: Remove after migration to MapStruct is complete.
     */
    @Deprecated
    private ShopDto toDto(Shop shop) {
        ShopDto dto = new ShopDto();
        dto.setId(shop.getId());
        dto.setName(shop.getName());
        dto.setAddress(shop.getAddress());
        dto.setCreatedAt(shop.getCreatedAt());
        return dto;
    }
}
