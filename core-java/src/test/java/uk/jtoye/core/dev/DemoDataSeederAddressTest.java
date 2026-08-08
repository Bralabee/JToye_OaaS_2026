package uk.jtoye.core.dev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.geo.PostcodeCentroid;
import uk.jtoye.core.geo.PostcodeCentroidRepository;
import uk.jtoye.core.geo.PostcodeGeocoder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The seeded demo addresses, checked against the SAME Code-Point Open fixture the geocoder's
 * own tests use. This is the guard that makes the seeder-to-fixture link load-bearing rather
 * than a convention someone can quietly break.
 *
 * <p>It exists because of a defect this repo actually shipped: the address seeded for
 * "Peckham Jollof Co." carried a postcode that <strong>does not exist</strong> — well-formed
 * enough to satisfy every plausible UK-postcode regex, absent from Code-Point Open, and a
 * 404 from ONSPD. Nothing failed. The shop simply held NULL coordinates forever and would
 * have vanished from every distance result introduced by the change meant to fix locality.
 * A prose comment saying "do not restore it" is not a control; this is.
 *
 * <p>The two address-less shops are asserted in the OTHER direction, and that half matters
 * just as much: they are the only rows on the dev database that exercise the "no extractable
 * postcode" branch, so a later tidy-up that helpfully invents postcodes for them would delete
 * the only live proof that an unresolvable address yields NULL rather than {@code (0,0)}.
 */
class DemoDataSeederAddressTest {

    private PostcodeGeocoder geocoder;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, PostcodeCentroid> fixture = loadFixture();
        PostcodeCentroidRepository repository = mock(PostcodeCentroidRepository.class);
        when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(fixture.get(inv.getArgument(0, String.class))));
        geocoder = new PostcodeGeocoder(repository);
    }

    private static Map<String, PostcodeCentroid> loadFixture() throws Exception {
        Map<String, PostcodeCentroid> rows = new HashMap<>();
        try (InputStream in = DemoDataSeederAddressTest.class
                .getResourceAsStream("/geo/postcode-centroids-fixture.csv");
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
        assertThat(rows).as("the 33-02 fixture must be readable and non-empty").isNotEmpty();
        return rows;
    }

    @Test
    @DisplayName("every curated seeded address resolves to a real GB coordinate")
    void everyCuratedAddressGeocodes() {
        List<String> curated = List.of(
                DemoDataSeeder.MAMA_ADES_ADDRESS,
                DemoDataSeeder.PECKHAM_JOLLOF_ADDRESS,
                DemoDataSeeder.BRIXTON_GRILL_ADDRESS);

        for (String address : curated) {
            Optional<PostcodeGeocoder.Coordinate> located = geocoder.locate(address);
            assertThat(located)
                    .as("seeded address '%s' must resolve against the committed fixture", address)
                    .isPresent();
            // Not merely present: a coordinate in Great Britain. The fixture's own range is
            // 49.9..60.8 N / -7..2 E, so this also catches a swapped lat/lon pair.
            assertThat(located.get().latitude()).isBetween(49.0, 61.0);
            assertThat(located.get().longitude()).isBetween(-8.0, 2.0);
            assertThat(located.get().latitude() == 0.0 && located.get().longitude() == 0.0)
                    .as("seeded address '%s' landed at Null Island", address)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the two address-less shops still do NOT geocode — they are the negative controls")
    void theNegativeControlsStayUnresolvable() {
        assertThat(geocoder.locate(DemoDataSeeder.TENANT_B_PROBE_ADDRESS))
                .as("tenant-b-probe must keep its postcode-free address")
                .isEmpty();
        assertThat(geocoder.locate(DemoDataSeeder.ARCHIVE_ADDRESS))
                .as("unsorted-legacy-items must keep its postcode-free address")
                .isEmpty();
    }

    @Test
    @DisplayName("the non-existent postcode is gone from every seeded address, in every spelling")
    void theNonExistentPostcodeIsNotSeededAnywhere() {
        // The literal is assembled rather than written out, for two reasons. It keeps this
        // assertion honest about SPELLING (spaced and unspaced are the same postcode to the
        // geocoder), and it means this file does not itself contain the string that the
        // Task-2 verification greps for as an absence in the seeder — a rule that must spell
        // the token it forbids is a rule that fires on its own definition.
        String outward = "SE15";
        String inward = "4QA";

        List<String> seeded = List.of(
                DemoDataSeeder.MAMA_ADES_ADDRESS,
                DemoDataSeeder.PECKHAM_JOLLOF_ADDRESS,
                DemoDataSeeder.BRIXTON_GRILL_ADDRESS,
                DemoDataSeeder.TENANT_B_PROBE_ADDRESS,
                DemoDataSeeder.ARCHIVE_ADDRESS);

        for (String address : seeded) {
            assertThat(address.toUpperCase())
                    .as("a seeded address must not carry the non-existent unit")
                    .doesNotContain(outward + " " + inward)
                    .doesNotContain(outward + inward);
        }
    }

    @Test
    @DisplayName("non-vacuity: this instrument CAN see the non-existent postcode when it is present")
    void theAbsenceCheckCanActuallyFail() {
        // Without this arm the test above is a statement about the pattern, not the seeder.
        String reintroduced = "12 Bellenden Road, Peckham, London " + "SE15" + " " + "4QA";

        assertThat(reintroduced.toUpperCase()).contains("SE15" + " " + "4QA");
        assertThat(geocoder.locate(reintroduced))
                .as("and it still does not resolve — which is why it must not be seeded")
                .isEmpty();
    }
}
