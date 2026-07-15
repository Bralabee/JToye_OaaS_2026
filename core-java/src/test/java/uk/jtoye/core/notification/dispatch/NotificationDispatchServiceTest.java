package uk.jtoye.core.notification.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.jtoye.core.notification.NotificationProperties;
import uk.jtoye.core.notification.consent.ConsentGate;
import uk.jtoye.core.notification.consent.NotificationCategory;
import uk.jtoye.core.notification.consent.UnsubscribeTokenService;
import uk.jtoye.core.notification.template.EmailTemplateRenderer;
import uk.jtoye.core.notification.template.RecipientRole;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.onboarding.OnboardingState;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the LOCKED per-family recipient audiences (D-04) and the
 * consent-gated fan-out of {@link NotificationDispatchService} (COMMS-02).
 *
 * <p>The order family is deliberately VENDOR-ONLY on the new path — the
 * customer is served by the untouched legacy {@code OrderStateChangeListener}
 * path, so a customer recipient here would double-email. Refund + payment reach
 * BOTH audiences (both were previously unconsumed). Onboarding is vendor-only.
 */
class NotificationDispatchServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000004a1");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000004b2");
    private static final UUID SHOP_ID = UUID.fromString("00000000-0000-0000-0000-0000000004c3");
    private static final String VENDOR_EMAIL = "vendor@shop.test";
    private static final String CUSTOMER_EMAIL = "customer@buyer.test";

    private TenantRepository tenantRepository;
    private OrderRepository orderRepository;
    private ConsentGate consentGate;
    private EmailChannel emailChannel;
    private WhatsAppSmsChannel whatsAppSmsChannel;
    private NotificationDispatchService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        orderRepository = mock(OrderRepository.class);
        consentGate = mock(ConsentGate.class);
        emailChannel = mock(EmailChannel.class);
        whatsAppSmsChannel = mock(WhatsAppSmsChannel.class);

        Tenant tenant = mock(Tenant.class);
        when(tenant.getContactEmail()).thenReturn(VENDOR_EMAIL);
        when(tenantRepository.findById(TENANT)).thenReturn(Optional.of(tenant));

        Order order = mock(Order.class);
        when(order.getCustomerEmail()).thenReturn(CUSTOMER_EMAIL);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        // Real renderer (no deps), real token machinery configured with a secret
        // so the transactional unsubscribe URL is non-null.
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        UnsubscribeTokenService tokenService = new UnsubscribeTokenService("unit-test-secret");
        NotificationProperties props = new NotificationProperties();
        props.getUnsubscribe().setSigningSecret("unit-test-secret");
        props.getUnsubscribe().setBaseUrl("http://localhost:3000");

        RecipientResolver resolver = new RecipientResolver(tenantRepository, orderRepository);
        service = new NotificationDispatchService(
                resolver, consentGate, renderer, tokenService, props, emailChannel, whatsAppSmsChannel);
    }

    private OrderStateChangeEvent orderEvent() {
        return new OrderStateChangeEvent(ORDER_ID, TENANT, "ORD-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
    }

    private OnboardingStateChangeEvent onboardingEvent() {
        return new OnboardingStateChangeEvent(UUID.randomUUID(), TENANT, SHOP_ID,
                OnboardingState.VERIFYING, "Manual review required", OffsetDateTime.now());
    }

    private RefundEvent refundEvent() {
        return new RefundEvent(UUID.randomUUID(), ORDER_ID, TENANT, "ORD-1", "re_1",
                500, "GBP", RefundEvent.RefundEventType.REFUND_SUCCEEDED, "SUCCEEDED", null,
                OffsetDateTime.now());
    }

    private PaymentEvent paymentEvent() {
        return new PaymentEvent(ORDER_ID, TENANT, "ORD-1", "pi_1", 1200, "GBP",
                PaymentEvent.PaymentEventType.SUCCEEDED, null, OffsetDateTime.now());
    }

    private PaymentEvent paymentFailedEvent() {
        return new PaymentEvent(ORDER_ID, TENANT, "ORD-1", "pi_1", 1200, "GBP",
                PaymentEvent.PaymentEventType.FAILED, "card_declined", OffsetDateTime.now());
    }

    @Test
    @DisplayName("order.state.* dispatches to the VENDOR contact_email ONLY (no customer — no duplicate with the legacy path)")
    void orderStateEvent_dispatchesToVendorOnly() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("order.state.changed", TENANT, orderEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(1)).deliver(cap.capture());
        assertThat(cap.getValue().recipient()).isEqualTo(VENDOR_EMAIL);
        assertThat(cap.getAllValues())
                .as("the new order path must NOT send to the customer (legacy path already does)")
                .noneMatch(m -> CUSTOMER_EMAIL.equals(m.recipient()));
    }

    @Test
    @DisplayName("onboarding.state.* dispatches to the vendor ONLY (D-04, no platform operator)")
    void onboardingEvent_dispatchesToVendorOnly() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("onboarding.state.changed", TENANT, onboardingEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(1)).deliver(cap.capture());
        assertThat(cap.getValue().recipient()).isEqualTo(VENDOR_EMAIL);
    }

    @Test
    @DisplayName("order.refunded dispatches to BOTH the customer email AND the vendor contact_email")
    void refundEvent_dispatchesToBoth() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("order.refunded", TENANT, refundEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(2)).deliver(cap.capture());
        assertThat(cap.getAllValues()).extracting(NotificationMessage::recipient)
                .containsExactlyInAnyOrder(CUSTOMER_EMAIL, VENDOR_EMAIL);
    }

    @Test
    @DisplayName("payment.* dispatches to BOTH the customer email AND the vendor contact_email")
    void paymentEvent_dispatchesToBoth() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("payment.succeeded", TENANT, paymentEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(2)).deliver(cap.capture());
        assertThat(cap.getAllValues()).extracting(NotificationMessage::recipient)
                .containsExactlyInAnyOrder(CUSTOMER_EMAIL, VENDOR_EMAIL);
    }

    @Test
    @DisplayName("WR-02 — payment.failed dispatch renders failure copy (modelFor plumbs the payment type through)")
    void paymentFailed_rendersFailureCopy_notSuccess() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("payment.failed", TENANT, paymentFailedEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(2)).deliver(cap.capture());
        assertThat(cap.getAllValues())
                .as("no failed-payment email may thank the recipient or claim receipt")
                .allSatisfy(m -> {
                    String html = m.email().html().toLowerCase();
                    assertThat(html).doesNotContain("thank you");
                    assertThat(html).doesNotContain("received");
                    assertThat(html).contains("fail");
                });
    }

    @Test
    @DisplayName("WR-02 — payment.succeeded dispatch still renders the success copy")
    void paymentSucceeded_rendersSuccessCopy() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("payment.succeeded", TENANT, paymentEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(2)).deliver(cap.capture());
        assertThat(cap.getAllValues())
                .as("successful-payment emails confirm receipt")
                .allSatisfy(m -> assertThat(m.email().html().toLowerCase()).contains("received"));
    }

    @Test
    @DisplayName("a suppressed recipient (consent gate false) is NEVER delivered to")
    void suppressedRecipient_notDelivered() {
        // Gate refuses every send.
        when(consentGate.allows(any(), any(), any())).thenReturn(false);

        service.dispatch("order.state.changed", TENANT, orderEvent());

        verify(emailChannel, never()).deliver(any());
        verify(whatsAppSmsChannel, never()).deliver(any());
    }

    @Test
    @DisplayName("a transactional message carries a non-null one-click unsubscribe URL")
    void transactionalMessage_carriesUnsubscribeUrl() {
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        service.dispatch("order.state.changed", TENANT, orderEvent());

        ArgumentCaptor<NotificationMessage> cap = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(1)).deliver(cap.capture());
        String unsub = cap.getValue().unsubscribeUrl();
        assertThat(unsub).isNotNull();
        assertThat(unsub).contains(TENANT.toString());
        assertThat(unsub).contains("token=");
    }

    @Test
    @DisplayName("RecipientResolver classifies order.state.* vs order.refunded vs payment.* vs onboarding.state.* correctly")
    void recipientResolver_classifiesFamilies() {
        assertThat(RecipientResolver.Family.classify("order.state.changed"))
                .isEqualTo(RecipientResolver.Family.ORDER_STATE);
        assertThat(RecipientResolver.Family.classify("order.refunded"))
                .isEqualTo(RecipientResolver.Family.ORDER_REFUND);
        assertThat(RecipientResolver.Family.classify("payment.succeeded"))
                .isEqualTo(RecipientResolver.Family.PAYMENT);
        assertThat(RecipientResolver.Family.classify("onboarding.state.changed"))
                .isEqualTo(RecipientResolver.Family.ONBOARDING);
    }

    @Test
    @DisplayName("order.state.* resolves exactly one VENDOR recipient; onboarding resolves exactly one vendor recipient")
    void recipientResolver_vendorOnlyFamilies() {
        RecipientResolver resolver = new RecipientResolver(tenantRepository, orderRepository);

        List<RecipientResolver.Recipient> order =
                resolver.forEvent("order.state.changed", TENANT, orderEvent());
        assertThat(order).extracting(RecipientResolver.Recipient::role)
                .containsExactly(RecipientRole.VENDOR);
        assertThat(order).extracting(RecipientResolver.Recipient::email)
                .containsExactly(VENDOR_EMAIL);

        List<RecipientResolver.Recipient> onboarding =
                resolver.forEvent("onboarding.state.changed", TENANT, onboardingEvent());
        assertThat(onboarding).extracting(RecipientResolver.Recipient::role)
                .containsExactly(RecipientRole.VENDOR);
    }
}
