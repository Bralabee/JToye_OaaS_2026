package uk.jtoye.core.common.contact;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of PR 1122: the checkout's JavaScript {@code \s} accepts non-breaking and other Unicode spaces (a phone
 * pasted from a contact card carries U+00A0), so core must accept exactly the same set, or the browser lets
 * through a number core then refuses with a typed 400. The set is JavaScript's WhiteSpace plus LineTerminator
 * list, written out in both files; {@code frontend/lib/__tests__/contact-and-radius.test.ts} runs the same
 * fixtures on the other side.
 */
class ContactPlausibilityWhitespaceParityTest {

    @ParameterizedTest(name = "accepts U+{0} as a separator and as padding")
    @ValueSource(strings = {"00A0", "202F", "2009", "3000", "FEFF", "1680", "2000", "200A", "205F", "2028", "2029", "0009", "000B", "000C"})
    void acceptsTheWhitespaceJavaScriptAccepts(String hex) {
        String space = new String(Character.toChars(Integer.parseInt(hex, 16)));
        assertThat(ContactPlausibility.isPlausiblePhone("07700" + space + "900123"))
                .as("separator U+" + hex).isTrue();
        assertThat(ContactPlausibility.normalisePhone("07700" + space + "900123"))
                .as("normalised U+" + hex).isEqualTo("+447700900123");
        assertThat(ContactPlausibility.isPlausiblePhone(space + "07700 900123" + space))
                .as("padding U+" + hex + " is trimmed, as JavaScript's trim() does").isTrue();
    }

    @ParameterizedTest(name = "refuses U+{0}: JavaScript's \\s does not match it")
    @ValueSource(strings = {"200B", "0085", "00AD", "2060"})
    void refusesWhatJavaScriptDoesNotCallWhitespace(String hex) {
        String c = new String(Character.toChars(Integer.parseInt(hex, 16)));
        assertThat(ContactPlausibility.isPlausiblePhone("07700" + c + "900123")).as("separator U+" + hex).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"07700 900123", "07700900123", "+44 7700 900123"})
    void theAsciiControlsStillPass(String phone) {
        assertThat(ContactPlausibility.isPlausiblePhone(phone)).isTrue();
    }
}
