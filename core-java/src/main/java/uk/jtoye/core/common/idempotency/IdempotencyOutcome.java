package uk.jtoye.core.common.idempotency;

/**
 * Immutable carrier returned by {@link IdempotencyService#execute}: the HTTP
 * status the controller should echo plus the (fresh or replayed) response value.
 *
 * <p>Carrying the status lets the controller reproduce the ORIGINAL response
 * status on a replay rather than assuming a fixed code.
 *
 * @param status the HTTP status to echo (fresh create or the stored replay status)
 * @param value  the response DTO (freshly created or deserialized from the store)
 * @param <T>    the response DTO type
 */
public record IdempotencyOutcome<T>(int status, T value) {
}
