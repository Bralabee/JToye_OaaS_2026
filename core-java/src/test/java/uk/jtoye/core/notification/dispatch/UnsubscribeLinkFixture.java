package uk.jtoye.core.notification.dispatch;

import org.mockito.ArgumentCaptor;
import uk.jtoye.core.notification.NotificationProperties;
import uk.jtoye.core.notification.consent.ConsentGate;
import uk.jtoye.core.notification.consent.UnsubscribeTokenService;
import uk.jtoye.core.notification.template.EmailTemplateRenderer;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL {@link NotificationDispatchService} for a given pair of
 * configured origins and hands back the two unsubscribe URLs an email would
 * actually carry.
 *
 * <p>Deliberately not a URL builder: {@link UnsubscribeLinkRoutingTest} must
 * assert against what production composes, not against a second implementation
 * that could drift into agreeing with itself.
 */
final class UnsubscribeLinkFixture {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000516a");
    static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-00000000516b");
    static final String VENDOR_EMAIL = "vendor+unsub@shop.test";
    /** Shared with the integration test so a token minted here verifies in the running app. */
    static final String SECRET = "routing-test-unsubscribe-secret";

    private UnsubscribeLinkFixture() {
    }

    /** The URL a recipient CLICKS (a browser GET) — must land on a page, not an API. */
    static String pageUrl(String appOrigin, String oneClickOrigin) {
        return dispatchAndCapture(appOrigin, oneClickOrigin).unsubscribeUrl();
    }

    /** The URL stamped into {@code List-Unsubscribe} — must accept an RFC 8058 POST. */
    static String oneClickUrl(String appOrigin, String oneClickOrigin) {
        return dispatchAndCapture(appOrigin, oneClickOrigin).oneClickUnsubscribeUrl();
    }

    static NotificationMessage dispatchAndCapture(String appOrigin, String oneClickOrigin) {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.getContactEmail()).thenReturn(VENDOR_EMAIL);
        when(tenantRepository.findById(TENANT)).thenReturn(Optional.of(tenant));

        OrderRepository orderRepository = mock(OrderRepository.class);
        Order order = mock(Order.class);
        when(order.getCustomerEmail()).thenReturn("customer+unsub@buyer.test");
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        ConsentGate consentGate = mock(ConsentGate.class);
        when(consentGate.allows(any(), any(), any())).thenReturn(true);

        EmailChannel emailChannel = mock(EmailChannel.class);
        WhatsAppSmsChannel whatsAppSmsChannel = mock(WhatsAppSmsChannel.class);

        NotificationProperties props = properties(appOrigin, oneClickOrigin);
        NotificationDispatchService service = new NotificationDispatchService(
                new RecipientResolver(tenantRepository, orderRepository),
                consentGate,
                new EmailTemplateRenderer(),
                new UnsubscribeTokenService(SECRET),
                props,
                emailChannel,
                whatsAppSmsChannel);

        service.dispatch("order.state.changed", TENANT, new OrderStateChangeEvent(
                ORDER_ID, TENANT, "ORD-516", OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now()));

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailChannel, times(1)).deliver(captor.capture());
        return captor.getValue();
    }

    /** Config exactly as an environment supplies it: the app origin and the API origin, separately. */
    static NotificationProperties properties(String appOrigin, String oneClickOrigin) {
        NotificationProperties props = new NotificationProperties();
        props.getUnsubscribe().setSigningSecret(SECRET);
        props.getUnsubscribe().setBaseUrl(appOrigin);
        props.getUnsubscribe().setOneClickBaseUrl(oneClickOrigin);
        return props;
    }
}
