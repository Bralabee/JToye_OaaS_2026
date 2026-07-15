package uk.jtoye.core.notification.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped access to {@link NotificationSuppression} rows. All finders are
 * keyed on {@code tenantId} so RLS and the query predicate agree; the write goes
 * through {@link #insertIfAbsent} (the house {@code INSERT ... ON CONFLICT DO
 * NOTHING} idiom) so a one-click unsubscribe is race-safe and idempotent against
 * the {@code UNIQUE (tenant_id, recipient, category)} key.
 */
public interface NotificationSuppressionRepository extends JpaRepository<NotificationSuppression, UUID> {

    boolean existsByTenantIdAndRecipientAndCategory(UUID tenantId, String recipient, NotificationCategory category);

    Optional<NotificationSuppression> findByTenantIdAndRecipientAndCategory(UUID tenantId, String recipient, NotificationCategory category);

    /**
     * Idempotent, race-safe suppression write. Returns the number of rows
     * inserted: {@code 1} for a fresh unsubscribe, {@code 0} when a suppression
     * row for the same {@code (tenant, recipient, category)} already exists (a
     * replayed unsubscribe link). The GUC pinned by {@code TenantSetLocalAspect}
     * plus the RLS {@code WITH CHECK} guarantee the row is written under the
     * caller's own tenant.
     */
    @Modifying
    @Query(value = "INSERT INTO notification_suppression (id, tenant_id, recipient, category) "
            + "VALUES (:id, :tenantId, :recipient, :category) "
            + "ON CONFLICT (tenant_id, recipient, category) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("tenantId") UUID tenantId,
                       @Param("recipient") String recipient,
                       @Param("category") String category);
}
