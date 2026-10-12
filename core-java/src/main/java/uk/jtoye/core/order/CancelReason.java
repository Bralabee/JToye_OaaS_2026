package uk.jtoye.core.order;

/**
 * Why a vendor cancelled an order (Phase 37-27, D-18; written by 37-29's bulk reject). Stored as
 * {@code orders.cancel_reason_code} (V81), whose CHECK lists exactly these four values — add a value
 * here and the constraint must be rewritten in a migration (the V43 lesson), so the set is the one
 * UI-SPEC D1 names and no more.
 *
 * <p>{@code null} on the order means no reason was recorded: every cancellation before V81, and a
 * cancel through a path that gives none. It is never defaulted.
 */
public enum CancelReason {
    /** The vendor believes the order is not genuine (the UXT-041 flood). */
    SUSPECTED_FAKE,
    /** The shop cannot make or deliver it (out of stock, out of area, closing). */
    CANNOT_FULFIL,
    /** The customer asked for it to be cancelled. */
    CUSTOMER_REQUEST,
    /** Anything else. */
    OTHER
}
