package uk.jtoye.core.review;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one rule for the reviewer name a PUBLIC review carries (37-35, RWO-044 / UXT-044, T-37-85): the
 * first name, a space, and the upper-cased first letter of the last name with a full stop.
 *
 * <pre>
 *   "Grace Pemberton"      -> "Grace P."
 *   "Grace Ann Pemberton"  -> "Grace P."      (middle names are dropped)
 *   "Mary-Jane O'Neil"     -> "Mary-Jane O."
 *   "grace pemberton"      -> "grace P."      (only the initial is upper-cased)
 *   "Pemberton, Grace"     -> "Grace P."      (the comma form is "Family, Given")
 *   "Grace (Pemberton)"    -> "Grace P."      (punctuation around a name is not part of it)
 *   "Grace"                -> "Grace"         (a single name is shown as given)
 *   "Pemberton,"           -> null            (a family name alone: nothing is published)
 *   "", "   ", null        -> null            (the storefront shows "Customer")
 * </pre>
 *
 * <p>Derived at READ time, never stored: the review row keeps the name the order carried, because an
 * Article 17 erasure scrubs it (V67) and an Article 15 export returns it, and neither may work from a
 * truncated copy. Names are split on any Unicode white space ({@code (?U)\s}), not only ASCII, so a tab
 * or a no-break space between two names cannot make them one token and publish the surname whole.
 * The initial is a code point, so a surname beginning outside the Basic Multilingual Plane keeps its
 * whole first character.
 *
 * <p>The output is only ever the first GIVEN name and one letter (PR #918 round 1): a comma marks the
 * "Family, Given" order, so the family name is never the token published whole, and each token is
 * trimmed of the punctuation around it, so the initial is a letter or digit and no trailing comma or
 * bracket rides along. Known residual, deliberately not guessed at: a surname-first name WITHOUT a
 * comma ("PEMBERTON Grace") cannot be told apart from "Given Family" in general and is shown as
 * "PEMBERTON G." — inventing a capitalisation heuristic would mis-handle every customer who types
 * their whole name in capitals.
 */
public final class ReviewerDisplayName {

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    private ReviewerDisplayName() {
    }

    /**
     * @param fullName the name as stored on the review (the order's customer name), possibly null
     * @return the public display form, or null when there is no name
     */
    public static String of(String fullName) {
        if (fullName == null) {
            return null;
        }
        int comma = fullName.indexOf(',');
        if (comma >= 0) {
            List<String> family = tokens(fullName.substring(0, comma));
            List<String> given = tokens(fullName.substring(comma + 1));
            if (given.isEmpty()) {
                // A family name and no given name: publishing it would publish the surname whole.
                return null;
            }
            if (family.isEmpty()) {
                return given.get(0);
            }
            return given.get(0) + " " + initialOf(family.get(0));
        }
        List<String> tokens = tokens(fullName);
        if (tokens.isEmpty()) {
            return null;
        }
        String first = tokens.get(0);
        if (tokens.size() == 1) {
            return first;
        }
        return first + " " + initialOf(tokens.get(tokens.size() - 1));
    }

    /** The white-space separated tokens of {@code part}, each trimmed of surrounding punctuation; empty ones dropped. */
    private static List<String> tokens(String part) {
        return Arrays.stream(WHITESPACE.split(part))
                .map(ReviewerDisplayName::trimPunctuation)
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /** {@code token} without the non-letter, non-digit code points at either end ("(Pemberton)" -> "Pemberton"). */
    private static String trimPunctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && !Character.isLetterOrDigit(token.codePointAt(start))) {
            start += Character.charCount(token.codePointAt(start));
        }
        while (end > start && !Character.isLetterOrDigit(token.codePointBefore(end))) {
            end -= Character.charCount(token.codePointBefore(end));
        }
        return token.substring(start, end);
    }

    /** The upper-cased first code point and a full stop; the token is non-empty and starts with a letter or digit. */
    private static String initialOf(String token) {
        return Character.toString(Character.toUpperCase(token.codePointAt(0))) + ".";
    }
}
