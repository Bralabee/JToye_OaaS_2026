package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the #87 split-horizon issuer bug (fixed in PR #134).
 *
 * <p>Issue #87 added issuer validation but wired the expected {@code iss} claim
 * and the JWKS fetch URL from a single value (the INTERNAL {@code KC_ISSUER_URI},
 * e.g. {@code http://keycloak:8080/...}). Keycloak stamps tokens with its PUBLIC
 * frontend issuer ({@code http://localhost:8085/...}); in the containerised
 * topology these differ, so every real token was rejected — a total live-auth
 * outage that unit + Testcontainers tests missed (there the JWKS host and the
 * advertised issuer are the same container).
 *
 * <p>These tests build the REAL {@link SecurityConfig#jwtDecoder} with the two
 * hosts deliberately split, extract the composite validator the decoder installs,
 * and assert that the token {@code iss} is validated against the PUBLIC
 * {@code expectedIssuer} — NOT the internal JWKS host. The first test would FAIL
 * against the pre-fix wiring ({@code createDefaultWithIssuer(issuerUri)}) and
 * PASSES with the fix ({@code createDefaultWithIssuer(expectedIssuer)}).
 *
 * <p>Plain JUnit (no Spring context, no {@code jwt()} post-processor which would
 * bypass the decoder), mirroring {@link AudienceValidatorTest}. {@code build()}
 * on {@code withJwkSetUri} is lazy, so no network call is made.
 */
class JwtIssuerDecouplingTest {

    private static final String JWKS_HOST = "http://keycloak:8080/realms/jtoye-dev";      // internal, reachable for JWKS
    private static final String PUBLIC_ISSUER = "http://localhost:8085/realms/jtoye-dev"; // what Keycloak stamps on 'iss'
    private static final String AUDIENCE = "core-api";

    /** The composite validator installed by the production decoder, with the two hosts split. */
    @SuppressWarnings("unchecked")
    private static OAuth2TokenValidator<Jwt> decoderValidator() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "issuerUri", JWKS_HOST);         // JWKS fetched from here
        ReflectionTestUtils.setField(config, "expectedIssuer", PUBLIC_ISSUER); // 'iss' validated against here
        ReflectionTestUtils.setField(config, "expectedAudience", AUDIENCE);
        JwtDecoder decoder = config.jwtDecoder(new RestTemplateBuilder());
        return (OAuth2TokenValidator<Jwt>) ReflectionTestUtils.getField(decoder, "jwtValidator");
    }

    private static Jwt.Builder tokenWithIssuer(String issuer) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(issuer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("aud", List.of(AUDIENCE));
    }

    @Test
    void acceptsPublicIssuerTokenEvenThoughJwksHostDiffers() {
        // The regression case: token carries the PUBLIC issuer, JWKS host is internal.
        // Pre-fix (iss validated against the internal JWKS host) this was rejected.
        Jwt jwt = tokenWithIssuer(PUBLIC_ISSUER).build();

        assertThat(decoderValidator().validate(jwt).hasErrors())
                .as("token minted by Keycloak (public iss) must be accepted despite the internal JWKS host")
                .isFalse();
    }

    @Test
    void rejectsTokenWhoseIssuerIsTheInternalJwksHost() {
        // Proves 'iss' is validated against the PUBLIC issuer, not the JWKS host —
        // no real Keycloak token ever carries the internal host as its issuer.
        Jwt jwt = tokenWithIssuer(JWKS_HOST).build();

        assertThat(decoderValidator().validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rejectsUnrelatedIssuer() {
        Jwt jwt = tokenWithIssuer("http://evil.example.com/realms/jtoye-dev").build();

        assertThat(decoderValidator().validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void stillEnforcesAudienceAfterDecoupling() {
        // Audience validation remains additive after the issuer decoupling:
        // correct (public) issuer but wrong audience is still rejected.
        Jwt jwt = tokenWithIssuer(PUBLIC_ISSUER).claim("aud", List.of("someone-else")).build();

        assertThat(decoderValidator().validate(jwt).hasErrors()).isTrue();
    }
}
