package uk.jtoye.core.exception;

/**
 * #833 (UXT-064): {@code DELETE /shops/{id}} was refused because the shop still has live products.
 *
 * <p>{@code products.shop_id} is {@code ON DELETE SET NULL} (V20), so deleting such a shop used to
 * orphan its products: still listed, unreachable by id, and moved to "All Shops" by the next edit.
 * The delete is refused instead, so the vendor moves (copy then delete) or deletes the products first.
 * Archived products (#832) do not count: they are already off every list.
 *
 * <p>409 Conflict, RFC 7807 type {@code https://jtoye.uk/errors/shop-has-products}, with the
 * {@code productCount} as data so a client (the dashboard, an agent) does not parse the sentence.
 */
public class ShopHasProductsException extends RuntimeException {

    private final long productCount;

    public ShopHasProductsException(long productCount) {
        super(("This shop still has %d product%s. Move them to another shop or delete them first, "
                + "then delete the shop.").formatted(productCount, productCount == 1 ? "" : "s"));
        this.productCount = productCount;
    }

    public long getProductCount() {
        return productCount;
    }
}
