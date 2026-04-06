package uk.jtoye.core.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {
    Optional<Shop> findByName(String name);

    Optional<Shop> findBySlug(String slug);

    Optional<Shop> findBySlugAndPublishedTrue(String slug);

    Page<Shop> findByPublishedTrue(Pageable pageable);

    @Query("SELECT s FROM Shop s WHERE s.published = true AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(s.tags) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Shop> searchPublished(@Param("q") String query, Pageable pageable);

    @Query("SELECT s FROM Shop s WHERE LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(s.address) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<Shop> search(@Param("q") String query);

    @Query(value = "SELECT * FROM shops WHERE published = true AND search_vector @@ plainto_tsquery('english', :q) ORDER BY ts_rank(search_vector, plainto_tsquery('english', :q)) DESC",
           countQuery = "SELECT COUNT(*) FROM shops WHERE published = true AND search_vector @@ plainto_tsquery('english', :q)",
           nativeQuery = true)
    Page<Shop> fullTextSearchPublished(@Param("q") String query, Pageable pageable);
}
