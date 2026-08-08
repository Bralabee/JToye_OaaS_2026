package uk.jtoye.core.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The geocoder's contract, driven by REAL Code-Point Open centroids loaded from
 * {@code src/test/resources/geo/postcode-centroids-fixture.csv} — not by invented
 * numbers, which would only ever compare the fixture to itself.
 *
 * <p>The central design claim under test is that <strong>the table is the authority,
 * not a regex</strong>. {@code SE15 4QA} is the proof: it is in this repo's seeded demo
 * data, it satisfies every plausible UK-postcode pattern, and it does not exist —
 * {@code api.postcodes.io} returns 404 for it while returning 200 for {@code SE15 5BS}
 * (checked 2026-08-08). Any implementation that validates with a regex accepts it and
 * then has to invent a coordinate; this one returns empty.
 */
class PostcodeGeocoderTest {

    /**
     * Independent reference for SE15 5BS — ONSPD-derived, via api.postcodes.io, fetched
     * 2026-08-08. Deliberately NOT read from the fixture: an accuracy assertion whose
     * expected value comes from the thing under test cannot fail.
     */
    private static final double SE15_5BS_REF_LAT = 51.472436;
    private static final double SE15_5BS_REF_LON = -0.070022;

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private Map<String, PostcodeCentroid> fixture;
    private PostcodeGeocoder geocoder;

    @BeforeEach
    void setUp() throws Exception {
        fixture = loadFixture();
        PostcodeCentroidRepository repository = mock(PostcodeCentroidRepository.class);
        when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(fixture.get(inv.getArgument(0, String.class))));
        geocoder = new PostcodeGeocoder(repository);
    }

    private static Map<String, PostcodeCentroid> loadFixture() throws Exception {
        Map<String, PostcodeCentroid> rows = new HashMap<>();
        try (InputStream in = PostcodeGeocoderTest.class.getResourceAsStream("/geo/postcode-centroids-fixture.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",");
                rows.put(parts[0], new PostcodeCentroid(
                        parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            }
        }
        return rows;
    }

    /** Great-circle distance in metres — the only honest way to state "within 10 m". */
    private static double metresBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ---- The fixture's own invariant -------------------------------------------------

    @Test
    @DisplayName("the fixture does NOT contain SE15 4QA — the phase's permanent negative control")
    void fixtureOmitsTheNonExistentPostcode() {
        // Asserted rather than assumed: adding SE154QA to the fixture would turn the
        // unknown-postcode test below green by making the product wrong. This is the
        // guard that makes that impossible to do quietly.
        assertThat(fixture).doesNotContainKey("SE154QA");
    }

    // ---- Resolution ------------------------------------------------------------------

    @Test
    @DisplayName("a real postcode resolves to within 10 m of an INDEPENDENT reference")
    void resolvesRealPostcodeAccurately() {
        Optional<PostcodeGeocoder.Coordinate> located =
                geocoder.locate("48 Rye Lane, Peckham, London SE15 5BS");

        assertThat(located).isPresent();
        double error = metresBetween(
                located.get().latitude(), located.get().longitude(),
                SE15_5BS_REF_LAT, SE15_5BS_REF_LON);
        assertThat(error)
                .as("distance from the ONSPD reference for SE15 5BS")
                .isLessThan(10.0);
    }

    @ParameterizedTest
    @DisplayName("extraction is case- and space-insensitive: every spelling gives the same point")
    @ValueSource(strings = {
            "48 Rye Lane, Peckham, London SE15 5BS",
            "48 rye lane, peckham, london se15 5bs",
            "48 Rye Lane, Peckham, London SE155BS",
            "48 Rye Lane, Peckham, London SE15  5BS",
            "48 Rye Lane, Peckham, London se15   5bs   "
    })
    void normalisesEverySpellingToOneKey(String address) {
        Optional<PostcodeGeocoder.Coordinate> located = geocoder.locate(address);

        assertThat(located).isPresent();
        assertThat(located.get().latitude()).isEqualTo(51.472435);
        assertThat(located.get().longitude()).isEqualTo(-0.070047);
    }

    // ---- The failure modes that must NOT be (0,0) and must NOT throw -----------------

    @Test
    @DisplayName("SE15 4QA is well-formed but not real: EMPTY, never (0,0), never an exception")
    void wellFormedButNonExistentPostcodeYieldsEmpty() {
        Optional<PostcodeGeocoder.Coordinate> located =
                geocoder.locate("12 Bellenden Road, Peckham, London SE15 4QA");

        assertThat(located).isEmpty();
    }

    @Test
    @DisplayName("an address with no extractable postcode yields empty")
    void addressWithoutPostcodeYieldsEmpty() {
        assertThat(geocoder.locate("1 Probe Lane, London")).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("degenerate input returns empty without throwing")
    @ValueSource(strings = { "—", "", "   ", ",,,", "SE15", "5BS", "0000 000" })
    void degenerateInputYieldsEmpty(String address) {
        assertThat(geocoder.locate(address)).isEmpty();
    }

    @Test
    @DisplayName("null returns empty without throwing")
    void nullYieldsEmpty() {
        assertThat(geocoder.locate(null)).isEmpty();
    }

    @Test
    @DisplayName("a pathological long string does not hang the matcher (untrusted vendor text)")
    void pathologicalInputDoesNotCatastrophicallyBacktrack() {
        // The regex is anchored to the end with bounded quantifiers and no nested
        // repetition, so this is linear. A nested-quantifier pattern would hang here.
        String hostile = "A".repeat(50_000) + " 1111 111";

        long start = System.nanoTime();
        Optional<PostcodeGeocoder.Coordinate> located = geocoder.locate(hostile);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(located).isEmpty();
        assertThat(elapsedMs).as("regex evaluation time in ms").isLessThan(1_000L);
    }

    @Test
    @DisplayName("the other seeded demo postcode also resolves — one hit is not a pattern")
    void resolvesTheSecondDemoShop() {
        Optional<PostcodeGeocoder.Coordinate> located =
                geocoder.locate("Unit 5, Brixton Village, London SW9 8PS");

        assertThat(located).isPresent();
        double error = metresBetween(
                located.get().latitude(), located.get().longitude(),
                51.462621, -0.111782); // api.postcodes.io, 2026-08-08
        assertThat(error).isLessThan(10.0);
    }
}
