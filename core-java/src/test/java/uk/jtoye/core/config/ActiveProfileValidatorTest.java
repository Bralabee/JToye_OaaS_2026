package uk.jtoye.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain-JUnit coverage of the pure {@link ActiveProfileValidator#validate(String[])}
 * rule — no Spring context, calling the package-visible method directly with plain
 * arrays. Encodes the Issue #78 [P0-2] contract: exact, case-sensitive membership
 * of the known profile set, with a loud failure naming both the offending profile
 * and the valid set.
 */
class ActiveProfileValidatorTest {

    @Test
    void singleValidProfileIsAccepted() {
        assertThatCode(() -> ActiveProfileValidator.validate(new String[]{"prod"}))
                .doesNotThrowAnyException();
    }

    @Test
    void validComboProfilesAreAccepted() {
        assertThatCode(() -> ActiveProfileValidator.validate(new String[]{"prod", "test"}))
                .doesNotThrowAnyException();
        assertThatCode(() -> ActiveProfileValidator.validate(new String[]{"dev", "test"}))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyDefaultProfileIsAccepted() {
        assertThatCode(() -> ActiveProfileValidator.validate(new String[]{}))
                .doesNotThrowAnyException();
        assertThatCode(() -> ActiveProfileValidator.validate(null))
                .doesNotThrowAnyException();
    }

    @Test
    void productionTypoIsRejectedWithActionableMessage() {
        assertThatThrownBy(() -> ActiveProfileValidator.validate(new String[]{"production"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("local")
                .hasMessageContaining("dev")
                .hasMessageContaining("test")
                .hasMessageContaining("staging")
                .hasMessageContaining("prod")
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    @Test
    void caseVariantIsRejectedProvingCaseSensitivity() {
        assertThatThrownBy(() -> ActiveProfileValidator.validate(new String[]{"Prod"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Prod");
        assertThatThrownBy(() -> ActiveProfileValidator.validate(new String[]{"PRODUCTION"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRODUCTION");
    }

    @Test
    void unknownProfileInAnOtherwiseValidComboIsRejected() {
        assertThatThrownBy(() -> ActiveProfileValidator.validate(new String[]{"prod", "bogus"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bogus");
    }
}
