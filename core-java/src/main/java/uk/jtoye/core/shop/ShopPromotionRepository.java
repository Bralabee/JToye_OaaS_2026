package uk.jtoye.core.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShopPromotionRepository extends JpaRepository<ShopPromotion, UUID> {

    @Query("SELECT p FROM ShopPromotion p WHERE p.shopId = :shopId AND p.active = true AND p.validFrom <= CURRENT_TIMESTAMP AND p.validUntil > CURRENT_TIMESTAMP ORDER BY p.createdAt DESC")
    List<ShopPromotion> findActiveByShopId(@Param("shopId") UUID shopId);

    List<ShopPromotion> findByShopId(UUID shopId);

    Page<ShopPromotion> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
