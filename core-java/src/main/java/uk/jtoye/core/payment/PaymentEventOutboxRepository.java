package uk.jtoye.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentEventOutboxRepository extends JpaRepository<PaymentEventOutbox, UUID> {

    /**
     * Claim the next batch of publishable rows for the flusher (Issue #93).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} makes concurrent flusher replicas
     * partition the PENDING set instead of double-publishing it: rows locked
     * by one replica's transaction are silently skipped by every other
     * replica. Locks are held until the claiming transaction commits, at
     * which point the rows are already SENT (or rescheduled) and no longer
     * match the WHERE clause.
     *
     * <p>{@code next_attempt_at <= now()} enforces the exponential-backoff
     * schedule — a row that just failed is invisible until its backoff
     * window elapses, so a broker outage doesn't burn attempts every tick.
     *
     * <p>Native query: RLS on payment_event_outbox still applies (the
     * tenant GUC is set by TenantSetLocalAspect before repository calls),
     * so each claim only sees the current tenant's rows.
     */
    @Query(value = """
            SELECT * FROM payment_event_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= now()
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PaymentEventOutbox> claimPendingBatch(@Param("batchSize") int batchSize);

    /**
     * Resurrection pass (Issue #93): give every retry-exhausted FAILED row a
     * fresh lease. Rows flagged {@code poison} (unrecoverable payloads) are
     * excluded — retrying those can never succeed. Attempts reset to 0 so the
     * backoff ladder restarts small after recovery.
     *
     * <p>{@code clearAutomatically} guards the JPA session-cache trap: any
     * outbox entity already managed in this persistence context would
     * otherwise go stale after the bulk UPDATE.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payment_event_outbox
            SET status = 'PENDING',
                attempts = 0,
                next_attempt_at = now()
            WHERE status = 'FAILED'
              AND poison = FALSE
            """, nativeQuery = true)
    int resurrectFailed();
}
