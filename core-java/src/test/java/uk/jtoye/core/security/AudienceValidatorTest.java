package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decoder-level unit tests for {@link AudienceValidator} (issue #87 P1-5,
 * threat T-bl2-01). Exercises the exact validator the {@code NimbusJwtDecoder}
 * runs — a plain class instantiated directly with NO Spring context and NO
 * {@code jwt()} post-processor (which would bypass the decoder entirely).
 */
class AudienceValidatorTest {

    private static final String EXPECTED = "core-api";
    private final AudienceValidator validator = new AudienceValidator(EXPECTED);

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8085/realms/jtoye-dev")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    @Test
    void acceptsWhenAudienceIsExactlyExpected() {
        Jwt jwt = baseJwt().claim("aud", "core-api").build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void acceptsWhenAudienceListContainsExpected() {
        Jwt jwt = baseJwt().claim("aud", List.of("account", "core-api", "edge-api")).build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rejectsWhenAudienceIsDifferentSingleValue() {
        Jwt jwt = baseJwt().claim("aud", "someone-else").build();

        var result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .anySatisfy(e -> assertThat(e.getErrorCode()).isEqualTo("invalid_token"));
    }

    @Test
    void rejectsWhenAudienceClaimAbsent() {
        Jwt jwt = baseJwt().build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rejectsBlankExpectedAudienceAtConstruction() {
        assertThatThrownBy(() -> new AudienceValidator("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
