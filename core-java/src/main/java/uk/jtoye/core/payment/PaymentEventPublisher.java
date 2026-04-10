package uk.jtoye.core.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes {@link PaymentEvent} messages to the payment.events topic exchange.
 * Fire-and-forget: publish failures are logged but never propagate to the caller,
 * so a RabbitMQ outage cannot block Stripe webhook processing.
 */
@Component
public class PaymentEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public PaymentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishSucceeded(UUID orderId, UUID tenantId, String orderNumber,
                                 String paymentIntentId, long amountPennies, String currency) {
        publish(new PaymentEvent(
                orderId, tenantId, orderNumber, paymentIntentId,
                amountPennies, currency, PaymentEvent.PaymentEventType.SUCCEEDED,
                null, OffsetDateTime.now()
        ));
    }

    public void publishFailed(UUID orderId, UUID tenantId, String orderNumber,
                              String paymentIntentId, long amountPennies, String currency,
                              String failureReason) {
        publish(new PaymentEvent(
                orderId, tenantId, orderNumber, paymentIntentId,
                amountPennies, currency, PaymentEvent.PaymentEventType.FAILED,
                failureReason, OffsetDateTime.now()
        ));
    }

    private void publish(PaymentEvent event) {
        String routingKey = "payment." + event.type().name().toLowerCase();
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE,
                    routingKey,
                    event
            );
            log.info("Published payment event {}: order={} pi={}",
                    event.type(), event.orderNumber(), event.paymentIntentId());
        } catch (Exception e) {
            log.error("Failed to publish payment event for order {}: {}",
                    event.orderNumber(), e.getMessage());
        }
    }
}
