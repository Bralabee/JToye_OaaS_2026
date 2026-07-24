package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * IMG-03 gate strictness (SPEC D3) over the real worker + Postgres:
 * <ul>
 *   <li><b>compress/decode fail = hard veto</b> — a disallowed/undecodable input flips the
 *       asset to {@code FAILED} with a vendor-visible reason; no derivative is served.</li>
 *   <li><b>low content-relevance = review queue, NOT a reject</b> — with the advisory vision
 *       flag ON and a below-threshold confidence, the asset stays {@code ACTIVE} and is
 *       {@code flagged=true} (never rejected — "don't wrongly block legitimate rare dishes").</li>
 *   <li><b>vision is advisory-gated</b> — with the flag OFF (default), the asset is ACTIVE and
 *       the vision provider is never even consulted.</li>
 * </ul>
 * The vision provider is a {@code @MockBean}; the {@code jtoye.media.vision.enabled} flag is
 * toggled per-test on the live {@link MediaProperties} bean.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class GateStrictnessTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaProcessingWorker worker;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ProductMediaRepository productMediaRepository;
    @Autowired private MediaProperties mediaProperties;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    @SpyBean private StorageService storageService;
    @MockBean private ImageAnalysisService imageAnalysisService;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
        Mockito.doReturn("http://minio/derivative").when(storageService)
                .putBytes(anyString(), any(byte[].class), anyString());
        Mockito.doNothing().when(storageService).deleteByKey(anyString());
    }

    @AfterEach
    void clear() {
        mediaProperties.getVision().setEnabled(false);   // never leak the flag across tests
        TenantContext.clear();
    }

    @Test
    void normalizeFailMarksFailed() {
        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId);
        // An input that is not an allowlisted image — the normalizer vetoes it.
        byte[] notAnImage = "this-is-definitely-not-an-image-payload".getBytes(StandardCharsets.ISO_8859_1);
        Mockito.doReturn(notAnImage).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        MediaAsset processed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(processed.getFailureReason()).as("vendor-visible reason").isNotBlank();
        // Hard veto: no derivative stored, no live product_media link.
        Mockito.verify(storageService, Mockito.never()).putBytes(anyString(), any(byte[].class), anyString());
        assertThat(productMediaRepository.findByProductIdOrderByPrimaryDescSortOrderAsc(productId)).isEmpty();
    }

    @Test
    void lowConfidenceGoesActiveAndFlagged() throws Exception {
        mediaProperties.getVision().setEnabled(true);   // advisory flag ON
        Mockito.when(imageAnalysisService.isEnabled()).thenReturn(true);
        ImageAnalysisResult lowConf = new ImageAnalysisResult();
        lowConf.setIdentifiedName("Unknown");
        lowConf.setConfidence(0.1);   // below the default minConfidence (0.35)
        Mockito.when(imageAnalysisService.analyze(any(byte[].class), anyString()))
                .thenReturn(Optional.of(lowConf));

        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId);
        Mockito.doReturn(jpegOf(900, 700)).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        MediaAsset processed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(processed.getStatus())
                .as("low relevance is flagged for review, NEVER rejected")
                .isEqualTo(MediaAsset.Status.ACTIVE);
        assertThat(processed.isFlagged()).as("ACTIVE + flagged -> vendor review queue").isTrue();
    }

    @Test
    void visionFlagOffIsAdvisoryOnly() throws Exception {
        // Advisory flag OFF (default) — even a would-be low-confidence result is never consulted.
        mediaProperties.getVision().setEnabled(false);

        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId);
        Mockito.doReturn(jpegOf(800, 600)).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        MediaAsset processed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(MediaAsset.Status.ACTIVE);
        assertThat(processed.isFlagged()).as("vision is advisory-off -> never flagged").isFalse();
        // The provider was never even consulted when the advisory flag is off.
        Mockito.verify(imageAnalysisService, Mockito.never()).analyze(any(byte[].class), anyString());
    }

    // ---- helpers -----------------------------------------------------------

    private UUID seedProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)");
        return id;
    }

    private UUID insertPendingAsset(String quarantineKey, UUID productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status, product_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 'PENDING', ?, true, 0)",
                id, tenant, quarantineKey, randomSha(), productId);
        return id;
    }

    private static String randomSha() {
        String s = UUID.randomUUID().toString().replace("-", "") + "0".repeat(64);
        return s.substring(0, 64);
    }

    private static byte[] jpegOf(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }
}
