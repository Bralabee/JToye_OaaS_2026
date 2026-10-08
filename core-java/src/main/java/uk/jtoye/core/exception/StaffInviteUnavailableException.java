package uk.jtoye.core.exception;

/**
 * A staff-invitation link cannot be used (37-08, D-07; UI-SPEC B2 state 4): it has expired, been used,
 * been cancelled, names another business, names a business that is no longer active, or is malformed or
 * unknown. Maps to a 404 with the stable type {@code https://jtoye.uk/errors/staff-invite-unavailable}.
 *
 * <p>Deliberately carries no reason. Every cause answers with one byte-identical body, so a link holder
 * cannot learn which cause applies, or whether a guessed token ever existed (T-37-18).
 */
public class StaffInviteUnavailableException extends RuntimeException {

    public static final String DETAIL = "This invitation can't be used.";

    public StaffInviteUnavailableException() {
        super(DETAIL);
    }
}
