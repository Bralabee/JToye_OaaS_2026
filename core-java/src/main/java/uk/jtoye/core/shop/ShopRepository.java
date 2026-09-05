package uk.jtoye.core.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {
    Optional<Shop> findByName(String name);

    // Tenant-scoped reads for the authenticated management plane (QA-council BE-03).
    // The `shops_public_read` RLS policy (V16) permits `published = true`, so a bare
    // findAll()/search() leaks every tenant's PUBLISHED shops into the authenticated
    // "my shops" endpoints. These queries add an explicit tenant filter so the
    // management list/search return only the caller's own shops. The anonymous
    // storefront (/public/shops) keeps using the RLS-only path and is unaffected.
    Page<Shop> findByTenantId(UUID tenantId, Pageable pageable);

    @Query("SELECT s FROM Shop s WHERE s.tenantId = :tenantId AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(s.address) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<Shop> searchByTenant(@Param("tenantId") UUID tenantId, @Param("q") String query);

    // Vendor-scoped access (Phase 23, VSA-02 / D-01): read-scope the authenticated
    // "my shops" list to the caller's GRANT SET, not just the tenant. A non-GROUP_ADMIN
    // sees only the shops they hold a shop_staff grant on — narrowed at the QUERY, never
    // a post-hoc in-memory filter (D-01). GROUP_ADMIN keeps the wider findByTenantId path.
    Page<Shop> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids, Pageable pageable);

    @Query("SELECT s FROM Shop s WHERE s.tenantId = :tenantId AND s.id IN :ids AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(s.address) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<Shop> searchByTenantAndIdIn(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids, @Param("q") String query);

    // Tenant-scoped by-id read for the authenticated management endpoint (BE-03
    // completion): the plain findById is RLS-only and shops_public_read permits
    // published=true, so a tenant could fetch another tenant's PUBLISHED shop by id.
    Optional<Shop> findByIdAndTenantId(UUID id, UUID tenantId);

    // Tenant-scoped by-name read for the Edge sync upsert (PR #726 review M1). The shop
    // upsert key is (tenant, name) — idx_shops_tenant_name is unique per TENANT, not globally —
    // but the bare findByName runs under shops_public_read and so also returns a FOREIGN
    // tenant's PUBLISHED shop of the same name: two rows (IncorrectResultSizeDataAccessException,
    // 500) when the caller has its own, or the foreign row alone (which the SEC-5 gate then
    // refuses) when it does not, so that caller could never sync-create the shop.
    Optional<Shop> findByNameAndTenantId(String name, UUID tenantId);

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

    // ==========================================================================================
    // Distance-ordered public discovery (33-06 / #460 link 5)
    // ==========================================================================================
    //
    // WHY asin AND NOT acos. The spherical law of cosines —
    // acos(sin*sin + cos*cos*cos(dLon)) — is the form usually reached for and it has a real
    // failure mode: for two nearly identical points, floating-point rounding pushes acos's
    // argument to 1.0000000000000002 and PostgreSQL raises "ERROR: input is out of range".
    // The input that triggers it is a customer standing on the shop's own postcode centroid,
    // which any anonymous caller can send — i.e. an unauthenticated 500. The asin haversine
    // below is well-conditioned for small separations and returns 0 for coincident points.
    // If acos is ever reintroduced it MUST be wrapped LEAST(1.0, GREATEST(-1.0, ...)).
    //
    // WHY THE BOUNDING BOX. Measured on the live database: sin, cos, acos and radians are all
    // proleakproof = f, while float8lt/le/ge/gt/eq are proleakproof = t. Under a row-security
    // barrier PostgreSQL will not push a non-leakproof user qual beneath the policy, so a
    // haversine expression can never be an index qual — the same mechanism V44 documents for
    // ts_match_vq. The float8 comparisons CAN sit below the barrier and use the partial
    // shops(latitude, longitude) btree from V61. The box is computed in Java by
    // GeoBounds.boxAround and passed as four more NAMED parameters; it is deliberately larger
    // than the circle (a box slightly too small silently drops shops that really are inside the
    // radius, and that failure has no symptom beyond "the nearest kitchen never appears").
    //
    // WHY THE RADIUS PREDICATE IS ALSO HERE. A box contains its circle, so a shop at the corner
    // of the box is r*sqrt(2) away and must still be excluded. The exact test therefore runs on
    // the survivors of the prefilter, in a derived table so the expression is written ONCE —
    // repeating it in the WHERE clause would let the ordering formula and the filtering formula
    // drift apart.
    //
    // WHY published = true IS IN THE COUNT QUERY TOO. A count that omits it leaks the existence
    // of unpublished shops through totalElements while the page content stays correct
    // (T-33-06-03). That is invisible to any assertion that only inspects content, so
    // PublicStorefrontDistanceIntegrationTest asserts the total separately.
    //
    // 6371.0088 is the IUGG mean Earth radius and is the SAME constant as
    // GeoBounds.EARTH_RADIUS_KM, so the prefilter and the exact test agree about what "5 km"
    // means. The secondary ORDER BY on id makes paging deterministic when two shops are
    // equidistant; without it, page 2 can repeat or skip a row.
    //
    // Every value is a NAMED JPA parameter. Nothing is concatenated into this SQL, and no
    // client-supplied Sort is threaded into it — the endpoint fixes its own ordering (T-33-06-01,
    // T-33-06-02).
    @Query(value = """
            SELECT d.id AS id, d.slug AS slug, d.distance_km AS distance_km
              FROM (
                SELECT s.id AS id,
                       s.slug AS slug,
                       2 * 6371.0088 * asin(sqrt(
                           power(sin(radians(s.latitude - :lat) / 2), 2)
                         + cos(radians(:lat)) * cos(radians(s.latitude))
                         * power(sin(radians(s.longitude - :lon) / 2), 2)
                       )) AS distance_km
                  FROM shops s
                 WHERE s.published = true
                   AND s.latitude IS NOT NULL
                   AND s.longitude IS NOT NULL
                   AND s.latitude  BETWEEN :latMin AND :latMax
                   AND s.longitude BETWEEN :lonMin AND :lonMax
              ) d
             WHERE d.distance_km <= :radiusKm
             ORDER BY d.distance_km ASC, d.id ASC
            """,
           countQuery = """
            SELECT COUNT(*)
              FROM (
                SELECT 2 * 6371.0088 * asin(sqrt(
                           power(sin(radians(s.latitude - :lat) / 2), 2)
                         + cos(radians(:lat)) * cos(radians(s.latitude))
                         * power(sin(radians(s.longitude - :lon) / 2), 2)
                       )) AS distance_km
                  FROM shops s
                 WHERE s.published = true
                   AND s.latitude IS NOT NULL
                   AND s.longitude IS NOT NULL
                   AND s.latitude  BETWEEN :latMin AND :latMax
                   AND s.longitude BETWEEN :lonMin AND :lonMax
              ) d
             WHERE d.distance_km <= :radiusKm
            """,
           nativeQuery = true)
    Page<ShopWithDistance> findPublishedNear(@Param("lat") double latitude,
                                            @Param("lon") double longitude,
                                            @Param("latMin") double minLatitude,
                                            @Param("latMax") double maxLatitude,
                                            @Param("lonMin") double minLongitude,
                                            @Param("lonMax") double maxLongitude,
                                            @Param("radiusKm") double radiusKm,
                                            Pageable pageable);
}
