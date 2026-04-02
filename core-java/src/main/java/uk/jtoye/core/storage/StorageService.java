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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client s3Client;
    private final StorageProperties properties;

    public StorageService(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    /**
     * Upload a file to S3/MinIO with tenant-isolated path.
     *
     * @param tenantId   the tenant owning this resource
     * @param pathPrefix e.g. "products" or "shops"
     * @param entityId   the entity (product/shop) ID
     * @param file       the uploaded file
     * @return the public URL of the uploaded image
     */
    public String upload(UUID tenantId, String pathPrefix, UUID entityId, MultipartFile file) {
        validate(file);

        String extension = getExtension(file.getOriginalFilename());
        String key = tenantId + "/" + pathPrefix + "/" + entityId + "/" + UUID.randomUUID() + extension;

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getS3().getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException e) {
            log.error("Failed to read uploaded file: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process uploaded file");
        }

        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;
        log.info("Uploaded image: {} ({} bytes)", publicUrl, file.getSize());
        return publicUrl;
    }

    /**
     * Upload a file with a fixed name (e.g. logo, banner) — replaces any previous file at same key.
     */
    public String uploadNamed(UUID tenantId, String pathPrefix, UUID entityId, String name, MultipartFile file) {
        validate(file);

        String extension = getExtension(file.getOriginalFilename());
        String key = tenantId + "/" + pathPrefix + "/" + entityId + "/" + name + extension;

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getS3().getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException e) {
            log.error("Failed to read uploaded file: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process uploaded file");
        }

        String publicUrl = properties.getS3().getPublicUrl() + "/" + key;
        log.info("Uploaded image: {} ({} bytes)", publicUrl, file.getSize());
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

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File too large. Maximum size: " + (properties.getMaxFileSizeBytes() / 1_048_576) + "MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !properties.getAllowedContentTypes().contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid file type. Allowed: " + String.join(", ", properties.getAllowedContentTypes()));
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".jpg";
    }
}
