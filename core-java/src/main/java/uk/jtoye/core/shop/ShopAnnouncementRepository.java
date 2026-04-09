package uk.jtoye.core.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShopAnnouncementRepository extends JpaRepository<ShopAnnouncement, UUID> {

    @Query("SELECT a FROM ShopAnnouncement a WHERE a.shopId = :shopId AND a.active = true " +
           "AND (a.validFrom IS NULL OR a.validFrom <= CURRENT_TIMESTAMP) " +
           "AND (a.validUntil IS NULL OR a.validUntil > CURRENT_TIMESTAMP) " +
           "ORDER BY a.createdAt DESC")
    List<ShopAnnouncement> findActiveByShopId(@Param("shopId") UUID shopId);

    List<ShopAnnouncement> findByShopId(UUID shopId);
}
