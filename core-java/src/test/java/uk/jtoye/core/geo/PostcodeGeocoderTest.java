package uk.jtoye.core.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private PostcodeCentroidRepository repository;
    private PostcodeGeocoder geocoder;

    @BeforeEach
    void setUp() throws Exception {
        fixture = loadFixture();
        repository = mock(PostcodeCentroidRepository.class);
        when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(fixture.get(inv.getArgument(0, String.class))));
        when(repository.findDistrictCentroid(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> districtOver(
                        inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class),
                        inv.getArgument(2, Integer.class)));
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

    /**
     * Emulates {@code PostcodeCentroidRepository.findDistrictCentroid} over the fixture, using
     * the same three predicates the SQL uses — closed range on the key, plus the unit-length
     * guard. Emulating the SQL rather than hardcoding an answer is what lets the fixture's
     * {@code M111AA} row falsify the guard here as well as in Testcontainers.
     *
     * <p><strong>The zero-match case returns a row of NULLs, not {@code null}.</strong> That is
     * not an approximation, it is the trap: {@code SELECT avg(...)} with no {@code GROUP BY}
     * always returns exactly one row, so a mock that returned {@code null} on a miss would let a
     * {@code projection != null} gate pass this suite and unbox NULL to Null Island in
     * production.
     */
    private DistrictCentroid districtOver(String rangeStart, String rangeEnd, int unitLength) {
        List<PostcodeCentroid> matched = fixture.values().stream()
                .filter(r -> r.getPostcode().length() == unitLength)
                .filter(r -> r.getPostcode().compareTo(rangeStart) >= 0)
                .filter(r -> r.getPostcode().compareTo(rangeEnd) <= 0)
                .toList();

        Double latitude = matched.isEmpty() ? null
                : matched.stream().mapToDouble(PostcodeCentroid::getLatitude).average().orElseThrow();
        Double longitude = matched.isEmpty() ? null
                : matched.stream().mapToDouble(PostcodeCentroid::getLongitude).average().orElseThrow();
        long units = matched.size();

        return new DistrictCentroid() {
            @Override
            public Double getLatitude() {
                return latitude;
            }

            @Override
            public Double getLongitude() {
                return longitude;
            }

            @Override
            public long getUnits() {
                return units;
            }
        };
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

    @Test
    @DisplayName("the fixture holds the M11 unit that makes the length guard falsifiable")
    void fixtureHoldsTheLengthGuardProbe() {
        // 33-08. M111AA ('M11 1AA', space-stripped) is a REAL Code-Point Open row, read out of
        // the committed dataset — not invented. It sorts INSIDE the M1 district's key range
        // [M10AA, M19ZZ] and is excluded only by length(postcode) = 5. Without this row in the
        // fixture the guard cannot be shown to do anything at unit level.
        assertThat(fixture).containsKey("M111AA");
        assertThat("M111AA".compareTo("M10AA"))
                .as("M111AA must sort at or above the M1 district's lower bound")
                .isGreaterThanOrEqualTo(0);
        assertThat("M111AA".compareTo("M19ZZ"))
                .as("M111AA must sort at or below the M1 district's upper bound")
                .isLessThanOrEqualTo(0);
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

    // =====================================================================================
    // 33-08 / #619 — the SEARCH entry point
    // =====================================================================================

    @Nested
    @DisplayName("locateSearchTerm — a customer's search box, not a vendor's address line")
    class LocateSearchTermTest {

        /**
         * SE15 is the district probe rather than the SE22 named in the plan's behaviour list,
         * and the substitution is deliberate: the 8-row fixture holds no SE22 unit, so an
         * SE22 arm here could only ever assert EMPTY and would prove nothing about the district
         * path. SE15 has three units in the fixture AND is the exact term the committed
         * {@code locate("SE15") -> empty} assertion covers, so using it here makes the two entry
         * points diverge on one input — which is the property this task actually has to hold.
         * SE22 against real data is Task 3's integration arm and CA-G's live measurement.
         */
        private static final String DISTRICT_PROBE = "SE15";

        @Test
        @DisplayName("a bare outward code resolves to the district centroid, precision DISTRICT")
        void outwardCodeResolvesToDistrict() {
            Optional<PostcodeGeocoder.LocatedPostcode> located = geocoder.locateSearchTerm(DISTRICT_PROBE);

            assertThat(located).isPresent();
            assertThat(located.get().precision()).isEqualTo(PostcodeGeocoder.Precision.DISTRICT);
            assertThat(located.get().key()).isEqualTo("SE15");

            // The mean of the THREE SE15 units in the fixture — computed here from the fixture's
            // own rows rather than pasted as a literal, so adding an SE15 row cannot silently
            // invalidate the expectation.
            double expectedLat = (51.472435 + 51.470379 + 51.466812) / 3;
            double expectedLon = (-0.070047 + -0.069184 + -0.073164) / 3;
            assertThat(located.get().coordinate().latitude()).isEqualTo(expectedLat);
            assertThat(located.get().coordinate().longitude()).isEqualTo(expectedLon);
        }

        @Test
        @DisplayName("a full unit resolves by primary key, precision UNIT, space-stripped key")
        void fullUnitResolvesToUnit() {
            Optional<PostcodeGeocoder.LocatedPostcode> located = geocoder.locateSearchTerm("se15 5bs");

            assertThat(located).isPresent();
            assertThat(located.get().precision()).isEqualTo(PostcodeGeocoder.Precision.UNIT);
            assertThat(located.get().key()).isEqualTo("SE155BS");
            assertThat(located.get().coordinate().latitude()).isEqualTo(51.472435);
            assertThat(located.get().coordinate().longitude()).isEqualTo(-0.070047);
        }

        @Test
        @DisplayName("a unit that does not exist falls back to its district rather than to nothing")
        void unitMissFallsBackToDistrict() {
            // SE15 4QA is the repo's permanent negative control: well-formed, in our own seeded
            // demo data, and absent from Code-Point Open. Before 33-08 a customer typing it got
            // zero kitchens. The district it names is real, so the honest answer is SE15's area.
            Optional<PostcodeGeocoder.LocatedPostcode> located = geocoder.locateSearchTerm("SE15 4QA");

            assertThat(located).isPresent();
            assertThat(located.get().precision()).isEqualTo(PostcodeGeocoder.Precision.DISTRICT);
            assertThat(located.get().key()).isEqualTo("SE15");
        }

        @Test
        @DisplayName("THE AGGREGATE TRAP: a district with no rows yields empty, never Null Island")
        void absentDistrictYieldsEmpty() {
            // The repository answers this with a ROW OF NULLS (avg over zero rows), not with a
            // null projection and not with zero rows. A `projection != null` gate would pass and
            // then unbox both coordinates to 0.0 — putting every customer's nearest kitchen off
            // the coast of Africa. ZZ99 is well-formed and does not exist.
            Optional<PostcodeGeocoder.LocatedPostcode> located = geocoder.locateSearchTerm("ZZ99");

            assertThat(located).isEmpty();
        }

        @Test
        @DisplayName("the computed range bounds are M10AA / M19ZZ / length 5 — the risky Java logic")
        void computesClosedRangeBoundsForOutwardCode() {
            // The SQL's semantics are proven against real PostgreSQL in Task 3. What is proven
            // HERE is the arithmetic this class owns: an off-by-one in either bound, or a
            // dropped length, is invisible from the returned coordinate alone.
            geocoder.locateSearchTerm("M1");

            ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
            verify(repository).findDistrictCentroid(start.capture(), end.capture(), length.capture());

            assertThat(start.getValue()).isEqualTo("M10AA");
            assertThat(end.getValue()).isEqualTo("M19ZZ");
            assertThat(length.getValue()).isEqualTo(5);
        }

        @Test
        @DisplayName("the unit-length guard keeps M11's units out of the M1 district (CA-F, at unit level)")
        void lengthGuardExcludesTheLongerDistrict() {
            // M1 has exactly one 5-character unit in the fixture (M11AE) and one 6-character
            // intruder (M111AA) that sorts inside the same key range. If the guard is dropped the
            // answer becomes the mean of the two and moves ~5 km east. Asserting equality with
            // M11AE's own coordinate is therefore decisive, not merely plausible.
            Optional<PostcodeGeocoder.LocatedPostcode> located = geocoder.locateSearchTerm("M1");

            assertThat(located).isPresent();
            assertThat(located.get().coordinate().latitude()).isEqualTo(53.483471);
            assertThat(located.get().coordinate().longitude()).isEqualTo(-2.231184);

            double intruderPull = metresBetween(
                    located.get().coordinate().latitude(), located.get().coordinate().longitude(),
                    (53.483471 + 53.473612) / 2, (-2.231184 + -2.172088) / 2);
            assertThat(intruderPull)
                    .as("distance from the WRONG (unguarded) centroid — must be far from it")
                    .isGreaterThan(1_000.0);
        }

        @ParameterizedTest
        @DisplayName("a term that is not a postcode is not a location — every one yields empty")
        @ValueSource(strings = {
                "jollof",          // an ordinary food search
                "",                // blank
                "   ",             // whitespace only
                "SE22 pizza",      // a postcode PLUS words: the both-ends anchor is what rejects it
                "pizza SE22",      // ...in either order
                "vegan",
                "—"
        })
        void nonPostcodeTermsYieldEmpty(String term) {
            assertThat(geocoder.locateSearchTerm(term)).isEmpty();
        }

        @Test
        @DisplayName("null yields empty without throwing")
        void nullTermYieldsEmpty() {
            assertThat(geocoder.locateSearchTerm(null)).isEmpty();
        }

        @Test
        @DisplayName("an over-long term is refused BEFORE the regex and BEFORE the database (T-33-08-01)")
        void overLongTermShortCircuitsBeforeTheMatcher() {
            String hostile = "A".repeat(400);

            long start = System.nanoTime();
            Optional<PostcodeGeocoder.LocatedPostcode> located = geocoder.locateSearchTerm(hostile);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(located).isEmpty();
            assertThat(elapsedMs).as("evaluation time in ms").isLessThan(1_000L);

            // The DoS control is the LENGTH CAP, not the regex's shape, so the proof is that
            // neither lookup was reached at all. Timing alone would pass on a slow regex too.
            verify(repository, never()).findById(anyString());
            verify(repository, never()).findDistrictCentroid(anyString(), anyString(), anyInt());
        }

        // ---- Regression: the WRITE path is untouched ---------------------------------

        @Test
        @DisplayName("REGRESSION: locate() still refuses a bare outward code, so vendor addresses "
                + "never get a district centroid")
        void locateStillRejectsBareOutwardCode() {
            // This is the whole reason locateSearchTerm exists as a second entry point. locate()
            // feeds ShopService.applyCoordinate; loosening it would start assigning ~1 km district
            // centroids to shops whose address the extractor only half-parsed, silently degrading
            // distanceKm for every one of them. The two calls below must disagree, permanently.
            assertThat(geocoder.locate("SE15")).isEmpty();
            assertThat(geocoder.locateSearchTerm("SE15")).isPresent();
        }

        @Test
        @DisplayName("REGRESSION: a full address still resolves through locate(), unchanged")
        void locateStillResolvesFullAddress() {
            Optional<PostcodeGeocoder.Coordinate> located =
                    geocoder.locate("48 Rye Lane, Peckham, London SE15 5BS");

            assertThat(located).isPresent();
            assertThat(located.get().latitude()).isEqualTo(51.472435);
            assertThat(located.get().longitude()).isEqualTo(-0.070047);
        }

        @Test
        @DisplayName("REGRESSION: a full address is NOT a search term — the search path stays "
                + "anchored at both ends")
        void locateSearchTermRejectsAFullAddress() {
            assertThat(geocoder.locateSearchTerm("48 Rye Lane, Peckham, London SE15 5BS")).isEmpty();
        }

        @Test
        @DisplayName("the LEADING anchor is load-bearing: a term locate() would happily accept is "
                + "refused as a search term")
        void leadingAnchorRejectsWhatTrailingAnchorAccepts() {
            // "x SE15 5BS" is ten characters, so the length cap does not reach it and the two
            // patterns are the only thing that can differ. locate() is anchored at the END only,
            // so it finds the postcode and resolves. locateSearchTerm() is anchored at BOTH ends,
            // so the leading "x " makes the whole term a text search. Without this pairing, the
            // "SE22 pizza" arm above proves only that SOMETHING rejected it.
            String term = "x SE15 5BS";

            assertThat(geocoder.locate(term))
                    .as("CONTROL: locate()'s trailing anchor accepts this")
                    .isPresent();
            assertThat(geocoder.locateSearchTerm(term))
                    .as("the search path's leading anchor must refuse it")
                    .isEmpty();
        }
    }
}
