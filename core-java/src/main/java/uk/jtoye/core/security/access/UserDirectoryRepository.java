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
}
