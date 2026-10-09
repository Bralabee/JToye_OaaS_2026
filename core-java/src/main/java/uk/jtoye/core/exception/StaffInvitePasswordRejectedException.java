package uk.jtoye.core.exception;

/**
 * Keycloak refused the account the invited person asked for (37-08, D-26), in practice the vendor realm's
 * password policy. Maps to a 422 with the stable type
 * {@code https://jtoye.uk/errors/staff-invite-password-rejected} whose {@code detail} is Keycloak's own
 * message, verbatim (UI-SPEC B2: the page shows no client-side policy that could disagree with the
 * server's). The message is Keycloak's {@code errorMessage} only; it never contains the password.
 * The invitation stays open.
 */
public class StaffInvitePasswordRejectedException extends RuntimeException {

    public StaffInvitePasswordRejectedException(String keycloakMessage) {
        super(keycloakMessage == null || keycloakMessage.isBlank()
                ? "The account service refused this password." : keycloakMessage);
    }
}
