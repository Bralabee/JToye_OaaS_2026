package uk.jtoye.core.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #102 [P2-11] AC1 end-to-end (Testcontainers Postgres, Flyway V48, real
 * RBAC converter, real interceptors): a tenant is created / suspended /
 * reactivated / offboarded through the admin API — no SQL — and a
 * suspended/offboarded tenant's API traffic is rejected with 403 by
 * {@code TenantStatusInterceptor}, tenant-wide.
 *
 * <p>Mirrors {@code RoleBasedAccessIntegrationTest}: the MockMvc {@code jwt()}
 * post-processor supplies authorities via the REAL
 * {@link KeycloakRealmRoleConverter} against a {@code realm_access} claim, so
 * the {@code @PreAuthorize("hasRole('admin')")} gate is exercised exactly as
 * production maps it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class TenantLifecycleAdminIntegrationTest {

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

    /** The platform admin's own tenant (V13-style seed row). */
    private static final UUID ADMIN_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                ADMIN_TENANT, "Tenant A");
    }

    // Both tokens carry a UUID subject — the 23-08 fail-closed ShopAccessService gate denies any
    // authenticated principal whose sub is not a UUID, so the pre-Phase-23 default MockMvc subject
    // ("user") 403'd on the tenant-scoped shop/order traffic these tests exercise (the admin
    // lifecycle endpoints themselves are role-gated, not shop-gated, and were unaffected). The
    // realm-role / RBAC semantics are unchanged: authorities still come from the real
    // KeycloakRealmRoleConverter against the realm_access claim.

    /** admin realm role + a tenant claim; authorities via the REAL converter. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt(UUID tenantId) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /** low-privilege (user-only) token for the negative RBAC control. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(UUID tenantId) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private UUID createTenantViaApi(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/tenants")
                        .with(adminJwt(ADMIN_TENANT))
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"plan\":\"STANDARD\","
                                + "\"contactEmail\":\"owner@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan").value("STANDARD"))
                .andExpect(jsonPath("$.stripeConnectStatus").value("NONE"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    // ------------------------------------------------------------------
    // AC1: create / suspend / reactivate / offboard — no SQL
    // ------------------------------------------------------------------

    @Test
    void fullLifecycle_createSuspendReactivateOffboard_noSql() throws Exception {
        UUID tenantId = createTenantViaApi("Lifecycle Vendor " + UUID.randomUUID());

        // The row really exists (created via API, not SQL)
        mockMvc.perform(get("/api/v1/admin/tenants/{id}", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // suspend
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/suspend", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspendedAt").isNotEmpty());

        // reactivate
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/reactivate", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // offboard (terminal)
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFBOARDED"))
                .andExpect(jsonPath("$.offboardedAt").isNotEmpty());

        // terminal: any further transition is an invalid-state 400
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/suspend", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/reactivate", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isBadRequest());

        // DB agrees (single source of truth check)
        assertEquals("OFFBOARDED", jdbcTemplate.queryForObject(
                "SELECT status FROM tenants WHERE id = ?", String.class, tenantId));
    }

    @Test
    void duplicateName_conflicts409() throws Exception {
        String name = "Dup Vendor " + UUID.randomUUID();
        createTenantViaApi(name);
        mockMvc.perform(post("/api/v1/admin/tenants")
                        .with(adminJwt(ADMIN_TENANT))
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // AC1 authz: platform-admin RBAC gate
    // ------------------------------------------------------------------

    @Test
    void lowPrivForbiddenOnAllLifecycleEndpoints() throws Exception {
        UUID tenantId = createTenantViaApi("RBAC Vendor " + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/admin/tenants")
                        .with(userJwt(ADMIN_TENANT))
                        .contentType("application/json")
                        .content("{\"name\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/tenants").with(userJwt(ADMIN_TENANT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/suspend", tenantId).with(userJwt(ADMIN_TENANT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(userJwt(ADMIN_TENANT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/stripe/connect", tenantId).with(userJwt(ADMIN_TENANT)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // AC1 enforcement: suspended/offboarded tenants' API traffic is rejected
    // ------------------------------------------------------------------

    @Test
    void suspendedTenantTraffic_rejected403_thenRestoredOnReactivate() throws Exception {
        UUID tenantId = createTenantViaApi("Blocked Vendor " + UUID.randomUUID());

        // Baseline: the tenant's traffic flows (tenant-scoped list endpoint)
        mockMvc.perform(get("/api/v1/shops").with(userJwt(tenantId)))
                .andExpect(status().isOk());

        // suspend → the SAME request is now rejected tenant-wide with 403.
        // (Same-instance cache eviction is immediate — the eviction path under test.)
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/suspend", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/shops").with(userJwt(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("suspended")));
        // ...even with an admin-role token: enforcement is tenant-wide, not role-based
        mockMvc.perform(get("/api/v1/orders").with(adminJwt(tenantId)))
                .andExpect(status().isForbidden());

        // reactivate → traffic restored
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/reactivate", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/shops").with(userJwt(tenantId)))
                .andExpect(status().isOk());

        // offboard → permanently rejected
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/shops").with(userJwt(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("offboarded")));
    }

    @Test
    void suspendedAdminsOwnTenant_lifecycleSurfaceStaysReachable_noLockout() throws Exception {
        // Suspend the admin's OWN tenant...
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/suspend", ADMIN_TENANT).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk());
        try {
            // ...their general API traffic is blocked like anyone else's...
            mockMvc.perform(get("/api/v1/shops").with(adminJwt(ADMIN_TENANT)))
                    .andExpect(status().isForbidden());
            // ...but the lifecycle surface is exempt, so they can un-suspend themselves.
            mockMvc.perform(post("/api/v1/admin/tenants/{id}/reactivate", ADMIN_TENANT).with(adminJwt(ADMIN_TENANT)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/shops").with(adminJwt(ADMIN_TENANT)))
                    .andExpect(status().isOk());
        } finally {
            // Belt-and-braces: never leave the shared seed tenant suspended for other tests.
            jdbcTemplate.update("UPDATE tenants SET status = 'ACTIVE', suspended_at = NULL WHERE id = ?",
                    ADMIN_TENANT);
        }
    }

    // ------------------------------------------------------------------
    // Stripe Connect endpoint (no live Stripe in this environment)
    // ------------------------------------------------------------------

    @Test
    void stripeConnect_withoutConfiguredKeys_isCleanClientError_notA500() throws Exception {
        UUID tenantId = createTenantViaApi("Connect Vendor " + UUID.randomUUID());
        // The test environment has EMPTY Stripe keys (no live Stripe): the endpoint
        // must fail loud-and-clean (400 invalid-state), not 500. The SDK-level
        // destination-charge/Express behaviour is proven in StripeConnectServiceTest
        // with MockedStatic; live Connect verification is deferred to a keyed env.
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/stripe/connect", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("not configured")));
    }

    // ------------------------------------------------------------------
    // Issue #102 remainder: Keycloak deprovisioning on offboard.
    // The feature is DISABLED here (default), so the REAL
    // KeycloakDeprovisionService exercises the inert path end-to-end.
    // ------------------------------------------------------------------

    @Test
    void featureDisabled_offboard_staysClean_markerNull() throws Exception {
        UUID tenantId = createTenantViaApi("Offboard Clean Vendor " + UUID.randomUUID());

        // Offboard succeeds; the after-commit hook runs the real (inert) service.
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFBOARDED"));

        // Feature off -> no Keycloak calls, marker stays NULL.
        assertNull(jdbcTemplate.queryForObject(
                "SELECT keycloak_deprovisioned_at FROM tenants WHERE id = ?",
                java.sql.Timestamp.class, tenantId));
    }

    @Test
    void retrigger_requiresAdmin_userForbidden() throws Exception {
        // The class-level @PreAuthorize gate fires before the method body, so a
        // low-privilege token is 403'd regardless of tenant state.
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/keycloak/deprovision", ADMIN_TENANT)
                        .with(userJwt(ADMIN_TENANT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void retrigger_activeTenant_is400_notOffboarded() throws Exception {
        UUID tenantId = createTenantViaApi("Retrigger Active Vendor " + UUID.randomUUID());
        // Tenant is ACTIVE -> not a valid deprovision target (checked before the
        // config gate), so a distinct 400 even with the feature off.
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/keycloak/deprovision", tenantId)
                        .with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("OFFBOARDED")));
    }

    @Test
    void retrigger_offboardedButFeatureDisabled_notConfigured400() throws Exception {
        UUID tenantId = createTenantViaApi("Retrigger Disabled Vendor " + UUID.randomUUID());
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/offboard", tenantId).with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isOk());

        // OFFBOARDED passes the state gate; the feature-off config gate then 400s.
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/keycloak/deprovision", tenantId)
                        .with(adminJwt(ADMIN_TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("not configured")));
    }
}
