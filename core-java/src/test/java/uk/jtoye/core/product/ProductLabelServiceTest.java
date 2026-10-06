package uk.jtoye.core.product;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.InvalidProductionDateException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.LabelRenderModel.IngredientRun;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    // Phase 23 (VSA-02): require(...) is a void no-op on the bare mock, so generateLabel
    // proceeds exactly as before — no stub needed, just satisfy the constructor dependency.
    @Mock
    private ShopAccessService shopAccessService;

    /**
     * #861 (D-17): "today" for the label comes from an injected clock. Fixed at 10:00 UTC on
     * 5 July 2026 (11:00 BST), and deliberately in UTC, so the service must resolve the London
     * date itself.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);

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
        productLabelService = serviceAt(FIXED_CLOCK);
    }

    private ProductLabelService serviceAt(Clock clock) {
        return new ProductLabelService(productRepository, shopRepository, shopAccessService, clock);
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

        assertThat(model.durabilityLine()).isEqualTo("Use by: 8 July 2026");
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

        assertThat(model.durabilityLine()).isEqualTo("Best before: 15 July 2026");
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
        assertThat(pageCount(pdf)).as("label pages").isEqualTo(1);

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

    @Test
    @DisplayName("generateLabel - the two new #861 lines (May contain, Produced) still fit the 100x60mm label on ONE page")
    void generateLabelWithMayContainFitsOnePage() throws Exception {
        Product product = compliantProduct();
        product.setMayContainMask((1 << 10) | (1 << 7)); // Sesame, Nuts: neither declared
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                .thenReturn(Optional.of(compliantShop()));

        byte[] pdf = productLabelService.generateLabel(productId, LocalDate.of(2026, 7, 4));

        assertThat(pageCount(pdf)).as("label pages").isEqualTo(1);
        String text = extractText(pdf);
        assertThat(text).contains("May contain: Nuts, Sesame");
        assertThat(text).contains("Produced: 4 July 2026");
        assertThat(text).contains("Use by: 7 July 2026");
        assertThat(text).contains("12 Market Street, London, E1 6AN");
    }

    // ---- #861 (D-16, D-17): may-contain, production date, en-GB ----

    private static final int CRUSTACEANS = 1 << 1;
    private static final int MILK = 1 << 6;
    private static final int NUTS = 1 << 7;
    private static final int SESAME = 1 << 10;

    /** A product declaring Milk, shelf life 2, use-by, with the given may-contain mask. */
    private Product milkProduct(Integer mayContainMask) {
        Product product = compliantProduct();
        product.setIngredientsText("Rice, **milk**, pepper");
        product.setAllergenMask(MILK);
        product.setShelfLifeDays(2);
        product.setMayContainMask(mayContainMask);
        return product;
    }

    private String labelText(Product product, LocalDate productionDate, Clock clock) throws Exception {
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(compliantShop()));
        return extractText(serviceAt(clock).generateLabel(productId, productionDate));
    }

    @Test
    @DisplayName("#861 feature case: Milk declared, may-contain Sesame|Milk, produced 2026-10-03, shelf life 2 -> Produced 3 October, Use by 5 October, May contain: Sesame (Milk omitted)")
    void buildRenderModelProductionDateAndMayContain() {
        LabelRenderModel model = ProductLabelService.buildRenderModel(
                milkProduct(SESAME | MILK), compliantShop(), LocalDate.of(2026, 10, 3));

        assertThat(model.productionDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(model.productionLine()).isEqualTo("Produced: 3 October 2026");
        assertThat(model.durabilityLine()).isEqualTo("Use by: 5 October 2026");
        assertThat(model.mayContainNames()).containsExactly("Sesame");
        assertThat(model.mayContainLine()).isEqualTo("May contain: Sesame");
        // The declared emphasis is unchanged: milk is still the one emphasised run.
        assertThat(model.ingredientRuns()).filteredOn(IngredientRun::emphasised)
                .extracting(IngredientRun::text).containsExactly("milk");
    }

    @Test
    @DisplayName("#861 adjacency: a bit in BOTH masks is shown only under the declaration; the stored may-contain mask keeps it")
    void declaredBitIsOmittedFromMayContainButKeptInTheMask() {
        Product product = milkProduct(MILK);

        LabelRenderModel model = ProductLabelService.buildRenderModel(
                product, compliantShop(), LocalDate.of(2026, 10, 3));

        assertThat(model.mayContainNames()).as("Milk is declared, so nothing is left to say").isEmpty();
        assertThat(model.mayContainLine()).isNull();
        assertThat(product.getMayContainMask()).as("stored may-contain mask is untouched").isEqualTo(MILK);
        assertThat(product.getAllergenMask()).as("declared mask is untouched").isEqualTo(MILK);
    }

    @Test
    @DisplayName("#861 empty: may-contain NULL and 0 both give no 'May contain' line and an empty list")
    void mayContainNullAndZeroPrintNoLine() throws Exception {
        for (Integer mask : new Integer[]{null, 0}) {
            LabelRenderModel model = ProductLabelService.buildRenderModel(
                    milkProduct(mask), compliantShop(), LocalDate.of(2026, 10, 3));
            assertThat(model.mayContainNames()).as("names for mask %s", mask).isEmpty();
            assertThat(model.mayContainLine()).as("line for mask %s", mask).isNull();

            String text = labelText(milkProduct(mask), LocalDate.of(2026, 10, 3),
                    Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC));
            assertThat(text).as("PDF for mask %s", mask).doesNotContain("May contain");
        }
    }

    @Test
    @DisplayName("#861 ordering: may-contain names are in AllergenCatalog bit order whatever order the mask was built in")
    void mayContainNamesAreInCatalogueOrder() {
        int builtBackwards = 0;
        builtBackwards |= SESAME;
        builtBackwards |= NUTS;
        builtBackwards |= CRUSTACEANS;

        LabelRenderModel model = ProductLabelService.buildRenderModel(
                milkProduct(builtBackwards), compliantShop(), LocalDate.of(2026, 10, 3));

        assertThat(model.mayContainNames()).containsExactly("Crustaceans", "Nuts", "Sesame");
        assertThat(model.mayContainLine()).isEqualTo("May contain: Crustaceans, Nuts, Sesame");
    }

    @Test
    @DisplayName("#861 encoding: dates are en-GB with the FULL month name ('30 September 2026', never 'Sept' or 'Sep')")
    void datesUseTheFullUkMonthName() {
        LabelRenderModel model = ProductLabelService.buildRenderModel(
                milkProduct(null), compliantShop(), LocalDate.of(2026, 9, 28));

        assertThat(model.productionLine()).isEqualTo("Produced: 28 September 2026");
        assertThat(model.durabilityLine()).isEqualTo("Use by: 30 September 2026");
    }

    @Test
    @DisplayName("#861 BST midnight: no productionDate at 2026-10-03T23:30Z (00:30 BST on 4 October) is labelled 4 October")
    void defaultProductionDateIsTheLondonDateAtBstMidnight() throws Exception {
        Clock halfPastMidnightBst = Clock.fixed(Instant.parse("2026-10-03T23:30:00Z"), ZoneOffset.UTC);

        String text = labelText(milkProduct(null), null, halfPastMidnightBst);

        assertThat(text).contains("Produced: 4 October 2026");
        assertThat(text).contains("Use by: 6 October 2026");
    }

    @Test
    @DisplayName("#861 BST midnight: at 00:30 BST on 4 October, productionDate 4 October is today (allowed) and 5 October is tomorrow (422 naming productionDate)")
    void futureProductionDateIsJudgedAgainstTheLondonDate() throws Exception {
        Clock halfPastMidnightBst = Clock.fixed(Instant.parse("2026-10-03T23:30:00Z"), ZoneOffset.UTC);

        assertThat(labelText(milkProduct(null), LocalDate.of(2026, 10, 4), halfPastMidnightBst))
                .contains("Produced: 4 October 2026");

        assertThatThrownBy(() -> labelText(milkProduct(null), LocalDate.of(2026, 10, 5), halfPastMidnightBst))
                .isInstanceOf(InvalidProductionDateException.class)
                .hasMessageContaining("productionDate")
                .hasMessageContaining("after today");
    }

    @Test
    @DisplayName("#861: a productionDate whose use-by (or best-before) is before today is a 422 naming productionDate; a durability date of today is allowed")
    void passedDurabilityDateIsRefused() throws Exception {
        Clock fourthOctober = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> labelText(milkProduct(null), LocalDate.of(2026, 10, 1), fourthOctober))
                .isInstanceOf(InvalidProductionDateException.class)
                .hasMessageContaining("productionDate")
                .hasMessageContaining("use-by date of 3 October 2026");

        Product bestBefore = milkProduct(null);
        bestBefore.setDurabilityType("BEST_BEFORE");
        assertThatThrownBy(() -> labelText(bestBefore, LocalDate.of(2026, 10, 1), fourthOctober))
                .isInstanceOf(InvalidProductionDateException.class)
                .hasMessageContaining("best-before date of 3 October 2026");

        assertThat(labelText(milkProduct(null), LocalDate.of(2026, 10, 2), fourthOctober))
                .contains("Use by: 4 October 2026");
    }

    @Test
    @DisplayName("#861 concurrency: the default date is read from the clock on EVERY request, so downloads either side of UK midnight get their own dates")
    void defaultDateIsResolvedPerRequest() throws Exception {
        // Two downloads one minute before and one minute after midnight BST, through ONE service.
        Clock ticking = new SequenceClock(
                Instant.parse("2026-10-03T22:59:00Z"),   // 23:59 BST, 3 October
                Instant.parse("2026-10-03T23:01:00Z"));  // 00:01 BST, 4 October
        when(productRepository.findById(productId)).thenReturn(Optional.of(milkProduct(null)));
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(compliantShop()));
        ProductLabelService service = serviceAt(ticking);

        String before = extractText(service.generateLabel(productId));
        String after = extractText(service.generateLabel(productId));

        assertThat(before).contains("Produced: 3 October 2026");
        assertThat(after).contains("Produced: 4 October 2026");
    }

    /** A clock that returns the given instants in turn (the last one repeats), zone UTC. */
    private static final class SequenceClock extends Clock {
        private final Instant[] instants;
        private int next;

        SequenceClock(Instant... instants) {
            this.instants = instants;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            SequenceClock outer = this;
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return zone; }
                @Override public Clock withZone(java.time.ZoneId z) { return outer.withZone(z); }
                @Override public Instant instant() { return outer.instant(); }
            };
        }

        @Override
        public Instant instant() {
            Instant i = instants[Math.min(next, instants.length - 1)];
            next++;
            return i;
        }
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

    private static int pageCount(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

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
