package uk.jtoye.core.media.exception;

/**
 * The asset's raw quarantine bytes are not available to re-drive (27-01 / D-03). Either they were
 * never claimed ({@code quarantine_expires_at IS NULL} — every pre-V60 row, and every
 * V53-backfilled asset) or they have already been reclaimed
 * ({@code quarantine_reclaimed_at IS NOT NULL} — the retention sweep deleted the object, or the
 * worker discarded it on a validation veto).
 *
 * <p>Both halves are load-bearing: without the second, the endpoint would happily flip a
 * bytes-gone asset back to PENDING and enqueue an event the worker can only fail on a missing
 * object. The vendor's real recourse in this state is a re-upload, so the copy must say so.
 */
public class QuarantineNotRetainedException extends MediaRedriveRejectedException {

    public QuarantineNotRetainedException(String message) {
        super(message, "media-quarantine-not-retained", "media.quarantine_not_retained");
    }
}
