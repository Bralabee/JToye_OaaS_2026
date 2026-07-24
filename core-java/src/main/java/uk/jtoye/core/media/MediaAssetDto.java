package uk.jtoye.core.media;

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
        Integer height) {

    /**
     * Map a {@link MediaAsset} entity to the wire DTO. The {@code url}/{@code thumbnailUrl}
     * are resolved by the CALLER (the service, which owns {@code StorageService}) and passed
     * in, so this factory stays a pure, DB-free transform — unit-testable, and honouring the
     * 24-02 convention that DB/storage access never lives in the mapping layer.
     */
    public static MediaAssetDto from(MediaAsset asset, String url, String thumbnailUrl) {
        return new MediaAssetDto(
                asset.getId(),
                MediaAssetStatus.valueOf(asset.getStatus().name()),
                asset.isFlagged(),
                asset.getFailureReason(),
                url,
                thumbnailUrl,
                asset.getWidth(),
                asset.getHeight());
    }
}
