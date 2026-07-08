package uk.jtoye.core.product;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, fail-soft parser that turns vendor ingredients text carrying the
 * {@code **allergen**} inline markup convention into a plain ingredients string
 * plus the emphasis (allergen) spans over it.
 *
 * <p>This is the SINGLE SOURCE OF TRUTH for the markup transform, used by both:
 * <ul>
 *   <li>the save path ({@link ProductService}) — which persists the parsed spans
 *       into {@code products.allergen_spans} as a cache; and</li>
 *   <li>the render path ({@code ProductLabelService.buildRenderModel}) — which
 *       RE-PARSES {@code ingredients_text} fresh at render time (authoritative),
 *       so stored offsets can never go stale relative to an edited text.</li>
 * </ul>
 *
 * <p>Markup rules (delimiter {@code **}, double asterisk, chosen because it never
 * collides with real ingredient punctuation like {@code ( ) , %}):
 * <ul>
 *   <li>Content between an opening {@code **} and the NEXT {@code **} is one
 *       emphasised run; the delimiters are stripped from the plainText.</li>
 *   <li>Pairing is left-to-right and non-nested: each {@code **} opens, the next
 *       {@code **} closes.</li>
 *   <li>An empty pair ({@code ****}) strips its delimiters and produces NO span.</li>
 *   <li>A dangling/unmatched {@code **} is fail-soft: it is kept LITERAL in the
 *       plainText and produces no span.</li>
 *   <li>A lone {@code *} is not a delimiter and is preserved literally.</li>
 *   <li>{@code null} input yields empty plainText; blank input is preserved
 *       verbatim. The parser NEVER throws on vendor input.</li>
 * </ul>
 */
public final class IngredientMarkupParser {

    private static final String DELIM = "**";

    private IngredientMarkupParser() {
    }

    /**
     * Parsed result: the ingredients text with markup stripped, plus the emphasis
     * spans (offsets into {@code plainText}). {@code spans} is immutable.
     */
    public record ParsedIngredients(String plainText, List<AllergenSpan> spans) {
    }

    /**
     * Parse {@code raw} into plainText + emphasis spans. Fail-soft: never throws.
     *
     * @param raw the vendor ingredients text (may contain {@code **...**} markup);
     *            may be null or blank
     * @return the plain ingredients text and the ordered allergen spans over it
     */
    public static ParsedIngredients parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ParsedIngredients(raw == null ? "" : raw, List.of());
        }

        StringBuilder plain = new StringBuilder(raw.length());
        List<AllergenSpan> spans = new ArrayList<>();
        int i = 0;
        int n = raw.length();

        while (i < n) {
            if (isDelimAt(raw, i)) {
                // Opening delimiter — locate the matching close.
                int close = raw.indexOf(DELIM, i + DELIM.length());
                if (close < 0) {
                    // Dangling/unmatched delimiter -> keep literal, fail-soft.
                    plain.append(DELIM);
                    i += DELIM.length();
                } else {
                    String content = raw.substring(i + DELIM.length(), close);
                    if (!content.isEmpty()) {
                        int start = plain.length();
                        plain.append(content);
                        spans.add(new AllergenSpan(start, plain.length()));
                    }
                    // Empty pair (****) -> delimiters stripped, no span.
                    i = close + DELIM.length();
                }
            } else {
                plain.append(raw.charAt(i));
                i++;
            }
        }

        return new ParsedIngredients(plain.toString(), List.copyOf(spans));
    }

    private static boolean isDelimAt(String s, int idx) {
        return idx + 1 < s.length() && s.charAt(idx) == '*' && s.charAt(idx + 1) == '*';
    }
}
