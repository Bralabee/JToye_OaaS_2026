package uk.jtoye.core.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.dev.DemoImageManifest.ManifestEntry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the bundled demo-catalog imagery (quick task 260713-kds) against drift.
 * Pure classpath + POJO assertions — NO Spring context, NO object store, NO
 * Testcontainers — so it runs in the fast unit slice.
 *
 * <p>The load-bearing assertion is {@link #everyEntryMapsToACuratedProduct()}:
 * it derives the expected (slug → dish titles) map from
 * {@link DemoDataSeeder#curatedTitlesBySlug()} (the single source of truth for
 * the curated menus) rather than a second literal list, so a manifest that names
 * a shop the seeder does not know ("Peckham Jollof Co" vs the seeder's "Peckham
 * Jollof Co.", or an apostrophe-mangled slug of "Mama Ade's Kitchen") fails here
 * instead of silently shipping a product with no photo.
 */
class DemoImageManifestTest {

    private static final int EXPECTED_ENTRIES = 21;
    private static final Set<String> CURATED_SLUGS =
            Set.of("mama-ades-kitchen", "peckham-jollof-co", "brixton-village-grill");

    @Test
    @DisplayName("manifest.json parses from the classpath with exactly 21 entries")
    void manifestParsesToTwentyOneEntries() {
        List<ManifestEntry> entries = DemoImageManifest.load();
        assertThat(entries).hasSize(EXPECTED_ENTRIES);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.dish()).isNotBlank();
            assertThat(e.shop()).isNotBlank();
            assertThat(e.filename()).isNotBlank().endsWith(".jpg");
            assertThat(e.license()).isNotBlank();
        });
    }

    @Test
    @DisplayName("every manifest entry maps to a real curated product (slug + dish title)")
    void everyEntryMapsToACuratedProduct() {
        Map<String, Set<String>> curatedTitlesBySlug = DemoDataSeeder.curatedTitlesBySlug();
        // Sanity: the source of truth covers exactly the three curated storefronts.
        assertThat(curatedTitlesBySlug.keySet()).isEqualTo(CURATED_SLUGS);

        for (ManifestEntry entry : DemoImageManifest.load()) {
            String slug = DemoImageManifest.slugForShop(entry.shop());
            assertThat(CURATED_SLUGS)
                    .as("manifest shop '%s' -> slug '%s'", entry.shop(), slug)
                    .contains(slug);

            Set<String> titlesForShop = curatedTitlesBySlug.get(slug);
            boolean dishMatches = titlesForShop.stream()
                    .anyMatch(t -> t.equalsIgnoreCase(entry.dish()));
            assertThat(dishMatches)
                    .as("dish '%s' must be a curated product of shop '%s' (titles=%s)",
                            entry.dish(), slug, titlesForShop)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every manifest image exists on the classpath")
    void everyImageIsOnTheClasspath() {
        for (ManifestEntry entry : DemoImageManifest.load()) {
            assertThat(DemoImageManifest.imageExists(entry.filename()))
                    .as("classpath image %s%s", DemoImageManifest.BASE_PATH, entry.filename())
                    .isTrue();
            // And its bytes are readable + non-trivial (a real JPEG, not a stub).
            assertThat(DemoImageManifest.readImage(entry.filename()))
                    .as("bytes of %s", entry.filename())
                    .hasSizeGreaterThan(1024);
        }
    }

    @Test
    @DisplayName("all 21 images spread exactly 7 per curated shop")
    void everyCuratedShopHasSevenImages() {
        Map<String, Long> perSlug = DemoImageManifest.load().stream()
                .collect(Collectors.groupingBy(
                        e -> DemoImageManifest.slugForShop(e.shop()),
                        Collectors.counting()));
        assertThat(perSlug)
                .containsEntry("mama-ades-kitchen", 7L)
                .containsEntry("peckham-jollof-co", 7L)
                .containsEntry("brixton-village-grill", 7L);
    }

    /**
     * 38-07: the Jackson-3 load returns what the Jackson-2 load returned. The literals are the
     * first and last entries as Boot 3.5's Jackson 2 bound them on the plan base
     * (evidence/38-07-jackson-request-response.txt, Task 2), compared as whole records, so a
     * dropped {@code @JsonProperty} rename ({@code license_url}, {@code source_url}) or a
     * reordering shows here.
     */
    @Test
    @DisplayName("Jackson-3 load: first and last entries equal the Jackson-2 load, field for field")
    void firstAndLastEntriesEqualTheJackson2Load() {
        List<ManifestEntry> entries = DemoImageManifest.load();
        assertThat(entries).hasSize(EXPECTED_ENTRIES);
        assertThat(entries.get(0)).isEqualTo(new ManifestEntry("Beef Suya Wrap", "Brixton Village Grill",
                "beef-suya-wrap.jpg", "Ooreoluwa4578420", "CC0",
                "http://creativecommons.org/publicdomain/zero/1.0/deed.en",
                "https://commons.wikimedia.org/wiki/File:Nigerian_Beef-Suya.jpg"));
        assertThat(entries.get(EXPECTED_ENTRIES - 1)).isEqualTo(new ManifestEntry("Suya Platter",
                "Peckham Jollof Co", "suya-platter.jpg", "Bukky658", "CC BY-SA 4.0",
                "https://creativecommons.org/licenses/by-sa/4.0",
                "https://commons.wikimedia.org/wiki/File:Suya_with_pepper_sauce.jpg"));
    }

    /**
     * 38-07: a manifest that does not parse still fails as the loader's own
     * {@link IllegalStateException} naming the resource. On Jackson 2 the parse error was a
     * checked {@code JsonProcessingException} (an {@code IOException}) and the
     * {@code catch (IOException)} wrapped it; on Jackson 3 it is the unchecked
     * {@code JacksonException}, which that catch no longer sees.
     */
    @Test
    @DisplayName("a manifest that does not parse fails as IllegalStateException naming the resource")
    void unparseableManifest_failsAsTheLoadersOwnException() {
        String resource = "dev-manifest-38-07/truncated-manifest.json";
        assertThatThrownBy(() -> DemoImageManifest.load(resource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to read demo image manifest: " + resource)
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test
    @DisplayName("a missing manifest still fails as IllegalStateException (unchanged)")
    void missingManifest_failsAsTheLoadersOwnException() {
        assertThatThrownBy(() -> DemoImageManifest.load("dev-manifest-38-07/absent.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Demo image manifest not found on classpath: dev-manifest-38-07/absent.json");
    }

    @Test
    @DisplayName("every license is attribution-compatible (CC0 / CC BY / CC BY-SA — no NC/ND)")
    void everyLicenseIsAttributionCompatible() {
        for (ManifestEntry entry : DemoImageManifest.load()) {
            String license = entry.license();
            boolean ok = license.equals("CC0")
                    || license.startsWith("CC BY-SA")
                    || license.startsWith("CC BY");
            assertThat(ok)
                    .as("license '%s' for '%s' must be CC0/CC BY/CC BY-SA", license, entry.dish())
                    .isTrue();
            assertThat(license)
                    .as("license '%s' for '%s' must not be NC/ND-restricted", license, entry.dish())
                    .doesNotContain("NC")
                    .doesNotContain("ND");
        }
    }
}
