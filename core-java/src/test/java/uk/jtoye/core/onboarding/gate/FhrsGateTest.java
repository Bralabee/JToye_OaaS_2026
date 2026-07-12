package uk.jtoye.core.onboarding.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateStatus;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.OnboardingProperties;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.onboarding.client.FhrsEstablishment;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit proof of the {@link FhrsGate} rating-to-status mapping: a
 * mocked {@link FhrsClient} feeds canned establishment lists, a stubbed
 * {@link ShopRepository} returns a seeded shop, and {@link OnboardingProperties}
 * supplies the config-driven {@code min-rating}. Covers the six mapping cases plus
 * the config-driven threshold (raising min-rating flips a mid-rating establishment
 * to FAILED — proving no hardcoded literal).
 */
class FhrsGateTest {

    private final FhrsClient fhrsClient = mock(FhrsClient.class);
    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final OnboardingProperties properties = new OnboardingProperties();

    private final UUID shopId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    private FhrsGate gate() {
        return new FhrsGate(fhrsClient, properties, shopRepository);
    }

    private VendorOnboarding onboardingWithShop() {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        return onboarding;
    }

    // CR-02: the gate now resolves the shop with an explicit tenant filter, so stub
    // the tenant-scoped finder (not the RLS-only findById).
    private void seedShop() {
        Shop shop = new Shop();
        shop.setName("Mama Put Kitchen");
        shop.setAddress("12 High Street, London");
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));
    }

    private FhrsEstablishment est(String id, String rating, String scheme) {
        return new FhrsEstablishment(id, rating, scheme);
    }

    @Test
    @DisplayName("gate identity: FOOD_HYGIENE_RATING, automatic, mandatory")
    void identity() {
        FhrsGate g = gate();
        assertThat(g.type()).isEqualTo(GateType.FOOD_HYGIENE_RATING);
        assertThat(g.isAutomatic()).isTrue();
        assertThat(g.mandatory(OnboardingModel.MARKETPLACE)).isTrue();
        assertThat(g.mandatory(OnboardingModel.WHITE_LABEL)).isTrue();
    }

    @Test
    @DisplayName("single FHRS rating >= min-rating -> PASSED with evidence + externalRef")
    void ratingAtOrAboveThresholdPasses() {
        seedShop();
        when(fhrsClient.lookup(any(), any())).thenReturn(List.of(est("774297", "5", "FHRS")));

        GateResult result = gate().evaluate(onboardingWithShop());

        assertThat(result.status()).isEqualTo(GateStatus.PASSED);
        assertThat(result.externalRef()).isEqualTo("774297");
        assertThat(result.evidence())
                .containsEntry("fhrs_rating", 5)
                .containsEntry("establishment_id", "774297")
                .containsEntry("scheme", "FHRS");
    }

    @Test
    @DisplayName("single FHRS rating below min-rating -> FAILED naming the rating + threshold")
    void ratingBelowThresholdFails() {
        seedShop();
        when(fhrsClient.lookup(any(), any())).thenReturn(List.of(est("100", "1", "FHRS")));

        GateResult result = gate().evaluate(onboardingWithShop());

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).contains("1").contains("2");
    }

    @Test
    @DisplayName("single Scotland FHIS Pass -> PASSED (scheme FHIS)")
    void fhisPassPasses() {
        seedShop();
        when(fhrsClient.lookup(any(), any())).thenReturn(List.of(est("55123", "Pass", "FHIS")));

        GateResult result = gate().evaluate(onboardingWithShop());

        assertThat(result.status()).isEqualTo(GateStatus.PASSED);
        assertThat(result.externalRef()).isEqualTo("55123");
        assertThat(result.evidence())
                .containsEntry("fhrs_rating", "Pass")
                .containsEntry("scheme", "FHIS");
    }

    @Test
    @DisplayName("no match -> MANUAL_REVIEW (never FAILED)")
    void noMatchManualReview() {
        seedShop();
        when(fhrsClient.lookup(any(), any())).thenReturn(List.of());

        assertThat(gate().evaluate(onboardingWithShop()).status()).isEqualTo(GateStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("ambiguous multi-match -> MANUAL_REVIEW (never FAILED)")
    void multiMatchManualReview() {
        seedShop();
        when(fhrsClient.lookup(any(), any()))
                .thenReturn(List.of(est("1", "5", "FHRS"), est("2", "4", "FHRS")));

        assertThat(gate().evaluate(onboardingWithShop()).status()).isEqualTo(GateStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("client failure (5xx / open circuit) -> MANUAL_REVIEW, never a silent pass")
    void clientFailureManualReview() {
        seedShop();
        when(fhrsClient.lookup(any(), any())).thenThrow(new RuntimeException("circuit open"));

        assertThat(gate().evaluate(onboardingWithShop()).status()).isEqualTo(GateStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("min-rating is config-driven: raising it to 4 fails a rating-3 establishment")
    void thresholdIsConfigDriven() {
        seedShop();
        properties.getFhrs().setMinRating(4);
        when(fhrsClient.lookup(any(), any())).thenReturn(List.of(est("3", "3", "FHRS")));

        assertThat(gate().evaluate(onboardingWithShop()).status()).isEqualTo(GateStatus.FAILED);
    }
}
