package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderLimits;
import uk.jtoye.core.order.OrderNumberGenerator;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.payment.PaymentIntentResult;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopAnnouncementRepository;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 30-17 / PAY-04 / UI-SPEC § S1 — every response that hands a browser a client secret also tells it
 * which Stripe account owns the PaymentIntent, so Stripe.js can be initialised with
 * {@code { stripeAccount }} and {@code confirmPayment} can find a direct-charge intent.
 *
 * <p>Unit arms with mocked collaborators, the harness {@link PublicStorefrontServiceTest} uses. The
 * routing itself (which modes put an intent on a connected account) is {@code PaymentServiceTest}'s;
 * here the question is only that the storefront carries the answer through, on the first response,
 * on an idempotent replay and on the balance session, and that the field is PRESENT as JSON null —
 * not absent — when the intent lives on the platform.
 *
 * <p>Assertions read the serialised JSON, so they compile on a tree whose DTOs lack the field and
 * fail there on an assertion (30-17 RED).
 */
@ExtendWith(MockitoExtension.class)
class GuestOrderStripeAccountTest {

    private static final String SLUG = "acct-shop-30-17";
    private static final String TEST_HMAC_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final java.time.Clock FIXED_CLOCK =
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T11:00:00Z"), java.time.ZoneOffset.UTC);

    @Mock private ShopRepository shopRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Session hibernateSession;
    @Mock private PaymentService paymentService;
    @Mock private ShopAnnouncementRepository announcementRepository;
    @Mock private PostcodeGeocoder postcodeGeocoder;
    @Mock private IdempotencyService idempotencyService;
    @Mock private uk.jtoye.core.onboarding.TraderIdentityService traderIdentityService;
    @Mock private uk.jtoye.core.shop.ShopSlugAliasRepository shopSlugAliasRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private PublicStorefrontService service;
    private Shop shop;
    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        java.sql.Connection mockConn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement mockStmt = mock(java.sql.PreparedStatement.class);
        lenient().when(mockConn.prepareStatement(any(String.class))).thenReturn(mockStmt);
        lenient().doAnswer(inv -> {
            inv.<org.hibernate.jdbc.Work>getArgument(0).execute(mockConn);
            return null;
        }).when(hibernateSession).doWork(any());

        service = new PublicStorefrontService(shopRepository, shopSlugAliasRepository, productRepository,
                orderRepository, eventPublisher, entityManager, paymentService, announcementRepository,
                idempotencyService, postcodeGeocoder, new OrderNumberGenerator(), traderIdentityService,
                new ShopAvailabilityPolicy(FIXED_CLOCK), new OrderLimits(50, 200, 50, 100_000L),
                new CashOrderCaps(orderRepository, 3, 3, 5), new ClientIpDigester(TEST_HMAC_KEY), 5.0, 50.0);

        // No database: the keyed path simply runs the work, so the legacy in-work lookup on
        // orders.idempotency_key is what reaches replayConfirmation (as in PublicStorefrontServiceTest).
        lenient().doAnswer(inv -> {
            Supplier<?> work = inv.getArgument(4);
            return new IdempotencyOutcome<>(201, work.get());
        }).when(idempotencyService).executeWithoutStoringResponse(any(), any(), any(), any(), any(), any());

        tenantId = UUID.randomUUID();
        shop = new Shop();
        setField(shop, "id", UUID.randomUUID());
        shop.setTenantId(tenantId);
        shop.setName("Account Shop");
        shop.setSlug(SLUG);
        shop.setPublished(true);
        shop.setAddress("1 Test St");
        shop.setDeliveryFeePennies(0L);
        shop.setHoursSource(uk.jtoye.core.shop.HoursSource.LEGACY_ALWAYS_OPEN);
        lenient().when(shopRepository.findBySlugAndPublishedTrue(SLUG)).thenReturn(Optional.of(shop));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ first response

    @Test
    @DisplayName("first response: a direct-charge card order's confirmation names the connected account")
    void firstResponse_directCharge_namesConnectedAccount() throws Exception {
        JsonNode json = placeCardOrder(intentResult("pi_first_direct", "pi_first_direct_secret_a", "acct_first_direct"));

        assertEquals("pi_first_direct_secret_a", json.path("clientSecret").asString());
        assertTrue(json.has("stripeAccountId"), "the confirmation must carry stripeAccountId: " + json);
        assertEquals("acct_first_direct", json.path("stripeAccountId").asString());
        assertFalse(json.toString().contains("\"pi_first_direct\""), "the PaymentIntent id is never sent");
    }

