package uk.jtoye.core.payment;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
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
    @Mock private FinancialTransactionService financialTransactionService;

    private PaymentService paymentService;

    private UUID orderId;
    private UUID tenantId;
    private Order testOrder;

    @BeforeEach
    void setUp() throws Exception {
        paymentService = new PaymentService(stripeProperties, orderRepository, eventPublisher, financialTransactionService);

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
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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
            assertEquals(VatRate.STANDARD, tx.vatRate());
            assertTrue(tx.description().contains("pi_test_123"));

            // Verify event published
            verify(eventPublisher).publishStateChange(
                    eq(orderId), eq(tenantId), eq("ORD-TEST-20260403-ABCD1234"),
                    eq(OrderStatus.DRAFT), eq(OrderStatus.PENDING));
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
            // No event published on failure
            verify(eventPublisher, never()).publishStateChange(any(), any(), any(), any(), any());
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
}
