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
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the go-live loop on real Postgres 15 (Testcontainers): the guarded
 * {@code POST /onboarding/go-live} publishes the shop only when the mandatory +
 * allergen gates are PASSED, is rejected (400) while the allergen gate is not
 * PASSED, and the {@code Shop.published} sole-writer invariant holds
 * ({@code updateShop} cannot publish).
 *
 * <p>Not {@code @Transactional}: go-live drives the state machine + the
 * {@code ShopService.setPublished} side effect through the service's own
 * transaction, so the seeded onboarding + gate rows MUST be committed to be visible
 * to it. Each test uses a fresh random tenant so rows never collide. Gate rows are
 * seeded directly via the repository (bootstrap role bypasses RLS) so the guard is
 * exercised deterministically, independent of the async gate chain / gate beans.
 *
 * <p>N4: {@code Shop.published} is a nullable {@code Boolean} — published assertions
 * use Boolean semantics (never a primitive {@code false}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingGoLiveIntegrationTest {

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
    @Autowired private VendorOnboardingGateRepository gateRepository;
    @Autowired private ShopService shopService;

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
    }

    @Test
    @WithMockUser
    void goLiveBlockedWhileAllergenGateNotPassed_returns400AndShopStaysUnpublished() throws Exception {
        UUID onboardingId = seedApprovedOnboarding();
        // Mandatory gate present but the allergen gate has NOT passed.
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PENDING, true);

        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());

        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.APPROVED.name());
    }

    @Test
    @WithMockUser
    void goLiveWithAllGatesPassed_publishesShopAndReachesLive() throws Exception {
        UUID onboardingId = seedApprovedOnboarding();
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED, true);

        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.LIVE.name());
    }

    @Test
    @WithMockUser
    void updateShopCannotPublish_soleWriterInvariantHolds() {
        // A direct updateShop with published=true must NOT flip the shop live:
        // ShopService.setPublished (reached only from the GO_LIVE side effect) is the
        // sole authorised writer of published=true (threat T-18-05-T).
        CreateShopRequest req = new CreateShopRequest();
        req.setName("shop-" + shopId);
        req.setAddress("1 Test Street");
        req.setPublished(true);

        TenantContext.set(tenantId);
        try {
            shopService.updateShop(shopId, req);
        } finally {
            TenantContext.clear();
        }

        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
    }

    /** Persist an APPROVED onboarding for {@link #tenantId}/{@link #shopId}. */
    private UUID seedApprovedOnboarding() {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(OnboardingState.APPROVED);
        return onboardingRepository.saveAndFlush(onboarding).getId();
    }

    private void seedGate(UUID onboardingId, GateType type, GateStatus gateStatus, boolean mandatory) {
        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(tenantId);
        gate.setOnboardingId(onboardingId);
        gate.setGateType(type);
        gate.setStatus(gateStatus);
        gate.setMandatory(mandatory);
        gateRepository.saveAndFlush(gate);
    }

    private Boolean publishedFlagOf(UUID id) {
        return jdbc.queryForObject("SELECT published FROM shops WHERE id = ?", Boolean.class, id);
    }

    private String statusOf(UUID onboardingId) {
        return jdbc.queryForObject("SELECT status FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }
}
