package uk.jtoye.core.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Rejects any JWT whose {@code aud} claim does not contain the configured
 * expected audience (issue #87 P1-5, threat T-bl2-01). Runs as an ADDITIVE
 * validator inside the resource server's {@code DelegatingOAuth2TokenValidator},
 * alongside — never replacing — the issuer + timestamp defaults and the #83
 * {@code KeycloakRealmRoleConverter} authority mapping.
 *
 * <p>Without this check a token minted for any other client in the same
 * Keycloak realm (token confusion) would be accepted by this resource server.
 * A blank expected audience is treated as a configuration error at
 * construction so enforcement can never silently degrade to a no-op.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            throw new IllegalArgumentException(
                    "Expected audience must be configured (jtoye.security.jwt.expected-audience); "
                            + "a blank value would silently disable JWT audience enforcement");
        }
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audiences = jwt.getAudience();
        if (audiences != null && audiences.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Required audience " + expectedAudience + " is missing",
                null));
    }
}
