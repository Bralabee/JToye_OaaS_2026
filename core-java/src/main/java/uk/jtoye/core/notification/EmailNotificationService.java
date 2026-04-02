package uk.jtoye.core.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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

    @Async
    public void sendOrderReady(OrderStateChangeEvent event, String recipientEmail) {
        sendNotification(event, recipientEmail,
                "Order " + event.orderNumber() + " — Ready!",
                """
                Your order %s is ready for collection!

                Please pick it up at your earliest convenience.

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
