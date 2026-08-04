package uk.jtoye.core.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderStateChangeEvent;

import java.time.format.DateTimeFormatter;

@Service
public class EmailNotificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final JavaMailSender mailSender;

    @Value("${notification.email.from:noreply@jtoye.uk}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean emailEnabled;

    @Value("${notification.email.tracking-base-url:http://localhost:3000}")
    private String trackingBaseUrl;

    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendOrderConfirmation(OrderStateChangeEvent event, String recipientEmail) {
        sendNotification(event, recipientEmail,
                "Order " + event.orderNumber() + " — Received",
                """
                We've received your order %s.

                The shop will confirm it shortly. You'll receive an update when they start preparing it.

                %s

                — J'Toye""");
    }

    @Async
    public void sendOrderConfirmed(OrderStateChangeEvent event, String recipientEmail) {
        sendNotification(event, recipientEmail,
                "Order " + event.orderNumber() + " — Confirmed",
                """
                Great news! Your order %s has been confirmed by the shop.

                Preparation will begin soon.

                %s

                — J'Toye""");
    }

    @Async
    public void sendOrderPreparing(OrderStateChangeEvent event, String recipientEmail) {
        sendNotification(event, recipientEmail,
                "Order " + event.orderNumber() + " — Being Prepared",
                """
                Your order %s is now being prepared.

                We'll let you know when it's ready.

                %s

                — J'Toye""");
    }

    /**
     * READY copy, branched on how the order is fulfilled (#502).
     *
     * <p>This transition previously sent collection-only copy unconditionally, so
     * every DELIVERY customer was told to come and pick the order up. That is not
     * a rare path: {@code orders.fulfilment_type} is {@code NOT NULL DEFAULT
     * 'DELIVERY'} (V45) and {@code Order} defaults the field to
     * {@link FulfilmentType#DELIVERY}, so every order created outside the
     * storefront checkout — which is the only writer that sets the value
     * explicitly — is a DELIVERY order.
     *
     * <p>A {@code null} type resolves to the DELIVERY copy, matching the column
     * default. The asymmetry is deliberate: "come and collect" is the actively
     * harmful answer when the fulfilment mode is unknown, so it is never the
     * fallback.
     *
     * <p><b>#458 note:</b> the DELIVERY copy says the order is ready and will be
     * on its way, NOT that it is out for delivery. There is no {@code DISPATCHED}
     * value on {@code OrderStatus} and no such edge in
     * {@code OrderStateMachineConfig}, so READY cannot truthfully claim the order
     * has left the shop. "Out for delivery" is left free for the real dispatch
     * state if #458 introduces one.
     */
    @Async
    public void sendOrderReady(OrderStateChangeEvent event, String recipientEmail,
                                FulfilmentType fulfilmentType) {
        String subject = "Order " + event.orderNumber() + " — Ready!";

        if (fulfilmentType == FulfilmentType.COLLECTION) {
            sendNotification(event, recipientEmail, subject,
                    """
                    Your order %s is ready for collection!

                    Please pick it up at your earliest convenience.

                    %s

                    — J'Toye""");
            return;
        }

        sendNotification(event, recipientEmail, subject,
                """
                Your order %s is ready and will be on its way to you shortly.

                There's no need to come to the shop — we'll deliver it to the address on your order.

                %s

                — J'Toye""");
    }

    @Async
    public void sendOrderCompletedNotification(OrderStateChangeEvent event, String recipientEmail) {
        sendNotification(event, recipientEmail,
                "Order " + event.orderNumber() + " — Completed",
                """
                Your order %s has been completed.

                Thank you for your business! We hope to see you again soon.

                %s

                — J'Toye""");
    }

    @Async
    public void sendOrderCancelledNotification(OrderStateChangeEvent event, String recipientEmail) {
        sendNotification(event, recipientEmail,
                "Order " + event.orderNumber() + " — Cancelled",
                """
                Your order %s has been cancelled.

                Previous status: %s
                If this was unexpected, please contact the shop directly.

                %s

                — J'Toye""".formatted(
                        event.orderNumber(),
                        event.previousStatus(),
                        trackingLink(event)
                ));
    }

    private void sendNotification(OrderStateChangeEvent event, String recipientEmail,
                                   String subject, String bodyTemplate) {
        if (!emailEnabled || recipientEmail == null || recipientEmail.isBlank()) {
            log.debug("Email notification skipped for order {} (enabled={}, email={})",
                    event.orderNumber(), emailEnabled, recipientEmail);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipientEmail);
        message.setSubject(subject);
        message.setText(bodyTemplate.formatted(event.orderNumber(), trackingLink(event)));

        send(message, event.orderNumber());
    }

    private String trackingLink(OrderStateChangeEvent event) {
        return "Track your order: " + trackingBaseUrl + "/track?order=" + event.orderNumber();
    }

    private void send(SimpleMailMessage message, String orderNumber) {
        try {
            mailSender.send(message);
            log.info("Email notification sent for order {} to {}", orderNumber, message.getTo());
        } catch (MailException e) {
            log.error("Failed to send email for order {}: {}", orderNumber, e.getMessage());
        }
    }
}
