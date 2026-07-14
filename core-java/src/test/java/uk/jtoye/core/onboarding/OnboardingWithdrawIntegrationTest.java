package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ONBD-01 proof on real Postgres 15 (Testcontainers): the vendor-facing
 * {@code POST /onboarding/withdraw} endpoint drives the canonical
 * {@code WITHDRAW} transition end-to-end through the REAL state machine and the
 * V43 schema. The state-machine layer (WITHDRAW legal from all five pre-live
 * states; illegal from terminal states) is already proven by
 * {@code VendorOnboardingStateMachineServiceTest}; this test proves the endpoint
 * + service wiring, the RFC 7807 rejection of a terminal source, and the
 * sole-writer invariant (withdraw never touches {@code Shop.published}).
 *
 * <ul>
 *   <li>withdraw from DRAFT / ACTION_REQUIRED → 200 and the persisted status is
 *       terminal WITHDRAWN;</li>
 *   <li>withdraw from a terminal source (REJECTED) → RFC 7807 400
 *       ({@code application/problem+json}) with the status left unchanged;</li>
 *   <li>withdraw is a no-side-effect status change — a shop that was
 *       {@code published=true} stays published (the state machine remains the
 *       sole writer of {@code Shop.published}).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingWithdrawIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VendorOnboardingRepository onboardingRepository;

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

    // --- Withdraw succeeds from a pre-live state ------------------------------------

    @Test
    @WithMockUser
    void withdrawFromDraft_200AndStatusWithdrawn() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.DRAFT);

        mockMvc.perform(post("/api/v1/onboarding/withdraw")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        assertThat(dbStatus(onboardingId)).isEqualTo("WITHDRAWN");
    }

    @Test
    @WithMockUser
    void withdrawFromActionRequired_200AndStatusWithdrawn() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.ACTION_REQUIRED);

        mockMvc.perform(post("/api/v1/onboarding/withdraw")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        assertThat(dbStatus(onboardingId)).isEqualTo("WITHDRAWN");
    }

    // --- Withdraw from a terminal source is rejected (RFC 7807) ----------------------

    @Test
    @WithMockUser
    void withdrawFromTerminalRejected_400ProblemJsonAndStateUnchanged() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.REJECTED);

        mockMvc.perform(post("/api/v1/onboarding/withdraw")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        // The terminal state is untouched — the state machine vetoed the transition.
        assertThat(dbStatus(onboardingId)).isEqualTo("REJECTED");
    }

    // --- Withdraw is a no-side-effect status change (sole-writer invariant) ----------

    @Test
    @WithMockUser
    void withdrawDoesNotFlipShopPublished() throws Exception {
        // A shop that is already published=true: withdraw must NOT write published
        // (WITHDRAW falls into the transition default arm; only GO_LIVE/SUSPEND/
        // REINSTATE may touch published — the state machine stays the sole writer).
        jdbc.update("UPDATE shops SET published = true WHERE id = ?", shopId);
        UUID onboardingId = seedOnboarding(OnboardingState.APPROVED);

        mockMvc.perform(post("/api/v1/onboarding/withdraw")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        assertThat(dbStatus(onboardingId)).isEqualTo("WITHDRAWN");
        Boolean published = jdbc.queryForObject(
                "SELECT published FROM shops WHERE id = ?", Boolean.class, shopId);
        assertThat(published).as("withdraw must not flip Shop.published").isTrue();
    }

    // --- helpers ---------------------------------------------------------------------

    private UUID seedOnboarding(OnboardingState state) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
        onboarding.setSubmittedAt(OffsetDateTime.now().minusHours(1));
        return onboardingRepository.saveAndFlush(onboarding).getId();
    }

    private String dbStatus(UUID onboardingId) {
        return jdbc.queryForObject(
                "SELECT status FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }
}
