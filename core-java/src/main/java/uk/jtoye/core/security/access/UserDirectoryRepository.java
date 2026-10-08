package uk.jtoye.core.security.access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the login-populated {@link UserDirectory} (V52
 * {@code user_directory}, D-09).
 *
 * <p>Reads are tenant-scoped by RLS AND an explicit {@code tenantId} predicate
 * (mirrors {@code shop/ShopRepository.findByTenantId}) — the grant-target picker
 * (23-04). The write is the throttled native upsert below, never a per-request
 * {@code save()}.
 */
public interface UserDirectoryRepository extends JpaRepository<UserDirectory, UserDirectoryId> {

    /** All known directory entries for a tenant — the grant-target picker (23-04). */
    List<UserDirectory> findByTenantId(UUID tenantId);

    /**
     * WR-05 (23-12 Task 1): does {@code userId} appear in this tenant's directory? A
     * grant may only target a user who has logged in at least once (D-09 login-populated
     * picker); this enforces the precondition the {@code GrantStaffRequest} javadoc
     * already claimed. Tenant-scoped by RLS AND the explicit {@code tenantId} predicate
     * (mirrors {@code ShopStaffRepository.existsByTenantIdAndUserId}).
     */
    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    /**
     * Throttled login upsert (D-09): records/refreshes a directory row from the
     * authenticated JWT. On first sight it INSERTs; on a returning user it only
     * DOES the UPDATE when the existing row is stale
     * ({@code last_seen < :cutoff}), so a returning user within the window is a
     * no-op — never a write per request. The caller passes
     * {@code cutoff = now - configured interval}. Returns rows affected.
     */
    @Modifying
    @Query(value = "INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
            + "VALUES (:tenantId, :userId, :email, :displayName, now()) "
            + "ON CONFLICT (tenant_id, user_id) DO UPDATE SET "
            + "last_seen = now(), email = EXCLUDED.email, display_name = EXCLUDED.display_name "
            + "WHERE user_directory.last_seen < :cutoff",
            nativeQuery = true)
    int upsertSeen(@Param("tenantId") UUID tenantId,
                   @Param("userId") UUID userId,
                   @Param("email") String email,
                   @Param("displayName") String displayName,
                   @Param("cutoff") OffsetDateTime cutoff);

    /**
     * The request-path sign-in record (37-05, D-09 + Pitfall 13), used by
     * {@link UserDirectoryToucher}: {@link #upsertSeen}'s throttled upsert, plus the V76
     * {@code realm_admin_seen_at} stamp. When {@code realmAdmin} is true the column is set the FIRST
     * time only ({@code COALESCE} keeps an existing stamp), and the throttle is bypassed for exactly
     * that case — a row refreshed within the window but not yet stamped is still stamped — so an
     * admin who first signed in without the role is not left reading "No access" for an hour.
     * A false {@code realmAdmin} never clears a stamp.
     */
    @Modifying
    @Query(value = "INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen, realm_admin_seen_at) "
            + "VALUES (:tenantId, :userId, :email, :displayName, now(), "
            + "CASE WHEN CAST(:realmAdmin AS boolean) THEN now() ELSE NULL END) "
            + "ON CONFLICT (tenant_id, user_id) DO UPDATE SET "
            + "last_seen = now(), email = EXCLUDED.email, display_name = EXCLUDED.display_name, "
            + "realm_admin_seen_at = COALESCE(user_directory.realm_admin_seen_at, EXCLUDED.realm_admin_seen_at) "
            + "WHERE user_directory.last_seen < :cutoff "
            + "OR (user_directory.realm_admin_seen_at IS NULL AND EXCLUDED.realm_admin_seen_at IS NOT NULL)",
            nativeQuery = true)
    int recordSignIn(@Param("tenantId") UUID tenantId,
                     @Param("userId") UUID userId,
                     @Param("email") String email,
                     @Param("displayName") String displayName,
                     @Param("realmAdmin") boolean realmAdmin,
                     @Param("cutoff") OffsetDateTime cutoff);

    /**
     * WR-10 / UK-GDPR Article-17: erase a subject's directory rows for a tenant by email.
     * {@code user_directory} is keyed {@code (tenant_id, user_id)} — a vendor-staff identity
     * space with NO natural {@code Customer} join — so erasure matches on
     * {@code tenant_id + email}, mirroring {@code GdprService}'s guest-order email sweep.
     * There is NO {@code _aud} mirror (D-09 — a derived cache, audit lives on
     * {@code shop_staff}), so a straight tenant-scoped DELETE is the COMPLETE erasure. Zero
     * matches is the NORMAL case (a storefront customer is usually not a staff user) — the
     * caller MUST treat 0 as success, not a failure. The explicit {@code tenant_id}
     * predicate (not RLS alone) keeps it correct even under the SUPERUSER bootstrap role
     * that bypasses FORCE RLS. Returns the number of rows deleted.
     */
    @Modifying
    @Query(value = "DELETE FROM user_directory WHERE tenant_id = :tenantId AND email = :email",
            nativeQuery = true)
    int deleteByTenantIdAndEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);
}
