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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The load-bearing D-04a safety property: because copy-on-write mints + repoints ONLY
 * on worker success, a REPLACEMENT upload that FAILS normalization never clobbers the
 * product's existing live image. The failed asset is left {@code FAILED} and unreferenced;
 * the {@code product_media} row still points at the ORIGINAL ACTIVE asset.
 *
 * <p>The paired success case proves the other half: a SUCCESSFUL replacement DOES repoint
 * the slot to the new derivative and releases the displaced asset (physical delete at
 * ref-count 0) — so the mint-on-success ordering is genuinely load-bearing (a naive
 * "repoint on accept" would fail {@link #failedReplacementDoesNotClobber}).
 *
 * <p>Runs as the Testcontainers superuser (RLS bypassed) — this proves the CoW MECHANICS;
 * the tenant wall is proven under NOSUPERUSER in {@code MediaAssetRlsPolicyIntegrationTest}
 * and {@code MediaProcessingWorkerIntegrationTest#workerPinsTenantGuc}. {@link StorageService}
 * is a {@code @SpyBean} so the derivative write / raw read / physical delete are controlled
 * without a live MinIO.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class CowSafetyIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    @SpyBean private StorageService storageService;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
        Mockito.doReturn("http://minio/derivative").when(storageService)
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void failedReplacementDoesNotClobber() {
        UUID productId = seedProduct();
        // Existing live image: an ACTIVE asset the product's primary slot points at.
        UUID originalAssetId = insertActiveAsset(tenant + "/media/" + UUID.randomUUID() + ".webp");
        UUID pmRowId = insertPrimaryLink(productId, originalAssetId);

        // A REPLACEMENT upload arrives (new PENDING asset, same primary slot) that FAILS normalize.
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID replacementId = insertPendingAsset(quarantineKey, productId, true, 0);
        byte[] notAnImage = "corrupt-not-an-image".getBytes(StandardCharsets.ISO_8859_1);
        Mockito.doReturn(notAnImage).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, replacementId));
        em.flush();
        em.clear();

        // The replacement is FAILED...
        assertThat(mediaAssetRepository.findById(replacementId).orElseThrow().getStatus())
                .isEqualTo(MediaAsset.Status.FAILED);
        // ...and the product's live image is UNTOUCHED — the slot still points at the original.
        ProductMedia link = productMediaRepository.findById(pmRowId).orElseThrow();
        assertThat(link.getAssetId())
                .as("D-04a: a FAILED replacement never clobbers the live image")
                .isEqualTo(originalAssetId);
        assertThat(mediaAssetRepository.findById(originalAssetId))
                .as("the original ACTIVE asset is still present + live")
                .isPresent()
                .get()
                .satisfies(a -> assertThat(a.getStatus()).isEqualTo(MediaAsset.Status.ACTIVE));
        // No derivative was ever stored for the failed replacement.
        Mockito.verify(storageService, Mockito.never())
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
    }

    @Test
    void successfulReplacementRepointsAndReleasesOld() throws Exception {
        UUID productId = seedProduct();
        UUID originalAssetId = insertActiveAsset(tenant + "/media/" + UUID.randomUUID() + ".webp");
        String originalKey = mediaAssetRepository.findById(originalAssetId).orElseThrow().getObjectKey();
        UUID pmRowId = insertPrimaryLink(productId, originalAssetId);

        // A REPLACEMENT that SUCCEEDS.
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID replacementId = insertPendingAsset(quarantineKey, productId, true, 0);
        Mockito.doReturn(jpegOf(1000, 800)).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, replacementId));
        em.flush();
        em.clear();

        // The replacement is ACTIVE and the slot now points at it (CoW repoint on success)...
        assertThat(mediaAssetRepository.findById(replacementId).orElseThrow().getStatus())
                .isEqualTo(MediaAsset.Status.ACTIVE);
        assertThat(productMediaRepository.findById(pmRowId).orElseThrow().getAssetId())
                .as("the slot is repointed to the new derivative on success")
                .isEqualTo(replacementId);
        // ...and the displaced original is released (ref-count 0 -> physical delete + row removed).
        assertThat(mediaAssetRepository.findById(originalAssetId))
                .as("the displaced asset is reclaimed at ref-count 0")
                .isEmpty();
        Mockito.verify(storageService).deleteByKey(originalKey);
    }

    // ---- helpers -----------------------------------------------------------

    private UUID seedProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)");
        return id;
    }

    private UUID insertActiveAsset(String objectKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE')",
                id, tenant, objectKey, randomSha());
        return id;
    }

    private UUID insertPendingAsset(String quarantineKey, UUID productId, boolean primary, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status, product_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 'PENDING', ?, ?, ?)",
                id, tenant, quarantineKey, randomSha(), productId, primary, sortOrder);
        return id;
    }

    private UUID insertPrimaryLink(UUID productId, UUID assetId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, true, 0)",
                id, tenant, productId, assetId);
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
