package uk.jtoye.core.storefront;

import uk.jtoye.core.geo.PostcodeGeocoder;

/**
 * The server's own statement about how it read a {@code q} (33-08 / #619).
 *
 * <p>Not yet implemented — see the RED commit that introduced this signature.
 */
public record SearchInterpretation(Kind kind, String postcode,
                                   PostcodeGeocoder.Precision precision, Double radiusKm) {

    /** The response header this interpretation is published on. Declared once, here. */
    public static final String HEADER = "X-Search-Interpretation";

    /** How the server read the search term. */
    public enum Kind {
        /** The term was answered as text — full-text search or the LIKE fallback. */
        TEXT,
        /** The term was read as a place, and the results are ordered by distance from it. */
        PROXIMITY
    }

    public static SearchInterpretation text() {
        return new SearchInterpretation(Kind.TEXT, null, null, null);
    }

    public static SearchInterpretation proximity(String key, PostcodeGeocoder.Precision precision,
                                                 double radiusKm) {
        return new SearchInterpretation(Kind.PROXIMITY, key, precision, radiusKm);
    }

    public String headerValue() {
        return null;
    }
}
