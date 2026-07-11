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
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.CompanyProfile;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.onboarding.client.FhrsEstablishment;
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-gate closure proof for the vendor-onboarding first slice, on real
 * Postgres 15 (Testcontainers): a vendor submits, all THREE mandatory automatic
 * gates evaluate green, and — with {@code onboarding.auto-approve=true} — the
 * onboarding reaches {@code APPROVED} with <strong>no manual/admin APPROVE call
 * anywhere in the test</strong>, then go-live publishes the shop
 * ({@code Shop.published = true}). The false toggle documents the guard: the same
 * green onboarding halts at {@code PENDING_APPROVAL} and go-live is rejected.
 *
 * <p>This is the headline "go live without manual review" capability proven end
 * to end, with no admin-approve crutch masking the gap (HIGH-1). The two external
 * HTTP clients are stubbed green so the fully-automatic path is deterministic:
 * <ul>
 *   <li>{@code @MockBean FhrsClient} → one FHRS establishment rated 5 (≥ the
 *       config min-rating 2) → FOOD_HYGIENE_RATING PASSED;</li>
 *   <li>{@code @MockBean CompaniesHouseClient} → an {@code active} company profile
 *       → BUSINESS_VERIFIED PASSED;</li>
 *   <li>a seeded, fully-labelled product (V41 durability/shelf-life/ingredients)
 *       → ALLERGEN_DATA_COMPLETE PASSED for real (no stub).</li>
 * </ul>
 *
 * <p>{@code @SpyBean OnboardingProperties} toggles {@code isAutoApprove()} per test
 * on a single container. The class is intentionally NOT {@code @Transactional}: the
 * {@code @Async @Transactional} recompute runs on a separate thread/connection, so
 * the onboarding + gate rows MUST be committed to be visible to it. Each test uses
 * a fresh random tenant so rows never collide. {@code Shop.published} is a nullable
 * {@code Boolean}, so published assertions use Boolean semantics (never a primitive
 * {@code false}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class VendorOnboardingEndToEndIntegrationTest {

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

    @MockBean private FhrsClient fhrsClient;
    @MockBean private CompaniesHouseClient companiesHouseClient;
    @SpyBean private OnboardingProperties onboardingProperties;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedGreenVendor() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
        // Fully-labelled product so the ALLERGEN_DATA_COMPLETE gate passes for real.
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, shelf_life_days, durability_type, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 3, 'USE_BY', 0)",
                UUID.randomUUID(), tenantId, "SKU-" + shopId.toString().substring(0, 8), "Test Product",
                "Wheat flour, **milk**, sugar", shopId);

        // Stub the external clients GREEN so all three gates PASS deterministically.
        when(fhrsClient.lookup(any(), any()))
                .thenReturn(List.of(new FhrsEstablishment("123456", "5", "FHRS")));
        when(companiesHouseClient.lookup(any()))
                .thenReturn(Optional.of(new CompanyProfile("12345678", "active")));
    }

    @Test
    @WithMockUser
    void fullyAutomaticPath_allGatesGreen_autoApprovesThenGoLivePublishes() throws Exception {
        // Headline "go live without manual review": auto-approve ON.
        when(onboardingProperties.isAutoApprove()).thenReturn(true);

        UUID onboardingId = createAndSubmit();
        // Drive the recompute deterministically (also kicked async by submit()); it
        // evaluates the three PENDING gate rows green, fires GATES_PASSED and — because
        // auto-approve is ON — APPROVE. NO admin/service APPROVE call is made anywhere
        // in this test: the auto-approve recompute is the ONLY thing that advances past
        // PENDING_APPROVAL.
        gateChainRunner.runAndRecompute(onboardingId, tenantId);

        JsonNode approved = awaitStatus(OnboardingState.APPROVED);
        // approvedAt stamped -> the onboarding auto-reached APPROVED with no manual review.
        assertThat(approved.get("approvedAt").isNull()).isFalse();
        // All three mandatory gate rows materialised on submit and evaluated PASSED
        // (proves the registry wired all three concrete gate beans).
        assertAllThreeGatesPassed(approved);

        // Vendor go-live -> LIVE, and the shop is published.
        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.LIVE.name());
    }

    @Test
    @WithMockUser
    void autoApproveFalse_sameGreenOnboardingHaltsAtPendingApproval_goLiveRejected() throws Exception {
        // Same fully-green onboarding, but auto-approve OFF: there is no admin-approve
        // endpoint in this slice, so the onboarding must halt at PENDING_APPROVAL and
        // the shop must stay unpublished.
        when(onboardingProperties.isAutoApprove()).thenReturn(false);

        UUID onboardingId = createAndSubmit();
        gateChainRunner.runAndRecompute(onboardingId, tenantId);

        JsonNode pending = awaitStatus(OnboardingState.PENDING_APPROVAL);
        // No APPROVE fired -> approvedAt stays null.
        assertThat(pending.get("approvedAt").isNull()).isTrue();
        assertAllThreeGatesPassed(pending);

        // go-live is only valid from APPROVED; from PENDING_APPROVAL it is an illegal
        // transition -> 400, and the shop stays unpublished.
        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());

        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.PENDING_APPROVAL.name());
    }

    /** create (DRAFT) → submit (VERIFYING); returns the onboarding id. */
    private UUID createAndSubmit() throws Exception {
        String created = mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        UUID onboardingId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post("/api/v1/onboarding/submit")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"));
        return onboardingId;
    }

    private String createBody() throws Exception {
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shopId);
        req.setCompanyNumber("12345678");
        return objectMapper.writeValueAsString(req);
    }

    /** Poll GET /me until {@code expected} is observed or a bounded deadline lapses. */
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

    /**
     * Assert the onboarding carries exactly the three mandatory gate types, each
     * PASSED — proving all three gate beans (BUSINESS_VERIFIED, FOOD_HYGIENE_RATING,
     * ALLERGEN_DATA_COMPLETE) were materialised on submit and evaluated green.
     */
    private void assertAllThreeGatesPassed(JsonNode me) {
        Map<String, String> byType = new HashMap<>();
        me.get("gates").forEach(g -> byType.put(g.get("gateType").asText(), g.get("status").asText()));
        assertThat(byType).containsOnlyKeys(
                GateType.BUSINESS_VERIFIED.name(),
                GateType.FOOD_HYGIENE_RATING.name(),
                GateType.ALLERGEN_DATA_COMPLETE.name());
        assertThat(byType.values()).containsOnly(GateStatus.PASSED.name());
    }

    private Boolean publishedFlagOf(UUID id) {
        return jdbc.queryForObject("SELECT published FROM shops WHERE id = ?", Boolean.class, id);
    }

    private String statusOf(UUID onboardingId) {
        return jdbc.queryForObject("SELECT status FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }
}
