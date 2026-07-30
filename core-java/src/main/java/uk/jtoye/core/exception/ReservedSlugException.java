package uk.jtoye.core.exception;

/**
 * Thrown when a vendor-supplied shop slug collides with a STATIC route segment on
 * the storefront, e.g. {@code /shop/signin}.
 *
 * <p>This is a real reachability bug, not a style rule. The storefront routes shops
 * at {@code /shop/[slug]}, and Next.js resolves a static segment BEFORE the dynamic
 * one — so a shop whose slug equals a static segment is permanently unreachable at
 * its own URL, with no error anywhere to say why. {@code /shop/auth} and
 * {@code /shop/orders} have been static since Phase 18; {@code /shop/signin} joins
 * them with the customer sign-in page.
 *
 * <p>Slugs are only at risk when supplied explicitly: {@code ShopService} appends a
 * random 8-character suffix to any slug it generates itself, so a generated slug can
 * never collide. {@code CreateShopRequest.slug} is honoured verbatim when non-blank,
 * which is the path this guards.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity — the request is well-formed, and the
 * value is individually valid, but it cannot be accepted in this position.
 */
public class ReservedSlugException extends RuntimeException {
    public ReservedSlugException(String message) {
        super(message);
    }
}
