package uk.jtoye.core.dev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loader + shop-name resolution for the bundled dev demo-catalog imagery
 * (quick task 260713-kds). The 21 license-verified dish photos and their
 * attribution metadata live on the classpath under {@link #BASE_PATH}; this
 * class parses {@code manifest.json} with Jackson (already on the classpath via
 * {@code spring-boot-starter-web} — no new dependency) and exposes the byte
 * loading + shop→slug normalization both {@link DemoDataSeeder} and the unit
 * test rely on.
 *
 * <p><strong>Shop→slug is an EXPLICIT map, not a slugify.</strong> A generic
 * slugify of "Mama Ade's Kitchen" yields {@code mama-ade-s-kitchen} (apostrophe
 * → dash) not the real slug {@code mama-ades-kitchen}, and the manifest writes
 * "Peckham Jollof Co" (no trailing period) while the seeder shop name is
 * "Peckham Jollof Co." (with period). The map below is matched
 * case-insensitively and tolerant of one trailing '.', and throws loudly on an
 * unmapped shop so manifest/seeder drift fails fast.
 */
public final class DemoImageManifest {

    /** Classpath directory holding {@code manifest.json} + the 21 {@code *.jpg} assets. */
    public static final String BASE_PATH = "dev/demo-images/";

    /**
     * Explicit manifest-shop-name → curated-shop-slug map. Keys are the
     * normalized (trimmed, trailing-'.'-stripped, lowercased) manifest values.
     */
    private static final Map<String, String> SHOP_SLUGS = Map.of(
            "brixton village grill", "brixton-village-grill",
            "mama ade's kitchen", "mama-ades-kitchen",
            "peckham jollof co", "peckham-jollof-co");

    private DemoImageManifest() {
    }

    /**
     * One row of {@code manifest.json}. Unknown fields (width_hint, wikimedia_file,
     * verified_visually, note) are ignored — only the fields the seeder + credits
     * doc need are bound.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestEntry(
            @JsonProperty("dish") String dish,
            @JsonProperty("shop") String shop,
            @JsonProperty("filename") String filename,
            @JsonProperty("author") String author,
            @JsonProperty("license") String license,
            @JsonProperty("license_url") String licenseUrl,
            @JsonProperty("source_url") String sourceUrl) {
    }

    /** Parse {@code dev/demo-images/manifest.json} from the classpath. */
    public static List<ManifestEntry> load() {
        ObjectMapper mapper = new ObjectMapper();
        String resource = BASE_PATH + "manifest.json";
        try (InputStream in = classLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Demo image manifest not found on classpath: " + resource);
            }
            return mapper.readValue(in, new TypeReference<List<ManifestEntry>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read demo image manifest: " + resource, e);
        }
    }

    /**
     * Resolve a manifest "shop" value to its curated storefront slug via the
     * explicit map. Case-insensitive; tolerates one trailing '.'.
     *
     * @throws IllegalStateException on an unmapped shop (manifest/seeder drift)
     */
    public static String slugForShop(String manifestShop) {
        if (manifestShop == null) {
            throw new IllegalStateException("Manifest shop name is null");
        }
        String norm = manifestShop.trim().toLowerCase(Locale.ROOT);
        if (norm.endsWith(".")) {
            norm = norm.substring(0, norm.length() - 1).trim();
        }
        String slug = SHOP_SLUGS.get(norm);
        if (slug == null) {
            throw new IllegalStateException("No curated-shop slug mapping for demo manifest shop: '"
                    + manifestShop + "'");
        }
        return slug;
    }

    /** Read a bundled demo image's bytes from the classpath. */
    public static byte[] readImage(String filename) {
        String resource = BASE_PATH + filename;
        try (InputStream in = classLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Demo image not found on classpath: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read demo image: " + resource, e);
        }
    }

    /** True if the named image resource is present on the classpath. */
    public static boolean imageExists(String filename) {
        return classLoader().getResource(BASE_PATH + filename) != null;
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : DemoImageManifest.class.getClassLoader();
    }
}
