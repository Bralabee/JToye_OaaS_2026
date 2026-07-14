package uk.jtoye.core.onboarding;

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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ONBD-02 proof on real Postgres 15 (Testcontainers): the vendor-facing
 * {@code POST /onboarding/company-number} endpoint corrects the onboarding
 * company number, re-validated exactly like create and gated to the states where
 * a vendor is still building / fixing the application.
 *
 * <ul>
 *   <li>update in DRAFT / ACTION_REQUIRED → 200 and the persisted value is the
 *       normalised (trimmed/uppercased) company number;</li>
 *   <li>a blank/whitespace value in DRAFT persists NULL (sole trader), matching
 *       create semantics;</li>
 *   <li>update outside DRAFT/ACTION_REQUIRED (e.g. VERIFYING) → RFC 7807 400 with
 *       the stored value unchanged (a data edit fires no state-machine event, so it
 *       never touches {@code status}/{@code Shop.published});</li>
 *   <li>a malformed value → RFC 7807 400 from bean-validation at the boundary,
 *       before the service is called (garbage never reaches the Companies House
 *       client — threat T-21-01-01).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingCompanyNumberUpdateIntegrationTest {

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

    // --- Update succeeds in DRAFT / ACTION_REQUIRED (normalised) ----------------------

    @Test
    @WithMockUser
    void updateInDraft_200AndNormalisedPersisted() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.DRAFT, null);

        mockMvc.perform(post("/api/v1/onboarding/company-number")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyNumber\":\"  ab123456  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyNumber").value("AB123456"));

        assertThat(dbCompanyNumber(onboardingId)).isEqualTo("AB123456");
    }

    @Test
    @WithMockUser
    void updateInActionRequired_200AndPersisted() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.ACTION_REQUIRED, "OLD00001");

        mockMvc.perform(post("/api/v1/onboarding/company-number")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyNumber\":\"cd654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyNumber").value("CD654321"));

        assertThat(dbCompanyNumber(onboardingId)).isEqualTo("CD654321");
    }

    // --- Blank = sole trader (persists null) -----------------------------------------

    @Test
    @WithMockUser
    void blankCompanyNumberInDraftPersistsNull() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.DRAFT, "SC999999");

        mockMvc.perform(post("/api/v1/onboarding/company-number")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyNumber\":\"   \"}"))
                .andExpect(status().isOk());

        assertThat(dbCompanyNumber(onboardingId)).isNull();
    }

    // --- Out-of-window state is rejected (RFC 7807), value unchanged -----------------

    @Test
    @WithMockUser
    void updateInVerifyingIs400AndUnchanged() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING, "SC111111");

        mockMvc.perform(post("/api/v1/onboarding/company-number")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyNumber\":\"NW000002\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertThat(dbCompanyNumber(onboardingId)).isEqualTo("SC111111");
    }

    // --- Malformed value is a clean 400 before the service ---------------------------

    @Test
    @WithMockUser
    void malformedCompanyNumberIs400BeforeService() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.DRAFT, "SC000001");

        mockMvc.perform(post("/api/v1/onboarding/company-number")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyNumber\":\"!!not-valid!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        // Bean-validation rejected it at the boundary — the stored value is untouched
        // (the garbage never reached the service or the Companies House client).
        assertThat(dbCompanyNumber(onboardingId)).isEqualTo("SC000001");
    }

    // --- helpers ---------------------------------------------------------------------

    private UUID seedOnboarding(OnboardingState state, String companyNumber) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setStatus(state);
        onboarding.setCompanyNumber(companyNumber);
        return onboardingRepository.saveAndFlush(onboarding).getId();
    }

    private String dbCompanyNumber(UUID onboardingId) {
        return jdbc.queryForObject(
                "SELECT company_number FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }
}
