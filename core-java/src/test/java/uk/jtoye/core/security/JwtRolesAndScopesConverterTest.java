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
 * Unit tests for {@link JwtRolesAndScopesConverter} (issue #206 [AI-4], threats
 * T-2g8-01/T-2g8-04).
 *
 * <p>Proves the combined converter emits BOTH families of authorities with NO Spring
 * context — the converter is a plain class instantiated directly, exactly as production
 * wires it and as the MockMvc {@code jwt().authorities(...)} seam supplies it. The two
 * load-bearing invariants asserted here are:
 * <ol>
 *   <li>the #83 {@code realm_access.roles -> ROLE_*} mapping is preserved (dropping it
 *       would silently break every {@code hasRole('admin')} gate), and</li>
 *   <li>the stock {@code scope -> SCOPE_*} mapping is restored (the bare #83 converter
 *       threw it away, so {@code hasAuthority('SCOPE_catalog:write')} would never match).</li>
 * </ol>
 * Neither claim's absence may throw — a scopeless/roleless token must degrade to an empty
 * authority set, mirroring #83's defensive parity.
 */
class JwtRolesAndScopesConverterTest {

    private final JwtRolesAndScopesConverter converter = new JwtRolesAndScopesConverter();

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    @Test
    void mapsRealmRolesToRolePrefixedAuthoritiesWithNoScopes() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_admin")
                .noneMatch(a -> a.startsWith("SCOPE_"));
    }

    @Test
    void mapsSpaceDelimitedScopeClaimToScopePrefixedAuthoritiesWithNoRoles() {
        Jwt jwt = baseJwt()
                .claim("scope", "catalog:read catalog:write")
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_catalog:read", "SCOPE_catalog:write")
                .noneMatch(a -> a.startsWith("ROLE_"));
    }

    @Test
    void mergesRolesAndScopesWhenBothClaimsPresent() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .claim("scope", "catalog:read")
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        // Proves neither authority family is dropped when the other is present.
        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_admin", "SCOPE_catalog:read");
    }

    @Test
    void returnsEmptyAndNeverThrowsWhenNeitherClaimPresent() {
        Jwt jwt = baseJwt().build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }
}
