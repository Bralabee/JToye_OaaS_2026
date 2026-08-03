package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.PromotionDto;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class PromotionService {
    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final ShopPromotionRepository promotionRepository;
    private final PromotionMapper promotionMapper;
    private final ShopAccessService shopAccessService;

    public PromotionService(ShopPromotionRepository promotionRepository, PromotionMapper promotionMapper,
                            ShopAccessService shopAccessService) {
        this.promotionRepository = promotionRepository;
        this.promotionMapper = promotionMapper;
        this.shopAccessService = shopAccessService;
    }

    @Transactional(readOnly = true)
    public Page<PromotionDto> getAllPromotions(Pageable pageable) {
        log.debug("Fetching promotions with pagination: page {}, size {}",
                pageable.getPageNumber(), pageable.getPageSize());
        // VSA-02 (D-01): read-scope by grant set at the QUERY.
        // FC-1 (QA-council, F-H1): confine the GROUP_ADMIN authenticated list to the caller's
        // tenant. A bare findAll() leaked other tenants' rows via the shop_promotions_read RLS
        // storefront carve-out; findByTenantId keeps this list tenant-scoped regardless.
        if (shopAccessService.isGroupAdmin()) {
            UUID tenantId = TenantContext.get()
                    .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
            return promotionRepository.findByTenantId(tenantId, pageable)
                    .map(promotionMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return promotionRepository.findByShopIdIn(granted, pageable)
                .map(promotionMapper::toDto);
    }

    /**
     * Get promotions for ONE shop of the tenant (WR-04, issue #280, plan 23-18).
     *
     * <p>Backs {@code GET /promotions?shopId=}, replacing a client-side filter applied over a
     * single already-paginated page (wrong counts, false empty state, unreachable rows past page 1).
     *
     * <p>VSA-02 (D-02): an explicit shop-scoped read requires at least STAFF on that shop — a
     * caller without a grant gets a typed 403, NOT an empty page.
     */
    @Transactional(readOnly = true)
    public Page<PromotionDto> getPromotionsByShop(UUID shopId, Pageable pageable) {
        log.debug("Fetching promotions for shop {} with pagination: page {}, size {}",
                shopId, pageable.getPageNumber(), pageable.getPageSize());
        shopAccessService.require(shopId, ShopRole.STAFF);
        return promotionRepository.findByShopIdIn(Set.of(shopId), pageable)
                .map(promotionMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<PromotionDto> getPromotionById(UUID id) {
        log.debug("Fetching promotion by ID: {}", id);
        return promotionRepository.findById(id)
                .map(promotion -> {
                    // VSA-02 (D-02): by-id read requires at least STAFF on the promo's shop.
                    shopAccessService.require(promotion.getShopId(), ShopRole.STAFF);
                    return promotionMapper.toDto(promotion);
                });
    }

    public PromotionDto createPromotion(CreatePromotionRequest request) {
        // VSA-02 (D-02): marketing create requires SHOP_MANAGER on the target shop (body shopId).
        shopAccessService.require(request.getShopId(), ShopRole.SHOP_MANAGER);

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating promotion '{}' for tenant {}", request.getLabel(), tenantId);

        ShopPromotion entity = promotionMapper.toEntity(request);
        entity.setTenantId(tenantId);

        entity = promotionRepository.saveAndFlush(entity);

        log.info("Created promotion '{}' with ID {} for tenant {}", entity.getLabel(), entity.getId(), tenantId);

        return promotionMapper.toDto(entity);
    }

    public PromotionDto updatePromotion(UUID id, CreatePromotionRequest request) {
        log.debug("Updating promotion {}", id);

        ShopPromotion entity = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));
        // VSA-02 (D-02): parent-lookup — marketing update requires SHOP_MANAGER.
        shopAccessService.require(entity.getShopId(), ShopRole.SHOP_MANAGER);

        promotionMapper.updateEntity(request, entity);

        try {
            entity = promotionRepository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException ex) {
            throw vanishedMidTransaction(id, ex);
        }

        log.info("Updated promotion '{}' with ID {}", entity.getLabel(), entity.getId());

        return promotionMapper.toDto(entity);
    }

    public void deletePromotion(UUID id) {
        log.debug("Deleting promotion {}", id);

        ShopPromotion entity = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));
        // VSA-02 (D-02): parent-lookup — marketing delete requires SHOP_MANAGER.
        shopAccessService.require(entity.getShopId(), ShopRole.SHOP_MANAGER);

        try {
            promotionRepository.delete(entity);
            // Issue #390: flush HERE rather than letting the DELETE go at commit. Without it the
            // row-count failure is raised after this method returns, outside any catch we could
            // write, and the request ends as an untyped error from the transaction boundary.
            promotionRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            throw vanishedMidTransaction(id, ex);
        }

        log.info("Deleted promotion '{}' with ID {}", entity.getLabel(), entity.getId());
    }

    /**
     * Issue #390 — the row was there when we read it and gone when we wrote it, so Hibernate's
     * row-count check failed ({@code Batch update returned unexpected row count from update [0]}).
     * That is a missing resource, not a server fault and not a conflict to retry: a client that
     * re-reads finds nothing and a client that retries gets the same answer forever.
     *
     * <p>Reachable by ordinary concurrent vendor activity — two staff deleting the same promotion,
     * a double-click, a retry after a slow response — and previously it left the request as an
     * untyped 5xx that inflated the {@code HighErrorRate} 5xx ratio with non-errors.
     *
     * <p><strong>Why 404 is unconditionally right here.</strong> {@link ShopPromotion} carries no
     * JPA {@code @Version}, so the statement's predicate is {@code id = ?} alone (confirmed in the
     * provider message: {@code delete from shop_promotions where id=?}). Zero affected rows can
     * therefore mean exactly one thing — no such row is visible to this transaction. It cannot mean
     * "stale version", which is the case {@code GlobalExceptionHandler}'s 409 handler exists for;
     * that handler stays in place for {@code @Version}-carrying entities and is not weakened here.
     *
     * <p>The message is byte-identical to the absent-at-read-time 404 above, so a caller cannot
     * distinguish the two and no timing information leaks. The provider message names the table and
     * is logged, never returned (same reasoning as the 409 handler).
     */
    private ResourceNotFoundException vanishedMidTransaction(UUID id, OptimisticLockingFailureException ex) {
        log.info("Promotion {} was removed by another transaction before this write landed: {}",
                id, ex.getMessage());
        return new ResourceNotFoundException("Promotion not found: " + id);
    }
}
