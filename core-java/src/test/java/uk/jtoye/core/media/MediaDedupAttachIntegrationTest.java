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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-01 + WR-01 — the sha256 dedup short-circuit on accept must still ATTACH the reused asset
 * to the target product (a "regression by omission" otherwise: a deduplicated upload returned
 * 202 but silently left the product imageless). Proven over real Postgres by driving
 * {@link MediaAssetService#acceptQuarantineAndQueue} directly:
 *
 * <ul>
 *   <li><b>ACTIVE</b> dedup — two products uploading identical bytes each get a product_media
 *       row pointing at the ONE shared ACTIVE asset (CoW share, ref-count 2).</li>
 *   <li><b>ACTIVE</b> dedup onto an occupied slot — repoints the slot to the shared asset and
 *       releases the displaced one (ref-count 0 physical delete).</li>
 *   <li><b>PENDING</b> dedup — a second product shares the in-flight asset now (its slot points
 *       at the PENDING asset); the asset's own placement intent is untouched.</li>
 *   <li><b>FAILED</b> dedup (WR-01) — a FAILED row no longer permanently poisons the bytes: the
 *       re-upload resets it to PENDING, re-quarantines the raw bytes and re-enqueues an outbox
 *       event for reprocessing.</li>
 * </ul>
 *
 * <p>Runs as the Testcontainers superuser (RLS bypassed) — CoW/dedup MECHANICS, not tenant
 * isolation. No {@code Authentication} on the thread, so the accept's VSA-02 shop gate takes the
 * internal-caller bypass (the shop gate is proven separately). {@link StorageService} is a
 * {@code @SpyBean} (no live MinIO).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
// #283: drives mediaAssetService.acceptQuarantineAndQueue (gated at :118) to reach the dedup
// behaviour under test; the gate is scaffolding here, not the subject.
@uk.jtoye.core.testsupport.AsSystemHarness
class MediaDedupAttachIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaAssetService mediaAssetService;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ProductMediaRepository productMediaRepository;
    @Autowired private MediaEventOutboxRepository mediaEventOutboxRepository;
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
        Mockito.lenient().doReturn("image/jpeg").when(storageService).detectContentType(Mockito.any());
        Mockito.lenient().doReturn("http://minio/obj").when(storageService)
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        Mockito.lenient().doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---- CR-01: ACTIVE dedup attaches / repoints -----------------------------------------

    @Test
    void activeDedupAttachesBothProductsToTheSharedAsset() {
        String sha = pad("shared-active");
        MediaAsset shared = seedAsset(sha, MediaAsset.Status.ACTIVE, null);
        UUID productA = seedProduct();
        UUID productB = seedProduct();

        MediaAcceptDto rA = accept(productA, sha);
        MediaAcceptDto rB = accept(productB, sha);
        em.flush();
        em.clear();

        assertThat(rA.assetId()).isEqualTo(shared.getId());
        assertThat(rA.status()).isEqualTo("ACTIVE");
        assertThat(rB.assetId()).isEqualTo(shared.getId());

        // BOTH products end up with a product_media row pointing at the ONE shared asset.
        assertThat(primaryAssetOf(productA)).as("product A attached to the shared asset").isEqualTo(shared.getId());
        assertThat(primaryAssetOf(productB)).as("product B attached to the shared asset").isEqualTo(shared.getId());
        assertThat(productMediaRepository.countByAssetId(shared.getId()))
                .as("the shared asset is now referenced by both products (ref-count 2)").isEqualTo(2);
        assertThat(countAssetsWithSha(sha)).as("no duplicate asset minted").isEqualTo(1);
    }

    @Test
    void activeDedupRepointsAnOccupiedPrimarySlotAndReleasesTheDisplacedAsset() {
        String sha = pad("shared-repoint");
        MediaAsset shared = seedAsset(sha, MediaAsset.Status.ACTIVE, null);
        UUID product = seedProduct();
        // product already has a primary slot pointing at a DIFFERENT (soon-displaced) asset.
        MediaAsset old = seedAsset(pad("old-primary"), MediaAsset.Status.ACTIVE, null);
        String oldKey = old.getObjectKey();
        newPrimaryLink(product, old.getId());

        accept(product, sha);
        em.flush();
        em.clear();

        assertThat(primaryAssetOf(product))
                .as("the primary slot is repointed to the shared dedup asset").isEqualTo(shared.getId());
        assertThat(mediaAssetRepository.findById(old.getId()))
                .as("the displaced asset is released at ref-count 0").isEmpty();
        Mockito.verify(storageService).deleteByKey(oldKey);
    }

    // ---- CR-01: PENDING dedup shares the in-flight asset ---------------------------------

    @Test
    void pendingDedupSharesTheInFlightAssetWithTheSecondProduct() {
        String sha = pad("shared-pending");
        UUID productA = seedProduct();
        UUID productB = seedProduct();
        // A worker is in flight for productA (the asset's placement intent points at productA).
        MediaAsset inFlight = seedAsset(sha, MediaAsset.Status.PENDING, productA);

        MediaAcceptDto rB = accept(productB, sha);
        em.flush();
        em.clear();

        assertThat(rB.assetId()).isEqualTo(inFlight.getId());
        assertThat(rB.status()).isEqualTo("PENDING");
        // productB now shares the in-flight asset (its slot points at the PENDING asset).
        assertThat(primaryAssetOf(productB))
                .as("product B shares the in-flight PENDING asset").isEqualTo(inFlight.getId());
        // The asset stays PENDING and its OWN placement intent (product A) is untouched.
        MediaAsset reread = mediaAssetRepository.findById(inFlight.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(MediaAsset.Status.PENDING);
        assertThat(reread.getProductId()).as("the asset's placement intent is not overwritten").isEqualTo(productA);
        assertThat(countAssetsWithSha(sha)).as("no duplicate asset minted").isEqualTo(1);
    }

    // ---- WR-01: FAILED dedup reprocesses instead of poisoning the bytes ------------------

    @Test
    void failedDedupReprocessesTheAssetToPendingAndReEnqueues() {
        String sha = pad("shared-failed");
        UUID product = seedProduct();
        // A previously-FAILED asset for these bytes (its quarantine was purged; reason set).
        MediaAsset failed = seedAsset(sha, MediaAsset.Status.FAILED, null);
        failed.setFailureReason("Image could not be processed");
        mediaAssetRepository.saveAndFlush(failed);
        long outboxBefore = countOutbox();

        MediaAcceptDto r = accept(product, sha);
        em.flush();
        em.clear();

        assertThat(r.assetId()).as("the SAME (tenant, sha) row is reused, not a duplicate")
                .isEqualTo(failed.getId());
        assertThat(r.status()).isEqualTo("PENDING");

        MediaAsset reread = mediaAssetRepository.findById(failed.getId()).orElseThrow();
        assertThat(reread.getStatus()).as("the FAILED asset is reset to PENDING for reprocessing")
                .isEqualTo(MediaAsset.Status.PENDING);
        assertThat(reread.getFailureReason()).as("the stale failure reason is cleared").isNull();
        assertThat(reread.getProductId()).as("the placement intent is this re-upload's product")
                .isEqualTo(product);
        assertThat(countAssetsWithSha(sha)).as("no duplicate asset minted (same row reprocessed)").isEqualTo(1);
        assertThat(countOutbox()).as("a fresh outbox event is enqueued for reprocessing")
                .isEqualTo(outboxBefore + 1);
        // The raw bytes are re-quarantined (available on this accept even though the old quarantine was purged).
        Mockito.verify(storageService).putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
    }

    private long countOutbox() {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM media_event_outbox WHERE tenant_id = ?", Long.class, tenant);
        return c == null ? 0 : c;
    }

    // ---- helpers -----------------------------------------------------------

    private MediaAcceptDto accept(UUID productId, String sha) {
        return mediaAssetService.acceptQuarantineAndQueue(
                productId, "raw-bytes".getBytes(), sha, null,
                new MediaAssetService.MediaPlacement(true, 0));
    }

    private UUID seedProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)");
        return id;
    }

    private MediaAsset seedAsset(String sha, MediaAsset.Status status, UUID productIntent) {
        MediaAsset a = new MediaAsset();
        a.setTenantId(tenant);
        a.setObjectKey(status == MediaAsset.Status.ACTIVE
                ? tenant + "/media/" + UUID.randomUUID() + ".webp"
                : tenant + "/quarantine/" + sha + ".jpg");
        a.setSha256(sha);
        a.setContentType(status == MediaAsset.Status.ACTIVE ? "image/webp" : "image/jpeg");
        a.setStatus(status);
        if (productIntent != null) {
            a.setProductId(productIntent);
            a.setIsPrimary(true);
            a.setSortOrder(0);
        }
        return mediaAssetRepository.saveAndFlush(a);
    }

    private void newPrimaryLink(UUID productId, UUID assetId) {
        ProductMedia pm = new ProductMedia();
        pm.setTenantId(tenant);
        pm.setProductId(productId);
        pm.setAssetId(assetId);
        pm.setPrimary(true);
        pm.setSortOrder(0);
        productMediaRepository.saveAndFlush(pm);
    }

    private UUID primaryAssetOf(UUID productId) {
        return productMediaRepository.findByProductIdAndPrimaryTrue(productId)
                .map(ProductMedia::getAssetId)
                .orElse(null);
    }

    private int countAssetsWithSha(String sha) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE tenant_id = ? AND sha256 = ?", Integer.class, tenant, sha);
        return c == null ? 0 : c;
    }

    /** Pad/truncate to the CHAR(64) sha256 width. */
    private static String pad(String s) {
        return (s + "0".repeat(64)).substring(0, 64);
    }
}
