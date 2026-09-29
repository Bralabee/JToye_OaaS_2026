package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.BlobObjectStore;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.AzuriteTestSupport;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 24 upload pipeline and the Phase 27 quarantine contract on REAL storage: real
 * Postgres, a digest-pinned Azurite, and {@link StorageService} left exactly as the application
 * builds it — no spy, no mock, no stubbed I/O anywhere in this class.
 *
 * <p>Every media suite written before Phase 36 replaces storage with a spy and stubs its reads
 * and writes (36-RESEARCH Pitfall 1), so those suites say nothing about what the store does. This
 * one drives the same entry points they drive — {@link MediaAssetService#acceptQuarantineAndQueue}
 * and {@link MediaProcessingWorker#onMediaEvent} called directly under a pinned tenant, not a live
 * RabbitMQ round trip — and reads every verdict back out of the store: through the
 * {@link BlobObjectStore} port for the private container, and with an anonymous HTTP GET (no
 * {@code Authorization} header, what a browser sends) for anything public.
 *
 * <p>Runs as the Testcontainers superuser: it proves storage mechanics, not tenant isolation (the
 * worker's GUC pin under NOSUPERUSER is {@code MediaProcessingWorkerIntegrationTest#workerPinsTenantGuc}).
 * {@code @AsSystemHarness}: the accept's shop gate is scaffolding (a product with no shop).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
@uk.jtoye.core.testsupport.AsSystemHarness
class MediaPipelineAzuriteIntegrationTest {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final AzuriteContainer AZURITE = AzuriteTestSupport.newAzurite();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        AzuriteTestSupport.registerAzuriteProperties(registry, AZURITE);
        // One try, short timeout: the transient-outage case pauses the emulator, and the read has
        // to fail within the test rather than after the default 3 x 30 s.
        registry.add("storage.blob.max-tries", () -> "1");
        registry.add("storage.blob.try-timeout-seconds", () -> "3");
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private MediaAssetService mediaAssetService;
    @Autowired private MediaProcessingWorker worker;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private StorageService storageService;
    @Autowired private BlobObjectStore store;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    private UUID tenant;

    @BeforeEach
    void seed() {
        // The test profile keeps the boot probe off, so the fixture creates the containers
        // (public at level BLOB, quarantine PRIVATE) — the real layout.
        AzuriteTestSupport.createContainers(store, ContainerAccess.BLOB);
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "pipeline-" + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Accept quarantines the raw bytes privately; processing publishes WebP derivative + thumbnail and removes the raw")
    void acceptThenProcessOnRealStorage() throws Exception {
        UUID productId = seedProduct();
        byte[] raw = jpegOf(1200, 900);

        // --- accept: the raw bytes land in the PRIVATE quarantine container ---------------------
        UUID assetId = accept(productId, raw);
        MediaAsset pending = mediaAssetRepository.findById(assetId).orElseThrow();
        String quarantineKey = pending.getObjectKey();
        assertThat(pending.getStatus()).isEqualTo(MediaAsset.Status.PENDING);
        assertThat(quarantineKey).startsWith(tenant + "/quarantine/");

        assertThat(store.get(AzuriteTestSupport.QUARANTINE_CONTAINER, quarantineKey))
                .as("the quarantine container holds exactly the uploaded raw bytes")
                .isEqualTo(raw);
        assertNotAnonymouslyReadable(quarantineKey, raw);

        // --- process: ACTIVE, and the derivative + thumbnail are public WebP --------------------
        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        TenantContext.set(tenant);   // the worker clears the ThreadLocal in its finally
        em.flush();
        em.clear();

        MediaAsset active = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(MediaAsset.Status.ACTIVE);
        String derivativeKey = tenant + "/media/" + assetId + ".webp";
        assertThat(active.getObjectKey()).isEqualTo(derivativeKey);

        for (String key : List.of(derivativeKey, tenant + "/media/" + assetId + "_thumb.webp")) {
            String url = storageService.urlForKey(key);
            HttpResponse<byte[]> get = anonymousGet(url);
            assertThat(get.statusCode()).as("anonymous GET of %s", url).isEqualTo(200);
            assertThat(get.headers().firstValue("Content-Type")).as("Content-Type of %s", url).contains("image/webp");
            assertThat(get.headers().firstValue("Cache-Control")).as("Cache-Control of %s", url).contains(IMMUTABLE);
            assertThat(get.body()).as("the served bytes of %s are a real WebP", url).startsWith(RIFF);
            assertThat(Arrays.copyOfRange(get.body(), 8, 12)).isEqualTo(WEBP);
        }

        // --- the raw is gone, and "absent" still counts as gone for the sweep sentinel ----------
        assertThat(store.deleteIfExists(AzuriteTestSupport.QUARANTINE_CONTAINER, quarantineKey))
                .as("the worker already deleted the quarantined raw, so there is nothing left to delete")
                .isFalse();
        assertThat(storageService.deleteByKeyChecked(quarantineKey))
                .as("a checked delete of the already-removed raw reports 'gone' (27-01 F-5)")
                .isTrue();
        assertNotAnonymouslyReadable(quarantineKey, raw);
    }

    @Test
    @DisplayName("A corrupt upload ends FAILED: no derivative is published and its hostile raw bytes are discarded")
    void corruptUploadFailsWithNoDerivative() throws Exception {
        UUID productId = seedProduct();
        byte[] corrupt = corruptJpeg();

        UUID assetId = accept(productId, corrupt);
        String quarantineKey = mediaAssetRepository.findById(assetId).orElseThrow().getObjectKey();
        assertThat(store.get(AzuriteTestSupport.QUARANTINE_CONTAINER, quarantineKey))
                .as("precondition: the corrupt bytes were quarantined").isEqualTo(corrupt);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        TenantContext.set(tenant);
        em.flush();
        em.clear();

        MediaAsset failed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(failed.getFailureReason()).isNotBlank();
        assertNoDerivative(assetId);
        // 27-01 D-07 as shipped: undecodable bytes are worthless and possibly hostile, so the worker
        // DISCARDS them (failAndDiscard) and closes the sentinel — they are not re-drivable.
        assertThat(store.deleteIfExists(AzuriteTestSupport.QUARANTINE_CONTAINER, quarantineKey))
                .as("the corrupt raw was discarded").isFalse();
        assertThat(failed.getQuarantineReclaimedAt()).as("sentinel closed: the bytes are gone").isNotNull();
    }

    @Test
    @DisplayName("A transient storage outage during the read ends FAILED with the quarantine bytes RETAINED and re-drivable")
    void transientReadFailureRetainsQuarantineBytes() throws Exception {
        UUID productId = seedProduct();
        byte[] raw = jpegOf(800, 600);

        UUID assetId = accept(productId, raw);
        String quarantineKey = mediaAssetRepository.findById(assetId).orElseThrow().getObjectKey();

        // A real outage: the emulator process is frozen while the worker reads.
        var docker = AZURITE.getDockerClient();
        docker.pauseContainerCmd(AZURITE.getContainerId()).exec();
        try {
            worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        } finally {
            docker.unpauseContainerCmd(AZURITE.getContainerId()).exec();
        }
        TenantContext.set(tenant);
        em.flush();
        em.clear();

        MediaAsset failed = mediaAssetRepository.findById(assetId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("Could not read the quarantined upload");
        assertThat(failed.getQuarantineReclaimedAt()).as("sentinel open: the bytes still exist").isNull();
        assertNoDerivative(assetId);
        assertThat(store.get(AzuriteTestSupport.QUARANTINE_CONTAINER, quarantineKey))
                .as("27-01 failRetainingBytes: the vendor's only copy survives the outage, byte for byte")
                .isEqualTo(raw);
    }

    // ---- helpers -----------------------------------------------------------

    private UUID accept(UUID productId, byte[] raw) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        return mediaAssetService.acceptQuarantineAndQueue(productId, raw, sha, null,
                new MediaAssetService.MediaPlacement(true, 0)).assetId();
    }

    /** Neither container serves the key anonymously, and a refusal never carries the raw bytes. */
    private static void assertNotAnonymouslyReadable(String key, byte[] raw) throws Exception {
        for (String container : List.of(AzuriteTestSupport.QUARANTINE_CONTAINER, AzuriteTestSupport.PUBLIC_CONTAINER)) {
            String direct = AzuriteTestSupport.blobEndpoint(AZURITE) + "/" + container + "/" + key;
            HttpResponse<byte[]> get = anonymousGet(direct);
            assertThat(get.statusCode()).as("anonymous GET of %s", direct).isNotEqualTo(200);
            assertThat(new String(get.body(), StandardCharsets.ISO_8859_1))
                    .as("the refused response must not carry the raw bytes")
                    .doesNotContain(new String(raw, StandardCharsets.ISO_8859_1));
        }
    }

    private void assertNoDerivative(UUID assetId) throws Exception {
        for (String key : List.of(tenant + "/media/" + assetId + ".webp", tenant + "/media/" + assetId + "_thumb.webp")) {
            String url = storageService.urlForKey(key);
            assertThat(anonymousGet(url).statusCode()).as("no derivative may exist at %s", url).isEqualTo(404);
        }
    }

    private UUID seedProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)");
        return id;
    }

    private static byte[] jpegOf(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        // A random dot keeps the bytes (and so the per-tenant dedup sha) distinct per call.
        g.setColor(new Color(ThreadLocalRandom.current().nextInt(0xFFFFFF)));
        g.fillRect(ThreadLocalRandom.current().nextInt(w - 10), ThreadLocalRandom.current().nextInt(h - 10), 10, 10);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    /** JPEG magic, then noise: sniffed as a JPEG on accept, undecodable in the worker. */
    private static byte[] corruptJpeg() {
        byte[] bytes = new byte[4096];
        ThreadLocalRandom.current().nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static HttpResponse<byte[]> anonymousGet(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
