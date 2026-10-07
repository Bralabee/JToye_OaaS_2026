package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.CompanyProfile;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.onboarding.client.FhrsEstablishment;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #789 / D-10 / D-12 / D-20: no shop goes live unless its customers can see who the seller is
 * and how to email them. The onboarding state machine is the ONLY writer of
 * {@code Shop.published} (Phase 18), so the guarantee lives in the GO_LIVE guard: it requires a
 * PASSED {@code TRADER_IDENTITY} gate row, exactly as it requires a PASSED
 * {@code ALLERGEN_DATA_COMPLETE} row.
 *
 * <p>The arms, on real Postgres 15:
 * <ul>
 *   <li>the gate's own predicate: no identity, no shop email, a COMPANY with no company number
 *       each refuse go-live and leave a FAILED row naming what to add; the passing shapes
 *       (company with number, sole trader without, email with no phone) publish;</li>
 *   <li>VACUITY (RESEARCH Pitfall 7): an onboarding approved before this gate existed has NO
 *       {@code TRADER_IDENTITY} row. Go-live must materialise the row rather than pass on its
 *       absence, and must not leave the vendor stuck once the details exist;</li>
 *   <li>WAIVED: a human waiver never publishes a shop without the statutory details, through
 *       the admin resolve path AND at the guard itself (the guard arm bypasses the service's
 *       refresh, so it is the arm that fails if the guard ever accepts WAIVED or drops the
 *       explicit requirement);</li>
 *   <li>the submit path: a fresh vendor with no business details gets a FAILED row at submit.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: go-live runs in the service's own transaction and the gate
 * chain runs on an async worker, so seeded rows must be committed. Each test uses a fresh
 * random tenant. Seeds go in as the Testcontainers superuser (RLS bypassed); the production
 * RLS posture of {@code trader_identity} is proven by {@code TraderIdentityRlsIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class TraderIdentityGateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String LEGAL_NAME = "Adebayo Foods Ltd";
    private static final String SHOP_EMAIL = "kitchen@adebayo-foods.example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VendorOnboardingRepository onboardingRepository;
    @Autowired private VendorOnboardingGateRepository gateRepository;
    @Autowired private VendorOnboardingStateMachineService stateMachineService;

    @MockitoBean private FhrsClient fhrsClient;
    @MockitoBean private CompaniesHouseClient companiesHouseClient;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedTenantShopAndLabelledProduct() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        // No email and no phone: each arm adds exactly the details it is about.
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
        // Fully labelled, so the go-live ALLERGEN_DATA_COMPLETE refresh passes and the only
        // thing that can refuse go-live in these arms is the seller-details gate.
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, shelf_life_days, durability_type, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 3, 'USE_BY', 0)",
                UUID.randomUUID(), tenantId, "SKU-" + shopId.toString().substring(0, 8), "Test Product",
                "Wheat flour, **milk**, sugar", shopId);

        when(fhrsClient.lookup(any(), any()))
                .thenReturn(List.of(new FhrsEstablishment("123456", "5", "FHRS")));
        when(companiesHouseClient.lookup(any()))
                .thenReturn(Optional.of(new CompanyProfile("12345678", "active")));
    }

    // --- The gate's predicate, through POST /onboarding/go-live -------------------------

    @Test
    void noTraderIdentity_goLiveRefused_rowFailedNamingBusinessDetails() throws Exception {
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed("12345678");
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.PASSED); // stale: must be re-checked
        setShopEmail(SHOP_EMAIL);

        goLive().andExpect(status().isBadRequest());

        assertNotPublished(onboardingId);
        assertThat(gateStatus(onboardingId)).isEqualTo("FAILED");
        assertThat(gateReason(onboardingId)).contains("business details");
    }

    @Test
    void companyWithNumberAndShopEmail_goLivePublishes_evidenceNamesFieldsNotValues() throws Exception {
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed("12345678");
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.PENDING);
        seedTraderIdentity("COMPANY");
        setShopEmail(SHOP_EMAIL);

        goLive().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
        assertThat(gateStatus(onboardingId)).isEqualTo("PASSED");
        String evidence = gateEvidence(onboardingId);
        assertThat(evidence).contains("legal_name").contains("shop_email").contains("company_number");
        // The evidence records WHICH details were present, never the details themselves.
        assertThat(evidence).doesNotContain(LEGAL_NAME).doesNotContain(SHOP_EMAIL).doesNotContain("12345678");
    }

    @Test
    void shopWithoutEmail_goLiveRefused_reasonNamesTheShopEmail() throws Exception {
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed("12345678");
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.PENDING);
        seedTraderIdentity("COMPANY");
        jdbc.update("UPDATE shops SET phone = ? WHERE id = ?", "0121 496 0000", shopId); // a phone is not enough

        goLive().andExpect(status().isBadRequest());

        assertNotPublished(onboardingId);
        assertThat(gateStatus(onboardingId)).isEqualTo("FAILED");
        assertThat(gateReason(onboardingId)).contains("email address to your shop");
    }

    @Test
    void shopWithEmailAndNoPhone_goLivePublishes() throws Exception {
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed("12345678");
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.PENDING);
        seedTraderIdentity("COMPANY");
        setShopEmail(SHOP_EMAIL);
        assertThat(jdbc.queryForObject("SELECT phone FROM shops WHERE id = ?", String.class, shopId)).isNull();

        goLive().andExpect(status().isOk());

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void companyWithoutCompanyNumber_goLiveRefused_reasonNamesTheCompanyNumber() throws Exception {
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed(null);
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.PENDING);
        seedTraderIdentity("COMPANY");
        setShopEmail(SHOP_EMAIL);

        goLive().andExpect(status().isBadRequest());

        assertNotPublished(onboardingId);
        assertThat(gateReason(onboardingId)).contains("company number");
    }

    @Test
    void soleTraderWithoutCompanyNumber_goLivePublishes() throws Exception {
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed(null);
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.PENDING);
        seedTraderIdentity("SOLE_TRADER");
        setShopEmail(SHOP_EMAIL);

        goLive().andExpect(status().isOk());

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
    }

    // --- Vacuity: an onboarding approved before the gate existed --------------------------

    @Test
    void onboardingApprovedBeforeTheGateExisted_isNeitherPassedVacuouslyNorStuck() throws Exception {
        // The pre-deploy shape: APPROVED, every OTHER mandatory gate PASSED, and no
        // TRADER_IDENTITY row at all (materialise() runs only at submit).
        UUID onboardingId = seedApprovedWithEveryOtherGatePassed("12345678");
        assertThat(gateRowCount(onboardingId)).isZero();

        goLive().andExpect(status().isBadRequest());

        assertNotPublished(onboardingId);
        // The refused go-live materialised the gate, so the vendor can see what to add.
        assertThat(gateStatus(onboardingId)).isEqualTo("FAILED");
        assertThat(gateReason(onboardingId)).contains("business details");

        // The vendor adds the details; the next go-live re-evaluates and publishes.
        seedTraderIdentity("COMPANY");
        setShopEmail(SHOP_EMAIL);

        goLive().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
        assertThat(gateStatus(onboardingId)).isEqualTo("PASSED");
        assertThat(gateRowCount(onboardingId)).isEqualTo(1);
    }

    // --- WAIVED never publishes ----------------------------------------------------------

    @Test
    void adminWaivesTheGate_goLiveStillRefusedWithoutTheDetails() throws Exception {
        UUID onboardingId = seedOnboarding(OnboardingState.VERIFYING, "12345678");
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED);
        seedGate(onboardingId, GateType.TRADER_IDENTITY, GateStatus.FAILED);
        setShopEmail(SHOP_EMAIL); // the identity itself is missing

        mockMvc.perform(post("/api/v1/onboarding/admin/" + onboardingId + "/gates/TRADER_IDENTITY/resolve")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"WAIVE\",\"reason\":\"waived by a reviewer\"}"))
                .andExpect(status().isOk());
        assertThat(gateStatus(onboardingId)).isEqualTo("WAIVED");

        // The waiver satisfies APPROVE (every mandatory gate PASSED or WAIVED) ...
        awaitDbStatus(onboardingId, "PENDING_APPROVAL");
        mockMvc.perform(post("/api/v1/onboarding/admin/" + onboardingId + "/approve").with(adminJwt()))
                .andExpect(status().isOk());
        assertThat(dbStatus(onboardingId)).isEqualTo("APPROVED");

        // ... but never GO_LIVE.
        goLive().andExpect(status().isBadRequest());

        assertNotPublished(onboardingId);
    }

    @Test
    void goLiveGuard_refusesWaivedAndAbsentTraderIdentity_acceptsPassed() {
        // The guard ALONE, with no service refresh in front of it: this is the arm that fails
        // if the guard ever accepts WAIVED or stops requiring the row explicitly.
        UUID waived = seedApprovedWithEveryOtherGatePassed("12345678");
        seedGate(waived, GateType.TRADER_IDENTITY, GateStatus.WAIVED);
        assertThatThrownBy(() -> stateMachineService.sendEvent(waived, OnboardingState.APPROVED, OnboardingEvent.GO_LIVE))
                .isInstanceOf(InvalidStateTransitionException.class);

        newTenantAndShop();
        UUID absent = seedApprovedWithEveryOtherGatePassed("12345678");
        assertThatThrownBy(() -> stateMachineService.sendEvent(absent, OnboardingState.APPROVED, OnboardingEvent.GO_LIVE))
                .isInstanceOf(InvalidStateTransitionException.class);

        // Control: the same guard admits a PASSED row, so the two refusals above are about
        // the trader-identity row and nothing else.
        newTenantAndShop();
        UUID passed = seedApprovedWithEveryOtherGatePassed("12345678");
        seedGate(passed, GateType.TRADER_IDENTITY, GateStatus.PASSED);
        assertThat(stateMachineService.sendEvent(passed, OnboardingState.APPROVED, OnboardingEvent.GO_LIVE))
                .isEqualTo(OnboardingState.LIVE);
    }

    // --- The submit path ----------------------------------------------------------------

    @Test
    void submitWithoutBusinessDetails_materialisesAFailedTraderIdentityGate() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"MARKETPLACE\",\"shopId\":\"" + shopId + "\",\"companyNumber\":\"12345678\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/onboarding/submit").with(adminJwt()))
                .andExpect(status().isOk());

        UUID onboardingId = jdbc.queryForObject(
                "SELECT id FROM vendor_onboarding WHERE tenant_id = ?", UUID.class, tenantId);
        awaitDbStatus(onboardingId, "ACTION_REQUIRED");
        assertThat(gateStatus(onboardingId)).isEqualTo("FAILED");
        assertThat(gateReason(onboardingId)).contains("business details");
        assertThat(jdbc.queryForObject(
                "SELECT mandatory FROM vendor_onboarding_gate WHERE onboarding_id = ? AND gate_type = 'TRADER_IDENTITY'",
                Boolean.class, onboardingId)).isTrue();
    }

    // --- helpers -------------------------------------------------------------------------

    private RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private org.springframework.test.web.servlet.ResultActions goLive() throws Exception {
        return mockMvc.perform(post("/api/v1/onboarding/go-live").with(adminJwt()));
    }

    private void newTenantAndShop() {
        seedTenantShopAndLabelledProduct();
    }

    private UUID seedApprovedWithEveryOtherGatePassed(String companyNumber) {
        UUID onboardingId = seedOnboarding(OnboardingState.APPROVED, companyNumber);
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED);
        seedGate(onboardingId, GateType.FOOD_HYGIENE_RATING, GateStatus.PASSED);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED);
        return onboardingId;
    }

    private UUID seedOnboarding(OnboardingState state, String companyNumber) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        onboarding.setCompanyNumber(companyNumber);
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

    private void seedTraderIdentity(String entityType) {
        jdbc.update("INSERT INTO trader_identity (id, tenant_id, legal_name, entity_type, address_line1, "
                        + "address_city, address_postcode) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, LEGAL_NAME, entityType, "12 Digbeth High Street", "Birmingham", "B5 6DY");
    }

    private void setShopEmail(String email) {
        jdbc.update("UPDATE shops SET email = ? WHERE id = ?", email, shopId);
    }

    private void assertNotPublished(UUID onboardingId) {
        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
        assertThat(dbStatus(onboardingId)).isEqualTo("APPROVED");
    }

    private int gateRowCount(UUID onboardingId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding_gate WHERE onboarding_id = ? AND gate_type = 'TRADER_IDENTITY'",
                Integer.class, onboardingId);
        return n == null ? 0 : n;
    }

    private String gateStatus(UUID onboardingId) {
        return jdbc.queryForObject(
                "SELECT status FROM vendor_onboarding_gate WHERE onboarding_id = ? AND gate_type = 'TRADER_IDENTITY'",
                String.class, onboardingId);
    }

    private String gateReason(UUID onboardingId) {
        return jdbc.queryForObject(
                "SELECT reason FROM vendor_onboarding_gate WHERE onboarding_id = ? AND gate_type = 'TRADER_IDENTITY'",
                String.class, onboardingId);
    }

    private String gateEvidence(UUID onboardingId) {
        return jdbc.queryForObject(
                "SELECT evidence::text FROM vendor_onboarding_gate WHERE onboarding_id = ? AND gate_type = 'TRADER_IDENTITY'",
                String.class, onboardingId);
    }

    private Boolean publishedFlagOf(UUID id) {
        return jdbc.queryForObject("SELECT published FROM shops WHERE id = ?", Boolean.class, id);
    }

    private String dbStatus(UUID onboardingId) {
        return jdbc.queryForObject("SELECT status FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }

    /** Poll the committed onboarding status until {@code expected} or a bounded deadline. */
    private void awaitDbStatus(UUID onboardingId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = dbStatus(onboardingId);
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out awaiting onboarding status " + expected + "; last=" + last);
    }
}
