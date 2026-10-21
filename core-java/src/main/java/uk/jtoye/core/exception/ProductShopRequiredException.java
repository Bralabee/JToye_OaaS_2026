package uk.jtoye.core.exception;

/**
 * Thrown when a product is created with no shop (issue #959, swarm SW-ADE-01).
 *
 * <p>A product belongs to exactly one shop (UIX-05): the storefront menu, guest checkout, the vendor
 * order route, per-shop stock and the PPDS label's business identity are all keyed on it, so a
 * shop-less product is on sale nowhere and cannot be ordered. The dashboard's old "All Shops" default
 * stored exactly that and said nothing. Maps to HTTP 400 {@code errors/shop-required} via
 * {@code GlobalExceptionHandler}, with a typed {@code field} member ({@code shopId}).
 */
public class ProductShopRequiredException extends RuntimeException {
    public static final String FIELD = "shopId";

    public ProductShopRequiredException() {
        super("Choose a shop for this product. A product with no shop does not appear on any "
                + "shop's menu and cannot be ordered.");
    }
}
