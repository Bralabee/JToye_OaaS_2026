package uk.jtoye.core.exception;

/**
 * Phase 37-45 (D-22): the credential is active in core but its Keycloak client no longer exists (it was
 * deleted in Keycloak directly), so its secret cannot be rotated. 409 {@code credential-client-missing}: the
 * credential can no longer mint tokens; delete it and issue a new one.
 */
public class CredentialClientMissingException extends RuntimeException {

    public CredentialClientMissingException() {
        super("The Keycloak client behind this credential no longer exists. Delete the credential and issue a new one.");
    }
}
