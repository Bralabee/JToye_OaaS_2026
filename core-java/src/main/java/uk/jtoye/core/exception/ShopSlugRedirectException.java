package uk.jtoye.core.exception;

/**
 * Raised when a public storefront shop lookup matches a historical slug alias in
 * {@code shop_slug_alias} (Phase 39 / Plan 39-09, RWO-028) whose target shop is published.
 *
 * <p>Handled by {@code PublicStorefrontController} to emit an HTTP 301 Moved Permanently
 * response with {@code Location} and {@code X-Shop-Canonical-Slug} headers pointing to
 * {@link #getCanonicalSlug()}.
 */
public class ShopSlugRedirectException extends RuntimeException {

    private final String oldSlug;
    private final String canonicalSlug;

    public ShopSlugRedirectException(String oldSlug, String canonicalSlug) {
        super("Shop slug '" + oldSlug + "' moved permanently to '" + canonicalSlug + "'");
        this.oldSlug = oldSlug;
        this.canonicalSlug = canonicalSlug;
    }

    public String getOldSlug() {
        return oldSlug;
    }

    public String getCanonicalSlug() {
        return canonicalSlug;
    }
}
