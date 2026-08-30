package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-council cluster P1 (API-1 + API-3 rider) — {@code PUT /products/{id}} reuses
 * {@link CreateProductRequest} for a partial edit, but {@link ProductMapper#updateEntity} had no
 * {@code NullValuePropertyMappingStrategy.IGNORE}: any field the edit form omits arrives as
 * {@code null} and MapStruct wrote that null straight onto the entity, wiping fields the vendor
 * never touched (and, for {@code displayOrder}/{@code available}/{@code featured}, a NOT NULL
 * DEFAULT column — SQLState 23502).
 *
 * <p>The adjudicated correct semantics is PARTIAL MERGE: an omitted/null field preserves the
 * existing value, exactly {@code ShopMapper.updateEntity}'s existing {@code @BeanMapping} for
 * QA-council BE-02 (the real origin commit is {@code 8747912d}, "fix(shops): restore shop writes
 * — audit-column drift + partial-update (BE-02)" — squash-merged into main under an unrelated
 * "Phase 17" commit, {@code c8082ade}, which is why {@code git log} misattributes it there).
 *
 * <p>Blanket IGNORE is not, on its own, correct: {@code quantityInStock} is the one field where
 * an explicit {@code null} is a real instruction ("vendor unchecked inventory tracking"), so it
 * carries its own {@code @Mapping}-level override; and {@code vatRate} needed its DTO default
 * REMOVED so an omitted field actually arrives {@code null} (a request that never mentions
 * {@code vatRate} previously always carried the DTO's Java-side default {@code STANDARD}, which
 * is indistinguishable from an explicit STANDARD and would have resurfaced under blanket IGNORE
 * as a silent ZERO_RATED -> STANDARD reset on every edit).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ProductPartialUpdateIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;

    // Authorization is not under test here (proven elsewhere) — same pattern as
    // ProductImageDeleteIntegrationTest.
    @MockBean private ShopAccessService shopAccessService;

    private UUID tenant;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Leg 1 — omitted boxed fields on NOT-NULL-DEFAULT columns must be preserved
    // ------------------------------------------------------------------

    @Test
    void putOmittingDisplayOrderAvailableFeaturedPreservesExistingValues() {
        ProductDto created = productService.createProduct(
                fullRequest("PU-SKU-1", VatRate.STANDARD, 7, false, true, 10, 3, "USE_BY"));

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-1");
        // displayOrder / available / featured / quantityInStock / shelfLifeDays / durabilityType
        // all deliberately left unset below — the exact shape of an edit-form PUT that never
        // renders those controls.

        ProductDto updated = productService.updateProduct(created.getId(), edit);

        assertThat(updated.getDisplayOrder()).as("displayOrder must survive an edit that omits it")
                .isEqualTo(7);
        assertThat(updated.getAvailable()).as("available must survive an edit that omits it")
                .isFalse();
        assertThat(updated.getFeatured()).as("featured must survive an edit that omits it")
                .isTrue();
    }

    @Test
    void putOmittingShelfLifeDaysAndDurabilityTypePreservesPpdsFields() {
        ProductDto created = productService.createProduct(
                fullRequest("PU-SKU-2", VatRate.STANDARD, 0, true, false, 10, 5, "BEST_BEFORE"));

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-2");

        ProductDto updated = productService.updateProduct(created.getId(), edit);

        assertThat(updated.getShelfLifeDays())
                .as("PPDS shelf-life must survive an edit form that never renders it")
                .isEqualTo(5);
        assertThat(updated.getDurabilityType())
                .as("PPDS durability type must survive an edit form that never renders it")
                .isEqualTo("BEST_BEFORE");
    }

    // ------------------------------------------------------------------
    // Leg 2 — quantityInStock is the ONE field where explicit null must still clear
    // ------------------------------------------------------------------

    @Test
    void explicitNullQuantityInStockClearsTracking() {
        ProductDto created = productService.createProduct(
                fullRequest("PU-SKU-3", VatRate.STANDARD, 0, true, false, 15, null, null));
        assertThat(created.getQuantityInStock()).isEqualTo(15);

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-3");
        // quantityInStock deliberately left null: "vendor unchecked inventory tracking".

        ProductDto updated = productService.updateProduct(created.getId(), edit);

        assertThat(updated.getQuantityInStock())
                .as("an explicit null quantityInStock must still clear tracking, even though "
                        + "every OTHER omitted field on this same request must be preserved")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Leg 3 — vatRate: an omitted field must preserve, not reset to STANDARD
    // ------------------------------------------------------------------

    @Test
    void putWithoutVatRatePreservesZeroRatedProduct() {
        ProductDto created = productService.createProduct(
                fullRequest("PU-SKU-4", VatRate.ZERO, 0, true, false, 10, null, null));
        assertThat(created.getVatRate()).isEqualTo(VatRate.ZERO);

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-4");
        // vatRate deliberately left unset — the edit form never renders it.

        ProductDto updated = productService.updateProduct(created.getId(), edit);

        assertThat(updated.getVatRate())
                .as("omitting vatRate on an edit must not silently reset a ZERO-rated product "
                        + "to STANDARD")
                .isEqualTo(VatRate.ZERO);
    }

    @Test
    void createWithoutVatRateDefaultsToStandard() {
        CreateProductRequest create = requiredFieldsOnly("PU-SKU-5");
        // vatRate deliberately left unset on a CREATE (not an edit) — must still default.

        ProductDto created = productService.createProduct(create);

        assertThat(created.getVatRate())
                .as("a brand-new product must never be silently created with a null VAT rate")
                .isEqualTo(VatRate.STANDARD);
    }

    // ---- helpers -------------------------------------------------------

    /** Only the fields validation actually requires on every PUT — the omitted-field shape. */
    private CreateProductRequest requiredFieldsOnly(String sku) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku(sku);
        req.setTitle("Test product " + sku);
        req.setIngredientsText("Water");
        req.setAllergenMask(0);
        req.setPricePennies(500L);
        return req;
    }

    private CreateProductRequest fullRequest(String sku, VatRate vatRate, int displayOrder,
            boolean available, boolean featured, Integer quantityInStock, Integer shelfLifeDays,
            String durabilityType) {
        CreateProductRequest req = requiredFieldsOnly(sku);
        req.setVatRate(vatRate);
        req.setDisplayOrder(displayOrder);
        req.setAvailable(available);
        req.setFeatured(featured);
        req.setQuantityInStock(quantityInStock);
        req.setShelfLifeDays(shelfLifeDays);
        req.setDurabilityType(durabilityType);
        return req;
    }
}
