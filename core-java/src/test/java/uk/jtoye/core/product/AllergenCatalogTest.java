package uk.jtoye.core.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Canonical unit tests for {@link AllergenCatalog} — the Java copy of the UK FSA
 * 14-allergen bit table, and the span-text to allergen-bit resolver that D-03's
 * reconciliation flag is built on.
 *
 * <p>Pure JUnit (no Spring, no Testcontainers). Two independent contracts are
 * exercised here:
 * <ul>
 *   <li>the <b>table</b> — bit to name, mask to names, mask membership. Cross-language
 *       agreement with {@code frontend/types/api.ts} is asserted separately by
 *       {@code frontend/__tests__/allergen-table-parity.test.ts}, which reads BOTH
 *       files off disk; this file asserts the Java side is internally correct.</li>
 *   <li>the <b>resolver</b> — emphasised span text to a bit through an explicit
 *       conservative synonym list. The failing direction that matters is
 *       UNDER-declaration, so the resolver has to fire on real vendor prose
 *       ("yoghurt (milk)"), and it has to stay silent on a spice ("cardamom") and on
 *       a word that merely contains a synonym ("eggplant", "nutmeg") — a resolver
 *       that flags everything is ignored, which is the same outcome as no flag.</li>
 * </ul>
 */
class AllergenCatalogTest {

    // ---------------------------------------------------------------- the table

    @Test
    @DisplayName("nameFor returns the exact 14 UK FSA names in bit order")
    void nameForEveryBit() {
        assertThat(AllergenCatalog.nameFor(0)).isEqualTo("Gluten");
        assertThat(AllergenCatalog.nameFor(1)).isEqualTo("Crustaceans");
        assertThat(AllergenCatalog.nameFor(2)).isEqualTo("Eggs");
        assertThat(AllergenCatalog.nameFor(3)).isEqualTo("Fish");
        assertThat(AllergenCatalog.nameFor(4)).isEqualTo("Peanuts");
        assertThat(AllergenCatalog.nameFor(5)).isEqualTo("Soybeans");
        assertThat(AllergenCatalog.nameFor(6)).isEqualTo("Milk");
        assertThat(AllergenCatalog.nameFor(7)).isEqualTo("Nuts");
        assertThat(AllergenCatalog.nameFor(8)).isEqualTo("Celery");
        assertThat(AllergenCatalog.nameFor(9)).isEqualTo("Mustard");
        assertThat(AllergenCatalog.nameFor(10)).isEqualTo("Sesame");
        assertThat(AllergenCatalog.nameFor(11)).isEqualTo("Sulphites");
        assertThat(AllergenCatalog.nameFor(12)).isEqualTo("Lupin");
        assertThat(AllergenCatalog.nameFor(13)).isEqualTo("Molluscs");
    }

