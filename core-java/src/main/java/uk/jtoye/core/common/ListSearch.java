package uk.jtoye.core.common;

import java.util.Locale;

/**
 * A vendor's free-text search term for an admin list (orders, customers), turned into the LIKE
 * patterns the repository queries bind (issue #822).
 *
 * <p>The dashboards used to filter the ONE page they had loaded in the browser, so an order on
 * page three was simply not found. The search now runs in the query, over every page, inside the
 * caller's tenant (RLS) and shop grant set (the service decides which finder to call). This class
 * only shapes the term; it never decides who may see what.
 *
 * <p>Two properties matter for safety:
 * <ul>
 *   <li><b>The term is data, never a pattern.</b> {@code %}, {@code _} and {@code \} typed by the
 *       user are escaped, so {@code q=%} finds a literal percent sign instead of every row.</li>
 *   <li><b>The term is bounded.</b> Anything past {@link #MAX_LENGTH} characters is cut, so a
 *       pasted megabyte cannot become a megabyte LIKE pattern.</li>
 * </ul>
 *
 * @param text           the lower-cased, escaped, %-wrapped pattern for case-insensitive
 *                       {@code lower(column) LIKE :text ESCAPE '\'} matches
 * @param phone          the term reduced to DIGITS with a UK country code folded to a leading 0, %-wrapped,
 *                       for matching a stored phone reduced the same way IN SQL (the repositories'
 *                       {@code regexp_replace}): {@code +44 7700 900123}, {@code 07700-900-123} and
 *                       {@code (07700) 900123} are one number
 * @param searchesPhone  true only when the term LOOKS like a phone number (digits, spaces and + - ( ) .
 *                       only, at least 3 digits); a word or an order number never consults the phone column
 */
public record ListSearch(String text, String phone, boolean searchesPhone) {

    /** The longest term honoured; a longer one is cut. */
    public static final int MAX_LENGTH = 100;

    /** The escape character the repositories declare ({@code ESCAPE '\'}). */
    private static final char ESCAPE = '\\';

    /**
     * @return the search for {@code raw}, or {@code null} when it is null or blank - the caller
     *         then runs its ordinary unfiltered finder, so an absent {@code q} costs nothing
     */
    public static ListSearch of(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH).strip();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String digits = lower.replaceAll("[^0-9]", "");
        boolean looksLikePhone = PHONE_CHARS.matcher(lower).matches() && digits.length() >= 3;
        return new ListSearch("%" + escape(lower) + "%", "%" + foldCountryCode(digits) + "%", looksLikePhone);
    }

    /** What a phone-number term may be made of; anything else is a word, not a number. */
    private static final java.util.regex.Pattern PHONE_CHARS = java.util.regex.Pattern.compile("[0-9+().\\-\\s]+");

    /**
     * {@code 447700900123} and {@code 4407700900123} (a +44 term, with or without the trunk 0 in brackets)
     * become {@code 07700900123}. Folded only for a full-length term (11+ digits): a short fragment such as
     * {@code 4477} is a slice of some number, and rewriting it would make it match something else. The SQL
     * side applies {@code ^440?} to the whole stored number, so both sides meet at the national form.
     */
    static String foldCountryCode(String digits) {
        return digits.length() >= 11 ? digits.replaceFirst("^440?", "0") : digits;
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                out.append(ESCAPE);
            }
            out.append(c);
        }
        return out.toString();
    }
}
