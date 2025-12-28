package uk.jtoye.core.order;

/**
 * Order lifecycle states.
 * Maps to order_status enum in database.
 */
public enum OrderStatus {
    /** Order is being created, not yet submitted */
    DRAFT,

    /** Order submitted, awaiting confirmation */
    PENDING,

    /** Order confirmed, ready for preparation */
    CONFIRMED,

    /** Order is being prepared */
    PREPARING,

    /** Order is ready for pickup/delivery */
    READY,

    /** Order has been completed */
    COMPLETED,

    /** Order was cancelled */
    CANCELLED
}
