package uk.jtoye.core.payment;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private StripeProperties stripeProperties;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private PaymentEventPublisher paymentEventPublisher;
    @Mock private FinancialTransactionService financialTransactionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RefundService refundService;
    @Mock private StripeConnectService stripeConnectService;

    private PaymentService paymentService;
    // issue #98 [P2-7]: real registry so the jtoye.payment.failed counter test
    // can assert an increment; the other tests simply ignore it.
    private SimpleMeterRegistry meterRegistry;

    private UUID orderId;
    private UUID tenantId;
    private Order testOrder;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new PaymentService(stripeProperties, orderRepository, eventPublisher,
                paymentEventPublisher, financialTransactionService, jdbcTemplate, refundService,
                stripeConnectService, providerOf(meterRegistry));
        // AUDIT-W0-03: PaymentService now runs INSERT ... ON CONFLICT DO NOTHING
        // against processed_stripe_events. For unit tests we model the "first
        // delivery" path (1 row inserted) so the existing assertions about
        // downstream side-effects continue to hold. Tests that explicitly want
        // to assert duplicate-event short-circuit semantics live in
        // StripeWebhookIdempotencyIntegrationTest (Testcontainers Postgres).
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(1);

        orderId = UUID.randomUUID();
        tenantId = UUID.randomUUID();

        testOrder = new Order();
        setField(testOrder, "id", orderId);
        testOrder.setTenantId(tenantId);
        testOrder.setShopId(UUID.randomUUID());
        testOrder.setOrderNumber("ORD-TEST-20260403-ABCD1234");
        testOrder.setStatus(OrderStatus.DRAFT);
        testOrder.setPaymentStatus(PaymentStatus.PENDING);
        testOrder.setTotalAmountPennies(1500L);
        // Resolved (predominant) VAT rate — the ledger row now follows the order's
        // rate, not a hardcoded STANDARD literal (Issue #81 BUG 2).
        testOrder.setVatRate(VatRate.STANDARD);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // issue #98 [P2-7]: null-safe ObjectProvider<MeterRegistry> wrapper, mirroring
    // the RateLimitInterceptorFailOpenTest precedent, so PaymentService can build
    // its jtoye.payment.failed counter against a real (assertable) registry.
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return registry;
            }

            @Override
            public MeterRegistry getObject() {
                return registry;
            }

            @Override
            public MeterRegistry getObject(Object... args) {
                return registry;
            }
        };
    }

    @Test
    @DisplayName("init sets Stripe API key when configured")
    void init_setsApiKey() {
        when(stripeProperties.getApiKey()).thenReturn("sk_test_123");
        when(stripeProperties.getConnectTimeoutMs()).thenReturn(5000);
        when(stripeProperties.getReadTimeoutMs()).thenReturn(20000);
        when(stripeProperties.getMaxNetworkRetries()).thenReturn(2);
        paymentService.init();
        // No exception = success (Stripe.apiKey is a static field, we just verify no NPE)
    }

    @Test
    @DisplayName("init logs warning when API key is blank")
    void init_blankKey() {
        when(stripeProperties.getApiKey()).thenReturn("");
        paymentService.init();
        // No exception = graceful degradation
    }

    @Test
    @DisplayName("handleWebhookEvent rejects invalid signature")
    void handleWebhook_invalidSignature() {
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        assertThrows(IllegalArgumentException.class, () ->
                paymentService.handleWebhookEvent("{}", "bad_sig"));
    }

    @Test
    @DisplayName("handlePaymentIntentSucceeded transitions order DRAFT → PENDING")
    void handlePaymentSucceeded_transitionsOrder() throws Exception {
        // Build mock PaymentIntent with metadata
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_test_123");
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));
        when(mockIntent.getLatestCharge()).thenReturn(null); // skip charge lookup

        // Build mock Event
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.succeeded");
        when(mockEvent.getId()).thenReturn("evt_test_123");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        // Mock Webhook.constructEvent
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            // Mock order lookup
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            // Execute
            paymentService.handleWebhookEvent("{}", "sig_test");

            // Verify order transitioned
            assertEquals(OrderStatus.PENDING, testOrder.getStatus());
            assertEquals(PaymentStatus.CAPTURED, testOrder.getPaymentStatus());
            assertEquals("pi_test_123", testOrder.getPaymentReference());
            assertEquals("Card", testOrder.getPaymentMethod());

            // Verify financial transaction created
            ArgumentCaptor<CreateTransactionRequest> txCaptor = ArgumentCaptor.forClass(CreateTransactionRequest.class);
            verify(financialTransactionService).createTransaction(txCaptor.capture());
            CreateTransactionRequest tx = txCaptor.getValue();
            assertEquals(1500L, tx.amountPennies());
            assertEquals(VatRate.STANDARD, tx.vatRate()); // follows order.getVatRate() (BUG 2)
            assertEquals(orderId, tx.orderId());           // keyed for idempotency (BUG 3)
            assertTrue(tx.description().contains("pi_test_123"));

            // Verify event published (Phase 23: 6-arg overload now carries the order's shopId)
            verify(eventPublisher).publishStateChange(
                    eq(orderId), eq(tenantId), any(), eq("ORD-TEST-20260403-ABCD1234"),
                    eq(OrderStatus.DRAFT), eq(OrderStatus.PENDING));

            // Verify payment succeeded event published
            verify(paymentEventPublisher).publishSucceeded(
                    eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                    eq("pi_test_123"), eq(1500L), eq("gbp"));
        }
    }

    @Test
    @DisplayName("handlePaymentIntentFailed marks order payment as FAILED")
    void handlePaymentFailed_marksOrder() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_test_fail");
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.payment_failed");
        when(mockEvent.getId()).thenReturn("evt_test_fail");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            paymentService.handleWebhookEvent("{}", "sig_test");

            assertEquals(OrderStatus.DRAFT, testOrder.getStatus()); // stays DRAFT
            assertEquals(PaymentStatus.FAILED, testOrder.getPaymentStatus());
            assertEquals("pi_test_fail", testOrder.getPaymentReference());

            // No financial transaction on failure
            verify(financialTransactionService, never()).createTransaction(any());
            // No order state change published on failure (order stays DRAFT).
            // Phase 23: OrderService/PaymentService now use the 6-arg (shopId-carrying) overload.
            verify(eventPublisher, never()).publishStateChange(any(), any(), any(), any(), any(), any());
            // But a payment.failed event IS published for audit/analytics
            verify(paymentEventPublisher).publishFailed(
                    eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                    eq("pi_test_fail"), eq(1500L), eq("gbp"), any());
        }
    }

    @Test
    @DisplayName("handlePaymentIntentFailed increments the jtoye.payment.failed counter (issue #98 PaymentFailureSpike signal)")
    void handlePaymentFailed_incrementsCounter() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_counter_fail");
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.payment_failed");
        when(mockEvent.getId()).thenReturn("evt_counter_fail");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            // Counter absent (or zero) before the failed-payment webhook flows through.
            assertEquals(0.0, meterRegistry.get("jtoye.payment.failed").counter().count());

            paymentService.handleWebhookEvent("{}", "sig_test");

            assertEquals(1.0, meterRegistry.get("jtoye.payment.failed").counter().count(),
                    "a payment_intent.payment_failed webhook must increment jtoye.payment.failed by 1");
        }
    }

    @Test
    @DisplayName("handleWebhook ignores events without order_id metadata")
    void handleWebhook_noOrderId_skips() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_no_meta");
        when(mockIntent.getMetadata()).thenReturn(Map.of()); // no order_id

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.succeeded");
        when(mockEvent.getId()).thenReturn("evt_no_meta");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            paymentService.handleWebhookEvent("{}", "sig_test");

            // Should not touch any order
            verify(orderRepository, never()).findById(any());
            verify(orderRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("handleWebhook ignores unhandled event types")
    void handleWebhook_unhandledType_skips() throws Exception {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("charge.refunded");
        when(mockEvent.getId()).thenReturn("evt_refund");

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            paymentService.handleWebhookEvent("{}", "sig_test");

            verify(orderRepository, never()).findById(any());
        }
    }

    // ------------------------------------------------------------------
    // issue #102 (ADR-0001 Decision 2) — destination-charge routing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createPaymentIntent routes a MARKETPLACE order as a destination charge with the platform fee")
    void createPaymentIntent_marketplace_destinationCharge() throws Exception {
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        when(stripeConnectService.resolveDestinationAccount(tenantId))
                .thenReturn(Optional.of("acct_market_1"));
        // 2.5% of 1500p = 37.5 → floored to 37 (fee math itself is proven in
        // StripeConnectServiceTest; here we prove PaymentService APPLIES it).
        when(stripeConnectService.applicationFeePennies(1500L)).thenReturn(37L);

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_dest_1");
        when(mockIntent.getClientSecret()).thenReturn("pi_dest_1_secret_x");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            PaymentIntentResult result = paymentService.createPaymentIntent(testOrder);
            assertEquals("pi_dest_1_secret_x", result.clientSecret());
            assertEquals("pi_dest_1", result.paymentIntentId(),
                    "#538: the caller needs the PaymentIntent id to persist on the order");

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            piMock.verify(() -> PaymentIntent.create(captor.capture(),
                    any(com.stripe.net.RequestOptions.class)));
            com.stripe.param.PaymentIntentCreateParams params = captor.getValue();

            assertEquals(1500L, params.getAmount());
            assertEquals("gbp", params.getCurrency());
            assertNotNull(params.getTransferData(), "destination charge must carry transfer_data");
            assertEquals("acct_market_1", params.getTransferData().getDestination());
            assertEquals(37L, params.getApplicationFeeAmount());
            assertEquals(tenantId.toString(), params.getMetadata().get("tenant_id"));
        }
    }

    @Test
    @DisplayName("createPaymentIntent omits application_fee_amount when the configured fee is zero")
    void createPaymentIntent_marketplace_zeroFee_omitsApplicationFee() throws Exception {
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        when(stripeConnectService.resolveDestinationAccount(tenantId))
                .thenReturn(Optional.of("acct_market_1"));
        when(stripeConnectService.applicationFeePennies(1500L)).thenReturn(0L);

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getClientSecret()).thenReturn("pi_secret");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            paymentService.createPaymentIntent(testOrder);

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            piMock.verify(() -> PaymentIntent.create(captor.capture(),
                    any(com.stripe.net.RequestOptions.class)));

            assertNotNull(captor.getValue().getTransferData());
            assertNull(captor.getValue().getApplicationFeeAmount());
        }
    }

    @Test
    @DisplayName("createPaymentIntent keeps pooled behaviour for WHITE_LABEL / unlinked tenants (no routing)")
    void createPaymentIntent_noDestination_pooledBehaviourUnchanged() throws Exception {
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        // WHITE_LABEL, unlinked, or not-ENABLED all resolve empty (proven in
        // StripeConnectServiceTest) — PaymentService must then leave the params
        // exactly as before #102: no transfer_data, no application fee.
        when(stripeConnectService.resolveDestinationAccount(tenantId)).thenReturn(Optional.empty());

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getClientSecret()).thenReturn("pi_secret");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            paymentService.createPaymentIntent(testOrder);

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            piMock.verify(() -> PaymentIntent.create(captor.capture(),
                    any(com.stripe.net.RequestOptions.class)));

            assertNull(captor.getValue().getTransferData());
            assertNull(captor.getValue().getApplicationFeeAmount());
            assertEquals(1500L, captor.getValue().getAmount());
            assertEquals("gbp", captor.getValue().getCurrency());
            verify(stripeConnectService, never()).applicationFeePennies(anyLong());
        }
    }

    // ------------------------------------------------------------------
    // issue #538 — persist-before-pay + Stripe idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#538: createPaymentIntent REFUSES a transient (unsaved) order instead of NPE-ing on its null id")
    void createPaymentIntent_transientOrder_isRejected() {
        // The exact shape of the production defect: an Order built in memory and
        // handed to Stripe before orderRepository.save assigned it an id. Before
        // the fix this threw NullPointerException from inside a metadata builder
        // (PaymentService.java:126) and surfaced as an opaque 500; now it is a
        // named failure that says what the caller did wrong.
        Order transientOrder = new Order();
        transientOrder.setTenantId(tenantId);
        transientOrder.setOrderNumber("ORD-TRANSIENT-0001");
        transientOrder.setTotalAmountPennies(1500L);
        assertNull(transientOrder.getId(), "precondition: the order under test must be unsaved");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> paymentService.createPaymentIntent(transientOrder));
            assertTrue(ex.getMessage().contains("persisted order"), ex.getMessage());

            // The strongest half: Stripe is never reached at all, so no intent
            // can be orphaned by a caller that got the ordering wrong.
            piMock.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("#538: createPaymentIntent sends an Idempotency-Key derived from the order id (SDK retries default to 2)")
    void createPaymentIntent_sendsIdempotencyKeyDerivedFromOrderId() throws Exception {
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        when(stripeConnectService.resolveDestinationAccount(tenantId)).thenReturn(Optional.empty());

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getClientSecret()).thenReturn("pi_secret");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            paymentService.createPaymentIntent(testOrder);

            ArgumentCaptor<com.stripe.net.RequestOptions> optionsCaptor =
                    ArgumentCaptor.forClass(com.stripe.net.RequestOptions.class);
            piMock.verify(() -> PaymentIntent.create(
                    any(com.stripe.param.PaymentIntentCreateParams.class), optionsCaptor.capture()));

            // Stripe.maxNetworkRetries defaults to 2 and the SDK only emits an
            // Idempotency-Key header when the caller supplies one — it never
            // generates one — so without this a single checkout could leave three
            // PaymentIntents at Stripe. Asserted against the production
            // expression, not a copy of it, so the two cannot drift apart.
            assertEquals(PaymentService.intentIdempotencyKey(testOrder),
                    optionsCaptor.getValue().getIdempotencyKey());
            assertTrue(optionsCaptor.getValue().getIdempotencyKey().contains(orderId.toString()),
                    "the key must be derived from the order id, which is stable for this order's life");
        }
    }

    @Test
    @DisplayName("handleWebhook dispatches account.updated to StripeConnectService")
    void handleWebhook_accountUpdated_dispatchesToConnectService() throws Exception {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("account.updated");
        when(mockEvent.getId()).thenReturn("evt_acct_upd");

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            paymentService.handleWebhookEvent("{}", "sig_test");

            verify(stripeConnectService).handleAccountUpdated(mockEvent);
        }
    }

    @Test
    @DisplayName("handleWebhook short-circuits a duplicate account.updated before the Connect handler (idempotency)")
    void handleWebhook_duplicateAccountUpdated_shortCircuits() throws Exception {
        Event mockEvent = mock(Event.class);
        lenient().when(mockEvent.getType()).thenReturn("account.updated");
        when(mockEvent.getId()).thenReturn("evt_acct_dup");

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");
        // Duplicate delivery: the ON CONFLICT DO NOTHING insert affects 0 rows.
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(0);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            paymentService.handleWebhookEvent("{}", "sig_test");

            verify(stripeConnectService, never()).handleAccountUpdated(any());
        }
    }

    // ------------------------------------------------------------------
    // ADR-0007 / GAP-GTM-01 — Direct Charge & Split Deposit Routing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADR-0007: createPaymentIntent routes DIRECT_CHARGE_VENDOR_MOR via Stripe-Account header without transfer_data")
    void createPaymentIntent_directChargeVendorMor_setsStripeAccountHeaderWithoutTransferData() throws Exception {
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        when(stripeConnectService.resolveSettlementRouting(testOrder))
                .thenReturn(Optional.of(new SettlementRoutingDecision(
                        SettlementPolicyMode.DIRECT_CHARGE_VENDOR_MOR,
                        "acct_vendor_direct",
                        1500L,
                        37L,
                        null,
                        null
                )));

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_direct_1");
        when(mockIntent.getClientSecret()).thenReturn("pi_direct_1_secret");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            PaymentIntentResult result = paymentService.createPaymentIntent(testOrder);
            assertEquals("pi_direct_1", result.paymentIntentId());

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> paramsCaptor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            ArgumentCaptor<com.stripe.net.RequestOptions> optionsCaptor =
                    ArgumentCaptor.forClass(com.stripe.net.RequestOptions.class);
            piMock.verify(() -> PaymentIntent.create(paramsCaptor.capture(), optionsCaptor.capture()));

            assertEquals(1500L, paramsCaptor.getValue().getAmount());
            assertEquals(37L, paramsCaptor.getValue().getApplicationFeeAmount());
            assertNull(paramsCaptor.getValue().getTransferData(),
                    "DIRECT_CHARGE_VENDOR_MOR must use Stripe-Account header, not destination transfer_data");
            assertEquals("DIRECT_CHARGE_VENDOR_MOR", paramsCaptor.getValue().getMetadata().get("settlement_mode"));
            assertEquals("acct_vendor_direct", optionsCaptor.getValue().getStripeAccount());
        }
    }

    @Test
    @DisplayName("ADR-0007: createPaymentIntent routes SPLIT_DEPOSIT_VENDOR_MOR by charging deposit and stamping balanceDuePennies")
    void createPaymentIntent_splitDepositVendorMor_chargesDepositAndStampsBalanceOnOrder() throws Exception {
        testOrder.setTotalAmountPennies(10_000L);
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        when(stripeConnectService.resolveSettlementRouting(testOrder))
                .thenReturn(Optional.of(new SettlementRoutingDecision(
                        SettlementPolicyMode.SPLIT_DEPOSIT_VENDOR_MOR,
                        "acct_caterer_1",
                        3000L,
                        75L,
                        3000L,
                        7000L
                )));

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_dep_1");
        when(mockIntent.getClientSecret()).thenReturn("pi_dep_1_secret");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            paymentService.createPaymentIntent(testOrder);

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> paramsCaptor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            ArgumentCaptor<com.stripe.net.RequestOptions> optionsCaptor =
                    ArgumentCaptor.forClass(com.stripe.net.RequestOptions.class);
            piMock.verify(() -> PaymentIntent.create(paramsCaptor.capture(), optionsCaptor.capture()));

            assertEquals(3000L, paramsCaptor.getValue().getAmount());
            assertEquals(75L, paramsCaptor.getValue().getApplicationFeeAmount());
            assertEquals("SPLIT_DEPOSIT_VENDOR_MOR", paramsCaptor.getValue().getMetadata().get("settlement_mode"));
            assertEquals("acct_caterer_1", optionsCaptor.getValue().getStripeAccount());
            assertEquals(3000L, testOrder.getDepositAmountPennies());
            assertEquals(7000L, testOrder.getBalanceDuePennies());
        }
    }

    @Test
    @DisplayName("ADR-0007: handlePaymentIntentSucceeded defers full-order SALE ledger row when balanceDuePennies > 0")
    void handlePaymentSucceeded_splitDepositWithBalance_defersFullSaleLedgerRowUntilCompleted() throws Exception {
        testOrder.setTotalAmountPennies(10_000L);
        testOrder.setDepositAmountPennies(3000L);
        testOrder.setBalanceDuePennies(7000L);

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_dep_succ");
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));
        when(mockIntent.getLatestCharge()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.succeeded");
        when(mockEvent.getId()).thenReturn("evt_dep_succ");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            paymentService.handleWebhookEvent("{}", "sig_test");

            assertEquals(OrderStatus.PENDING, testOrder.getStatus());
            assertEquals(PaymentStatus.CAPTURED, testOrder.getPaymentStatus());
            verify(paymentEventPublisher).publishSucceeded(
                    eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                    eq("pi_dep_succ"), eq(3000L), eq("gbp"));
            verify(financialTransactionService, never()).createTransaction(any());
        }
    }

    @Test
    @DisplayName("GAP-GTM-04: createBalancePaymentIntent creates BALANCE PaymentIntent for balanceDuePennies on connected account")
    void createBalancePaymentIntent_validBalance_createsIntentWithBalanceMetadata() throws Exception {
        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.setPaymentStatus(PaymentStatus.CAPTURED);
        testOrder.setTotalAmountPennies(10_000L);
        testOrder.setDepositAmountPennies(3000L);
        testOrder.setBalanceDuePennies(7000L);
        when(stripeProperties.getCurrency()).thenReturn("gbp");
        when(stripeConnectService.resolveSettlementRouting(testOrder))
                .thenReturn(Optional.of(new SettlementRoutingDecision(
                        SettlementPolicyMode.SPLIT_DEPOSIT_VENDOR_MOR,
                        "acct_caterer_1",
                        3000L,
                        75L,
                        3000L,
                        7000L
                )));
        when(stripeConnectService.applicationFeePennies(tenantId, 7000L)).thenReturn(175L);

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_bal_1");
        when(mockIntent.getClientSecret()).thenReturn("pi_bal_1_secret");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                            any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            PaymentIntentResult result = paymentService.createBalancePaymentIntent(testOrder);
            assertEquals("pi_bal_1", result.paymentIntentId());
            assertEquals("pi_bal_1_secret", result.clientSecret());

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> paramsCaptor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            ArgumentCaptor<com.stripe.net.RequestOptions> optionsCaptor =
                    ArgumentCaptor.forClass(com.stripe.net.RequestOptions.class);
            piMock.verify(() -> PaymentIntent.create(paramsCaptor.capture(), optionsCaptor.capture()));

            assertEquals(7000L, paramsCaptor.getValue().getAmount());
            assertEquals(175L, paramsCaptor.getValue().getApplicationFeeAmount());
            assertEquals("BALANCE", paramsCaptor.getValue().getMetadata().get("payment_stage"));
            assertEquals("SPLIT_DEPOSIT_VENDOR_MOR", paramsCaptor.getValue().getMetadata().get("settlement_mode"));
            assertEquals("acct_caterer_1", optionsCaptor.getValue().getStripeAccount());
            assertEquals(PaymentService.balanceIntentIdempotencyKey(testOrder),
                    optionsCaptor.getValue().getIdempotencyKey());
        }
    }

    @Test
    @DisplayName("PAY-04 / A-1: retrieveClientSecret(order, piId) routes with RequestOptions.setStripeAccount for direct-charge vendor MOR orders")
    void retrieveClientSecret_directChargeOrder_passesConnectedStripeAccount() throws Exception {
        when(stripeConnectService.resolveSettlementRouting(testOrder))
                .thenReturn(Optional.of(new SettlementRoutingDecision(
                        SettlementPolicyMode.DIRECT_CHARGE_VENDOR_MOR,
                        "acct_vendor_direct_1",
                        1500L,
                        8L,
                        null,
                        null
                )));

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getClientSecret()).thenReturn("pi_direct_123_secret_abc");

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.retrieve(eq("pi_direct_123"), any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(mockIntent);

            String secret = paymentService.retrieveClientSecret(testOrder, "pi_direct_123");
            assertEquals("pi_direct_123_secret_abc", secret);

            ArgumentCaptor<com.stripe.net.RequestOptions> optionsCaptor =
                    ArgumentCaptor.forClass(com.stripe.net.RequestOptions.class);
            piMock.verify(() -> PaymentIntent.retrieve(eq("pi_direct_123"), optionsCaptor.capture()));
            assertEquals("acct_vendor_direct_1", optionsCaptor.getValue().getStripeAccount());
        }
    }

    @Test
    @DisplayName("GAP-GTM-04: createBalancePaymentIntent rejects order with zero or null balanceDuePennies")
    void createBalancePaymentIntent_zeroBalance_throwsIllegalState() {
        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.setBalanceDuePennies(0L);
        assertThrows(IllegalStateException.class, () -> paymentService.createBalancePaymentIntent(testOrder));
    }

    @Test
    @DisplayName("GAP-GTM-04: createBalancePaymentIntent rejects DRAFT order before upfront deposit is paid")
    void createBalancePaymentIntent_draftOrder_throwsIllegalState() {
        testOrder.setStatus(OrderStatus.DRAFT);
        testOrder.setBalanceDuePennies(7000L);
        assertThrows(IllegalStateException.class, () -> paymentService.createBalancePaymentIntent(testOrder));
    }

    @Test
    @DisplayName("GAP-GTM-04: handlePaymentIntentFailed with payment_stage=BALANCE preserves CAPTURED deposit status and deposit paymentReference")
    void handlePaymentFailed_balanceStage_preservesCapturedDepositAndEmitsBalanceAmount() throws Exception {
        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.setPaymentStatus(PaymentStatus.CAPTURED);
        testOrder.setPaymentReference("pi_deposit_captured");
        testOrder.setTotalAmountPennies(10_000L);
        testOrder.setDepositAmountPennies(3000L);
        testOrder.setBalanceDuePennies(7000L);

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_bal_failed");
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString(),
                "payment_stage", "BALANCE"
        ));
        when(mockIntent.getLastPaymentError()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.payment_failed");
        when(mockEvent.getId()).thenReturn("evt_bal_fail");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

            paymentService.handleWebhookEvent("{}", "sig_test");

            assertEquals(PaymentStatus.CAPTURED, testOrder.getPaymentStatus(),
                    "Failed balance attempt must not overwrite CAPTURED deposit status");
            assertEquals("pi_deposit_captured", testOrder.getPaymentReference(),
                    "Failed balance attempt must not overwrite captured deposit paymentReference");
            assertEquals(7000L, testOrder.getBalanceDuePennies());
            verify(paymentEventPublisher).publishFailed(
                    eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                    eq("pi_bal_failed"), eq(7000L), eq("gbp"), eq("unknown"));
        }
    }

    @Test
    @DisplayName("GAP-GTM-04: handlePaymentIntentSucceeded with payment_stage=BALANCE clears balanceDuePennies, preserves CONFIRMED status, and writes full-order SALE ledger row")
    void handlePaymentSucceeded_balanceStage_clearsBalanceAndWritesSaleLedgerRow() throws Exception {
        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.setTotalAmountPennies(10_000L);
        testOrder.setDepositAmountPennies(3000L);
        testOrder.setBalanceDuePennies(7000L);

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_bal_succ");
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString(),
                "payment_stage", "BALANCE"
        ));
        when(mockIntent.getLatestCharge()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.succeeded");
        when(mockEvent.getId()).thenReturn("evt_bal_succ");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(mockEvent);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            paymentService.handleWebhookEvent("{}", "sig_test");

            assertEquals(OrderStatus.CONFIRMED, testOrder.getStatus(),
                    "Balance settlement must not regress a CONFIRMED order back to PENDING");
            assertEquals(0L, testOrder.getBalanceDuePennies());
            assertEquals("pi_bal_succ", testOrder.getPaymentReference());
            verify(eventPublisher, never()).publishStateChange(any(), any(), any(), any(), any(), any());
            verify(paymentEventPublisher).publishSucceeded(
                    eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                    eq("pi_bal_succ"), eq(7000L), eq("gbp"));

            ArgumentCaptor<CreateTransactionRequest> txCaptor = ArgumentCaptor.forClass(CreateTransactionRequest.class);
            verify(financialTransactionService).createTransaction(txCaptor.capture());
            assertEquals(10_000L, txCaptor.getValue().amountPennies());
        }
    }

    // --- Phase 30-05 (issue #958): a successful payment never moves an order backwards ---

    @Test
    @DisplayName("#958: payment on a CONFIRMED (link) order records the money, keeps CONFIRMED, publishes no state change")
    void handlePaymentSucceeded_onConfirmedOrder_keepsStatus() throws Exception {
        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.setPaymentReference("plink_958");

        deliverSucceeded("pi_958_conf", "evt_958_conf");

        assertEquals(OrderStatus.CONFIRMED, testOrder.getStatus());
        assertEquals(PaymentStatus.CAPTURED, testOrder.getPaymentStatus());
        assertEquals("pi_958_conf", testOrder.getPaymentReference());
        verify(orderRepository).save(testOrder);
        verify(eventPublisher, never()).publishStateChange(any(), any(), any(), any(), any(), any());
        verify(paymentEventPublisher).publishSucceeded(
                eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                eq("pi_958_conf"), eq(1500L), eq("gbp"));
        verify(financialTransactionService).createTransaction(any());
    }

    @Test
    @DisplayName("#958: payment on a CANCELLED order records the money, keeps CANCELLED, publishes no state change")
    void handlePaymentSucceeded_onCancelledOrder_keepsStatus() throws Exception {
        testOrder.setStatus(OrderStatus.CANCELLED);

        deliverSucceeded("pi_958_canc", "evt_958_canc");

        assertEquals(OrderStatus.CANCELLED, testOrder.getStatus());
        assertEquals(PaymentStatus.CAPTURED, testOrder.getPaymentStatus());
        verify(eventPublisher, never()).publishStateChange(any(), any(), any(), any(), any(), any());
        verify(paymentEventPublisher).publishSucceeded(
                eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                eq("pi_958_canc"), eq(1500L), eq("gbp"));
    }

    /** Delivers one payment_intent.succeeded for {@link #testOrder} with no charge lookup. */
    private void deliverSucceeded(String paymentIntentId, String eventId) throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn(paymentIntentId);
        when(mockIntent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));
        when(mockIntent.getLatestCharge()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockIntent));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.succeeded");
        when(mockEvent.getId()).thenReturn(eventId);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(mockEvent);
            paymentService.handleWebhookEvent("{}", "sig_test");
        }
    }

    // ------------------------------------------------------------------
    // issue #1038: the stale-draft cleanup cancels an abandoned card draft's
    // PaymentIntent at Stripe before the order is deleted.
    // ------------------------------------------------------------------

    private static final String ABANDONED_INTENT = "pi_abandoned_1038";

    /** Stubs PaymentIntent.retrieve(ABANDONED_INTENT, options) to an intent in {@code status}. */
    private PaymentIntent stubRetrieved(MockedStatic<PaymentIntent> piMock, String status) {
        PaymentIntent retrieved = mock(PaymentIntent.class);
        when(retrieved.getStatus()).thenReturn(status);
        piMock.when(() -> PaymentIntent.retrieve(eq(ABANDONED_INTENT), any(RequestOptions.class)))
                .thenReturn(retrieved);
        return retrieved;
    }

    private static PaymentIntent canceledIntent() {
        PaymentIntent canceled = mock(PaymentIntent.class);
        lenient().when(canceled.getStatus()).thenReturn(PaymentService.STRIPE_STATUS_CANCELED);
        return canceled;
    }

    @ParameterizedTest(name = "{0} is cancelled as abandoned")
    @ValueSource(strings = {"requires_payment_method", "requires_confirmation", "requires_action"})
    @DisplayName("issue #1038: an unpaid intent is cancelled with reason abandoned and an idempotency key")
    void cancelAbandoned_unpaidIntent_isCancelledAsAbandoned(String status) throws Exception {
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            PaymentIntent retrieved = stubRetrieved(piMock, status);
            PaymentIntent canceled = canceledIntent();
            when(retrieved.cancel(any(PaymentIntentCancelParams.class), any(RequestOptions.class)))
                    .thenReturn(canceled);

            String result = paymentService.cancelAbandonedPaymentIntent(ABANDONED_INTENT, null);

            assertEquals(PaymentService.STRIPE_STATUS_CANCELED, result);
            ArgumentCaptor<PaymentIntentCancelParams> params = ArgumentCaptor.forClass(PaymentIntentCancelParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            verify(retrieved, times(1)).cancel(params.capture(), options.capture());
            assertEquals(PaymentIntentCancelParams.CancellationReason.ABANDONED,
                    params.getValue().getCancellationReason());
            // Asserted against the production expression (AGENTS.md oaas-money rule 2).
            assertEquals(PaymentService.abandonedIntentCancelIdempotencyKey(ABANDONED_INTENT),
                    options.getValue().getIdempotencyKey());
            assertTrue(options.getValue().getIdempotencyKey().contains(ABANDONED_INTENT));
        }
    }

    @Test
    @DisplayName("issue #1038: an already-canceled intent reports canceled without a second cancel")
    void cancelAbandoned_alreadyCanceled_returnsCanceledWithoutCancelling() throws Exception {
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            PaymentIntent retrieved = stubRetrieved(piMock, PaymentService.STRIPE_STATUS_CANCELED);

            String result = paymentService.cancelAbandonedPaymentIntent(ABANDONED_INTENT, null);

            assertEquals(PaymentService.STRIPE_STATUS_CANCELED, result);
            verify(retrieved, never()).cancel(any(PaymentIntentCancelParams.class), any(RequestOptions.class));
        }
    }

    @ParameterizedTest(name = "{0} is left alone")
    @ValueSource(strings = {"succeeded", "processing", "requires_capture"})
    @DisplayName("issue #1038: an intent that has taken or is holding money is never cancelled")
    void cancelAbandoned_moneyMovedOrHeld_isLeftAlone(String status) throws Exception {
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            PaymentIntent retrieved = stubRetrieved(piMock, status);
            PaymentIntent canceled = canceledIntent();
            lenient().when(retrieved.cancel(any(PaymentIntentCancelParams.class), any(RequestOptions.class)))
                    .thenReturn(canceled);

            String result = paymentService.cancelAbandonedPaymentIntent(ABANDONED_INTENT, null);

            assertEquals(status, result);
            verify(retrieved, never()).cancel(any(PaymentIntentCancelParams.class), any(RequestOptions.class));
        }
    }

    @Test
    @DisplayName("issue #1038: a direct-charge intent is retrieved AND cancelled on the connected account")
    void cancelAbandoned_directCharge_usesTheConnectedAccountOnBothCalls() throws Exception {
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            PaymentIntent retrieved = stubRetrieved(piMock, "requires_payment_method");
            PaymentIntent canceled = canceledIntent();
            when(retrieved.cancel(any(PaymentIntentCancelParams.class), any(RequestOptions.class)))
                    .thenReturn(canceled);

            paymentService.cancelAbandonedPaymentIntent(ABANDONED_INTENT, "acct_vendor_1038");

            ArgumentCaptor<RequestOptions> retrieveOptions = ArgumentCaptor.forClass(RequestOptions.class);
            piMock.verify(() -> PaymentIntent.retrieve(eq(ABANDONED_INTENT), retrieveOptions.capture()));
            assertEquals("acct_vendor_1038", retrieveOptions.getValue().getStripeAccount());
            ArgumentCaptor<RequestOptions> cancelOptions = ArgumentCaptor.forClass(RequestOptions.class);
            verify(retrieved).cancel(any(PaymentIntentCancelParams.class), cancelOptions.capture());
            assertEquals("acct_vendor_1038", cancelOptions.getValue().getStripeAccount());
        }
    }

    @Test
    @DisplayName("issue #1038: a platform intent is retrieved and cancelled with no Stripe-Account header")
    void cancelAbandoned_platformIntent_sendsNoStripeAccount() throws Exception {
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            PaymentIntent retrieved = stubRetrieved(piMock, "requires_payment_method");
            PaymentIntent canceled = canceledIntent();
            when(retrieved.cancel(any(PaymentIntentCancelParams.class), any(RequestOptions.class)))
                    .thenReturn(canceled);

            paymentService.cancelAbandonedPaymentIntent(ABANDONED_INTENT, null);

            ArgumentCaptor<RequestOptions> retrieveOptions = ArgumentCaptor.forClass(RequestOptions.class);
            piMock.verify(() -> PaymentIntent.retrieve(eq(ABANDONED_INTENT), retrieveOptions.capture()));
            assertNull(retrieveOptions.getValue().getStripeAccount());
            ArgumentCaptor<RequestOptions> cancelOptions = ArgumentCaptor.forClass(RequestOptions.class);
            verify(retrieved).cancel(any(PaymentIntentCancelParams.class), cancelOptions.capture());
            assertNull(cancelOptions.getValue().getStripeAccount());
        }
    }

    @Test
    @DisplayName("issue #1038: a reference that is not a PaymentIntent id is refused before Stripe")
    void cancelAbandoned_nonIntentReference_isRefusedBeforeStripe() {
        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            assertThrows(IllegalArgumentException.class,
                    () -> paymentService.cancelAbandonedPaymentIntent("plink_1", null));
            assertThrows(IllegalArgumentException.class,
                    () -> paymentService.cancelAbandonedPaymentIntent(null, null));
            piMock.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("issue #1038: only direct-charge and split-deposit routing name a connected account")
    void directChargeAccountFor_onlyDirectAndSplitDepositRoutingNameAnAccount() {
        when(stripeConnectService.resolveSettlementRouting(testOrder)).thenReturn(Optional.of(
                new SettlementRoutingDecision(SettlementPolicyMode.DIRECT_CHARGE_VENDOR_MOR, "acct_v",
                        1500L, 0L, null, null)));
        assertEquals(Optional.of("acct_v"), paymentService.directChargeAccountFor(testOrder));

        when(stripeConnectService.resolveSettlementRouting(testOrder)).thenReturn(Optional.of(
                new SettlementRoutingDecision(SettlementPolicyMode.SPLIT_DEPOSIT_VENDOR_MOR, "acct_v",
                        500L, 0L, 500L, 1000L)));
        assertEquals(Optional.of("acct_v"), paymentService.directChargeAccountFor(testOrder));

        when(stripeConnectService.resolveSettlementRouting(testOrder)).thenReturn(Optional.of(
                new SettlementRoutingDecision(SettlementPolicyMode.DESTINATION_CHARGE_PLATFORM_MOR, "acct_v",
                        1500L, 37L, null, null)));
        assertEquals(Optional.empty(), paymentService.directChargeAccountFor(testOrder));

        when(stripeConnectService.resolveSettlementRouting(testOrder)).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), paymentService.directChargeAccountFor(testOrder));
    }

    @Test
    @DisplayName("issue #1038: payment_intent.canceled for an order that no longer exists completes without reading orders")
    void webhook_paymentIntentCanceled_forAnOrderThatNoLongerExists_completesWithoutReadingOrders() {
        // The intent still names its (now deleted) order, so a handler added for
        // this event later would reach orderRepository and could throw on it.
        PaymentIntent canceled = mock(PaymentIntent.class);
        lenient().when(canceled.getId()).thenReturn(ABANDONED_INTENT);
        lenient().when(canceled.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()));
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(deserializer.getObject()).thenReturn(Optional.of(canceled));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("payment_intent.canceled");
        when(mockEvent.getId()).thenReturn("evt_canceled_1038");
        lenient().when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(mockEvent);

            assertDoesNotThrow(() -> paymentService.handleWebhookEvent("{}", "sig_test"));

            verifyNoInteractions(orderRepository);
        }
    }

    // --- PAY-03 (plan 30-12): one route and one secret per Stripe event scope ---

    @Test
    @DisplayName("T-30-50: a blank Connect secret refuses before any signature verification is attempted")
    void connectWebhook_blankSecret_refusesBeforeVerification() {
        when(stripeProperties.getConnectWebhookSecret()).thenReturn("");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> paymentService.handleConnectWebhookEvent("{}", "t=1,v1=00"));
            assertEquals("Webhook endpoint not configured", refused.getMessage());
            webhookMock.verifyNoInteractions();
        }
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("the Connect route verifies with the Connect secret only and dispatches an event carrying account")
    void connectWebhook_verifiesWithConnectSecretAndDispatches() {
        when(stripeProperties.getConnectWebhookSecret()).thenReturn("whsec_connect");
        Event mockEvent = mock(Event.class);
        when(mockEvent.getAccount()).thenReturn("acct_connected_1");
        when(mockEvent.getType()).thenReturn("account.updated");
        when(mockEvent.getId()).thenReturn("evt_connect_unit_1");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent("{}", "sig", "whsec_connect")).thenReturn(mockEvent);

            paymentService.handleConnectWebhookEvent("{}", "sig");

            webhookMock.verify(() -> Webhook.constructEvent("{}", "sig", "whsec_connect"));
        }
        verify(stripeProperties, never()).getWebhookSecret();
        verify(stripeConnectService).handleAccountUpdated(mockEvent);
    }

    @Test
    @DisplayName("the platform route refuses an event carrying account before the dedup INSERT")
    void platformWebhook_eventWithAccount_isRefusedBeforeDedup() {
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");
        Event mockEvent = mock(Event.class);
        when(mockEvent.getAccount()).thenReturn("acct_connected_1");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(mockEvent);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> paymentService.handleWebhookEvent("{}", "sig"));
            assertEquals("Connect event sent to the platform endpoint", refused.getMessage());
        }
        verifyNoInteractions(jdbcTemplate, stripeConnectService);
    }

    // --- PAY-03 (plan 30-12) Task 2: objects carry their environment; foreign events are acknowledged ---

    /**
     * A PaymentService over a REAL StripeProperties bound through Spring's Binder, so the arms
     * exercise the configuration key itself ({@code stripe.environment-tag}); {@code null} leaves
     * the tag undeclared, as a runtime without STRIPE_ENVIRONMENT_TAG would.
     */
    private PaymentService serviceWithEnvironmentTag(String tag) {
        java.util.Map<String, String> source = new java.util.HashMap<>();
        source.put("stripe.webhook-secret", "whsec_test");
        if (tag != null) {
            source.put("stripe.environment-tag", tag);
        }
        StripeProperties props = new StripeProperties();
        new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(source))
                .bind("stripe", org.springframework.boot.context.properties.bind.Bindable.ofInstance(props));
        return new PaymentService(props, orderRepository, eventPublisher, paymentEventPublisher,
                financialTransactionService, jdbcTemplate, refundService, stripeConnectService,
                providerOf(meterRegistry));
    }

    private com.stripe.param.PaymentIntentCreateParams capturedIntentParams(MockedStatic<PaymentIntent> piMock) {
        ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
        piMock.verify(() -> PaymentIntent.create(paramsCaptor.capture(), any(com.stripe.net.RequestOptions.class)));
        return paramsCaptor.getValue();
    }

    @Test
    @DisplayName("PAY-03: createPaymentIntent tags the intent with metadata environment = stripe.environment-tag")
    void createPaymentIntent_carriesEnvironmentTag() throws Exception {
        PaymentService service = serviceWithEnvironmentTag("staging");
        when(stripeConnectService.resolveDestinationAccount(tenantId)).thenReturn(Optional.empty());
        PaymentIntent mockIntent = mock(PaymentIntent.class);

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                    any(com.stripe.net.RequestOptions.class))).thenReturn(mockIntent);

            service.createPaymentIntent(testOrder);

            assertEquals("staging", capturedIntentParams(piMock).getMetadata().get("environment"));
        }
    }

    @Test
    @DisplayName("PAY-03: createBalancePaymentIntent tags the balance intent with metadata environment")
    void createBalancePaymentIntent_carriesEnvironmentTag() throws Exception {
        PaymentService service = serviceWithEnvironmentTag("staging");
        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.setBalanceDuePennies(700L);
        when(stripeConnectService.resolveSettlementRouting(testOrder)).thenReturn(Optional.empty());
        PaymentIntent mockIntent = mock(PaymentIntent.class);

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                    any(com.stripe.net.RequestOptions.class))).thenReturn(mockIntent);

            service.createBalancePaymentIntent(testOrder);

            assertEquals("staging", capturedIntentParams(piMock).getMetadata().get("environment"));
        }
    }

    @Test
    @DisplayName("PAY-03: an undeclared environment tag is written as \"unset\", never as a real environment name")
    void createPaymentIntent_undeclaredTag_writesUnset() throws Exception {
        PaymentService service = serviceWithEnvironmentTag(null);
        when(stripeConnectService.resolveDestinationAccount(tenantId)).thenReturn(Optional.empty());
        PaymentIntent mockIntent = mock(PaymentIntent.class);

        try (MockedStatic<PaymentIntent> piMock = mockStatic(PaymentIntent.class)) {
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class),
                    any(com.stripe.net.RequestOptions.class))).thenReturn(mockIntent);

            service.createPaymentIntent(testOrder);

            assertEquals("unset", capturedIntentParams(piMock).getMetadata().get("environment"));
        }
    }

    private Event succeededEventWithEnvironment(String eventId, String environment) {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("order_id", orderId.toString());
        metadata.put("tenant_id", tenantId.toString());
        if (environment != null) {
            metadata.put("environment", environment);
        }
        PaymentIntent intent = mock(PaymentIntent.class);
        lenient().when(intent.getId()).thenReturn("pi_env_" + eventId);
        lenient().when(intent.getMetadata()).thenReturn(metadata);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        lenient().when(deserializer.getObject()).thenReturn(Optional.of(intent));
        Event event = mock(Event.class);
        lenient().when(event.getId()).thenReturn(eventId);
        lenient().when(event.getType()).thenReturn("payment_intent.succeeded");
        lenient().when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    @Test
    @DisplayName("PAY-03: a verified event whose object names ANOTHER environment is acknowledged with no dedup row and no side effect")
    void webhook_foreignEnvironment_isAcknowledgedWithoutTrace() {
        PaymentService service = serviceWithEnvironmentTag("staging");
        Event event = succeededEventWithEnvironment("evt_env_foreign", "ci");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);
            assertDoesNotThrow(() -> service.handleWebhookEvent("{}", "sig"));
        }
        verifyNoInteractions(jdbcTemplate, orderRepository, paymentEventPublisher, financialTransactionService);
    }

    @Test
    @DisplayName("PAY-03: an event tagged \"unset\" is same-environment for a runtime whose tag is undeclared")
    void webhook_unsetTaggedEvent_onUndeclaredRuntime_isProcessed() {
        PaymentService service = serviceWithEnvironmentTag(null);
        Event event = succeededEventWithEnvironment("evt_env_unset", "unset");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);
            service.handleWebhookEvent("{}", "sig");
        }
        assertEquals(PaymentStatus.CAPTURED, testOrder.getPaymentStatus());
    }
}
