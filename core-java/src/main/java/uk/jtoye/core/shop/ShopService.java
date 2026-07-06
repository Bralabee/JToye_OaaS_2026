package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.storage.StorageService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ShopService {
    private static final Logger log = LoggerFactory.getLogger(ShopService.class);

    private final ShopRepository shopRepository;
    private final ShopMapper shopMapper;
    private final StorageService storageService;
    private final TenantCacheEvictor cacheEvictor;

    public ShopService(ShopRepository shopRepository,
                       ShopMapper shopMapper,
                       StorageService storageService,
                       TenantCacheEvictor cacheEvictor) {
        this.shopRepository = shopRepository;
        this.shopMapper = shopMapper;
        this.storageService = storageService;
        this.cacheEvictor = cacheEvictor;
    }

    // createShop inserts a brand-new entity; no existing cache key could match it,
    // so no eviction is required. (Previously @CacheEvict(allEntries=true) blew
    // away every tenant's cache on a single tenant's write.)
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

    // QA-council BE-03: scope the authenticated "my shops" list to the caller's
    // tenant. Relying on RLS alone leaked other tenants' PUBLISHED shops here,
    // because the shops_public_read policy (V16) OR-permits published=true.
    @Transactional(readOnly = true)
    public Page<ShopDto> getAllShops(Pageable pageable) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        log.debug("Fetching shops for tenant {} with pagination: page {}, size {}",
                tenantId, pageable.getPageNumber(), pageable.getPageSize());
        return shopRepository.findByTenantId(tenantId, pageable)
                .map(shopMapper::toDto);
    }

    // QA-council BE-03: scope authenticated shop search to the caller's tenant
    // (same published-shop leak as getAllShops via the unscoped search query).
    @Transactional(readOnly = true)
    public List<ShopDto> search(String query) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        log.debug("Searching shops for tenant {} with query: {}", tenantId, query);
        return shopRepository.searchByTenant(tenantId, query).stream()
                .map(shopMapper::toDto)
                .toList();
    }

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
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Updated shop {} with ID {}", shop.getName(), shop.getId());

        return shopMapper.toDto(shop);
    }

    public ShopDto uploadLogo(UUID shopId, MultipartFile file) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getLogoUrl());

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.uploadNamed(tenantId, "shops", shopId, "logo", file);
        shop.setLogoUrl(url);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Uploaded logo for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto removeLogo(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getLogoUrl());
        shop.setLogoUrl(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Removed logo for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto uploadBanner(UUID shopId, MultipartFile file) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getBannerUrl());

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.uploadNamed(tenantId, "shops", shopId, "banner", file);
        shop.setBannerUrl(url);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Uploaded banner for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto removeBanner(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getBannerUrl());
        shop.setBannerUrl(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Removed banner for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public void deleteShop(UUID shopId) {
        log.debug("Deleting shop {}", shopId);

        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        // Clean up images from storage
        storageService.delete(shop.getLogoUrl());
        storageService.delete(shop.getBannerUrl());

        shopRepository.delete(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

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
