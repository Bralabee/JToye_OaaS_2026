package uk.jtoye.core.order;

/**
 * Events that trigger order state transitions.
 * Used by Spring StateMachine to manage order workflow.
 */
public enum OrderEvent {
    /** Submit draft order for processing */
    SUBMIT,

    /** Confirm pending order */
    CONFIRM,

    /** Start preparing confirmed order */
    START_PREP,

    /** Mark order as ready for pickup/delivery */
    MARK_READY,

    /** Complete order (picked up/delivered) */
    COMPLETE,

    /** Cancel order at any stage */
    CANCEL
}
