package uk.jtoye.core.common;

/**
 * The one Java rule for ending a sentence with a value (G-37-6 defect 2, 37-57).
 *
 * <p><b>Why.</b> A name a vendor or a person typed can already end a sentence. The staff invitation
 * read "as Staff for Peckham Jollof Co.." in Mailhog because its template put its own full stop after
 * the shop's name. Every email sentence that ends with such a value ends it through
 * {@link #withFullStop} (or, where markup renders the value in its own element, {@link #fullStopAfter}),
 * so the rule is decided here once and not at each call site.
 *
 * <p><b>Convention</b> (British English): an abbreviation's full stop also ends the sentence, and so does
 * a closing "!", "?" or "…". A closing quote or bracket does NOT end it: the full stop goes after it.
 * Only the last character decides. Nothing else is removed, rewritten or interpreted (markup-like text
 * stays text), and a value that already ends in two full stops is returned as it was: the rule never
 * repairs input.
 *
 * <p><b>Trailing whitespace</b>, dropped before deciding, is exactly the JavaScript whitespace class:
 * every code unit for which {@link Character#isSpaceChar(char)} is true (Zs, Zl, Zp), plus 0x09-0x0D
 * and 0xFEFF, 25 code units in all. It is NOT {@link Character#isWhitespace(char)}, which also drops
 * 0x1C-0x1F and keeps the no-break spaces and 0xFEFF, so the two languages would disagree on a name
 * ending in one of those.
 *
 * <p><b>In email code</b>, apply it AFTER {@code EmailNotificationService.sanitise}: control
 * characters (the CR/LF header-injection class, T-31.1-85) are gone by then, and this rule adds at most
 * one full stop.
 *
 * <p><b>Mirror.</b> {@code frontend/lib/sentence-end.ts} is the TypeScript rule (37-56).
 * {@code SentencesTest} reads its {@code SENTENCE_TERMINATORS} and {@code TRAILING_SPACE_CODE_POINTS}
 * declarations and holds both equal to this class, so the two cannot drift.
 */
public final class Sentences {

    /** What already ends a sentence: full stop, exclamation mark, question mark, ellipsis. */
    public static final String TERMINATORS = ".!?…";

    private Sentences() {
    }

    /** True when the text, trailing whitespace removed, already ends with one of {@link #TERMINATORS}. */
    public static boolean endsSentence(String text) {
        String trimmed = trimEnd(text);
        return !trimmed.isEmpty() && TERMINATORS.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    /** The text with trailing whitespace removed, followed by one full stop unless it already ends a sentence. */
    public static String withFullStop(String text) {
        String trimmed = trimEnd(text);
        return endsSentence(trimmed) ? trimmed : trimmed + ".";
    }

    /**
     * "" when the text already ends a sentence, otherwise ".": for markup that renders the value inside
     * its own element and the full stop after it.
     */
    public static String fullStopAfter(String text) {
        return endsSentence(text) ? "" : ".";
    }

    private static String trimEnd(String text) {
        int end = text.length();
        while (end > 0 && isTrailingSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** The JavaScript whitespace class: Zs, Zl, Zp, plus 0x09-0x0D and 0xFEFF. */
    private static boolean isTrailingSpace(char c) {
        return Character.isSpaceChar(c) || (c >= 0x09 && c <= 0x0D) || c == 0xFEFF;
    }
}
