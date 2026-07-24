package uk.jtoye.core.media.exception;

/**
 * Reject-early oversize guard (IMG-02 / T-24-09). Thrown by
 * {@code MediaUploadController.accept} when the declared {@code Content-Length}
 * exceeds {@code jtoye.media.max-upload-bytes} — BEFORE a single {@code MultipartFile}
 * byte is buffered (a 2GB in-memory upload is itself a DoS). Mapped by
 * {@code GlobalExceptionHandler} to an RFC 7807 413 with a stable
 * {@code .../errors/payload-too-large} type (D-06 machine-parseable errors).
 */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
