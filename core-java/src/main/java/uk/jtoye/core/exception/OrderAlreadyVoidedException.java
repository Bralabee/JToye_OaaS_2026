package uk.jtoye.core.exception;

import java.time.OffsetDateTime;

/**
 * Thrown when a void with a DIFFERENT reason targets an order that is already voided (Phase 37-10, D-10).
 *
 * <p>A void is idempotent by construction: the same reason again returns the existing void unchanged. A
 * different reason would be a second, contradicting record of why the order was voided, so it is refused
 * and the caller is told who voided it and when.
 *
 * <p>Maps to HTTP 409 via {@code GlobalExceptionHandler.handleOrderAlreadyVoided} with the stable type
 * {@code https://jtoye.uk/errors/order-already-voided}, code {@code ORDER_ALREADY_VOIDED}, and the
 * properties {@code orderNumber}, {@code voidedBy} and {@code voidedAt}.
 */
public class OrderAlreadyVoidedException extends RuntimeException {

    private final String orderNumber;
    private final String voidedBy;
    private final OffsetDateTime voidedAt;

    public OrderAlreadyVoidedException(String orderNumber, String voidedBy, OffsetDateTime voidedAt) {
        super("Order " + orderNumber + " was already voided by " + voidedBy + " at " + voidedAt
                + " with a different reason.");
        this.orderNumber = orderNumber;
        this.voidedBy = voidedBy;
        this.voidedAt = voidedAt;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getVoidedBy() {
        return voidedBy;
    }

    public OffsetDateTime getVoidedAt() {
        return voidedAt;
    }
}
