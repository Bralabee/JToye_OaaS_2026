package uk.jtoye.core.media.exception;

/**
 * The asset has used its whole manual re-drive budget (T-27-03):
 * {@code media_asset.process_attempts >= jtoye.media.max-process-attempts}. Bounds the work a
 * vendor can push through the pipeline for one permanently-broken upload — without it, a Re-process
 * button on a deterministically-failing asset is an unbounded loop the vendor is invited to run.
 */
public class RedriveBudgetExhaustedException extends MediaRedriveRejectedException {

    public RedriveBudgetExhaustedException(String message) {
        super(message, "media-redrive-budget-exhausted", "media.redrive_budget_exhausted");
    }
}
