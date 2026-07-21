package uk.jtoye.core.security.access;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link ShopStaff} grants (V52 {@code shop_staff}).
 *
 * <p>All reads are tenant-scoped by the RLS wall AND by an explicit
 * {@code tenantId} predicate (mirrors {@code shop/ShopRepository.findByTenantId}):
 * membership resolution (23-02 cache source), the last-GROUP_ADMIN guard (23-04),
 * and the race-safe JIT provision insert (23-02).
 */
public interface ShopStaffRepository extends JpaRepository<ShopStaff, UUID> {

    /** All grants for a user within a tenant — membership resolution (23-02 cache source). */
    List<ShopStaff> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    /** All grants in a tenant — the staff-management list view (23-04). */
    List<ShopStaff> findByTenantId(UUID tenantId);

    /** Count of grants with a given role in a tenant — last-GROUP_ADMIN guard (D-11, 23-04). */
    long countByTenantIdAndRole(UUID tenantId, ShopRole role);

    /**
     * Pessimistically lock ALL of a tenant's tenant-wide ({@code shop_id IS NULL})
     * GROUP_ADMIN rows for the duration of the current transaction (CR-06, 23-09).
     * Called at the TOP of the last-GROUP_ADMIN guard in both {@code revoke()} and the
     * {@code grant()} downgrade path — BEFORE the {@code countByTenantIdAndRole} — so the
     * whole check-then-act is serialized: a concurrent revoke/downgrade blocks on this
     * {@code SELECT ... FOR UPDATE} until the first transaction commits, then re-reads the
     * true post-commit count and correctly 409s. This prevents two concurrent writes from
     * racing the tenant to ZERO GROUP_ADMINs (a permanent lockout under strict-scoping ON).
     *
     * <p>The row set is exactly the invariant's set: {@code grant()} rejects shop-scoped
     * GROUP_ADMIN grants, so these {@code shop_id IS NULL} rows and
     * {@code countByTenantIdAndRole(tenantId, GROUP_ADMIN)} agree. {@code ORDER BY s.id}
     * gives both racing transactions a deterministic lock-acquisition order (no deadlock).
     * The predicate stays tenant-scoped (explicit {@code tenantId} + the FORCE-RLS wall),
     * so a lock taken by tenant A cannot be observed or blocked by tenant B.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ShopStaff s WHERE s.tenantId = :tenantId AND s.shopId IS NULL "
            + "AND s.role = uk.jtoye.core.security.access.ShopRole.GROUP_ADMIN ORDER BY s.id")
    List<ShopStaff> lockTenantGroupAdmins(@Param("tenantId") UUID tenantId);

    /** Has this sub already been provisioned in this tenant? — JIT short-circuit (23-02). */
    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    /**
     * Does the tenant hold ANY tenant-wide ({@code shop_id IS NULL}) GROUP_ADMIN grant of the
     * given provenance? Used by the strict-scoping bootstrap rule (CR-07, 23-14): if an
     * {@link GrantSource#OPERATOR} tenant-wide GROUP_ADMIN exists, the tenant is not at risk of
     * lockout when JIT grants are de-honoured, so no JIT bootstrap admin is retained. Tenant-scoped
     * by the RLS wall AND the explicit {@code tenantId} predicate.
     */
    @Query("SELECT (count(s) > 0) FROM ShopStaff s WHERE s.tenantId = :tenantId AND s.shopId IS NULL "
            + "AND s.role = uk.jtoye.core.security.access.ShopRole.GROUP_ADMIN AND s.grantSource = :source")
    boolean existsTenantWideGroupAdminBySource(@Param("tenantId") UUID tenantId,
                                               @Param("source") GrantSource source);

    /**
     * The tenant's JIT-sourced tenant-wide ({@code shop_id IS NULL}) GROUP_ADMIN grants, OLDEST
     * FIRST ({@code created_at} ascending, tie-broken by {@code id}). The head is the deterministic
     * bootstrap admin retained under strict-scoping ON so de-honouring JIT grants can never leave a
     * tenant with zero GROUP_ADMINs (CR-07 lockout safety, 23-14). Tenant-scoped by the RLS wall AND
     * the explicit {@code tenantId} predicate.
     */
    @Query("SELECT s FROM ShopStaff s WHERE s.tenantId = :tenantId AND s.shopId IS NULL "
            + "AND s.role = uk.jtoye.core.security.access.ShopRole.GROUP_ADMIN "
            + "AND s.grantSource = uk.jtoye.core.security.access.GrantSource.JIT "
            + "ORDER BY s.createdAt ASC, s.id ASC")
    List<ShopStaff> findTenantWideJitGroupAdminsOldestFirst(@Param("tenantId") UUID tenantId);

    /**
     * Race-safe JIT provision of a tenant-wide GROUP_ADMIN grant (D-04): reserves
     * the row with {@code ON CONFLICT DO NOTHING} against the V52 functional unique
     * index (house reserve idiom, V47/V50), so two concurrent first-requests from
     * the same {@code sub} produce exactly one row. Returns rows affected
     * (1 = provisioned, 0 = already present). {@code shop_id} is NULL (tenant-wide)
     * and {@code grant_source} is {@code 'JIT'} (V57) — so the strict-scoping switch
     * can distinguish this auto-provisioned row from a deliberate operator grant (CR-07).
     */
    @Modifying
    @Query(value = "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
            + "VALUES (:id, :tenantId, :userId, NULL, 'GROUP_ADMIN', 'JIT', now()) "
            + "ON CONFLICT (tenant_id, user_id, COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid)) "
            + "DO NOTHING",
            nativeQuery = true)
    int insertGroupAdminIfAbsent(@Param("id") UUID id,
                                 @Param("tenantId") UUID tenantId,
                                 @Param("userId") UUID userId);
}
