package uk.jtoye.core.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps Keycloak realm roles into Spring Security {@code ROLE_*} authorities (issue #83, P1-1).
 *
 * <p>Keycloak mints realm roles into the JWT under the {@code realm_access} claim:
 * <pre>{@code { "realm_access": { "roles": ["admin", "user"] } }}</pre>
 * Each role is mapped to {@code new SimpleGrantedAuthority("ROLE_" + role)} preserving the
 * literal (lowercase) role name, so {@code admin -> ROLE_admin}. Spring's
 * {@code hasRole('admin')} then checks for authority {@code ROLE_admin}.
 *
 * <p>Defensive by design: returns an empty collection (never {@code null}) when
 * {@code realm_access} is absent, is not a {@code Map}, or carries no {@code roles} list.
 * Tokens minted by other clients may omit the claim entirely — such a token simply carries
 * no roles and is therefore denied at every {@code hasRole('admin')} gate, never accidentally
 * granted.
 *
 * <p>Plain public class (no Spring stereotype) so it can be instantiated directly in unit and
 * integration tests, and by {@link SecurityConfig} when wiring the resource server.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return List.of();
        }
        Object roles = realmAccessMap.get(ROLES_KEY);
        if (!(roles instanceof Collection<?> roleCollection)) {
            return List.of();
        }
        return roleCollection.stream()
                .filter(role -> role instanceof String s && !s.isBlank())
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toUnmodifiableList());
    }
}
