package uk.jtoye.core.product;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.LabelRenderModel.IngredientRun;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductLabelService}.
 *
 * <p>Covers the pure {@code buildRenderModel} (render-model shape + negative
 * asserts), the mock-wired {@code generateLabel} (real PDF-text extraction via
 * OpenPDF's {@link PdfTextExtractor}), and the fail-loud paths that must 422
 * (missing address, missing durability, and a NON-NULL shopId that resolves to
 * no tenant-owned shop) rather than emit a non-compliant PDF or a 500.
 */
@ExtendWith(MockitoExtension.class)
class ProductLabelServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ShopRepository shopRepository;

    @InjectMocks
    private ProductLabelService productLabelService;

    private UUID tenantId;
    private UUID productId;
    private UUID shopId;

    private static void setField(Object target, String fieldName, Object value) {
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
        productId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        // generateLabel calls TenantContext.get() whenever shopId != null.
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Product compliantProduct() {
        Product product = new Product();
        setField(product, "id", productId);
        product.setTenantId(tenantId);
        product.setSku("YAM-500");
        product.setTitle("Yam Pottage 500g");
        product.setIngredientsText("Wheat flour, **milk**, sugar");
        product.setAllergenMask(0);
        product.setPricePennies(599L);
        product.setShelfLifeDays(3);
        product.setDurabilityType("USE_BY");
        product.setShopId(shopId);
        return product;
    }

    private Shop compliantShop() {
        Shop shop = new Shop();
        shop.setName("Test Kitchen Ltd");
        shop.setAddress("12 Market Street, London, E1 6AN");
        return shop;
    }

    // ---- Pure render-model ----

    @Test
    @DisplayName("buildRenderModel - emits an inline emphasised 'milk' run and a fixed durability line")
    void buildRenderModelHappyPath() {
        LabelRenderModel model = ProductLabelService.buildRenderModel(
                compliantProduct(), compliantShop(), LocalDate.of(2026, 7, 5));

        assertThat(model.ingredientRuns())
                .anySatisfy(run -> {
                    assertThat(run.text()).isEqualTo("milk");
                    assertThat(run.emphasised()).isTrue();
                });
        assertThat(model.ingredientRuns())
                .filteredOn(run -> !run.emphasised())
                .extracting(IngredientRun::text)
                .contains("Wheat flour, ", ", sugar");

        assertThat(model.durabilityLine()).isEqualTo("Use by: 8 Jul 2026");
        assertThat(model.businessName()).isEqualTo("Test Kitchen Ltd");
        assertThat(model.businessAddress()).isEqualTo("12 Market Street, London, E1 6AN");
    }

    @Test
    @DisplayName("buildRenderModel - BEST_BEFORE durability wording")
    void buildRenderModelBestBefore() {
        Product product = compliantProduct();
        product.setDurabilityType("BEST_BEFORE");
        product.setShelfLifeDays(10);

        LabelRenderModel model = ProductLabelService.buildRenderModel(
                product, compliantShop(), LocalDate.of(2026, 7, 5));

        assertThat(model.durabilityLine()).isEqualTo("Best before: 15 Jul 2026");
    }

    @Test
    @DisplayName("buildRenderModel - no run or field carries a prohibited fallback string")
    void buildRenderModelHasNoProhibitedContent() {
        LabelRenderModel model = ProductLabelService.buildRenderModel(
                compliantProduct(), compliantShop(), LocalDate.of(2026, 7, 5));

        assertThat(model.ingredientRuns())
                .extracting(IngredientRun::text)
                .allSatisfy(text -> {
                    assertThat(text).doesNotContain("CONTAINS");
                    assertThat(text).doesNotContain("No allergens declared");
                });
        assertThat(model.durabilityLine()).doesNotContain("CONTAINS");
    }

    // ---- Mock-wired generateLabel ----

    @Test
    @DisplayName("generateLabel - returns valid PDF bytes for a compliant product")
    void generateLabelReturnsPdfBytes() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(compliantProduct()));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                .thenReturn(Optional.of(compliantShop()));

        byte[] result = productLabelService.generateLabel(productId);

        assertThat(result).isNotEmpty();
        assertThat(new String(result, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("generateLabel - PDF text has product name + Use by + business identity, and NO prohibited fallback")
    void generateLabelPdfTextIsCompliant() throws Exception {
        when(productRepository.findById(productId)).thenReturn(Optional.of(compliantProduct()));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                .thenReturn(Optional.of(compliantShop()));

        byte[] pdf = productLabelService.generateLabel(productId);
        String text = extractText(pdf);

        // Positive: FSA-required content is present.
        assertThat(text).contains("Yam Pottage 500g");
        assertThat(text).contains("Ingredients:");
        assertThat(text).contains("milk");
        assertThat(text).contains("Use by:");
        assertThat(text).contains("Test Kitchen Ltd");
        assertThat(text).contains("Market Street");
        // Negative: the removed non-compliant format must be gone.
        assertThat(text).doesNotContain("CONTAINS");
        assertThat(text).doesNotContain("No allergens declared");
    }

    // ---- Fail-loud (422) ----

    @Test
    @DisplayName("generateLabel - 422 naming business address when the shop address is blank")
    void generateLabelFailsWhenAddressBlank() {
        Shop noAddress = compliantShop();
        noAddress.setAddress("   ");
        when(productRepository.findById(productId)).thenReturn(Optional.of(compliantProduct()));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(noAddress));

        assertThatThrownBy(() -> productLabelService.generateLabel(productId))
                .isInstanceOf(IncompleteLabelDataException.class)
                .hasMessageContaining("business address");
    }

    @Test
    @DisplayName("generateLabel - 422 naming durability fields when shelf life / durability type are null")
    void generateLabelFailsWhenDurabilityMissing() {
        Product product = compliantProduct();
        product.setShelfLifeDays(null);
        product.setDurabilityType(null);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                .thenReturn(Optional.of(compliantShop()));

        assertThatThrownBy(() -> productLabelService.generateLabel(productId))
                .isInstanceOf(IncompleteLabelDataException.class)
                .hasMessageContaining("shelf life")
                .hasMessageContaining("durability type");
    }

    @Test
    @DisplayName("generateLabel - 422 (not 500) when a NON-NULL shopId resolves to no tenant-owned shop")
    void generateLabelFailsWhenShopResolvesEmpty() {
        // shop_id is non-null but the tenant-scoped lookup is empty (orphaned /
        // cross-tenant). Must be treated as missing business identity -> 422, NEVER
        // a NoSuchElementException / 500.
        when(productRepository.findById(productId)).thenReturn(Optional.of(compliantProduct()));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productLabelService.generateLabel(productId))
                .isInstanceOf(IncompleteLabelDataException.class)
                .hasMessageContaining("business identity");
    }

    @Test
    @DisplayName("generateLabel - still throws ResourceNotFoundException when the product is absent")
    void generateLabelProductNotFound() {
        UUID missingId = UUID.randomUUID();
        when(productRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productLabelService.generateLabel(missingId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found")
                .hasMessageContaining(missingId.toString());
    }

    // ---- Helpers ----

    private static String extractText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder sb = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
}
