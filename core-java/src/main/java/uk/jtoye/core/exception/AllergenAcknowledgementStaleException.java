package uk.jtoye.core.exception;

import java.util.List;
import java.util.UUID;

/**
 * A storefront guest order acknowledged an allergen set that is no longer the basket's declared
 * set (Phase 31.1 #785, D-05): typically a vendor edited a product between the checkout render and
 * the submit. The platform never records an acknowledgement of a set the customer was not shown, so
 * the order is refused rather than placed. Maps to a 409 whose problem type is defined once, in
 * {@code GlobalExceptionHandler}, and is deliberately distinct from {@code idempotency-conflict} so
 * a client can branch on {@code type} alone.
 *
 * <p>Carries what the client needs to re-render the panel without another round trip: the current
 * mask, its names in {@code AllergenCatalog} bit order, the acknowledged mask, and the per-line
 * attribution in basket order. The data is the vendor's public declaration, already served by
 * {@code /public/shops/{slug}/products} (T-31.1-09).
 *
 * <p>Only the DECLARED mask is compared. Advisory reconciliation flags are shown at checkout but are
 * not part of the acknowledged set (31.1-03 flagged assumption, RESEARCH Open Question 7).
 *
 * <p>Thrown inside the reserved idempotency work, so the reservation rolls back and the corrected
 * resubmit under the same key succeeds (T-31.1-10).
 */
public class AllergenAcknowledgementStaleException extends RuntimeException {

    /** One basket line's declared allergens, as the server read them at submit. */
    public record StaleLine(UUID productId, String productName, int allergenMask, List<String> allergens) {
        public StaleLine {
            allergens = List.copyOf(allergens);
        }
    }

    private final int currentMask;
    private final List<String> currentAllergens;
    private final int acknowledgedMask;
    private final List<StaleLine> lines;

    public AllergenAcknowledgementStaleException(int currentMask, List<String> currentAllergens,
                                                 int acknowledgedMask, List<StaleLine> lines) {
        super("The allergen information for your basket changed after you read it. "
                + "Read it again and confirm before placing the order.");
        this.currentMask = currentMask;
        this.currentAllergens = List.copyOf(currentAllergens);
        this.acknowledgedMask = acknowledgedMask;
        this.lines = List.copyOf(lines);
    }

    public int getCurrentMask() {
        return currentMask;
    }

    public List<String> getCurrentAllergens() {
        return currentAllergens;
    }

    public int getAcknowledgedMask() {
        return acknowledgedMask;
    }

    public List<StaleLine> getLines() {
        return lines;
    }
}
