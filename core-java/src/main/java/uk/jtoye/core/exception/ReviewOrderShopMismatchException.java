package uk.jtoye.core.exception;

/**
 * A review cited an order that was not placed at the shop being reviewed (Phase 37-26, D-21,
 * RWO-005 / UXT-005 P0). Rendered as a 400 {@code https://jtoye.uk/errors/review-order-shop-mismatch}
 * with code {@code REVIEW_ORDER_SHOP_MISMATCH}.
 *
 * <p>Until this existed {@code ReviewService.createReview} checked that the order belonged to the
 * reviewer, was COMPLETED and had not been reviewed — but never that it belonged to the SHOP in the
 * URL. Within one tenant every shop's orders are visible to the service (RLS is the tenant wall, not
 * the shop wall), so a customer who had bought once from shop A could leave a "verified" review on
 * shop B of the same tenant. The order is a proof of purchase FROM THE SHOP REVIEWED, or it is no
 * proof at all.
 */
public class ReviewOrderShopMismatchException extends RuntimeException {

    public static final String DETAIL = "This order was not placed at this shop, so it can't be used to review it.";

    public ReviewOrderShopMismatchException() {
        super(DETAIL);
    }
}
