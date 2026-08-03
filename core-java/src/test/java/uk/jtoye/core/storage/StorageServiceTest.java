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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StorageService.
 * Tests file validation (type, size, magic bytes, dimensions) and S3 key generation.
 */
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

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
        properties.setMaxFileSizeBytes(5_242_880); // 5MB
        properties.setAllowedContentTypes(List.of("image/jpeg", "image/png", "image/webp", "image/gif"));
        properties.getS3().setBucket("jtoye-images");
        properties.getS3().setPublicUrl("http://localhost:9000/jtoye-images");

        storageService = new StorageService(s3Client, properties, new MediaNormalizer(new MediaProperties()));
    }

    /**
     * Creates a valid JPEG byte array with real JPEG magic bytes and valid image data.
     */
    private byte[] createValidJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    /**
     * Creates a valid PNG byte array with real PNG magic bytes and valid image data.
     */
    private byte[] createValidPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    // ---- Upload: S3 Key Generation ----

    @Test
    @DisplayName("upload - Generates S3 key with tenant isolation (tenantId/prefix/entityId/uuid.ext)")
    void testUpload_GeneratesCorrectS3Key() throws Exception {
        byte[] jpegBytes = createValidJpeg(500, 500);

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", jpegBytes);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        String url = storageService.upload(tenantId, "products", entityId, file);

        // URL should contain tenant isolation path
        assertTrue(url.startsWith("http://localhost:9000/jtoye-images/"));
        assertTrue(url.contains(tenantId.toString()));
        assertTrue(url.contains("products"));
        assertTrue(url.contains(entityId.toString()));
        // Issue #445: the key extension is the DERIVATIVE's, not the client filename's — only the
        // normalized WebP is stored, so a ".jpg" upload is served from a ".webp" key.
        assertTrue(url.endsWith(".webp"), "expected a .webp derivative key, got: " + url);

        // Verify S3 was called with correct bucket
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals("jtoye-images", captor.getValue().bucket());
        assertTrue(captor.getValue().key().startsWith(tenantId.toString()));
    }

    // ---- Upload: File Type Validation ----

    @Test
    @DisplayName("upload - Rejects non-image file (text/plain with wrong magic bytes)")
    void testUpload_RejectsNonImage() {
        byte[] textContent = "This is not an image".getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file", "readme.txt", "text/plain", textContent);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> storageService.upload(tenantId, "products", entityId, file));

        assertTrue(ex.getReason().contains("Invalid image format"));
    }

    @Test
    @DisplayName("upload - Rejects file with spoofed content type (claims JPEG but is text)")
    void testUpload_RejectsSpoofedContentType() {
        byte[] textContent = "Not really a JPEG image file content here".getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.jpg", "image/jpeg", textContent);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> storageService.upload(tenantId, "products", entityId, file));

        assertTrue(ex.getReason().contains("Invalid image format"));
    }

    // ---- Upload: File Size Validation ----

    @Test
    @DisplayName("upload - Rejects oversized file")
    void testUpload_RejectsOversizedFile() throws Exception {
        // Create a valid JPEG header but make the total size exceed the limit
        byte[] jpegBytes = createValidJpeg(500, 500);
        // Set a very small limit so our valid image exceeds it
        properties.setMaxFileSizeBytes(100);

        MockMultipartFile file = new MockMultipartFile(
                "file", "big.jpg", "image/jpeg", jpegBytes);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> storageService.upload(tenantId, "products", entityId, file));

        assertTrue(ex.getReason().contains("File too large"));
    }

    @Test
    @DisplayName("upload - Rejects empty file")
    void testUpload_RejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.jpg", "image/jpeg", new byte[0]);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> storageService.upload(tenantId, "products", entityId, file));

        assertTrue(ex.getReason().contains("File is empty"));
    }

    // ---- Upload: Magic Bytes Validation ----

    @Test
    @DisplayName("upload - Accepts valid JPEG with correct magic bytes")
    void testUpload_AcceptsValidJpeg() throws Exception {
        byte[] jpegBytes = createValidJpeg(500, 500);

        MockMultipartFile file = new MockMultipartFile(
                "file", "food.jpg", "image/jpeg", jpegBytes);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        String url = storageService.upload(tenantId, "products", entityId, file);

        assertNotNull(url);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("upload - Accepts valid PNG with correct magic bytes")
    void testUpload_AcceptsValidPng() throws Exception {
        byte[] pngBytes = createValidPng(500, 500);

        MockMultipartFile file = new MockMultipartFile(
                "file", "food.png", "image/png", pngBytes);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(null);

        String url = storageService.upload(tenantId, "products", entityId, file);

        assertNotNull(url);
        // Issue #445: a PNG upload is normalized to WebP too — see the note on the key test above.
        assertTrue(url.endsWith(".webp"), "expected a .webp derivative key, got: " + url);
    }

    @Test
    @DisplayName("upload - Rejects file too small for magic byte detection")
    void testUpload_RejectsTooSmallFile() {
        byte[] tinyBytes = new byte[]{0x01, 0x02};

        MockMultipartFile file = new MockMultipartFile(
                "file", "tiny.jpg", "image/jpeg", tinyBytes);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> storageService.upload(tenantId, "products", entityId, file));

        assertTrue(ex.getReason().contains("Invalid image format"));
    }

    // ---- Upload: Dimension Validation ----

    @Test
    @DisplayName("upload - Rejects product image below minimum dimensions (400x400)")
    void testUpload_RejectsTooSmallDimensions() throws Exception {
        byte[] smallJpeg = createValidJpeg(100, 100); // Below 400x400 minimum

        MockMultipartFile file = new MockMultipartFile(
                "file", "small.jpg", "image/jpeg", smallJpeg);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> storageService.upload(tenantId, "products", entityId, file));

        assertTrue(ex.getReason().contains("400x400"));
    }

    // ---- Delete ----

    @Test
    @DisplayName("delete - Handles null URL gracefully (no exception)")
    void testDelete_NullUrl() {
        assertDoesNotThrow(() -> storageService.delete(null));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("delete - Handles empty URL gracefully (no exception)")
    void testDelete_EmptyUrl() {
        assertDoesNotThrow(() -> storageService.delete(""));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("delete - Handles blank URL gracefully (no exception)")
    void testDelete_BlankUrl() {
        assertDoesNotThrow(() -> storageService.delete("   "));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("delete - Skips external URL (not from our S3)")
    void testDelete_ExternalUrl() {
        assertDoesNotThrow(() -> storageService.delete("https://example.com/other-image.jpg"));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("delete - Deletes valid S3 object by extracting key from URL")
    void testDelete_ValidS3Url() {
        String key = tenantId + "/products/" + entityId + "/image.jpg";
        String fullUrl = "http://localhost:9000/jtoye-images/" + key;

        storageService.delete(fullUrl);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertEquals("jtoye-images", captor.getValue().bucket());
        assertEquals(key, captor.getValue().key());
    }

    @Test
    @DisplayName("delete - Handles S3 error gracefully (logs warning, does not throw)")
    void testDelete_S3Error() {
        String key = tenantId + "/products/" + entityId + "/image.jpg";
        String fullUrl = "http://localhost:9000/jtoye-images/" + key;

        doThrow(new RuntimeException("S3 connection failed"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        assertDoesNotThrow(() -> storageService.delete(fullUrl));
    }
}
