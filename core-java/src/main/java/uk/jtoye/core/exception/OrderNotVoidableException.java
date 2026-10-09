package uk.jtoye.core.exception;

import uk.jtoye.core.order.OrderStatus;

/**
 * Thrown when a void targets a DRAFT or PENDING order (Phase 37-10, D-10).
 *
 * <p>Those orders are not yet part of the shop's records: nothing was sent to the kitchen and nothing was
 * settled, so they are deleted, not voided. The mirror image of {@link OrderNotDeletableException}.
 *
 * <p>Maps to HTTP 409 via {@code GlobalExceptionHandler.handleOrderNotVoidable} with the stable type
 * {@code https://jtoye.uk/errors/order-not-voidable}, code {@code ORDER_NOT_VOIDABLE}, and the properties
 * {@code orderStatus} and {@code orderNumber}.
 */
public class OrderNotVoidableException extends RuntimeException {

    private final String orderNumber;
    private final OrderStatus status;

    public OrderNotVoidableException(String orderNumber, OrderStatus status) {
        super("Order " + orderNumber + " is " + status.name() + ", so there is nothing to void. "
                + "Delete it instead.");
        this.orderNumber = orderNumber;
        this.status = status;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
