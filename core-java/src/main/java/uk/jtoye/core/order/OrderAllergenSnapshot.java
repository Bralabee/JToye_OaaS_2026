package uk.jtoye.core.order;

import uk.jtoye.core.order.dto.OrderAllergenFlagDto;
import uk.jtoye.core.product.AllergenCatalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The write side and the read side of the order-line allergen snapshot (LGL-03, V63).
 *
 * <h2>Why a snapshot and not a live join</h2>
 *
 * <p>Aggregating an order's allergens by joining back to {@code products} at read time means a
 * vendor who edits an allergen mask AFTER the order is placed silently changes what the customer
 * is recorded as having acknowledged and what the kitchen ticket shows. The customer acknowledged
 * set A, the kitchen sees set B, and no record of A exists anywhere. {@code OrderItem} already
 * snapshots {@code productName} for exactly this class of drift; the mask is snapshotted beside
 * it, at the same moment, for the same reason.
 *
 * <h2>Why this class exists rather than more code in the services</h2>
 *
 * <p>There are TWO order write paths — {@code PublicStorefrontService.createGuestOrder} (the
 * storefront) and {@code OrderService.createOrder} (the vendor / API / MCP path) — and two read
 * consumers, the checkout panel (UI-SPEC S3) and the kitchen display (UI-SPEC S4). Putting the
 * capture and the view in one place is what makes "the same answer everywhere" provable rather
 * than merely intended. {@link OrderAllergenAggregator} stays pure and entity-free, exactly as
 * plan 31-04 built it; this class is the thin seam between it and JPA.
 *
 * <h2>NULL is not zero, and the difference is legal</h2>
 *
 * <p>{@code null} means NOT RECORDED — the line predates V63, and no backfill invented a mask for
 * it, because fabricating a record of what a past customer was shown would be a worse defect than
 * the one V63 fixes. {@code 0} means the vendor DECLARED NONE of the 14 regulated allergens. The
 * checkout copy for the second is legally specific ("that is not the same as allergen-free"), so
 * the two must stay distinguishable all the way to the wire.
 */
public final class OrderAllergenSnapshot {

    /**
     * Separator for the (product name, allergen bit) dedup key. A NUL byte cannot occur inside a
     * value read out of a PostgreSQL text column, so no product title can forge a key that
     * collides with a different pair. A printable separator could not make that claim — a title
     * ending in a digit would be enough to muddy it.
     */
    private static final String KEY_SEPARATOR = "\0";

    private OrderAllergenSnapshot() {
    }

    /**
     * An order's allergen picture as the DTO layer exposes it.
     *
     * <p>Every field is {@code null} together when the order is NOT RECORDED, and non-null
     * together otherwise — so a consumer can branch on any one of them and cannot accidentally
     * read "not recorded" as "nothing declared".
     */
    public record OrderAllergenView(Integer declaredMask,
                                    List<String> declaredNames,
                                    List<OrderAllergenFlagDto> flags) {

        /** The order carries no snapshot: it predates V63, or it has no lines at all. */
        static final OrderAllergenView NOT_RECORDED = new OrderAllergenView(null, null, null);
    }

    // ==============================================================================
    // Write side
    // ==============================================================================

    /**
     * Capture this line's allergen snapshot from the product it was priced against.
     *
     * <p>Called from the item loop of every order write path, beside the existing
     * {@code productName} snapshot.
     *
     * <p><strong>Why the aggregator is asked about ONE line at a time.</strong>
     * {@link OrderAllergenAggregator#aggregate(Collection)} flattens its reconciliation flags into
     * an order-level list keyed by product NAME. Attributing that flattened list back to
     * individual lines by name would mis-attribute whenever two lines share a product name (the
     * same product ordered twice, or two products with the same title), and a flag pointing at the
     * wrong item on a kitchen ticket is a safety defect, not a cosmetic one. Asking the SAME
     * shared function for one line's answer keeps the attribution exact while keeping a single
     * implementation of the reconciliation rules. The order-level union is then rebuilt from the
     * stored lines by {@link #viewOf(Collection)}, so the checkout and the kitchen display are
     * reading one set of numbers, not two computations.
     *
     * @param item            the order line being built (mutated)
     * @param productName     the product title, already snapshotted onto the line
     * @param declaredMask    {@code products.allergen_mask}; a null column is read as 0
     * @param ingredientsText the vendor's raw ingredients text, markup included; may be null
     */
    public static void capture(OrderItem item,
                               String productName,
                               Integer declaredMask,
                               String ingredientsText) {
        int mask = declaredMask == null ? 0 : declaredMask;

        OrderAllergenAggregator.OrderAllergens line = OrderAllergenAggregator.aggregate(
                List.of(new OrderAllergenAggregator.ItemAllergens(productName, mask, ingredientsText)));

        int flagMask = 0;
        for (OrderAllergenAggregator.ReconciliationFlag flag : line.flags()) {
            flagMask |= 1 << flag.allergenBit();
        }

        // Declared and flagged are set as two independent values. The heuristic never widens the
        // declaration — that is the invariant plan 31-04 is built on, and it is preserved here by
        // construction rather than by convention.
        item.setAllergenMask(mask);
        item.setAllergenFlagMask(flagMask);
    }

    // ==============================================================================
    // Read side
    // ==============================================================================

    /** Whether this line carries a snapshot at all. False for rows written before V63. */
    public static boolean isRecorded(OrderItem item) {
        return item != null && item.getAllergenMask() != null;
    }

    /**
     * This line's declared allergen names, or {@code null} when the line predates V63.
     *
     * <p>Empty-not-null when the vendor declared nothing — the same contract
     * {@link AllergenCatalog#namesFor(int)} gives, so the per-item badge can render "no allergens"
     * as an honest empty state rather than as a missing value.
     */
    public static List<String> namesOf(OrderItem item) {
        return isRecorded(item) ? AllergenCatalog.namesFor(item.getAllergenMask()) : null;
    }

    /**
     * Rebuild the order-level view from its stored lines.
     *
     * <p><strong>A partially-recorded order reads as NOT RECORDED, and that is the safety-relevant
     * choice.</strong> Unioning only the lines that happen to carry a snapshot would produce a set
     * that is silently INCOMPLETE — under-declaration, which is the direction that injures
     * someone — while looking exactly like a complete one. An order with no lines is likewise not
     * recorded: there is nothing there to have been recorded.
     *
     * <p>Flags are rebuilt per line, in line order, ascending by allergen bit, deduplicated per
     * (product name, allergen) — matching {@link OrderAllergenAggregator}'s emission contract, so
     * the value a consumer sees is the value the aggregator would have produced.
     */
    public static OrderAllergenView viewOf(Collection<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return OrderAllergenView.NOT_RECORDED;
        }

        int mask = 0;
        List<OrderAllergenFlagDto> flags = new ArrayList<>();
        Set<String> alreadyFlagged = new LinkedHashSet<>();

        for (OrderItem item : items) {
            if (!isRecorded(item)) {
                return OrderAllergenView.NOT_RECORDED;
            }
            mask |= item.getAllergenMask();

            Integer flagMask = item.getAllergenFlagMask();
            if (flagMask == null) {
                continue;
            }
            for (int bit : AllergenCatalog.bitsFor(flagMask)) {
                if (alreadyFlagged.add(item.getProductName() + KEY_SEPARATOR + bit)) {
                    flags.add(new OrderAllergenFlagDto(
                            item.getProductName(), bit, AllergenCatalog.nameFor(bit)));
                }
            }
        }

        return new OrderAllergenView(mask, AllergenCatalog.namesFor(mask), List.copyOf(flags));
    }
}
