package uk.jtoye.core.onboarding.gate;

import org.junit.jupiter.api.Test;
import uk.jtoye.core.onboarding.GateStatus;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AllergenCompletenessGate} — the ALLERGEN_DATA_COMPLETE
 * gate. Uses a mocked {@link ProductRepository} and builds fixtures directly from
 * the V41 {@link Product} setters (durabilityType / shelfLifeDays / ingredientsText).
 *
 * <p>The completeness predicate mirrors {@code ProductLabelService.validatePpdsData}
 * (the authoritative PPDS label code): a product is complete when it has a
 * durability type, a shelf life, and a present ingredients list (from which the
 * label re-parses the emphasised allergen runs). An allergen-free product with an
 * empty span set is compliant — the gate never invents a "must declare an allergen"
 * rule the label code does not enforce.
 */
class AllergenCompletenessGateTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final AllergenCompletenessGate gate = new AllergenCompletenessGate(productRepository);

    @Test
    void gateMetadata_isAutomaticMandatoryAllergenType() {
        assertThat(gate.type()).isEqualTo(GateType.ALLERGEN_DATA_COMPLETE);
        assertThat(gate.isAutomatic()).isTrue();
        assertThat(gate.mandatory()).isTrue();
    }

    @Test
    void allProductsComplete_passesWithProductsCheckedEvidence() {
        UUID shopId = UUID.randomUUID();
        VendorOnboarding onboarding = onboardingForShop(shopId);
        when(productRepository.findByShopId(shopId))
                .thenReturn(List.of(completeProduct("SKU-1"), completeProduct("SKU-2")));

        GateResult result = gate.evaluate(onboarding);

        assertThat(result.status()).isEqualTo(GateStatus.PASSED);
        assertThat(result.evidence()).containsEntry("products_checked", 2);
    }

    @Test
    void oneProductMissingDurabilityType_failsNamingTheOffender() {
        UUID shopId = UUID.randomUUID();
        VendorOnboarding onboarding = onboardingForShop(shopId);
        Product offender = completeProduct("SKU-NO-DURABILITY");
        offender.setDurabilityType(null);
        when(productRepository.findByShopId(shopId))
                .thenReturn(List.of(completeProduct("SKU-OK"), offender));

        GateResult result = gate.evaluate(onboarding);

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).contains("SKU-NO-DURABILITY").doesNotContain("SKU-OK");
    }

    @Test
    void oneProductMissingShelfLife_fails() {
        UUID shopId = UUID.randomUUID();
        VendorOnboarding onboarding = onboardingForShop(shopId);
        Product offender = completeProduct("SKU-NO-SHELF");
        offender.setShelfLifeDays(null);
        when(productRepository.findByShopId(shopId)).thenReturn(List.of(offender));

        GateResult result = gate.evaluate(onboarding);

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).contains("SKU-NO-SHELF");
    }

    @Test
    void oneProductWithNoDerivableAllergenData_fails() {
        // A blank ingredients list means the label cannot derive/print the
        // emphasised allergen runs — i.e. the allergen spans are not derivable.
        UUID shopId = UUID.randomUUID();
        VendorOnboarding onboarding = onboardingForShop(shopId);
        Product offender = completeProduct("SKU-NO-INGREDIENTS");
        offender.setIngredientsText("   ");
        when(productRepository.findByShopId(shopId)).thenReturn(List.of(offender));

        GateResult result = gate.evaluate(onboarding);

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).contains("SKU-NO-INGREDIENTS");
    }

    @Test
    void emptyShop_failsBecauseThereIsNothingToPublish() {
        UUID shopId = UUID.randomUUID();
        VendorOnboarding onboarding = onboardingForShop(shopId);
        when(productRepository.findByShopId(shopId)).thenReturn(List.of());

        GateResult result = gate.evaluate(onboarding);

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void noShopAttached_fails() {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setShopId(null);

        GateResult result = gate.evaluate(onboarding);

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).isNotBlank();
    }

    private VendorOnboarding onboardingForShop(UUID shopId) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setShopId(shopId);
        return onboarding;
    }

    /** A fully allergen-complete product: durability type + shelf life + ingredients. */
    private Product completeProduct(String sku) {
        Product product = new Product();
        product.setSku(sku);
        product.setTitle("Title " + sku);
        product.setIngredientsText("Wheat flour, **milk**, sugar");
        product.setShelfLifeDays(3);
        product.setDurabilityType("USE_BY");
        return product;
    }
}
