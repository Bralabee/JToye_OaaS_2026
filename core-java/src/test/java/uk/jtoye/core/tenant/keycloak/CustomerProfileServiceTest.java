package uk.jtoye.core.tenant.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.jtoye.core.exception.CustomerProfileUnavailableException;
import uk.jtoye.core.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SW-JRD-07 / SW-SAM-11 (owner ruling 2026-11-04): a signed-in customer changes their display name.
 * The name lives only in the customer realm, so the change goes through the existing Keycloak admin
 * seam, to the account named by the VERIFIED token's subject and no other.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerProfileServiceTest {

    private static final JsonMapper M = JsonMapper.builder().build();
    private static final String REALM = "jtoye-customers";
    private static final String TOKEN = "admin-tok";
    private static final String SUB = "7d6f6b1e-0000-4000-8000-000000000001";
    private static final String EMAIL = "ada@example.com";

    private KeycloakAdminClient client;
    private KeycloakAdminProperties props;
    private CustomerProfileService service;

    @BeforeEach
    void setUp() {
        client = mock(KeycloakAdminClient.class);
        props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://kc.test:8080");
        props.setPassword("pw-" + java.util.UUID.randomUUID());
        props.setRealms(List.of("jtoye-dev"));
        service = new CustomerProfileService(client, props);
        when(client.obtainAdminToken()).thenReturn(TOKEN);
    }

    private static ObjectNode rep(String email) {
        ObjectNode n = M.createObjectNode();
        n.put("id", SUB);
        n.put("username", "ada");
        n.put("email", email);
        n.put("emailVerified", true);
        n.put("firstName", "Old");
        n.put("lastName", "Name");
        n.putObject("attributes").putArray("locale").add("en");
        return n;
    }

    @Test
    @DisplayName("changes first and last name on the token's own account, keeping every other field")
    void changesTheName() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));

        String saved = service.updateDisplayName(SUB, EMAIL, "  Ada   King Lovelace ");

        assertThat(saved).isEqualTo("Ada King Lovelace");
        ArgumentCaptor<ObjectNode> put = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).updateUser(eq(REALM), put.capture(), eq(TOKEN));
        assertThat(put.getValue().path("firstName").asString()).isEqualTo("Ada");
        assertThat(put.getValue().path("lastName").asString()).isEqualTo("King Lovelace");
        assertThat(put.getValue().path("email").asString()).isEqualTo(EMAIL);
        assertThat(put.getValue().path("attributes").path("locale").get(0).asString()).isEqualTo("en");
    }

    @Test
    @DisplayName("a single-word name is the first name with an empty last name")
    void singleWordName() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));

        service.updateDisplayName(SUB, EMAIL, "Cher");

        ArgumentCaptor<ObjectNode> put = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).updateUser(eq(REALM), put.capture(), eq(TOKEN));
        assertThat(put.getValue().path("firstName").asString()).isEqualTo("Cher");
        assertThat(put.getValue().path("lastName").asString()).isEmpty();
    }

    @Test
    @DisplayName("the account is chosen by the token's subject, and refused if its stored email is not the token's")
    void refusesAnAccountWhoseEmailDiffers() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep("someone-else@example.com")));

        assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "Ada"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(client, never()).updateUser(any(), any(), any());
    }

    @Test
    @DisplayName("an unknown subject is a 404, nothing is written")
    void unknownSubject() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "Ada"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(client, never()).updateUser(any(), any(), any());
    }

    @Test
    @DisplayName("only the customer realm is ever touched, never the vendor realm list")
    void onlyTheCustomerRealm() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));

        service.updateDisplayName(SUB, EMAIL, "Ada");

        verify(client, never()).getUser(eq("jtoye-dev"), any(), any());
        verify(client, never()).updateUser(eq("jtoye-dev"), any(), any());
    }

    @Test
    @DisplayName("with the admin seam switched off it is the typed 'unavailable', and nothing is attempted")
    void notConfigured() {
        props.setEnabled(false);

        assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "Ada"))
                .isInstanceOf(CustomerProfileUnavailableException.class);
        verify(client, never()).obtainAdminToken();
    }

    @Test
    @DisplayName("a Keycloak failure is the typed 'unavailable', never a stack trace to the caller")
    void keycloakFailure() {
        when(client.getUser(REALM, SUB, TOKEN)).thenThrow(new KeycloakAdminException("boom realm=" + REALM, null));

        assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "Ada"))
                .isInstanceOf(CustomerProfileUnavailableException.class);
    }

    @Test
    @DisplayName("F4: Keycloak refusing the NAME with a 400/422 is a 400 'name not accepted', never a 503 retry-later")
    void keycloakValidationRefusalIsABadRequest() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));
        for (int status : new int[]{400, 422}) {
            org.mockito.Mockito.doThrow(new KeycloakAdminException("Keycloak user update failed for realm=" + REALM,
                    org.springframework.web.client.HttpClientErrorException.create(
                            org.springframework.http.HttpStatusCode.valueOf(status), "refused", null, null, null)))
                    .when(client).updateUser(eq(REALM), any(), eq(TOKEN));

            assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "A&B Smith"))
                    .as("Keycloak %s on the update", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("wasn't accepted");
        }
    }

    @Test
    @DisplayName("F4: a mononym whose empty lastName a stricter realm refuses is the same 400, not a 503")
    void mononymRefusedByAStricterRealmIsABadRequest() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));
        org.mockito.Mockito.doThrow(new KeycloakAdminException("Keycloak user update failed for realm=" + REALM,
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatusCode.valueOf(400), "lastName required", null, null, null)))
                .when(client).updateUser(eq(REALM), any(), eq(TOKEN));

        assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "Cher"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("F4: a Keycloak 5xx, a connection failure and a 401 on OUR admin credential stay the retryable 503")
    void keycloakServerSideFailuresStayUnavailable() {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));
        Throwable[] causes = {
                org.springframework.web.client.HttpServerErrorException.create(
                        org.springframework.http.HttpStatusCode.valueOf(500), "boom", null, null, null),
                new org.springframework.web.client.ResourceAccessException("connection refused"),
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatusCode.valueOf(401), "admin token", null, null, null),
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatusCode.valueOf(403), "admin forbidden", null, null, null)};
        for (Throwable cause : causes) {
            org.mockito.Mockito.doThrow(new KeycloakAdminException("Keycloak user update failed for realm=" + REALM, cause))
                    .when(client).updateUser(eq(REALM), any(), eq(TOKEN));

            assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, "Ada Lovelace"))
                    .as("cause %s", cause.getClass().getSimpleName())
                    .isInstanceOf(CustomerProfileUnavailableException.class);
        }
    }

    @Test
    @DisplayName("blank, over-long and control-character names are refused before Keycloak is asked")
    void invalidNames() {
        for (String bad : new String[]{null, "", "   ", "x".repeat(101), "Ada\u0000", "Ada\nKing"}) {
            assertThatThrownBy(() -> service.updateDisplayName(SUB, EMAIL, bad))
                    .as("name %s", bad == null ? "null" : "'" + bad.replace("\n", "\\n") + "'")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(client, never()).obtainAdminToken();
    }

    @Test
    @DisplayName("the log never carries the name or the address")
    void logHygiene(CapturedOutput out) {
        when(client.getUser(REALM, SUB, TOKEN)).thenReturn(Optional.of(rep(EMAIL)));

        service.updateDisplayName(SUB, EMAIL, "Zebediah Quux");

        assertThat(out.getAll()).doesNotContain("Zebediah").doesNotContain("Quux").doesNotContain(EMAIL);
    }
}