    @Test
    @DisplayName("the catalogue is exactly 14 entries, bits 0..13, no gaps and no duplicates")
    void catalogueShape() {
        List<AllergenCatalog.Allergen> all = AllergenCatalog.allergens();

        assertThat(all).hasSize(14);
        assertThat(all).extracting(AllergenCatalog.Allergen::bit)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        assertThat(all).extracting(AllergenCatalog.Allergen::name).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("nameFor rejects a bit outside 0..13 rather than inventing an allergen")
    void nameForRejectsOutOfRange() {
        assertThatThrownBy(() -> AllergenCatalog.nameFor(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> AllergenCatalog.nameFor(14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("14");
    }

    @Test
    @DisplayName("namesFor(mask with bits 6 and 0) -> Gluten then Milk, in bit order")
    void namesForIsInBitOrder() {
        int mask = (1 << 6) | (1 << 0);

        assertThat(AllergenCatalog.namesFor(mask)).containsExactly("Gluten", "Milk");
    }

    @Test
    @DisplayName("namesFor(0) is an EMPTY list, never null — an empty set is a value")
    void namesForZeroIsEmptyNotNull() {
        List<String> names = AllergenCatalog.namesFor(0);

        assertThat(names).isNotNull();
        assertThat(names).isEmpty();
    }

    @Test
    @DisplayName("namesFor never returns duplicates even when every bit is set")
    void namesForAllBitsHasNoDuplicates() {
        int allBits = (1 << 14) - 1;

        List<String> names = AllergenCatalog.namesFor(allBits);

        assertThat(names).hasSize(14);
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).startsWith("Gluten").endsWith("Molluscs");
    }

    @Test
    @DisplayName("bitsFor mirrors namesFor: ordered bits, empty for 0")
    void bitsForOrderedAndEmptyForZero() {
        assertThat(AllergenCatalog.bitsFor((1 << 10) | (1 << 6))).containsExactly(6, 10);
        assertThat(AllergenCatalog.bitsFor(0)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("hasAllergen agrees with the TypeScript (mask & (1 << bit)) !== 0 for every bit 0..13")
    void hasAllergenAgreesWithTypeScript() {
        for (int bit = 0; bit < 14; bit++) {
            int only = 1 << bit;
            // set -> true, in both languages
            assertThat(AllergenCatalog.hasAllergen(only, bit))
                    .as("bit %d set", bit)
                    .isEqualTo((only & (1 << bit)) != 0)
                    .isTrue();
            // cleared -> false, in both languages
            int without = ~only;
            assertThat(AllergenCatalog.hasAllergen(without, bit))
                    .as("bit %d cleared", bit)
                    .isEqualTo((without & (1 << bit)) != 0)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("hasAllergen is false outside the catalogue range — there is no allergen 14")
    void hasAllergenOutsideCatalogueIsFalse() {
        assertThat(AllergenCatalog.hasAllergen(~0, 14)).isFalse();
        assertThat(AllergenCatalog.hasAllergen(~0, -1)).isFalse();
    }

    // ------------------------------------------------------------- the resolver

    @Test
    @DisplayName("resolveBit(\"milk\") -> bit 6")
    void resolvesBareAllergenName() {
        assertThat(AllergenCatalog.resolveBit("milk")).contains(6);
    }

    @Test
    @DisplayName("resolveBit is case- and whitespace-insensitive")
    void resolveBitIgnoresCaseAndSurroundingWhitespace() {
        assertThat(AllergenCatalog.resolveBit("MILK")).contains(6);
        assertThat(AllergenCatalog.resolveBit(" Milk ")).contains(6);
        assertThat(AllergenCatalog.resolveBit("\tMiLk\n")).contains(6);
    }

    @Test
    @DisplayName("resolveBit(\"yoghurt (milk)\") -> bit 6 — the repo's own fixture is a COMPOUND phrase")
    void resolvesCompoundPhraseFromTheRepoFixture() {
        // DemoDataSeeder.java:551 and ingredient-text.test.tsx both use
        // "mango, **yoghurt (milk)**, cardamom". A whole-string equality match would
        // resolve nothing on this, the only real emphasised span in the tree.
        assertThat(AllergenCatalog.resolveBit("yoghurt (milk)")).contains(6);
    }

    @Test
    @DisplayName("resolveBit(\"wheat flour\") -> bit 0 (Gluten) via a declared synonym")
    void resolvesGlutenSynonym() {
        assertThat(AllergenCatalog.resolveBit("wheat flour")).contains(0);
    }

    @Test
    @DisplayName("resolveBit(\"almonds\") -> bit 7 (Nuts) via a declared synonym")
    void resolvesNutSynonym() {
        assertThat(AllergenCatalog.resolveBit("almonds")).contains(7);
    }

    @Test
    @DisplayName("resolveBit(\"cardamom\") is EMPTY — a spice is not an allergen")
    void doesNotResolveANonAllergen() {
        // A resolver that matches everything flags every product, and a flag that
        // fires on everything is ignored — the same outcome as no flag at all.
        assertThat(AllergenCatalog.resolveBit("cardamom")).isEmpty();
        assertThat(AllergenCatalog.resolveBit("mango")).isEmpty();
    }

    @Test
    @DisplayName("a synonym embedded in an UNRELATED word does not match (word-boundary aware, not `contains`)")
    void doesNotMatchSynonymAsSubstringOfAnotherWord() {
        // "eggplant" contains "egg"; "nutmeg" contains "nut". Both are real
        // ingredients that are NOT the allergen their substring names.
        assertThat(AllergenCatalog.resolveBit("eggplant")).isEmpty();
        assertThat(AllergenCatalog.resolveBit("nutmeg")).isEmpty();
        assertThat(AllergenCatalog.resolveBit("butternut squash")).isEmpty();

        // Positive control for the same instrument: the bare words DO resolve, so the
        // three empties above are word-boundary behaviour and not a dead resolver.
        assertThat(AllergenCatalog.resolveBit("egg")).contains(2);
        assertThat(AllergenCatalog.resolveBit("nuts")).contains(7);
    }

    @Test
    @DisplayName("resolveBit(null) and resolveBit(\"\") are EMPTY and never throw")
    void resolveBitIsFailSoftOnAbsentInput() {
        assertDoesNotThrow(() -> AllergenCatalog.resolveBit(null));
        assertDoesNotThrow(() -> AllergenCatalog.resolveBit(""));
        assertThat(AllergenCatalog.resolveBit(null)).isEmpty();
        assertThat(AllergenCatalog.resolveBit("")).isEmpty();
        assertThat(AllergenCatalog.resolveBit("   ")).isEmpty();
        assertThat(AllergenCatalog.resolveBit("()%,")).isEmpty();
    }

    @Test
    @DisplayName("resolveBits returns EVERY allergen a span names, ascending, deduplicated")
    void resolveBitsReturnsAllNamedAllergens() {
        // A vendor may emphasise a run that names two allergens. Returning only the
        // first would under-declare, which is the injurious direction.
        assertThat(AllergenCatalog.resolveBits("milk and egg")).containsExactly(2, 6);
        // "yoghurt" and "milk" both resolve to 6 -> one entry, not two.
        assertThat(AllergenCatalog.resolveBits("yoghurt (milk)")).containsExactly(6);
        assertThat(AllergenCatalog.resolveBits("cardamom")).isNotNull().isEmpty();
        assertThat(AllergenCatalog.resolveBits(null)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("resolveBit is the lowest bit resolveBits found — the two agree by construction")
    void resolveBitAgreesWithResolveBits() {
        Optional<Integer> single = AllergenCatalog.resolveBit("milk and egg");
        List<Integer> all = AllergenCatalog.resolveBits("milk and egg");

        assertThat(all).isNotEmpty();
        assertThat(single).contains(all.get(0));
    }

    @Test
    @DisplayName("every one of the 14 allergens is reachable from its own lower-cased name")
    void everyAllergenNameResolvesToItsOwnBit() {
        // Without this, an allergen could sit in the table with no synonym entry at
        // all and the reconciliation would be permanently blind to it.
        for (AllergenCatalog.Allergen a : AllergenCatalog.allergens()) {
            assertThat(AllergenCatalog.resolveBits(a.name().toLowerCase()))
                    .as("allergen %d (%s) must resolve from its own name", a.bit(), a.name())
                    .contains(a.bit());
        }
    }

    @Test
    @DisplayName("the resolver never throws on pathological vendor input")
    void resolverIsFailSoftOnPathologicalInput() {
        String pathological = "*".repeat(5000) + " milk " + "(".repeat(5000);

        assertDoesNotThrow(() -> AllergenCatalog.resolveBits(pathological));
        assertThat(AllergenCatalog.resolveBits(pathological)).contains(6);
    }
}
