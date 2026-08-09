package uk.jtoye.core.exception;

/**
 * Thrown when the PLATFORM distance-search radius ({@code jtoye.geo.default-radius-km} against
 * {@code jtoye.geo.max-radius-km}) is not a usable query input — a SERVER configuration fault, not
 * a client request-shape error (WR-03, 33-08/09).
 *
 * <p><strong>Why a dedicated type rather than a bare {@link IllegalStateException}.</strong> The
 * generic handler maps {@code IllegalStateException} to a 400 carrying {@code ex.getMessage()},
 * which would blame an anonymous customer for an operator's environment variable and echo internal
 * config-key names back to them. That is the exact mistake {@link MissingTenantContextException}
 * was created to stop repeating, so this follows the same shape: 500, generic detail, specifics to
 * the ERROR log. Extends {@code IllegalStateException} so any existing
 * {@code catch (IllegalStateException)} call site keeps working.
 *
 * <p><strong>Normally unreachable at request time, and deliberately still present.</strong>
 * {@code PublicStorefrontService}'s constructor applies the same check, so a misconfigured radius
 * refuses to start the context rather than waiting for a customer to find it. This exception is
 * the second layer: it keeps the invariant local to the query that depends on it, so a future
 * caller that acquires a radius by some other route cannot reintroduce the silent-empty-page
 * failure WR-03 describes.
 */
public class MisconfiguredPlatformRadiusException extends IllegalStateException {

    public MisconfiguredPlatformRadiusException(String message) {
        super(message);
    }
}
