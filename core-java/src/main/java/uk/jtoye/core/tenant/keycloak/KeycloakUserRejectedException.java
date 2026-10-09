package uk.jtoye.core.tenant.keycloak;

/**
 * Keycloak refused to create a user with a 400 (37-08, D-26): in practice its password policy
 * ({@code {"errorMessage":"Password policy not met"}}, measured on Keycloak 24.0.5 against the
 * {@code jtoye-dev} realm) or a user-profile validation.
 *
 * <p>Carries Keycloak's {@code errorMessage} ONLY ({@link #getKeycloakMessage()}), never the request
 * body: that body held the password (T-37-22). The caller decides what HTTP status it becomes.
 */
public class KeycloakUserRejectedException extends KeycloakAdminException {

    private final String keycloakMessage;

    public KeycloakUserRejectedException(String realm, String keycloakMessage) {
        super("Keycloak refused the new user for realm=" + realm);
        this.keycloakMessage = keycloakMessage;
    }

    /** Keycloak's own {@code errorMessage}, or a generic sentence when it sent none. */
    public String getKeycloakMessage() {
        return keycloakMessage;
    }
}
