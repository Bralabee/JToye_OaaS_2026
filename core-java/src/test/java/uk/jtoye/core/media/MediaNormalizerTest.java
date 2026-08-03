package uk.jtoye.core.media;

import org.junit.jupiter.api.Test;
import uk.jtoye.core.media.exception.DecompressionBombException;
import uk.jtoye.core.media.exception.UnreadableImageException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure-transform {@link MediaNormalizer} (IMG-02, threats
 * T-24-01/02/03). Pure JVM — no containers: the WebP encode runs against
 * scrimage's bundled (glibc) cwebp on the dev host, while the musl-container
 * proof of the same binary lives in {@code MediaWebpMuslSmokeTest}.
 */
class MediaNormalizerTest {

    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};

    // Defaults (D-02a budget): max-dimension 1600 / quality 80 / thumbnail 400 /
    // max-megapixels 40. The normalizer reads every one from this bean.
    private final MediaProperties props = new MediaProperties();
    private final MediaNormalizer normalizer = new MediaNormalizer(props);

    @Test
    void budgetLivesInConfigNotLiterals() {
        assertThat(props.getMaxDimension()).isEqualTo(1600);
        assertThat(props.getQuality()).isEqualTo(80);
        assertThat(props.getThumbnail()).isEqualTo(400);
        assertThat(props.getMaxMegapixels()).isEqualTo(40);
    }

    @Test
    void bombRejectedBeforeDecode() {
        // A tiny PNG whose IHDR declares 30000x30000 (900 MP >> 40 MP cap) with NO
        // pixel data (no IDAT). A full decode is therefore impossible — so getting
        // DecompressionBombException (not a decode error) proves the megapixel guard
        // fired at the header read, before any pixel buffer was allocated.
        byte[] bombHeader = craftPngHeaderOnly(30_000, 30_000);
        assertThatThrownBy(() -> normalizer.normalize(bombHeader))
                .isInstanceOf(DecompressionBombException.class);
    }

    @Test
    void magicByteMismatchVetoes() {
        byte[] pdf = "%PDF-1.7\n%not-an-image-payload-bytes".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> normalizer.normalize(pdf))
                .isInstanceOf(UnreadableImageException.class);
    }

    /**
     * Issue #445 added a gif magic-byte signature + an allowlist parameter so the LEGACY
     * synchronous endpoints can transcode gif. This pins the async product pipeline's behaviour
     * as unchanged: the default entry point still vetoes gif, and it must fail at the ALLOWLIST
     * (not merely at detection), which is why the fixture is a real, decodable gif.
     */
    @Test
    void defaultAllowlistStillVetoesGifForTheProductPipeline() throws Exception {
        byte[] gif = gifOf(800, 600);
        assertThat(normalizer.normalize(gif, MediaNormalizer.LEGACY_SYNC_INPUT_TYPES).derivativeBytes())
                .as("the legacy allowlist admits and transcodes the very same bytes")
                .startsWith(RIFF);

        assertThatThrownBy(() -> normalizer.normalize(gif))
                .isInstanceOf(UnreadableImageException.class)
                .hasMessageContaining("image/gif");
    }

    @Test
    void encodesToWebpWithinBudget() throws Exception {
        byte[] jpeg = jpegOf(2000, 1500);
        MediaNormalizer.NormalizedImage out = normalizer.normalize(jpeg);

        // Derivative is a valid WebP (RIFF....WEBP magic).
        assertThat(out.derivativeBytes()).startsWith(RIFF);
        assertThat(Arrays.copyOfRange(out.derivativeBytes(), 8, 12)).isEqualTo(WEBP);

        // ...decodable, longest edge within the max-dimension budget.
        BufferedImage derivative = ImageIO.read(new ByteArrayInputStream(out.derivativeBytes()));
        assertThat(derivative).as("derivative must be a decodable WebP").isNotNull();
        assertThat(Math.max(derivative.getWidth(), derivative.getHeight()))
                .isLessThanOrEqualTo(props.getMaxDimension());

        // Thumbnail is a valid WebP within the thumbnail budget.
        assertThat(out.thumbnailBytes()).startsWith(RIFF);
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(out.thumbnailBytes()));
        assertThat(thumb).as("thumbnail must be a decodable WebP").isNotNull();
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight()))
                .isLessThanOrEqualTo(props.getThumbnail());

        // Reported dimensions agree with the budget.
        assertThat(Math.max(out.width(), out.height())).isLessThanOrEqualTo(props.getMaxDimension());
    }

    @Test
    void exifAndGpsStrippedFromOutput() throws Exception {
        byte[] withExif = jpegWithExifGps(800, 600);
        String rawText = new String(withExif, StandardCharsets.ISO_8859_1);
        assertThat(rawText).as("fixture must actually carry EXIF + a GPS marker")
                .contains("Exif").contains("GPSINFOSECRET");

        MediaNormalizer.NormalizedImage out = normalizer.normalize(withExif);

        String derivativeText = new String(out.derivativeBytes(), StandardCharsets.ISO_8859_1);
        assertThat(derivativeText).as("re-encode must drop the EXIF/GPS payload")
                .doesNotContain("GPSINFOSECRET")
                .doesNotContain("Exif");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static byte[] jpegOf(int w, int h) throws Exception {
        return encodeGradient(w, h, "jpg");
    }

    private static byte[] gifOf(int w, int h) throws Exception {
        return encodeGradient(w, h, "gif");
    }

    private static byte[] encodeGradient(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }

    /** A PNG with a valid signature + a valid IHDR (correct CRC) declaring w×h, but NO IDAT. */
    private static byte[] craftPngHeaderOnly(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}); // PNG signature
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 2;   // colour type: truecolour RGB
        ihdr[10] = 0;  // compression method
        ihdr[11] = 0;  // filter method
        ihdr[12] = 0;  // interlace method
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
    private static byte[] jpegWithExifGps(int w, int h) throws Exception {
        byte[] base = jpegOf(w, h); // begins FF D8 (SOI)

        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});               // EXIF identifier
        content.writeBytes(new byte[]{'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08}); // big-endian TIFF, IFD@8
        content.writeBytes(new byte[]{0x00, 0x00});                            // 0 IFD entries
        content.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});                // next-IFD offset = 0
        content.writeBytes("GPSINFOSECRET".getBytes(StandardCharsets.US_ASCII)); // searchable payload
        byte[] c = content.toByteArray();
        int segLen = c.length + 2; // APP1 length field counts the 2 length bytes + content

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{base[0], base[1]});                       // SOI (FF D8)
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1});              // APP1 marker
        out.writeBytes(new byte[]{(byte) ((segLen >>> 8) & 0xFF), (byte) (segLen & 0xFF)}); // length
        out.writeBytes(c);
        out.write(base, 2, base.length - 2);                               // remainder of the JPEG
        return out.toByteArray();
    }
}
