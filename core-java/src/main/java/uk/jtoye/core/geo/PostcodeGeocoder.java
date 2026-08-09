package uk.jtoye.core.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a free-text UK address into a WGS84 coordinate, offline.
 *
 * <p>The single postcode-to-coordinate implementation on the platform: the API write path
 * (33-05) and the dev seeder share it, so a shop created through the UI and a shop created by
 * the seeder cannot disagree about where they are.
 *
 * <h2>The table is the authority, not the regex</h2>
 *
 * <p>This is the design decision worth defending, because the obvious alternative — validate
 * with a "correct" UK postcode regex, or a validation library — is wrong in both directions
 * at once, and this repo has a live example of each:
 *
 * <ul>
 *   <li><strong>It accepts things that do not exist.</strong> {@code SE15 4QA} appears in this
 *       repo's own seeded demo data, matches every plausible UK-postcode pattern, and is not a
 *       real postcode — {@code api.postcodes.io} returns 404 for it (checked 2026-08-08). A
 *       regex-validating geocoder accepts it and then has to invent a coordinate.</li>
 *   <li><strong>It rejects things that do exist.</strong> A pattern that disagrees with the
 *       dataset turns away real vendors at signup, and the vendor has no way to argue with
 *       it.</li>
 * </ul>
 *
 * <p>So the regex here is deliberately <em>permissive</em>: it only has to find a candidate at
 * the end of the string. The primary-key lookup then decides. A hit is proof the postcode is
 * real; a miss is proof it is not.
 *
 * <h2>Failure is always empty</h2>
 *
 * <p>{@link #locate(String)} never throws and never returns {@code (0,0)}. Null Island is the
 * specific hazard: a shop at {@code (0,0)} is roughly 5,800 km from London but nearer to the
 * origin than any real shop, so under a distance sort it becomes <em>the nearest kitchen to
 * every customer on the platform</em>. The dataset filters those rows out (879 of them in the
 * 2026-08 release); this class makes sure the code path cannot reintroduce one.
 */
@Service
public class PostcodeGeocoder {

    private static final Logger log = LoggerFactory.getLogger(PostcodeGeocoder.class);

    /**
     * Permissive trailing-postcode extractor.
     *
     * <p>Anchored to the end of the string, with bounded quantifiers and no nested repetition,
     * because the input is untrusted vendor text and a pattern like {@code (\w+\s*)+$} is a
     * catastrophic-backtracking denial of service. Every quantifier below has a small explicit
     * upper bound, so matching is linear in the input length.
     *
     * <p>Group 1 is the outward code ({@code SE15}), group 2 the inward code ({@code 5BS}).
     * Deliberately looser than the real UK grammar — correctness is the table's job.
     */
    private static final Pattern TRAILING_POSTCODE = Pattern.compile(
            "([A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?)\\s{0,4}([0-9][A-Za-z]{2})\\s{0,8}$");

    /**
     * Permissive whole-term postcode matcher for the customer SEARCH box (33-08 / #619).
     *
     * <p>Two things make it different from {@link #TRAILING_POSTCODE}, and both are load-bearing:
     *
     * <ul>
     *   <li><strong>Anchored at BOTH ends.</strong> {@code TRAILING_POSTCODE} only has to find a
     *       postcode at the end of an address line. A search term is not an address: if the
     *       customer typed anything else at all — {@code "SE22 pizza"}, {@code "x SE15 5BS"} —
     *       they are searching for words, and answering with a map of a district would be the
     *       platform deciding it knew better. Both-ends anchoring is what makes that a text
     *       search instead of a proximity one.</li>
     *   <li><strong>The inward code is OPTIONAL.</strong> {@code SE22} on its own is the whole
     *       point of #619; {@code TRAILING_POSTCODE} requires the inward code and must keep
     *       requiring it, because it feeds the vendor write path.</li>
     * </ul>
     *
     * <p>Group 1 is the outward code ({@code SE15}), group 2 the optional inward code
     * ({@code 5BS}). Every quantifier is explicitly bounded and there is no nested repetition,
     * so matching is linear (T-33-08-01) — but the real denial-of-service control is
     * {@link #MAX_SEARCH_TERM_LENGTH}, applied before the matcher ever runs.
     */
    private static final Pattern SEARCH_POSTCODE = Pattern.compile(
            "^\\s{0,4}([A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?)(?:\\s{0,4}([0-9][A-Za-z]{2}))?\\s{0,4}$");

    /** Longest possible normalised key ({@code XX99XXX}); the column is {@code length = 8}. */
    private static final int MAX_KEY_LENGTH = 8;

    /**
     * Longest search term that could possibly be a postcode: outward 4 + up to 4 spaces between
     * the codes + inward 3, with slack. Anything longer is refused BEFORE the regex, so the
     * matcher never sees an unbounded string from an anonymous caller (T-33-08-01).
     */
    private static final int MAX_SEARCH_TERM_LENGTH = 12;

    /** Lowest inward code in any district — Code-Point Open inward codes are digit-letter-letter. */
    private static final String LOWEST_INWARD = "0AA";

    /** Highest inward code in any district. */
    private static final String HIGHEST_INWARD = "9ZZ";

    private final PostcodeCentroidRepository repository;

    public PostcodeGeocoder(PostcodeCentroidRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolve an address to a coordinate.
     *
     * @param address free-text address, may be {@code null} or malformed
     * @return the centroid of the address's postcode unit, or empty if no postcode could be
     *         extracted or the extracted postcode is not in the dataset. Never {@code (0,0)},
     *         never an exception.
     */
    public Optional<Coordinate> locate(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = TRAILING_POSTCODE.matcher(address);
        if (!matcher.find()) {
            log.debug("No extractable postcode in address, cannot geocode");
            return Optional.empty();
        }

        String key = (matcher.group(1) + matcher.group(2)).toUpperCase(Locale.ROOT);
        if (key.length() > MAX_KEY_LENGTH) {
            return Optional.empty();
        }

        Optional<Coordinate> located = repository.findById(key)
                .map(row -> new Coordinate(row.getLatitude(), row.getLongitude()));

        if (located.isEmpty()) {
            // WARN, and name the postcode rather than the whole address line: the postcode is
            // what an operator needs to act on, and the rest of the address is customer- or
            // vendor-identifying detail that does not belong in a log.
            log.warn("Postcode '{}' is not in the Code-Point Open dataset — address not geocoded. "
                    + "This is expected for Northern Ireland (the dataset is GB-only) and for "
                    + "postcodes that do not exist.", key);
        }
        return located;
    }

    /**
     * Resolve a customer's SEARCH TERM to a coordinate (33-08 / #619).
     *
     * <p>The second entry point on this class, and deliberately not a loosening of
     * {@link #locate(String)}. The two answer different questions from different callers:
     * {@code locate} reads a VENDOR's address line on the write path, where a district centroid
     * would be a silent ~1 km error stamped onto a shop forever; this reads a CUSTOMER's search
     * box, where a district centroid is exactly the right answer to {@code "SE22"}. Their
     * disagreement about the term {@code "SE15"} — empty there, present here — is asserted
     * permanently in {@code PostcodeGeocoderTest}.
     *
     * <h2>The table is the authority, not the regex</h2>
     *
     * <p>Unchanged from {@code locate}, and worth restating because this method has two lookups
     * instead of one. {@link #SEARCH_POSTCODE} only NOMINATES a candidate; a row in
     * {@code postcode_centroid} is what decides. {@code ZZ99 9ZZ} matches the pattern perfectly
     * and resolves to nothing, and that is the correct outcome — the alternative is inventing a
     * coordinate and quietly showing the customer kitchens that are nowhere near them.
     *
     * <h2>Unit first, then district</h2>
     *
     * <p>A full unit is tried by primary key. If the unit is absent the OUTWARD code is tried,
     * which is what turns this repo's permanent negative control {@code SE15 4QA} — well-formed,
     * in our own seeded demo data, and not in Code-Point Open — from zero kitchens into the
     * kitchens around SE15.
     *
     * @param term the raw {@code q} a customer typed; untrusted, may be {@code null}
     * @return where the term points, its normalised key and how precise that is, or empty if the
     *         term is not a postcode or names no postcode in the dataset. Never {@code (0,0)},
     *         never an exception.
     */
    public Optional<LocatedPostcode> locateSearchTerm(String term) {
        if (term == null || term.isBlank() || term.length() > MAX_SEARCH_TERM_LENGTH) {
            // Length is checked HERE, before the matcher and before either lookup: this is the
            // DoS control on an anonymous endpoint, and a bounded regex alone is not one.
            return Optional.empty();
        }

        Matcher matcher = SEARCH_POSTCODE.matcher(term);
        if (!matcher.matches()) {
            // Not a postcode-shaped term at all. Not a miss worth logging — this is the ordinary
            // case for every food search on the platform.
            return Optional.empty();
        }

        String outward = matcher.group(1).toUpperCase(Locale.ROOT);
        String inward = matcher.group(2);

        if (inward != null) {
            String key = (outward + inward).toUpperCase(Locale.ROOT);
            if (key.length() <= MAX_KEY_LENGTH) {
                Optional<LocatedPostcode> unit = repository.findById(key)
                        .map(row -> new LocatedPostcode(
                                new Coordinate(row.getLatitude(), row.getLongitude()),
                                key, Precision.UNIT));
                if (unit.isPresent()) {
                    return unit;
                }
            }
            // Fall through to the district: the customer named a real area even if that exact
            // unit does not exist.
        }

        return locateDistrict(outward);
    }

    /**
     * Mean centroid of every unit under one outward code, or empty.
     *
     * <p>The bounds are computed here rather than in SQL so the predicate stays a plain
     * comparison the planner can push at the primary-key index — see the measured EXPLAIN on
     * {@link PostcodeCentroidRepository#findDistrictCentroid}. The inward code is always
     * digit-letter-letter, so {@code 0AA} and {@code 9ZZ} are the true inclusive bounds and the
     * range needs no successor arithmetic.
     */
    private Optional<LocatedPostcode> locateDistrict(String outward) {
        String rangeStart = outward + LOWEST_INWARD;
        String rangeEnd = outward + HIGHEST_INWARD;
        int unitLength = outward.length() + LOWEST_INWARD.length();

        DistrictCentroid district = repository.findDistrictCentroid(rangeStart, rangeEnd, unitLength);

        // THE AGGREGATE TRAP. This projection is never null: avg() with no GROUP BY returns one
        // row of NULLs when nothing matched. Gate on the count AND on both coordinates, or the
        // NULLs unbox to (0,0) — Null Island, which under a distance sort is the nearest kitchen
        // to every customer on the platform.
        if (district == null || district.getUnits() <= 0
                || district.getLatitude() == null || district.getLongitude() == null) {
            // WARN, and name the NORMALISED KEY only — never the raw term. The term is arbitrary
            // customer text and does not belong in a log; the key is [A-Z0-9]{2,8} by
            // construction. Same rule as locate().
            log.warn("Postcode district '{}' is not in the Code-Point Open dataset — search term "
                    + "not resolved to a location. This is expected for Northern Ireland (the "
                    + "dataset is GB-only) and for districts that do not exist.", outward);
            return Optional.empty();
        }

        return Optional.of(new LocatedPostcode(
                new Coordinate(district.getLatitude(), district.getLongitude()),
                outward, Precision.DISTRICT));
    }

    /**
     * A WGS84 point. Never constructed for an unresolved address — the absence of a coordinate
     * is represented by an empty {@link Optional}, never by a sentinel value.
     */
    public record Coordinate(double latitude, double longitude) {
    }

    /** How precisely a search term was resolved. */
    public enum Precision {
        /** A full postcode unit: a primary-key hit, ~100 m. */
        UNIT,
        /** An outward code: the mean of every unit in that district, ~1 km. */
        DISTRICT
    }

    /** A resolved search term: where it is, the normalised key, and how precise that is. */
    public record LocatedPostcode(Coordinate coordinate, String key, Precision precision) {
    }
}
