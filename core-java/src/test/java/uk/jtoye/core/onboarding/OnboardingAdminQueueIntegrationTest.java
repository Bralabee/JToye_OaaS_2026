package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin approve/reject queue (#178 slice 2) on real Postgres 15 (Testcontainers).
 * Exercises the {@code /api/v1/onboarding/admin} surface end-to-end through the
 * REAL {@link KeycloakRealmRoleConverter} (the #83 RBAC pattern proven by
 * {@code RoleBasedAccessIntegrationTest}), the REAL state machine, and the V43
 * schema:
 *
 * <ul>
 *   <li><strong>AuthZ</strong>: a {@code user}-only token gets 403 on all three
 *       endpoints — the gate short-circuits before the service;</li>
 *   <li><strong>Queue</strong>: PENDING_APPROVAL applications are listed with the
 *       review-relevant fields (model, shop name, submitted date, gate breakdown);
 *       non-pending states are not;</li>
 *   <li><strong>Approve</strong>: drives the SM's APPROVE event — 200 + APPROVED +
 *       {@code approvedAt} when all mandatory gates are green, and a 400 guard
 *       veto (state unchanged) when one is not, mirroring the guard-veto
 *       regression pattern;</li>
 *   <li><strong>Reject</strong>: the REQUIRED human reason is persisted on the
 *       aggregate AND recorded in the Envers {@code vendor_onboarding_aud}
 *       mirror; a blank reason is a 400 bean-validation failure that leaves the
 *       state untouched; REJECT from LIVE is an illegal transition (400);</li>
 *   <li><strong>RLS</strong>: the queue's SELECT shape is tenant-scoped under a
 *       NOSUPERUSER role — tenant B's pending application is invisible to
 *       tenant A's admin (the platform-wide queue is a documented follow-up);</li>
 *   <li><strong>IN-08</strong>: an authenticated request whose token carries no
 *       tenant maps to 500 (server misconfiguration), not 400.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingAdminQueueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String RLS_TEST_ROLE = "rls_admin_queue_role";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VendorOnboardingRepository onboardingRepository;
    @Autowired private VendorOnboardingGateRepository gateRepository;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedTenantAndShop() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "Mama's Kitchen " + shopId.toString().substring(0, 8),
                "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
    }

    /** admin realm-role token bound to {@code tenant} — the REAL #83 converter maps it. */
    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /** low-privilege (user-only) token bound to {@code tenant}. */
    private static RequestPostProcessor userJwt(UUID tenant) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    // --- AuthZ: non-admin is rejected before the service --------------------------

    @Test
    void nonAdminGets403OnEveryQueueEndpoint() throws Exception {
        UUID anyId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/onboarding/admin/pending").with(userJwt(tenantId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/approve", anyId).with(userJwt(tenantId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/reject", anyId).with(userJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Queue listing -------------------------------------------------------------

    @Test
    void adminListsPendingApplicationWithGateSummaryShopNameAndSubmittedDate() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL,
                OffsetDateTime.now().minusHours(3));
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED);

        String body = mockMvc.perform(get("/api/v1/onboarding/admin/pending").with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode row = findById(objectMapper.readTree(body), onboardingId);
        assertThat(row).as("seeded PENDING_APPROVAL onboarding is listed").isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(row.get("model").asText()).isEqualTo("MARKETPLACE");
        assertThat(row.get("shopName").asText()).startsWith("Mama's Kitchen");
        assertThat(row.get("submittedAt").isNull()).isFalse();
        assertThat(row.get("gates")).hasSize(3);
        row.get("gates").forEach(g -> assertThat(g.get("status").asText()).isEqualTo("PASSED"));
    }

    @Test
    void queueOmitsNonPendingStates() throws Exception {
        UUID draftId = seedOnboarding(OnboardingState.DRAFT, null);

        String body = mockMvc.perform(get("/api/v1/onboarding/admin/pending").with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(findById(objectMapper.readTree(body), draftId))
                .as("DRAFT onboarding must not appear in the approval queue").isNull();
    }

    // --- Approve ---------------------------------------------------------------------

    @Test
    void adminApprovesPendingApplication_statusApprovedAndApprovedAtStamped() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL,
                OffsetDateTime.now().minusHours(1));
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.WAIVED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED);

        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/approve", onboardingId)
                        .with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());

        assertThat(dbStatus(onboardingId)).isEqualTo("APPROVED");
    }

    /**
     * Guard-veto regression (mirrors the #177 pattern): a human approval of an
     * application whose mandatory gate went red is VETOED by the APPROVE guard —
     * the event is consumed but the state must not change, surfacing as 400.
     */
    @Test
    void approveVetoedByGateGuard_400AndStateUnchanged() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL,
                OffsetDateTime.now().minusHours(1));
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.FAILED);

        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/approve", onboardingId)
                        .with(adminJwt(tenantId)))
                .andExpect(status().isBadRequest());

        assertThat(dbStatus(onboardingId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void approveNonexistentOnboardingIs404() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/approve", UUID.randomUUID())
                        .with(adminJwt(tenantId)))
                .andExpect(status().isNotFound());
    }

    // --- Reject ------------------------------------------------------------------------

    @Test
    void adminRejectsWithReason_persistedOnAggregateAndInEnversAudit() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL,
                OffsetDateTime.now().minusHours(2));
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);

        String reason = "Hygiene evidence inconsistent with the registered premises";
        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/reject", onboardingId)
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(reason));

        assertThat(dbStatus(onboardingId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT rejection_reason FROM vendor_onboarding WHERE id = ?",
                String.class, onboardingId)).isEqualTo(reason);

        // Auditable: the Envers _aud mirror recorded the rejection write.
        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_aud WHERE id = ? AND rejection_reason = ?",
                Integer.class, onboardingId, reason);
        assertThat(audited).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rejectWithBlankReasonIs400AndStateUnchanged() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL,
                OffsetDateTime.now().minusHours(2));

        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/reject", onboardingId)
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(dbStatus(onboardingId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void rejectFromLiveIsIllegalTransition_400() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.LIVE, OffsetDateTime.now().minusDays(1));

        mockMvc.perform(post("/api/v1/onboarding/admin/{id}/reject", onboardingId)
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isBadRequest());

        assertThat(dbStatus(onboardingId)).isEqualTo("LIVE");
        assertThat(jdbc.queryForObject(
                "SELECT rejection_reason FROM vendor_onboarding WHERE id = ?",
                String.class, onboardingId)).isNull();
    }

    // --- RLS: the queue SELECT is tenant-scoped ------------------------------------------

    /**
     * The exact query shape behind {@code findByStatusOrderBySubmittedAtAsc},
     * executed under a NOSUPERUSER NOBYPASSRLS role with tenant A's GUC while
     * tenant B also has a PENDING_APPROVAL row: only A's is visible. This is the
     * mechanical proof that the admin queue is tenant-scoped (the platform-wide
     * queue is the documented follow-up on #178).
     */
    @Test
    @Transactional
    void pendingQueueSelectIsTenantScopedUnderRls() {
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");

        UUID tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantB, "test-" + tenantB);
        UUID pendingA = UUID.randomUUID();
        UUID pendingB = UUID.randomUUID();
        jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, model, status) "
                + "VALUES (?, ?, 'MARKETPLACE', 'PENDING_APPROVAL')", pendingA, tenantId);
        jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, model, status) "
                + "VALUES (?, ?, 'MARKETPLACE', 'PENDING_APPROVAL')", pendingB, tenantB);

        try {
            TenantContext.set(tenantId);
            jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);

            List<UUID> visible = jdbc.queryForList(
                    "SELECT id FROM vendor_onboarding WHERE status = 'PENDING_APPROVAL' "
                            + "ORDER BY submitted_at ASC", UUID.class);
            assertThat(visible).contains(pendingA).doesNotContain(pendingB);
        } finally {
            TenantContext.clear();
        }
    }

    // --- IN-08: missing tenant context maps to 500, not 400 ------------------------------

    @Test
    void missingTenantOnAuthenticatedRequestIs500ServerFault() throws Exception {
        // Admin token with NO tenant_id claim -> TenantContext never established ->
        // CurrentTenant.require() -> MissingTenantContextException -> 500 (IN-08;
        // previously the generic IllegalStateException handler blamed the client with 400).
        RequestPostProcessor tenantlessAdmin = jwt()
                .jwt(j -> j.claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());

        mockMvc.perform(get("/api/v1/onboarding/admin/pending").with(tenantlessAdmin))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Missing Tenant Context"));
    }

    // --- helpers --------------------------------------------------------------------------

    private UUID seedOnboarding(OnboardingState state, OffsetDateTime submittedAt) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
        onboarding.setSubmittedAt(submittedAt);
        return onboardingRepository.saveAndFlush(onboarding).getId();
    }

    private void seedGate(UUID onboardingId, GateType type, GateStatus gateStatus) {
        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(tenantId);
        gate.setOnboardingId(onboardingId);
        gate.setGateType(type);
        gate.setStatus(gateStatus);
        gate.setMandatory(true);
        gateRepository.saveAndFlush(gate);
    }

    private String dbStatus(UUID onboardingId) {
        return jdbc.queryForObject(
                "SELECT status FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }

    /**
     * The bootstrap superuser test datasource bypasses RLS, so the queue may also
     * contain rows seeded by sibling tests — find OUR row by id instead of
     * asserting list size (tenant scoping itself is proven under the NOSUPERUSER
     * role above).
     */
    private JsonNode findById(JsonNode list, UUID id) {
        for (JsonNode row : list) {
            if (id.toString().equals(row.get("id").asText())) {
                return row;
            }
        }
        return null;
    }
}
