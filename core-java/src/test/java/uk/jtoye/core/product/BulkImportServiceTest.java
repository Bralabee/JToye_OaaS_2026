package uk.jtoye.core.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.media.MediaAcceptDto;
import uk.jtoye.core.media.MediaAssetService;
import uk.jtoye.core.product.dto.BulkImportResult;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.storage.StorageService;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BulkImportService.
 * Tests CSV import and AI-powered image import with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class BulkImportServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ImageAnalysisService imageAnalysisService;

    @Mock
    private StorageService storageService;

    @Mock
    private ShopAccessService shopAccessService;

    @Mock
    private MediaAssetService mediaAssetService;

    @InjectMocks
    private BulkImportService bulkImportService;

    private UUID tenantId;

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        // Phase 23 (VSA-02): these fixtures import CSVs with no shop_id column, so run as
        // a GROUP_ADMIN — unassigned rows are allowed (legacy behaviour) and the image
        // path's requireGroupAdmin() passes. Lenient: not every test reaches the gate.
        lenient().when(shopAccessService.isGroupAdmin()).thenReturn(true);

        // Default: repository saves and returns the product with an ID
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            setField(p, "id", UUID.randomUUID());
            setField(p, "createdAt", OffsetDateTime.now());
            return p;
        });
        lenient().when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            if (p.getId() == null) {
                setField(p, "id", UUID.randomUUID());
            }
            return p;
        });

        // Default mapper stub
        lenient().when(productMapper.toDto(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ProductDto dto = new ProductDto();
            dto.setId(product.getId());
            dto.setSku(product.getSku());
            dto.setTitle(product.getTitle());
            dto.setPricePennies(product.getPricePennies());
            dto.setCategory(product.getCategory());
            return dto;
        });
    }

    // ---- CSV Import Tests ----

    @Test
    @DisplayName("importFromCsv - Creates products from valid CSV rows")
    void testImportFromCsv_ValidRows() {
        String csv = "title,sku,price_pounds,ingredients,category\n" +
                "Jollof Rice,JOLLOF-001,8.99,\"Rice, tomatoes, peppers\",Mains\n" +
                "Puff Puff,PUFF-001,2.50,\"Flour, sugar, yeast\",Snacks\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        BulkImportResult result = bulkImportService.importFromCsv(file);

        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getErrorCount());
        assertEquals(2, result.getCreated().size());
        verify(productRepository, times(2)).save(any(Product.class));
    }

    @Test
    @DisplayName("importFromCsv - Reports error for row missing required title")
    void testImportFromCsv_MissingTitle() {
        String csv = "title,sku,price_pounds\n" +
                ",SKU-001,5.00\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        BulkImportResult result = bulkImportService.importFromCsv(file);

        assertEquals(1, result.getTotalRows());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("Title is required"));
    }

    @Test
    @DisplayName("importFromCsv - Reports error for invalid price")
    void testImportFromCsv_InvalidPrice() {
        String csv = "title,price_pounds\n" +
                "Suya,not-a-number\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        BulkImportResult result = bulkImportService.importFromCsv(file);

        assertEquals(1, result.getTotalRows());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("Invalid price"));
    }

    @Test
    @DisplayName("importFromCsv - Returns error when required columns missing from header")
    void testImportFromCsv_MissingRequiredColumns() {
        String csv = "sku,category\n" +
                "SKU-001,Mains\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        BulkImportResult result = bulkImportService.importFromCsv(file);

        assertEquals(0, result.getTotalRows());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("title"));
    }

    @Test
    @DisplayName("importFromCsv - Malformed shop_id is a per-row error, not a 403, and the batch continues (WR-07)")
    void testImportFromCsv_MalformedShopId_RowErrorNot403() {
        String csv = "title,price_pounds,shop_id\n" +
                "Bad Row,5.00,not-a-uuid\n" +
                "Good Row,6.00,\n";  // blank shop_id -> null -> allowed for GROUP_ADMIN

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        BulkImportResult result = bulkImportService.importFromCsv(file);

        assertEquals(2, result.getTotalRows());
        // The good row still imports — a bad cell does not abort the whole batch.
        assertEquals(1, result.getSuccessCount());
        // The malformed shop_id is reported as a row-level validation error...
        assertEquals(1, result.getErrorCount());
        BulkImportResult.RowError err = result.getErrors().get(0);
        assertEquals("shop_id", err.getField());
        assertTrue(err.getMessage().contains("Invalid shop_id"),
                "malformed shop_id must be a validation error, got: " + err.getMessage());
        // ...and it was NEVER surfaced as an authorization denial (no require() on the bad row).
        verify(shopAccessService, never()).require(any(), any());
    }

    @Test
    @DisplayName("importFromCsv - Handles empty CSV gracefully")
    void testImportFromCsv_EmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);

        BulkImportResult result = bulkImportService.importFromCsv(file);

        assertEquals(0, result.getTotalRows());
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    @DisplayName("importFromCsv - Converts pounds to pennies correctly")
    void testImportFromCsv_PriceConversion() {
        String csv = "title,price_pounds\n" +
                "Egusi Soup,12.99\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        bulkImportService.importFromCsv(file);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());

        Product saved = captor.getValue();
        assertEquals(1299L, saved.getPricePennies());
        assertEquals(tenantId, saved.getTenantId());
    }

    @Test
    @DisplayName("importFromCsv - Sets tenant ID on all created products")
    void testImportFromCsv_SetsTenantId() {
        String csv = "title,price_pounds\n" +
                "Product A,1.00\n" +
                "Product B,2.00\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        bulkImportService.importFromCsv(file);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, times(2)).save(captor.capture());

        for (Product product : captor.getAllValues()) {
            assertEquals(tenantId, product.getTenantId());
        }
    }

    @Test
    @DisplayName("importFromCsv - Fails when tenant context not set")
    void testImportFromCsv_MissingTenant() {
        TenantContext.clear();

        String csv = "title,price_pounds\nTest,1.00\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> bulkImportService.importFromCsv(file));
    }

    // ---- Image Import Tests ----

    @Test
    @DisplayName("importFromImages - Calls AI service for each file and creates draft products")
    void testImportFromImages_CreatesProducts() throws Exception {
        when(imageAnalysisService.isEnabled()).thenReturn(true);

        ImageAnalysisResult aiResult = new ImageAnalysisResult();
        aiResult.setIdentifiedName("Jollof Rice");
        aiResult.setDescription("Smoky Nigerian jollof rice");
        aiResult.setIngredients("Rice, tomatoes, peppers");
        aiResult.setCategory("Mains");
        aiResult.setConfidence(0.95);
        aiResult.setDietaryTags(List.of("Halal", "Gluten-Free"));

        when(imageAnalysisService.analyze(any(byte[].class), any(String.class)))
                .thenReturn(Optional.of(aiResult));
        when(mediaAssetService.acceptQuarantineAndQueue(any(UUID.class), any(byte[].class), any(String.class),
                any(), any(MediaAssetService.MediaPlacement.class)))
                .thenReturn(new MediaAcceptDto(UUID.randomUUID(), "PENDING"));

        MockMultipartFile image1 = new MockMultipartFile(
                "files", "jollof.jpg", "image/jpeg", new byte[]{1, 2, 3});
        MockMultipartFile image2 = new MockMultipartFile(
                "files", "rice.jpg", "image/jpeg", new byte[]{4, 5, 6});

        BulkImportResult result = bulkImportService.importFromImages(new MultipartFile[]{image1, image2});

        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getErrorCount());
        verify(imageAnalysisService, times(2)).analyze(any(byte[].class), any(String.class));
        // The image STORAGE is routed through the ONE safe pipeline (accept -> quarantine ->
        // PENDING media_asset + outbox), NOT the legacy synchronous storageService.upload.
        verify(mediaAssetService, times(2)).acceptQuarantineAndQueue(any(UUID.class), any(byte[].class),
                any(String.class), any(), any(MediaAssetService.MediaPlacement.class));
        verify(storageService, never()).upload(any(UUID.class), eq("products"), any(UUID.class), any(MultipartFile.class));
    }

    @Test
    @DisplayName("importFromImages - Routes image storage through the ONE pipeline, primary placement, no second upload path")
    void testImportFromImages_RoutesThroughOnePipeline() throws Exception {
        when(imageAnalysisService.isEnabled()).thenReturn(true);

        ImageAnalysisResult aiResult = new ImageAnalysisResult();
        aiResult.setIdentifiedName("Egusi Soup");
        aiResult.setConfidence(0.92);
        when(imageAnalysisService.analyze(any(byte[].class), any(String.class)))
                .thenReturn(Optional.of(aiResult));
        when(mediaAssetService.acceptQuarantineAndQueue(any(UUID.class), any(byte[].class), any(String.class),
                any(), any(MediaAssetService.MediaPlacement.class)))
                .thenReturn(new MediaAcceptDto(UUID.randomUUID(), "PENDING"));

        MockMultipartFile image = new MockMultipartFile(
                "files", "egusi.jpg", "image/jpeg", new byte[]{9, 8, 7, 6});

        bulkImportService.importFromImages(new MultipartFile[]{image});

        // The bulk image lands via the pipeline as a PRIMARY placement with the raw bytes.
        ArgumentCaptor<MediaAssetService.MediaPlacement> placement =
                ArgumentCaptor.forClass(MediaAssetService.MediaPlacement.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mediaAssetService).acceptQuarantineAndQueue(any(UUID.class), bytes.capture(), any(String.class),
                any(), placement.capture());
        assertTrue(placement.getValue().isPrimary(), "bulk image becomes the product's primary");
        assertEquals(0, placement.getValue().sortOrder());
        assertArrayEquals(new byte[]{9, 8, 7, 6}, bytes.getValue());

        // No second (legacy synchronous) upload route survives.
        verify(storageService, never()).upload(any(UUID.class), any(String.class), any(UUID.class), any(MultipartFile.class));
    }

    @Test
    @DisplayName("importFromImages - Returns error when AI service is disabled")
    void testImportFromImages_AiDisabled() {
        when(imageAnalysisService.isEnabled()).thenReturn(false);

        MockMultipartFile image = new MockMultipartFile(
                "files", "test.jpg", "image/jpeg", new byte[]{1, 2, 3});

        BulkImportResult result = bulkImportService.importFromImages(new MultipartFile[]{image});

        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("AI analysis is not available"));
        verify(imageAnalysisService, never()).analyze(any(), any());
    }

    @Test
    @DisplayName("importFromImages - Skips low-confidence results")
    void testImportFromImages_LowConfidence() {
        when(imageAnalysisService.isEnabled()).thenReturn(true);

        ImageAnalysisResult lowConf = new ImageAnalysisResult();
        lowConf.setIdentifiedName("Unknown");
        lowConf.setConfidence(0.1);

        when(imageAnalysisService.analyze(any(byte[].class), any(String.class)))
                .thenReturn(Optional.of(lowConf));

        MockMultipartFile image = new MockMultipartFile(
                "files", "unclear.jpg", "image/jpeg", new byte[]{1, 2, 3});

        BulkImportResult result = bulkImportService.importFromImages(new MultipartFile[]{image});

        assertEquals(1, result.getTotalRows());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrors().get(0).getMessage().contains("Could not identify"));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    @DisplayName("importFromImages - Creates draft products (unavailable, zero price)")
    void testImportFromImages_DraftProducts() throws Exception {
        when(imageAnalysisService.isEnabled()).thenReturn(true);

        ImageAnalysisResult aiResult = new ImageAnalysisResult();
        aiResult.setIdentifiedName("Suya");
        aiResult.setDescription("Spicy grilled beef");
        aiResult.setIngredients("Beef, yaji spice");
        aiResult.setCategory("Snacks");
        aiResult.setConfidence(0.9);

        when(imageAnalysisService.analyze(any(byte[].class), any(String.class)))
                .thenReturn(Optional.of(aiResult));
        when(mediaAssetService.acceptQuarantineAndQueue(any(UUID.class), any(byte[].class), any(String.class),
                any(), any(MediaAssetService.MediaPlacement.class)))
                .thenReturn(new MediaAcceptDto(UUID.randomUUID(), "PENDING"));

        MockMultipartFile image = new MockMultipartFile(
                "files", "suya.jpg", "image/jpeg", new byte[]{1, 2, 3});

        bulkImportService.importFromImages(new MultipartFile[]{image});

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());

        Product saved = captor.getValue();
        assertEquals("Suya", saved.getTitle());
        assertEquals(0L, saved.getPricePennies());
        assertFalse(saved.getAvailable());
        assertFalse(saved.getFeatured());
        assertEquals(tenantId, saved.getTenantId());
    }

    // ---- CSV Template ----

    @Test
    @DisplayName("generateCsvTemplate - Returns valid CSV with headers and example")
    void testGenerateCsvTemplate() {
        String template = bulkImportService.generateCsvTemplate();

        assertTrue(template.contains("title"));
        assertTrue(template.contains("price_pounds"));
        assertTrue(template.contains("sku"));
        assertTrue(template.contains("Jollof Rice"));
    }
}
