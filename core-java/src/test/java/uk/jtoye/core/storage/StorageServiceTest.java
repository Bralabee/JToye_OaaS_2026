package uk.jtoye.core.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;
import uk.jtoye.core.security.TenantContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StorageService.
 * Tests file validation (type, size, magic bytes, dimensions) and object key generation.
 */
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private BlobObjectStore store;

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
        properties.getBlob().setPublicUrl("http://localhost:10000/devstoreaccount1/jtoye-images");

        storageService = new StorageService(store, properties, new MediaNormalizer(new MediaProperties()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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

    // ---- Upload: Object Key Generation ----

    @Test
    @DisplayName("upload - Generates object key with tenant isolation (tenantId/prefix/entityId/uuid.ext)")
    void testUpload_GeneratesCorrectObjectKey() throws Exception {
        byte[] jpegBytes = createValidJpeg(500, 500);

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", jpegBytes);


        String url = storageService.upload(tenantId, "products", entityId, file);

        // URL should contain tenant isolation path
        assertTrue(url.startsWith("http://localhost:10000/devstoreaccount1/jtoye-images/"));
        assertTrue(url.contains(tenantId.toString()));
        assertTrue(url.contains("products"));
        assertTrue(url.contains(entityId.toString()));
        // Issue #445: the key extension is the DERIVATIVE's, not the client filename's — only the
        // normalized WebP is stored, so a ".jpg" upload is served from a ".webp" key.
        assertTrue(url.endsWith(".webp"), "expected a .webp derivative key, got: " + url);

        // Verify the store was called with the public container, the tenant-first key, the
        // produced content type and the immutable cache control
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).put(eq("jtoye-images"), keyCaptor.capture(), any(byte[].class),
                eq("image/webp"), eq("public, max-age=31536000, immutable"));
        assertTrue(keyCaptor.getValue().startsWith(tenantId.toString()));
        assertEquals("http://localhost:10000/devstoreaccount1/jtoye-images/" + keyCaptor.getValue(), url);
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


        String url = storageService.upload(tenantId, "products", entityId, file);

        assertNotNull(url);
        verify(store).put(anyString(), anyString(), any(byte[].class), anyString(), anyString());
    }

    @Test
    @DisplayName("upload - Accepts valid PNG with correct magic bytes")
    void testUpload_AcceptsValidPng() throws Exception {
        byte[] pngBytes = createValidPng(500, 500);

        MockMultipartFile file = new MockMultipartFile(
                "file", "food.png", "image/png", pngBytes);


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
        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    @Test
    @DisplayName("delete - Handles empty URL gracefully (no exception)")
    void testDelete_EmptyUrl() {
        assertDoesNotThrow(() -> storageService.delete(""));
        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    @Test
    @DisplayName("delete - Handles blank URL gracefully (no exception)")
    void testDelete_BlankUrl() {
        assertDoesNotThrow(() -> storageService.delete("   "));
        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    @Test
    @DisplayName("delete - Skips external URL (not from our store)")
    void testDelete_ExternalUrl() {
        assertDoesNotThrow(() -> storageService.delete("https://example.com/other-image.jpg"));
        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    @Test
    @DisplayName("delete - Deletes valid stored object by extracting key from URL")
    void testDelete_ValidStoredUrl() {
        String key = tenantId + "/products/" + entityId + "/image.jpg";
        String fullUrl = "http://localhost:10000/devstoreaccount1/jtoye-images/" + key;

        // D-09: every production caller runs with the owning tenant in context; the delete is
        // only honoured when the key's tenant segment is that tenant.
        withTenant(tenantId, () -> storageService.delete(fullUrl));

        verify(store).deleteIfExists("jtoye-images", key);
    }

    @Test
    @DisplayName("delete - Handles storage error gracefully (logs warning, does not throw)")
    void testDelete_StorageError() {
        String key = tenantId + "/products/" + entityId + "/image.jpg";
        String fullUrl = "http://localhost:10000/devstoreaccount1/jtoye-images/" + key;

        doThrow(new RuntimeException("storage connection failed"))
                .when(store).deleteIfExists(anyString(), anyString());

        // The tenant is set so the store IS reached and the catch is what is exercised; without
        // it the D-09 guard would return first and this test would pass without testing anything.
        withTenant(tenantId, () -> assertDoesNotThrow(() -> storageService.delete(fullUrl)));
        verify(store).deleteIfExists("jtoye-images", key);
    }

    // ---- Delete: the D-09 tenant guard (Phase 36) ----
    //
    // ProductMapper, ShopMapper and ReviewService persist client-supplied image URLs, and every
    // tenant's images share one public container. Before D-09, delete(url) removed any key under
    // the public prefix, so tenant A could delete tenant B's image by saving B's URL on its own
    // row and then removing it.

    private static final String PUBLIC = "http://localhost:10000/devstoreaccount1/jtoye-images/";

    @Test
    @DisplayName("delete (D-09) - own tenant's key is deleted exactly once from the public container")
    void d09OwnTenantKeyIsDeleted() {
        String key = tenantId + "/products/" + entityId + "/x.webp";

        withTenant(tenantId, () -> storageService.delete(PUBLIC + key));

        verify(store, times(1)).deleteIfExists("jtoye-images", key);
        verifyNoMoreInteractions(store);
    }

    @Test
    @DisplayName("delete (D-09) - another tenant's key is refused and the WARN names both tenant ids")
    void d09ForeignTenantKeyIsRefused() {
        UUID other = UUID.randomUUID();
        String key = other + "/products/" + entityId + "/x.webp";

        List<ILoggingEvent> events = captureStorageLog(
                () -> withTenant(tenantId, () -> storageService.delete(PUBLIC + key)));

        verify(store, never()).deleteIfExists(anyString(), anyString());
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains(other.toString())
                        && e.getFormattedMessage().contains(tenantId.toString())),
                "a WARN naming the key tenant and the context tenant, got: " + messages(events));
    }

    @Test
    @DisplayName("delete (D-09) - no tenant context: refused (fail closed) with a WARN")
    void d09NoTenantContextIsRefused() {
        TenantContext.clear();
        String key = tenantId + "/products/" + entityId + "/x.webp";

        List<ILoggingEvent> events = captureStorageLog(() -> storageService.delete(PUBLIC + key));

        verify(store, never()).deleteIfExists(anyString(), anyString());
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "a WARN for the refused delete, got: " + messages(events));
    }

    @Test
    @DisplayName("delete (D-09) - a key with no tenant segment is refused")
    void d09KeyWithoutTenantSegmentIsRefused() {
        withTenant(tenantId, () -> storageService.delete(PUBLIC + "nokey"));
        withTenant(tenantId, () -> storageService.delete(PUBLIC + "/" + tenantId + "/products/x.webp"));

        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    @Test
    @DisplayName("delete (D-09) - a URL outside the public origin is still skipped")
    void d09ExternalUrlIsStillSkipped() {
        withTenant(tenantId, () -> storageService.delete("https://cdn.example.com/x.webp"));

        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    @Test
    @DisplayName("delete (D-09) - a dot segment cannot walk from the caller's tenant into another tenant's key")
    void d09DotSegmentTraversalIsRefused() {
        UUID other = UUID.randomUUID();
        String suffix = "/products/" + entityId + "/x.webp";

        withTenant(tenantId, () -> {
            storageService.delete(PUBLIC + tenantId + "/../" + other + suffix);
            storageService.delete(PUBLIC + tenantId + "/./../" + other + suffix);
            storageService.delete(PUBLIC + tenantId + "/%2E%2E/" + other + suffix);
            storageService.delete(PUBLIC + tenantId + "/..%2F" + other + suffix);
            storageService.delete(PUBLIC + tenantId + "\\..\\" + other + suffix);
        });

        verify(store, never()).deleteIfExists(anyString(), anyString());
    }

    private static void withTenant(UUID tenant, Runnable body) {
        TenantContext.set(tenant);
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private static List<ILoggingEvent> captureStorageLog(Runnable body) {
        Logger logger = (Logger) LoggerFactory.getLogger(StorageService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    private static String messages(List<ILoggingEvent> events) {
        return events.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList().toString();
    }

    // ---- Container routing: quarantine is private (Phase 36, T-36-01) ----

    @Test
    @DisplayName("isQuarantineKey - only '<tenant>/quarantine/...' is a quarantine key")
    void isQuarantineKeyRecognisesOnlyTheSecondSegment() {
        assertTrue(StorageService.isQuarantineKey(tenantId + "/quarantine/" + "a".repeat(64) + ".jpg"));

        assertFalse(StorageService.isQuarantineKey(tenantId + "/media/x.webp"), "a derivative key");
        assertFalse(StorageService.isQuarantineKey("quarantine/x"), "no tenant segment");
        assertFalse(StorageService.isQuarantineKey(tenantId + "/products/quarantine-shots/x.webp"),
                "'quarantine' deeper in the path, or as a prefix of another word, is not the quarantine segment");
        assertFalse(StorageService.isQuarantineKey(""), "empty key");
    }

    @Test
    @DisplayName("putBytes - a quarantine key goes to the PRIVATE container with no cache header and no URL")
    void putBytesQuarantineKeyIsPrivateWithoutUrlOrImmutableHeader() {
        String key = tenantId + "/quarantine/" + "b".repeat(64) + ".jpg";
        byte[] bytes = {1, 2, 3};

        String url = storageService.putBytes(key, bytes, "image/jpeg");

        verify(store).put("jtoye-quarantine", key, bytes, "image/jpeg", null);
        assertNull(url, "a private object has no public URL, so none may be minted for it");
    }

    @Test
    @DisplayName("putBytes - a derivative key goes to the PUBLIC container with the immutable header and its URL")
    void putBytesPublicKeyIsPublicWithImmutableHeaderAndUrl() {
        String key = tenantId + "/media/" + entityId + ".webp";
        byte[] bytes = {4, 5, 6};

        String url = storageService.putBytes(key, bytes, "image/webp");

        verify(store).put("jtoye-images", key, bytes, "image/webp", "public, max-age=31536000, immutable");
        assertEquals("http://localhost:10000/devstoreaccount1/jtoye-images/" + key, url);
    }

    @Test
    @DisplayName("urlForKey - refuses a quarantine key and composes the public URL for any other key")
    void urlForKeyRefusesQuarantineKeys() {
        String quarantineKey = tenantId + "/quarantine/" + "c".repeat(64) + ".png";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> storageService.urlForKey(quarantineKey));
        assertEquals("quarantine keys have no public URL", ex.getMessage());

        String publicKey = tenantId + "/media/" + entityId + ".webp";
        assertEquals("http://localhost:10000/devstoreaccount1/jtoye-images/" + publicKey,
                storageService.urlForKey(publicKey));
    }

    @Test
    @DisplayName("getBytes and deleteByKeyChecked - a quarantine key addresses the private container")
    void readAndDeleteOfQuarantineKeyAddressThePrivateContainer() {
        String key = tenantId + "/quarantine/" + "d".repeat(64) + ".jpg";
        when(store.get("jtoye-quarantine", key)).thenReturn(new byte[]{7});
        when(store.deleteIfExists("jtoye-quarantine", key)).thenReturn(true);

        assertArrayEquals(new byte[]{7}, storageService.getBytes(key));
        assertTrue(storageService.deleteByKeyChecked(key));

        verify(store).get("jtoye-quarantine", key);
        verify(store).deleteIfExists("jtoye-quarantine", key);
    }
}
