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
 *   "Grace"                -> "Grace"         (a single name is shown as given)
 *   "", "   ", null        -> null            (the storefront shows "Customer")
 * </pre>
 *
 * <p>Derived at READ time, never stored: the review row keeps the name the order carried, because an
 * Article 17 erasure scrubs it (V67) and an Article 15 export returns it, and neither may work from a
 * truncated copy. Names are split on any Unicode white space ({@code (?U)\s}), not only ASCII, so a tab
 * or a no-break space between two names cannot make them one token and publish the surname whole.
 * The initial is a code point, so a surname beginning outside the Basic Multilingual Plane keeps its
 * whole first character.
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
        List<String> tokens = Arrays.stream(WHITESPACE.split(fullName))
                .filter(token -> !token.isEmpty())
                .toList();
        if (tokens.isEmpty()) {
            return null;
        }
        String first = tokens.get(0);
        if (tokens.size() == 1) {
            return first;
        }
        int initial = Character.toUpperCase(tokens.get(tokens.size() - 1).codePointAt(0));
        return first + " " + Character.toString(initial) + ".";
    }
}
