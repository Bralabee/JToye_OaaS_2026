package uk.jtoye.core.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.product.IngredientMarkupParser.ParsedIngredients;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Canonical unit tests for {@link IngredientMarkupParser} — the single source of
 * truth that turns vendor ingredients text with {@code **allergen**} markup into
 * {plainText, emphasis spans}. Pure JUnit (no Spring/Testcontainers).
 *
 * <p>Each test asserts the EXACT plainText and the EXACT {@link AllergenSpan}
 * offsets, verifying {@code substring(start,end)} equals the allergen word so an
 * off-by-one in the offset math cannot slip through. The parser is fail-soft: it
 * NEVER throws on vendor input.
 */
class IngredientMarkupParserTest {

    @Test
    @DisplayName("single marked allergen -> exact plainText + one span over the word")
    void singleAllergen() {
        ParsedIngredients p = IngredientMarkupParser.parse("Wheat flour, **milk**, sugar");

        assertThat(p.plainText()).isEqualTo("Wheat flour, milk, sugar");
        assertThat(p.spans()).containsExactly(new AllergenSpan(13, 17));
        AllergenSpan span = p.spans().get(0);
        assertThat(p.plainText().substring(span.start(), span.end())).isEqualTo("milk");
    }

    @Test
    @DisplayName("no markup -> plainText unchanged, no spans (real punctuation preserved)")
    void noMarkup() {
        ParsedIngredients p = IngredientMarkupParser.parse("Yam (100%)");

        assertThat(p.plainText()).isEqualTo("Yam (100%)");
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("adjacent markup -> concatenated plainText + two back-to-back spans")
    void adjacentMarkup() {
        ParsedIngredients p = IngredientMarkupParser.parse("**milk****egg**");

        assertThat(p.plainText()).isEqualTo("milkegg");
        assertThat(p.spans()).containsExactly(new AllergenSpan(0, 4), new AllergenSpan(4, 7));
        assertThat(p.plainText().substring(0, 4)).isEqualTo("milk");
        assertThat(p.plainText().substring(4, 7)).isEqualTo("egg");
    }

    @Test
    @DisplayName("multiple separated allergens -> a span over each marked word")
    void multipleSpans() {
        ParsedIngredients p = IngredientMarkupParser.parse("**milk**, sugar, **egg**");

        assertThat(p.plainText()).isEqualTo("milk, sugar, egg");
        assertThat(p.spans()).hasSize(2);
        AllergenSpan first = p.spans().get(0);
        AllergenSpan second = p.spans().get(1);
        assertThat(p.plainText().substring(first.start(), first.end())).isEqualTo("milk");
        assertThat(p.plainText().substring(second.start(), second.end())).isEqualTo("egg");
    }

    @Test
    @DisplayName("dangling/naked delimiter -> fail-soft: stray ** kept literal, no spans, no throw")
    void danglingDelimiter() {
        ParsedIngredients p = assertDoesNotThrow(
                () -> IngredientMarkupParser.parse("Milk ** and egg"));

        assertThat(p.plainText()).isEqualTo("Milk ** and egg");
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("empty pair -> delimiters stripped, NO zero-length span produced")
    void emptyPair() {
        ParsedIngredients p = IngredientMarkupParser.parse("****");

        assertThat(p.plainText()).isEmpty();
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("empty pair embedded in text -> only the delimiters vanish, no span")
    void emptyPairEmbedded() {
        ParsedIngredients p = IngredientMarkupParser.parse("Sugar, ****, salt");

        assertThat(p.plainText()).isEqualTo("Sugar, , salt");
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("null input -> empty plainText, no spans, no throw")
    void nullInput() {
        ParsedIngredients p = assertDoesNotThrow(() -> IngredientMarkupParser.parse(null));

        assertThat(p.plainText()).isEmpty();
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("blank input -> plainText preserved verbatim, no spans")
    void blankInput() {
        ParsedIngredients p = IngredientMarkupParser.parse("   ");

        assertThat(p.plainText()).isEqualTo("   ");
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("a single asterisk is not a delimiter -> preserved literally, no span")
    void singleAsteriskIsLiteral() {
        ParsedIngredients p = IngredientMarkupParser.parse("Butter* (50%), salt");

        assertThat(p.plainText()).isEqualTo("Butter* (50%), salt");
        assertThat(p.spans()).isEmpty();
    }

    @Test
    @DisplayName("spans returned are immutable (defensive copy)")
    void spansAreImmutable() {
        ParsedIngredients p = IngredientMarkupParser.parse("**milk**");
        List<AllergenSpan> spans = p.spans();

        assertThat(spans).containsExactly(new AllergenSpan(0, 4));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> spans.add(new AllergenSpan(0, 1)));
    }
}
