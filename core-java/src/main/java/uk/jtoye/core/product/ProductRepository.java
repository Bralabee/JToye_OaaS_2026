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

    // Vendor-scoped access (Phase 23, VSA-02 / D-01; WR-08 read half, plan 23-10): read-scope
    // the product list to the caller's GRANT SET at the QUERY, ALSO admitting legacy
    // tenant-wide rows whose shop_id IS NULL (an `IN (:ids)` list never matches NULL, which is
    // exactly why a scoped user's catalogue previously showed ZERO of them — WR-08). This is the
    // READ half of the null-shop policy: reads of a null-shop resource are tenant-wide-visible to
    // any GRANTED scoped user, pairing with 23-08's WRITE half which keeps null-shop writes
    // GROUP_ADMIN-only. It restores the pre-phase visibility of legacy catalogue data (Incremental
    // Betterment) rather than silently hiding it. A non-GROUP_ADMIN sees their granted shops PLUS
    // tenant-wide rows; GROUP_ADMIN keeps the wider findAll path; a ZERO-grant user is
    // short-circuited to an empty page BEFORE this call (deny-by-default preserved).
    //
    // The tenant_id filter is EXPLICIT (not RLS-only), mirroring ShopRepository.findByTenantIdAndIdIn:
    // the OR-null branch is NOT implicitly tenant-local the way `shop_id IN (grant set)` is (grant
    // ids belong to the caller's tenant), so a null-shop row of ANOTHER tenant would match `shop_id
    // IS NULL` if RLS were ever bypassed (e.g. a table-owner connection). The parentheses are
    // load-bearing: `tenant_id = :tid AND (shop_id IN (:ids) OR shop_id IS NULL)`, never
    // `... AND shop_id IN (:ids) OR shop_id IS NULL` which would leak cross-tenant null-shop rows.
    @Query("SELECT p FROM Product p WHERE p.tenantId = :tenantId "
            + "AND (p.shopId IN :shopIds OR p.shopId IS NULL)")
    Page<Product> findTenantScopedInGrantSetOrTenantWide(@Param("tenantId") UUID tenantId,
            @Param("shopIds") Collection<UUID> shopIds, Pageable pageable);

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

    // WR-04 (issue #280, plan 23-18): paged single-shop variant backing the explicit
    // `GET /products?shopId=` narrow. Deliberately EXCLUDES legacy tenant-wide
    // (shop_id IS NULL) rows, unlike findTenantScopedInGrantSetOrTenantWide: that is
    // exactly what the client-side filter this replaces did
    // (`products.filter(p => p.shopId === contextShopId)`), so moving the narrow to the
    // server changes WHERE the filter runs and nothing about WHICH rows a vendor sees.
    // Tenant scoping is RLS's job here — there is no OR-null branch to make non-tenant-local
    // (contrast findTenantScopedInGrantSetOrTenantWide, which needs an explicit tenant_id for
    // precisely that reason), and the caller has already passed
    // shopAccessService.require(shopId, STAFF), which 403s on a shop outside the caller's grants.
    Page<Product> findByShopId(UUID shopId, Pageable pageable);

    // WR-04 (issue #280, plan 23-18): single-shop FTS variant. Same UNION/ts_rank logic as
    // searchFullText, narrowed by shop_id so search obeys the shop switcher — without it the
    // switcher would silently stop applying the moment a vendor types two characters, because
    // the screen swaps to this endpoint at searchQuery.length >= 2. No OR-null branch (matching
    // findByShopId above), so RLS + the caller's require() gate carry tenant scoping.
    @Query(value = "SELECT p.* FROM products p WHERE p.shop_id = :shopId AND p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!') "
            + "ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) DESC, p.title ASC, p.id ASC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.shop_id = :shopId AND p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!')",
           nativeQuery = true)
    Page<Product> searchFullTextByShop(@Param("tsQuery") String tsQuery, @Param("skuPrefix") String skuPrefix,
            @Param("shopId") UUID shopId, Pageable pageable);

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

    // Vendor-scoped access (Phase 23, VSA-02 / D-01; WR-08 read half, plan 23-10):
    // grant-set-narrowed variant of searchFullText for a scoped (non-GROUP_ADMIN) caller.
    // Identical FTS+SKU logic, with an EXPLICIT tenant filter plus a parenthesised
    // `(p.shop_id IN (:shopIds) OR p.shop_id IS NULL)` so search results include the caller's
    // granted shops PLUS legacy tenant-wide rows (shop_id IS NULL) of THIS tenant, matching the
    // getAllProducts null-shop read policy — never products of other shops OR other tenants. The
    // tenant_id predicate is explicit (not RLS-only) for the same reason as
    // findTenantScopedInGrantSetOrTenantWide: the OR-null branch is not implicitly tenant-local.
    // Callers guarantee a non-empty shopIds set (a zero-grant user is short-circuited before this).
    @Query(value = "SELECT p.* FROM products p WHERE p.tenant_id = :tenantId "
            + "AND (p.shop_id IN (:shopIds) OR p.shop_id IS NULL) AND p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!') "
            + "ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsQuery)) DESC, p.title ASC, p.id ASC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.tenant_id = :tenantId "
            + "AND (p.shop_id IN (:shopIds) OR p.shop_id IS NULL) AND p.id IN ("
            + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', :tsQuery) "
            + "UNION "
            + "SELECT id FROM products WHERE LOWER(sku) LIKE :skuPrefix ESCAPE '!')",
           nativeQuery = true)
    Page<Product> searchFullTextInGrantSetOrTenantWide(@Param("tenantId") UUID tenantId,
                                        @Param("tsQuery") String tsQuery, @Param("skuPrefix") String skuPrefix,
                                        @Param("shopIds") Collection<UUID> shopIds, Pageable pageable);
}
