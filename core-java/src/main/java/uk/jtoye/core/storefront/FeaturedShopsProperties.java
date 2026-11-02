package uk.jtoye.core.storefront;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The featured kitchens of THIS environment (quick 261102-r6u, owner decision D-02, design D17): an
 * ordered list of shop slugs bound from {@code jtoye.storefront.featured-shop-slugs}
 * ({@code JTOYE_FEATURED_SHOP_SLUGS}, comma-separated). The landing row leads with them before a
 * visitor shares a location. It is an editorial, per-environment ordering hint and never paid
 * placement; staging lists its demo kitchens and production ships it empty.
 *
 * <p>Validated HERE, at startup, so a typo stops core-java with a message naming the key rather than
 * silently featuring nothing for months: a blank entry (as in {@code a,,b} or a trailing comma), a
 * duplicate, an entry outside the {@code CreateShopRequest} slug shape, or more than
 * {@link #MAX_FEATURED} entries is refused. Entries are trimmed first, because a YAML list is not.
 * Slugs are configuration, never personal data, so naming the offending entry in the message is safe;
 * the slug shape also excludes CR and LF, so a configured value cannot inject a log line (T-r6u-02).
 *
 * <p>An absent or empty setting binds to an empty list: the inert state, in which the landing row is
 * exactly what it was before this feature existed.
 */
@ConfigurationProperties(prefix = "jtoye.storefront")
public record FeaturedShopsProperties(List<String> featuredShopSlugs) {

    /** The property this record binds, as an operator would search for it. */
    public static final String PROPERTY = "jtoye.storefront.featured-shop-slugs";

    /**
     * The most kitchens that can be featured. It bounds the one {@code IN} query behind the public
     * endpoint and the landing row it leads: the row's first page is 8 cards, so 12 already pushes
     * every unfeatured kitchen off the first screen on a phone.
     */
    public static final int MAX_FEATURED = 12;

    /** The slug shape {@code CreateShopRequest} enforces: lower-case letters, digits, single hyphens. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final int MAX_SLUG_LENGTH = 100;

    public FeaturedShopsProperties {
        featuredShopSlugs = validate(featuredShopSlugs);
    }

    private static List<String> validate(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // A single blank value is the empty setting (an env var set to ""), not a blank entry.
        if (raw.size() == 1 && (raw.get(0) == null || raw.get(0).isBlank())) {
            return List.of();
        }
        if (raw.size() > MAX_FEATURED) {
            throw new IllegalStateException(PROPERTY + " lists " + raw.size()
                    + " slugs; at most " + MAX_FEATURED + " kitchens can be featured");
        }
        List<String> slugs = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String entry = raw.get(i) == null ? "" : raw.get(i).trim();
            if (entry.isEmpty()) {
                throw new IllegalStateException(PROPERTY + " has a blank entry at position " + (i + 1)
                        + " (two commas together, or a trailing comma)");
            }
            if (entry.length() > MAX_SLUG_LENGTH || !SLUG.matcher(entry).matches()) {
                throw new IllegalStateException(PROPERTY + " entry '" + sanitise(entry)
                        + "' is not a shop slug (lower-case letters, digits and single hyphens, at most "
                        + MAX_SLUG_LENGTH + " characters)");
            }
            if (!seen.add(entry)) {
                throw new IllegalStateException(PROPERTY + " lists '" + entry + "' twice");
            }
            slugs.add(entry);
        }
        return List.copyOf(slugs);
    }

    /** A refused entry is echoed without control characters, so the startup message stays one line. */
    private static String sanitise(String entry) {
        String printable = entry.replaceAll("\\p{Cntrl}", "?");
        return printable.length() > MAX_SLUG_LENGTH ? printable.substring(0, MAX_SLUG_LENGTH) + "..." : printable;
    }
}
