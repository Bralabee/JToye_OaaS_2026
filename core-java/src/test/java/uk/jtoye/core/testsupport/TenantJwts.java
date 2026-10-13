package uk.jtoye.core.testsupport;

import org.springframework.test.web.servlet.request.RequestPostProcessor;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Production-shaped MockMvc principals for tenant-scoped, shop-access-gated endpoints
 * (QA-remediate 20260902 SEC-1).
 *
 * <p><strong>Why not {@code @WithMockUser}.</strong> That annotation installs a
 * {@code UsernamePasswordAuthenticationToken} whose principal is not a {@code Jwt}.
 * {@code ShopAccessService.requireVendorUserId()} is fail-closed (CR-03 / D-04): a
 * request principal that is not a {@code Jwt} with a UUID {@code sub} is DENIED with the
 * typed shop-access 403. Every endpoint that sits behind {@code requireGroupAdmin()} /
 * {@code require(shopId, role)} therefore returns 403 to a {@code @WithMockUser} test,
 * which is not a bug in the gate — it is the gate working. {@code SecurityHeadersIntegrationTest}
 * records the first time this regression fired; the webhook control plane was the second.
 *
 * <p>The shape mirrors {@code RoleBasedAccessIntegrationTest.adminJwt()}: a {@code tenant_id}
 * claim (which {@code JwtTenantFilter} prefers over the {@code X-Tenant-Id} header) and a
 * {@code realm_access.roles} claim mapped through the REAL {@link KeycloakRealmRoleConverter},
 * so {@code admin -> ROLE_admin} is the same mapping production uses — plus a UUID
 * {@code subject}, because that is what a Keycloak vendor user carries and what the
 * shop-staff model keys on.
 */
public final class TenantJwts {

    /**
     * 37-45 (Q7): the {@code azp} of a token the dashboard obtained, i.e. the NextAuth
     * {@code KEYCLOAK_CLIENT_ID} ({@code core-api} in every repository runtime) and the default of
     * {@code jtoye.access.human-client-ids}. Every person-shaped token here carries it, so a test keeps its
     * meaning under the human-only rule on credential and staff management.
     */
    public static final String DASHBOARD_CLIENT_ID = "core-api";

    private TenantJwts() {
    }

    /**
     * A realm-admin vendor token for {@code tenantId}: UUID {@code sub}, {@code tenant_id}
     * claim, realm role {@code admin} → {@code ROLE_admin} (the D-03 bridge, an implicit
     * tenant-wide GROUP_ADMIN with no {@code shop_staff} row and no JIT provision).
     */
    public static RequestPostProcessor adminJwt(UUID tenantId) {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("azp", DASHBOARD_CLIENT_ID)
                        .claim("email", "operator@example.com")
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /**
     * An ordinary vendor user {@code sub} in {@code tenantId}: realm role {@code user} ONLY,
     * so the realm-admin bridge never fires and the caller's access is exactly what its
     * {@code shop_staff} rows say it is. Under the shipped default (D-06, strict scoping ON) there
     * is no implicit admin: a {@code sub} with no rows has no shop access at all, so a test that
     * needs access seeds it with {@link ShopGrants#grantOperator}.
     */
    public static RequestPostProcessor vendorJwt(UUID sub, UUID tenantId) {
        return jwt().jwt(j -> j
                        .subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("azp", DASHBOARD_CLIENT_ID)
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /**
     * 37-45 (D-22, D-23): a token minted by an integrator credential's Keycloak service-account client, in
     * the shape 37-44's client produces: {@code sub} = the service-account user (the id its {@code shop_staff}
     * grant is keyed on), {@code azp} = the credential's clientId, a hard-coded {@code tenant_id}, and the
     * granted scopes in {@code scope}. It carries NO {@code realm_access}, {@code email} or
     * {@code preferred_username}: the credential client has no roles, profile or email scope (37-44), and
     * core must accept the token without them. Authorities come from the production
     * {@link JwtRolesAndScopesConverter}, so {@code scope} becomes {@code SCOPE_*} exactly as it does live.
     */
    public static RequestPostProcessor serviceAccountJwt(UUID serviceAccountUserId, UUID tenantId, String clientId,
                                                         String... scopes) {
        return jwt().jwt(j -> j
                        .subject(serviceAccountUserId.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("azp", clientId)
                        .claim("scope", String.join(" ", scopes)))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /**
     * A realm-admin token carrying NO tenant claim, for arms that must reach a controller
     * with no tenant established ({@code JwtTenantFilter} leaves {@code TenantContext}
     * unset, and with no {@code X-Tenant-Id} header nothing else sets it).
     */
    public static RequestPostProcessor tenantlessAdminJwt() {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("azp", DASHBOARD_CLIENT_ID)
                        .claim("email", "operator@example.com")
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
