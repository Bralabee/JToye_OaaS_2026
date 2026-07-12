package uk.jtoye.core.exception;

/**
 * Thrown when a tenant-scoped operation runs without an established
 * {@code TenantContext} — a SERVER-side security-configuration fault (the
 * JWT/tenant filter chain failed to map a tenant), not a client request-shape
 * error. Maps to HTTP 500 per the documented convention (IN-08): the generic
 * {@code IllegalStateException} handler returns 400, which misreported this
 * misconfiguration as the caller's fault. Extends {@link IllegalStateException}
 * so existing {@code catch (IllegalStateException)} call sites keep working.
 */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
