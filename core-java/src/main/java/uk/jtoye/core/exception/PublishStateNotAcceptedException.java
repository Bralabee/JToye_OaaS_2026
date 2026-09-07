package uk.jtoye.core.exception;

/**
 * Thrown when {@code PUT /shops/{id}} carries a {@code published} value that asks to
 * CHANGE the shop's publish state — an instruction this endpoint is not permitted to
 * carry out.
 *
 * <p>Issue #450 item 4 (QA council {@code disc-20260802-121732}, F-L6-PUBLISHDROP /
 * INT-06). The sole-writer invariant is correct and unchanged: {@code Shop.published}
 * is written only by {@code ShopService.setPublished}, reached only from the guarded
 * onboarding {@code GO_LIVE}/{@code SUSPEND}/{@code REINSTATE} side effects (threat
 * T-18-05-T). What was wrong was the SIGNAL, not the outcome — {@code updateShop}
 * snapshotted {@code published}, let the mapper copy the request value over it, then
 * restored the snapshot and returned {@code 200 OK}. Two distinct inputs
 * ({@code published:true} and {@code published:false}) produced one identical
 * response, so a client could not tell that its instruction had been refused. The
 * council's words: "correct outcome, unfalsifiable response".
 *
 * <p><b>Why only on a CHANGE, not on mere presence.</b> The vendor shop-edit form
 * (`frontend/app/dashboard/shops/page.tsx`) initialises its publish checkbox from the
 * shop and sends {@code published} on EVERY save, so rejecting the field's presence
 * would turn every ordinary shop edit into a hard failure — trading away a working
 * good to add a new one. A request whose {@code published} already equals the shop's
 * current state is a no-op and still succeeds with {@code 200}; only a genuine
 * publish/unpublish instruction is refused.
 *
 * <p>Maps to HTTP 409 Conflict with the stable RFC 7807 type
 * {@code https://jtoye.uk/errors/shop-publish-not-accepted}: the request is
 * well-formed and the value is individually valid, but it conflicts with the
 * resource's state machine, which owns this transition. 409 rather than 422 because
 * the refusal is about WHO may make the transition and WHEN, not about the value
 * being unprocessable — and because the remedy is a different endpoint
 * ({@code POST /onboarding/go-live}), which the {@code detail} names.
 *
 * <p><b>The detail names only remedies a caller can reach (INT-2 / INT-3, QA council
 * 20260902-134741, adjudication A16).</b> It used to add "or the onboarding
 * suspend/reinstate transitions to unpublish". {@code OnboardingEvent.SUSPEND} and
 * {@code REINSTATE} are declared in the state machine and their side effects exist in
 * {@code VendorOnboardingService.transition}, but nothing fires them — no controller, no
 * service method, no UI — and their status is UNRECORDED (#178 closed without a rationale).
 * A LIVE vendor who wanted to stop trading was therefore refused here, sent to a remedy that
 * does not exist, and found no endpoint. There is currently no self-service unpublish; the
 * detail now says so and points at support. When (and if) the endpoints ship they must be
 * named here again; {@code PublishStateNotAcceptedExceptionTest} asserts that every path the
 * detail names exists.
 */
public class PublishStateNotAcceptedException extends RuntimeException {

    private final boolean requestedPublished;
    private final boolean currentPublished;

    public PublishStateNotAcceptedException(boolean requestedPublished, boolean currentPublished) {
        super(("Shop.published is written only by the onboarding state machine and cannot be changed "
                + "through this endpoint. Requested published=%s, shop is currently published=%s; the "
                + "shop's publish state was NOT changed and no other field in this request was applied. "
                + "To publish, use POST /api/v1/onboarding/go-live (APPROVED -> LIVE). There is currently "
                + "no self-service unpublish: taking a live storefront down is handled by J'Toye support "
                + "- contact support. Re-send this update with published=%s (or omit the field) to save "
                + "your other changes.")
                .formatted(requestedPublished, currentPublished, currentPublished));
        this.requestedPublished = requestedPublished;
        this.currentPublished = currentPublished;
    }

    public boolean getRequestedPublished() {
        return requestedPublished;
    }

    public boolean getCurrentPublished() {
        return currentPublished;
    }
}
