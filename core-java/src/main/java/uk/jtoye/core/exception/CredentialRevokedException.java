package uk.jtoye.core.exception;

/**
 * Phase 37-45 (D-22, D-24): the credential has been revoked. Type {@code credential-revoked} in two places:
 * 409 when a Group admin tries to rotate it (a revoked credential cannot be brought back; issue a new one),
 * and 403 when a token the credential minted before it was revoked is still presented
 * ({@code CredentialLastUsedRecorder}), so the refusal does not wait for the token to expire.
 */
public class CredentialRevokedException extends RuntimeException {

    public static final String TYPE = "https://jtoye.uk/errors/credential-revoked";
    public static final String DETAIL = "This credential has been revoked. Issue a new credential instead.";

    public CredentialRevokedException() {
        super(DETAIL);
    }
}
