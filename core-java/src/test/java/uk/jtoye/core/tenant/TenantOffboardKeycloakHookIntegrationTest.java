package uk.jtoye.core.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminException;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #102 remainder — the offboard after-commit Keycloak deprovisioning hook,
 * with the feature ENABLED via {@link TestPropertySource}. The
 * {@link KeycloakAdminClient} is mocked (NOT the service), so the REAL
 * {@code KeycloakDeprovisionService} runs on a real Testcontainers Postgres:
 *
 * <ul>
 *   <li>on a clean sweep, {@code keycloak_deprovisioned_at} is stamped AFTER the
 *       offboard tx commits (proves the {@code REQUIRES_NEW} after-commit write
 *       actually persists);</li>
 *   <li>on a Keycloak failure, the offboard still reaches OFFBOARDED and the
 *       marker stays NULL — best-effort, non-rolling-back.</li>
 * </ul>
 *
 * <p>base-url is a deliberately unreachable {@code http://localhost:1}: the mock
 * client intercepts every call, so nothing is dialled. Each test uses a distinct
 * tenant; the shared seed tenant is never offboarded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@TestPropertySource(properties = {
        "jtoye.keycloak.admin.enabled=true",
        "jtoye.keycloak.admin.base-url=http://localhost:1",
        "jtoye.keycloak.admin.password=test-secret",
        "jtoye.keycloak.admin.realms=jtoye-dev"
})
class TenantOffboardKeycloakHookIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    /** Mock the low-level seam; the real KeycloakDeprovisionService orchestrates it. */
    @MockBean private KeycloakAdminClient keycloakAdminClient;

    private static final UUID ADMIN_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                ADMIN_TENANT, "Tenant A");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt(UUID tenantId) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private UUID createTenantViaApi(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/tenants")
                        .with(adminJwt(ADMIN_TENANT))
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private Timestamp markerOf(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT keycloak_deprovisioned_at FROM tenants WHERE id = ?", Timestamp.class, tenantId);
    }

    @Test
    void hookInvokedAfterCommit_stampsMarker_onCleanSweep() throws Exception {
        when(keycloakAdminClient.obtainAdminToken()).thenReturn("tok");
        // Empty user set is a clean sweep (no realm errored) -> marker gets stamped.
        when(keycloakAdminClient.searchUsersByTenant(anyString(), any(UUID.class), anyString()))
                .thenReturn(List.<ObjectNode>of());

        UUID tenantId = createTenantViaApi("Hook Success Vendor " + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFBOARDED"));

        // The after-commit hook ran the real service, which stamped the marker in
        // its own REQUIRES_NEW transaction.
        assertNotNull(markerOf(tenantId), "keycloak_deprovisioned_at should be stamped after a clean sweep");
        verify(keycloakAdminClient, atLeastOnce()).obtainAdminToken();
    }

    @Test
    void keycloakFailure_doesNotRollBackOffboard_markerStaysNull() throws Exception {
        // Keycloak is unreachable: the token call blows up mid-sweep.
        when(keycloakAdminClient.obtainAdminToken())
                .thenThrow(new KeycloakAdminException("keycloak unreachable"));

        UUID tenantId = createTenantViaApi("Hook Failure Vendor " + UUID.randomUUID());

        // Offboard still succeeds (best-effort, non-rolling-back).
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFBOARDED"));

        // DB is the source of truth: OFFBOARDED committed, marker NULL.
        assertEquals("OFFBOARDED", jdbcTemplate.queryForObject(
                "SELECT status FROM tenants WHERE id = ?", String.class, tenantId));
        assertNull(markerOf(tenantId), "marker must stay NULL when the Keycloak sweep failed");
    }
}
