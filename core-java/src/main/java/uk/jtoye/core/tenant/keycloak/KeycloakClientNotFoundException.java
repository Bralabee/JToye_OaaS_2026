package uk.jtoye.core.tenant.keycloak;

/**
 * Keycloak answered 404 for a credential client (37-44, D-22): the client UUID no longer exists in the
 * realm, typically deleted in the Keycloak console or by a concurrent revoke. A delete treats 404 as the goal
 * state and returns {@code false} instead; every other client call raises this. The message names the realm
 * only, never a secret (T-37-106).
 */
public class KeycloakClientNotFoundException extends KeycloakAdminException {

    public KeycloakClientNotFoundException(String realm) {
        super("Keycloak client not found in realm=" + realm);
    }
}
