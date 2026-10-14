package uk.jtoye.core.exception;

/**
 * Phase 37-45 (Q7, T-37-107): a staff grant named an integrator credential's service-account user; since 37-46
 * also a staff revoke of such a user's grant, which would leave the credential ACTIVE with no access. A
 * credential's access is what it was issued with (D-23: one role, one shop or all shops, never GROUP_ADMIN),
 * and the Staff API must not widen it: once the credential has called the API its service account sits in the
 * staff directory like a person, so without this check a Group admin could make it a Group admin. 409
 * {@code staff-target-is-credential}; to change a credential's access, revoke it and issue a new one.
 */
public class StaffTargetIsCredentialException extends RuntimeException {

    public static final String DETAIL = "This is an integration credential, not a person. Its access is set when it "
            + "is issued: revoke it and issue a new credential to change it.";

    public StaffTargetIsCredentialException() {
        super(DETAIL);
    }
}
