package uk.jtoye.core.common.idempotency;

import org.springframework.http.ResponseEntity;

/**
 * Immutable carrier returned by {@link IdempotencyService#execute}: the HTTP
 * status the controller should echo plus the (fresh or replayed) response value.
 *
 * <p>Carrying the status lets the controller reproduce the ORIGINAL response
 * status on a replay rather than assuming a fixed code.
 *
 * <p>{@code replayed} (#879 / UXT-123, P2-RAV-17): true when the value came from the store rather than from
 * running the work. A replay returns the original status and body, so without this an integrator cannot tell
 * "created now" from "returned from an earlier call"; {@link #toResponse()} turns it into the
 * {@value #REPLAYED_HEADER} response header (absent on a fresh create).
 *
 * @param status   the HTTP status to echo (fresh create or the stored replay status)
 * @param value    the response DTO (freshly created or deserialized from the store)
 * @param replayed whether the value was replayed from an earlier request with the same key
 * @param <T>      the response DTO type
 */
public record IdempotencyOutcome<T>(int status, T value, boolean replayed) {

    /** The response header that marks a replay. Exposed to browsers via {@code cors.exposed-headers}. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** A fresh (not replayed) outcome. */
    public IdempotencyOutcome(int status, T value) {
        this(status, value, false);
    }

    /** The status and body, plus {@code Idempotent-Replayed: true} when this is a replay. */
    public ResponseEntity<T> toResponse() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(value);
    }
}
