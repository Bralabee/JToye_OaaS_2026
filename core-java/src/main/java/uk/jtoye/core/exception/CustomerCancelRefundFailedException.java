package uk.jtoye.core.exception;

/**
 * Thrown when the card refund for a customer's cancellation could not be made -> HTTP 502 (#868).
 *
 * <p>The cancel is rolled back with it, so the order is still PENDING and the customer may try again or
 * contact the shop. The message never carries the payment provider's error text.
 */
public class CustomerCancelRefundFailedException extends RuntimeException {

    private final String orderNumber;

    public CustomerCancelRefundFailedException(String orderNumber, Throwable cause) {
        super("We couldn't refund the card for order " + orderNumber + ", so the order has not been cancelled. "
                + "Please try again in a moment, or contact the shop.", cause);
        this.orderNumber = orderNumber;
    }

    public String getOrderNumber() {
        return orderNumber;
    }
}
