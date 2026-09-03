package uk.jtoye.core.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailNotificationService.
 * Verifies email construction, conditional sending, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Captor
    private ArgumentCaptor<SimpleMailMessage> messageCaptor;

    private EmailNotificationService service;

    private static final String FROM_ADDRESS = "noreply@jtoye.uk";
    private static final String TRACKING_BASE_URL = "https://shop.jtoye.uk";
    private static final String RECIPIENT = "customer@example.com";

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", FROM_ADDRESS);
        ReflectionTestUtils.setField(service, "emailEnabled", true);
        ReflectionTestUtils.setField(service, "trackingBaseUrl", TRACKING_BASE_URL);
    }

    private OrderStateChangeEvent createTestEvent(String orderNumber,
                                                   OrderStatus previous,
                                                   OrderStatus next) {
        return new OrderStateChangeEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderNumber,
                previous,
                next,
                OffsetDateTime.now()
        );
    }

    private OrderStateChangeEvent createTestEvent() {
        return createTestEvent("ORD-100", OrderStatus.PENDING, OrderStatus.CONFIRMED);
    }

    // --- Order confirmation ---

    @Test
    @DisplayName("sendOrderConfirmation - Sends email with correct subject and tracking link")
    void testSendOrderConfirmation() {
        OrderStateChangeEvent event = createTestEvent();

        service.sendOrderConfirmation(event, RECIPIENT);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals(FROM_ADDRESS, msg.getFrom());
        assertArrayEquals(new String[]{RECIPIENT}, msg.getTo());
        assertEquals("Order ORD-100 \u2014 Received", msg.getSubject());
        assertTrue(msg.getText().contains("ORD-100"));
        assertTrue(msg.getText().contains(TRACKING_BASE_URL + "/track?order=ORD-100"));
    }

    // --- Order confirmed ---

    @Test
    @DisplayName("sendOrderConfirmed - Subject contains Confirmed and body has tracking link")
    void testSendOrderConfirmed() {
        OrderStateChangeEvent event = createTestEvent();

        service.sendOrderConfirmed(event, RECIPIENT);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals("Order ORD-100 \u2014 Confirmed", msg.getSubject());
        assertTrue(msg.getText().contains("has been confirmed"));
        assertTrue(msg.getText().contains("/track?order=ORD-100"));
    }

    // --- Order preparing ---

    @Test
    @DisplayName("sendOrderPreparing - Subject contains Being Prepared")
    void testSendOrderPreparing() {
        OrderStateChangeEvent event = createTestEvent("ORD-200",
                OrderStatus.CONFIRMED, OrderStatus.PREPARING);

        service.sendOrderPreparing(event, RECIPIENT);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals("Order ORD-200 \u2014 Being Prepared", msg.getSubject());
        assertTrue(msg.getText().contains("now being prepared"));
    }

    // --- Order ready ---

    @Test
    @DisplayName("sendOrderReady - COLLECTION keeps the existing subject and collection copy")
    void testSendOrderReadyCollection() {
        OrderStateChangeEvent event = createTestEvent("ORD-300",
                OrderStatus.PREPARING, OrderStatus.READY);

        service.sendOrderReady(event, RECIPIENT, FulfilmentType.COLLECTION);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals("Order ORD-300 \u2014 Ready!", msg.getSubject());
        assertTrue(msg.getText().contains("ready for collection"));
    }

    @Test
    @DisplayName("sendOrderReady - DELIVERY says it will be delivered and never says collect (#502)")
    void testSendOrderReadyDelivery() {
        OrderStateChangeEvent event = createTestEvent("ORD-301",
                OrderStatus.PREPARING, OrderStatus.READY);

        service.sendOrderReady(event, RECIPIENT, FulfilmentType.DELIVERY);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals("Order ORD-301 \u2014 Ready!", msg.getSubject());
        assertTrue(msg.getText().contains("deliver it to the address on your order"));
        assertFalse(msg.getText().toLowerCase().contains("collect"),
                "a DELIVERY customer must never be told to collect (#502)");
        assertTrue(msg.getText().contains("/track?order=ORD-301"));
    }

    /**
     * {@code orders.fulfilment_type} is {@code NOT NULL DEFAULT 'DELIVERY'} (V45),
     * so null is unreachable through the schema \u2014 but if it ever were, the copy
     * must not fall back to "come and collect", which is the actively harmful
     * answer when the fulfilment mode is unknown.
     *
     * <p>COR-1 note (2026-09-02): since both order-creation paths now set the fulfilment
     * type explicitly ({@code FulfilmentPolicy}), the column default is a history device
     * only. This arm asserts a safety property on an unreachable input; that is its value,
     * and it is recorded here so nobody deletes it as "dead".
     */
    @Test
    @DisplayName("sendOrderReady - null fulfilment type falls back to DELIVERY copy (#502)")
    void testSendOrderReadyNullFulfilmentType() {
        OrderStateChangeEvent event = createTestEvent("ORD-302",
                OrderStatus.PREPARING, OrderStatus.READY);

        service.sendOrderReady(event, RECIPIENT, null);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertFalse(msg.getText().toLowerCase().contains("collect"));
        assertTrue(msg.getText().contains("we'll deliver it"));
    }

    // --- Order completed ---

    @Test
    @DisplayName("sendOrderCompletedNotification - Subject contains Completed")
    void testSendOrderCompleted() {
        OrderStateChangeEvent event = createTestEvent("ORD-400",
                OrderStatus.READY, OrderStatus.COMPLETED);

        service.sendOrderCompletedNotification(event, RECIPIENT);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals("Order ORD-400 \u2014 Completed", msg.getSubject());
        assertTrue(msg.getText().contains("has been completed"));
    }

    // --- Order cancelled ---

    @Test
    @DisplayName("sendOrderCancelledNotification - Subject contains Cancelled and body shows previous status")
    void testSendOrderCancelled() {
        OrderStateChangeEvent event = createTestEvent("ORD-500",
                OrderStatus.PREPARING, OrderStatus.CANCELLED);

        service.sendOrderCancelledNotification(event, RECIPIENT);

        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage msg = messageCaptor.getValue();

        assertEquals("Order ORD-500 \u2014 Cancelled", msg.getSubject());
        assertTrue(msg.getText().contains("has been cancelled"));
        assertTrue(msg.getText().contains("PREPARING"));
    }

    // --- Conditional sending ---

    @Test
    @DisplayName("sendNotification - Skips sending when emailEnabled is false")
    void testSkipsWhenDisabled() {
        ReflectionTestUtils.setField(service, "emailEnabled", false);

        service.sendOrderConfirmation(createTestEvent(), RECIPIENT);

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("sendNotification - Skips sending when recipient email is null")
    void testSkipsWhenEmailNull() {
        service.sendOrderConfirmation(createTestEvent(), null);

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("sendNotification - Skips sending when recipient email is blank")
    void testSkipsWhenEmailBlank() {
        service.sendOrderConfirmation(createTestEvent(), "   ");

        verifyNoInteractions(mailSender);
    }

    // --- Error handling ---

    @Test
    @DisplayName("send - Handles MailException gracefully without propagating")
    void testHandlesMailException() {
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() ->
                service.sendOrderConfirmation(createTestEvent(), RECIPIENT));

        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}
