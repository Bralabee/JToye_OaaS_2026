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
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMG-01 behavioural proof over real Postgres: copy-on-write repoint (D-01),
 * reference-counted delete-at-0 (physical MinIO delete ONLY when unreferenced),
 * sha256 per-tenant dedup, and the asset-first dual-read resolver query (D-03a).
 *
 * <p>Runs as the Testcontainers superuser (RLS bypassed) because these tests
 * exercise the CoW/ref-count MECHANICS, not tenant isolation — that wall is proven
 * separately under the NOSUPERUSER downgrade in
 * {@code MediaAssetRlsPolicyIntegrationTest}. {@link StorageService} is a
 * {@code @SpyBean} so the physical delete is asserted without a live MinIO (its real
 * {@code urlForKey} still runs for the dual-read check).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class MediaCopyOnWriteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ProductMediaRepository productMediaRepository;
    @Autowired private MediaAssetService mediaAssetService;
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
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---- helpers -----------------------------------------------------------

    private UUID seedProduct(String imageUrl) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text, image_url) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)", imageUrl);
        return id;
    }

    private MediaAsset newAsset(String sha, MediaAsset.Status status) {
        MediaAsset a = new MediaAsset();
        a.setTenantId(tenant);
        a.setObjectKey(tenant + "/media/" + UUID.randomUUID() + ".webp");
        a.setSha256(pad(sha));
        a.setContentType("image/webp");
        a.setStatus(status);
        return mediaAssetRepository.saveAndFlush(a);
    }

    private ProductMedia newPm(UUID productId, UUID assetId, boolean primary, int sortOrder) {
        ProductMedia pm = new ProductMedia();
        pm.setTenantId(tenant);
        pm.setProductId(productId);
        pm.setAssetId(assetId);
        pm.setPrimary(primary);
        pm.setSortOrder(sortOrder);
        return productMediaRepository.saveAndFlush(pm);
    }

    /** Pad/truncate to the CHAR(64) sha256 width. */
    private static String pad(String s) {
        String h = (s + "0".repeat(64));
        return h.substring(0, 64);
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void repointOnlyAffectsOneRow() {
        UUID p1 = seedProduct("flat-1");
        UUID p2 = seedProduct("flat-2");
        MediaAsset shared = newAsset("aaaa", MediaAsset.Status.ACTIVE);
        MediaAsset replacement = newAsset("bbbb", MediaAsset.Status.ACTIVE);
        ProductMedia pm1 = newPm(p1, shared.getId(), true, 0);
        ProductMedia pm2 = newPm(p2, shared.getId(), true, 0);

        mediaAssetService.repoint(pm1.getId(), replacement.getId());
        em.flush();
        em.clear();

        assertThat(productMediaRepository.findById(pm1.getId()).orElseThrow().getAssetId())
                .as("the repointed row now references the new asset")
                .isEqualTo(replacement.getId());
        assertThat(productMediaRepository.findById(pm2.getId()).orElseThrow().getAssetId())
                .as("the OTHER product sharing the asset is untouched (CoW)")
                .isEqualTo(shared.getId());
    }

    @Test
    void deletesOnlyAtRefCountZero() {
        UUID p1 = seedProduct("flat-1");
        UUID p2 = seedProduct("flat-2");
        MediaAsset shared = newAsset("cccc", MediaAsset.Status.ACTIVE);
        String key = shared.getObjectKey();
        ProductMedia pmA = newPm(p1, shared.getId(), true, 0);
        ProductMedia pmB = newPm(p2, shared.getId(), true, 0);

        // Two references -> not deleted.
        mediaAssetService.releaseAsset(shared.getId());
        em.flush();
        assertThat(assetExists(shared.getId())).as("still referenced by 2 rows -> kept").isTrue();
        Mockito.verify(storageService, Mockito.never()).deleteByKey(key);

        // Drop one reference -> still referenced.
        productMediaRepository.delete(pmA);
        productMediaRepository.flush();
        mediaAssetService.releaseAsset(shared.getId());
        em.flush();
        assertThat(assetExists(shared.getId())).as("still referenced by 1 row -> kept").isTrue();
        Mockito.verify(storageService, Mockito.never()).deleteByKey(key);

        // Drop the last reference -> physical delete + row removed.
        productMediaRepository.delete(pmB);
        productMediaRepository.flush();
        mediaAssetService.releaseAsset(shared.getId());
        em.flush();
        assertThat(assetExists(shared.getId())).as("ref-count 0 -> row deleted").isFalse();
        Mockito.verify(storageService).deleteByKey(key);   // physical MinIO delete happened exactly at 0
    }

    @Test
    void identicalUploadDedupsPerTenant() {
        String sha = pad("dedupe");
        MediaAsset first = newAsset("dedupe", MediaAsset.Status.ACTIVE);

        // Same tenant + same sha -> the existing ACTIVE asset is returned (dedup short-circuit).
        assertThat(mediaAssetService.findDedup(tenant, sha))
                .as("dedup returns the existing asset for an identical raw sha")
                .isPresent()
                .get()
                .satisfies(a -> {
                    assertThat(a.getId()).isEqualTo(first.getId());
                    assertThat(a.getStatus()).isEqualTo(MediaAsset.Status.ACTIVE);
                });

        // A DIFFERENT tenant may hold the SAME sha (dedup is per-tenant).
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                otherTenant, "test-" + otherTenant);
        MediaAsset other = new MediaAsset();
        other.setTenantId(otherTenant);
        other.setObjectKey(otherTenant + "/media/x.webp");
        other.setSha256(sha);
        other.setContentType("image/webp");
        other.setStatus(MediaAsset.Status.ACTIVE);
        assertThat(mediaAssetRepository.saveAndFlush(other).getId())
                .as("a different tenant may reuse the same sha (per-tenant unique index)")
                .isNotNull();

        // A second asset with the SAME (tenant, sha) violates uq_media_asset_tenant_sha.
        MediaAsset dup = new MediaAsset();
        dup.setTenantId(tenant);
        dup.setObjectKey(tenant + "/media/dup.webp");
        dup.setSha256(sha);
        dup.setContentType("image/webp");
        dup.setStatus(MediaAsset.Status.ACTIVE);
        assertThatThrownBy(() -> mediaAssetRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dualReadResolverReturnsActivePrimaryElseEmpty() {
        // ACTIVE primary derivative -> the resolver query returns its object_key.
        UUID pActive = seedProduct("https://cdn/flat-active.jpg");
        MediaAsset active = newAsset("active", MediaAsset.Status.ACTIVE);
        newPm(pActive, active.getId(), true, 0);
        assertThat(productMediaRepository.findPrimaryActiveObjectKey(pActive))
                .as("an ACTIVE primary resolves asset-first")
                .contains(active.getObjectKey());

        // Only a flat image_url (no product_media) -> empty (caller falls back to image_url).
        UUID pFlat = seedProduct("https://cdn/flat-only.jpg");
        assertThat(productMediaRepository.findPrimaryActiveObjectKey(pFlat))
                .as("no product_media -> empty -> flat fallback")
                .isEmpty();

        // A PENDING primary -> empty (the derivative is not yet servable).
        UUID pPending = seedProduct("https://cdn/flat-pending.jpg");
        MediaAsset pending = newAsset("pending", MediaAsset.Status.PENDING);
        newPm(pPending, pending.getId(), true, 0);
        assertThat(productMediaRepository.findPrimaryActiveObjectKey(pPending))
                .as("a PENDING primary resolves empty -> flat fallback during processing")
                .isEmpty();
    }

    private boolean assetExists(UUID assetId) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE id = ?", Integer.class, assetId);
        return c != null && c > 0;
    }
}
