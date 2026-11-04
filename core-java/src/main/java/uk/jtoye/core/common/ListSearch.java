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
 * @param phone          the same term with whitespace removed, for matching a stored phone with its
 *                       spaces removed too ({@code 07700900123} finds {@code 07700 900 123})
 * @param searchesPhone  false when the term holds no digit, so the phone column is not consulted
 *                       (a word cannot be a phone number)
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
        String noSpaces = lower.replaceAll("\\s+", "");
        boolean hasDigit = noSpaces.chars().anyMatch(Character::isDigit);
        return new ListSearch("%" + escape(lower) + "%", "%" + escape(noSpaces) + "%", hasDigit);
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
