package uk.jtoye.core.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Authorization contract for {@link BillingController} (issue #1020).
 *
 * <p>{@link BillingController} previously gated all four endpoints with
 * {@code @PreAuthorize("hasAnyRole('ADMIN', 'TENANT_ADMIN', 'VENDOR', 'OPERATOR')")},
 * which never matched the lowercase authorities {@code KeycloakRealmRoleConverter} builds
 * from Keycloak's {@code realm_access.roles} claim ({@code ROLE_admin}, {@code ROLE_vendor},
 * etc.), making billing unreachable (HTTP 403) for every real user.
 *
 * <p>All four endpoints now enforce {@link ShopAccessService#requireGroupAdmin()} — the same
 * Group-admin boundary used by finance, staff and webhooks:
 * <ul>
 *   <li>An operator-granted tenant-wide {@code GROUP_ADMIN} carrying a real Keycloak
 *       {@code vendor} realm role reaches all four endpoints (200 OK).</li>
 *   <li>A Keycloak realm {@code admin} ({@code ROLE_admin} via the D-03 bridge) reaches
 *       billing without needing a {@code shop_staff} row.</li>
 *   <li>Ungranted users, per-shop {@code STAFF}, per-shop {@code SHOP_MANAGER}, and callers
 *       carrying only a synthetic uppercase {@code VENDOR} realm role without a
 *       {@code GROUP_ADMIN} grant are refused on all four endpoints with HTTP 403 and problem
 *       type {@code https://jtoye.uk/errors/shop-access-denied}
 *       ({@code requiredRole = "GROUP_ADMIN"}), mutating nothing.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class BillingControllerAuthorizationIntegrationTest {

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Blank Stripe API key so subscription state transitions run against PostgreSQL
        // without outbound Stripe HTTP calls during authorization tests.
        registry.add("stripe.api-key", () -> "");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopAccessService shopAccessService;

    private Object bootedStrictScoping;

    @BeforeEach
    void captureStrictScoping() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    @Test
    @DisplayName("Issue #1020: an operator GROUP_ADMIN carrying Keycloak's lowercase 'vendor' realm role reaches all four billing endpoints")
    void operatorGroupAdmin_withKeycloakVendorRole_reachesAllFourBillingEndpoints() throws Exception {
        Fixture f = Fixture.seed(jdbc);
        UUID groupAdmin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenantId, groupAdmin, null, "GROUP_ADMIN",
                "owner-" + groupAdmin + "@example.com");
        RequestPostProcessor caller = keycloakJwt(f.tenantId, groupAdmin, List.of("vendor"));

        // 1. GET /api/v1/billing/subscription
        MvcResult getRes = mockMvc.perform(get("/api/v1/billing/subscription").with(caller))
                .andReturn();
        assertThat(getRes.getResponse().getStatus()).as(body(getRes)).isEqualTo(200);
        JsonNode initial = jsonMapper.readTree(getRes.getResponse().getContentAsString());
        assertThat(initial.path("tenantId").asString()).isEqualTo(f.tenantId.toString());

        // 2. POST /api/v1/billing/subscription (subscribe to WALK_SETUP_STEPDOWN)
        MvcResult subRes = mockMvc.perform(post("/api/v1/billing/subscription").with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planMode\":\"WALK_SETUP_STEPDOWN\"}"))
                .andReturn();
        assertThat(subRes.getResponse().getStatus()).as(body(subRes)).isEqualTo(200);
        JsonNode subBody = jsonMapper.readTree(subRes.getResponse().getContentAsString());
        assertThat(subBody.path("billingPlanMode").asString()).isEqualTo("WALK_SETUP_STEPDOWN");
        assertThat(subBody.path("walkSetupCompleted").asBoolean()).isFalse();

        // 3. POST /api/v1/billing/subscription/complete-walk-setup
        MvcResult walkRes = mockMvc.perform(post("/api/v1/billing/subscription/complete-walk-setup").with(caller))
                .andReturn();
        assertThat(walkRes.getResponse().getStatus()).as(body(walkRes)).isEqualTo(200);
        JsonNode walkBody = jsonMapper.readTree(walkRes.getResponse().getContentAsString());
        assertThat(walkBody.path("walkSetupCompleted").asBoolean()).isTrue();

        // 4. POST /api/v1/billing/subscription/cancel
        MvcResult cancelRes = mockMvc.perform(post("/api/v1/billing/subscription/cancel")
                        .param("immediate", "true")
                        .with(caller))
                .andReturn();
        assertThat(cancelRes.getResponse().getStatus()).as(body(cancelRes)).isEqualTo(200);
        JsonNode cancelBody = jsonMapper.readTree(cancelRes.getResponse().getContentAsString());
        assertThat(cancelBody.path("subscriptionStatus").asString()).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("Issue #1020: a Keycloak realm 'admin' (lowercase ROLE_admin bridge) reaches billing without a shop_staff row")
    void realmAdmin_withKeycloakAdminRole_reachesBillingWithoutShopStaffRow() throws Exception {
        Fixture f = Fixture.seed(jdbc);
        UUID realmAdmin = UUID.randomUUID();
        RequestPostProcessor caller = keycloakJwt(f.tenantId, realmAdmin, List.of("admin"));

        MvcResult getRes = mockMvc.perform(get("/api/v1/billing/subscription").with(caller))
                .andReturn();
        assertThat(getRes.getResponse().getStatus()).as(body(getRes)).isEqualTo(200);

        MvcResult subRes = mockMvc.perform(post("/api/v1/billing/subscription").with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planMode\":\"BATCH_PAYG_STARTER\"}"))
                .andReturn();
        assertThat(subRes.getResponse().getStatus()).as(body(subRes)).isEqualTo(200);
        JsonNode subBody = jsonMapper.readTree(subRes.getResponse().getContentAsString());
        assertThat(subBody.path("billingPlanMode").asString()).isEqualTo("BATCH_PAYG_STARTER");
    }

    @Test
    @DisplayName("Issue #1020: ungranted, STAFF, SHOP_MANAGER, and ungranted uppercase-VENDOR callers are refused on all four billing endpoints with shop-access-denied")
    void nonGroupAdminCallers_refusedOnAllBillingEndpoints_withShopAccessDenied() throws Exception {
        Fixture f = Fixture.seed(jdbc);

        UUID ungranted = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenantId, staff, f.shopId, "STAFF",
                "staff-" + staff + "@example.com");
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenantId, manager, f.shopId, "SHOP_MANAGER",
                "manager-" + manager + "@example.com");
        UUID legacyUppercaseRoleOnly = UUID.randomUUID();

        List<RequestPostProcessor> refusedCallers = List.of(
                keycloakJwt(f.tenantId, ungranted, List.of("vendor")),
                keycloakJwt(f.tenantId, staff, List.of("vendor")),
                keycloakJwt(f.tenantId, manager, List.of("vendor")),
                keycloakJwt(f.tenantId, legacyUppercaseRoleOnly, List.of("VENDOR"))
        );

        for (RequestPostProcessor caller : refusedCallers) {
            assertGroupAdminDenied(mockMvc.perform(get("/api/v1/billing/subscription").with(caller)).andReturn());
            assertGroupAdminDenied(mockMvc.perform(post("/api/v1/billing/subscription").with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"planMode\":\"SAAS_HYBRID_STANDARD\"}")).andReturn());
            assertGroupAdminDenied(mockMvc.perform(post("/api/v1/billing/subscription/complete-walk-setup")
                    .with(caller)).andReturn());
            assertGroupAdminDenied(mockMvc.perform(post("/api/v1/billing/subscription/cancel")
                    .param("immediate", "true")
                    .with(caller)).andReturn());
        }

        String planModeAfter = jdbc.queryForObject(
                "SELECT billing_plan_mode FROM tenants WHERE id = ?", String.class, f.tenantId);
        String statusAfter = jdbc.queryForObject(
                "SELECT subscription_status FROM tenants WHERE id = ?", String.class, f.tenantId);
        assertThat(planModeAfter).as("refused callers must not mutate billing_plan_mode").isEqualTo("SAAS_HYBRID_STANDARD");
        assertThat(statusAfter).as("refused callers must not mutate subscription_status").isEqualTo("NONE");
    }

    private void assertGroupAdminDenied(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("status, body: %s", content).isEqualTo(403);
        JsonNode problem = jsonMapper.readTree(content);
        assertThat(problem.path("type").asString()).as("problem type, body: %s", content).isEqualTo(SHOP_ACCESS_DENIED);
        assertThat(problem.path("requiredRole").asString()).as("requiredRole, body: %s", content).isEqualTo("GROUP_ADMIN");
    }

    /**
     * Builds a MockMvc JWT post-processor that feeds a real Keycloak-shaped {@code realm_access.roles}
     * claim through the production {@link JwtRolesAndScopesConverter}.
     */
    private static RequestPostProcessor keycloakJwt(UUID tenantId, UUID sub, List<String> realmRoles) {
        return jwt()
                .jwt(j -> j.subject(sub.toString())
                        .claim("azp", "core-api")
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("realm_access", Map.of("roles", realmRoles)))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private record Fixture(UUID tenantId, UUID shopId) {
        static Fixture seed(JdbcTemplate jdbc) {
            UUID tenantId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO tenants (id, name, status, billing_plan_mode, subscription_status, created_at, updated_at) "
                            + "VALUES (?, ?, 'ACTIVE', 'SAAS_HYBRID_STANDARD', 'NONE', NOW(), NOW())",
                    tenantId, "billing-1020-" + tenantId);
            UUID shopId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                            + "VALUES (?, ?, ?, ?, '1 Test High St, London, E1 6AN', true, 0)",
                    shopId, tenantId, "Billing Shop " + shopId, "billing-shop-" + shopId);
            return new Fixture(tenantId, shopId);
        }
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }
}
