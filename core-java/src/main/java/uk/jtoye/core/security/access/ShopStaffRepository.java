package uk.jtoye.core.security.access;

import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Has this sub already been provisioned in this tenant? — JIT short-circuit (23-02). */
    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    /**
     * Race-safe JIT provision of a tenant-wide GROUP_ADMIN grant (D-04): reserves
     * the row with {@code ON CONFLICT DO NOTHING} against the V52 functional unique
     * index (house reserve idiom, V47/V50), so two concurrent first-requests from
     * the same {@code sub} produce exactly one row. Returns rows affected
     * (1 = provisioned, 0 = already present). {@code shop_id} is NULL (tenant-wide).
     */
    @Modifying
    @Query(value = "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
            + "VALUES (:id, :tenantId, :userId, NULL, 'GROUP_ADMIN', now()) "
            + "ON CONFLICT (tenant_id, user_id, COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid)) "
            + "DO NOTHING",
            nativeQuery = true)
    int insertGroupAdminIfAbsent(@Param("id") UUID id,
                                 @Param("tenantId") UUID tenantId,
                                 @Param("userId") UUID userId);

    /**
     * Race-safe idempotent grant of an arbitrary {@code (user, shop|null, role)}
     * (23-04, VSA-04). Mirrors {@link #insertGroupAdminIfAbsent} exactly but takes
     * a caller-supplied {@code shop_id} + {@code role} + {@code created_by}, so a
     * retried or concurrent duplicate grant is a no-op against the V52 functional
     * unique index {@code uq_shop_staff_tenant_user_shop} rather than an untyped
     * {@code DataIntegrityViolationException} 500 (agent-readiness idempotency
     * contract). Returns rows affected (1 = a new grant, 0 = the grant already
     * existed). {@code role} is bound as text ({@code role.name()}); a NULL
     * {@code shopId} is the tenant-wide (GROUP_ADMIN-shape) grant, cast explicitly
     * so the untyped bind never trips "could not determine data type of parameter".
     */
    @Modifying
    @Query(value = "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, created_by) "
            + "VALUES (:id, :tenantId, :userId, CAST(:shopId AS uuid), CAST(:role AS varchar), now(), :createdBy) "
            + "ON CONFLICT (tenant_id, user_id, COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid)) "
            + "DO NOTHING",
            nativeQuery = true)
    int insertGrantIfAbsent(@Param("id") UUID id,
                            @Param("tenantId") UUID tenantId,
                            @Param("userId") UUID userId,
                            @Param("shopId") UUID shopId,
                            @Param("role") String role,
                            @Param("createdBy") UUID createdBy);
}
