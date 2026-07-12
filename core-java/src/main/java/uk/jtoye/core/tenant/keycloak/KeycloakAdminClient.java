package uk.jtoye.core.tenant.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Low-level seam over the Keycloak admin REST API (issue #102 remainder). Core
 * is a pure OAuth2 resource server today, so this is the FIRST admin caller from
 * the Java side (the only existing admin caller is the one-shot
 * {@code infra/keycloak/configure-keycloak.sh}, deliberately untouched).
 *
 * <p>Four operations, mapped to the Keycloak 24 admin REST shape:
 * <ul>
 *   <li>{@link #obtainAdminToken()} — master-realm {@code admin-cli} password grant.</li>
 *   <li>{@link #searchUsersByTenant} — paginated user search by the
 *       {@code tenant_id} attribute (page size 100).</li>
 *   <li>{@link #setUserEnabled} — PUT the full user representation back with
 *       {@code enabled} flipped (Keycloak requires the whole rep on update).</li>
 *   <li>{@link #logoutUser} — revoke the user's active sessions.</li>
 * </ul>
 *
 * <p><b>Security (STRIDE T-kc-01):</b> the bearer token and admin password are
 * NEVER logged. Non-2xx / transport failures are wrapped into
 * {@link KeycloakAdminException} carrying realm/operation context only — the
 * client never maps to an HTTP status or swallows a failure; the service layer
 * owns the best-effort availability decision.
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    /** Keycloak page size for the attribute search; a short final page ends pagination. */
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final KeycloakAdminProperties properties;
    private final ObjectMapper objectMapper;

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               KeycloakAdminProperties properties,
                               ObjectMapper objectMapper) {
        // baseUrl may be empty when the feature is inert — the client is simply
        // never called in that state (the service short-circuits on configured()).
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
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
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode token = node.get("access_token");
            if (token == null || token.asText().isBlank()) {
                throw new KeycloakAdminException("Keycloak token response had no access_token");
            }
            return token.asText();
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
                JsonNode page = objectMapper.readTree(body == null ? "[]" : body);
                int pageCount = 0;
                if (page.isArray()) {
                    for (JsonNode user : page) {
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
        String userId = userRep.path("id").asText();
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
