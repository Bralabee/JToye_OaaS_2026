package uk.jtoye.core.media.exception;

/**
 * Thrown by the media pipeline when an upload's declared dimensions exceed the
 * {@code jtoye.media.max-megapixels} cap. Raised at the ImageIO header read
 * BEFORE any pixel buffer is allocated (RESEARCH Pitfall 2 / threat T-24-01), so
 * a small compressed file that would decode to an enormous raster never reaches
 * {@code ImageIO.read()}. The async worker maps this to {@code status=FAILED} +
 * a vendor-visible {@code failure_reason}.
 */
public class DecompressionBombException extends RuntimeException {
    public DecompressionBombException(String message) {
        super(message);
    }
}
