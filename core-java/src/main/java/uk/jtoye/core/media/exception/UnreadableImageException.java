package uk.jtoye.core.media.exception;

/**
 * Thrown by the media pipeline when an upload is not an allowlisted image: its
 * magic bytes are not jpeg/png/webp (a content-type spoof — threat T-24-02), no
 * ImageIO reader claims it, or the decode-to-verify step fails. The async worker
 * maps this to {@code status=FAILED} + a vendor-visible {@code failure_reason}.
 */
public class UnreadableImageException extends RuntimeException {
    public UnreadableImageException(String message) {
        super(message);
    }

    public UnreadableImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
