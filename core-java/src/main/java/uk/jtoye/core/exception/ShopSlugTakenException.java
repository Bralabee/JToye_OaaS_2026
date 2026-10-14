package uk.jtoye.core.exception;

/**
 * Thrown when a shop is given an explicit slug that another shop already holds (Phase 37-48, RWO-028 /
 * UXT-028, owner ruling 2026-10-10). Slugs are the shop's public web address and are unique across the
 * platform ({@code idx_shops_slug}), so the vendor must choose another.
 *
 * <p>Maps to HTTP 409 {@code https://jtoye.uk/errors/shop-slug-taken}, code {@code SHOP_SLUG_TAKEN}, with the
 * refused slug as a typed member, via {@code GlobalExceptionHandler}: never the generic {@code duplicate}
 * type, and never a 500 from the unique index.
 */
public class ShopSlugTakenException extends RuntimeException {

    private final String slug;

    public ShopSlugTakenException(String slug) {
        super("The web address /shop/" + slug + " is already used by another shop. Choose a different one.");
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
