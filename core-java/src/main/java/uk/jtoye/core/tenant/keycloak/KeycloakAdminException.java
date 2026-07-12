package uk.jtoye.core.tenant.keycloak;

/**
 * Raised by {@link KeycloakAdminClient} when a Keycloak admin REST call fails
 * (non-2xx, connection error, malformed response). Carries realm/operation
 * context — but NEVER the bearer token or admin password (STRIDE T-kc-01).
 *
 * <p>This is a low-level seam exception: it is deliberately NOT mapped to any
 * HTTP status. {@code KeycloakDeprovisionService} catches it and applies the
 * best-effort, non-rolling-back contract (marker stays NULL, ERROR logged); the
 * client itself never decides on availability behaviour.
 */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
