package uk.jtoye.core.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Dispatch evidence for the stall sweep (27-01 / D-01): the LATEST outbox row per asset.
     *
     * <p>This is the query that lets {@link MediaPendingReaper} tell "the work was never
     * dispatched" (leave the bytes alone) from "the work was dispatched and stalled" (safe to flip
     * FAILED, still retaining bytes). Reap-eligible iff the latest row is {@code SENT}, or is
     * {@code FAILED AND poison = true}. Everything else — {@code PENDING}, non-poison
     * {@code FAILED}, and <b>no row at all</b> — fails CLOSED and is left untouched.
     *
     * <p>{@code created_at DESC} is <b>load-bearing</b>: the WR-01 reprocess path
     * ({@code MediaAssetService}) and the D-06 re-drive both insert a SECOND row for the same
     * {@code asset_id}, so reading any row but the newest would classify against a stale attempt.
     * Backed by {@code idx_media_event_outbox_asset (asset_id, created_at DESC)} (V60, F-2).
     *
     * <p>One native {@code DISTINCT ON} rather than N+1 per-asset lookups. RLS still applies — the
     * caller pins the tenant GUC.
     *
     * @return rows of {@code (asset_id UUID, status String, poison Boolean)}
     */
    @Query(value = """
            SELECT DISTINCT ON (asset_id) asset_id, status, poison
            FROM media_event_outbox
            WHERE asset_id IN (:assetIds)
            ORDER BY asset_id, created_at DESC
            """, nativeQuery = true)
    List<Object[]> findLatestDispatchStateForAssets(@Param("assetIds") Collection<UUID> assetIds);
}
