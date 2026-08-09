package uk.jtoye.core.storefront;

import uk.jtoye.core.geo.PostcodeGeocoder;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The server's own statement about how it read a {@code q} on {@code GET /public/shops}
 * (33-08 / #619), published as the {@value #HEADER} response header.
 *
 * <h2>Why the server asserts this, rather than the client inferring it</h2>
 *
 * <p>Because only the server knows. A client that re-derives "that looked like a postcode, so
 * these must be nearby kitchens" is guessing at a decision it did not make — and the guess is
 * wrong every time the postcode was not in Code-Point Open, or a shop literally named
 * "SE22 Kitchen" won the text search. That is the row-lying-about-itself failure class this
 * phase exists to close, so the disclosure travels FROM the code that made the choice.
 *
 * <p>Inferring proximity from {@code distanceKm != null} on the returned shops fails for the
 * same reason but worse: it fails on the EMPTY page, which is exactly where honesty matters
 * most. "No kitchens within 3.1 miles of SE22" and "no kitchens match 'SE22'" are different
 * sentences, and a zero-result page carries no shop to hang the flag on.
 *
 * <h2>The grammar is a PUBLISHED CONTRACT</h2>
 *
 * <pre>
 * X-Search-Interpretation: text
 * X-Search-Interpretation: proximity; postcode=SE22;    precision=district; radiusKm=5.0
 * X-Search-Interpretation: proximity; postcode=SE155BS; precision=unit;     radiusKm=5.0
 * </pre>
 *
 * <p>(Rendered without the alignment padding: single spaces after each {@code ;}.)
 *
 * <p>It is consumed by 33-09's {@code frontend/lib/search-interpretation.ts}. <strong>Any change
 * to these tokens is a breaking change to that module</strong>, not a cosmetic edit — change the
 * parser in the same commit or do not change the grammar.
 *
 * <p>Emitted on {@code ?q=} responses only. Absent from the plain listing and from the
 * {@code lat}/{@code lon} distance path: the header answers "how did you read my q?", and with
 * no {@code q} there is no question to answer.
 *
 * <h2>What is deliberately NOT in it</h2>
 *
 * <p><strong>No coordinate, ever</strong> (T-33-08-04). A response header lands in proxy and
 * access logs, and 33-06 established that coordinates do not reach a log on this platform. The
 * postcode DOES appear, and that is an accepted trade rather than an oversight: it is already in
 * the request URI's query string, so the header opens no sink that the access log did not
 * already have.
 */
public record SearchInterpretation(Kind kind, String postcode,
                                   PostcodeGeocoder.Precision precision, Double radiusKm) {

    /** The response header this interpretation is published on. Declared once, here. */
    public static final String HEADER = "X-Search-Interpretation";

    /** The whole header value for a text search — one token, no parameters. */
    private static final String TEXT_VALUE = "text";

    /**
     * The only key shape allowed into a header value (T-33-08-05).
     *
     * <p>Response-splitting control. The key originates in customer-supplied {@code q}, and
     * {@link PostcodeGeocoder} already uppercases it and constrains it by regex — so this is
     * defence in depth, placed at the sink rather than only at the source, because the sink is
     * what a future caller will reuse. CR, LF and {@code ;} are the injection alphabet for a
     * header value and none of them can pass {@code [A-Z0-9]}.
     */
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Z0-9]{2,8}$");

    /** How the server read the search term. */
    public enum Kind {
        /** The term was answered as text — full-text search, or the LIKE fallback. */
        TEXT,
        /** The term was read as a place, and the results are ordered by distance from it. */
        PROXIMITY
    }

    /** The term was answered as text. Carries no postcode, precision or radius. */
    public static SearchInterpretation text() {
        return new SearchInterpretation(Kind.TEXT, null, null, null);
    }

    /**
     * The term was read as a place.
     *
     * @param key       the NORMALISED postcode key — uppercase, space-stripped
     * @param precision whether {@code key} is a full unit or an outward code
     * @param radiusKm  the radius actually applied, so the customer-facing copy can be derived
     *                  from the number that was really used rather than a second literal
     */
    public static SearchInterpretation proximity(String key, PostcodeGeocoder.Precision precision,
                                                 double radiusKm) {
        return new SearchInterpretation(Kind.PROXIMITY, key, precision, radiusKm);
    }

    /**
     * Render this interpretation as the {@value #HEADER} value.
     *
     * <p>Degrades to {@value #TEXT_VALUE} rather than throwing or emitting a partial claim: an
     * incomplete disclosure is not a disclosure, and a proximity claim the caller cannot fully
     * parse is worse than no claim at all. Never returns {@code null} — a null header value is
     * a container-specific behaviour (dropped, or the literal string "null") and neither is a
     * contract.
     */
    public String headerValue() {
        if (kind != Kind.PROXIMITY || postcode == null || precision == null || radiusKm == null) {
            return TEXT_VALUE;
        }
        if (!SAFE_KEY.matcher(postcode).matches()) {
            return TEXT_VALUE;
        }
        return "proximity; postcode=" + postcode
                + "; precision=" + precision.name().toLowerCase(Locale.ROOT)
                + "; radiusKm=" + String.valueOf(radiusKm.doubleValue());
    }
}
