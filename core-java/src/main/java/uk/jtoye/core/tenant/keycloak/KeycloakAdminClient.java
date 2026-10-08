package uk.jtoye.core.tenant.keycloak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Low-level seam over the Keycloak admin REST API (issue #102 remainder). Core
 * is a pure OAuth2 resource server today, so this is the FIRST admin caller from
 * the Java side (the only existing admin caller is the one-shot
 * {@code infra/keycloak/configure-keycloak.sh}, deliberately untouched).
 *
 * <p>Eight operations, mapped to the Keycloak 24 admin REST shape (two added by 31.1-11 for DSAR
 * customer-account deletion, D-03, and two by 37-08 for staff invitations, D-07):
 * <ul>
 *   <li>{@link #obtainAdminToken()} — master-realm {@code admin-cli} password grant.</li>
 *   <li>{@link #searchUsersByTenant} — paginated user search by the
 *       {@code tenant_id} attribute (page size 100).</li>
 *   <li>{@link #setUserEnabled} — PUT the full user representation back with
 *       {@code enabled} flipped (Keycloak requires the whole rep on update).</li>
 *   <li>{@link #logoutUser} — revoke the user's active sessions.</li>
 *   <li>{@link #findUsersByEmail} — exact-email user search in one named realm.</li>
 *   <li>{@link #deleteUser} — delete one user; 404 reports "already gone", not an error.</li>
 *   <li>{@link #findVendorUsersByEmail} — 37-08: the exact-email search in the vendor realm, with each
 *       user's {@code tenant_id} attribute.</li>
 *   <li>{@link #createUser} — 37-08: create an invited person with the password they chose.</li>
 * </ul>
 *
 * <p><b>Security (STRIDE T-kc-01):</b> the bearer token and admin password are
 * NEVER logged. Non-2xx / transport failures are wrapped into
 * {@link KeycloakAdminException} carrying realm/operation context only — the
 * client never maps to an HTTP status or swallows a failure; the service layer
 * owns the best-effort availability decision.
 *
 * <p><b>Jackson 3 throughout (38-07, BOOT4-05):</b> the user representations are
 * {@code tools.jackson} nodes because Boot 4's RestClient writes request bodies with
 * the Jackson-3 converter. A Jackson-2 {@code ObjectNode} handed to that converter is
 * not a tree to it, so it was serialized as a bean
 * ({@code {"array":false,...,"nodeType":"OBJECT",...}}): Keycloak received no
 * {@code enabled} field and the offboarded user stayed ENABLED. The by-content test
 * {@code KeycloakAdminClientTest#setUserEnabled_putsTheSearchedRepBack_withOnlyEnabledFlipped_byContent}
 * guards it. Parse failures are now the unchecked {@code JacksonException}; the
 * generic {@code Exception} catches below still turn them into the same
 * {@link KeycloakAdminException} messages.
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    /** Keycloak page size for the attribute search; a short final page ends pagination. */
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final KeycloakAdminProperties properties;
    private final JsonMapper jsonMapper;

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               KeycloakAdminProperties properties,
                               JsonMapper jsonMapper) {
        // baseUrl may be empty when the feature is inert — the client is simply
        // never called in that state (the service short-circuits on configured()).
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Master-realm password grant ({@code grant_type=password},
     * {@code client_id=admin-cli}). Returns the {@code access_token}; never logs it.
     */
    public String obtainAdminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", properties.getUsername());
        form.add("password", properties.getPassword());
        try {
            String body = restClient.post()
                    .uri("/realms/master/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode node = jsonMapper.readTree(body == null ? "{}" : body);
            JsonNode token = node.get("access_token");
            if (token == null || token.asString().isBlank()) {
                throw new KeycloakAdminException("Keycloak token response had no access_token");
            }
            return token.asString();
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak admin token request failed", e);
        } catch (Exception e) {
            throw new KeycloakAdminException("Keycloak admin token response could not be parsed", e);
        }
    }

    /**
     * Pages {@code GET /admin/realms/{realm}/users?q=tenant_id:{uuid}} at page
     * size 100 until a page shorter than the page size returns, concatenating all
     * user representations. A single 100-item page followed by a 5-item page
     * yields 105 users across two GET calls.
     */
    public List<ObjectNode> searchUsersByTenant(String realm, UUID tenantId, String token) {
        List<ObjectNode> users = new ArrayList<>();
        int first = 0;
        try {
            while (true) {
                String body = restClient.get()
                        .uri("/admin/realms/{realm}/users?q=tenant_id:{tid}&first={first}&max={max}",
                                realm, tenantId.toString(), first, PAGE_SIZE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(String.class);
                JsonNode page = jsonMapper.readTree(body == null ? "[]" : body);
                int pageCount = 0;
                if (page.isArray()) {
                    for (JsonNode user : page.values()) {
                        if (user instanceof ObjectNode on) {
                            users.add(on);
                        }
                        pageCount++;
                    }
                }
                if (pageCount < PAGE_SIZE) {
                    break;
                }
                first += PAGE_SIZE;
            }
            return users;
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Keycloak user search failed for realm=" + realm, e);
        } catch (Exception e) {
            throw new KeycloakAdminException(
                    "Keycloak user-search response could not be parsed for realm=" + realm, e);
        }
    }

    /**
     * PUTs the FULL user representation back with {@code enabled} set to the given
     * flag. Keycloak requires the whole rep on update, so callers pass the object
     * returned from {@link #searchUsersByTenant} — only the {@code enabled} field
     * is flipped here. Disabling an already-disabled user is a harmless no-op PUT.
     */
    public void setUserEnabled(String realm, ObjectNode userRep, boolean enabled, String token) {
        String userId = userRep.path("id").asString();
        ObjectNode payload = userRep.deepCopy();
        payload.put("enabled", enabled);
        try {
            restClient.put()
                    .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Keycloak user disable failed for realm=" + realm + " userId=" + userId, e);
        }
    }

    /**
     * 31.1-11 (D-03): {@code GET /admin/realms/{realm}/users?email={email}&exact=true}. Without
     * {@code exact=true} Keycloak matches the email as a SUBSTRING, so this would return every
     * account whose address merely contains the subject's; with it, only the complete address
     * matches. The address travels as a URI variable, so {@code @} and {@code +} are
     * percent-encoded (a raw {@code +} would be read back as a space). Callers still re-check each
     * returned user's email before acting on it.
     *
     * <p>The error message names the realm and never the address: the address is personal data.
     */
    public List<CustomerRealmUser> findUsersByEmail(String realm, String email, String token) {
        List<CustomerRealmUser> users = new ArrayList<>();
        for (ObjectNode on : searchByExactEmail(realm, email, token)) {
            users.add(new CustomerRealmUser(
                    text(on, "id"), text(on, "username"), text(on, "email"),
                    text(on, "firstName"), text(on, "lastName"),
                    on.hasNonNull("createdTimestamp") && on.get("createdTimestamp").isNumber()
                            ? on.get("createdTimestamp").longValue() : null));
        }
        return users;
    }

    /**
     * 37-08 (D-07): the same exact-email search as {@link #findUsersByEmail}, in the VENDOR realm, reading
     * each user's {@code tenant_id} attribute (its first value; {@code null} when absent). The
     * staff-invite accept flow uses it to tell NEW from EXISTS_HERE from OTHER_BUSINESS. Errors name the
     * realm only, never the address.
     */
    public List<VendorRealmUser> findVendorUsersByEmail(String realm, String email, String token) {
        List<VendorRealmUser> users = new ArrayList<>();
        for (ObjectNode on : searchByExactEmail(realm, email, token)) {
            JsonNode tenant = on.path("attributes").path("tenant_id");
            String tenantId = null;
            if (tenant.isArray() && !tenant.isEmpty() && !tenant.get(0).isNull()) {
                tenantId = tenant.get(0).asString();
            } else if (tenant.isString()) {
                tenantId = tenant.asString();
            }
            users.add(new VendorRealmUser(text(on, "id"), text(on, "email"), tenantId));
        }
        return users;
    }

    /** The exact-email GET both lookups share; see {@link #findUsersByEmail} for why exact=true. */
    private List<ObjectNode> searchByExactEmail(String realm, String email, String token) {
        try {
            String body = restClient.get()
                    .uri("/admin/realms/{realm}/users?email={email}&exact=true", realm, email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            JsonNode page = jsonMapper.readTree(body == null ? "[]" : body);
            List<ObjectNode> users = new ArrayList<>();
            if (page.isArray()) {
                for (JsonNode user : page.values()) {
                    if (user instanceof ObjectNode on) {
                        users.add(on);
                    }
                }
            }
            return users;
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak user email search failed for realm=" + realm, e);
        } catch (Exception e) {
            throw new KeycloakAdminException(
                    "Keycloak user email-search response could not be parsed for realm=" + realm, e);
        }
    }

    /**
     * 37-08 (D-07, D-26): {@code POST /admin/realms/{realm}/users} — create the invited person with the
     * password they chose on the accept page, and return the new user's id.
     *
     * <p>The representation is a Jackson-3 {@link ObjectNode} (the 38-07 lesson above): {@code username}
     * and {@code email} are the invited address, {@code enabled} and {@code emailVerified} are true (the
     * invite link proved the mailbox; without {@code emailVerified} the vendor realm refuses to mint a
     * token, "Account is not fully set up"), {@code attributes.tenant_id} is the inviting business (an
     * admin-only managed attribute in the vendor realm's user profile, so the person cannot change it),
     * and the one credential is a non-temporary password.
     *
     * <p>The id is the last segment of Keycloak's {@code Location} header; when there is none it is read
     * back with the exact-email search. {@code password} is zeroed in a {@code finally} on every path. A
     * 400 (Keycloak 24.0.5 sends {@code {"errorMessage":"Password policy not met"}}) becomes
     * {@link KeycloakUserRejectedException} carrying that message ONLY; a 409 becomes
     * {@link KeycloakUserExistsException}. No error text ever carries the request body, the address or the
     * password (T-37-22).
     */
    public String createUser(String realm, String email, String firstName, String lastName,
                             char[] password, UUID tenantId, String token) {
        ObjectNode rep = jsonMapper.createObjectNode();
        try {
            rep.put("username", email);
            rep.put("email", email);
            rep.put("firstName", firstName);
            rep.put("lastName", lastName);
            rep.put("enabled", true);
            rep.put("emailVerified", true);
            rep.putObject("attributes").putArray("tenant_id").add(tenantId.toString());
            ObjectNode credential = rep.putArray("credentials").addObject();
            credential.put("type", "password");
            credential.put("value", new String(password));
            credential.put("temporary", false);

            ResponseEntity<Void> created = restClient.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rep)
                    .retrieve()
                    .toBodilessEntity();
            String id = idFromLocation(created.getHeaders().getLocation());
            if (id != null) {
                return id;
            }
        } catch (HttpClientErrorException.BadRequest e) {
            throw new KeycloakUserRejectedException(realm, keycloakErrorMessage(e));
        } catch (HttpClientErrorException.Conflict e) {
            throw new KeycloakUserExistsException(realm);
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak user create failed for realm=" + realm, e);
        } finally {
            java.util.Arrays.fill(password, '\0');
            rep.removeAll();
        }
        // Created, but Keycloak named no Location: read the id back by the exact address.
        for (ObjectNode on : searchByExactEmail(realm, email, token)) {
            String found = text(on, "email");
            if (found != null && found.equalsIgnoreCase(email) && text(on, "id") != null) {
                return text(on, "id");
            }
        }
        throw new KeycloakAdminException("Keycloak created a user but it could not be found for realm=" + realm);
    }

    /** The user id: the last non-empty path segment of the Location URI, or {@code null}. */
    private static String idFromLocation(URI location) {
        if (location == null || location.getPath() == null) {
            return null;
        }
        String path = location.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String id = slash < 0 ? path : path.substring(slash + 1);
        return id.isBlank() ? null : id;
    }

    /**
     * Keycloak's {@code errorMessage} (or {@code error_description} / {@code error}) from a 400 body.
     * Only that one string is kept; the rest of the body is discarded.
     */
    private String keycloakErrorMessage(HttpClientErrorException e) {
        try {
            JsonNode node = jsonMapper.readTree(e.getResponseBodyAsString());
            for (String field : List.of("errorMessage", "error_description", "error")) {
                JsonNode v = node.get(field);
                if (v != null && v.isString() && !v.asString().isBlank()) {
                    return v.asString();
                }
            }
        } catch (Exception ignored) {
            // An unreadable body falls through to the generic sentence below.
        }
        return "Keycloak refused this account.";
    }

    /**
     * 31.1-11 (D-03): {@code DELETE /admin/realms/{realm}/users/{id}}. Irreversible.
     *
     * @return {@code true} when Keycloak deleted the user (2xx); {@code false} when it answered 404,
     *         i.e. the user is already gone, which is the goal state and not an error
     * @throws KeycloakAdminException on any other failure, carrying the realm only
     */
    public boolean deleteUser(String realm, String userId, String token) {
        try {
            restClient.delete()
                    .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak user delete failed for realm=" + realm, e);
        }
    }

    /** A string field of a user representation, or {@code null} when absent or JSON null. */
    private static String text(ObjectNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Revokes the user's active sessions ({@code POST .../users/{id}/logout}). */
    public void logoutUser(String realm, String userId, String token) {
        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Keycloak user logout failed for realm=" + realm + " userId=" + userId, e);
        }
    }
}
