package uk.jtoye.core.exception;

/**
 * A storefront guest order arrived without {@code acknowledgedAllergenMask} (Phase 31.1 #784, D-05).
 * The acknowledgement rule is enforced on the SERVER, so a client that skips the checkout panel
 * (or calls the API directly) cannot place an order the customer never acknowledged. Maps to 422
 * {@code https://jtoye.uk/errors/allergen-acknowledgement-required}.
 *
 * <p>Thrown inside the reserved idempotency work, so the reservation rolls back with the
 * transaction and a corrected resubmit under the same key succeeds.
 */
public class AllergenAcknowledgementRequiredException extends RuntimeException {
    public AllergenAcknowledgementRequiredException() {
        super("Confirm you have read the allergen information for this order.");
    }
}
