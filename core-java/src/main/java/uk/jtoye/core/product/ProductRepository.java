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

    // UIX-05 (phase 19): strictly shop-scoped. The removed NULL-shop_id fallback
    // rendered every unassigned product on EVERY shop's menu (24/25 live rows were
    // unassigned) — a second shop showed a duplicated clone of the first. There is
    // no "tenant-wide items" feature; every product belongs to exactly one shop.
    // RLS already scopes `products` to the tenant; this narrows within-tenant by shop.
    @Query("SELECT p FROM Product p WHERE p.available = true AND p.shopId = :shopId ORDER BY p.category NULLS LAST, p.displayOrder ASC, p.title ASC")
    List<Product> findAvailableByShopOrderedByCategory(@Param("shopId") UUID shopId);

    List<Product> findByFeaturedTrueAndAvailableTrue();

    List<Product> findByShopId(UUID shopId);

    // Live product search (Issue #96): GIN/tsvector full-text over the V25
    // search_vector (title A, category B, description/ingredients C, dietary D)
    // using a caller-built prefix tsquery ("chick:*" finds "Chicken"), plus an
    // anchored SKU-prefix branch — SKU is not in the vector and adding it needs
    // a migration, so the old title-or-SKU UX is preserved via the second UNION
    // arm. The branches are UNIONed by id (not OR-ed in one predicate) so the
    // text branch stays servable by idx_products_search on its own; an OR would
    // force a full scan even once the index is reachable. NOTE: today the RLS
    // security barrier blocks that index for the app role because ts_match_vq
    // (the @@ function) is not LEAKPROOF — Postgres only allows leakproof
    // operators as index quals beneath row security, so the FTS branch planner-
    // degrades to a tenant-filtered seq scan. A future one-line migration
    // (ALTER FUNCTION pg_catalog.ts_match_vq(tsvector, tsquery) LEAKPROOF,
    // verified on postgres:15) flips this exact SQL to a Bitmap Index Scan with
    // zero code change; ProductSearchFtsIntegrationTest pins both plans. RLS
    // scopes every products reference to the current tenant. ts_rank orders FTS
    // matches by relevance; SKU-only matches rank 0 and sort after, tie-broken
    // by title.
    @Query(value = "SELECT p.* FROM products p WHERE p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!') "
            + "ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) DESC, p.title ASC, p.id ASC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!')",
           nativeQuery = true)
    Page<Product> searchFullText(@Param("tsQuery") String tsQuery, @Param("skuPrefix") String skuPrefix, Pageable pageable);
}
