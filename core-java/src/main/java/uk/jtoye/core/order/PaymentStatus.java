package uk.jtoye.core.order;

/**
 * Payment lifecycle states for Stripe integration.
 */
public enum PaymentStatus {
    /** No payment required or initiated */
    NONE,

    /** PaymentIntent created, awaiting customer action */
    PENDING,

    /** Payment authorized but not yet captured */
    AUTHORIZED,

    /** Payment successfully captured */
    CAPTURED,

    /** Payment failed (card declined, insufficient funds, etc.) */
    FAILED,

    /** Payment refunded (full or partial) */
    REFUNDED
}
