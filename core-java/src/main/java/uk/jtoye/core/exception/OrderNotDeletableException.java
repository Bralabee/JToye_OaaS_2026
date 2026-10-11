package uk.jtoye.core.exception;

import uk.jtoye.core.order.OrderStatus;

/**
 * Thrown when a delete targets an order that is past PENDING (Phase 37-10, D-10, UXT-018).
 *
 * <p>Only DRAFT and PENDING orders can be deleted. From CONFIRMED on, an order is part of the
 * shop's records (a kitchen ticket, a sale, a ledger row), and {@code financial_transactions.order_id}
 * carries no foreign key, so a delete would leave the ledger pointing at an order that reads 404.
 * Such an order is voided instead.
 *
 * <p>Maps to HTTP 409 via {@code GlobalExceptionHandler.handleOrderNotDeletable} with the stable type
 * {@code https://jtoye.uk/errors/order-not-deletable}, code {@code ORDER_NOT_DELETABLE}, and the
 * properties {@code orderStatus} and {@code orderNumber} ({@code status} stays the HTTP code).
 */
public class OrderNotDeletableException extends RuntimeException {

    private final String orderNumber;
    private final OrderStatus status;

    public OrderNotDeletableException(String orderNumber, OrderStatus status) {
        this(orderNumber, status, "Order " + orderNumber + " is " + status.name() + ", so it can't be deleted. "
                + "Void it instead to keep your records.");
    }

    private OrderNotDeletableException(String orderNumber, OrderStatus status, String message) {
        super(message);
        this.orderNumber = orderNumber;
        this.status = status;
    }

    /**
     * The refusal for an order whose STATUS would allow a delete but whose payment has settled (a card payment
     * captured while the order is still PENDING writes the SALE row at once): the detail names the payment, not
     * the status, because the status is not the reason.
     */
    public static OrderNotDeletableException settled(String orderNumber, OrderStatus status) {
        return new OrderNotDeletableException(orderNumber, status,
                "Order " + orderNumber + " has been paid, so it can't be deleted while it is " + status.name() + ". "
                        + "Void it instead to keep your records.");
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
