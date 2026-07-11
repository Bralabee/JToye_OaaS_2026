package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the onboarding submit slice over HTTP on real Postgres 15
 * (Testcontainers): create → submit → read status, the state machine enforcing
 * legal transitions, and the {@code onboarding.auto-approve} toggle driving the
 * async recompute both ways.
 *
 * <p>The class is intentionally NOT {@code @Transactional}: the
 * {@code @Async @Transactional} {@link GateChainRunner#runAndRecompute} runs on a
 * separate thread/connection, so the onboarding + seeded gate row MUST be
 * committed to be visible to it. Each test uses a fresh random tenant so rows
 * never collide across methods. {@link org.springframework.boot.test.mock.mockito.SpyBean}
 * on {@link OnboardingProperties} toggles {@code isAutoApprove()} per test on a
 * single container.
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
    @Autowired private GateChainRunner gateChainRunner;
    @Autowired private VendorOnboardingGateRepository gateRepository;

    @SpyBean private OnboardingProperties onboardingProperties;

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
        // 18-05: a mandatory automatic ALLERGEN_DATA_COMPLETE gate now exists, so on
        // submit the gate chain evaluates the shop's catalogue. Seed one fully-labelled
        // product (V41 durability_type + shelf_life_days + ingredients) so the allergen
        // gate PASSES here — without it the (empty-catalogue) gate would FAIL and drive
        // the onboarding to ACTION_REQUIRED, breaking the auto-approve scenarios below.
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, shelf_life_days, durability_type, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 3, 'USE_BY', 0)",
                UUID.randomUUID(), tenantId, "SKU-" + shopId.toString().substring(0, 8), "Test Product",
                "Wheat flour, **milk**, sugar", shopId);
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

    /** Poll GET /me until {@code expected} is observed or the bounded deadline lapses. */
    private JsonNode awaitStatus(OnboardingState expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
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

        // read back via GET /me. The submit RESPONSE proved VERIFYING synchronously;
        // by the time we re-read, the @Async gate recompute may already have advanced
        // the onboarding (a mandatory automatic gate now exists — 18-05). The durable,
        // timing-independent proof is that submitted_at was stamped + persisted and the
        // onboarding has left DRAFT.
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

    @Test
    @WithMockUser
    void autoApproveTrueDrivesFullyPassingOnboardingToApproved() throws Exception {
        when(onboardingProperties.isAutoApprove()).thenReturn(true);
        UUID onboardingId = createSubmitAndSeedPassedMandatoryGate();

        // Kick the async recompute; no explicit APPROVE call in the test.
        gateChainRunner.runAndRecompute(onboardingId, tenantId);

        JsonNode approved = awaitStatus(OnboardingState.APPROVED);
        assertThat(approved.get("approvedAt").isNull()).isFalse();
    }

    @Test
    @WithMockUser
    void autoApproveFalseHaltsAtPendingApproval() throws Exception {
        when(onboardingProperties.isAutoApprove()).thenReturn(false);
        UUID onboardingId = createSubmitAndSeedPassedMandatoryGate();

        gateChainRunner.runAndRecompute(onboardingId, tenantId);

        JsonNode pending = awaitStatus(OnboardingState.PENDING_APPROVAL);
        // No APPROVE fired -> approvedAt stays null.
        assertThat(pending.get("approvedAt").isNull()).isTrue();
    }

    /**
     * create → submit (→ VERIFYING) then commit one mandatory PASSED gate row so
     * the recompute sees a fully-passing gate set. Returns the onboarding id.
     */
    private UUID createSubmitAndSeedPassedMandatoryGate() throws Exception {
        String created = mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID onboardingId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post("/api/v1/onboarding/submit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"));

        // Commit one mandatory PASSED gate row (bootstrap role bypasses RLS).
        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(tenantId);
        gate.setOnboardingId(onboardingId);
        gate.setGateType(GateType.BUSINESS_VERIFIED);
        gate.setStatus(GateStatus.PASSED);
        gate.setMandatory(true);
        gateRepository.saveAndFlush(gate);

        return onboardingId;
    }
}