    @Test
    @DisplayName("first response: a destination (platform) intent's confirmation carries stripeAccountId as JSON null — present, not absent")
    void firstResponse_destinationCharge_carriesJsonNull() throws Exception {
        JsonNode json = placeCardOrder(intentResult("pi_first_dest", "pi_first_dest_secret_b", null));

        assertTrue(json.has("stripeAccountId"),
                "absent and null are different statements: null says 'platform', absent says nothing: " + json);
        assertTrue(json.get("stripeAccountId").isNull(), "a destination intent lives on the platform: " + json);
    }

    // ------------------------------------------------------------------ replay

    @Test
    @DisplayName("replay: a payable direct-charge DRAFT re-fetches its secret ON the connected account and names that account")
    void replay_directChargeDraft_namesAccountAndFetchesSecretThere() throws Exception {
        Order existing = existingOrder(OrderStatus.DRAFT, "pi_replay_direct");
        when(orderRepository.findByTenantIdAndIdempotencyKey(tenantId, "acct-retry-key")).thenReturn(Optional.of(existing));
        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.directChargeAccountFor(existing)).thenReturn(Optional.of("acct_replay_direct"));
        when(paymentService.retrieveClientSecret(existing, "pi_replay_direct")).thenReturn("pi_replay_direct_secret_c");
        // A direct-charge intent is not on the platform account: Stripe answers resource_missing there.
        lenient().when(paymentService.retrieveClientSecret("pi_replay_direct"))
                .thenThrow(new com.stripe.exception.ApiConnectionException(
                        "resource_missing: no such payment_intent on the platform account"));

        JsonNode json = replay();

