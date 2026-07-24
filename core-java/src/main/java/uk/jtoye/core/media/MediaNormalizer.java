package uk.jtoye.core.media;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.media.exception.DecompressionBombException;
import uk.jtoye.core.media.exception.UnreadableImageException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * Pure-transform stage of the Phase 24 safe upload pipeline (IMG-02). Given the
 * raw quarantine bytes it runs the async-worker diagram stages b-f:
 *
 * <ol>
 *   <li><b>(b) sniff</b> — reuse the 4-signature magic-byte allowlist and accept
 *       only jpeg/png/webp for the STORED derivative (gif is deliberately vetoed;
 *       never trust {@code file.getContentType()}); mitigates T-24-02.</li>
 *   <li><b>(c) bomb guard</b> — header-only dimension read via
 *       {@link ImageReader#getWidth}/{@link ImageReader#getHeight} and reject
 *       above {@code jtoye.media.max-megapixels} BEFORE any pixel buffer is
 *       allocated (RESEARCH Pitfall 2); mitigates T-24-01.</li>
 *   <li><b>(d) decode-verify</b> — {@code ImmutableImage.loader().fromBytes(...)};
 *       an undecodable input becomes {@link UnreadableImageException}.</li>
 *   <li><b>(e) resize</b> — aspect-fit within {@code max-dimension}; the
 *       decode→re-encode drops all source EXIF/GPS metadata (T-24-03, A2).</li>
 *   <li><b>(f) encode</b> — a WebP derivative + a WebP thumbnail via cwebp.</li>
 * </ol>
 *
 * <p>No DB, no MinIO — bytes in, bytes out — so the async worker (24-04) can call
 * it after pinning the tenant GUC. Every dimension/quality figure is read from
 * {@link MediaProperties}; this class carries NO numeric image budget literal
 * (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8).
 */
@Component
public class MediaNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MediaNormalizer.class);

    // The 4-signature magic-byte allowlist, mirrored from
    // storage/StorageService.detectContentType (RESEARCH "Don't Hand-Roll": no Tika).
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] RIFF_MAGIC = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MAGIC = {0x57, 0x45, 0x42, 0x50};

    // Allowlist for the STORED derivative: jpeg/png/webp only. gif is intentionally
    // excluded (a gif's animation/first-frame is not a product-image derivative).
    private static final Set<String> ALLOWED_INPUT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    // cwebp method (compression effort) 0-6; 6 = best compression for the storefront
    // derivative. Not a size/quality *budget* — an encoder effort constant.
    private static final int WEBP_METHOD = 6;

    private final MediaProperties props;

    public MediaNormalizer(MediaProperties props) {
        this.props = props;
    }

    /**
     * Validate + normalize {@code raw} into a WebP derivative + thumbnail.
     *
     * @throws UnreadableImageException  input is not an allowlisted image or fails decode
     * @throws DecompressionBombException declared dimensions exceed the megapixel cap
     */
    public NormalizedImage normalize(byte[] raw) {
        // (b) magic-byte sniff + allowlist veto — never trust the client content-type.
        String detected = detectContentType(raw);
        if (detected == null || !ALLOWED_INPUT_TYPES.contains(detected)) {
            throw new UnreadableImageException(
                    "Upload is not an allowed image type (jpeg/png/webp); detected=" + detected);
        }

        // (c) header-only decompression-bomb guard — BEFORE any decode.
        guardAgainstDecompressionBomb(raw);

        // (d) decode-to-verify (re-decode drops source metadata).
        ImmutableImage source;
        try {
            source = ImmutableImage.loader().fromBytes(raw);
        } catch (IOException | RuntimeException e) {
            throw new UnreadableImageException("Failed to decode image", e);
        }

        // (e) aspect-fit within the configured max dimension (bound never upscales).
        ImmutableImage fitted = source.bound(props.getMaxDimension(), props.getMaxDimension());

        // (f) encode the WebP derivative + a separately-bounded WebP thumbnail.
        WebpWriter derivativeWriter = WebpWriter.DEFAULT.withQ(props.getQuality()).withM(WEBP_METHOD);
        byte[] derivativeBytes = encodeWebp(fitted, derivativeWriter);

        ImmutableImage thumb = fitted.bound(props.getThumbnail(), props.getThumbnail());
        byte[] thumbnailBytes = encodeWebp(thumb, WebpWriter.DEFAULT.withQ(props.getQuality()));

        log.debug("Normalized {} upload -> WebP derivative {}x{} ({} bytes), thumbnail ({} bytes)",
                detected, fitted.width, fitted.height, derivativeBytes.length, thumbnailBytes.length);

        return new NormalizedImage(derivativeBytes, thumbnailBytes, fitted.width, fitted.height);
    }

    /**
     * Header-only megapixel cap (RESEARCH Pitfall 2). Reads the declared
     * dimensions from the first frame WITHOUT decoding pixels and rejects a bomb
     * before {@code width*height*4} bytes could ever be allocated.
     */
    private void guardAgainstDecompressionBomb(byte[] raw) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (iis == null) {
                throw new UnreadableImageException("No image input stream for upload");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new UnreadableImageException("No ImageIO reader for upload");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                long pixels = (long) reader.getWidth(0) * (long) reader.getHeight(0);
                long capPixels = (long) props.getMaxMegapixels() * 1_000_000L;
                if (pixels > capPixels) {
                    throw new DecompressionBombException(
                            "Declared image dimensions " + reader.getWidth(0) + "x" + reader.getHeight(0)
                                    + " (" + pixels + " px) exceed the " + props.getMaxMegapixels()
                                    + " megapixel cap");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            // Header could not be read at all — treat as unreadable, not a bomb.
            throw new UnreadableImageException("Failed to read image header", e);
        }
    }

    private static byte[] encodeWebp(ImmutableImage image, WebpWriter writer) {
        try {
            return image.forWriter(writer).bytes();
        } catch (IOException e) {
            // Compress-fail is a hard veto (SPEC Q2 stage 4); surface for FAILED mapping.
            throw new UnreadableImageException("WebP encode failed", e);
        }
    }

    /** Magic-byte content-type sniff — jpeg/png/webp (+gif detection, which the allowlist then vetoes). */
    private static String detectContentType(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if (startsWith(data, JPEG_MAGIC)) {
            return "image/jpeg";
        }
        if (startsWith(data, PNG_MAGIC)) {
            return "image/png";
        }
        if (startsWith(data, RIFF_MAGIC) && regionMatches(data, 8, WEBP_MAGIC)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean regionMatches(byte[] data, int offset, byte[] target) {
        if (data.length < offset + target.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (data[offset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The validated, normalized output: the stored WebP derivative + its WebP
     * thumbnail, plus the derivative's pixel dimensions (for width/height hints
     * that reduce storefront CLS — D-07).
     */
    public record NormalizedImage(byte[] derivativeBytes, byte[] thumbnailBytes, int width, int height) {
    }
}
