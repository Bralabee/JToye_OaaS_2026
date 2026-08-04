package uk.jtoye.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;
import uk.jtoye.core.storage.StorageService.ImageType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #489 — a shop logo/banner is served {@code public, max-age=31536000, immutable},
 * so the object at a given key may NEVER change. Before this fix the key was
 * {@code <tenant>/shops/<shopId>/logo.webp}: fixed for the life of the shop. A vendor who
 * replaced their logo overwrote the bytes at a key every browser and CDN had been told never
 * to revalidate, so the old image could keep being served for up to a year with no in-product
 * way to force a refresh. Issue #445 made this unconditional — before it, the extension
 * followed the client's filename ({@code logo.png} vs {@code logo.jpg}), so a format change
 * happened to dodge the collision; now every derivative is {@code .webp}.
 *
 * <p><b>The fix is option 1 from the issue</b> — content-addressed keys, matching the Phase-24
 * {@code media_asset} model that already keys quarantine objects by their sha256. The key is
 * now {@code <tenant>/shops/<shopId>/<name>-<sha256 of the stored bytes>.webp}, so
 * {@code immutable} becomes a true statement rather than a lie: the bytes at a key are the
 * bytes the key is derived from. Options 2 (drop {@code immutable}) and 3 (query-string
 * cache-bust) were rejected — 2 gives up the caching good the storefront's Core Web Vitals
 * depend on, and 3 leaves the object genuinely mislabelled.
 *
 * <p><b>Scope — the issue's premise is only partly true and this class records which part.</b>
 * The issue names four {@code cacheControl(... immutable)} call sites. Only ONE of them is
 * keyed deterministically:
 * <ul>
 *   <li>{@link StorageService#uploadNamed} — {@code .../logo.webp} — <b>the defect</b>;</li>
 *   <li>{@link StorageService#upload} (product gallery) — already
 *       {@code .../<random UUID>.webp}, a fresh key per upload, so {@code immutable} was
 *       always honest. Pinned below by {@link #productGalleryKeyWasAlreadyUniquePerUpload()};</li>
 *   <li>{@code putSeedImage} — deterministic, but it {@code HeadObject}-skips the PUT when the
 *       object exists, so the bytes at that key are written once and never replaced (and it is
 *       a dev-only classpath-asset seam);</li>
 *   <li>{@code putBytes} — the async pipeline's keys are server-generated per asset id /
 *       per raw sha256, never reused across different bytes for a served derivative.</li>
 * </ul>
 *
 * <p><b>Why no Testcontainers / no MinIO.</b> The behaviour under test is entirely the object
 * KEY chosen for a PUT, captured at the choke point every one of the three legacy synchronous
 * endpoints passes through. Nothing about tenancy, RLS or persistence changes, and the S3
 * client is mocked because the assertion is about the request, not the store. The end-to-end
 * "the vendor sees the new logo" claim additionally rests on
 * {@code ShopService.uploadLogo/uploadBanner} persisting the RETURNED url onto the shop row
 * and evicting the {@code shops} cache entry, which they already do.
 */
@ExtendWith(MockitoExtension.class)
class ShopBrandImageKeyTest {

    @Mock
    private S3Client s3Client;

    private StorageService storageService;
    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();

        StorageProperties properties = new StorageProperties();
        properties.setMaxFileSizeBytes(5_242_880);
        properties.setAllowedContentTypes(List.of("image/jpeg", "image/png", "image/webp", "image/gif"));
        properties.getS3().setBucket("jtoye-images");
        properties.getS3().setPublicUrl("http://localhost:9000/jtoye-images");

        storageService = new StorageService(s3Client, properties, new MediaNormalizer(new MediaProperties()));
    }

    // ------------------------------------------------------------------
    // 1. The headline criterion: a re-upload must not reuse the key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-uploading a DIFFERENT logo to the same shop must produce a different key and url")
    void reUploadedLogoMustNotReuseTheImmutableKey() throws Exception {
        acceptPuts();

        String firstUrl = storageService.uploadNamed(
                tenantId, "shops", shopId, "logo", logo(Color.ORANGE, Color.BLUE));
        String secondUrl = storageService.uploadNamed(
                tenantId, "shops", shopId, "logo", logo(Color.GREEN, Color.RED));

        List<Put> puts = capturePuts(2);

        // Instrument check FIRST: if the two normalized derivatives happened to be
        // byte-identical, "the keys differ" would be an assertion about nothing.
        assertThat(sha256(puts.get(0).bytes()))
                .as("the two uploads must really produce different stored bytes, or the key "
                        + "assertion below is vacuous")
                .isNotEqualTo(sha256(puts.get(1).bytes()));

        assertThat(puts.get(1).key())
                .as("issue #489: the second upload must be stored at a DIFFERENT key — the first "
                        + "object was served 'immutable', so its bytes may never be replaced")
                .isNotEqualTo(puts.get(0).key());
        assertThat(secondUrl)
                .as("and the url handed back to ShopService (which persists it on the shop row) "
                        + "must change, or every cached copy still resolves to the old image")
                .isNotEqualTo(firstUrl);
    }

    @Test
    @DisplayName("Re-uploading a DIFFERENT banner to the same shop must produce a different key and url")
    void reUploadedBannerMustNotReuseTheImmutableKey() throws Exception {
        acceptPuts();

        String firstUrl = storageService.uploadNamed(
                tenantId, "shops", shopId, "banner", banner(Color.ORANGE, Color.BLUE));
        String secondUrl = storageService.uploadNamed(
                tenantId, "shops", shopId, "banner", banner(Color.GREEN, Color.RED));

        List<Put> puts = capturePuts(2);

        assertThat(sha256(puts.get(0).bytes()))
                .as("the two banners must really differ in stored bytes")
                .isNotEqualTo(sha256(puts.get(1).bytes()));
        assertThat(puts.get(1).key()).isNotEqualTo(puts.get(0).key());
        assertThat(secondUrl).isNotEqualTo(firstUrl);
    }

    @Test
    @DisplayName("The logo key is the sha256 OF THE STORED BYTES, so 'immutable' is now true by construction")
    void logoKeyIsDerivedFromTheStoredDerivativeBytes() throws Exception {
        acceptPuts();

        String url = storageService.uploadNamed(tenantId, "shops", shopId, "logo", logo(Color.ORANGE, Color.BLUE));

        Put put = capturePuts(1).get(0);
        String expectedKey = tenantId + "/shops/" + shopId + "/logo-" + sha256(put.bytes()) + ".webp";
        assertThat(put.key())
                .as("hashing the DERIVATIVE (not the raw upload) is what makes the immutable "
                        + "promise hold even if the jtoye.media.* budget later changes the "
                        + "derivative produced from the same source file")
                .isEqualTo(expectedKey);
        assertThat(url).isEqualTo("http://localhost:9000/jtoye-images/" + expectedKey);
    }

    @Test
    @DisplayName("The banner key is the sha256 of the stored bytes, under its own 'banner-' prefix")
    void bannerKeyIsDerivedFromTheStoredDerivativeBytes() throws Exception {
        acceptPuts();

        storageService.uploadNamed(tenantId, "shops", shopId, "banner", banner(Color.ORANGE, Color.BLUE));

        Put put = capturePuts(1).get(0);
        assertThat(put.key()).isEqualTo(tenantId + "/shops/" + shopId + "/banner-" + sha256(put.bytes()) + ".webp");
    }

    // ------------------------------------------------------------------
    // 2. Goods that must be preserved (Incremental Betterment)
    //    These arms pass on BOTH the unfixed and fixed trees. They are
    //    regression guards, NOT evidence for the fix — recorded as such.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PASSES BOTH TREES: re-uploading the IDENTICAL logo reuses the same key (content-addressed, not random)")
    void reUploadingTheIdenticalLogoReusesTheSameKey() throws Exception {
        acceptPuts();

        // Deliberately two independently-built fixtures with the same content, not one
        // byte array used twice, so this measures the pipeline's determinism.
        String firstUrl = storageService.uploadNamed(tenantId, "shops", shopId, "logo", logo(Color.ORANGE, Color.BLUE));
        String secondUrl = storageService.uploadNamed(tenantId, "shops", shopId, "logo", logo(Color.ORANGE, Color.BLUE));

        List<Put> puts = capturePuts(2);
        assertThat(puts.get(1).key())
                .as("a content-addressed key means an unchanged image does not churn storage or "
                        + "cold-start a CDN. This is what forbids 'just randomize the key' as a "
                        + "later shortcut — that would satisfy the headline criterion and lose this good")
                .isEqualTo(puts.get(0).key());
        assertThat(secondUrl).isEqualTo(firstUrl);
    }

    @Test
    @DisplayName("PASSES BOTH TREES: logo and banner of the SAME image stay distinct objects")
    void logoAndBannerOfTheSameImageStayDistinct() throws Exception {
        acceptPuts();

        storageService.uploadNamed(tenantId, "shops", shopId, "logo", square(Color.ORANGE, Color.BLUE));
        storageService.uploadNamed(tenantId, "shops", shopId, "banner", square(Color.ORANGE, Color.BLUE));

        List<Put> puts = capturePuts(2);
        // Asserted by CONTAINS, not by prefix shape: the exact shape is pinned by the two
        // sha256-key arms above, and pinning it here too would make this arm fail on the
        // unfixed tree, which would mis-label it as evidence when it is a preserved good.
        assertThat(puts.get(0).key()).startsWith(tenantId + "/shops/" + shopId + "/").contains("logo");
        assertThat(puts.get(1).key()).startsWith(tenantId + "/shops/" + shopId + "/").contains("banner");
        assertThat(puts.get(0).key())
                .as("identical bytes uploaded as logo and as banner must stay two objects — a "
                        + "purely content-addressed key with no name segment would collapse them, "
                        + "and removing one would then remove the other")
                .isNotEqualTo(puts.get(1).key());
    }

    @Test
    @DisplayName("PASSES BOTH TREES: 'immutable' is still declared — the point of the fix is to make it TRUE, not to drop it")
    void immutableCacheControlIsRetained() throws Exception {
        acceptPuts();

        storageService.uploadNamed(tenantId, "shops", shopId, "logo", logo(Color.ORANGE, Color.BLUE));

        assertThat(capturePuts(1).get(0).cacheControl())
                .as("options 2/3 in the issue would have traded away the year-long cache the "
                        + "storefront's LCP depends on; option 1 keeps it and earns it")
                .isEqualTo("public, max-age=31536000, immutable");
    }

    @Test
    @DisplayName("FALSIFICATION RECORD: the product-gallery key was ALREADY unique per upload, on both trees")
    void productGalleryKeyWasAlreadyUniquePerUpload() throws Exception {
        // Issue #489 names StorageService.upload as one of four defective call sites. It is
        // not: its key has always carried a per-upload UUID.randomUUID(), so no two uploads
        // ever share a key and 'immutable' has always been honest there. Recorded as a test
        // rather than a comment so the claim cannot silently rot back into a "fix".
        acceptPuts();

        storageService.upload(tenantId, "products", shopId, product(Color.ORANGE, Color.BLUE), ImageType.PRODUCT);
        storageService.upload(tenantId, "products", shopId, product(Color.ORANGE, Color.BLUE), ImageType.PRODUCT);

        List<Put> puts = capturePuts(2);
        assertThat(puts.get(0).key()).isNotEqualTo(puts.get(1).key());
        assertThat(puts.get(0).key()).startsWith(tenantId + "/products/" + shopId + "/").endsWith(".webp");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Put(String key, String contentType, String cacheControl, byte[] bytes) {
    }

    private void acceptPuts() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);
    }

    private List<Put> capturePuts(int expected) throws IOException {
        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(expected)).putObject(reqCaptor.capture(), bodyCaptor.capture());

        List<Put> puts = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
            PutObjectRequest req = reqCaptor.getAllValues().get(i);
            byte[] stored = bodyCaptor.getAllValues().get(i).contentStreamProvider().newStream().readAllBytes();
            puts.add(new Put(req.key(), req.contentType(), req.cacheControl(), stored));
        }
        return puts;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static MockMultipartFile logo(Color from, Color to) throws IOException {
        return new MockMultipartFile("file", "logo.jpg", "image/jpeg", jpeg(1200, 1200, from, to));
    }

    private static MockMultipartFile square(Color from, Color to) throws IOException {
        return new MockMultipartFile("file", "brand.jpg", "image/jpeg", jpeg(1200, 1200, from, to));
    }

    private static MockMultipartFile banner(Color from, Color to) throws IOException {
        return new MockMultipartFile("file", "banner.jpg", "image/jpeg", jpeg(2400, 900, from, to));
    }

    private static MockMultipartFile product(Color from, Color to) throws IOException {
        return new MockMultipartFile("file", "dish.jpg", "image/jpeg", jpeg(1200, 1200, from, to));
    }

    private static byte[] jpeg(int w, int h, Color from, Color to) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, from, w, h, to));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }
}
