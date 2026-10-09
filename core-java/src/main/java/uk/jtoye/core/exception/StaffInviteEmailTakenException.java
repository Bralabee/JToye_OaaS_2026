package uk.jtoye.core.exception;

/**
 * The invited address already has a J'Toye account that belongs to another business, or to none (37-08,
 * D-07, T-37-20). One user, one tenant: {@code tenant_id} is single-valued, so accepting would either
 * re-home someone else's account or leave the new grant unusable. Maps to a 409 with the stable type
 * {@code https://jtoye.uk/errors/staff-invite-email-in-other-business}; nothing is written.
 */
public class StaffInviteEmailTakenException extends RuntimeException {

    public static final String DETAIL = "This email already belongs to another business. Each J'Toye account "
            + "works with one business.";

    public StaffInviteEmailTakenException() {
        super(DETAIL);
    }
}
