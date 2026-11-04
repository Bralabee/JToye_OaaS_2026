package uk.jtoye.core.common.contact;

import java.util.regex.Pattern;

/**
 * Plausibility checks for the contact details an order carries (#841, UXT-072).
 *
 * <p>The point is to refuse values that can never reach anybody ("x", "0", "call me", {@code name@example}) so a
 * vendor is not left with an order they cannot chase. It is NOT verification: a well-formed number can still be
 * wrong, and verification is a separate, consent-bearing flow (V88 phone challenges). It also does not judge
 * what a name "looks like": names are not constrained to a script, a length beyond two letters, or a pattern
 * (O'Brien, Nguyen, Li, a single-word name, a non-Latin name all pass).
 *
 * <p>Phones. Accepted: a UK number in national form ({@code 07700 900123}, {@code 020 7946 0958}), with the
 * {@code +44} / {@code 0044} prefix (with or without the trunk {@code 0}), or any other international number in
 * {@code +} form (E.164: 8 to 15 digits). Spaces, hyphens, dots and brackets are allowed as separators and
 * ignored. Refused: fewer than the digits a real number has, letters, a run of one repeated digit
 * ({@code 0000000000}), and a national number that is not 10 or 11 digits. {@link #normalisePhone} gives the
 * E.164 form of an accepted number; validation does not change what is stored.
 *
 * <p>Email. A local part, one {@code @}, and a dotted domain whose last label is at least two letters, no
 * whitespace: the shape a mail server will route, which Hibernate's {@code @Email} is not (it accepts
 * {@code name@example}). Not an RFC 5322 parser; the verified-contact flow is what proves an address.
 */
public final class ContactPlausibility {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$");
    /**
     * JavaScript's {@code \s} / {@code String.prototype.trim} set (WhiteSpace plus LineTerminator), spelled out so
     * core accepts exactly what the checkout's {@code /\s/} accepts. Java's own {@code \s} is ASCII only, and
     * {@code (?U)} / {@code Character.isWhitespace} would differ in the other direction (they include U+0085 and
     * exclude U+00A0, U+2007 and U+202F), so neither is used. See {@code ContactPlausibilityWhitespaceParityTest}.
     */
    private static final String JS_WHITESPACE =
            "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern PHONE_CHARS = Pattern.compile("^\\+?[0-9" + JS_WHITESPACE + "().-]+$");
    private static final Pattern JS_TRIM = Pattern.compile("^[" + JS_WHITESPACE + "]+|[" + JS_WHITESPACE + "]+$");

    private ContactPlausibility() {
    }

    /** At least two letters (any script); trimmed, so padding and punctuation alone do not count. */
    public static boolean isPlausibleName(String name) {
        if (name == null) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isLetter(cp)) {
                letters++;
            }
            i += Character.charCount(cp);
        }
        return letters >= 2;
    }

    public static boolean isPlausibleEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email.trim()).matches();
    }

    public static boolean isPlausiblePhone(String phone) {
        return normalisePhone(phone) != null;
    }

    /**
     * The E.164 form ({@code +447700900123}) of a plausible phone number, or {@code null} when it is not one.
     */
    public static String normalisePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = JS_TRIM.matcher(phone).replaceAll("");
        if (trimmed.isEmpty() || !PHONE_CHARS.matcher(trimmed).matches()) {
            return null;
        }
        boolean plus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.isEmpty() || allSameDigit(digits)) {
            return null;
        }
        if (!plus && digits.startsWith("00")) { // 0044..., 00353...: international dialling prefix
            digits = digits.substring(2);
            plus = true;
        }
        if (plus) {
            if (digits.startsWith("44")) {
                String national = digits.substring(2);
                if (national.startsWith("0")) { // +44 (0)7700 900123: the trunk zero is not dialled
                    national = national.substring(1);
                }
                boolean mobile = national.startsWith("7"); // UK mobiles are 07 + 9 digits, always
                return national.length() >= 9 && national.length() <= 10 && !national.startsWith("0")
                        && (!mobile || national.length() == 10)
                        ? "+44" + national : null;
            }
            return digits.length() >= 8 && digits.length() <= 15 && digits.charAt(0) != '0' ? "+" + digits : null;
        }
        // National form: a UK number is 10 or 11 digits starting with the trunk 0.
        boolean ukMobile = digits.startsWith("07");
        if (digits.startsWith("0") && (digits.length() == 10 || digits.length() == 11) && digits.charAt(1) != '0'
                && (!ukMobile || digits.length() == 11)) {
            return "+44" + digits.substring(1);
        }
        return null;
    }

    private static boolean allSameDigit(String digits) {
        char first = digits.charAt(0);
        for (int i = 1; i < digits.length(); i++) {
            if (digits.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }
}
