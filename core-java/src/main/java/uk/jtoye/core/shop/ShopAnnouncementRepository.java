package uk.jtoye.core.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ShopAnnouncementRepository extends JpaRepository<ShopAnnouncement, UUID> {

    // Vendor-scoped access (Phase 23, VSA-02 / D-01): grant-set read-scope for the
    // announcements list — a non-GROUP_ADMIN sees only announcements whose shop_id is
    // in their grant set, narrowed at the QUERY. Callers guarantee a non-empty set.
    Page<ShopAnnouncement> findByShopIdIn(Collection<UUID> shopIds, Pageable pageable);

    @Query("SELECT a FROM ShopAnnouncement a WHERE a.shopId = :shopId AND a.active = true " +
           "AND (a.validFrom IS NULL OR a.validFrom <= CURRENT_TIMESTAMP) " +
           "AND (a.validUntil IS NULL OR a.validUntil > CURRENT_TIMESTAMP) " +
           "ORDER BY a.createdAt DESC")
    List<ShopAnnouncement> findActiveByShopId(@Param("shopId") UUID shopId);

    List<ShopAnnouncement> findByShopId(UUID shopId);
}
