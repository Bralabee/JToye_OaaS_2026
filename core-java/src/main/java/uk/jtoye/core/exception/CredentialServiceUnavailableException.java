package uk.jtoye.core.exception;

/**
 * Phase 37-45 (D-22): Keycloak did not answer, or refused, a credential operation. 503
 * {@code credential-service-unavailable} with Retry-After; nothing was written (the request's transaction
 * rolls back, and a client Keycloak had already created is deleted again). The cause is the
 * {@code KeycloakAdminException}, whose message names the realm only, never a secret (T-37-106).
 */
public class CredentialServiceUnavailableException extends RuntimeException {

    public static final String DETAIL =
            "The account service did not answer. Nothing was changed; try again in a minute.";

    public CredentialServiceUnavailableException(Throwable cause) {
        super(DETAIL, cause);
    }
}
