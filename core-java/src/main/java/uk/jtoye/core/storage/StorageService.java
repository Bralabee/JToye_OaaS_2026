package uk.jtoye.core.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.exception.UnreadableImageException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client s3Client;
    private final StorageProperties properties;
    private final MediaNormalizer mediaNormalizer;

    /**
     * Issue #445: every synchronous upload is stored as the Phase-24 normalized derivative, so
     * both the object key's extension and its Content-Type are fixed and server-produced. These
     * are format identifiers, not a tunable budget — the budget itself (dimensions, quality,
     * megapixel cap) lives in {@code jtoye.media.*} and is read by {@link MediaNormalizer}.
     */
    private static final String DERIVATIVE_CONTENT_TYPE = "image/webp";
    private static final String DERIVATIVE_EXTENSION = ".webp";

    // Magic bytes for image format verification
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] GIF_MAGIC = {0x47, 0x49, 0x46};
    // WebP: starts with RIFF....WEBP
    private static final byte[] RIFF_MAGIC = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MAGIC = {0x57, 0x45, 0x42, 0x50};

    /**
     * Minimum image dimensions per image type.
     * Food retail needs decent quality — tiny images look unprofessional.
     */
    public enum ImageType {
        PRODUCT(400, 400, "Product images must be at least 400x400 pixels"),
        LOGO(100, 100, "Logos must be at least 100x100 pixels"),
        BANNER(600, 200, "Banners must be at least 600x200 pixels");

        final int minWidth;
        final int minHeight;
        final String message;

        ImageType(int minWidth, int minHeight, String message) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.message = message;
        }
    }

    private static final Map<String, String> MAGIC_TO_CONTENT_TYPE = Map.of(
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp"
    );

    public StorageService(S3Client s3Client, StorageProperties properties, MediaNormalizer mediaNormalizer) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.mediaNormalizer = mediaNormalizer;
    }

    /**
     * Upload a product image with dimension validation.
     */
    public String upload(UUID tenantId, String pathPrefix, UUID entityId, MultipartFile file) {
        return upload(tenantId, pathPrefix, entityId, file, ImageType.PRODUCT);
    }

    /**
     * Upload an image with type-specific dimension validation.
     *
     * <p>Issue #445: the stored object is the NORMALIZED WebP derivative, never the raw upload
     * (see {@link #validateAndNormalize}). The key therefore always ends {@code .webp} and the
     * object's {@code Content-Type} is the produced type, not the client's header.
     *
     * <p>Issue #489 scope note: this path is NOT affected. Its key carries a per-upload
     * {@code UUID.randomUUID()}, so no two uploads ever share a key and the {@code immutable}
     * cache-control below has always been an honest statement about the object. The issue
     * named it alongside {@link #uploadNamed}; only {@code uploadNamed} was keyed
     * deterministically. Pinned by
     * {@code ShopBrandImageKeyTest.productGalleryKeyWasAlreadyUniquePerUpload}.
     */
    public String upload(UUID tenantId, String pathPrefix, UUID entityId, MultipartFile file, ImageType imageType) {
        byte[] imageBytes = validateAndNormalize(file, imageType);

        String key = tenantId + "/" + pathPrefix + "/" + entityId + "/" + UUID.randomUUID() + DERIVATIVE_EXTENSION;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(DERIVATIVE_CONTENT_TYPE)
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(imageBytes)
        );

        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;
        log.info("Uploaded {} image: {} ({} bytes, normalized WebP)", imageType, publicUrl, imageBytes.length);
        return publicUrl;
    }

    /**
     * Upload a NAMED brand image (shop logo, shop banner) at a CONTENT-ADDRESSED key.
     *
     * <p>Issue #445: as {@link #upload}, the stored object is the normalized WebP derivative.
     * The caller ({@code ShopService.uploadLogo} / {@code uploadBanner}) deletes the previous
     * object by its stored URL BEFORE calling this, so the extension change from the source
     * format to {@code .webp} cannot orphan the old object.
     *
     * <p><b>Issue #489 — why the key carries a sha256.</b> The key used to be
     * {@code <tenant>/shops/<shopId>/logo.webp}: fixed for the life of the shop, while the
     * object was (and still is) served {@code public, max-age=31536000, immutable}. That
     * combination is a contradiction — {@code immutable} tells every browser and CDN the bytes
     * at this URL will never change, and a vendor re-uploading their logo changed exactly
     * those bytes. The vendor could then keep seeing the old logo for up to a year with no
     * in-product way to force a refresh. #445 made it unconditional: before it the extension
     * followed the client's filename, so a jpeg→png swap happened to dodge the collision.
     *
     * <p>The fix is option 1 of the issue — content addressing, the same device the Phase-24
     * {@code media_asset} model already uses for quarantine keys ({@code <tenant>/quarantine/
     * <sha256>.<ext>}). The key becomes {@code <tenant>/<prefix>/<entityId>/<name>-<sha256>.webp}
     * and {@code immutable} becomes true BY CONSTRUCTION: the bytes at a key are the bytes the
     * key was derived from. Dropping {@code immutable} (option 2) would have paid for the fix
     * with the storefront's LCP; a query-string cache-buster (option 3) would have left the
     * object genuinely mislabelled.
     *
     * <p>Two properties of the key are load-bearing and are pinned by
     * {@code ShopBrandImageKeyTest}:
     * <ul>
     *   <li>the hash is of the STORED DERIVATIVE, not of the raw upload — so a later change to
     *       the {@code jtoye.media.*} budget, which produces different derivative bytes from
     *       the same source file, also lands on a different key rather than silently
     *       overwriting an object promised immutable;</li>
     *   <li>the {@code <name>-} segment is retained — the same image uploaded as both logo and
     *       banner must stay two objects, or {@code removeLogo} would delete the banner too.</li>
     * </ul>
     *
     * <p>No migration is needed for shops still holding a {@code .../logo.webp} url: the url is
     * stored on the shop row and keeps resolving, and the caller's delete-by-stored-url runs
     * before this method, so the next upload retires the old key cleanly.
     */
    public String uploadNamed(UUID tenantId, String pathPrefix, UUID entityId, String name, MultipartFile file) {
        ImageType imageType = "logo".equals(name) ? ImageType.LOGO : ImageType.BANNER;
        byte[] imageBytes = validateAndNormalize(file, imageType);

        String key = tenantId + "/" + pathPrefix + "/" + entityId + "/"
                + name + "-" + sha256Hex(imageBytes) + DERIVATIVE_EXTENSION;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(DERIVATIVE_CONTENT_TYPE)
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(imageBytes)
        );

        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;
        log.info("Uploaded {} image: {} ({} bytes, normalized WebP)", imageType, publicUrl, imageBytes.length);
        return publicUrl;
    }

    /**
     * URL marker identifying a seeder-owned demo image (see
     * {@link #putSeedImage}). Vendor uploads share the {@code /products/} segment
     * but never carry {@code /products/seed/}, so this marker is what lets the
     * dev seeder distinguish its own prior seeds from a genuine vendor upload.
     */
    public static final String SEED_URL_MARKER = "/products/seed/";

    /**
     * Public-URL prefix shared by EVERY product upload (vendor and seed) for a
     * tenant: {@code <publicUrl>/<tenantId>/products/}.
     */
    public String productUploadUrlPrefix(UUID tenantId) {
        return properties.getS3().getPublicUrl() + "/" + tenantId + "/products/";
    }

    /**
     * Public-URL prefix for a SPECIFIC product's own uploads:
     * {@code <publicUrl>/<tenantId>/products/<productId>/}. This is the precise
     * signature of a genuine vendor upload — {@link #upload} keys every vendor
     * image under the product's OWN id ({@code entityId}). The dev demo seeder's
     * seeder-owns policy uses this: a URL under {@code /products/} but a
     * DIFFERENT entity id is a foreign/legacy artifact the seeder owns, whereas a
     * URL under the product's own prefix (lacking {@link #SEED_URL_MARKER}) is a
     * genuine vendor upload that must never be clobbered.
     */
    public String productUploadUrlPrefix(UUID tenantId, UUID productId) {
        return productUploadUrlPrefix(tenantId) + productId + "/";
    }

    /**
     * Idempotently upload a bundled demo-seed image to a DETERMINISTIC key
     * ({@code <tenantId>/products/seed/<filename>}) and return its
     * browser-reachable public URL. Dev-only demo-seeding seam
     * ({@link uk.jtoye.core.dev.DemoDataSeeder}): the bytes are license-vetted,
     * visually-verified classpath assets bundled at build time — never runtime or
     * user input — so this deliberately does NOT run the vendor upload's
     * MultipartFile/dimension pipeline. It reuses the same bucket, public-URL
     * mechanism and immutable cache-control the vendor path uses, and skips the
     * PUT when the object already exists (HeadObject) so repeated dev boots don't
     * re-upload. The magic-byte {@link #detectContentType} check is retained as a
     * sanity guard against a corrupt/non-image asset.
     *
     * <p>Issue #489 scope note: the key IS deterministic here, but the {@code immutable}
     * cache-control is still honest, because the HeadObject short-circuit above means the
     * bytes at that key are written once and never replaced. (Should that skip ever be
     * removed, this becomes the same defect {@link #uploadNamed} had, and the same content
     * addressing is the fix.)
     */
    public String putSeedImage(UUID tenantId, String filename, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seed image bytes are empty: " + filename);
        }
        String detected = detectContentType(bytes);
        if (detected == null || !properties.getAllowedContentTypes().contains(detected)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Seed image is not a recognised image type: " + filename);
        }

        String bucket = properties.getS3().getBucket();
        // Deterministic key so re-seeds are idempotent and the URL is stable.
        String key = tenantId + SEED_URL_MARKER + filename;
        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;

        boolean exists;
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            exists = true;
        } catch (NoSuchKeyException e) {
            exists = false;
        } catch (S3Exception e) {
            // MinIO / some S3-compatible stores surface a missing object as a
            // generic 404 rather than NoSuchKeyException; treat only 404 as absent.
            if (e.statusCode() == 404) {
                exists = false;
            } else {
                throw e;
            }
        }

        if (exists) {
            log.info("Seed image already present, skipping upload: {}", key);
            return publicUrl;
        }

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType != null ? contentType : detected)
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(bytes)
        );
        log.info("Uploaded seed image: {} ({} bytes)", publicUrl, bytes.length);
        return publicUrl;
    }

    /**
     * Delete an image from S3/MinIO by its public URL.
     */
    public void delete(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return;

        String publicUrlPrefix = properties.getS3().getPublicUrl() + "/";
        if (!imageUrl.startsWith(publicUrlPrefix)) {
            log.debug("Skipping delete for external URL: {}", imageUrl);
            return;
        }

        String key = imageUrl.substring(publicUrlPrefix.length());
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getS3().getBucket())
                    .key(key)
                    .build());
            log.info("Deleted image from storage: {}", key);
        } catch (Exception e) {
            log.warn("Failed to delete image {}: {}", key, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Key-addressed I/O for the Phase 24 async media pipeline. StorageService
    // stays the single owner of MinIO I/O; the worker/service layer never talks
    // to S3Client directly. Content-Type is always the DETECTED/produced type
    // (image/webp for a derivative) — NEVER the client-supplied file.getContentType(),
    // closing the content-type-spoof anti-pattern (T-24-02 / RESEARCH).
    // ------------------------------------------------------------------

    /**
     * Store {@code bytes} at a server-generated {@code objectKey} (e.g. quarantine
     * key on accept, or {@code <tenant>/media/<id>.webp} for an ACTIVE derivative)
     * and return its browser-reachable public URL.
     *
     * <p>Issue #489 scope note: the async pipeline's keys are already collision-free per
     * distinct content — the quarantine key IS the raw sha256, and a derivative key carries
     * the asset id, which a fresh upload always gets fresh (the dedup path reuses the row only
     * for byte-identical raw input). So {@code immutable} is honest on this path too.
     */
    public String putBytes(String objectKey, byte[] bytes, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(objectKey)
                        .contentType(contentType)   // detected/produced type — never the client header
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(bytes));
        log.info("Stored object by key: {} ({} bytes, {})", objectKey, bytes.length, contentType);
        return urlForKey(objectKey);
    }

    /**
     * Read the raw bytes of an object by key (the worker reads a quarantined upload
     * before normalising it).
     */
    public byte[] getBytes(String objectKey) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(objectKey)
                        .build())
                .asByteArray();
    }

    /**
     * Delete an object by its raw key (quarantine cleanup and the reference-count-0
     * physical delete — IMG-01). Best-effort: a missing object / transient S3 error
     * is logged, never thrown, so a cleanup failure cannot abort the caller's
     * transaction.
     *
     * <p>Behaviour is unchanged for every existing caller; it simply discards the
     * {@link #deleteByKeyChecked(String)} result.
     */
    public void deleteByKey(String objectKey) {
        deleteByKeyChecked(objectKey);
    }

    /**
     * Checked delete (27-01 / F-5): the same best-effort delete, but it reports whether the
     * object is actually gone.
     *
     * <p>{@link #deleteByKey(String)} catches every exception and only logs, so no caller of it
     * can ever learn whether the delete worked. {@code MediaQuarantineRetentionSweep} needs that
     * fact, because "these bytes are gone" is the ONLY termination condition of its sentinel: if
     * it stamped {@code quarantine_reclaimed_at} unconditionally, a transient S3 error would
     * strand the object forever with nothing to complain.
     *
     * <p>Never throws — the contract that keeps a cleanup failure from aborting a caller's
     * transaction is preserved.
     *
     * @return {@code true} iff the object was removed (or was already absent — a blank key and a
     *         successful delete of a missing object are both "gone"); {@code false} on any error.
     */
    public boolean deleteByKeyChecked(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return true;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getS3().getBucket())
                    .key(objectKey)
                    .build());
            log.info("Deleted object from storage by key: {}", objectKey);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete object {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    /** The browser-reachable public URL for a stored object key. */
    public String urlForKey(String objectKey) {
        return properties.getS3().getPublicUrl() + "/" + objectKey;
    }

    /**
     * Validate the upload and return the bytes that may be stored — the NORMALIZED WebP
     * derivative, never the raw upload (issue #445 / QA-A F-H3-RAWIMG).
     *
     * <p>Before this, the three legacy synchronous endpoints
     * ({@code POST /products/{id}/images}, {@code /shops/{id}/logo}, {@code /shops/{id}/banner})
     * PUT the client's bytes verbatim with the client's declared {@code Content-Type}. That
     * contradicted the Phase-24 design decision that <em>raw uploads are never canonical</em>,
     * and skipped three guards the async pipeline applies: the header-read decompression-bomb
     * cap, the EXIF/GPS strip, and the WebP transcode. All three now run here, at the single
     * choke point every one of those endpoints passes through, so no current or future caller
     * of {@link #upload}/{@link #uploadNamed} can bypass them.
     *
     * <p>Order is load-bearing:
     * <ol>
     *   <li>empty / size / magic-byte allowlist first — these were already enforced and their
     *       400 contract (message included) is preserved verbatim;</li>
     *   <li>then {@link MediaNormalizer#normalize(byte[], java.util.Set)}, whose FIRST act is the
     *       header-only megapixel guard — so a bomb is refused BEFORE any pixel buffer is
     *       allocated. The old code decoded first ({@code ImageIO.read} inside the dimension
     *       check), which is precisely the vector;</li>
     *   <li>the minimum-dimension rule LAST, against the derivative. That rule exists so a
     *       served image is not embarrassingly small, and the derivative is what is served.</li>
     * </ol>
     */
    private byte[] validateAndNormalize(MultipartFile file, ImageType imageType) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File too large. Maximum size: " + (properties.getMaxFileSizeBytes() / 1_048_576) + "MB");
        }

        // Read bytes once — reuse for the magic check and the normalize pass
        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read uploaded file: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file");
        }

        // Verify magic bytes match claimed content type (prevents spoofed uploads)
        String detectedType = detectContentType(imageBytes);
        String claimedType = file.getContentType();
        if (detectedType == null || !properties.getAllowedContentTypes().contains(detectedType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid image format. Allowed: JPEG, PNG, WebP, GIF");
        }
        if (claimedType != null && !claimedType.equals(detectedType)) {
            // No longer merely cosmetic: the claimed type is now DISCARDED rather than written
            // onto the public object, closing the stored-content-type spoof (T-24-02) on this path.
            log.warn("Content-type mismatch: claimed={} detected={}. Storing the produced type ({}).",
                    claimedType, detectedType, DERIVATIVE_CONTENT_TYPE);
        }

        // Bomb guard -> decode-verify -> EXIF-dropping re-encode -> WebP derivative.
        MediaNormalizer.NormalizedImage normalized;
        try {
            normalized = mediaNormalizer.normalize(imageBytes, MediaNormalizer.LEGACY_SYNC_INPUT_TYPES);
        } catch (UnreadableImageException e) {
            // Preserve the pre-existing 400 contract for an undecodable/unsupported image.
            // DecompressionBombException deliberately propagates: it is a NEW rejection with no
            // prior contract, and GlobalExceptionHandler maps it to a typed RFC 7807 422.
            log.warn("Rejected {} upload: {}", imageType, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read image. The file may be corrupted.");
        }

        validateDerivativeDimensions(normalized, imageType);

        return normalized.derivativeBytes();
    }

    /**
     * Detect actual content type from file magic bytes (jpeg/png/gif/webp allowlist),
     * or {@code null} if unrecognised. Public so the Phase 24 media accept
     * ({@code MediaAssetService.acceptQuarantineAndQueue}) reuses the SINGLE magic-byte
     * owner for the quarantine object's Content-Type rather than trusting the client
     * header (the async worker re-sniffs + decode-verifies authoritatively).
     */
    public String detectContentType(byte[] data) {
        if (data.length < 12) return null;

        if (startsWith(data, JPEG_MAGIC)) return "image/jpeg";
        if (startsWith(data, PNG_MAGIC)) return "image/png";
        if (startsWith(data, GIF_MAGIC)) return "image/gif";
        if (startsWith(data, RIFF_MAGIC) && regionMatches(data, 8, WEBP_MAGIC)) return "image/webp";

        return null;
    }

    /**
     * Validate that the DERIVATIVE meets the minimum requirements for the image type.
     *
     * <p>Issue #445: this used to run {@code ImageIO.read} over the raw upload — a full decode,
     * which is the decompression-bomb vector it now sits behind. The normalizer has already
     * decoded and re-encoded under the {@code jtoye.media.*} budget by the time this runs, so
     * the dimensions are read from its output for free; there is no second decode, and the rule
     * is now applied to the image users actually receive.
     *
     * <p>The old "oversized image, consider client-side compression" WARN is gone because it can
     * no longer fire: {@code max-dimension} bounds every derivative below that threshold.
     */
    private void validateDerivativeDimensions(MediaNormalizer.NormalizedImage normalized, ImageType imageType) {
        if (normalized.width() < imageType.minWidth || normalized.height() < imageType.minHeight) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    imageType.message + " (uploaded: " + normalized.width() + "x" + normalized.height() + ")");
        }
    }

    /**
     * Lowercase hex SHA-256 of {@code data} — the content address used by
     * {@link #uploadNamed}'s object key (issue #489). Same digest and encoding as
     * {@code MediaUploadController.sha256Hex}, which produces {@code media_asset.sha256}
     * and the Phase-24 quarantine key, so the two content-addressing schemes agree.
     */
    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS-required MessageDigest set; unreachable on any JRE.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private boolean regionMatches(byte[] data, int offset, byte[] target) {
        if (data.length < offset + target.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (data[offset + i] != target[i]) return false;
        }
        return true;
    }

    // getExtension(String) removed with issue #445: the stored object's extension is no longer
    // derived from the CLIENT-supplied filename. Every synchronous upload is stored as the
    // normalized WebP derivative, so the extension is DERIVATIVE_EXTENSION unconditionally.
}
