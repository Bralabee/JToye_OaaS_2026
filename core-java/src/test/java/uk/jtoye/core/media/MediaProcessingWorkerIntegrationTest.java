package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMG-02 worker-side proof over real Postgres + the real {@link MediaNormalizer}
 * (Scrimage/cwebp) — MinIO is a {@code @SpyBean} so the quarantine read is stubbed
 * and the derivative write is captured without a live object store.
 *
 * <p>Proves the async worker: pins the tenant GUC (visible + updatable under a
 * NOSUPERUSER role downgrade), transforms the quarantined raw bytes into a stored
 * WebP derivative, deletes the raw on success, hard-vetoes a spoofed upload to
 * {@code FAILED}, and is idempotent on redelivery.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class MediaProcessingWorkerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";
    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};

    @Autowired private MediaProcessingWorker worker;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ProductMediaRepository productMediaRepository;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    @SpyBean private StorageService storageService;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        jdbc.execute("DO $$ BEGIN "
                + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN "
                + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; "
                + "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "  END IF; "
                + "END $$");

        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
        // MinIO is stubbed — the derivative write and raw delete are asserted via the spy.
        Mockito.doReturn("http://minio/derivative").when(storageService).putBytes(
                Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void storesWebpDerivativeDeletesRaw() throws Exception {
        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId, true, 0);
        Mockito.doReturn(jpegOf(1200, 900)).when(storageService).getBytes(quarantineKey);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        MediaAsset processed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(MediaAsset.Status.ACTIVE);
        assertThat(processed.getObjectKey()).isEqualTo(tenant + "/media/" + assetId + ".webp");
        assertThat(processed.getContentType()).isEqualTo("image/webp");
        assertThat(processed.getWidth()).isNotNull().isPositive();
        assertThat(processed.getHeight()).isNotNull().isPositive();
        assertThat(processed.getBytes()).isNotNull().isPositive();

        // The stored derivative object is a real WebP (RIFF....WEBP magic).
        Mockito.verify(storageService, Mockito.atLeastOnce())
                .putBytes(Mockito.eq(tenant + "/media/" + assetId + ".webp"), stored.capture(), Mockito.eq("image/webp"));
        byte[] derivative = stored.getValue();
        assertThat(derivative).startsWith(RIFF);
        assertThat(Arrays.copyOfRange(derivative, 8, 12)).isEqualTo(WEBP);

        // The raw quarantine object was deleted on success.
        Mockito.verify(storageService).deleteByKey(quarantineKey);

        // The product_media slot was created pointing at the freshly-ACTIVE asset.
        List<ProductMedia> links = productMediaRepository.findByProductIdOrderByPrimaryDescSortOrderAsc(productId);
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getAssetId()).isEqualTo(assetId);
        assertThat(links.get(0).isPrimary()).isTrue();
    }

    @Test
    void workerPinsTenantGuc() throws Exception {
        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId, true, 0);
        Mockito.doReturn(jpegOf(800, 600)).when(storageService).getBytes(quarantineKey);

        // Downgrade to a NOSUPERUSER role so the V53 RLS policies actually fire.
        TenantContext.clear();
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);

        // RED baseline: with NO tenant pinned the PENDING row is invisible under FORCE RLS.
        Integer hiddenBeforePin = jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE id = ?", Integer.class, assetId);
        assertThat(hiddenBeforePin).as("without a pinned tenant GUC the PENDING row is RLS-hidden").isZero();

        // The worker pins the tenant GUC itself (TenantContext + set_config) and therefore
        // sees + updates the row even under the NOSUPERUSER downgrade.
        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        // Re-pin the tenant to read back (the worker cleared the ThreadLocal in its finally).
        TenantContext.set(tenant);
        MediaAsset processed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(processed.getStatus())
                .as("the pinned GUC made the PENDING row visible + updatable to ACTIVE under NOSUPERUSER")
                .isEqualTo(MediaAsset.Status.ACTIVE);
    }

    @Test
    void magicByteMismatchVetoes() {
        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId, true, 0);
        // A ".jpg" that is really a PDF — the worker must sniff + veto, never store a derivative.
        byte[] pdf = "%PDF-1.7\n%not-an-image-payload-bytes-at-all".getBytes(StandardCharsets.ISO_8859_1);
        Mockito.doReturn(pdf).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        MediaAsset processed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(processed.getFailureReason()).isNotBlank();

        // No derivative object was ever written, and no product_media link created.
        Mockito.verify(storageService, Mockito.never())
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        assertThat(productMediaRepository.findByProductIdOrderByPrimaryDescSortOrderAsc(productId)).isEmpty();
    }

    @Test
    void redeliverySkips() {
        UUID productId = seedProduct();
        String derivativeKey = tenant + "/media/" + UUID.randomUUID() + ".webp";
        // An already-ACTIVE asset (the worker previously processed it).
        UUID assetId = insertActiveAsset(derivativeKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        // Idempotent: no re-read of the quarantine, no re-write, status unchanged.
        Mockito.verify(storageService, Mockito.never()).getBytes(Mockito.anyString());
        Mockito.verify(storageService, Mockito.never())
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        assertThat(mediaAssetRepository.findById(assetId).orElseThrow().getStatus())
                .isEqualTo(MediaAsset.Status.ACTIVE);
    }

    // ---- helpers -----------------------------------------------------------

    private UUID seedProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)");
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

    private UUID insertActiveAsset(String objectKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE')",
                id, tenant, objectKey, randomSha());
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
