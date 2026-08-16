package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.order.OrderAllergenAggregator.ItemAllergens;
import uk.jtoye.core.order.OrderAllergenAggregator.OrderAllergens;
import uk.jtoye.core.order.OrderAllergenAggregator.ReconciliationFlag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Canonical unit tests for {@link OrderAllergenAggregator} — the one pure function that
 * produces the order-level allergen answer shared by checkout (UI-SPEC S3) and the
 * kitchen display (UI-SPEC S4).
 *
 * <p>Two independent outputs are under test and they must stay independent:
 * <ul>
 *   <li>the <b>declared</b> set — the union of the items' vendor-declared masks. This is
 *       the legally operative statement and nothing in this class may rewrite it.</li>
 *   <li>the <b>reconciliation flags</b> — advisory "Check" lines naming a product whose
 *       free-text ingredients emphasise an allergen its declared mask omits.</li>
 * </ul>
 *
 * <p>The case at the centre of this file is the QA council's A11Y-02 (CRITICAL): a product
 * with {@code allergenMask: 0} and {@code **milk**} in its ingredients text renders the
 * emphasis to a sighted user and NO allergen panel at all — a sighted user sees a bolded
 * allergen, a blind user gets nothing. Nothing on the tree before this class reconciled
 * the two, so that test is red on the pre-change tree by construction.
 *
 * <p>D-01 / D-02: every input here is the ORDER's own declared product data. No consumer
 * allergen profile is read, passed or inferred anywhere — there is no consumer-versus-product
 * comparison in this phase, and a signature that accepted one would be wrong by design.
 */
class OrderAllergenAggregatorTest {

    private static final int GLUTEN = 1 << 0;
    private static final int MILK = 1 << 6;
    private static final int SESAME = 1 << 10;

    private static ItemAllergens item(String name, int mask, String ingredients) {
        return new ItemAllergens(name, mask, ingredients);
    }

    // ------------------------------------------------------------ the declared union

