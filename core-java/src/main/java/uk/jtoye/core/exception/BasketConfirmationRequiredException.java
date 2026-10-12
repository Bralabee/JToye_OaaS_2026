package uk.jtoye.core.exception;

/**
 * A storefront guest order arrived without the prices the customer was shown — a line missing
 * {@code expectedUnitPricePennies} or the body missing {@code expectedTotalPennies} (Phase 37 plan 33,
 * RWO-007 / UXT-007, decision D-16; CONTEXT discretion Q4: the expectation is REQUIRED, like 31.1's
 * allergen acknowledgement, because an optional check is a skippable one). Without it the server
 * cannot tell whether the basket still matches what was shown, so it refuses rather than guesses.
 * Maps to 422 {@code https://jtoye.uk/errors/basket-confirmation-required}, code
 * {@code BASKET_CONFIRMATION_REQUIRED}; the checkout always sends the fields, so a client that reaches
 * this is a cached pre-37-C page, which shows "Refresh the page and try again" (UI-SPEC C1).
 *
 * <p>Refused before any product is read and inside the reserved idempotency work, so the reservation
 * rolls back with the transaction and a corrected resubmit under the same key succeeds.
 */
public class BasketConfirmationRequiredException extends RuntimeException {
    public BasketConfirmationRequiredException() {
        super("Expected prices are missing. Send expectedUnitPricePennies on each item and expectedTotalPennies.");
    }
}
