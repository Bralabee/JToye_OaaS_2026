package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the onboarding SUBMIT slice over HTTP on real Postgres 15
 * (Testcontainers): create → submit → read status, with the state machine
 * enforcing legal transitions (a second submit from VERIFYING is rejected).
 *
 * <p><strong>Hermetic gate chain.</strong> Three mandatory automatic gate beans
 * now materialise on submit (18-03 {@code FhrsGate} → FOOD_HYGIENE_RATING, 18-04
 * {@code CompaniesHouseGate} → BUSINESS_VERIFIED, 18-05 {@code AllergenCompletenessGate}
 * → ALLERGEN_DATA_COMPLETE). To keep this test off the network and deterministic
 * the two external HTTP clients are {@code @MockBean}ed so the FHRS + Companies
 * House gates degrade to MANUAL_REVIEW; with at least one mandatory gate not
 * PASSED/WAIVED the async recompute leaves the onboarding in VERIFYING — the
 * behaviour these two scenarios assert.
 *
 * <p>The fully-automatic "all gates green → auto-approve → go-live → published"
 * composition (both {@code onboarding.auto-approve} toggle states) is proven
 * separately in {@link VendorOnboardingEndToEndIntegrationTest}, which stubs the
 * clients green; those scenarios were removed from here (their manual
 * BUSINESS_VERIFIED seed collided with the row the CompaniesHouseGate now
 * materialises on submit).
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the
 * {@code @Async @Transactional} {@link GateChainRunner#runAndRecompute} runs on a
 * separate thread/connection, so the onboarding + gate rows MUST be committed to
 * be visible to it. Each test uses a fresh random tenant so rows never collide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingSubmitIntegrationTest {

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

    // Mock the external HTTP clients so the FHRS + Companies House gates never hit
    // the network: FHRS returns no match (MANUAL_REVIEW), Companies House fails
    // closed (MANUAL_REVIEW). Either alone keeps a mandatory gate un-passed so the
    // recompute leaves the onboarding in VERIFYING.
    @MockBean private FhrsClient fhrsClient;
    @MockBean private CompaniesHouseClient companiesHouseClient;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedTenantAndShop() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) " +
                        "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "Test Address");
        // One fully-labelled product so the allergen gate PASSES for real; the
        // onboarding still stays VERIFYING because the two mocked-client gates
        // below resolve to MANUAL_REVIEW.
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, shelf_life_days, durability_type, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 3, 'USE_BY', 0)",
                UUID.randomUUID(), tenantId, "SKU-" + shopId.toString().substring(0, 8), "Test Product",
                "Wheat flour, **milk**, sugar", shopId);

        // FHRS: no establishment matched -> the gate maps to MANUAL_REVIEW.
        when(fhrsClient.lookup(any(), any())).thenReturn(List.of());
        // Companies House: fail closed (as an unconfigured key would) -> MANUAL_REVIEW.
        when(companiesHouseClient.lookup(any()))
                .thenThrow(new IllegalStateException("Companies House API key not configured (test)"));
    }

    private String createBody() throws Exception {
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shopId);
        req.setCompanyNumber("12345678");
        return objectMapper.writeValueAsString(req);
    }

    private JsonNode getMe() throws Exception {
        String body = mockMvc.perform(get("/api/v1/onboarding/me")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @WithMockUser
    void createThenSubmitReadsBackVerifyingWithSubmittedAt() throws Exception {
        // create -> DRAFT (201)
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.shopId").value(shopId.toString()))
                .andExpect(jsonPath("$.id").exists());

        // submit -> VERIFYING (200)
        mockMvc.perform(post("/api/v1/onboarding/submit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty());

        // Read back via GET /me. The submit RESPONSE proved VERIFYING synchronously;
        // the mocked-client gates resolve to MANUAL_REVIEW so the async recompute
        // does not advance the onboarding. The durable, timing-independent proof is
        // that submitted_at was stamped + persisted and the onboarding has left DRAFT.
        JsonNode me = getMe();
        assertThat(me.get("submittedAt").isNull()).isFalse();
        assertThat(me.get("status").asText()).isNotEqualTo("DRAFT");
    }

    @Test
    @WithMockUser
    void resubmitFromVerifyingReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/onboarding/submit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());

        // Second submit from VERIFYING is an illegal transition -> 400.
        mockMvc.perform(post("/api/v1/onboarding/submit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());
    }
}
