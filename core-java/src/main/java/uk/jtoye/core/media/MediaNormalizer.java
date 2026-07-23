package uk.jtoye.core.media;

import org.springframework.stereotype.Component;

/**
 * Pure-transform stage of the Phase 24 safe upload pipeline (IMG-02). Given the
 * raw quarantine bytes it: sniffs magic bytes (jpeg/png/webp allowlist),
 * header-guards a decompression bomb, decode-verifies, resizes within the
 * config budget, and produces a WebP derivative + thumbnail with EXIF stripped.
 *
 * <p>No DB / no MinIO — it takes bytes and returns bytes, so the async worker
 * (24-04) can call it after pinning the tenant GUC. Real implementation lands in
 * the GREEN step; this skeleton exists so the RED tests compile and fail.
 */
@Component
public class MediaNormalizer {

    private final MediaProperties props;

    public MediaNormalizer(MediaProperties props) {
        this.props = props;
    }

    public NormalizedImage normalize(byte[] raw) {
        throw new UnsupportedOperationException("MediaNormalizer.normalize not yet implemented");
    }

    /**
     * The validated, normalized output: the stored WebP derivative + its WebP
     * thumbnail, plus the derivative's pixel dimensions (for width/height hints
     * that reduce storefront CLS — D-07).
     */
    public record NormalizedImage(byte[] derivativeBytes, byte[] thumbnailBytes, int width, int height) {
    }
}
