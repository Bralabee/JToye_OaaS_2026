package uk.jtoye.core.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ShopPromotionRepository extends JpaRepository<ShopPromotion, UUID> {

    // Vendor-scoped access (Phase 23, VSA-02 / D-01): grant-set read-scope for the
    // promotions list — a non-GROUP_ADMIN sees only promotions whose shop_id is in
    // their grant set, narrowed at the QUERY. Callers guarantee a non-empty set.
    Page<ShopPromotion> findByShopIdIn(Collection<UUID> shopIds, Pageable pageable);

    // FC-1 (QA-council): the GROUP_ADMIN authenticated list is confined to the caller's
    // tenant at the QUERY. A bare findAll() leaks OTHER tenants' rows through the
    // shop_promotions_read RLS policy's `OR EXISTS(published shop)` storefront carve-out;
    // an explicit tenant filter closes that read leak without touching the RLS policy or
    // the anonymous storefront path.
    Page<ShopPromotion> findByTenantId(UUID tenantId, Pageable pageable);

    @Query("SELECT p FROM ShopPromotion p WHERE p.shopId = :shopId AND p.active = true AND p.validFrom <= CURRENT_TIMESTAMP AND p.validUntil > CURRENT_TIMESTAMP ORDER BY p.createdAt DESC")
    List<ShopPromotion> findActiveByShopId(@Param("shopId") UUID shopId);

    List<ShopPromotion> findByShopId(UUID shopId);

    Page<ShopPromotion> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
