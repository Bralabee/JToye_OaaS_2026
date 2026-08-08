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

    /** Longest possible normalised key ({@code XX99XXX}); the column is {@code length = 8}. */
    private static final int MAX_KEY_LENGTH = 8;

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
     * A WGS84 point. Never constructed for an unresolved address — the absence of a coordinate
     * is represented by an empty {@link Optional}, never by a sentinel value.
     */
    public record Coordinate(double latitude, double longitude) {
    }
}
