package uk.jtoye.core.exception;

/** Thrown when an order awaiting its card or link payment is submitted, accepted or prepared → HTTP 409. */
public class AwaitingPaymentException extends RuntimeException {

    // Its own class, deliberately not a subclass of InvalidStateTransitionException: the transition is legal, the
    // order is just not paid for yet, and no existing invalid-transition catch may swallow it. The message is the
    // 30-UI-SPEC staff sentence that the dashboard, the kitchen and MCP agents show verbatim, so it carries no issue
    // number and no internal reasoning.
    public AwaitingPaymentException(String orderNumber) {
        super("Order " + orderNumber + " is awaiting payment. It can't be submitted, accepted or prepared until the "
                + "customer's payment completes.");
    }
}
