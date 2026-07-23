package uk.jtoye.core.exception;

import uk.jtoye.core.security.access.ShopRole;

import java.util.UUID;

/**
 * Thrown by {@code ShopAccessService.require(shopId, minRole)} when the
 * authenticated caller lacks the required shop role on {@code shopId} (Phase 23,
 * VSA-02 / D-01 / D-13).
 *
 * <p><strong>Deliberately extends {@link RuntimeException}, NOT Spring Security's
 * {@code AccessDeniedException}.</strong> The generic
 * {@code GlobalExceptionHandler.handleAccessDenied} maps every
 * {@code AccessDeniedException} to the shared {@code .../errors/forbidden} 403.
 * The shop-scope 403 must be provably DISTINCT — a dedicated
 * {@code @ExceptionHandler(ShopAccessDeniedException.class)} returns the stable
 * type {@code https://jtoye.uk/errors/shop-access-denied}, separate from both the
 * RLS 404 ({@code .../not-found}) and the generic 403 ({@code .../forbidden}), so
 * the frontend D-13 access-required state can key on it and the tenant-boundary
 * signal (RLS 404) is never blurred with the in-tenant shop gate (SPEC §D-01).
 *
 * <p>Carries machine-parseable context — {@link #getShopId()} (nullable for a
 * GROUP_ADMIN-only action such as shop create that has no {@code shopId}) and
 * {@link #getRequiredRole()} — surfaced as RFC 7807 problem properties for the
 * AI-agent-readiness contract (typed/stable codes).
 */
public class ShopAccessDeniedException extends RuntimeException {

    /** The shop the caller was denied on; {@code null} for a GROUP_ADMIN-only action with no shop. */
    private final transient UUID shopId;

    /** The minimum role the action required. */
    private final transient ShopRole requiredRole;

    public ShopAccessDeniedException(UUID shopId, ShopRole requiredRole) {
        super("Shop access denied: requires " + requiredRole
                + (shopId != null ? " on shop " + shopId : " (group-wide)"));
        this.shopId = shopId;
        this.requiredRole = requiredRole;
    }

    public UUID getShopId() {
        return shopId;
    }

    public ShopRole getRequiredRole() {
        return requiredRole;
    }
}
