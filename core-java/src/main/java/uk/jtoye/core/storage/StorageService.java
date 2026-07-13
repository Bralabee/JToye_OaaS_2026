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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client s3Client;
    private final StorageProperties properties;

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

    public StorageService(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    /**
     * Upload a product image with dimension validation.
     */
    public String upload(UUID tenantId, String pathPrefix, UUID entityId, MultipartFile file) {
        return upload(tenantId, pathPrefix, entityId, file, ImageType.PRODUCT);
    }

    /**
     * Upload an image with type-specific dimension validation.
     */
    public String upload(UUID tenantId, String pathPrefix, UUID entityId, MultipartFile file, ImageType imageType) {
        byte[] imageBytes = validateAndRead(file, imageType);

        String extension = getExtension(file.getOriginalFilename());
        String key = tenantId + "/" + pathPrefix + "/" + entityId + "/" + UUID.randomUUID() + extension;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(file.getContentType())
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(imageBytes)
        );

        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;
        log.info("Uploaded {} image: {} ({} bytes)", imageType, publicUrl, imageBytes.length);
        return publicUrl;
    }

    /**
     * Upload a file with a fixed name (e.g. logo, banner).
     */
    public String uploadNamed(UUID tenantId, String pathPrefix, UUID entityId, String name, MultipartFile file) {
        ImageType imageType = "logo".equals(name) ? ImageType.LOGO : ImageType.BANNER;
        byte[] imageBytes = validateAndRead(file, imageType);

        String extension = getExtension(file.getOriginalFilename());
        String key = tenantId + "/" + pathPrefix + "/" + entityId + "/" + name + extension;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(file.getContentType())
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(imageBytes)
        );

        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;
        log.info("Uploaded {} image: {} ({} bytes)", imageType, publicUrl, imageBytes.length);
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

    /**
     * Validates the file (size, type, magic bytes, dimensions) and returns its bytes.
     */
    private byte[] validateAndRead(MultipartFile file, ImageType imageType) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File too large. Maximum size: " + (properties.getMaxFileSizeBytes() / 1_048_576) + "MB");
        }

        // Read bytes once — reuse for magic check, dimension check, and upload
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
            log.warn("Content-type mismatch: claimed={} detected={}. Using detected type.", claimedType, detectedType);
        }

        // Verify image dimensions
        validateDimensions(imageBytes, imageType);

        return imageBytes;
    }

    /**
     * Detect actual content type from file magic bytes.
     */
    private String detectContentType(byte[] data) {
        if (data.length < 12) return null;

        if (startsWith(data, JPEG_MAGIC)) return "image/jpeg";
        if (startsWith(data, PNG_MAGIC)) return "image/png";
        if (startsWith(data, GIF_MAGIC)) return "image/gif";
        if (startsWith(data, RIFF_MAGIC) && regionMatches(data, 8, WEBP_MAGIC)) return "image/webp";

        return null;
    }

    /**
     * Validate image dimensions meet minimum requirements for the image type.
     */
    private void validateDimensions(byte[] imageBytes, ImageType imageType) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Could not read image. The file may be corrupted.");
            }

            int width = image.getWidth();
            int height = image.getHeight();

            if (width < imageType.minWidth || height < imageType.minHeight) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        imageType.message + " (uploaded: " + width + "x" + height + ")");
            }

            // Warn on excessively large images (not a hard error — client should compress)
            if (width > 4096 || height > 4096) {
                log.warn("Oversized image uploaded: {}x{} — consider client-side compression", width, height);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read image. The file may be corrupted.");
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

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".jpg";
    }
}
