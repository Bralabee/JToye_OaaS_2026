package uk.jtoye.core.tenant.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure-unit test of {@link KeycloakAdminClient} against a
 * {@link MockRestServiceServer} bound to the RestClient.Builder — NO Spring
 * context, NO live Keycloak. Proves the four admin operations hit the correct
 * Keycloak 24 admin REST shapes, that the paginated search walks pages until a
 * short page, that the disable PUT carries the full rep with {@code enabled=false},
 * that every {@code /admin} call carries the bearer token, and that a 5xx
 * propagates as {@link KeycloakAdminException}.
 */
class KeycloakAdminClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "http://kc.test:8080";

    private static KeycloakAdminProperties testProps() {
        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setBaseUrl(BASE);
        props.setRealms(List.of("jtoye-dev"));
        props.setUsername("admin");
        props.setPassword("s3cr3t");
        return props;
    }

    /** Bind a fresh mock server to a builder, return both it and a wired client. */
    private record Fixture(MockRestServiceServer server, KeycloakAdminClient client) {}

    private static Fixture newFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakAdminClient client = new KeycloakAdminClient(builder, testProps(), MAPPER);
        return new Fixture(server, client);
    }

    private static ArrayNode usersArray(int count) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = 0; i < count; i++) {
            ObjectNode user = MAPPER.createObjectNode();
            user.put("id", UUID.randomUUID().toString());
            user.put("username", "u" + i);
            user.put("enabled", true);
            arr.add(user);
        }
        return arr;
    }

    @Test
    void obtainAdminToken_postsPasswordGrant_toMasterRealm() {
        Fixture f = newFixture();
        f.server().expect(requestTo(BASE + "/realms/master/protocol/openid-connect/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("grant_type=password")))
                .andExpect(content().string(containsString("client_id=admin-cli")))
                .andRespond(withSuccess("{\"access_token\":\"tok\"}", MediaType.APPLICATION_JSON));

        String token = f.client().obtainAdminToken();

        assertEquals("tok", token);
        f.server().verify();
    }

    @Test
    void searchUsersByTenant_paginates_untilShortPageReturns() {
        Fixture f = newFixture();
        UUID tenantId = UUID.randomUUID();

        // First page: exactly PAGE_SIZE (100) users -> the client must fetch again.
        f.server().expect(requestTo(allOf(
                        containsString("/admin/realms/jtoye-dev/users"),
                        containsString("q=tenant_id:" + tenantId),
                        containsString("first=0"),
                        containsString("max=100"))))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(usersArray(100).toString(), MediaType.APPLICATION_JSON));

        // Second page: a short 5-user page -> pagination stops here.
        f.server().expect(requestTo(allOf(
                        containsString("/admin/realms/jtoye-dev/users"),
                        containsString("first=100"),
                        containsString("max=100"))))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(usersArray(5).toString(), MediaType.APPLICATION_JSON));

        List<ObjectNode> users = f.client().searchUsersByTenant("jtoye-dev", tenantId, "tok");

        assertEquals(105, users.size());
        f.server().verify(); // both expectations consumed
    }

    @Test
    void setUserEnabled_putsFullRep_withEnabledFalse_andBearer() {
        Fixture f = newFixture();
        ObjectNode user = MAPPER.createObjectNode();
        String userId = UUID.randomUUID().toString();
        user.put("id", userId);
        user.put("username", "vendor-owner");
        user.put("enabled", true);
        user.put("firstName", "Ada"); // proves the FULL rep is preserved on PUT

        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users/" + userId))
                .andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.username").value("vendor-owner"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        f.client().setUserEnabled("jtoye-dev", user, false, "tok");

        f.server().verify();
    }

    @Test
    void logoutUser_postsLogout_withBearer() {
        Fixture f = newFixture();
        String userId = UUID.randomUUID().toString();

        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users/" + userId + "/logout"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        f.client().logoutUser("jtoye-dev", userId, "tok");

        f.server().verify();
    }

    @Test
    void serverError_propagatesAsKeycloakAdminException() {
        Fixture f = newFixture();
        f.server().expect(requestTo(BASE + "/realms/master/protocol/openid-connect/token"))
                .andRespond(withServerError());

        assertThrows(KeycloakAdminException.class, () -> f.client().obtainAdminToken());
        f.server().verify();
    }
}
