package uk.jtoye.core.tenant.keycloak;

/**
 * Keycloak answered 409 to a user create (37-08): a user with the same username or email already
 * exists in the realm, typically created a moment earlier by a concurrent accept of another
 * invitation to the same address. The caller looks the user up again instead of failing.
 */
public class KeycloakUserExistsException extends KeycloakAdminException {

    public KeycloakUserExistsException(String realm) {
        super("Keycloak user already exists in realm=" + realm);
    }
}
