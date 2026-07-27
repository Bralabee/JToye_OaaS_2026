package uk.jtoye.core.media;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MediaAsset} (V53 {@code media_asset}).
 *
 * <p>All reads are tenant-scoped by the RLS wall. {@link #findByTenantIdAndSha256}
 * additionally carries an explicit {@code tenantId} predicate and backs the sha256
 * dedup short-circuit (IMG-01): an identical raw upload within a tenant reuses the
 * existing asset rather than storing duplicate bytes (the V53
 * {@code uq_media_asset_tenant_sha} unique index enforces it).
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /** Dedup short-circuit: the existing asset for an identical raw sha256 within a tenant. */
    Optional<MediaAsset> findByTenantIdAndSha256(UUID tenantId, String sha256);

    /**
     * Orphan-reaper query (24-04): {@code PENDING} assets created before
     * {@code cutoff} — quarantined uploads a worker never carried to a terminal
     * state (crashed mid-process). {@link MediaPendingReaper} flips these to
     * {@code FAILED} and deletes their quarantine object. Tenant-scoped by the RLS
     * wall (the reaper pins the tenant GUC per tenant before calling this).
     */
    @Query("SELECT a FROM MediaAsset a WHERE a.status = uk.jtoye.core.media.MediaAsset.Status.PENDING "
            + "AND a.createdAt < :cutoff")
    List<MediaAsset> findStalePending(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * The vendor review/rejection queue selection (IMG-03, 24-05; widened by 27-01 / D-10): the
     * assets that need vendor attention — a {@code FAILED} upload (rejection reason + re-upload),
     * a {@code flagged} {@code ACTIVE} asset (content-relevance review: Keep/Replace), OR a
     * {@code PENDING} asset older than {@code delayCutoff} — newest first. Clean {@code ACTIVE}
     * and freshly-{@code PENDING} assets are excluded (nothing to review yet). Tenant-scoped by
     * the RLS wall (the request thread pins the tenant GUC), so another tenant's rows are invisible.
     *
     * <p>The third disjunct is the D-10 widening. A stalled upload used to be visible ONLY as an
     * inline spinner on the one product page it was uploaded from — so a vendor whose dispatch path
     * was unhealthy had no surface that said so. It now appears in the queue alongside the failures.
     * {@code delayCutoff} is {@code now - jtoye.media.reaper-grace-ms}: the same threshold the
     * reaper uses to call a PENDING row stalled, so the queue and the reaper agree on "stalled".
     *
     * @param delayCutoff PENDING assets created before this instant are stalled and included
     */
    @Query("SELECT a FROM MediaAsset a "
            + "WHERE a.status = uk.jtoye.core.media.MediaAsset.Status.FAILED "
            + "OR (a.status = uk.jtoye.core.media.MediaAsset.Status.ACTIVE AND a.flagged = true) "
            + "OR (a.status = uk.jtoye.core.media.MediaAsset.Status.PENDING AND a.createdAt < :delayCutoff) "
            + "ORDER BY a.createdAt DESC")
    List<MediaAsset> findReviewQueue(@Param("delayCutoff") OffsetDateTime delayCutoff);

    /**
     * The retention sweep's candidate selection (V60, 27-01 / D-03) — quarantine objects whose
     * retained raw bytes may now be reclaimed.
     *
     * <p>Three of the sweep's four guards live here as query clauses:
     * <ol>
     *   <li>{@code status <> ACTIVE} — a live derivative is never a candidate;</li>
     *   <li>{@code quarantineReclaimedAt IS NULL} — <b>the sentinel</b>, the sweep's single
     *       termination condition (without it the legacy arm re-selects the same rows forever);</li>
     *   <li>expiry, in two arms: an explicit {@code quarantineExpiresAt < :now}, <b>or</b> the
     *       legacy arm {@code quarantineExpiresAt IS NULL AND createdAt < :legacyCutoff}, which
     *       collects rows that predate V60 exactly once before they are sentinel-stamped.</li>
     * </ol>
     *
     * <p>The fourth guard — {@code objectKey} must contain {@code /quarantine/} — is deliberately
     * applied in Java by the sweep, NOT here, so the two guards stay independently breakable
     * (D-03). A criterion whose BREAK edits this JPQL string is only falsifiable where the query
     * actually executes, i.e. under Testcontainers; against a mocked repository the stub does the
     * filtering and the edit changes nothing.
     *
     * <p>Tenant-scoped by the RLS wall — the sweep pins the tenant GUC per tenant before calling.
     */
    @Query("SELECT a FROM MediaAsset a "
            + "WHERE a.status <> uk.jtoye.core.media.MediaAsset.Status.ACTIVE "
            + "AND a.quarantineReclaimedAt IS NULL "
            + "AND (a.quarantineExpiresAt < :now "
            + "  OR (a.quarantineExpiresAt IS NULL AND a.createdAt < :legacyCutoff))")
    List<MediaAsset> findReclaimableQuarantine(@Param("now") OffsetDateTime now,
                                               @Param("legacyCutoff") OffsetDateTime legacyCutoff);

    /**
     * The worker's claim lock (27-01 / D-04): {@code SELECT ... FOR UPDATE} on one asset row.
     *
     * <p><b>Depends on READ COMMITTED isolation</b> — Spring's default, and load-bearing here.
     * Under READ COMMITTED a second {@link MediaProcessingWorker} on the same asset <em>blocks</em>
     * until the first commits, then re-reads the COMMITTED row, sees {@code ACTIVE}/{@code FAILED}
     * and takes the existing {@code not_pending} skip. Exactly one worker ever runs the pipeline
     * for one asset. Under REPEATABLE READ the loser would instead abort with a serialization
     * failure, so do not raise the isolation level without revisiting this.
     *
     * <p>The wait is bounded by {@code SET LOCAL lock_timeout} (D-04a), issued on the same pinned
     * connection immediately before this call — Postgres has no "wait N then proceed" for
     * {@code FOR UPDATE}, and {@code jakarta.persistence.lock.timeout} is not honoured as a numeric
     * wait by the Postgres dialect (only NOWAIT / SKIP LOCKED). Do not "simplify" it to a
     * {@code @QueryHint}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM MediaAsset a WHERE a.id = :id")
    Optional<MediaAsset> lockForProcessing(@Param("id") UUID id);
}
