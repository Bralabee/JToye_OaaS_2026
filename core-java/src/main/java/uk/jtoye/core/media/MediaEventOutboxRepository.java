package uk.jtoye.core.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link MediaEventOutbox} (V58). Claim + resurrection queries are
 * copied verbatim from {@code payment/PaymentEventOutboxRepository} (table renamed)
 * so the same hardened multi-replica semantics apply.
 */
@Repository
public interface MediaEventOutboxRepository extends JpaRepository<MediaEventOutbox, UUID> {

    /**
     * Claim the next batch of publishable rows for the flusher.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} makes concurrent flusher replicas partition
     * the PENDING set instead of double-publishing it. {@code next_attempt_at <= now()}
     * enforces the exponential-backoff schedule. RLS on media_event_outbox still
     * applies — the tenant GUC is pinned by the flusher before each claim, so each
     * claim only sees the current tenant's rows.
     */
    @Query(value = """
            SELECT * FROM media_event_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= now()
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MediaEventOutbox> claimPendingBatch(@Param("batchSize") int batchSize);

    /**
     * Resurrection pass: give every retry-exhausted non-poison FAILED row a fresh
     * lease so events always drain once the broker recovers. Poison rows (payload
     * corruption) are excluded — retrying those can never succeed.
     *
     * <p>{@code clearAutomatically} guards the JPA session-cache trap after a bulk
     * UPDATE.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE media_event_outbox
            SET status = 'PENDING',
                attempts = 0,
                next_attempt_at = now()
            WHERE status = 'FAILED'
              AND poison = FALSE
            """, nativeQuery = true)
    int resurrectFailed();
}
