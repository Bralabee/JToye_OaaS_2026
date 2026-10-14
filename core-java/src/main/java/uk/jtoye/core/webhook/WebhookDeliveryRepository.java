package uk.jtoye.core.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link WebhookDelivery}.
 *
 * <p>RLS bounds every query to the current tenant at the database layer (the
 * worker sets the tenant GUC before each per-tenant claim transaction). The
 * claim query mirrors {@code PaymentEventOutboxRepository.claimPendingBatch}:
 * {@code FOR UPDATE SKIP LOCKED} lets concurrent worker replicas partition the
 * due set instead of double-delivering, and {@code next_attempt_at <= now()}
 * enforces the exponential-backoff schedule.
 */
@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /**
     * Claim the next batch of due deliveries for the current tenant. A row is
     * due when it is still deliverable ({@code PENDING} or {@code RETRYING}) and
     * its backoff window has elapsed. {@code FOR UPDATE SKIP LOCKED} makes N
     * worker replicas claim disjoint sets; the lock is held until the claiming
     * transaction commits (by which point the row is DELIVERED/RETRYING/FAILED
     * and no longer matches).
     */
    @Query(value = """
            SELECT * FROM webhook_delivery
            WHERE status IN ('PENDING', 'RETRYING')
              AND next_attempt_at <= now()
            ORDER BY next_attempt_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDelivery> claimDueBatch(@Param("limit") int limit);

    /**
     * Retention prune (#107): delete this tenant's delivery rows created before
     * {@code cutoff}. Derived-delete finder — runs under the tenant GUC so it
     * only ever removes the current tenant's rows. Scoped to webhook_delivery
     * ONLY; suppression rows are never time-pruned (SPEC AC #13).
     */
    long deleteByCreatedAtBefore(OffsetDateTime cutoff);

    /**
     * Paged delivery log for one subscription with optional status + event-type
     * filters (both {@code null} = unfiltered). Newest first.
     */
    @Query("""
            SELECT d FROM WebhookDelivery d
            WHERE d.subscriptionId = :subscriptionId
              AND (:status IS NULL OR d.status = :status)
              AND (:eventType IS NULL OR d.eventType = :eventType)
            ORDER BY d.createdAt DESC
            """)
    Page<WebhookDelivery> findLog(@Param("subscriptionId") UUID subscriptionId,
                                  @Param("status") WebhookDelivery.Status status,
                                  @Param("eventType") String eventType,
                                  Pageable pageable);

    /** Fetch a delivery scoped to its owning subscription (replay lookup). */
    Optional<WebhookDelivery> findByIdAndSubscriptionId(UUID id, UUID subscriptionId);

    // ---- HELD (UXT-038, 37-43). Each carries an explicit tenant predicate on top of RLS: the
    // Testcontainers bootstrap role bypasses FORCE RLS, and a bulk UPDATE must never reach
    // another tenant's rows even then.

    /**
     * Hold every queued ({@code from}) row of one subscription: status becomes {@code HELD}.
     * {@code flushAutomatically} writes the caller's pending changes first (the delivery that
     * just exhausted its attempts is FAILED in the database before this runs, so it is not held).
     * No {@code clearAutomatically}: the worker still holds managed rows from its claimed batch.
     *
     * @return how many rows are now held by this call
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WebhookDelivery d
               SET d.status = :held, d.updatedAt = :now
             WHERE d.tenantId = :tenantId
               AND d.subscriptionId = :subscriptionId
               AND d.status IN :from
            """)
    int holdQueued(@Param("tenantId") UUID tenantId,
                   @Param("subscriptionId") UUID subscriptionId,
                   @Param("from") Collection<WebhookDelivery.Status> from,
                   @Param("held") WebhookDelivery.Status held,
                   @Param("now") OffsetDateTime now);

    /** Resume: every HELD row of the subscription back to PENDING, due now, with a fresh attempt budget. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WebhookDelivery d
               SET d.status = :pending, d.attemptCount = 0, d.nextAttemptAt = :now, d.updatedAt = :now
             WHERE d.tenantId = :tenantId
               AND d.subscriptionId = :subscriptionId
               AND d.status = :held
            """)
    int requeueHeld(@Param("tenantId") UUID tenantId,
                    @Param("subscriptionId") UUID subscriptionId,
                    @Param("held") WebhookDelivery.Status held,
                    @Param("pending") WebhookDelivery.Status pending,
                    @Param("now") OffsetDateTime now);

    /** Revoke (terminal): a held row can never be delivered, so it is FAILED with the reason. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WebhookDelivery d
               SET d.status = :failed, d.lastError = :reason, d.updatedAt = :now
             WHERE d.tenantId = :tenantId
               AND d.subscriptionId = :subscriptionId
               AND d.status = :held
            """)
    int failHeld(@Param("tenantId") UUID tenantId,
                 @Param("subscriptionId") UUID subscriptionId,
                 @Param("held") WebhookDelivery.Status held,
                 @Param("failed") WebhookDelivery.Status failed,
                 @Param("reason") String reason,
                 @Param("now") OffsetDateTime now);

    long countByTenantIdAndSubscriptionIdAndStatus(UUID tenantId, UUID subscriptionId, WebhookDelivery.Status status);

    /** One grouped count for the subscription list (rows: subscriptionId, count), never one query per row. */
    @Query("""
            SELECT d.subscriptionId, count(d) FROM WebhookDelivery d
             WHERE d.tenantId = :tenantId AND d.status = :status
             GROUP BY d.subscriptionId
            """)
    List<Object[]> countByStatusPerSubscription(@Param("tenantId") UUID tenantId,
                                                @Param("status") WebhookDelivery.Status status);
}