        assertTrue(json.has("stripeAccountId"), "the replay must carry stripeAccountId: " + json);
        assertEquals("acct_replay_direct", json.path("stripeAccountId").asString());
        assertEquals("pi_replay_direct_secret_c", json.path("clientSecret").asString(),
                "the secret must come from the account the browser is told to use");
    }

    @Test
    @DisplayName("replay: a destination (platform) DRAFT carries stripeAccountId as JSON null")
    void replay_destinationDraft_carriesJsonNull() throws Exception {
        Order existing = existingOrder(OrderStatus.DRAFT, "pi_replay_dest");
        when(orderRepository.findByTenantIdAndIdempotencyKey(tenantId, "acct-retry-key")).thenReturn(Optional.of(existing));
        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.directChargeAccountFor(existing)).thenReturn(Optional.empty());
        lenient().when(paymentService.retrieveClientSecret(existing, "pi_replay_dest")).thenReturn("pi_replay_dest_secret_d");
        lenient().when(paymentService.retrieveClientSecret("pi_replay_dest")).thenReturn("pi_replay_dest_secret_d");

        JsonNode json = replay();

        assertEquals("pi_replay_dest_secret_d", json.path("clientSecret").asString());
        assertTrue(json.has("stripeAccountId"), "present as null, not absent: " + json);
        assertTrue(json.get("stripeAccountId").isNull(), json.toString());
    }

    @Test
    @DisplayName("replay: an order with no PaymentIntent (cash) carries null even where the shop now routes direct — as its first response did")
    void replay_orderWithoutIntent_carriesNull() throws Exception {
        Order existing = existingOrder(OrderStatus.PENDING, null);
        when(orderRepository.findByTenantIdAndIdempotencyKey(tenantId, "acct-retry-key")).thenReturn(Optional.of(existing));
        lenient().when(paymentService.directChargeAccountFor(existing)).thenReturn(Optional.of("acct_never_used"));

        JsonNode json = replay();

        assertTrue(json.has("stripeAccountId"), json.toString());
        assertTrue(json.get("stripeAccountId").isNull(), "no intent, no owning account: " + json);
        assertTrue(json.get("clientSecret").isNull());
    }

    // ------------------------------------------------------------------ balance session

    @Test
    @DisplayName("balance session: a split-deposit balance intent's session names the connected account")
    void balanceSession_namesConnectedAccount() throws Exception {
        // Built BEFORE when(): an assertion thrown inside an unfinished stubbing poisons the next test.
        PaymentIntentResult intent = intentResult("pi_bal_acct", "pi_bal_acct_secret_e", "acct_balance_split");
        Order order = depositOrder();
        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.createBalancePaymentIntent(order)).thenReturn(intent);

        JsonNode json = jsonMapper.valueToTree(
                service.createBalancePaymentSession(order.getOrderNumber(), order.getCustomerEmail()));

        assertEquals("pi_bal_acct_secret_e", json.path("clientSecret").asString());
        assertTrue(json.has("stripeAccountId"), json.toString());
        assertEquals("acct_balance_split", json.path("stripeAccountId").asString());
    }

    @Test
    @DisplayName("balance session: a platform balance intent's session carries stripeAccountId as JSON null")
    void balanceSession_platformIntent_carriesJsonNull() throws Exception {
        PaymentIntentResult intent = intentResult("pi_bal_plat", "pi_bal_plat_secret_f", null);
        Order order = depositOrder();
        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.createBalancePaymentIntent(order)).thenReturn(intent);

        JsonNode json = jsonMapper.valueToTree(
                service.createBalancePaymentSession(order.getOrderNumber(), order.getCustomerEmail()));

        assertTrue(json.has("stripeAccountId"), json.toString());
        assertTrue(json.get("stripeAccountId").isNull(), json.toString());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Builds a {@link PaymentIntentResult} through its canonical three-component constructor,
     * reflectively, so this file compiles on a tree whose record has two components and FAILS there
     * on an assertion instead of a compile error (30-17 RED).
     */
    private static PaymentIntentResult intentResult(String id, String secret, String account) {
        try {
            Constructor<PaymentIntentResult> ctor =
                    PaymentIntentResult.class.getDeclaredConstructor(String.class, String.class, String.class);
            return ctor.newInstance(id, secret, account);
        } catch (NoSuchMethodException e) {
            fail("30-17: PaymentIntentResult must carry (paymentIntentId, clientSecret, stripeAccountId)");
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode placeCardOrder(PaymentIntentResult intent) throws Exception {
        Product product = new Product();
        setField(product, "id", UUID.randomUUID());
        product.setTitle("Jollof Rice");
        product.setPricePennies(899L);
        product.setAvailable(true);
        product.setVatRate(VatRate.STANDARD);
        product.setAllergenMask(0);
        product.setShopId(shop.getId());
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(paymentService.isConfigured()).thenReturn(true);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.createPaymentIntent(any(Order.class))).thenReturn(intent);

        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(product.getId());
        item.setQuantity(2);
        item.setExpectedUnitPricePennies(GuestOrderAcknowledgements.expectedUnitPrice(product));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Ada Lovelace");
        request.setCustomerEmail("ada@example.com");
        request.setCustomerPhone("07700900000");
        request.setFulfilmentType("COLLECTION");
        request.setItems(List.of(item));
        request.setAcknowledgedAllergenMask(GuestOrderAcknowledgements.currentMask(product));
        GuestOrderAcknowledgements.expectShownTotal(request, shop);

        GuestOrderConfirmation confirmation = service.createGuestOrder(SLUG, request);
        return jsonMapper.valueToTree(confirmation);
    }

    private JsonNode replay() {
        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Retry Customer");
        request.setCustomerEmail("retry@example.com");
        request.setCustomerPhone("07700900004");
        request.setFulfilmentType("COLLECTION");
        request.setIdempotencyKey("acct-retry-key");
        request.setItems(List.of());
        GuestOrderAcknowledgements.expectShownTotal(request, shop);
        GuestOrderConfirmation confirmation = service.createGuestOrder(SLUG, request);
        verify(orderRepository, never()).save(any(Order.class));
        return jsonMapper.valueToTree(confirmation);
    }

    private Order existingOrder(OrderStatus status, String paymentReference) throws Exception {
        Order order = new Order();
        setField(order, "id", UUID.randomUUID());
        order.setTenantId(tenantId);
        order.setShopId(shop.getId());
        order.setOrderNumber("ORD-ACCT-0001");
        order.setStatus(status);
        order.setSubtotalPennies(1798L);
        order.setDeliveryFeePennies(0L);
        order.setVatRate(VatRate.STANDARD);
        order.setVatAmountPennies(300L);
        order.setTotalAmountPennies(1798L);
        order.setItemCount(2);
        order.setPaymentReference(paymentReference);
        return order;
    }

    private Order depositOrder() throws Exception {
        Order order = new Order();
        setField(order, "id", UUID.randomUUID());
        order.setTenantId(tenantId);
        order.setShopId(shop.getId());
        order.setOrderNumber("ORD-ACCT-BAL-0001");
        order.setCustomerEmail("customer@example.com");
        order.setStatus(OrderStatus.CONFIRMED);
        order.setTotalAmountPennies(10_000L);
        order.setDepositAmountPennies(3000L);
        order.setBalanceDuePennies(7000L);
        when(orderRepository.findByOrderNumberAndCustomerEmail(order.getOrderNumber(), order.getCustomerEmail()))
                .thenReturn(Optional.of(order));
        return order;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
