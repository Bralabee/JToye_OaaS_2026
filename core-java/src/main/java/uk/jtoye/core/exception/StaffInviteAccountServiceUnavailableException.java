package uk.jtoye.core.exception;

/**
 * The account service (the Keycloak admin seam) could not be used while previewing or accepting a staff
 * invitation (37-08): it is switched off in this runtime, or Keycloak did not answer. Maps to a 503 with
 * the stable type {@code https://jtoye.uk/errors/staff-invite-account-service-unavailable} and a
 * {@code Retry-After}. Nothing is written and the invitation stays open, so the same link works later.
 *
 * <p>Carries no Keycloak detail: the underlying exception names a realm, which a link holder has no use
 * for.
 */
public class StaffInviteAccountServiceUnavailableException extends RuntimeException {

    public static final String DETAIL = "We couldn't reach the account service. Your invitation is still "
            + "open: try again in a few minutes.";

    public StaffInviteAccountServiceUnavailableException(Throwable cause) {
        super(DETAIL, cause);
    }
}
