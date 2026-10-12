package uk.jtoye.core.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 37-35 (RWO-044 / UXT-044): the one rule for the reviewer name a PUBLIC review carries — the first
 * name and the initial of the last name with a full stop ("Grace Pemberton" -> "Grace P."). The persona
 * run found every storefront review published the customer's full name as typed at checkout.
 *
 * <p>Each surname case is asserted twice: the exact display form, and that the full surname is not in
 * it. The second form is what the threat (T-37-85) is about; the first stops "drop everything" passing.
 */
class ReviewerDisplayNameTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @DisplayName("first name plus the last name's initial and a full stop; the surname is never published")
    @CsvSource(delimiter = '|', value = {
            "Grace Pemberton           | Grace P.     | Pemberton",
            "Grace Ann Pemberton       | Grace P.     | Pemberton",
            "Mary-Jane O'Neil          | Mary-Jane O. | O'Neil",
            "grace pemberton           | grace P.     | pemberton",
            "Zoë émile-Ölund           | Zoë É.       | émile",
            "Adaeze Okonkwo-Bello      | Adaeze O.    | Okonkwo",
    })
    void twoOrMoreTokens(String full, String expected, String surname) {
        String shown = ReviewerDisplayName.of(full);
        assertThat(shown).isEqualTo(expected);
        assertThat(shown).doesNotContain(surname);
    }

    @Test
    @DisplayName("leading, trailing and repeated whitespace is not part of the name")
    void surroundingWhitespace() {
        assertThat(ReviewerDisplayName.of("   Grace    Pemberton  ")).isEqualTo("Grace P.");
    }

    @Test
    @DisplayName("a tab, a newline or a no-break space separates names too, so it cannot carry the surname through")
    void otherWhitespace() {
        assertThat(ReviewerDisplayName.of("Grace\tPemberton")).isEqualTo("Grace P.");
        assertThat(ReviewerDisplayName.of("Grace\nPemberton")).isEqualTo("Grace P.");
        assertThat(ReviewerDisplayName.of("Grace Pemberton")).isEqualTo("Grace P.");
    }

    @Test
    @DisplayName("a one-character surname is its own initial")
    void singleCharacterSurname() {
        assertThat(ReviewerDisplayName.of("Grace p")).isEqualTo("Grace P.");
    }

    @Test
    @DisplayName("a single name is shown as given, without a full stop")
    void singleToken() {
        assertThat(ReviewerDisplayName.of("Grace")).isEqualTo("Grace");
        assertThat(ReviewerDisplayName.of("  Grace  ")).isEqualTo("Grace");
    }

    @ParameterizedTest(name = "[{index}] blank -> null")
    @DisplayName("no name at all is null, so the storefront shows 'Customer'")
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", " "})
    void blankIsNull(String full) {
        assertThat(ReviewerDisplayName.of(full)).isNull();
    }
}
