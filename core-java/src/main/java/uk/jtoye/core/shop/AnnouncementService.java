package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AnnouncementService {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

    private final ShopAnnouncementRepository announcementRepository;
    private final AnnouncementMapper announcementMapper;
    private final ShopAccessService shopAccessService;

    public AnnouncementService(ShopAnnouncementRepository announcementRepository, AnnouncementMapper announcementMapper,
                               ShopAccessService shopAccessService) {
        this.announcementRepository = announcementRepository;
        this.announcementMapper = announcementMapper;
        this.shopAccessService = shopAccessService;
    }

    @Transactional(readOnly = true)
    public Page<AnnouncementDto> getAllAnnouncements(Pageable pageable) {
        log.debug("Fetching announcements with pagination: page {}, size {}",
                pageable.getPageNumber(), pageable.getPageSize());
        // VSA-02 (D-01): read-scope by grant set at the QUERY.
        // FC-1 (QA-council, F-H1): confine the GROUP_ADMIN authenticated list to the caller's
        // tenant. A bare findAll() leaked other tenants' rows via the shop_announcements_read RLS
        // storefront carve-out; findByTenantId keeps this list tenant-scoped regardless.
        if (shopAccessService.isGroupAdmin()) {
            UUID tenantId = TenantContext.get()
                    .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
            return announcementRepository.findByTenantId(tenantId, pageable)
                    .map(announcementMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return announcementRepository.findByShopIdIn(granted, pageable)
                .map(announcementMapper::toDto);
    }

    /**
     * Get announcements for ONE shop of the tenant (WR-04, issue #280, plan 23-18).
     *
     * <p>Backs {@code GET /announcements?shopId=}, replacing a client-side filter applied over a
     * single already-paginated page (wrong counts, false empty state, unreachable rows past page 1).
     *
     * <p>VSA-02 (D-02): an explicit shop-scoped read requires at least STAFF on that shop — a
     * caller without a grant gets a typed 403, NOT an empty page.
     */
    @Transactional(readOnly = true)
    public Page<AnnouncementDto> getAnnouncementsByShop(UUID shopId, Pageable pageable) {
        log.debug("Fetching announcements for shop {} with pagination: page {}, size {}",
                shopId, pageable.getPageNumber(), pageable.getPageSize());
        shopAccessService.require(shopId, ShopRole.STAFF);
        return announcementRepository.findByShopIdIn(Set.of(shopId), pageable)
                .map(announcementMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<AnnouncementDto> getAnnouncementById(UUID id) {
        log.debug("Fetching announcement by ID: {}", id);
        return announcementRepository.findById(id)
                .map(announcement -> {
                    // VSA-02 (D-02): by-id read requires at least STAFF on the announcement's shop.
                    shopAccessService.require(announcement.getShopId(), ShopRole.STAFF);
                    return announcementMapper.toDto(announcement);
                });
    }

    public AnnouncementDto createAnnouncement(CreateAnnouncementRequest request) {
        // VSA-02 (D-02): marketing create requires SHOP_MANAGER on the target shop (body shopId).
        shopAccessService.require(request.getShopId(), ShopRole.SHOP_MANAGER);

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating announcement '{}' for tenant {}", request.getTitle(), tenantId);

        ShopAnnouncement entity = announcementMapper.toEntity(request);
        entity.setTenantId(tenantId);

        if (entity.getActive() == null) {
            entity.setActive(true);
        }

        entity = announcementRepository.saveAndFlush(entity);

        log.info("Created announcement '{}' with ID {} for tenant {}", entity.getTitle(), entity.getId(), tenantId);

        return announcementMapper.toDto(entity);
    }

    public AnnouncementDto updateAnnouncement(UUID id, CreateAnnouncementRequest request) {
        log.debug("Updating announcement {}", id);

        ShopAnnouncement entity = announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found: " + id));
        // VSA-02 (D-02): parent-lookup — marketing update requires SHOP_MANAGER.
        shopAccessService.require(entity.getShopId(), ShopRole.SHOP_MANAGER);

        announcementMapper.updateEntity(request, entity);

        entity = announcementRepository.saveAndFlush(entity);

        log.info("Updated announcement '{}' with ID {}", entity.getTitle(), entity.getId());

        return announcementMapper.toDto(entity);
    }

    public void deleteAnnouncement(UUID id) {
        log.debug("Deleting announcement {}", id);

        ShopAnnouncement entity = announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found: " + id));
        // VSA-02 (D-02): parent-lookup — marketing delete requires SHOP_MANAGER.
        shopAccessService.require(entity.getShopId(), ShopRole.SHOP_MANAGER);

        announcementRepository.delete(entity);

        log.info("Deleted announcement '{}' with ID {}", entity.getTitle(), entity.getId());
    }
}
