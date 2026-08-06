package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.PublishStateNotAcceptedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    /**
     * Production-shaped auth: a UUID-subject Keycloak JWT with the realm-admin authority
     * (implicit GROUP_ADMIN). Replaces the pre-Phase-23 {@code WithMockUser}, whose non-JWT
     * principal the fail-closed {@code ShopAccessService} (23-08) now denies. Go-live itself
     * is not shop-gated (it drives the state machine + the sole-writer {@code setPublished}),
     * so these methods pass on any authenticated principal; the JWT is simply the production
     * auth shape.
     */
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "operator@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    /**
     * Set a UUID-subject realm-admin (implicit GROUP_ADMIN) directly on the SecurityContext,
     * mirroring {@code ShopAccessEnforcementIntegrationTest.authenticate(sub, realmAdmin=true)}.
     * Used by the non-MockMvc {@code updateShopCannotPublish} test, which calls
     * {@code ShopService.updateShop} directly and so must cross the fail-closed shop gate as a
     * genuine UUID-subject principal rather than the old {@code WithMockUser}.
     */
    private static void authenticateAsAdmin() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", "operator@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
    }

    @Test
    void goLiveBlockedWhileAllergenGateNotPassed_returns400AndShopStaysUnpublished() throws Exception {
        UUID onboardingId = seedApprovedOnboarding();
        // Mandatory gate present but the allergen gate has NOT passed.
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PENDING, true);

        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());

        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.APPROVED.name());
    }

    @Test
    void goLiveWithAllGatesPassed_publishesShopAndReachesLive() throws Exception {
        UUID onboardingId = seedApprovedOnboarding();
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED, true);
        // WR-03: go-live re-evaluates the allergen gate against current products, so the
        // catalogue must be fully labelled for the PASSED row to survive the fresh check.
        seedCompliantProduct();

        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.LIVE.name());
    }

    @Test
    void goLiveReEvaluatesAllergenGate_blockedWhenProductNowIncompleteDespiteStalePassedRow() throws Exception {
        // WR-03 TOCTOU: the stored allergen row is stale PASSED, but a product added
        // after the gate ran is missing its PPDS data. Go-live must re-evaluate the
        // allergen gate against CURRENT products and block (400) — never trust the row.
        UUID onboardingId = seedApprovedOnboarding();
        seedGate(onboardingId, GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true);
        seedGate(onboardingId, GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED, true);
        seedIncompleteProduct();

        mockMvc.perform(post("/api/v1/onboarding/go-live")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());

        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
        assertThat(statusOf(onboardingId)).isEqualTo(OnboardingState.APPROVED.name());
    }

    @Test
    void updateShopCannotPublish_soleWriterInvariantHolds() {
        // A direct updateShop with published=true must NOT flip the shop live:
        // ShopService.setPublished (reached only from the GO_LIVE side effect) is the
        // sole authorised writer of published=true (threat T-18-05-T).
        //
        // Issue #450 item 4: the invariant is unchanged, but the refusal is now EXPLICIT.
        // Before, this call returned normally and the assertion below was the only thing
        // that could tell the instruction had been dropped — which is exactly the client's
        // problem the council recorded (INT-06). It now throws a typed exception, and the
        // shop is still not published.
        CreateShopRequest req = new CreateShopRequest();
        req.setName("shop-" + shopId);
        req.setAddress("1 Test Street");
        req.setPublished(true);

        // A UUID-subject realm-admin (implicit GROUP_ADMIN) so updateShop CLEARS the 23-08
        // fail-closed shop gate and reaches the publish guard — proving the invariant holds
        // on an otherwise-authorised update, not merely because access was denied. (The old
        // WithMockUser non-JWT principal is now denied at the gate, which would prove nothing.)
        authenticateAsAdmin();
        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> shopService.updateShop(shopId, req))
                    .isInstanceOf(PublishStateNotAcceptedException.class)
                    .hasMessageContaining("onboarding state machine");
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }

        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------------------
    // Issue #450 item 4 (QA council disc-20260802-121732, F-L6-PUBLISHDROP / INT-06).
    //
    // The outcome above was always right; the RESPONSE was the defect. PUT /shops/{id}
    // with {"published":true} returned 200 OK with the shop unchanged — identical to the
    // response for {"published":false} — so the client could not tell it had been refused.
    // These four arms pin the corrected contract at the HTTP boundary on real Postgres:
    //   (a) a publish instruction is refused with a typed 409 and changes nothing;
    //   (b) an unpublish instruction on a LIVE shop is refused the same way — proving the
    //       guard compares against ACTUAL state, not a hardcoded "reject true";
    //   (c) CONTROL: a body echoing the shop's current state still succeeds with 200 —
    //       this is what the vendor shop-edit form sends on every ordinary save, and a
    //       guard that broke it would be a regression, not a fix;
    //   (d) CONTROL: omitting `published` entirely still succeeds.
    // Without (c) and (d) a blanket "always 409" would pass (a) and (b) and read as a fix.
    // ---------------------------------------------------------------------------------

    @Test
    void putShopAskingToPublish_returns409TypedAndAppliesNothing() throws Exception {
        String nameBefore = nameOf(shopId);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopBody("renamed-by-a-request-that-also-publishes", true)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-publish-not-accepted"))
                .andExpect(jsonPath("$.code").value("SHOP_PUBLISH_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.field").value("published"))
                .andExpect(jsonPath("$.requestedPublished").value(true))
                .andExpect(jsonPath("$.currentPublished").value(false));

        // The sole-writer invariant still holds, and the refusal is all-or-nothing: the
        // co-submitted name change must NOT have been persisted, or the 409 would be
        // lying about "no other field in this request was applied".
        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
        assertThat(nameOf(shopId)).isEqualTo(nameBefore);
    }

    @Test
    void putShopAskingToUnpublishALiveShop_returns409Typed() throws Exception {
        jdbc.update("UPDATE shops SET published = true WHERE id = ?", shopId);
        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopBody("shop-" + shopId, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-publish-not-accepted"))
                .andExpect(jsonPath("$.requestedPublished").value(false))
                .andExpect(jsonPath("$.currentPublished").value(true));

        // Refusing the instruction must not itself unpublish the shop.
        assertThat(publishedFlagOf(shopId)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void putShopEchoingCurrentPublishedState_succeedsAndAppliesTheOtherFields() throws Exception {
        // CONTROL. The vendor shop-edit form initialises its publish checkbox from the shop
        // and sends `published` on EVERY save. That save must keep working untouched.
        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopBody("renamed-with-published-echoed", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed-with-published-echoed"));

        assertThat(nameOf(shopId)).isEqualTo("renamed-with-published-echoed");
        assertThat(publishedFlagOf(shopId)).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void putShopOmittingPublished_succeedsAndLeavesPublishedAlone() throws Exception {
        // CONTROL. A body with no `published` at all is not an instruction about it.
        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopBody("renamed-without-published", null)))
                .andExpect(status().isOk());

        assertThat(nameOf(shopId)).isEqualTo("renamed-without-published");
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

    /** A fully-labelled product (durability + shelf life + ingredients) — allergen-complete. */
    private void seedCompliantProduct() {
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, shelf_life_days, durability_type, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 3, 'USE_BY', 0)",
                UUID.randomUUID(), tenantId, "SKU-" + shopId.toString().substring(0, 8), "Test Product",
                "Wheat flour, **milk**, sugar", shopId);
    }

    /** A product missing shelf life + durability type — NOT allergen-complete. */
    private void seedIncompleteProduct() {
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 0)",
                UUID.randomUUID(), tenantId, "SKU-BAD-" + shopId.toString().substring(0, 8), "Unlabelled Product",
                "Wheat flour, milk, sugar", shopId);
    }

    private Boolean publishedFlagOf(UUID id) {
        return jdbc.queryForObject("SELECT published FROM shops WHERE id = ?", Boolean.class, id);
    }

    private String nameOf(UUID id) {
        return jdbc.queryForObject("SELECT name FROM shops WHERE id = ?", String.class, id);
    }

    /** A PUT /shops/{id} body carrying an explicit {@code published} plus a changed name. */
    private static String shopBody(String name, Boolean published) {
        return """
                {"name":"%s","address":"1 Test Street"%s}"""
                .formatted(name, published == null ? "" : ",\"published\":" + published);
    }

    private String statusOf(UUID onboardingId) {
        return jdbc.queryForObject("SELECT status FROM vendor_onboarding WHERE id = ?", String.class, onboardingId);
    }
}
