package uk.jtoye.core.order;

/**
 * Why a vendor cancelled an order (Phase 37-27, D-18; written by 37-29's bulk reject). Stored as
 * {@code orders.cancel_reason_code} (V81), whose CHECK lists exactly these four values — add a value
 * here and the constraint must be rewritten in a migration (the V43 lesson), so the set is the one
 * UI-SPEC D1 names and no more.
 *
 * <p>#820: the category (and only the category) now reaches the customer, as {@link #customerText()}.
 *
 * <p>{@code null} on the order means no reason was recorded: every cancellation before V81, and a
 * cancel through a path that gives none. It is never defaulted.
 */
public enum CancelReason {
    /** The vendor believes the order is not genuine (the UXT-041 flood). */
    SUSPECTED_FAKE("The shop could not verify this order."),
    /** The shop cannot make or deliver it (out of stock, out of area, closing). */
    CANNOT_FULFIL("The shop could not make or deliver this order."),
    /** The customer asked for it to be cancelled. */
    CUSTOMER_REQUEST("This order was cancelled at your request."),
    /** Anything else. */
    OTHER("The shop cancelled this order for another reason.");

    private final String customerText;

    CancelReason(String customerText) {
        this.customerText = customerText;
    }

    /**
     * What the CUSTOMER is told (#820, owner ruling 2026-11-04, which relaxes T-37-75 for the category only):
     * the reason CATEGORY in plain English, on the cancellation email and the order/tracking page. Never the
     * enum name, and never anything free-typed. {@link #SUSPECTED_FAKE} reads as "could not verify", not as
     * an accusation: the vendor's suspicion stays the vendor's record.
     */
    public String customerText() {
        return customerText;
    }
}
