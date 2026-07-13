package uk.jtoye.core.exception;

/**
 * Thrown when an {@code Idempotency-Key} is reused with a DIFFERENT request
 * body than the one stored on the original reservation (the stored
 * {@code request_hash} does not match the current request's SHA-256). Maps to
 * HTTP 422 Unprocessable Entity — the request is well-formed but semantically
 * conflicts with the prior use of the same key, so it is neither replayed nor
 * executed afresh.
 */
public class IdempotencyPayloadMismatchException extends RuntimeException {
    public IdempotencyPayloadMismatchException(String message) {
        super(message);
    }
}
