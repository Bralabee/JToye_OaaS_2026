package uk.jtoye.core.storefront;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.payment.StripeProperties;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #538 — the ONLINE-PAYMENT half of guest checkout, which no test in this
 * repository had ever executed.
 *
 * <p><strong>Why the defect survived 1425 Java tests.</strong> Every existing
 * test that reaches the {@code paymentService.isConfigured() == true} branch of
 * {@link PublicStorefrontService#createGuestOrder} does so with a <em>mocked</em>
 * {@code PaymentService} (see {@code PublicStorefrontServiceTest}, which stubs
 * {@code createPaymentIntent(...)} to return {@code "cs_test_secret"}). The bug
 * lives INSIDE the real {@code PaymentService}, so a mock hides it completely.
 * Meanwhile no deployed stack sets {@code STRIPE_API_KEY}, so every environment
 * takes the Cash-on-Delivery fallback and the card path is never executed. The
 * card path 500s with:
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "java.util.UUID.toString()" because
 * the return value of "uk.jtoye.core.order.Order.getId()" is null
 *     at uk.jtoye.core.payment.PaymentService.createPaymentIntent(PaymentService.java:126)
 * </pre>
 *
 * <p><strong>What this class does differently.</strong> It uses the REAL
 * {@code PaymentService} bean against a REAL Postgres (Testcontainers, because
 * order identity assignment is the thing under test and H2 would prove nothing
 * about it), and flips {@code isConfigured()} by mutating the injected
 * {@link StripeProperties} bean rather than by stubbing the service. Only the
 * outermost Stripe HTTP call — the static {@code PaymentIntent.create} — is
 * stubbed, so everything between the storefront and the network is genuinely
 * executed.
 *
 * <p><strong>No live key, ever.</strong> The api-key value below is a plain
 * sentence, deliberately NOT Stripe-key-shaped; {@code isConfigured()} only
 * tests for non-blank. No request ever leaves the JVM: {@code MockedStatic}
 * intercepts {@code PaymentIntent.create} before any transport is constructed.
 *
 * <p><strong>Context isolation.</strong> The Spring context cache key includes
 * {@code spring.datasource.url}, which carries this class's own container port,
 * so the {@link StripeProperties} mutation below cannot reach another test
 * class's context. It is restored in {@code @AfterEach} regardless.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestCheckoutOnlinePaymentIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(GuestCheckoutOnlinePaymentIntegrationTest.class);

    /**
     * Non-blank so {@code PaymentService.isConfigured()} returns true, and
     * deliberately not shaped like any Stripe credential. Test mode is not even
     * reached — the transport is never constructed.
     */
    private static final String NOT_A_KEY_JUST_NON_BLANK = "stripe-configured-for-this-test-only";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired PublicStorefrontService publicStorefrontService;
    @Autowired StripeProperties stripeProperties;
    @Autowired JdbcTemplate jdbcTemplate;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug/SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000538");
    private static final String SHOP_SLUG = "shop-issue538-online-payment";

    private UUID shopId;
    private String originalApiKey;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        originalApiKey = stripeProperties.getApiKey();
        seedTenantIdempotent(TENANT_ID, "Issue 538 Tenant");
        shopId = seedShopIdempotent(TENANT_ID, SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        stripeProperties.setApiKey(originalApiKey);
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Test A — THE FALSIFIER. Fails on the unfixed tree with the CI NPE.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("card checkout: the order is persisted BEFORE the PaymentIntent, so the intent can reference its id (#538)")
    void cardCheckout_persistsOrderBeforeCreatingPaymentIntent() {
        stripeProperties.setApiKey(NOT_A_KEY_JUST_NON_BLANK);
        assertThat(stripeProperties.getApiKey())
                .as("precondition: this test must take the online-payment branch, not COD")
                .isNotBlank();

        UUID productId = seedProductWithStock(TENANT_ID, shopId, "SKU-ISSUE538-CARD", 10);
        String idempotencyKey = "issue538-card-" + System.nanoTime();

        PaymentIntent stubIntent = mock(PaymentIntent.class);
        when(stubIntent.getId()).thenReturn("pi_issue538_stub");
        when(stubIntent.getClientSecret()).thenReturn("pi_issue538_stub_secret_abc");

        GuestOrderConfirmation confirmation;
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            // BOTH overloads are stubbed on purpose so this test source is
            // IDENTICAL on the unfixed and fixed trees — the fail direction must
            // fail for the ordering defect, not for an unstubbed signature.
            // On the unfixed tree neither stub is ever consulted: the NPE fires
            // while BUILDING the params (PaymentService.java:126), before
            // PaymentIntent.create is reached at all.
            piMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(stubIntent);
            piMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                            any(RequestOptions.class)))
                    .thenReturn(stubIntent);

            confirmation = publicStorefrontService.createGuestOrder(
                    SHOP_SLUG, guestRequestFor(productId, 2, idempotencyKey));
        }

        // The order stays DRAFT until the payment_intent.succeeded webhook lands
        // — the pre-existing contract (PublicStorefrontService:507), unchanged.
        assertThat(confirmation.getStatus())
                .as("a card order stays DRAFT until the webhook confirms payment")
                .isEqualTo("DRAFT");
        assertThat(confirmation.getClientSecret())
                .as("the browser needs a real client secret to mount Stripe Elements")
                .isEqualTo("pi_issue538_stub_secret_abc");

        Map<String, Object> row = readOrderRow(confirmation.getOrderNumber());
        assertThat(row.get("id"))
                .as("#538: the order must exist in the database with an identity")
                .isNotNull();
        assertThat(row.get("status")).isEqualTo("DRAFT");
        // The local↔Stripe reconciliation link. Without it the WR-02 idempotent
        // retry re-fetch (PublicStorefrontService:346-357) can never fire, so a
        // retried card checkout returns a null client secret and the customer
        // can never pay.
        assertThat(row.get("payment_reference"))
                .as("#538: the Stripe PaymentIntent id must be persisted on the order row")
                .isEqualTo("pi_issue538_stub");

        log.info("ISSUE-538 INVARIANT: card checkout order={} id={} payment_reference={} status={}",
                confirmation.getOrderNumber(), row.get("id"),
                row.get("payment_reference"), row.get("status"));
    }

    // ------------------------------------------------------------------
    // Test B — a failed PaymentIntent must leave NO order behind.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("card checkout: a failed PaymentIntent rolls the persisted DRAFT order back, leaving no row (#538)")
    void cardCheckout_failedPaymentIntent_rollsBackTheOrder() {
        stripeProperties.setApiKey(NOT_A_KEY_JUST_NON_BLANK);

        UUID productId = seedProductWithStock(TENANT_ID, shopId, "SKU-ISSUE538-FAIL", 10);
        String idempotencyKey = "issue538-fail-" + System.nanoTime();

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            ApiConnectionException outage = new ApiConnectionException("simulated Stripe outage");
            piMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(outage);
            piMock.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                            any(RequestOptions.class)))
                    .thenThrow(outage);

            assertThatThrownBy(() -> publicStorefrontService.createGuestOrder(
                    SHOP_SLUG, guestRequestFor(productId, 1, idempotencyKey)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Payment processing unavailable");
        }

        // createGuestOrder is @Transactional and the controller is NOT, so it is
        // the outermost boundary; the RuntimeException it rethrows is unchecked,
        // which triggers Spring's default rollback. The DRAFT row written before
        // the Stripe call must therefore be gone. This matters: a surviving DRAFT
        // row would poison the retry — the idempotency short-circuit would return
        // it with a null client secret forever, leaving an unpayable order.
        long orphans = countOrdersByIdempotencyKey(idempotencyKey);
        log.info("ISSUE-538 INVARIANT: orders surviving a failed PaymentIntent = {} (expected 0)", orphans);
        assertThat(orphans)
                .as("#538: a failed PaymentIntent must not leave an unpayable DRAFT order behind")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Test C — CONTROL. The COD fallback must behave exactly as it does today.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("CONTROL: with no Stripe key the COD fallback is unchanged — PENDING / NONE / 'Cash on Delivery' (#538)")
    void codFallback_unchanged() {
        stripeProperties.setApiKey("");
        assertThat(stripeProperties.getApiKey())
                .as("precondition: this test must take the COD branch")
                .isBlank();

        UUID productId = seedProductWithStock(TENANT_ID, shopId, "SKU-ISSUE538-COD", 10);
        String idempotencyKey = "issue538-cod-" + System.nanoTime();

        GuestOrderConfirmation confirmation;
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            confirmation = publicStorefrontService.createGuestOrder(
                    SHOP_SLUG, guestRequestFor(productId, 1, idempotencyKey));

            // The strongest half of the control: Stripe is not merely unused for
            // the RESULT, it is never called at all.
            piMock.verifyNoInteractions();
        }

        assertThat(confirmation.getStatus()).isEqualTo("PENDING");
        assertThat(confirmation.getClientSecret())
                .as("a COD order has no client secret")
                .isNull();

        Map<String, Object> row = readOrderRow(confirmation.getOrderNumber());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("payment_status")).isEqualTo("NONE");
        assertThat(row.get("payment_method")).isEqualTo("Cash on Delivery");
        assertThat(row.get("payment_reference"))
                .as("a COD order references no Stripe object")
                .isNull();

        log.info("ISSUE-538 CONTROL: COD order={} status={} payment_status={} payment_method={}",
                confirmation.getOrderNumber(), row.get("status"),
                row.get("payment_status"), row.get("payment_method"));
    }

    // ---- Request builder ----

    private GuestOrderRequest guestRequestFor(UUID productId, int qty, String idempotencyKey) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Card Buyer");
        request.setCustomerEmail("card-buyer@example.com");
        request.setCustomerPhone("+447700900538");
        request.setNotes("issue #538 online-payment path");
        request.setIdempotencyKey(idempotencyKey);
        request.setItems(List.of(item));
        // COLLECTION keeps this class focused on the payment ordering: no address
        // required, delivery fee forced to £0.
        request.setFulfilmentType("COLLECTION");
        return request;
    }

    // ---- Read helpers (Testcontainers bootstrap role is SUPERUSER, so RLS is
    //      bypassed regardless; TenantContext is set for correctness) ----

    private Map<String, Object> readOrderRow(String orderNumber) {
        TenantContext.set(TENANT_ID);
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT id, status, payment_status, payment_method, payment_reference "
                            + "FROM orders WHERE order_number = ?", orderNumber);
        } finally {
            TenantContext.clear();
        }
    }

    private long countOrdersByIdempotencyKey(String idempotencyKey) {
        TenantContext.set(TENANT_ID);
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM orders WHERE idempotency_key = ?", Long.class, idempotencyKey);
            return Objects.requireNonNull(count, "count(*) never returns null");
        } finally {
            TenantContext.clear();
        }
    }

    // ---- Idempotent seed helpers (Phase 13/14 pattern) ----

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private UUID seedShopIdempotent(UUID tenantId, String slug) {
        TenantContext.set(tenantId);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, tenantId, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No opening_hours -> shop is always open (validateShopIsOpen returns early).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, tenantId, "Issue 538 Shop", slug);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedProductWithStock(UUID tenantId, UUID shopId, String sku, int stock) {
        TenantContext.set(tenantId);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM products WHERE tenant_id = ? AND sku = ?", UUID.class, tenantId, sku);
            UUID productId;
            if (existing.isEmpty()) {
                productId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                                + "allergen_mask, price_pennies, display_order, available, featured, "
                                + "shop_id, quantity_in_stock, vat_rate, version) "
                                + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, ?, 'STANDARD', 0)",
                        productId, tenantId, sku, "Suya Skewer", "beef, yaji", shopId, stock);
            } else {
                productId = existing.get(0);
                jdbcTemplate.update(
                        "UPDATE products SET quantity_in_stock = ?, version = version + 1 WHERE id = ?",
                        stock, productId);
            }
            return productId;
        } finally {
            TenantContext.clear();
        }
    }
}
