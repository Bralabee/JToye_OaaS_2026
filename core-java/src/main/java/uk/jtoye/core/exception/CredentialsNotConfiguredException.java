package uk.jtoye.core.exception;

/**
 * Phase 37-45 (D-22): integrator credentials live in Keycloak, and the Keycloak admin seam
 * ({@code jtoye.keycloak.admin.*}) is not configured, so no credential can be issued, listed, rotated or
 * revoked. 400 {@code credentials-not-configured}, the #102 deprovision precedent; the Developers page reads
 * it and hides creation (37-46).
 */
public class CredentialsNotConfiguredException extends RuntimeException {

    public static final String DETAIL =
            "Integration credentials are not available: the Keycloak admin connection is not configured.";

    public CredentialsNotConfiguredException() {
        super(DETAIL);
    }
}
