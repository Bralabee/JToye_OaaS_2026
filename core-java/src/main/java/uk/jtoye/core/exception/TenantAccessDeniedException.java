package uk.jtoye.core.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when a caller's upstream tenant context (populated from a JWT
 * tenant_id claim by JwtTenantFilter) contradicts the tenant derived from
 * a request's resource path slug. Extends Spring Security's
 * AccessDeniedException so GlobalExceptionHandler.handleAccessDenied maps
 * it to a 403 ProblemDetail with zero handler changes. Per ASVS V4.1.5,
 * the response body is the generic "Access denied" string — tenant UUIDs
 * appear only in the SLF4J structured audit log at the rejection site.
 */
public class TenantAccessDeniedException extends AccessDeniedException {
    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
