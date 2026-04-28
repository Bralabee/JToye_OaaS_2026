package uk.jtoye.core.payment;

/**
 * Lifecycle states for a {@link Refund} row.
 *
 * <p>Lowercase Java enum names match Stripe's webhook wire format 1:1, so
 * {@code RefundStatus.valueOf(stripeStatus)} works without a converter. Per
 * UC-3 LOCKED in Phase 17 CONTEXT.md, the enum is a thin Stripe-API wrapper —
 * Stripe wire format wins over Java PascalCase convention.
 *
 * <p>{@code CREATING} is a pre-Stripe sentinel set when the row is inserted
 * BEFORE the Stripe.Refund.create call (stored-first idempotency). Stripe
 * never returns "CREATING" on the wire so it cannot collide with a real
 * Stripe status.
 */
public enum RefundStatus {
    /** Pre-Stripe sentinel; never seen on wire. */
    CREATING,

    /** Refund completed successfully. */
    succeeded,

    /** Refund failed at Stripe. */
    failed,

    /** Refund pending bank settlement (typical for non-card-present). */
    pending,

    /** Refund needs additional action (rare — e.g. some local payment methods). */
    requires_action,

    /** Refund canceled (only valid in pending state via Connect). */
    canceled
}
