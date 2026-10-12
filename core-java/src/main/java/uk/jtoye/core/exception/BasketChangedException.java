package uk.jtoye.core.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * A storefront guest order was submitted against a basket that no longer matches what the customer
 * was shown (Phase 37 plan 33, RWO-007 / UXT-007, decision D-16): a vendor repriced a line, made it
 * unavailable, ran out of it, the shop's minimum is no longer met, or the total differs. The platform
 * never records an order at prices or availability the customer did not see, so the order is refused
 * rather than placed — with ONE typed diff ({@code changes}) the checkout renders ("Suya £9.00 → £14.00")
 * and asks the customer to re-confirm. Maps to 409 {@code https://jtoye.uk/errors/basket-changed},
 * code {@code BASKET_CHANGED}, defined once in {@code GlobalExceptionHandler} and deliberately distinct
 * from {@code idempotency-conflict} and from 31.1's {@code allergen-acknowledgement-stale}, so a client
 * branches on {@code type} alone.
 *
 * <p><b>Composed with 31.1, not beside it.</b> When the allergen set ALSO changed, this problem carries
 * the same four allergen properties the 31.1 stale 409 carries ({@code currentAllergenMask},
 * {@code currentAllergens}, {@code acknowledgedAllergenMask}, {@code lines}), so the client re-renders
 * both panels and asks for one re-confirmation. When ONLY the allergen set changed, this exception is
 * never thrown: {@link AllergenAcknowledgementStaleException} is thrown unchanged, so its contract and
 * its client suites stay as 31.1 built them.
 *
 * <p>The expected prices are compared, never charged: the unit prices on the order are always the
 * server's (goods P2-KYL-P2). A line naming a product that does not exist or belongs to another shop is
 * reported as {@link Kind#REMOVED} with the id ONLY — no name — so the response is indistinguishable
 * from a non-existent id and discloses nothing about another shop's catalogue (goods P2-KYL-P3,
 * T-37-81).
 *
 * <p>Thrown inside the reserved idempotency work, so the reservation rolls back and the corrected
 * resubmit under the SAME key succeeds (T-37-82; goods P2-CHA-12, P2-CHA-13, P2-RAV-20).
 */
public class BasketChangedException extends RuntimeException {

    /** What changed on one line, or on the basket as a whole. */
    public enum Kind {
        /** The line's unit price differs from the one the customer saw: {@code expected} → {@code current}. */
        PRICE_CHANGED,
        /** The product is no longer available to order. */
        UNAVAILABLE,
        /** Fewer units are in stock than the customer asked for: {@code requested} vs {@code available}. */
        SOLD_OUT,
        /** The product is not on this shop's menu (no such product, or another shop's): id only, no name. */
        REMOVED,
        /** The basket met the shop's minimum at the prices shown, and the lines that can still be ordered no longer
         *  reach it: {@code minimum} vs {@code subtotal}. A basket that was always below it is not a change (400). */
        BELOW_MINIMUM,
        /** The order total differs from the one the customer saw: {@code expected} → {@code current}. */
        TOTAL_CHANGED
    }

    /**
     * One entry of the diff. Only the fields that belong to the {@code kind} are set; the rest are
     * {@code null} and left out of the JSON, so a client reads each kind by its own fields and a
     * {@code REMOVED} entry can never carry a name. {@code detail} is the human wording for the entry
     * where one existed before this plan (the shop-minimum sentence), kept verbatim.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BasketChange(Kind kind, UUID productId, String name, Long expected, Long current,
                               Integer requested, Integer available, Long minimum, Long subtotal, String detail) {

        public static BasketChange priceChanged(UUID productId, String name, long expected, long current) {
            return new BasketChange(Kind.PRICE_CHANGED, productId, name, expected, current, null, null, null, null, null);
        }

        public static BasketChange unavailable(UUID productId, String name) {
            return new BasketChange(Kind.UNAVAILABLE, productId, name, null, null, null, null, null, null, null);
        }

        public static BasketChange soldOut(UUID productId, String name, int requested, int available) {
            return new BasketChange(Kind.SOLD_OUT, productId, name, null, null, requested, available, null, null, null);
        }

        /** Id only, by design: the same entry for a non-existent id and for another shop's product. */
        public static BasketChange removed(UUID productId) {
            return new BasketChange(Kind.REMOVED, productId, null, null, null, null, null, null, null, null);
        }

        public static BasketChange belowMinimum(long minimum, long subtotal, String detail) {
            return new BasketChange(Kind.BELOW_MINIMUM, null, null, null, null, null, null, minimum, subtotal, detail);
        }

        public static BasketChange totalChanged(long expected, long current) {
            return new BasketChange(Kind.TOTAL_CHANGED, null, null, expected, current, null, null, null, null, null);
        }
    }

    private final List<BasketChange> changes;
    private final Integer currentAllergenMask;
    private final List<String> currentAllergens;
    private final Integer acknowledgedAllergenMask;
    private final List<AllergenAcknowledgementStaleException.StaleLine> allergenLines;

    /** The diff alone: the allergen set the customer acknowledged is still the basket's declared set. */
    public BasketChangedException(List<BasketChange> changes) {
        this(changes, null, null, null, null);
    }

    /**
     * The diff plus the 31.1 allergen properties, for a submit where the allergen set changed as well.
     * {@code currentAllergenMask} {@code null} means "no allergen change" and leaves the four
     * properties off the problem.
     */
    public BasketChangedException(List<BasketChange> changes, Integer currentAllergenMask,
                                  List<String> currentAllergens, Integer acknowledgedAllergenMask,
                                  List<AllergenAcknowledgementStaleException.StaleLine> allergenLines) {
        super("Your basket has changed since you opened it. Check the changes and confirm before placing the order.");
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("A basket-changed refusal must name at least one change");
        }
        this.changes = List.copyOf(changes);
        this.currentAllergenMask = currentAllergenMask;
        this.currentAllergens = currentAllergens == null ? null : List.copyOf(currentAllergens);
        this.acknowledgedAllergenMask = acknowledgedAllergenMask;
        this.allergenLines = allergenLines == null ? null : List.copyOf(allergenLines);
    }

    public List<BasketChange> getChanges() {
        return changes;
    }

    /** True when the allergen set changed too, so the handler carries the 31.1 properties as well. */
    public boolean hasAllergenChange() {
        return currentAllergenMask != null;
    }

    public Integer getCurrentAllergenMask() {
        return currentAllergenMask;
    }

    public List<String> getCurrentAllergens() {
        return currentAllergens;
    }

    public Integer getAcknowledgedAllergenMask() {
        return acknowledgedAllergenMask;
    }

    public List<AllergenAcknowledgementStaleException.StaleLine> getAllergenLines() {
        return allergenLines;
    }
}
