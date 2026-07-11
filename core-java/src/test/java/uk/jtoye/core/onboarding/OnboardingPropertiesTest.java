package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast unit test (no Spring context) for {@link OnboardingProperties} Java-level
 * defaults and the redacted {@code toString()}. The design contract
 * (VENDOR_ONBOARDING_STATE_MODEL.md §6) fixes FHRS {@code min-rating = 2} and
 * {@code api-version = "2"} as the code defaults so a missing yaml/env value can
 * never silently pick a stricter or looser threshold, and the Companies House
 * API key must never leak through an accidental {@code log.info("{}", props)}.
 *
 * <p>Also pins the five enum vocabularies to the exact cardinality the V43 CHECK
 * constraints encode — a drift between an enum constant and the CHECK string
 * would fail at INSERT time under {@code @Enumerated(EnumType.STRING)}.
 */
class OnboardingPropertiesTest {

    @Test
    void javaDefaultsMatchDesignContract() {
        OnboardingProperties props = new OnboardingProperties();

        assertThat(props.isAutoApprove()).isFalse();
        assertThat(props.getMenuMinimum()).isEqualTo(1);
        assertThat(props.getFhrs().getMinRating()).isEqualTo(2);
        assertThat(props.getFhrs().getApiVersion()).isEqualTo("2");
        // Secret defaults empty (never null) — house convention mirrors StripeProperties.
        assertThat(props.getCompaniesHouse().getApiKey()).isEmpty();
    }

    @Test
    @DisplayName("autoApproveModels defaults to [WHITE_LABEL] and the helper is model-scoped (#178)")
    void autoApproveModelsDefaultsToWhiteLabelOnly() {
        OnboardingProperties props = new OnboardingProperties();

        // Default per-model policy: WHITE_LABEL auto-approves, MARKETPLACE stays manual.
        assertThat(props.getAutoApproveModels()).containsExactly(OnboardingModel.WHITE_LABEL);
        assertThat(props.autoApprovesModel(OnboardingModel.WHITE_LABEL)).isTrue();
        assertThat(props.autoApprovesModel(OnboardingModel.MARKETPLACE)).isFalse();
        // Null-safe: a model-less onboarding never auto-approves via the per-model path.
        assertThat(props.autoApprovesModel(null)).isFalse();
    }

    @Test
    void toStringMasksCompaniesHouseApiKey() {
        OnboardingProperties props = new OnboardingProperties();
        props.getCompaniesHouse().setApiKey("live_ch_secret_should_never_appear");

        String rendered = props.toString();
        assertThat(rendered).doesNotContain("live_ch_secret_should_never_appear");
        assertThat(rendered).contains("***");
    }

    @Test
    void toStringShowsUnsetForBlankApiKey() {
        OnboardingProperties props = new OnboardingProperties();
        assertThat(props.toString()).contains("<unset>");
    }

    @Test
    void enumVocabulariesMatchV43CheckCardinality() {
        assertThat(OnboardingState.values()).hasSize(9);
        assertThat(OnboardingEvent.values()).hasSize(10);
        assertThat(OnboardingModel.values()).hasSize(2);
        assertThat(GateType.values()).hasSize(8);
        assertThat(GateStatus.values()).hasSize(5);

        // Spot-check the constant NAMES the V43 CHECK strings + @Enumerated(STRING) rely on.
        assertThat(OnboardingState.DRAFT.name()).isEqualTo("DRAFT");
        assertThat(GateType.FOOD_HYGIENE_RATING.name()).isEqualTo("FOOD_HYGIENE_RATING");
        assertThat(GateStatus.MANUAL_REVIEW.name()).isEqualTo("MANUAL_REVIEW");
    }
}
