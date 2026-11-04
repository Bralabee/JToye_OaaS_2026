package uk.jtoye.core.tenant.keycloak;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * SW-JRD-07: the two admin calls behind a customer changing their name, against a mock Keycloak. The
 * PUT carries the whole representation (Keycloak requires it) and is a real JSON object, the same
 * content check the disable PUT has had since 38-07.
 */
class KeycloakAdminClientUserUpdateTest {

    private static final JsonMapper J3 = JsonMapper.builder().build();
    private static final String BASE = "http://kc.test:8080";
    private static final String ID = "7d6f6b1e-0000-4000-8000-000000000001";

    private record Fixture(MockRestServiceServer server, KeycloakAdminClient client) { }

    private static Fixture fixture() {
        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setBaseUrl(BASE);
        props.setRealms(List.of("jtoye-dev"));
        props.setUsername("admin");
        props.setPassword("pw-" + java.util.UUID.randomUUID());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(server, new KeycloakAdminClient(builder, props, J3));
    }

    @Test
    void getUser_readsByIdInTheNamedRealm() {
        Fixture f = fixture();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"id\":\"" + ID + "\",\"email\":\"ada@example.com\",\"firstName\":\"Ada\"}",
                        MediaType.APPLICATION_JSON));

        Optional<ObjectNode> user = f.client().getUser("jtoye-customers", ID, "tok");

        f.server().verify();
        assertThat(user).isPresent();
        assertThat(user.get().path("email").asString()).isEqualTo("ada@example.com");
    }

    @Test
    void getUser_404_isEmpty_notAnError() {
        Fixture f = fixture();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(f.client().getUser("jtoye-customers", ID, "tok")).isEmpty();
    }

    @Test
    void getUser_5xx_isAKeycloakAdminException_naming_the_realm_only() {
        Fixture f = fixture();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + ID))
                .andRespond(withServerError());

        KeycloakAdminException e = assertThrows(KeycloakAdminException.class,
                () -> f.client().getUser("jtoye-customers", ID, "tok"));
        assertThat(e.getMessage()).contains("jtoye-customers");
    }

    @Test
    void updateUser_putsTheFullRepAsARealJsonObject_byContent() {
        Fixture f = fixture();
        ObjectNode rep = J3.createObjectNode();
        rep.put("id", ID);
        rep.put("email", "ada@example.com");
        rep.put("firstName", "Ada");
        rep.put("lastName", "King");
        rep.putObject("attributes").putArray("locale").add("en");

        AtomicReference<String> putBody = new AtomicReference<>();
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(request -> putBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        f.client().updateUser("jtoye-customers", rep, "tok");

        f.server().verify();
        JsonNode sent = J3.readTree(putBody.get());
        assertThat(sent.isObject()).as("a JSON object, not a bean-serialised node: %s", putBody.get()).isTrue();
        assertThat(sent.has("nodeType")).isFalse();
        assertThat(sent.path("firstName").asString()).isEqualTo("Ada");
        assertThat(sent.path("lastName").asString()).isEqualTo("King");
        assertThat(sent.path("attributes").path("locale").get(0).asString()).isEqualTo("en");
    }

    @Test
    void updateUser_refusal_isAKeycloakAdminException() {
        Fixture f = fixture();
        ObjectNode rep = J3.createObjectNode();
        rep.put("id", ID);
        f.server().expect(requestTo(BASE + "/admin/realms/jtoye-customers/users/" + ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(KeycloakAdminException.class, () -> f.client().updateUser("jtoye-customers", rep, "tok"));
    }
}
