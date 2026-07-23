package uk.jtoye.core.product;

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
import uk.jtoye.core.media.MediaAsset;
import uk.jtoye.core.media.MediaAssetRepository;
import uk.jtoye.core.media.ProductMedia;
import uk.jtoye.core.media.ProductMediaRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMG-01 delete surface (24-05, T-24-26) — the vendor image-delete paths
 * ({@code ProductService.removeImage} / {@code removeAdditionalImage}) now drop the
 * {@code product_media} join row and ref-count-release the asset: a physical MinIO
 * delete happens ONLY at reference-count 0, and a still-referenced (shared) asset is
 * preserved. Proven over real Postgres.
 *
 * <p>Runs as the Testcontainers SUPERUSER (RLS bypassed) — like
 * {@code MediaCopyOnWriteIntegrationTest}, this exercises the CoW/ref-count delete
 * MECHANICS, not tenant isolation (proven separately under the NOSUPERUSER downgrade).
 * {@link StorageService} is a {@code @SpyBean} so the physical delete is asserted without a
 * live MinIO; {@link ShopAccessService} is a {@code @MockBean} so the SHOP_MANAGER gate is a
 * no-op here (proven elsewhere) and these tests focus on the delete wiring.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ProductImageDeleteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ProductService productService;
    @Autowired private ProductRepository productRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ProductMediaRepository productMediaRepository;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    @SpyBean private StorageService storageService;
    @MockBean private ShopAccessService shopAccessService;

    private UUID tenant;
    private int seq;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        seq = 0;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
        // No live MinIO: stub both the key-addressed and flat deletes to no-ops.
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
        Mockito.doNothing().when(storageService).delete(Mockito.anyString());
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

    private UUID seedProductWithGallery(String imageUrl, String... galleryUrls) {
        UUID id = UUID.randomUUID();
        Object[] arr = galleryUrls;
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO products "
                    + "(id, tenant_id, sku, title, ingredients_text, image_url, additional_image_urls) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)");
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, "SKU-" + id.toString().substring(0, 8));
            ps.setString(4, "Product");
            ps.setString(5, "Yam (100%)");
            ps.setString(6, imageUrl);
            ps.setArray(7, con.createArrayOf("text", arr));
            return ps;
        });
        return id;
    }

    private MediaAsset newAsset(MediaAsset.Status status) {
        MediaAsset a = new MediaAsset();
        a.setTenantId(tenant);
        a.setObjectKey(tenant + "/media/" + UUID.randomUUID() + ".webp");
        a.setSha256(String.format("%064d", seq++));
        a.setContentType("image/webp");
        a.setStatus(status);
        return mediaAssetRepository.saveAndFlush(a);
    }

    private void newPm(UUID productId, UUID assetId, boolean primary, int sortOrder) {
        ProductMedia pm = new ProductMedia();
        pm.setTenantId(tenant);
        pm.setProductId(productId);
        pm.setAssetId(assetId);
        pm.setPrimary(primary);
        pm.setSortOrder(sortOrder);
        productMediaRepository.saveAndFlush(pm);
    }

    private boolean assetExists(UUID assetId) {
        return mediaAssetRepository.findById(assetId).isPresent();
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void deletingLastReferenceReleasesAsset() {
        UUID p = seedProduct("http://minio/jtoye-images/flat.jpg");
        MediaAsset asset = newAsset(MediaAsset.Status.ACTIVE);
        String key = asset.getObjectKey();
        newPm(p, asset.getId(), true, 0);

        productService.removeImage(p);
        em.flush();
        em.clear();

        assertThat(productMediaRepository.findByProductIdAndPrimaryTrue(p))
                .as("the primary product_media row is dropped").isEmpty();
        assertThat(assetExists(asset.getId()))
                .as("sole reference removed -> media_asset deleted (ref-count 0)").isFalse();
        Mockito.verify(storageService).deleteByKey(key);   // physical MinIO delete happened at ref-count 0
        assertThat(productRepository.findById(p).orElseThrow().getImageUrl())
                .as("flat image_url dual-read cleanup preserved").isNull();
    }

    @Test
    void deletingWhileStillReferencedDoesNotDeleteAsset() {
        UUID p1 = seedProduct("http://minio/jtoye-images/flat-1.jpg");
        UUID p2 = seedProduct("http://minio/jtoye-images/flat-2.jpg");
        MediaAsset shared = newAsset(MediaAsset.Status.ACTIVE);
        String key = shared.getObjectKey();
        newPm(p1, shared.getId(), true, 0);
        newPm(p2, shared.getId(), true, 0);

        productService.removeImage(p1);
        em.flush();
        em.clear();

        assertThat(productMediaRepository.findByProductIdAndPrimaryTrue(p1))
                .as("p1's join row is dropped").isEmpty();
        assertThat(productMediaRepository.findByProductIdAndPrimaryTrue(p2))
                .as("p2's join row (sharing the asset) is untouched").isPresent();
        assertThat(assetExists(shared.getId()))
                .as("still referenced by p2 -> asset preserved").isTrue();
        assertThat(productMediaRepository.countByAssetId(shared.getId()))
                .as("exactly one reference remains").isEqualTo(1);
        Mockito.verify(storageService, Mockito.never()).deleteByKey(key);   // shared asset never physically deleted
    }

    @Test
    void removeAdditionalImageReleasesCorrectGalleryRow() {
        UUID p = seedProductWithGallery(null, "http://minio/jtoye-images/g1.jpg", "http://minio/jtoye-images/g2.jpg");
        MediaAsset g1 = newAsset(MediaAsset.Status.ACTIVE);
        MediaAsset g2 = newAsset(MediaAsset.Status.ACTIVE);
        String g1Key = g1.getObjectKey();
        newPm(p, g1.getId(), false, 1);   // gallery position 0 (sort_order 1 = 1-based backfill ordinality)
        newPm(p, g2.getId(), false, 2);   // gallery position 1

        productService.removeAdditionalImage(p, 0);
        em.flush();
        em.clear();

        assertThat(assetExists(g1.getId()))
                .as("the removed gallery entry's asset is released (ref-count 0)").isFalse();
        Mockito.verify(storageService).deleteByKey(g1Key);
        assertThat(assetExists(g2.getId()))
                .as("the remaining gallery asset is untouched").isTrue();
        assertThat(productMediaRepository.countByAssetId(g2.getId()))
                .as("the remaining gallery join row is untouched").isEqualTo(1);
        assertThat(productRepository.findById(p).orElseThrow().getAdditionalImageUrls())
                .as("flat additional_image_urls dual-read cleanup preserved (index 0 removed)")
                .containsExactly("http://minio/jtoye-images/g2.jpg");
    }
}
