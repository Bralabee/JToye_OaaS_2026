package uk.jtoye.core.tenant.keycloak;

/**
 * Keycloak answered 409 to a client create (37-44, D-22): a client with the same clientId already exists
 * in the realm. Credential clientIds are generated server-side, so this means a collision or a retried
 * create; the caller generates a new clientId or looks the existing client up. The message names the realm
 * only, never a secret (T-37-106).
 */
public class KeycloakClientExistsException extends KeycloakAdminException {

    public KeycloakClientExistsException(String realm) {
        super("Keycloak client already exists in realm=" + realm);
    }
}
