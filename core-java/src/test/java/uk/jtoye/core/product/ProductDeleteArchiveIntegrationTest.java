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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.ResourceInUseException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #832 (UXT-063; ADE-04, P2-CHA-19): a vendor could not delete a product once it had a photo or had
 * ever been ordered.
 *
 * <ul>
 *   <li>A photo: {@code product_media.product_id} has no ON DELETE CASCADE and {@code deleteProduct} never
 *       removed the join rows, so the delete hit 23503 and was reported as "still referenced by an
 *       existing order" - a false reason.</li>
 *   <li>An order of any status, even CANCELLED: {@code fk_order_items_product}. The vendor's only option was
 *       available=false, which leaves the dead item in the catalogue for ever.</li>
 * </ul>
 *
 * Now: the delete drops the product's {@code product_media} links and ref-count-releases their assets
 * (V53: the object is deleted only at zero references); a product that order lines still name is ARCHIVED
 * (V90 {@code archived_at}) instead - off every list, the storefront and the SKU index - while the order
 * history keeps its snapshotted name; a product on an order that is still in flight is refused (409).
 *
 * <p>Real Postgres under Testcontainers; the superuser role bypasses RLS, so this proves the delete/archive
 * mechanics, not tenant isolation (the delete itself is the unchanged tenant-scoped repository call).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ProductDeleteArchiveIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
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
    @MockitoSpyBean private StorageService storageService;
    @MockitoBean private ShopAccessService shopAccessService;

    private UUID tenant;
    private UUID shopId;
    private int seq;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        seq = 0;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                shopId, tenant, "Shop " + shopId, "shop-" + shopId.toString().substring(0, 8));
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
        Mockito.doReturn(true).when(storageService).delete(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---- helpers -----------------------------------------------------------

    private UUID seedProduct(String sku, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text, shop_id, available) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true)",
                id, tenant, sku, title, "Yam (100%)", shopId);
        return id;
    }

    private MediaAsset newAsset() {
        MediaAsset a = new MediaAsset();
        a.setTenantId(tenant);
        a.setObjectKey(tenant + "/media/" + UUID.randomUUID() + ".webp");
        a.setSha256(String.format("%064d", seq++));
        a.setContentType("image/webp");
        a.setStatus(MediaAsset.Status.ACTIVE);
        return mediaAssetRepository.saveAndFlush(a);
    }

    private void link(UUID productId, UUID assetId, boolean primary, int sortOrder) {
        ProductMedia pm = new ProductMedia();
        pm.setTenantId(tenant);
        pm.setProductId(productId);
        pm.setAssetId(assetId);
        pm.setPrimary(primary);
        pm.setSortOrder(sortOrder);
        productMediaRepository.saveAndFlush(pm);
    }

    private UUID seedOrderWith(UUID productId, String status, String productName) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                        + "total_amount_pennies, item_count, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0, now(), now())",
                orderId, tenant, shopId, "ORD-" + orderId, status, 500L, "ZERO", 500L, 1);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, product_id, quantity, "
                        + "unit_price_pennies, total_price_pennies, product_name, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, 500, 500, ?, now())",
                UUID.randomUUID(), tenant, orderId, productId, productName);
        return orderId;
    }

    private void settle() {
        em.flush();
        em.clear();
    }

    private int rows(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void aProductWithAPhotoAndNoOrdersIsDeleted() {
        UUID p = seedProduct("PHOTO-1", "Jollof");
        MediaAsset asset = newAsset();
        link(p, asset.getId(), true, 0);

        productService.deleteProduct(p);
        settle();

        assertThat(rows("SELECT COUNT(*) FROM products WHERE id = ?", p)).as("the product row is gone").isZero();
        assertThat(rows("SELECT COUNT(*) FROM product_media WHERE product_id = ?", p))
                .as("its product_media link is gone").isZero();
        assertThat(mediaAssetRepository.findById(asset.getId()))
                .as("sole reference released -> the asset is deleted (V53 ref-count 0)").isEmpty();
        Mockito.verify(storageService).deleteByKey(asset.getObjectKey());
    }

    @Test
    void aSharedAssetSurvivesTheDeleteOfOneOfItsProducts() {
        UUID p1 = seedProduct("SHARED-1", "Jollof");
        UUID p2 = seedProduct("SHARED-2", "Jollof copy");
        MediaAsset shared = newAsset();
        link(p1, shared.getId(), true, 0);
        link(p2, shared.getId(), true, 0);

        productService.deleteProduct(p1);
        settle();

        assertThat(rows("SELECT COUNT(*) FROM products WHERE id = ?", p1)).isZero();
        assertThat(mediaAssetRepository.findById(shared.getId()))
                .as("still referenced by the other product -> the asset is preserved").isPresent();
        assertThat(productMediaRepository.countByAssetId(shared.getId())).isEqualTo(1);
        Mockito.verify(storageService, Mockito.never()).deleteByKey(shared.getObjectKey());
    }

    @Test
    void aProductOnlyOnACancelledOrderIsArchivedNotRefused() {
        UUID p = seedProduct("CANCELLED-1", "Zobo");
        UUID order = seedOrderWith(p, "CANCELLED", "Zobo");

        productService.deleteProduct(p);   // used to be a 409 (fk_order_items_product)
        settle();

        assertThat(rows("SELECT COUNT(*) FROM products WHERE id = ? AND archived_at IS NOT NULL AND available = false", p))
                .as("the row stays, archived and unavailable, so order_items keeps its foreign key").isEqualTo(1);
        assertThat(productRepository.findById(p)).as("an archived product is invisible to entity reads").isEmpty();
        assertThat(productRepository.findAll().stream().map(Product::getId))
                .as("and to the vendor's list").doesNotContain(p);
        assertThat(jdbc.queryForObject("SELECT product_name FROM order_items WHERE order_id = ?", String.class, order))
                .as("the order history keeps the snapshotted name").isEqualTo("Zobo");
    }

    @Test
    void aProductWithAPhotoAndACancelledOrderIsArchivedAndItsPhotoReleased() {
        UUID p = seedProduct("BOTH-1", "Puff puff");
        MediaAsset asset = newAsset();
        link(p, asset.getId(), true, 0);
        seedOrderWith(p, "CANCELLED", "Puff puff");

        productService.deleteProduct(p);
        settle();

        assertThat(rows("SELECT COUNT(*) FROM products WHERE id = ? AND archived_at IS NOT NULL", p)).isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM product_media WHERE product_id = ?", p))
                .as("the archived product keeps no photo link").isZero();
        assertThat(mediaAssetRepository.findById(asset.getId())).as("and its asset is released").isEmpty();
    }

    @Test
    void anArchivedProductsSkuCanBeUsedAgain() {
        UUID p = seedProduct("REUSE-1", "Old dish");
        seedOrderWith(p, "COMPLETED", "Old dish");
        productService.deleteProduct(p);
        settle();

        UUID again = seedProduct("REUSE-1", "New dish");   // would violate idx_products_tenant_sku before V90
        assertThat(again).isNotEqualTo(p);
        assertThat(productRepository.existsByTenantIdAndSku(tenant, "REUSE-1"))
                .as("the live product answers for the SKU, the archived one does not").isTrue();
    }

    @Test
    void anArchivedProductIsNotFoundBySearch() {
        UUID p = seedProduct("SEARCH-1", "Quenelle");
        seedOrderWith(p, "REFUNDED", "Quenelle");
        productService.deleteProduct(p);
        settle();

        assertThat(productRepository.searchFullText("quenelle:*", "search-%", PageRequest.of(0, 10)).getContent())
                .as("the native FTS search carries the archived predicate by hand").isEmpty();
        assertThat(productRepository.searchFullTextByShop("quenelle:*", "search-%", shopId, PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    void aProductOnAnOrderStillInFlightIsRefusedAndKept() {
        UUID p = seedProduct("OPEN-1", "Suya");
        seedOrderWith(p, "PREPARING", "Suya");

        assertThatThrownBy(() -> productService.deleteProduct(p))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("order that is still open");
        settle();

        assertThat(rows("SELECT COUNT(*) FROM products WHERE id = ? AND archived_at IS NULL", p))
                .as("neither deleted nor archived").isEqualTo(1);
    }
}
