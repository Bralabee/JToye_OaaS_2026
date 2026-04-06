package uk.jtoye.core.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findBySku(String sku);

    Page<Product> findByAvailableTrue(Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.available = true ORDER BY p.category NULLS LAST, p.displayOrder ASC, p.title ASC")
    List<Product> findAvailableOrderedByCategory();

    @Query("SELECT p FROM Product p WHERE p.available = true AND (p.shopId = :shopId OR p.shopId IS NULL) ORDER BY p.category NULLS LAST, p.displayOrder ASC, p.title ASC")
    List<Product> findAvailableByShopOrderedByCategory(@Param("shopId") UUID shopId);

    List<Product> findByFeaturedTrueAndAvailableTrue();

    List<Product> findByShopId(UUID shopId);

    @Query("SELECT p FROM Product p WHERE LOWER(p.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<Product> search(@Param("q") String query);

    @Query(value = "SELECT * FROM products WHERE search_vector @@ plainto_tsquery('english', :q) ORDER BY ts_rank(search_vector, plainto_tsquery('english', :q)) DESC", nativeQuery = true)
    Page<Product> fullTextSearch(@Param("q") String query, Pageable pageable);

    @Query(value = "SELECT * FROM products WHERE search_vector @@ plainto_tsquery('english', :q) AND available = true AND (shop_id = :shopId OR shop_id IS NULL) ORDER BY ts_rank(search_vector, plainto_tsquery('english', :q)) DESC", nativeQuery = true)
    Page<Product> fullTextSearchByShop(@Param("q") String query, @Param("shopId") UUID shopId, Pageable pageable);
}
