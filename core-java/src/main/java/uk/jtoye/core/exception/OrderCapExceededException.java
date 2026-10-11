package uk.jtoye.core.exception;

/**
 * One customer identity already holds the most OPEN cash orders this shop allows (Phase 37-27, D-18,
 * UXT-041). Rendered as a 429 {@code https://jtoye.uk/errors/order-cap-exceeded} with the identity
 * {@code axis} as a typed property, so the checkout shows the copy for that axis and an agent branches
 * without parsing the sentence.
 *
 * <p>429, but deliberately NOT the platform limiter's {@code rate-limited} type and with NO
 * {@code Retry-After}: a cap is not time-based — the slot frees when an open order completes — and
 * {@code lib/api-client.ts} auto-retries only the {@code rate-limited} type (RESEARCH Pitfall 8). Thrown
 * INSIDE the reserved idempotency work on the storefront path, so the reservation rolls back with it and
 * the same key succeeds once an order has completed.
 */
public class OrderCapExceededException extends RuntimeException {

    /** The identity axis that reached its cap. {@code IP} is 37-28's. */
    public enum Axis { EMAIL, PHONE, IP }

    private final Axis axis;
    private final int cap;

    public OrderCapExceededException(Axis axis, int cap) {
        super("This customer has reached the limit of open cash orders (" + axis + "). Try again once one is completed.");
        this.axis = axis;
        this.cap = cap;
    }

    public Axis getAxis() {
        return axis;
    }

    /** The configured maximum for the axis — the number of open cash orders already held. */
    public int getCap() {
        return cap;
    }
}
