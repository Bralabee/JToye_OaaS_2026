package uk.jtoye.core.tenant.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.jtoye.core.tenant.keycloak.CustomerAccountDeletionService.AccountDeletionResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test of {@link CustomerAccountDeletionService} against a mocked {@link KeycloakAdminClient}:
 * the two wrong-account guards (T-31.1-36), the non-throwing contract, and log hygiene (T-31.1-39).
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerAccountDeletionServiceTest {

    private static final String TOKEN = "tok";
    private static final String CUSTOMER_REALM = "jtoye-customers";
    private static final String VENDOR_REALM = "jtoye-dev";

    private KeycloakAdminClient client;
    private KeycloakAdminProperties props;
    private CustomerAccountDeletionService service;

    @BeforeEach
    void setUp() {
        client = mock(KeycloakAdminClient.class);
        props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://kc.test:8080");
        props.setPassword("s3cr3t");
        props.setRealms(List.of(VENDOR_REALM));
        service = new CustomerAccountDeletionService(client, props);
        when(client.obtainAdminToken()).thenReturn(TOKEN);
    }

    private static CustomerRealmUser user(String id, String email) {
        return new CustomerRealmUser(id, "u-" + id, email, null, null, null);
    }

    @Test
    void aUserWhoseEmailOnlyContainsTheAddressIsNeverDeleted() {
        String exactId = UUID.randomUUID().toString();
        String evilId = UUID.randomUUID().toString();
        String prefixId = UUID.randomUUID().toString();
        // What a server that ignored exact=true would return: substring matches beside the real one.
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN)).thenReturn(List.of(
                user(evilId, "grace@x.test.evil"),
                user(exactId, "Grace@X.test"),
                user(prefixId, "old.grace@x.test")));

        AccountDeletionResult result = service.deleteCustomerAccount("grace@x.test");

        assertThat(result).isEqualTo(AccountDeletionResult.DELETED);
        verify(client).deleteUser(CUSTOMER_REALM, exactId, TOKEN);
        verify(client, never()).deleteUser(anyString(), eq(evilId), anyString());
        verify(client, never()).deleteUser(anyString(), eq(prefixId), anyString());
    }

    @Test
    void onlySubstringMatchesComeBack_soNothingIsDeletedAndTheResultIsNoneFound() {
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN))
                .thenReturn(List.of(user(UUID.randomUUID().toString(), "grace@x.test.evil")));

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.NONE_FOUND);
        verify(client, never()).deleteUser(anyString(), anyString(), anyString());
    }

    @Test
    void aUserWithNoStoredEmailIsNeverDeleted() {
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN))
                .thenReturn(List.of(user(UUID.randomUUID().toString(), null)));

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.NONE_FOUND);
        verify(client, never()).deleteUser(anyString(), anyString(), anyString());
    }

    @Test
    void theServiceOnlyEverCallsTheClientWithTheCustomerRealm() {
        String id = UUID.randomUUID().toString();
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN))
                .thenReturn(List.of(user(id, "grace@x.test")));

        service.deleteCustomerAccount("grace@x.test");

        verify(client).obtainAdminToken();
        verify(client).findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN);
        verify(client).deleteUser(CUSTOMER_REALM, id, TOKEN);
        verifyNoMoreInteractions(client);
        verify(client, never()).findUsersByEmail(eq(VENDOR_REALM), anyString(), anyString());
    }

    @Test
    void aConfiguredCustomerRealmIsTheOneUsed() {
        props.setCustomerRealm("acme-customers");
        when(client.findUsersByEmail("acme-customers", "grace@x.test", TOKEN)).thenReturn(List.of());

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.NONE_FOUND);
        verify(client).findUsersByEmail("acme-customers", "grace@x.test", TOKEN);
        verify(client, never()).findUsersByEmail(eq(CUSTOMER_REALM), anyString(), anyString());
    }

    @Test
    void theAddressIsTrimmedAndLowerCasedForTheSearch() {
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace.persona+x@example.test", TOKEN)).thenReturn(List.of());

        service.deleteCustomerAccount("  Grace.Persona+X@Example.TEST ");

        verify(client).findUsersByEmail(CUSTOMER_REALM, "grace.persona+x@example.test", TOKEN);
    }

    @Test
    void aDeleteThatFindsTheUserAlreadyGoneStillCountsAsDeleted() {
        String id = UUID.randomUUID().toString();
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN)).thenReturn(List.of(user(id, "grace@x.test")));
        when(client.deleteUser(CUSTOMER_REALM, id, TOKEN)).thenReturn(false);

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.DELETED);
    }

    @Test
    void notConfiguredMakesNoKeycloakCallAtAll() {
        props.setEnabled(false);

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.NOT_CONFIGURED);
        verifyNoInteractions(client);
    }

    @Test
    void aKeycloakFailureIsReportedNotThrown_andTheAddressIsNotLogged(CapturedOutput output) {
        String address = "logged.never." + UUID.randomUUID() + "@x.test";
        when(client.findUsersByEmail(CUSTOMER_REALM, address, TOKEN))
                .thenThrow(new KeycloakAdminException("Keycloak user email search failed for realm=" + CUSTOMER_REALM
                        + " while looking up " + address));

        assertThat(service.deleteCustomerAccount(address)).isEqualTo(AccountDeletionResult.FAILED);
        assertThat(output.getAll()).as("CONTROL: the failure is logged").contains("event=dsar_account_deletion_failed");
        assertThat(output.getAll()).as("the address, even inside an exception message, is never logged")
                .doesNotContain(address);
    }

    @Test
    void aTokenFailureOrAnyRuntimeErrorIsReportedNotThrown() {
        when(client.obtainAdminToken()).thenThrow(new IllegalStateException("boom"));

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.FAILED);
    }

    @Test
    void aDeleteFailurePartWayIsFailed_notDeleted() {
        String id = UUID.randomUUID().toString();
        when(client.findUsersByEmail(CUSTOMER_REALM, "grace@x.test", TOKEN)).thenReturn(List.of(user(id, "grace@x.test")));
        when(client.deleteUser(CUSTOMER_REALM, id, TOKEN))
                .thenThrow(new KeycloakAdminException("Keycloak user delete failed for realm=" + CUSTOMER_REALM));

        assertThat(service.deleteCustomerAccount("grace@x.test")).isEqualTo(AccountDeletionResult.FAILED);
    }
}
