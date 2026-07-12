package uk.jtoye.core.tenant.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-unit test of {@link KeycloakDeprovisionService} (Mockito, NO Spring
 * context, NO live Keycloak): multi-realm disable+logout orchestration, the
 * marker-only-on-full-success rule, the best-effort non-throwing contract on
 * partial failure, the inert (feature-off) no-op, and the idempotent
 * short-circuit. Real {@link KeycloakAdminProperties} instances drive the
 * configured()/realms behaviour.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakDeprovisionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KeycloakAdminProperties enabledProps(String... realms) {
        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://kc.test:8080");
        props.setPassword("s3cr3t");
        props.setRealms(List.of(realms));
        return props;
    }

    private static ObjectNode user() {
        ObjectNode u = MAPPER.createObjectNode();
        u.put("id", UUID.randomUUID().toString());
        u.put("enabled", true);
        return u;
    }

    private static Tenant tenantWithMarker(UUID id, OffsetDateTime marker) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setName("Vendor " + id);
        t.setKeycloakDeprovisionedAt(marker);
        return t;
    }

    @Test
    void enabled_twoRealmsWithUsers_disablesLogsOut_andStampsMarkerOnce() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        TenantRepository repo = mock(TenantRepository.class);
        KeycloakAdminProperties props = enabledProps("realm-a", "realm-b");
        KeycloakDeprovisionService service = new KeycloakDeprovisionService(client, props, repo);

        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenantWithMarker(tenantId, null);
        when(repo.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(client.obtainAdminToken()).thenReturn("tok");
        ObjectNode a1 = user();
        ObjectNode a2 = user();
        ObjectNode b1 = user();
        when(client.searchUsersByTenant("realm-a", tenantId, "tok")).thenReturn(List.of(a1, a2));
        when(client.searchUsersByTenant("realm-b", tenantId, "tok")).thenReturn(List.of(b1));

        KeycloakDeprovisionResult result = service.deprovision(tenantId);

        assertTrue(result.complete());
        assertEquals(3, result.usersDisabled());
        assertNotNull(result.deprovisionedAt());
        // Each user disabled + logged out exactly once.
        verify(client).setUserEnabled("realm-a", a1, false, "tok");
        verify(client).setUserEnabled("realm-a", a2, false, "tok");
        verify(client).setUserEnabled("realm-b", b1, false, "tok");
        verify(client, times(3)).logoutUser(anyString(), anyString(), eq("tok"));
        // Marker stamped exactly once.
        verify(repo, times(1)).save(any(Tenant.class));
        assertNotNull(tenant.getKeycloakDeprovisionedAt());
    }

    @Test
    void enabled_disableThrows_leavesMarkerNull_returnsIncomplete_neverThrows() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        TenantRepository repo = mock(TenantRepository.class);
        KeycloakAdminProperties props = enabledProps("realm-a");
        KeycloakDeprovisionService service = new KeycloakDeprovisionService(client, props, repo);

        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenantWithMarker(tenantId, null);
        when(repo.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(client.obtainAdminToken()).thenReturn("tok");
        ObjectNode u1 = user();
        ObjectNode u2 = user();
        when(client.searchUsersByTenant("realm-a", tenantId, "tok")).thenReturn(List.of(u1, u2));
        // First user disables fine; the second throws mid-sweep. Lenient because
        // the code under test intentionally invokes setUserEnabled with the
        // first (different-arg) user before hitting this stub.
        lenient().doThrow(new KeycloakAdminException("boom"))
                .when(client).setUserEnabled("realm-a", u2, false, "tok");

        KeycloakDeprovisionResult result = service.deprovision(tenantId); // must NOT throw

        assertFalse(result.complete());
        assertEquals(1, result.usersDisabled()); // partial count so far
        assertNull(result.deprovisionedAt());
        assertNull(tenant.getKeycloakDeprovisionedAt());
        verify(repo, never()).save(any(Tenant.class)); // marker never stamped
    }

    @Test
    void disabled_isInertNoOp_neverTouchesKeycloakOrRepo_warnsOnce() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        TenantRepository repo = mock(TenantRepository.class);
        KeycloakAdminProperties props = new KeycloakAdminProperties(); // enabled=false (default)
        KeycloakDeprovisionService service = new KeycloakDeprovisionService(client, props, repo);

        UUID tenantId = UUID.randomUUID();
        KeycloakDeprovisionResult first = service.deprovision(tenantId);
        KeycloakDeprovisionResult second = service.deprovision(tenantId); // WARN is once-only, still no-ops

        assertFalse(first.complete());
        assertEquals(0, first.usersDisabled());
        assertFalse(second.complete());
        verifyNoInteractions(client);
        verifyNoInteractions(repo);
    }

    @Test
    void markerAlreadySet_shortCircuits_idempotent_noKeycloakCalls() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        TenantRepository repo = mock(TenantRepository.class);
        KeycloakAdminProperties props = enabledProps("realm-a");
        KeycloakDeprovisionService service = new KeycloakDeprovisionService(client, props, repo);

        UUID tenantId = UUID.randomUUID();
        OffsetDateTime existing = OffsetDateTime.now().minusHours(2);
        when(repo.findById(tenantId)).thenReturn(Optional.of(tenantWithMarker(tenantId, existing)));

        KeycloakDeprovisionResult result = service.deprovision(tenantId);

        assertTrue(result.complete());
        assertEquals(existing, result.deprovisionedAt());
        assertEquals(0, result.usersDisabled());
        verifyNoInteractions(client);
        verify(repo, never()).save(any(Tenant.class));
    }
}
