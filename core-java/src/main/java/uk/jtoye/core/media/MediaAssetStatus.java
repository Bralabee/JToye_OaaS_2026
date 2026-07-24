package uk.jtoye.core.media;

/**
 * The public, API-facing processing state of a {@link MediaAsset} (IMG-04) — the
 * enum the product/media DTOs and the vendor review queue expose so the 24-06
 * frontend can render each gallery entry: {@code PENDING} -&gt; a processing
 * spinner, {@code ACTIVE} -&gt; the servable WebP derivative, {@code FAILED} -&gt;
 * the vendor-visible rejection reason + re-upload.
 *
 * <p>Deliberately a DISTINCT type from the {@link MediaAsset.Status} persistence
 * enum so the wire contract is decoupled from the entity; the two are
 * name-identical and mapped in {@link MediaAssetDto#from} via {@code valueOf(name())}.
 */
public enum MediaAssetStatus {
    PENDING,
    ACTIVE,
    FAILED
}
