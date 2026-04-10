package uk.jtoye.core.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;

/**
 * Consumes payment events from RabbitMQ and emits a structured audit log.
 * Kept deliberately simple: this is the first consumer of the payment bus,
 * proving the topology end-to-end. Future consumers (reconciliation, analytics,
 * customer notifications) can bind additional queues to {@code payment.events}.
 */
@Component
public class PaymentEventAuditListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventAuditListener.class);

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_EVENTS_QUEUE)
    public void onPaymentEvent(PaymentEvent event) {
        if (event.type() == PaymentEvent.PaymentEventType.SUCCEEDED) {
            log.info("AUDIT payment.succeeded tenant={} order={} pi={} amount={} {}",
                    event.tenantId(), event.orderNumber(), event.paymentIntentId(),
                    event.amountPennies(), event.currency());
        } else {
            log.warn("AUDIT payment.failed tenant={} order={} pi={} amount={} {} reason={}",
                    event.tenantId(), event.orderNumber(), event.paymentIntentId(),
                    event.amountPennies(), event.currency(), event.failureReason());
        }
    }
}
