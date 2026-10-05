package uk.jtoye.core.tenant.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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

    /** Parses captured bodies; independent of the mapper the client is built with. */
    private static final JsonMapper J3 = JsonMapper.builder().build();

    /**
     * The keys a {@code JsonNode} gains when a mapper serializes it as a plain bean
     * (38-SPIKE §3 item 1 measured {@code array}, {@code bigDecimal}, {@code nodeType},
     * {@code containerNode}, ...). A real Keycloak user representation has none of them.
     */
    static final Set<String> GARBAGE_KEYS =
            Set.of("nodeType", "array", "bigDecimal", "containerNode", "missingNode", "valueNode");

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

    /**
     * BOOT4-05 / D-01: the disable PUT is asserted BY CONTENT.
     *
     * <p>The user representation comes from the client's own search (as it does in
     * {@code KeycloakDeprovisionService}), the PUT body is captured verbatim and parsed, and
     * the parsed body must be exactly the searched representation with only {@code enabled}
     * flipped to {@code false}. The garbage-key check runs first so a regression names the
     * measured signature: under Boot 4 a Jackson-2 node reaching the Jackson-3 RestClient
     * converter is written as a bean ({@code {"array":false,...,"nodeType":"OBJECT",...}}),
     * Keycloak receives no {@code enabled} field, and the offboarded user stays ENABLED.
     *
     * <p>The previous form ({@code jsonPath("$.enabled").value(false)} plus two fields) never
     * looked for those keys; this one does, and the Jackson-2-node arm in 38-07's evidence
     * shows it turning red on {@code nodeType}.
     */
    @Test
    void setUserEnabled_putsTheSearchedRepBack_withOnlyEnabledFlipped_byContent() {
        Fixture f = newFixture();
        UUID tenantId = UUID.randomUUID();
        String userId = UUID.randomUUID().toString();
        String searchedRep = "{\"id\":\"" + userId + "\",\"username\":\"vendor-owner\",\"enabled\":true,"
                + "\"firstName\":\"Ada\",\"emailVerified\":false,\"createdTimestamp\":1791100000000,"
                + "\"attributes\":{\"tenant_id\":[\"" + tenantId + "\"]}}";

        f.server().expect(requestTo(allOf(
                        containsString("/admin/realms/jtoye-dev/users"),
                        containsString("q=tenant_id:" + tenantId))))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[" + searchedRep + "]", MediaType.APPLICATION_JSON));

        AtomicReference<String> putBody = new AtomicReference<>();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users/" + userId))
                .andExpect(method(org.springframework.http.HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> putBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        List<ObjectNode> users = f.client().searchUsersByTenant("jtoye-dev", tenantId, "tok");
        assertEquals(1, users.size());
        f.client().setUserEnabled("jtoye-dev", users.get(0), false, "tok");

        f.server().verify();
        assertDisableBodyByContent(putBody.get(), searchedRep);
    }

    /**
     * The by-content check of a Keycloak disable PUT body. Shared so the 38-07 Jackson-2-node
     * arm runs the very same assertion against the pre-fix shape.
     */
    static void assertDisableBodyByContent(String body, String searchedRep) {
        assertThat(body).as("captured PUT body").isNotBlank();
        JsonNode put = J3.readTree(body);
        JsonNode searched = J3.readTree(searchedRep);
        assertThat(put.isObject()).as("PUT body must be a JSON object: %s", body).isTrue();

        // The measured defect signature first, so a regression names it.
        assertThat(put.propertyNames())
                .as("PUT body must not be a JsonNode serialized as a bean: %s", body)
                .doesNotContainAnyElementsOf(GARBAGE_KEYS);

        assertThat(put.path("enabled").isBoolean()).as("enabled must be a boolean: %s", body).isTrue();
        assertThat(put.path("enabled").booleanValue()).as("enabled: %s", body).isFalse();
        assertThat(put.path("id")).as("id preserved").isEqualTo(searched.path("id"));
        assertThat(put.path("username")).as("username preserved").isEqualTo(searched.path("username"));
        assertThat(put.path("attributes").path("tenant_id").isArray())
                .as("attributes.tenant_id stays a JSON array: %s", body).isTrue();
        assertThat(put.path("attributes").path("tenant_id"))
                .as("attributes.tenant_id preserved")
                .isEqualTo(searched.path("attributes").path("tenant_id"));

        // Keycloak requires the whole representation: nothing but enabled may change.
        tools.jackson.databind.node.ObjectNode expected =
                ((tools.jackson.databind.node.ObjectNode) searched).deepCopy();
        expected.put("enabled", false);
        assertThat(put).as("PUT body == searched rep with only enabled flipped").isEqualTo(expected);
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
