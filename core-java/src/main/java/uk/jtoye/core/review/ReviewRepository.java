package uk.jtoye.core.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

    Optional<Review> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    List<Review> findByCustomerEmail(String customerEmail);

    @Query(value = "SELECT COUNT(*) FROM reviews WHERE shop_id = :shopId", nativeQuery = true)
    long countByShopId(@Param("shopId") UUID shopId);

    @Query(value = "SELECT COALESCE(ROUND(AVG(food_rating)::numeric, 1), 0) FROM reviews WHERE shop_id = :shopId", nativeQuery = true)
    Double avgFoodRatingByShopId(@Param("shopId") UUID shopId);
}
