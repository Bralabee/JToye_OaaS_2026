package uk.jtoye.core.tenant.keycloak;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 37-44 (D-22, RWO-035): what {@link KeycloakAdminClient#createClient} needs to mint one integrator
 * credential as a Keycloak service-account client in the vendor realm.
 *
 * <ul>
 *   <li>{@code clientId}: the realm-unique Keycloak clientId. Generated server-side by the credential
 *       service (37-45); a tenant never chooses it, because a chosen clientId is a collision and
 *       impersonation vector.</li>
 *   <li>{@code tenantId}: the ISSUING tenant. It becomes a hard-coded {@code tenant_id} claim in every
 *       access token the client mints, so the credential can only ever act for this tenant
 *       (T-37-104).</li>
 *   <li>{@code scopes}: the realm client-scope NAMES the credential receives as default scopes (the
 *       #206 scopes in {@code docs/security-scopes.md}). The allow-list is the caller's job; this
 *       record only refuses an empty or blank entry.</li>
 * </ul>
 */
public record KeycloakClientSpec(String clientId, UUID tenantId, List<String> scopes) {

    public KeycloakClientSpec {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scopes, "scopes");
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("a credential needs at least one scope");
        }
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("a scope name must not be blank");
            }
        }
        scopes = List.copyOf(scopes);
    }
}
