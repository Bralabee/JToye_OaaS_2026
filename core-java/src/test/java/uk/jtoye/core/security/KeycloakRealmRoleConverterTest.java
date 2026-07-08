package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KeycloakRealmRoleConverter} (issue #83 P1-1, threat T-rlp-04).
 *
 * <p>Proves the realm_access.roles -> ROLE_* mapping is correct and null-safe with NO Spring
 * context — the converter is a plain class instantiated directly. A wrong prefix or a null
 * dereference here would either silently grant nothing (over-block) or, worse, throw and be
 * swallowed as a 500 that masks the gate; both are asserted against.
 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    @Test
    void mapsRealmRolesToPrefixedAuthorities() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("admin", "user")))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_user");
    }

    @Test
    void returnsEmptyWhenRealmAccessClaimAbsent() {
        Jwt jwt = baseJwt().build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void returnsEmptyWhenRealmAccessHasNoRolesList() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("notRoles", "irrelevant"))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }
}
