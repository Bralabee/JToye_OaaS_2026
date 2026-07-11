package uk.jtoye.core.onboarding;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WR-02 proof on real Postgres 15 (Testcontainers): {@code companyNumber} is
 * validated + normalised at the {@code POST /onboarding} boundary.
 *
 * <ul>
 *   <li>an over-length value is a clean 400 (Bean Validation), not the misleading
 *       409 "Duplicate Entry" the V43 {@code VARCHAR(32)} overflow used to produce;</li>
 *   <li>a valid value is stored trimmed + uppercased, so the persisted aggregate
 *       matches what {@code CompaniesHouseGate} looks up;</li>
 *   <li>a blank value persists as NULL (sole trader → gate WAIVED).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingCompanyNumberValidationIntegrationTest {

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

    private String createBody(String companyNumber) throws Exception {
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shopId);
        req.setCompanyNumber(companyNumber);
        return objectMapper.writeValueAsString(req);
    }

    @Test
    @WithMockUser
    void overLengthCompanyNumberIsRejected400NotDuplicate409() throws Exception {
        String tooLong = "A".repeat(40); // > V43 VARCHAR(32) and > the 2-10 pattern bound
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(tooLong)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void companyNumberIsNormalisedTrimAndUppercaseOnPersist() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("  sc123456  ")))
                .andExpect(status().isCreated());

        String stored = jdbc.queryForObject(
                "SELECT company_number FROM vendor_onboarding WHERE tenant_id = ?", String.class, tenantId);
        assertThat(stored).isEqualTo("SC123456");
    }

    @Test
    @WithMockUser
    void blankCompanyNumberPersistsAsNull() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("   ")))
                .andExpect(status().isCreated());

        String stored = jdbc.queryForObject(
                "SELECT company_number FROM vendor_onboarding WHERE tenant_id = ?", String.class, tenantId);
        assertThat(stored).isNull();
    }
}
