package uk.jtoye.core.exception;

/**
 * Thrown when a customer asks to cancel an order they may not cancel themselves -> HTTP 409
 * (#868, owner ruling 2026-11-04, persona close-out).
 *
 * <p>A customer may cancel only while the order is PENDING, i.e. before the kitchen has accepted it.
 * From then on the answer is this exception and its message tells them to contact the shop. The
 * {@link Reason} is machine-readable so the storefront can word the same fact for each case.
 */
public class CustomerCancelNotAllowedException extends RuntimeException {

    public enum Reason {
        /** The kitchen has accepted the order (or it is further on). */
        ALREADY_ACCEPTED,
        /** Not placed yet (an unpaid card order awaiting its payment). */
        NOT_PLACED,
        /** Paid in parts (deposit and balance), or a payment that has not settled either way. */
        PAYMENT_STATE
    }

    private final String orderNumber;
    private final String orderStatus;
    private final Reason reason;

    public CustomerCancelNotAllowedException(String orderNumber, String orderStatus, Reason reason) {
        super(messageFor(orderNumber, reason));
        this.orderNumber = orderNumber;
        this.orderStatus = orderStatus;
        this.reason = reason;
    }

    private static String messageFor(String orderNumber, Reason reason) {
        return switch (reason) {
            case ALREADY_ACCEPTED -> "Order " + orderNumber
                    + " can't be cancelled online any more: the kitchen has already accepted it. "
                    + "Please contact the shop to change or cancel it.";
            case NOT_PLACED -> "Order " + orderNumber
                    + " hasn't been placed yet, so there is nothing to cancel. If you were charged, please contact the shop.";
            case PAYMENT_STATE -> "Order " + orderNumber
                    + " can't be cancelled online because of how it was paid for. Please contact the shop to cancel it.";
        };
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public Reason getReason() {
        return reason;
    }
}
