package uk.jtoye.core.onboarding;

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
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin gate-resolve (ONBD-03 / D-01, plan 21-03) on real Postgres 15
 * (Testcontainers), exercised end-to-end through the REAL
 * {@link KeycloakRealmRoleConverter}, the REAL state machine + async recompute,
 * and the V43 schema. Proves the core invariant: {@code POST
 * /onboarding/admin/{id}/gates/{gateType}/resolve} writes ONLY the gate row and
 * lets the existing {@code GateChainRunner} recompute advance the state machine
 * (never a direct status/published write):
 *
 * <ul>
 *   <li><strong>Advance</strong>: PASS on the last blocking MANUAL_REVIEW gate ->
 *       recompute -> out of VERIFYING (GATES_PASSED); FAIL -> ACTION_REQUIRED;</li>
 *   <li><strong>Audit</strong>: the gate override writes a {@code
 *       vendor_onboarding_gate_aud} row (Envers);</li>
 *   <li><strong>AuthZ / RLS</strong>: non-admin -> 403; a nonexistent onboarding ->
 *       404; a foreign tenant's onboarding is invisible under the NOSUPERUSER RLS
 *       role (the mechanism that makes a foreign id a 404 with no existence oracle);</li>
 *   <li><strong>Input validation</strong>: FAIL with a blank reason -> 400 (state
 *       unchanged); PASS/WAIVE with no reason -> 200.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingGateResolveIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String RLS_TEST_ROLE = "rls_gate_resolve_role";

    @Autowired private MockMvc mockMvc;
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

    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private static RequestPostProcessor userJwt(UUID tenant) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private static String resolveUrl(UUID onboardingId, GateType gateType) {
        return "/api/v1/onboarding/admin/" + onboardingId + "/gates/" + gateType.name() + "/resolve";
    }

    // --- Advance: PASS on the last blocking gate -> recompute -> out of VERIFYING --------

    @Test
    void adminResolvesManualReviewGateToPass_recomputeAdvancesOutOfVerifying() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED);

        // PASS with NO reason body -> 200 (reason optional for PASS/WAIVE).
        mockMvc.perform(post(resolveUrl(onboardingId, GateType.FOOD_HYGIENE_RATING))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PASS\"}"))
                .andExpect(status().isOk());

        // The recompute (afterCommit, async) advances the SM off VERIFYING; MARKETPLACE
        // parks at PENDING_APPROVAL (no auto-approve). The gate row is now PASSED.
        awaitStatusNot(onboardingId, "VERIFYING");
        assertThat(dbStatus(onboardingId)).isEqualTo("PENDING_APPROVAL");
        assertThat(dbGateStatus(onboardingId, GateType.FOOD_HYGIENE_RATING)).isEqualTo("PASSED");
    }

    // --- Advance: FAIL -> recompute -> ACTION_REQUIRED ------------------------------------

    @Test
    void adminResolvesGateToFail_recomputeReachesActionRequired() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);

        mockMvc.perform(post(resolveUrl(onboardingId, GateType.FOOD_HYGIENE_RATING))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FAIL\",\"reason\":\"Hygiene rating could not be confirmed\"}"))
                .andExpect(status().isOk());

        awaitStatusIs(onboardingId, "ACTION_REQUIRED");
        assertThat(dbGateStatus(onboardingId, GateType.FOOD_HYGIENE_RATING)).isEqualTo("FAILED");
    }

    // --- Audit: the override writes an Envers _aud row -----------------------------------

    @Test
    void gateResolveWritesEnversAuditRow() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED);

        // WAIVE with NO reason -> 200 (reason optional).
        mockMvc.perform(post(resolveUrl(onboardingId, GateType.FOOD_HYGIENE_RATING))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"WAIVE\"}"))
                .andExpect(status().isOk());

        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_gate_aud "
                        + "WHERE onboarding_id = ? AND gate_type = 'FOOD_HYGIENE_RATING' AND status = 'WAIVED'",
                Integer.class, onboardingId);
        assertThat(audited).as("Envers _aud row for the WAIVED override").isGreaterThanOrEqualTo(1);
    }

    // --- AuthZ / RLS ---------------------------------------------------------------------

    @Test
    void gateResolveByNonAdminIs403() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);

        mockMvc.perform(post(resolveUrl(onboardingId, GateType.FOOD_HYGIENE_RATING))
                        .with(userJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PASS\"}"))
                .andExpect(status().isForbidden());

        // The gate short-circuits before the service — the gate row is untouched.
        assertThat(dbGateStatus(onboardingId, GateType.FOOD_HYGIENE_RATING)).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void gateResolveNonexistentOnboardingIs404() throws Exception {
        mockMvc.perform(post(resolveUrl(UUID.randomUUID(), GateType.FOOD_HYGIENE_RATING))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PASS\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * The mechanism behind the foreign-tenant 404: under a NOSUPERUSER NOBYPASSRLS
     * role with tenant A's GUC, tenant B's onboarding row is invisible — so
     * {@code requireOnboardingById}'s {@code findById} returns empty for a foreign id
     * (a clean 404, no cross-tenant existence oracle). Mirrors the admin-queue RLS proof.
     */
    @Test
    @Transactional
    void foreignTenantOnboardingIsInvisibleUnderRls() {
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
        UUID onboardingA = UUID.randomUUID();
        UUID onboardingB = UUID.randomUUID();
        jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, model, status) "
                + "VALUES (?, ?, 'MARKETPLACE', 'VERIFYING')", onboardingA, tenantId);
        jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, model, status) "
                + "VALUES (?, ?, 'MARKETPLACE', 'VERIFYING')", onboardingB, tenantB);

        try {
            TenantContext.set(tenantId);
            jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);

            List<UUID> visible = jdbc.queryForList(
                    "SELECT id FROM vendor_onboarding WHERE id IN (?, ?)", UUID.class,
                    onboardingA, onboardingB);
            assertThat(visible).contains(onboardingA).doesNotContain(onboardingB);
        } finally {
            TenantContext.clear();
        }
    }

    // --- Input validation: FAIL requires a reason ----------------------------------------

    @Test
    void failDecisionWithBlankReasonIs400_stateUnchanged() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);

        mockMvc.perform(post(resolveUrl(onboardingId, GateType.FOOD_HYGIENE_RATING))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FAIL\",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(dbStatus(onboardingId)).isEqualTo("VERIFYING");
        assertThat(dbGateStatus(onboardingId, GateType.FOOD_HYGIENE_RATING)).isEqualTo("MANUAL_REVIEW");
    }

    // --- INT-1 (QA council 20260902-134741, A15): resolve is VERIFYING|ACTION_REQUIRED ----

    /**
     * INT-1: a MANUAL_REVIEW gate parked beside a FAILED one lands the onboarding in
     * ACTION_REQUIRED, and the VERIFYING-only guard then 400'd the reviewer's only control —
     * the third lockout mechanism behind the two-actor dead-end. Resolving in ACTION_REQUIRED
     * writes the gate row (Envers-audited) and leaves the state with the vendor: the recompute
     * advances ONLY from VERIFYING, and the vendor's RESUBMIT (which preserves PASSED/WAIVED
     * rows) is what carries the reviewer's decision forward.
     */
    @Test
    void adminResolvesManualReviewGateWhileActionRequired_rowUpdated_stateStaysWithVendor() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.ACTION_REQUIRED);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.FAILED);

        mockMvc.perform(post(resolveUrl(onboardingId, GateType.BUSINESS_VERIFIED))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PASS\",\"reason\":\"Checked the register by company name\"}"))
                .andExpect(status().isOk());

        assertThat(dbGateStatus(onboardingId, GateType.BUSINESS_VERIFIED)).isEqualTo("PASSED");
        // The FAILED row is the vendor's to fix and is untouched by the reviewer's decision.
        assertThat(dbGateStatus(onboardingId, GateType.ALLERGEN_DATA_COMPLETE)).isEqualTo("FAILED");
        // No state change: the after-commit recompute returns early outside VERIFYING
        // (GateChainRunner), so the application stays ACTION_REQUIRED for the vendor's re-run.
        assertStatusHolds(onboardingId, "ACTION_REQUIRED");
    }

    // --- WR-01 (narrowed by INT-1): gate resolution is VERIFYING|ACTION_REQUIRED-only ----

    /**
     * WR-01: resolving a gate on an onboarding that has already left the review window
     * (here PENDING_APPROVAL — post-recompute, awaiting the admin queue) is rejected with a
     * 400 (RFC 7807) and does NOT mutate the gate row. Without this guard the gate row
     * would flip but the recompute (which only advances from VERIFYING) could never act
     * on it, stranding the onboarding until a later /approve failed with an unexplained
     * gate-guard veto. INT-1 widened the window to ACTION_REQUIRED; this arm proves the
     * guard still holds beyond it.
     */
    @Test
    void gateResolveOutsideVerifyingIs400_gateRowUnchanged() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);

        mockMvc.perform(post(resolveUrl(onboardingId, GateType.FOOD_HYGIENE_RATING))
                        .with(adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FAIL\",\"reason\":\"Hygiene rating could not be confirmed\"}"))
                .andExpect(status().isBadRequest());

        // No silent mutation: the onboarding status AND the gate row are untouched.
        assertThat(dbStatus(onboardingId)).isEqualTo("PENDING_APPROVAL");
        assertThat(dbGateStatus(onboardingId, GateType.FOOD_HYGIENE_RATING)).isEqualTo("MANUAL_REVIEW");
    }

    // --- helpers --------------------------------------------------------------------------

    private UUID seedOnboarding(OnboardingState state) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
        onboarding.setSubmittedAt(OffsetDateTime.now().minusHours(1));
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

    private String dbGateStatus(UUID onboardingId, GateType type) {
        return jdbc.queryForObject(
                "SELECT status FROM vendor_onboarding_gate WHERE onboarding_id = ? AND gate_type = ?",
                String.class, onboardingId, type.name());
    }

    private void awaitStatusIs(UUID onboardingId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(dbStatus(onboardingId))) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for onboarding " + onboardingId + " to reach " + expected
                + " (was " + dbStatus(onboardingId) + ")");
    }

    private void awaitStatusNot(UUID onboardingId, String notExpected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!notExpected.equals(dbStatus(onboardingId))) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for onboarding " + onboardingId + " to leave " + notExpected);
    }

    /**
     * Assert the status is {@code expected} now AND after the after-commit async recompute
     * has had time to run — a negative that must be sampled over time, not once.
     */
    private void assertStatusHolds(UUID onboardingId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < deadline) {
            assertThat(dbStatus(onboardingId)).isEqualTo(expected);
            Thread.sleep(100);
        }
        assertThat(dbStatus(onboardingId)).isEqualTo(expected);
    }
}
