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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CR-03 proof on real Postgres 15 (Testcontainers): ACTION_REQUIRED is no longer a
 * dead end. {@code POST /onboarding/resubmit} fires RESUBMIT (ACTION_REQUIRED →
 * VERIFYING), resets the flagged (FAILED/MANUAL_REVIEW) gate rows to PENDING, and
 * re-kicks the async gate chain — so a vendor who fixes the flagged data can advance.
 *
 * <ul>
 *   <li><strong>Happy re-run</strong>: an ACTION_REQUIRED onboarding whose only
 *       failure was the allergen gate advances to PENDING_APPROVAL once the product
 *       data is fixed and the checks are re-run (the two already-PASSED client gates
 *       are trusted, never re-run).</li>
 *   <li><strong>Guard veto</strong>: RESUBMIT from any non-ACTION_REQUIRED state
 *       (here DRAFT) is an illegal transition → 400, preserving the state-machine
 *       throw-on-veto contract.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the {@code @Async @Transactional} recompute runs on
 * a separate thread/connection, so seeded rows MUST be committed to be visible to it.
 * Gate rows are seeded directly via the repositories (bootstrap role bypasses RLS).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingResubmitIntegrationTest {

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
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
        // A fully-labelled product so the allergen gate PASSES on the re-run.
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, shelf_life_days, durability_type, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 3, 'USE_BY', 0)",
                UUID.randomUUID(), tenantId, "SKU-" + shopId.toString().substring(0, 8), "Test Product",
                "Wheat flour, **milk**, sugar", shopId);
    }

    @Test
    @WithMockUser
    void resubmitFromActionRequired_reEvaluatesFlaggedGate_andAdvances() throws Exception {
        // ACTION_REQUIRED onboarding: the two client gates already PASSED; only the
        // allergen gate FAILED (the product data has since been fixed above).
        UUID onboardingId = seedOnboarding(OnboardingState.ACTION_REQUIRED);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.FAILED);

        // Re-run the checks -> RESUBMIT (ACTION_REQUIRED -> VERIFYING).
        mockMvc.perform(post("/api/v1/onboarding/resubmit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"));

        // The afterCommit gate chain re-evaluates the reset allergen gate (now PASSED),
        // so all mandatory gates pass and the onboarding advances to PENDING_APPROVAL
        // (auto-approve defaults false).
        JsonNode pending = awaitStatus(OnboardingState.PENDING_APPROVAL);
        Map<String, String> byType = gateStatuses(pending);
        assertThat(byType.get(GateType.ALLERGEN_DATA_COMPLETE.name())).isEqualTo(GateStatus.PASSED.name());
        assertThat(byType.get(GateType.BUSINESS_VERIFIED.name())).isEqualTo(GateStatus.PASSED.name());
        assertThat(byType.get(GateType.FOOD_HYGIENE_RATING.name())).isEqualTo(GateStatus.PASSED.name());
    }

    @Test
    @WithMockUser
    void resubmitFromDraftIsIllegalTransition_returns400() throws Exception {
        // Create leaves the onboarding in DRAFT; RESUBMIT is only legal from
        // ACTION_REQUIRED, so the state machine vetoes it -> 400.
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shopId);
        req.setCompanyNumber("12345678");
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/onboarding/resubmit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());
    }

    private UUID seedOnboarding(OnboardingState state) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
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

    private JsonNode awaitStatus(OnboardingState expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getMe();
            if (expected.name().equals(last.get("status").asText())) {
                return last;
            }
            Thread.sleep(100);
        }
        fail("Timed out awaiting status " + expected + "; last status="
                + (last == null ? "n/a" : last.get("status").asText()));
        return null;
    }

    private JsonNode getMe() throws Exception {
        String body = mockMvc.perform(get("/api/v1/onboarding/me")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private Map<String, String> gateStatuses(JsonNode me) {
        Map<String, String> byType = new HashMap<>();
        me.get("gates").forEach(g -> byType.put(g.get("gateType").asText(), g.get("status").asText()));
        return byType;
    }
}
