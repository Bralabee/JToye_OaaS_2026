package uk.jtoye.core.tenant.keycloak;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
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
        ObjectNode expected = ((ObjectNode) searched).deepCopy();
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

    // ---- 31.1-11 (D-03, D-21): the customer-account deletion path, by request content ----------

    /**
     * The search is the exact-email form in the realm the caller names, with the address
     * percent-encoded as a URI variable. The full URI is compared as a string, so a missing
     * {@code exact=true} (Keycloak then does a SUBSTRING match), an unencoded {@code @}, or a
     * different realm each fail here rather than "a GET happened".
     */
    @Test
    void findUsersByEmail_getsTheExactEmailSearch_inTheNamedRealm_andParsesEachUser() {
        Fixture f = newFixture();
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String body = "[{\"id\":\"" + id1 + "\",\"username\":\"grace\",\"email\":\"grace@x.test\","
                + "\"firstName\":\"Grace\",\"lastName\":\"Persona\",\"createdTimestamp\":1791100000000,"
                + "\"enabled\":true},"
                + "{\"id\":\"" + id2 + "\",\"username\":\"no-email-user\",\"enabled\":true}]";

        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users?email=grace%40x.test&exact=true"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<CustomerRealmUser> users = f.client().findUsersByEmail("jtoye-customers", "grace@x.test", "tok");

        f.server().verify();
        assertThat(users).containsExactly(
                new CustomerRealmUser(id1, "grace", "grace@x.test", "Grace", "Persona", 1791100000000L),
                new CustomerRealmUser(id2, "no-email-user", null, null, null, null));
    }

    /**
     * A {@code +} in an address must reach Keycloak as {@code %2B}. Left raw, a query string
     * decodes it as a SPACE, and the search looks for a different person.
     */
    @Test
    void findUsersByEmail_encodesAPlusSoItIsNotReadAsASpace() {
        Fixture f = newFixture();
        f.server().expect(requestTo(BASE
                        + "/admin/realms/jtoye-customers/users?email=grace%2Bdsar%40x.test&exact=true"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(f.client().findUsersByEmail("jtoye-customers", "grace+dsar@x.test", "tok")).isEmpty();
        f.server().verify();
    }

    @Test
    void findUsersByEmail_serverError_propagatesAsKeycloakAdminException_withoutTheAddress() {
        Fixture f = newFixture();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users?email=grace%40x.test&exact=true"))
                .andRespond(withServerError());

        KeycloakAdminException e = assertThrows(KeycloakAdminException.class,
                () -> f.client().findUsersByEmail("jtoye-customers", "grace@x.test", "tok"));
        assertThat(e.getMessage()).contains("jtoye-customers").doesNotContain("grace");
        f.server().verify();
    }

    @Test
    void deleteUser_sendsDelete_toTheUserInTheNamedRealm_andReturnsTrueOn204() {
        Fixture f = newFixture();
        String userId = UUID.randomUUID().toString();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + userId))
                .andExpect(method(org.springframework.http.HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(f.client().deleteUser("jtoye-customers", userId, "tok")).isTrue();
        f.server().verify();
    }

    /** A 404 means the user is already gone: the goal state, reported as false, never thrown. */
    @Test
    void deleteUser_returnsFalseOn404_alreadyGone() {
        Fixture f = newFixture();
        String userId = UUID.randomUUID().toString();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + userId))
                .andExpect(method(org.springframework.http.HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(f.client().deleteUser("jtoye-customers", userId, "tok")).isFalse();
        f.server().verify();
    }

    @Test
    void deleteUser_serverError_propagatesAsKeycloakAdminException() {
        Fixture f = newFixture();
        String userId = UUID.randomUUID().toString();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + userId))
                .andExpect(method(org.springframework.http.HttpMethod.DELETE))
                .andRespond(withServerError());

        KeycloakAdminException e = assertThrows(KeycloakAdminException.class,
                () -> f.client().deleteUser("jtoye-customers", userId, "tok"));
        assertThat(e.getMessage()).contains("jtoye-customers");
        f.server().verify();
    }

    // ---- 37-08 (D-07, D-26): the staff-invite accept path creates the vendor user ---------------

    static final String VENDOR_REALM = "jtoye-dev";
    static final String NEW_PASSWORD = "Correct-Horse-9-Battery";

    /**
     * The create is asserted BY CONTENT: the captured body is parsed and compared field by field AND
     * as a whole, so a dropped {@code emailVerified} (the token mint then fails "Account is not fully
     * set up"), a dropped {@code attributes.tenant_id} (the user gets no tenant), a temporary password
     * or a Jackson-2 node written as a bean each fail here. The id comes from the Location header,
     * and the caller's password array is zeroed once the call returns.
     */
    @Test
    void createUser_postsTheFullRepresentation_byContent_andReturnsTheIdFromLocation() {
        Fixture f = newFixture();
        UUID tenantId = UUID.randomUUID();
        String newId = UUID.randomUUID().toString();
        AtomicReference<String> postBody = new AtomicReference<>();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> postBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .location(java.net.URI.create(BASE + "/admin/realms/jtoye-dev/users/" + newId)));

        char[] password = NEW_PASSWORD.toCharArray();
        String id = f.client().createUser(VENDOR_REALM, "new.person@example.com", "Ada", "Lovelace",
                password, tenantId, "tok");

        f.server().verify();
        assertThat(id).as("the id is the Location header's last segment").isEqualTo(newId);
        assertCreateBodyByContent(postBody.get(), "new.person@example.com", "Ada", "Lovelace", tenantId);
        assertThat(new String(password)).as("the caller's password array is zeroed after the call")
                .isEqualTo("\0".repeat(NEW_PASSWORD.length()));
    }

    /** Shared so a break arm (attribute dropped) runs the very same assertion. */
    static void assertCreateBodyByContent(String body, String email, String first, String last, UUID tenantId) {
        assertThat(body).as("captured POST body").isNotBlank();
        JsonNode node = J3.readTree(body);
        assertThat(node.isObject()).as("POST body must be a JSON object: %s", "<redacted>").isTrue();
        assertThat(node.propertyNames()).as("not a JsonNode serialized as a bean")
                .doesNotContainAnyElementsOf(GARBAGE_KEYS);
        assertThat(node.path("username").asString()).as("username = email").isEqualTo(email);
        assertThat(node.path("email").asString()).isEqualTo(email);
        assertThat(node.path("firstName").asString()).isEqualTo(first);
        assertThat(node.path("lastName").asString()).isEqualTo(last);
        assertThat(node.path("enabled").isBoolean() && node.path("enabled").booleanValue())
                .as("enabled true").isTrue();
        assertThat(node.path("emailVerified").isBoolean() && node.path("emailVerified").booleanValue())
                .as("emailVerified true: the invite link proved the address; without it the token mint fails")
                .isTrue();
        assertThat(node.path("attributes").path("tenant_id").isArray())
                .as("attributes.tenant_id must be a JSON array").isTrue();
        assertThat(node.path("attributes").path("tenant_id").size()).isEqualTo(1);
        assertThat(node.path("attributes").path("tenant_id").get(0).asString())
                .as("attributes.tenant_id [tenant]").isEqualTo(tenantId.toString());
        JsonNode creds = node.path("credentials");
        assertThat(creds.isArray() && creds.size() == 1).as("exactly one credential").isTrue();
        assertThat(creds.get(0).path("type").asString()).isEqualTo("password");
        assertThat(creds.get(0).path("value").asString()).isEqualTo(NEW_PASSWORD);
        assertThat(creds.get(0).path("temporary").isBoolean() && !creds.get(0).path("temporary").booleanValue())
                .as("temporary false: the invitee chose it").isTrue();

        ObjectNode expected = J3.createObjectNode();
        expected.put("username", email);
        expected.put("email", email);
        expected.put("firstName", first);
        expected.put("lastName", last);
        expected.put("enabled", true);
        expected.put("emailVerified", true);
        expected.putObject("attributes").putArray("tenant_id").add(tenantId.toString());
        ObjectNode cred = expected.putArray("credentials").addObject();
        cred.put("type", "password");
        cred.put("value", NEW_PASSWORD);
        cred.put("temporary", false);
        assertThat(node).as("the whole representation, nothing more").isEqualTo(expected);
    }

    /** Keycloak normally answers 201 + Location; without one the id is read back by exact email. */
    @Test
    void createUser_withoutLocation_readsTheIdBackByExactEmail() {
        Fixture f = newFixture();
        String newId = UUID.randomUUID().toString();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED));
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users?email=new.person%40example.com&exact=true"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("[{\"id\":\"" + newId + "\",\"email\":\"new.person@example.com\"}]",
                        MediaType.APPLICATION_JSON));

        String id = f.client().createUser(VENDOR_REALM, "new.person@example.com", "Ada", "Lovelace",
                NEW_PASSWORD.toCharArray(), UUID.randomUUID(), "tok");

        f.server().verify();
        assertThat(id).isEqualTo(newId);
    }

    /**
     * A 400 (the realm password policy) surfaces as a typed exception carrying Keycloak's
     * {@code errorMessage} verbatim — the body measured from Keycloak 24.0.5 — and neither the
     * message nor the exception text contains the password. The array is zeroed on failure too.
     */
    @Test
    void createUser_400_isTyped_carryingKeycloaksMessage_neverThePassword() {
        Fixture f = newFixture();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessage\":\"Password policy not met\"}"));

        char[] password = NEW_PASSWORD.toCharArray();
        KeycloakUserRejectedException e = assertThrows(KeycloakUserRejectedException.class,
                () -> f.client().createUser(VENDOR_REALM, "new.person@example.com", "Ada", "Lovelace",
                        password, UUID.randomUUID(), "tok"));

        f.server().verify();
        assertThat(e.getKeycloakMessage()).isEqualTo("Password policy not met");
        assertThat(e.getMessage()).contains("jtoye-dev").doesNotContain(NEW_PASSWORD).doesNotContain("new.person");
        assertThat(String.valueOf(e.getKeycloakMessage())).doesNotContain(NEW_PASSWORD);
        assertThat(new String(password)).isEqualTo("\0".repeat(NEW_PASSWORD.length()));
    }

    @Test
    void createUser_409_isTypedUserExists() {
        Fixture f = newFixture();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessage\":\"User exists with same username\"}"));

        assertThrows(KeycloakUserExistsException.class,
                () -> f.client().createUser(VENDOR_REALM, "new.person@example.com", "Ada", "Lovelace",
                        NEW_PASSWORD.toCharArray(), UUID.randomUUID(), "tok"));
        f.server().verify();
    }

    /** The vendor lookup is the exact-email search, and it reads each user's tenant_id attribute. */
    @Test
    void findVendorUsersByEmail_readsIdAndTheTenantAttribute() {
        Fixture f = newFixture();
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        UUID tenantId = UUID.randomUUID();
        String body = "[{\"id\":\"" + id1 + "\",\"email\":\"staff@x.test\",\"attributes\":{\"tenant_id\":[\""
                + tenantId + "\"]}},{\"id\":\"" + id2 + "\",\"email\":\"staff@x.test\"}]";
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-dev/users?email=staff%40x.test&exact=true"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<VendorRealmUser> users = f.client().findVendorUsersByEmail(VENDOR_REALM, "staff@x.test", "tok");

        f.server().verify();
        assertThat(users).containsExactly(
                new VendorRealmUser(id1, "staff@x.test", tenantId.toString()),
                new VendorRealmUser(id2, "staff@x.test", null));
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
