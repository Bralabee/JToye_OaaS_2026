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

/**
 * Sends email notifications for order state changes.
 * Async to avoid blocking the RabbitMQ listener thread.
 */
@Service
public class EmailNotificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final JavaMailSender mailSender;

    @Value("${notification.email.from:noreply@jtoye.uk}")
    private String fromAddress;

    @Value("${notification.email.enabled:false}")
    private boolean emailEnabled;

    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendOrderCompletedNotification(OrderStateChangeEvent event, String recipientEmail) {
        if (!emailEnabled || recipientEmail == null || recipientEmail.isBlank()) {
            log.debug("Email notification skipped for order {} (enabled={}, email={})",
                    event.orderNumber(), emailEnabled, recipientEmail);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipientEmail);
        message.setSubject("Order " + event.orderNumber() + " — Completed");
        message.setText(String.format(
                """
                Your order %s has been completed.

                Completed at: %s

                Thank you for your business!

                — J'Toye""",
                event.orderNumber(),
                event.timestamp().format(FORMATTER)
        ));

        send(message, event.orderNumber());
    }

    @Async
    public void sendOrderCancelledNotification(OrderStateChangeEvent event, String recipientEmail) {
        if (!emailEnabled || recipientEmail == null || recipientEmail.isBlank()) {
            log.debug("Email notification skipped for order {} (enabled={}, email={})",
                    event.orderNumber(), emailEnabled, recipientEmail);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipientEmail);
        message.setSubject("Order " + event.orderNumber() + " — Cancelled");
        message.setText(String.format(
                """
                Your order %s has been cancelled.

                Previous status: %s
                Cancelled at: %s

                If this was unexpected, please contact us.

                — J'Toye""",
                event.orderNumber(),
                event.previousStatus(),
                event.timestamp().format(FORMATTER)
        ));

        send(message, event.orderNumber());
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
