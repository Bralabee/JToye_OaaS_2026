package uk.jtoye.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;
import uk.jtoye.core.media.exception.DecompressionBombException;
import uk.jtoye.core.storage.StorageService.ImageType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #445 (QA-A / F-H3-RAWIMG) — the three legacy synchronous image endpoints must not
 * store the client's raw bytes as the canonical, publicly-served object.
 *
 * <p>The three endpoints reach MinIO through exactly two {@link StorageService} methods, and
 * nothing else in production calls them:
 * <ul>
 *   <li>{@code POST /api/v1/products/{id}/images} -&gt; {@code ProductService.addAdditionalImage}
 *       -&gt; {@link StorageService#upload(UUID, String, UUID, org.springframework.web.multipart.MultipartFile, ImageType)}</li>
 *   <li>{@code POST /api/v1/shops/{id}/logo} -&gt; {@code ShopService.uploadLogo}
 *       -&gt; {@link StorageService#uploadNamed}</li>
 *   <li>{@code POST /api/v1/shops/{id}/banner} -&gt; {@code ShopService.uploadBanner}
 *       -&gt; {@link StorageService#uploadNamed}</li>
 * </ul>
 * so asserting at that choke point covers all three without a Spring context.
 *
 * <p><b>Why no Testcontainers.</b> The defect is entirely in byte handling — what is PUT to
 * object storage, and with which Content-Type. No tenancy/RLS behaviour changes, so a real
 * Postgres would exercise nothing this test needs; the tenant scoping of these same endpoints
 * is already proven under RLS by {@code ShopImageCrossTenantIntegrationTest}. The S3 client is
 * mocked because the assertion is about the PUT payload, which is captured directly.
 *
 * <p>Every assertion below is written to FAIL on the pre-fix tree, except
 * {@link #nonImageMagicBytesStillRejected()} and {@link #belowMinimumDimensionsStillRejected()},
 * which are explicitly labelled as pre-existing behaviour retained (the issue's claim that
 * magic-byte sniffing is bypassed is inaccurate — see the test's own comment).
 */
@ExtendWith(MockitoExtension.class)
class LegacyImageUploadPipelineTest {

    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};

    @Mock
    private S3Client s3Client;

    private StorageProperties properties;
    private StorageService storageService;
    private UUID tenantId;
    private UUID entityId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        entityId = UUID.randomUUID();

        properties = new StorageProperties();
        properties.setMaxFileSizeBytes(5_242_880);
        properties.setAllowedContentTypes(List.of("image/jpeg", "image/png", "image/webp", "image/gif"));
        properties.getS3().setBucket("jtoye-images");
        properties.getS3().setPublicUrl("http://localhost:9000/jtoye-images");

        storageService = new StorageService(s3Client, properties, new MediaNormalizer(new MediaProperties()));
    }

    // ------------------------------------------------------------------
    // 1. Raw bytes are never canonical (the headline acceptance criterion)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /products/{id}/images - stored object is NOT byte-identical to the upload")
    void productGalleryUploadIsNotStoredRaw() throws Exception {
        byte[] raw = jpegOf(3000, 2400);
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT);

        Put put = capturePut();
        assertThat(sha256(put.bytes))
                .as("sha256 identity: what went in must NOT come back out unchanged")
                .isNotEqualTo(sha256(raw));
        assertThat(put.bytes).as("stored derivative must be WebP").startsWith(RIFF);
        assertThat(Arrays.copyOfRange(put.bytes, 8, 12)).isEqualTo(WEBP);
        assertThat(put.contentType).isEqualTo("image/webp");
        assertThat(put.key).endsWith(".webp");
    }

    @Test
    @DisplayName("POST /shops/{id}/logo - stored object is NOT byte-identical to the upload")
    void shopLogoUploadIsNotStoredRaw() throws Exception {
        byte[] raw = jpegOf(1200, 1200);
        MockMultipartFile file = new MockMultipartFile("file", "logo.jpg", "image/jpeg", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.uploadNamed(tenantId, "shops", entityId, "logo", file);

        Put put = capturePut();
        assertThat(sha256(put.bytes)).isNotEqualTo(sha256(raw));
        assertThat(put.bytes).startsWith(RIFF);
        assertThat(put.contentType).isEqualTo("image/webp");
        // Issue #489 changed the key from the fixed ".../logo.webp" to a content-addressed
        // ".../logo-<sha256 of these bytes>.webp" (see StorageService.uploadNamed). This
        // assertion's job here is the #445 one — the object is the WebP derivative at a
        // ".webp" key under the shop's own prefix; the key SHAPE is pinned by
        // ShopBrandImageKeyTest.
        assertThat(put.key)
                .isEqualTo(tenantId + "/shops/" + entityId + "/logo-" + sha256(put.bytes) + ".webp");
    }

    @Test
    @DisplayName("POST /shops/{id}/banner - stored object is NOT byte-identical to the upload")
    void shopBannerUploadIsNotStoredRaw() throws Exception {
        byte[] raw = jpegOf(2400, 900);
        MockMultipartFile file = new MockMultipartFile("file", "banner.jpg", "image/jpeg", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.uploadNamed(tenantId, "shops", entityId, "banner", file);

        Put put = capturePut();
        assertThat(sha256(put.bytes)).isNotEqualTo(sha256(raw));
        assertThat(put.bytes).startsWith(RIFF);
        assertThat(put.contentType).isEqualTo("image/webp");
        // See the note on the logo case above (issue #489 content-addressed key).
        assertThat(put.key)
                .isEqualTo(tenantId + "/shops/" + entityId + "/banner-" + sha256(put.bytes) + ".webp");
    }

    @Test
    @DisplayName("Derivative is bounded by the jtoye.media budget, not the source dimensions")
    void derivativeIsBoundedByTheConfiguredBudget() throws Exception {
        MediaProperties budget = new MediaProperties();
        byte[] raw = jpegOf(3000, 2400);
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT);

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(capturePut().bytes));
        assertThat(stored).as("stored object must be a decodable image").isNotNull();
        assertThat(Math.max(stored.getWidth(), stored.getHeight()))
                .isLessThanOrEqualTo(budget.getMaxDimension());
    }

    // ------------------------------------------------------------------
    // 2. EXIF/GPS privacy leak
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /products/{id}/images - EXIF GPS does not survive into the stored object")
    void productGalleryUploadStripsExifGps() throws Exception {
        byte[] raw = jpegWithExifGps(1200, 900);
        assertThat(new String(raw, StandardCharsets.ISO_8859_1))
                .as("fixture must actually carry EXIF + a GPS marker")
                .contains("Exif").contains("GPSINFOSECRET");
        MockMultipartFile file = new MockMultipartFile("file", "holiday.jpg", "image/jpeg", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT);

        assertThat(new String(capturePut().bytes, StandardCharsets.ISO_8859_1))
                .as("GPS coordinates must not reach the public storefront object")
                .doesNotContain("GPSINFOSECRET")
                .doesNotContain("Exif");
    }

    @Test
    @DisplayName("POST /shops/{id}/logo - EXIF GPS does not survive into the stored object")
    void shopLogoUploadStripsExifGps() throws Exception {
        byte[] raw = jpegWithExifGps(800, 800);
        MockMultipartFile file = new MockMultipartFile("file", "logo.jpg", "image/jpeg", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.uploadNamed(tenantId, "shops", entityId, "logo", file);

        assertThat(new String(capturePut().bytes, StandardCharsets.ISO_8859_1))
                .doesNotContain("GPSINFOSECRET")
                .doesNotContain("Exif");
    }

    // ------------------------------------------------------------------
    // 3. Decompression-bomb guard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Decompression bomb is refused by the header-read megapixel guard, before any decode")
    void decompressionBombRejectedBeforeDecode() {
        // A PNG with a valid signature + a valid IHDR declaring 30000x30000 (900 MP) and NO
        // IDAT. A full decode is impossible, so getting the BOMB error rather than a generic
        // "could not read image" is what proves the header-read guard fired first — the same
        // argument MediaNormalizerTest.bombRejectedBeforeDecode makes for the async pipeline.
        byte[] bomb = pngHeaderOnly(30_000, 30_000);
        MockMultipartFile file = new MockMultipartFile("file", "bomb.png", "image/png", bomb);

        assertThatThrownBy(() -> storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT))
                .isInstanceOf(DecompressionBombException.class)
                .hasMessageContaining("megapixel");
    }

    // ------------------------------------------------------------------
    // 4. Stored Content-Type is the PRODUCED type, never the client header
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Client-declared Content-Type is not persisted onto the public object (spoof vector)")
    void clientDeclaredContentTypeIsNotPersisted() throws Exception {
        // Real PNG magic bytes (so the format allowlist passes) but the client declares
        // text/html. Pre-fix, StorageService persisted file.getContentType() verbatim, so the
        // public bucket served attacker-influenced bytes as text/html.
        byte[] raw = pngOf(900, 900);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "text/html", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT);

        assertThat(capturePut().contentType)
                .as("Content-Type must be the produced type, never the client header")
                .isEqualTo("image/webp");
    }

    // ------------------------------------------------------------------
    // 5. Goods that must be preserved (Incremental Betterment)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GIF is still accepted (the file picker offers it) and is transcoded, not stored raw")
    void gifIsStillAcceptedAndTranscoded() throws Exception {
        byte[] raw = gifOf(800, 600);
        MockMultipartFile file = new MockMultipartFile("file", "anim.gif", "image/gif", raw);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        storageService.uploadNamed(tenantId, "shops", entityId, "banner", file);

        Put put = capturePut();
        assertThat(sha256(put.bytes)).isNotEqualTo(sha256(raw));
        assertThat(put.bytes).startsWith(RIFF);
        assertThat(put.contentType).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("PRE-EXISTING PASS: non-image magic bytes were already rejected before this fix")
    void nonImageMagicBytesStillRejected() {
        // Recorded deliberately: issue #445 says these endpoints skip magic-byte sniffing.
        // They do not — StorageService.validateAndRead has sniffed since before Phase 24. This
        // arm therefore passes on BOTH the unfixed and fixed trees and is a regression guard,
        // not evidence for the fix.
        byte[] notAnImage = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$NOT-AN-IMAGE-PAYLOAD".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile("file", "eicar.jpg", "image/jpeg", notAnImage);

        assertThatThrownBy(() -> storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid image format");
    }

    @Test
    @DisplayName("PRE-EXISTING PASS: minimum-dimension rule survives normalization")
    void belowMinimumDimensionsStillRejected() throws Exception {
        byte[] raw = jpegOf(100, 100);
        MockMultipartFile file = new MockMultipartFile("file", "tiny.jpg", "image/jpeg", raw);

        assertThatThrownBy(() -> storageService.upload(tenantId, "products", entityId, file, ImageType.PRODUCT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400x400");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Put(String key, String contentType, byte[] bytes) {
    }

    private Put capturePut() throws IOException {
        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());
        byte[] stored = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
        return new Put(reqCaptor.getValue().key(), reqCaptor.getValue().contentType(), stored);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static BufferedImage gradient(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }

    private static byte[] jpegOf(int w, int h) throws IOException {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    private static byte[] pngOf(int w, int h) throws IOException {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "png");
    }

    private static byte[] gifOf(int w, int h) throws IOException {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "gif");
    }

    /** A PNG with a valid signature + a valid IHDR (correct CRC) declaring w x h, but NO IDAT. */
    private static byte[] pngHeaderOnly(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 2;   // colour type: truecolour RGB
        writeChunk(out, "IHDR", ihdr);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        out.writeBytes(crcBytes);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    /** A real JPEG with an APP1 Exif segment (minimal valid TIFF) carrying a searchable GPS marker. */
    private static byte[] jpegWithExifGps(int w, int h) throws IOException {
        byte[] base = jpegOf(w, h);   // begins FF D8 (SOI)

        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        content.writeBytes(new byte[]{'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08});
        content.writeBytes(new byte[]{0x00, 0x00});
        content.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});
        content.writeBytes("GPSINFOSECRET".getBytes(StandardCharsets.US_ASCII));
        byte[] c = content.toByteArray();
        int segLen = c.length + 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{base[0], base[1]});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1});
        out.writeBytes(new byte[]{(byte) ((segLen >>> 8) & 0xFF), (byte) (segLen & 0xFF)});
        out.writeBytes(c);
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }
}
