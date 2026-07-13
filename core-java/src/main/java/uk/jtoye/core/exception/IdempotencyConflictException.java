package uk.jtoye.core.exception;

/**
 * Thrown when a request arrives with an {@code Idempotency-Key} that matches a
 * reservation whose first request is still IN-FLIGHT (the stored
 * {@code response_status} is NULL). Maps to HTTP 409 Conflict — the honest,
 * race-safe answer for a concurrent same-key request, matching Stripe's
 * concurrent-request behavior. A later retry (once the first request commits)
 * replays the stored response.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
