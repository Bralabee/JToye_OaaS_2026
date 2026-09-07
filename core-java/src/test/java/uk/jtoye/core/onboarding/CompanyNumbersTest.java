package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-7 / A14 (QA council 20260902-134741): the register key is exact and 8 wide, so the
 * canonical form pads purely numeric short numbers and leaves everything else alone.
 */
class CompanyNumbersTest {

    @Test
    @DisplayName("445790 -> 00445790 (purely numeric, shorter than 8, left-zero-padded)")
    void numericShortNumberIsZeroPadded() {
        assertThat(CompanyNumbers.normalise("445790")).isEqualTo("00445790");
        assertThat(CompanyNumbers.normalise("  445790  ")).isEqualTo("00445790");
        assertThat(CompanyNumbers.normalise("1")).isEqualTo("00000001");
    }

    @Test
    @DisplayName("SC123456 unchanged (letter prefix already fills the width)")
    void prefixedNumberIsNotPadded() {
        assertThat(CompanyNumbers.normalise("SC123456")).isEqualTo("SC123456");
        assertThat(CompanyNumbers.normalise("sc123456")).isEqualTo("SC123456");
        assertThat(CompanyNumbers.normalise("NI0123")).isEqualTo("NI0123");
    }

    @Test
    @DisplayName("00445790 unchanged (already 8) and longer numeric values are never truncated or padded")
    void fullLengthAndLongerNumbersAreUnchanged() {
        assertThat(CompanyNumbers.normalise("00445790")).isEqualTo("00445790");
        assertThat(CompanyNumbers.normalise("1234567890")).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("null / blank -> null (sole trader: no register lookup at all)")
    void blankIsNull() {
        assertThat(CompanyNumbers.normalise(null)).isNull();
        assertThat(CompanyNumbers.normalise("")).isNull();
        assertThat(CompanyNumbers.normalise("   ")).isNull();
    }

    @Test
    @DisplayName("non-ASCII digits are not treated as a numeric key (no silent padding into a plausible number)")
    void nonAsciiDigitsAreNotPadded() {
        // Arabic-Indic digits: Character.isDigit would say true; the register would not.
        assertThat(CompanyNumbers.normalise("١٢٣")).isEqualTo("١٢٣");
    }
}
