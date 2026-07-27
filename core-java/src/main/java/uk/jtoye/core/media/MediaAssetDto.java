package uk.jtoye.core.media;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The vendor-facing media asset view (IMG-03 / IMG-04): the data contract the
 * dashboard renders per gallery entry AND in the review/rejection queue. It carries
 * the processing {@link #status} ({@code PENDING} -&gt; processing, {@code ACTIVE}
 * -&gt; servable derivative, {@code FAILED} -&gt; rejected), the content-relevance
 * {@link #flagged} bit (an ACTIVE asset awaiting Keep/Replace in the review queue,
 * D-04), the vendor-visible {@link #failureReason} (set only on {@code FAILED}), and
 * the resolved derivative + thumbnail URLs (populated only for an ACTIVE asset — a
 * PENDING/FAILED asset has no servable object).
 *
 * <p>27-01 adds the last two components, both DERIVED (no column of their own):
 * <ul>
 *   <li>{@link #redrivable} — the raw quarantine bytes are still on disk, so
 *       {@code POST /api/v1/media/{assetId}/reprocess} can work. The UI offers
 *       <em>Re-process</em> exactly when this is true, and only <em>Re-upload</em> otherwise.</li>
 *   <li>{@link #delayed} — a {@code PENDING} asset older than the reaper grace, i.e. one that
 *       has visibly stalled (D-10). The UI replaces the indefinite spinner with an explained,
 *       actionable state; the review queue carries the row too, so a stall is not invisible
 *       outside the product page.</li>
 * </ul>
 *
 * <p>24-06 (frontend) consumes this shape: the {@code GET /api/v1/media/review-queue}
 * returns a {@code List<MediaAssetDto>}, and each product DTO gallery entry is one of
 * these (24-05 Task 2 enrichment).
 */
public record MediaAssetDto(
        UUID assetId,
        MediaAssetStatus status,
        boolean flagged,
        String failureReason,
        String url,
        String thumbnailUrl,
        Integer width,
        Integer height,
        boolean redrivable,
        boolean delayed) {

    /**
     * Map a {@link MediaAsset} entity to the wire DTO. The {@code url}/{@code thumbnailUrl}
     * are resolved by the CALLER (the service, which owns {@code StorageService}) and passed
     * in, so this factory stays a pure, DB-free transform — unit-testable, and honouring the
     * 24-02 convention that DB/storage access never lives in the mapping layer.
     *
     * <p>{@code delayCutoff} is passed in for the same reason: it is
     * {@code now - jtoye.media.reaper-grace-ms}, computed ONCE by the caller per request. Keeping
     * the clock out of here means {@link #delayed} is exercised at an exact boundary in a unit
     * test rather than by sleeping.
     *
     * @param delayCutoff a {@code PENDING} asset created before this instant is {@link #delayed}
     */
    public static MediaAssetDto from(MediaAsset asset, String url, String thumbnailUrl,
                                     OffsetDateTime delayCutoff) {
        // Retained iff the bytes were claimed (V60 onwards) and nothing has reclaimed them since —
        // the sentinel's negation. One column pair, three writers (accept, worker, sweep).
        boolean redrivable = asset.getQuarantineExpiresAt() != null
                && asset.getQuarantineReclaimedAt() == null;
        boolean delayed = asset.getStatus() == MediaAsset.Status.PENDING
                && asset.getCreatedAt() != null
                && delayCutoff != null
                && asset.getCreatedAt().isBefore(delayCutoff);
        return new MediaAssetDto(
                asset.getId(),
                MediaAssetStatus.valueOf(asset.getStatus().name()),
                asset.isFlagged(),
                asset.getFailureReason(),
                url,
                thumbnailUrl,
                asset.getWidth(),
                asset.getHeight(),
                redrivable,
                delayed);
    }
}
