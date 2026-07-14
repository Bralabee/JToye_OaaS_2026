package uk.jtoye.core.onboarding;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manual-review visibility (ONBD-03 / ONBD-05, plan 21-03) on real Postgres 15
 * (Testcontainers). Two concerns are proven end-to-end through the vendor and
 * admin surfaces:
 *
 * <ul>
 *   <li><strong>Vendor DTO derivation</strong>: {@code GET /onboarding/me} derives
 *       {@code reviewPending} at the {@code toDto} site — true only when the
 *       onboarding is VERIFYING with a MANUAL_REVIEW gate and no still-PENDING gate
 *       (D-03) — and now surfaces the stored {@code rejectionReason} (D-09, ONBD-05);</li>
 *   <li><strong>Admin review queue</strong>: {@code GET /onboarding/admin/reviews}
 *       lists VERIFYING + MANUAL_REVIEW applications (the black-hole state the
 *       existing {@code /pending} approve/reject queue never showed) while leaving
 *       that {@code /pending} queue untouched.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingReviewQueueIntegrationTest {

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

    /** vendor (user-role) token bound to {@code tenant} — sufficient for the self-scoped vendor endpoints. */
    private static RequestPostProcessor tenantJwt(UUID tenant) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    // --- ONBD-03: vendor DTO reviewPending derivation (GET /onboarding/me) --------------

    @Test
    void verifyingWithManualReviewAndNoPending_reviewPendingTrue() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING, OffsetDateTime.now().minusMinutes(5), null);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.WAIVED);

        mockMvc.perform(get("/api/v1/onboarding/me").with(tenantJwt(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"))
                .andExpect(jsonPath("$.reviewPending").value(true));
    }

    @Test
    void verifyingWithStillPendingGate_reviewPendingFalse() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING, OffsetDateTime.now().minusMinutes(1), null);
        // A still-running check (PENDING) means the checks are not yet parked for a human.
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PENDING);

        mockMvc.perform(get("/api/v1/onboarding/me").with(tenantJwt(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"))
                .andExpect(jsonPath("$.reviewPending").value(false));
    }

    @Test
    void actionRequiredWithFailedGate_reviewPendingFalse() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.ACTION_REQUIRED, OffsetDateTime.now().minusHours(1), null);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.FAILED);

        mockMvc.perform(get("/api/v1/onboarding/me").with(tenantJwt(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTION_REQUIRED"))
                .andExpect(jsonPath("$.reviewPending").value(false));
    }

    @Test
    void rejectedOnboarding_exposesStoredRejectionReasonOnVendorDto() throws Exception {
        String reason = "Hygiene evidence inconsistent with the registered premises";
        UUID onboardingId = seedOnboarding(OnboardingState.REJECTED, OffsetDateTime.now().minusDays(1), reason);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.FAILED);

        mockMvc.perform(get("/api/v1/onboarding/me").with(tenantJwt(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewPending").value(false))
                .andExpect(jsonPath("$.rejectionReason").value(reason));
    }

    // --- ONBD-03: admin review queue (GET /onboarding/admin/reviews) --------------------

    @Test
    void reviewQueueListsVerifyingWithManualReviewGate() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING, OffsetDateTime.now().minusMinutes(10), null);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.MANUAL_REVIEW);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);

        String body = mockMvc.perform(get("/api/v1/onboarding/admin/reviews").with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode row = findById(objectMapper.readTree(body), onboardingId);
        assertThat(row).as("VERIFYING + MANUAL_REVIEW onboarding is in the review queue").isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("VERIFYING");
        assertThat(row.get("shopName").asText()).startsWith("Mama's Kitchen");
        assertThat(row.get("gates").size()).isEqualTo(2);
    }

    @Test
    void reviewQueueOmitsVerifyingWithOnlyPendingGates() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING, OffsetDateTime.now().minusMinutes(2), null);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.PENDING);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PENDING);

        String body = mockMvc.perform(get("/api/v1/onboarding/admin/reviews").with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(findById(objectMapper.readTree(body), onboardingId))
                .as("VERIFYING with only PENDING gates (no MANUAL_REVIEW) must not be in the review queue")
                .isNull();
    }

    @Test
    void reviewQueueOmitsPendingApproval() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.PENDING_APPROVAL, OffsetDateTime.now().minusHours(1), null);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);

        String body = mockMvc.perform(get("/api/v1/onboarding/admin/reviews").with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(findById(objectMapper.readTree(body), onboardingId))
                .as("PENDING_APPROVAL stays in the /pending approve/reject queue, not /reviews")
                .isNull();
    }

    @Test
    void reviewQueueByNonAdminIs403() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/admin/reviews").with(tenantJwt(tenantId)))
                .andExpect(status().isForbidden());
    }

    // --- helpers --------------------------------------------------------------------------

    private UUID seedOnboarding(OnboardingState state, OffsetDateTime submittedAt, String rejectionReason) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
        onboarding.setSubmittedAt(submittedAt);
        onboarding.setRejectionReason(rejectionReason);
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

    /** find OUR row by id in a JSON array (the bootstrap superuser datasource bypasses RLS). */
    private JsonNode findById(JsonNode list, UUID id) {
        for (JsonNode row : list) {
            if (id.toString().equals(row.get("id").asText())) {
                return row;
            }
        }
        return null;
    }
}
