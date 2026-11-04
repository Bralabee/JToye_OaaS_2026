package uk.jtoye.core.exception;

/**
 * Thrown when the card refund for a customer's cancellation could not be made -> HTTP 502 (#868).
 *
 * <p>The order IS cancelled by then (the cancel, the reserved refund and the ledger reversal committed before
 * Stripe was asked, so a refunded order can never be left PENDING for the kitchen to cook), but the card refund
 * has not completed. The customer may repeat the same request to finish it, or contact the shop. The message
 * never carries the payment provider's error text.
 */
public class CustomerCancelRefundFailedException extends RuntimeException {

    private final String orderNumber;

    public CustomerCancelRefundFailedException(String orderNumber, Throwable cause) {
        super("Order " + orderNumber + " has been cancelled, but we couldn't complete the refund to your card yet. "
                + "Please try again in a moment, or contact the shop.", cause);
        this.orderNumber = orderNumber;
    }

    public String getOrderNumber() {
        return orderNumber;
    }
}
