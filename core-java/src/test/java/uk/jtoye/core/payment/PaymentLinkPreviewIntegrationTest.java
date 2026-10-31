package uk.jtoye.core.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.customer.PhoneVerificationService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Plan 30-16 / PAY-04 / D-03 / UI-SPEC § S3 data contract 1: a payment link can be checked
 * (who is asking, how much, is it still valid) WITHOUT being spent.
 *
 * <p>Owner decision 2026-10-31 (evidence/30-16-owner-decisions.txt, Task 1 = fragment): the token
 * travels in the URL fragment ({@code /pay#token=...}), so the page posts it in a JSON body. The
 * preview is {@code POST /api/v1/public/payment-links/preview {token}} and a body-token redeem
 * {@code POST /api/v1/public/payment-links/redeem {token}} sits beside the unchanged path redeem.
 *
 * <p>Every call goes through the real filter chain under the application role downgraded to
 * {@code NOSUPERUSER NOBYPASSRLS}, so the V88 token-scoped {@code order_payment_links_public_select}
 * policy and the tenant isolation policies on {@code orders} and {@code shops} genuinely apply.
 * Rows are seeded by SQL with a known raw token and its SHA-256 (no Stripe: the intent id is NULL).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PaymentLinkPreviewIntegrationTest {

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final String PREVIEW = "/api/v1/public/payment-links/preview";
    private static final String REDEEM = "/api/v1/public/payment-links/redeem";
    private static final String NOT_FOUND_TYPE = "https://jtoye.uk/errors/not-found";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername(DOWNGRADED_APP_ROLE)
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private PlatformTransactionManager txManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID shopA;
    private String shopAName;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // Only a superuser may run ALTER ROLE, so this happens once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER NOBYPASSRLS");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: app role must be NOSUPERUSER so FORCE RLS is genuinely enforced")
                .isFalse();

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?), (?, ?)",
                tenantA, "Tenant A " + tenantA, tenantB, "Tenant B " + tenantB);

        shopA = UUID.randomUUID();
        shopAName = "Mama Tolu's Kitchen " + shopA.toString().substring(0, 6);
        runAsTenant(tenantA, () -> jdbc.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, '1 Test Street, London, E1 6AN', true, 0, 0, 0)",
                shopA, tenantA, shopAName, "pay-" + shopA));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ arms

    @Test
    @DisplayName("preview of an ACTIVE link answers who, how much and until when — twice — and used_at stays NULL")
    void preview_isReadOnly_repeatable_andLeavesUsedAtNull() throws Exception {
        Seeded s = seedLink("UPFRONT", 3500, "PENDING", null, Instant.now().plus(30, ChronoUnit.MINUTES), null, null);

        for (int i = 1; i <= 2; i++) {
            MvcResult r = postToken(PREVIEW, s.rawToken, null);
            assertThat(r.getResponse().getStatus()).as("preview #" + i + ": " + body(r)).isEqualTo(200);
            assertThat(r.getResponse().getHeader("Cache-Control")).as("preview #" + i).contains("no-store");
            JsonNode json = json(r);
            assertThat(json.path("shopName").asString()).isEqualTo(shopAName);
            assertThat(json.path("orderNumber").asString()).isEqualTo(s.orderNumber);
            assertThat(json.path("amountPennies").asLong()).isEqualTo(3500L);
            assertThat(json.path("currency").asString()).isEqualTo("gbp");
            assertThat(json.path("purpose").asString()).isEqualTo("UPFRONT");
            assertThat(OffsetDateTime.parse(json.path("expiresAt").asString()).toInstant())
                    .isEqualTo(s.expiresAt);
            assertThat(json.path("state").asString()).isEqualTo("ACTIVE");
            assertThat(body(r)).as("the preview never carries the token or a client secret")
                    .doesNotContain(s.rawToken).doesNotContain("clientSecret");
        }

        assertThat(usedAtIsNull(s.linkId))
                .as("T-30-64: after two previews the single-use link is still unspent")
                .isTrue();

        // The legacy /public alias serves the same read-only preview.
        MvcResult alias = postToken("/public/payment-links/preview", s.rawToken, null);
        assertThat(alias.getResponse().getStatus()).as(body(alias)).isEqualTo(200);
        assertThat(json(alias).path("state").asString()).isEqualTo("ACTIVE");
        assertThat(usedAtIsNull(s.linkId)).isTrue();
    }

    @Test
    @DisplayName("preview then body redeem: redeem succeeds (the preview did not consume), then preview says USED and a 2nd redeem is 409")
    void previewThenBodyRedeem_redeemSucceeds_thenUsed() throws Exception {
        Seeded s = seedLink("UPFRONT", 4200, "PENDING", null, Instant.now().plus(30, ChronoUnit.MINUTES), null, null);

        assertThat(postToken(PREVIEW, s.rawToken, null).getResponse().getStatus()).isEqualTo(200);

        MvcResult redeemed = postToken(REDEEM, s.rawToken, null);
        assertThat(redeemed.getResponse().getStatus()).as(body(redeemed)).isEqualTo(200);
        assertThat(redeemed.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(json(redeemed).path("linkId").asString()).isEqualTo(s.linkId.toString());
        assertThat(json(redeemed).path("amountPennies").asLong()).isEqualTo(4200L);
        assertThat(usedAtIsNull(s.linkId)).as("the body redeem spends the link").isFalse();

        MvcResult after = postToken(PREVIEW, s.rawToken, null);
        assertThat(after.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(after).path("state").asString()).isEqualTo("USED");

        assertThat(postToken(REDEEM, s.rawToken, null).getResponse().getStatus())
                .as("single use: the body redeem refuses a repeat exactly as the path redeem does")
                .isEqualTo(409);
    }

    @Test
    @DisplayName("additive: the path redeem still works after a preview, and a body redeem of the same link is then 409")
    void pathRedeemUnchanged_afterPreview() throws Exception {
        Seeded s = seedLink("UPFRONT", 3000, "PENDING", null, Instant.now().plus(30, ChronoUnit.MINUTES), null, null);

        assertThat(postToken(PREVIEW, s.rawToken, null).getResponse().getStatus()).isEqualTo(200);
        MvcResult path = mockMvc.perform(post("/api/v1/public/payment-links/" + s.rawToken + "/redeem")).andReturn();
        assertThat(path.getResponse().getStatus()).as(body(path)).isEqualTo(200);
        assertThat(usedAtIsNull(s.linkId)).isFalse();
        assertThat(postToken(REDEEM, s.rawToken, null).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("states through HTTP: REVOKED, EXPIRED, PAID (CAPTURED), PAID beats USED, BALANCE paid at 0 due, and the body redeem's 410s")
    void states_andPrecedence_throughHttp() throws Exception {
        Instant future = Instant.now().plus(30, ChronoUnit.MINUTES);
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);

        Seeded revoked = seedLink("UPFRONT", 1000, "PENDING", null, future, null, Instant.now());
        Seeded expired = seedLink("UPFRONT", 1000, "PENDING", null, past, null, null);
        Seeded revokedAndExpired = seedLink("UPFRONT", 1000, "PENDING", null, past, null, Instant.now());
        Seeded captured = seedLink("UPFRONT", 1000, "CAPTURED", null, future, null, null);
        Seeded capturedAndUsed = seedLink("DEPOSIT", 1000, "CAPTURED", null, future, Instant.now(), null);
        Seeded usedAndRevoked = seedLink("UPFRONT", 1000, "PENDING", null, future, Instant.now(), Instant.now());
        Seeded balanceSettled = seedLink("BALANCE", 1000, "CAPTURED", 0L, future, null, null);
        Seeded balanceOwing = seedLink("BALANCE", 1000, "CAPTURED", 1500L, future, null, null);

        assertThat(previewState(revoked)).isEqualTo("REVOKED");
        assertThat(previewState(expired)).isEqualTo("EXPIRED");
        assertThat(previewState(revokedAndExpired)).as("REVOKED beats EXPIRED").isEqualTo("REVOKED");
        assertThat(previewState(captured)).isEqualTo("PAID");
        assertThat(previewState(capturedAndUsed)).as("PAID beats USED").isEqualTo("PAID");
        assertThat(previewState(usedAndRevoked)).as("USED beats REVOKED").isEqualTo("USED");
        assertThat(previewState(balanceSettled)).as("a BALANCE link is PAID when nothing is due").isEqualTo("PAID");
        assertThat(previewState(balanceOwing))
                .as("a BALANCE link is not PAID because the deposit was captured: the balance is still owed")
                .isEqualTo("ACTIVE");

        assertThat(postToken(REDEEM, revoked.rawToken, null).getResponse().getStatus()).isEqualTo(410);
        assertThat(postToken(REDEEM, expired.rawToken, null).getResponse().getStatus()).isEqualTo(410);
        for (Seeded s : new Seeded[]{revoked, expired, revokedAndExpired, captured, balanceSettled, balanceOwing}) {
            assertThat(usedAtIsNull(s.linkId)).as("previews and refused redeems spend nothing").isTrue();
        }
    }

    @Test
    @DisplayName("an unknown token is 404 not-found on preview and body redeem; a blank token is 400; there is no GET form")
    void unknownToken_404_blank_400_noGetForm() throws Exception {
        String unknown = "unknown-" + UUID.randomUUID();
        for (String route : new String[]{PREVIEW, REDEEM}) {
            MvcResult r = postToken(route, unknown, null);
            assertThat(r.getResponse().getStatus()).as(route + ": " + body(r)).isEqualTo(404);
            assertThat(json(r).path("type").asString()).as(route).isEqualTo(NOT_FOUND_TYPE);
            // A route that does not exist ALSO answers 404 not-found ("Resource not found"), so type and
            // status alone pass on a tree without the endpoint (measured on the RED run). The service's own
            // detail is what proves the token was looked up and not found.
            assertThat(json(r).path("detail").asString()).as(route).isEqualTo("Payment link not found.");
            assertThat(body(r)).as(route + " never echoes the token").doesNotContain(unknown);

            MvcResult blank = postToken(route, "   ", null);
            assertThat(blank.getResponse().getStatus()).as(route + " blank: " + body(blank)).isEqualTo(400);
        }

        Seeded s = seedLink("UPFRONT", 1000, "PENDING", null, Instant.now().plus(30, ChronoUnit.MINUTES), null, null);
        MvcResult getForm = mockMvc.perform(get("/api/v1/public/payment-links/" + s.rawToken)).andReturn();
        assertThat(getForm.getResponse().getStatus())
                .as("fragment decision: no route takes the token in a request line for a preview")
                .isNotEqualTo(200);
        assertThat(usedAtIsNull(s.linkId)).isTrue();
    }

    @Test
    @DisplayName("bearer semantics: a signed-in user of tenant B previews tenant A's link exactly as an anonymous visitor does")
    void tenantBCaller_previewsTenantALink_asAnonymousDoes() throws Exception {
        Seeded s = seedLink("UPFRONT", 2750, "PENDING", null, Instant.now().plus(30, ChronoUnit.MINUTES), null, null);

        RequestPostProcessor tenantBUser = jwt().jwt(j -> j
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantB.toString()));

        MvcResult anonymous = postToken(PREVIEW, s.rawToken, null);
        MvcResult asB = postToken(PREVIEW, s.rawToken, tenantBUser);
        assertThat(asB.getResponse().getStatus()).as(body(asB)).isEqualTo(200);
        assertThat(json(asB)).isEqualTo(json(anonymous));
        assertThat(json(asB).path("shopName").asString()).isEqualTo(shopAName);
        assertThat(usedAtIsNull(s.linkId)).isTrue();
    }

    @Test
    @DisplayName("RLS (NOSUPERUSER): with link 1's token GUC and no tenant, link 2's row is invisible; the preview answers link 1 only")
    void firstLinksToken_cannotReadSecondLink() throws Exception {
        Instant future = Instant.now().plus(30, ChronoUnit.MINUTES);
        Seeded one = seedLink("UPFRONT", 1111, "PENDING", null, future, null, null);
        Seeded two = seedLink("UPFRONT", 2222, "PENDING", null, future, null, null);

        long[] counts = new TransactionTemplate(txManager).execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.current_tenant_id', '', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.payment_link_token_sha256', ?, true)", String.class,
                    PhoneVerificationService.sha256Hex(one.rawToken));
            long own = jdbc.queryForObject("SELECT count(*) FROM order_payment_links WHERE id = ?", Long.class, one.linkId);
            long other = jdbc.queryForObject("SELECT count(*) FROM order_payment_links WHERE id = ?", Long.class, two.linkId);
            long all = jdbc.queryForObject("SELECT count(*) FROM order_payment_links", Long.class);
            return new long[]{own, other, all};
        });
        assertThat(counts[0]).as("control: link 1 is visible with its own token").isEqualTo(1L);
        assertThat(counts[1]).as("link 2 is not readable with link 1's token").isZero();
        assertThat(counts[2]).as("the token GUC exposes exactly one row").isEqualTo(1L);

        MvcResult r = postToken(PREVIEW, one.rawToken, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).path("orderNumber").asString()).isEqualTo(one.orderNumber);
        assertThat(json(r).path("amountPennies").asLong()).isEqualTo(1111L);
    }

    // ------------------------------------------------------------------ helpers

    private record Seeded(UUID linkId, UUID orderId, String orderNumber, String rawToken, Instant expiresAt) {
    }

    private Seeded seedLink(String purpose, long amountPennies, String orderPaymentStatus, Long balanceDuePennies,
                            Instant expiresAt, Instant usedAt, Instant revokedAt) {
        UUID orderId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        String orderNumber = "ORD-PRV-" + orderId.toString().substring(0, 8);
        String rawToken = "tok-" + UUID.randomUUID() + "-" + UUID.randomUUID();
        Instant expires = expiresAt.truncatedTo(ChronoUnit.MICROS);
        asTenant(tenantA, () -> {
            jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, payment_status, "
                            + "total_amount_pennies, subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                            + "customer_phone, phone_verified, balance_due_pennies, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', ?, 3500, 3500, 'STANDARD', 583, 0, '+447700900111', true, ?, now(), now())",
                    orderId, tenantA, shopA, orderNumber, orderPaymentStatus, balanceDuePennies);
            jdbc.update("INSERT INTO order_payment_links (id, tenant_id, shop_id, order_id, token_sha256, "
                            + "stripe_payment_intent_id, amount_pennies, purpose, phone_verified, expires_at, used_at, "
                            + "revoked_at, created_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, true, ?, ?, ?, now())",
                    linkId, tenantA, shopA, orderId, PhoneVerificationService.sha256Hex(rawToken), amountPennies,
                    purpose, Timestamp.from(expires), usedAt == null ? null : Timestamp.from(usedAt),
                    revokedAt == null ? null : Timestamp.from(revokedAt));
            return null;
        });
        return new Seeded(linkId, orderId, orderNumber, rawToken, expires);
    }

    /** Reads used_at under the link's tenant; a row RLS hid would throw, never read as "NULL". */
    private boolean usedAtIsNull(UUID linkId) {
        return asTenant(tenantA, () -> jdbc.queryForObject(
                "SELECT used_at IS NULL FROM order_payment_links WHERE id = ?", Boolean.class, linkId));
    }

    private <T> T asTenant(UUID tenant, Supplier<T> work) {
        TenantContext.set(tenant);
        try {
            return new TransactionTemplate(txManager).execute(st -> {
                jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class,
                        tenant.toString());
                return work.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    private void runAsTenant(UUID tenant, Runnable work) {
        asTenant(tenant, () -> {
            work.run();
            return null;
        });
    }

    private String previewState(Seeded s) throws Exception {
        MvcResult r = postToken(PREVIEW, s.rawToken, null);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        return json(r).path("state").asString();
    }

    private MvcResult postToken(String route, String token, RequestPostProcessor auth) throws Exception {
        var request = post(route)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("token", token)));
        if (auth != null) {
            request = request.with(auth);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return jsonMapper.readTree(body(r));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
