package uk.jtoye.core.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findBySku(String sku);

    // Vendor-scoped access (Phase 23, VSA-02 / D-01): read-scope the product list to
    // the caller's GRANT SET at the QUERY. A non-GROUP_ADMIN sees only products whose
    // shop_id is in their grant set — narrowed server-side, never a post-hoc filter.
    // GROUP_ADMIN keeps the wider findAll path. RLS still scopes every row to the tenant.
    Page<Product> findByShopIdIn(Collection<UUID> shopIds, Pageable pageable);

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
    // force a full scan even though the index is reachable. NOTE: Postgres only
    // allows LEAKPROOF operators as index quals beneath the RLS security
    // barrier; V44 marks ts_match_vq (the @@ function) LEAKPROOF so this exact
    // SQL is served by a Bitmap Index Scan on idx_products_search for the
    // RLS-bound app role (on DBs where the migration role lacked superuser the
    // ALTER is skipped with a WARNING and the branch planner-degrades to a
    // still-correct tenant-filtered seq scan until the documented manual step
    // runs). ProductSearchFtsIntegrationTest pins the plan. RLS scopes every
    // products reference to the current tenant. ts_rank orders FTS matches by
    // relevance; SKU-only matches rank 0 and sort after, tie-broken by title.
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

    // Vendor-scoped access (Phase 23, VSA-02 / D-01): grant-set-narrowed variant of
    // searchFullText for a scoped (non-GROUP_ADMIN) caller. Identical FTS+SKU logic,
    // with an added outer `p.shop_id IN (:shopIds)` filter so search results never
    // include products outside the caller's grants. Callers guarantee a non-empty
    // shopIds set (empty grant → deny-by-default short-circuit before this call).
    @Query(value = "SELECT p.* FROM products p WHERE p.shop_id IN (:shopIds) AND p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!') "
            + "ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) DESC, p.title ASC, p.id ASC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.shop_id IN (:shopIds) AND p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!')",
           nativeQuery = true)
    Page<Product> searchFullTextInShops(@Param("tsQuery") String tsQuery, @Param("skuPrefix") String skuPrefix,
                                        @Param("shopIds") Collection<UUID> shopIds, Pageable pageable);
}