    @Test
    @DisplayName("two items declaring {0,6} and {6,10} aggregate to exactly {0,6,10} — union, deduplicated, bit order")
    void declaredSetIsTheDeduplicatedUnionInBitOrder() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Jollof Rice", GLUTEN | MILK, null),
                item("Sesame Chicken", MILK | SESAME, null)));

        assertThat(result.declaredBits()).containsExactly(0, 6, 10);
        assertThat(result.declaredNames()).containsExactly("Gluten", "Milk", "Sesame");
        assertThat(result.declaredMask()).isEqualTo(GLUTEN | MILK | SESAME);
    }

    @Test
    @DisplayName("every item declaring mask 0 -> EMPTY declared set, and the result object still EXISTS")
    void allZeroMasksYieldAnEmptySetNotANullResult() {
        // An empty set is a value, not an absence: the checkout panel must still render
        // with the honest copy. A silently missing panel is indistinguishable from a
        // panel that failed to render.
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Plain Rice", 0, "rice, water"),
                item("Bottled Water", 0, null)));

        assertThat(result).isNotNull();
        assertThat(result.declaredBits()).isNotNull().isEmpty();
        assertThat(result.declaredNames()).isNotNull().isEmpty();
        assertThat(result.declaredMask()).isZero();
        assertThat(result.flags()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("an order with zero items -> empty declared set and no flags")
    void zeroItemsYieldAnEmptyResult() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of());

        assertThat(result).isNotNull();
        assertThat(result.declaredBits()).isEmpty();
        assertThat(result.declaredNames()).isEmpty();
        assertThat(result.declaredMask()).isZero();
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("item ordering does not affect the declared set — reversed items produce an equal result")
    void itemOrderingDoesNotAffectTheDeclaredSet() {
        List<ItemAllergens> items = List.of(
                item("Jollof Rice", GLUTEN | MILK, "**wheat**, **milk**"),
                item("Sesame Chicken", MILK | SESAME, "**sesame**"),
                item("Plain Rice", 0, "rice"));
        List<ItemAllergens> reversed = new ArrayList<>(items);
        java.util.Collections.reverse(reversed);

        OrderAllergens forwards = OrderAllergenAggregator.aggregate(items);
        OrderAllergens backwards = OrderAllergenAggregator.aggregate(reversed);

        assertThat(backwards.declaredBits()).isEqualTo(forwards.declaredBits());
        assertThat(backwards.declaredNames()).isEqualTo(forwards.declaredNames());
        assertThat(backwards.declaredMask()).isEqualTo(forwards.declaredMask());
        // Flags are emitted in item order, so the two runs carry the same flags in a
        // different sequence — asserted as a set so the invariant is stated, not skipped.
        assertThat(backwards.flags()).containsExactlyInAnyOrderElementsOf(forwards.flags());
    }

    // ---------------------------------------------------------- the reconciliation flag

    @Test
    @DisplayName("A11Y-02 verbatim: mask 0 with **milk** in the text -> exactly ONE flag naming the product and Milk")
    void undeclaredMilkInTheTextIsFlagged() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Jollof Rice", 0, "rice, tomato, **milk**, spices")));

        assertThat(result.flags()).hasSize(1);
        ReconciliationFlag flag = result.flags().get(0);
        assertThat(flag.productName()).isEqualTo("Jollof Rice");
        assertThat(flag.allergenBit()).isEqualTo(6);
        assertThat(flag.allergenName()).isEqualTo("Milk");
    }

    @Test
    @DisplayName("the repo's own fixture: **yoghurt (milk)** with a mask omitting bit 6 -> the same flag")
    void undeclaredCompoundPhraseIsFlagged() {
        // "mango, **yoghurt (milk)**, cardamom" is DemoDataSeeder.java:551 verbatim, and
        // the only real emphasised span in the tree. A whole-string match resolves nothing
        // on it, which would make the reconciliation silently useless on real vendor data.
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Mango Lassi", 0, "mango, **yoghurt (milk)**, cardamom")));

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).allergenName()).isEqualTo("Milk");
        assertThat(result.flags().get(0).productName()).isEqualTo("Mango Lassi");
    }

    @Test
    @DisplayName("declaring the allergen AND emphasising it -> NO flag; the good path is not nagged")
    void correctlyDeclaredAllergenIsNotFlagged() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Mango Lassi", MILK, "mango, **yoghurt (milk)**, cardamom")));

        assertThat(result.flags()).isEmpty();
        // ...and the declared set is still reported, so "no flag" is not "no allergen".
        assertThat(result.declaredNames()).containsExactly("Milk");
    }

    @Test
    @DisplayName("null or blank ingredients text -> no flag and no throw")
    void absentIngredientsTextIsFailSoft() {
        assertDoesNotThrow(() -> OrderAllergenAggregator.aggregate(Arrays.asList(
                item("No Text", 0, null),
                item("Blank Text", 0, ""),
                item("Whitespace Text", 0, "   "))));

        OrderAllergens result = OrderAllergenAggregator.aggregate(Arrays.asList(
                item("No Text", 0, null),
                item("Blank Text", 0, ""),
                item("Whitespace Text", 0, "   ")));

        assertThat(result.flags()).isEmpty();
        assertThat(result.declaredBits()).isEmpty();
    }

    @Test
    @DisplayName("an emphasised NON-allergen word -> no flag")
    void emphasisedNonAllergenIsNotFlagged() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Spiced Mango", 0, "mango, **cardamom**, sugar")));

        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("unmarked allergen text -> no flag; the emphasis markup is what the vendor asserts")
    void unmarkedIngredientTextIsNotFlagged() {
        // Reconciliation reads the EMPHASISED spans, which is what IngredientMarkupParser
        // and the PPDS label already treat as the vendor's allergen assertion. Flagging
        // every unmarked word would fire on almost every product.
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Milk Loaf", 0, "flour, milk, yeast")));

        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("THE DANGEROUS DIRECTION: a flag NEVER widens the declared set — both halves in one test")
    void reconciliationNeverAltersTheDeclaredSet() {
        // Declared and flagged are two separate outputs. A text match is a heuristic, and
        // rewriting a vendor's legally operative declaration from a heuristic is a worse
        // defect than the one being fixed. Both halves are asserted here together so a
        // future change cannot satisfy one and quietly drop the other.
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Jollof Rice", GLUTEN, "**wheat** flour, **milk**, tomato")));

        // half 1 — the declared set still omits Milk, exactly as the vendor declared it
        assertThat(result.declaredBits()).containsExactly(0);
        assertThat(result.declaredNames()).containsExactly("Gluten");
        assertThat(result.declaredMask()).isEqualTo(GLUTEN);
        assertThat(AllergenBits.hasMilk(result.declaredMask())).isFalse();

        // half 2 — and the undeclared Milk is carried SEPARATELY, as a flag
        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).allergenName()).isEqualTo("Milk");
        assertThat(result.flags().get(0).allergenBit()).isEqualTo(6);
    }

    @Test
    @DisplayName("a text mentioning the same allergen three times -> ONE flag per (product, allergen)")
    void repeatedMentionsProduceOneFlag() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Triple Dairy", 0, "**milk**, **butter**, **cheese**, sugar")));

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).allergenName()).isEqualTo("Milk");
    }

    @Test
    @DisplayName("one span naming two undeclared allergens -> a flag for EACH, ordered by bit")
    void aSpanNamingTwoAllergensFlagsBoth() {
        // Returning only the first would under-declare, which is the injurious direction.
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Custard Tart", 0, "**milk and egg** custard, sugar")));

        assertThat(result.flags()).extracting(ReconciliationFlag::allergenName)
                .containsExactly("Eggs", "Milk");
    }

    @Test
    @DisplayName("flags from several products are kept separate and named per product")
    void flagsAreNamedPerProduct() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Jollof Rice", 0, "rice, **milk**"),
                item("Sesame Chicken", 0, "chicken, **sesame**"),
                item("Plain Rice", 0, "rice")));

        assertThat(result.flags()).hasSize(2);
        assertThat(result.flags()).extracting(ReconciliationFlag::productName)
                .containsExactly("Jollof Rice", "Sesame Chicken");
        assertThat(result.flags()).extracting(ReconciliationFlag::allergenName)
                .containsExactly("Milk", "Sesame");
    }

    @Test
    @DisplayName("a partly-correct declaration flags only the MISSING allergen")
    void onlyTheUndeclaredAllergenIsFlagged() {
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Sesame Loaf", SESAME, "**sesame** seeds, **wheat** flour")));

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).allergenName()).isEqualTo("Gluten");
    }

    // ----------------------------------------------------------------- fail-soft input

    @Test
    @DisplayName("a null item collection and null entries are tolerated, never thrown on")
    void nullInputIsFailSoft() {
        assertDoesNotThrow(() -> OrderAllergenAggregator.aggregate(null));
        assertThat(OrderAllergenAggregator.aggregate(null).declaredBits()).isEmpty();

        List<ItemAllergens> withNull = Arrays.asList(item("Real", MILK, "**milk**"), null);
        assertDoesNotThrow(() -> OrderAllergenAggregator.aggregate(withNull));
        assertThat(OrderAllergenAggregator.aggregate(withNull).declaredNames())
                .containsExactly("Milk");
    }

    @Test
    @DisplayName("dangling markup is fail-soft — the parser's own rules are inherited, not reimplemented")
    void danglingMarkupIsFailSoft() {
        // IngredientMarkupParser keeps an unmatched ** literal and emits no span. Reusing
        // it rather than forking a second parser is what keeps this consistent with the
        // legally operative PPDS label.
        OrderAllergens result = OrderAllergenAggregator.aggregate(List.of(
                item("Odd Markup", 0, "rice, **milk")));

        assertThat(result.flags()).isEmpty();
    }

    /** Local bit helper so this test states its own expectation without importing the catalogue's. */
    private static final class AllergenBits {
        static boolean hasMilk(int mask) {
            return (mask & (1 << 6)) != 0;
        }
    }
}
