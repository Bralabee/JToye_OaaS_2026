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
