package uk.jtoye.core.payment;

import com.stripe.exception.SignatureVerificationException;
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
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class)))
                    .thenReturn(mockIntent);

            String clientSecret = paymentService.createPaymentIntent(testOrder);
            assertEquals("pi_dest_1_secret_x", clientSecret);

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            piMock.verify(() -> PaymentIntent.create(captor.capture()));
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
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class)))
                    .thenReturn(mockIntent);

            paymentService.createPaymentIntent(testOrder);

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            piMock.verify(() -> PaymentIntent.create(captor.capture()));

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
            piMock.when(() -> PaymentIntent.create(any(com.stripe.param.PaymentIntentCreateParams.class)))
                    .thenReturn(mockIntent);

            paymentService.createPaymentIntent(testOrder);

            ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
            piMock.verify(() -> PaymentIntent.create(captor.capture()));

            assertNull(captor.getValue().getTransferData());
            assertNull(captor.getValue().getApplicationFeeAmount());
            assertEquals(1500L, captor.getValue().getAmount());
            assertEquals("gbp", captor.getValue().getCurrency());
            verify(stripeConnectService, never()).applicationFeePennies(anyLong());
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
}
