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

    // BE-03 completion: scope the authenticated by-id read to the caller's tenant.
    // findById is RLS-only and shops_public_read permits published shops, so a
    // tenant could otherwise fetch another tenant's PUBLISHED shop by direct id.
    @Transactional(readOnly = true)
    @Cacheable(value = "shops", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
    public Optional<ShopDto> getShopById(UUID shopId) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        log.debug("Fetching shop {} for tenant {}", shopId, tenantId);
        return shopRepository.findByIdAndTenantId(shopId, tenantId)
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

        // QA-council M3: scope the write to the caller's tenant. findById is
        // RLS-only and shops_public_read (V16) permits published shops, so a
        // cross-tenant PUT otherwise loaded another tenant's PUBLISHED shop, then
        // failed the FORCE-RLS write with a StaleStateException surfaced as 500.
        // findByIdAndTenantId (as getShopById already uses) yields a clean 404.
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Shop shop = shopRepository.findByIdAndTenantId(shopId, tenantId)
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
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
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
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getLogoUrl());
        shop.setLogoUrl(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Removed logo for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto uploadBanner(UUID shopId, MultipartFile file) {
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
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
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getBannerUrl());
        shop.setBannerUrl(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Removed banner for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    /**
     * Set a shop's {@code published} flag. This is the SINGLE mutation point for
     * {@code published} and is reached ONLY from the vendor-onboarding state
     * machine's side effects (GO_LIVE → true, SUSPEND → false, REINSTATE → true)
     * in {@code VendorOnboardingService}. The onboarding state machine is the
     * <strong>sole authorised writer of {@code published=true}</strong>: no
     * controller or DTO path may flip a storefront live outside the guarded
     * GO_LIVE transition (threat T-18-02-T).
     *
     * <p>N4: {@code Shop.published} is a nullable {@code Boolean}; the primitive
     * {@code boolean} parameter autoboxes cleanly on {@code setPublished(published)},
     * so the entity field stays {@code Boolean} (no primitive migration).
     */
    public void setPublished(UUID shopId, boolean published) {
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        shop.setPublished(published);
        shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);
        log.info("Shop {} published flag set to {}", shopId, published);
    }

    public void deleteShop(UUID shopId) {
        log.debug("Deleting shop {}", shopId);

        // QA-council M3: scope the delete to the caller's tenant (see updateShop).
        // A cross-tenant DELETE otherwise loaded another tenant's published shop
        // then failed the FORCE-RLS delete with a StaleStateException → 500.
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Shop shop = shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        // Clean up images from storage
        storageService.delete(shop.getLogoUrl());
        storageService.delete(shop.getBannerUrl());

        shopRepository.delete(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Deleted shop {} with ID {}", shop.getName(), shop.getId());
    }

    // QA-council M3 (extended): the caller's tenant, required. Used to scope shop
    // writes so a cross-tenant request 404s BEFORE any side effect (e.g. an S3
    // object delete) runs against another tenant's shop.
    private UUID requireTenantId() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
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
