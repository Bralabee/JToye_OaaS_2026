package uk.jtoye.core.order;

import uk.jtoye.core.product.AllergenCatalog;
import uk.jtoye.core.product.AllergenSpan;
import uk.jtoye.core.product.IngredientMarkupParser;
import uk.jtoye.core.product.IngredientMarkupParser.ParsedIngredients;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates an order's allergen picture into TWO independent outputs: the declared
 * allergen set, and a list of advisory reconciliation flags.
 *
 * <h2>Why this is a pure function</h2>
 * <p>The same answer has to appear on the checkout panel (UI-SPEC S3) and on the kitchen
 * display (UI-SPEC S4). A pure, dependency-free static function is the only shape that
 * makes "the same answer" provable — it can be exercised end to end by a plain unit test
 * with no database, no broker and no Spring context, so the two surfaces cannot drift by
 * being fed through different code paths. There is deliberately no injected dependency,
 * no repository and no persistence here; the DTO and storage wiring belongs to plan 31-10.
 *
 * <h2>Declared and flagged are two different things, and must stay that way</h2>
 * <p>{@link OrderAllergens#declaredMask()} and its bit/name views are the union of what
 * the vendors actually declared. That is the legally operative statement and NOTHING in
 * this class rewrites it.
 *
 * <p>{@link OrderAllergens#flags()} is advisory. A flag says "this product's ingredients
 * text emphasises an allergen its declared mask omits — check before ordering". It is
 * produced by a text heuristic, and silently folding a heuristic into a vendor's legally
 * operative declaration would be a worse defect than the one being fixed: it would make
 * the platform the author of an allergen statement it cannot stand behind, and it would
 * mask the vendor's underlying data error instead of surfacing it. So the flag is carried
 * beside the declared set, never merged into it.
 *
 * <h2>Privacy boundary (Phase 31 D-01 / D-02)</h2>
 * <p>This aggregation is over the ORDER's own declared product data only. It never reads
 * a consumer's stored allergen profile — that field is Article 9 special-category health
 * data and the platform's recorded decision is not to process it. There is no
 * consumer-versus-product comparison anywhere in this phase: what the consumer is shown
 * is this order's own allergen set, which they acknowledge having read. The platform never
 * learns anyone's allergies. Asserted by grep in this plan's verification, because a
 * compiler cannot express the constraint.
 *
 * <h2>Reuse, never fork</h2>
 * <p>Reconciliation runs {@link IngredientMarkupParser#parse(String)} — the declared
 * single source of truth for the {@code **allergen**} markup transform, already used by
 * both the product save path and the PPDS / Natasha's-Law label renderer. A second parser
 * here would eventually disagree with a legally operative printed label. Its fail-soft
 * rules (non-nested left-to-right pairing, literal dangling delimiter, never throws on
 * vendor input) are inherited rather than reimplemented.
 */
public final class OrderAllergenAggregator {

    private OrderAllergenAggregator() {
    }

    /**
     * One order line's allergen inputs: the product name to show a human, the vendor's
     * declared allergen mask, and the raw ingredients text (markup included, may be null).
     *
     * <p>Deliberately a plain value rather than an entity: the caller decides whether the
     * mask comes from a write-time snapshot or a read-time lookup, and this class works
     * identically either way.
     */
    public record ItemAllergens(String productName, int declaredMask, String ingredientsText) {
    }

    /**
     * An advisory "Check" line: {@code productName}'s ingredients text emphasises
     * {@code allergenName}, which its declared mask omits. Never authoritative.
     */
    public record ReconciliationFlag(String productName, int allergenBit, String allergenName) {
    }

    /**
     * The order's allergen picture. {@code declaredMask} is the union of the items' masks;
     * {@code declaredBits} and {@code declaredNames} are its ordered, deduplicated views;
     * {@code flags} is the separate advisory list. Every list is non-null — an empty
     * allergen set is a value the checkout panel still renders honestly, not an absence.
     */
    public record OrderAllergens(int declaredMask, List<Integer> declaredBits,
                                 List<String> declaredNames, List<ReconciliationFlag> flags) {
    }

    /**
     * Aggregate an order's items into the declared set plus the reconciliation flags.
     *
     * <p>Fail-soft throughout: a null collection, a null element, and null/blank
     * ingredients text all yield a well-formed result rather than an exception. Vendor
     * free-text crosses a trust boundary here and a safety panel that throws is a safety
     * panel that does not render.
     *
     * @param items the order's lines; may be null or empty
     * @return the declared union and the advisory flags, never null
     */
    public static OrderAllergens aggregate(Collection<ItemAllergens> items) {
        int declaredMask = 0;
        List<ReconciliationFlag> flags = new ArrayList<>();

        if (items != null) {
            for (ItemAllergens item : items) {
                if (item == null) {
                    continue;
                }
                declaredMask |= item.declaredMask();
                collectFlags(item, flags);
            }
        }

        return new OrderAllergens(
                declaredMask,
                AllergenCatalog.bitsFor(declaredMask),
                AllergenCatalog.namesFor(declaredMask),
                List.copyOf(flags));
    }

    /**
     * Append one flag per allergen this item's emphasised ingredients text names but its
     * own declared mask omits.
     *
     * <p>Reconciliation is per item against the ITEM's mask, not against the order union —
     * otherwise a second product correctly declaring milk would silently excuse a first
     * product that failed to.
     *
     * <p>Only EMPHASISED runs are considered. The {@code **...**} markup is what the vendor
     * asserts as an allergen and what the PPDS label already emboldens; treating every
     * unmarked word as a candidate would fire on nearly every product, and a flag that
     * fires on everything is ignored — the same outcome as no flag at all.
     */
    private static void collectFlags(ItemAllergens item, List<ReconciliationFlag> flags) {
        String raw = item.ingredientsText();
        if (raw == null || raw.isBlank()) {
            return;
        }

        ParsedIngredients parsed = IngredientMarkupParser.parse(raw);
        String plain = parsed.plainText();

        // One flag per (product, allergen): a text naming milk three times is one problem,
        // and three identical Check lines on a kitchen ticket is noise that hides the rest.
        Set<Integer> alreadyFlagged = new LinkedHashSet<>();

        for (AllergenSpan span : parsed.spans()) {
            int start = span.start();
            int end = span.end();
            // The parser is the only producer of these offsets, but this is vendor-derived
            // data on a safety path: clamp rather than risk an exception reaching the panel.
            if (start < 0 || end > plain.length() || start >= end) {
                continue;
            }

            for (int bit : AllergenCatalog.resolveBits(plain.substring(start, end))) {
                if (AllergenCatalog.hasAllergen(item.declaredMask(), bit)) {
                    continue; // declared correctly — the good path is not nagged
                }
                if (alreadyFlagged.add(bit)) {
                    flags.add(new ReconciliationFlag(
                            item.productName(), bit, AllergenCatalog.nameFor(bit)));
                }
            }
        }
    }
}
