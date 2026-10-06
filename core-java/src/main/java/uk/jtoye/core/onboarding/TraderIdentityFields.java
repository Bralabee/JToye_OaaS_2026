package uk.jtoye.core.onboarding;

import java.util.Locale;

/**
 * The ONE canonical form of the trader-identity fields that customers see (#789), shared by the
 * request DTO's validation and the service's write path so what is validated is what is stored.
 * Pattern of {@link CompanyNumbers}: pure functions, no I/O.
 *
 * <ul>
 *   <li>Text fields are {@link String#strip() stripped}; an optional field that strips to empty
 *       is {@code null} (absent), never a stored empty string.</li>
 *   <li>A postcode is upper-cased, every whitespace run removed, and ONE space put before the
 *       three-character inward code: {@code "sw1a1aa"} and {@code " SW1A   1AA "} both become
 *       {@code "SW1A 1AA"}.</li>
 *   <li>A VAT number is upper-cased with every whitespace removed:
 *       {@code "gb 123 4567 89"} becomes {@code "GB123456789"}.</li>
 * </ul>
 *
 * <p>The shape checks themselves live on {@code UpdateTraderIdentityRequest} as Bean
 * Validation patterns, so a refusal is a field-level RFC 7807 400 naming the field.
 */
public final class TraderIdentityFields {

    /**
     * A UK postcode in any case with up to four spaces between outward and inward code, applied
     * after {@link String#strip()}. Every quantifier is bounded, so matching untrusted text is
     * linear. It checks SHAPE only; whether the postcode exists is not this record's question
     * (the trader declares their address, and the shape check stops typos and junk).
     */
    public static final String POSTCODE_PATTERN =
            "^[A-Za-z]{1,2}[0-9][A-Za-z0-9]?\\s{0,4}[0-9][A-Za-z]{2}$";

    /**
     * A UK VAT number: GB followed by exactly 9 or exactly 12 digits (the 12-digit form is a
     * branch-trader number), any case, optional single spaces between characters. Applied after
     * {@link String#strip()}; a blank value is absent, not invalid.
     */
    public static final String VAT_PATTERN = "^[Gg][Bb](\\s?[0-9]){9}((\\s?[0-9]){3})?$";

    private TraderIdentityFields() {
    }

    /** Strip; {@code null} stays {@code null}. Blank stays blank, so a required field still fails @NotBlank. */
    public static String strip(String raw) {
        return raw == null ? null : raw.strip();
    }

    /** Strip; blank becomes {@code null} (an optional field that was left empty). */
    public static String stripToNull(String raw) {
        String s = strip(raw);
        return s == null || s.isEmpty() ? null : s;
    }

    /** Canonical postcode: upper-case, one space before the inward code. Blank becomes {@code null}. */
    public static String normalisePostcode(String raw) {
        String compact = removeWhitespace(raw);
        if (compact == null || compact.isEmpty()) {
            return null;
        }
        String upper = compact.toUpperCase(Locale.ROOT);
        if (upper.length() <= 3) {
            return upper;
        }
        return upper.substring(0, upper.length() - 3) + " " + upper.substring(upper.length() - 3);
    }

    /** Canonical VAT number: upper-case, no whitespace. Blank becomes {@code null} (not registered). */
    public static String normaliseVatNumber(String raw) {
        String compact = removeWhitespace(raw);
        if (compact == null || compact.isEmpty()) {
            return null;
        }
        return compact.toUpperCase(Locale.ROOT);
    }

    private static String removeWhitespace(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints().filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
                .forEach(out::appendCodePoint);
        return out.toString();
    }
}
