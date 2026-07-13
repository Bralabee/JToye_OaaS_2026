package uk.jtoye.core.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Combined authority converter that emits BOTH Keycloak realm-role authorities
 * ({@code ROLE_*}, issue #83 P1-1) AND OAuth2 scope authorities ({@code SCOPE_*},
 * issue #206 [AI-4] scoped machine credentials).
 *
 * <p>This class is strictly <strong>additive</strong> to #83. The resource-server
 * default converter ({@link JwtGrantedAuthoritiesConverter}) maps the space-delimited
 * {@code scope} claim (then {@code scp}) to {@code SCOPE_*} authorities, but #83
 * <em>replaced</em> that default with {@link KeycloakRealmRoleConverter}, so no
 * {@code SCOPE_*} authority is produced by the bare role converter. This class composes
 * both delegates and returns the union, restoring scope mapping while preserving every
 * {@code hasRole('admin')} gate.
 *
 * <p><strong>Load-bearing:</strong> removing the {@link KeycloakRealmRoleConverter}
 * delegate would drop every {@code ROLE_*} authority and silently break the six
 * {@code @PreAuthorize("hasRole('admin')")} gates (refunds, finance, GDPR, tenant-admin,
 * onboarding-admin, dev-admin). Removing the {@link JwtGrantedAuthoritiesConverter}
 * delegate would drop {@code SCOPE_catalog:write} and re-open the product write surface
 * to any authenticated tenant token. Both delegates are required.
 *
 * <p>Both delegates return an empty (never {@code null}) collection when their claim is
 * absent, so a token carrying only roles, only scopes, or neither is handled without any
 * extra null guarding.
 *
 * <p>Plain public final class (no Spring stereotype) so it can be instantiated directly in
 * unit and integration tests, and by {@link SecurityConfig} when wiring the resource server.
 */
public final class JwtRolesAndScopesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    // #83 realm_access.roles -> ROLE_*  (reused verbatim; do NOT modify that class)
    private final KeycloakRealmRoleConverter roles = new KeycloakRealmRoleConverter();
    // Stock Spring converter: scope (space-delimited) then scp -> SCOPE_*
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        LinkedHashSet<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.addAll(roles.convert(jwt));   // ROLE_admin, ROLE_user, ...
        authorities.addAll(scopes.convert(jwt));  // SCOPE_catalog:read, SCOPE_catalog:write, ...
        return authorities;
    }
}
