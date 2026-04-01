package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;

@Component
public class OrderStateChangeListener {
    private static final Logger log = LoggerFactory.getLogger(OrderStateChangeListener.class);

    @RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
    public void handleOrderStateChange(OrderStateChangeEvent event) {
        log.info("Order state change received: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());

        switch (event.newStatus()) {
            case COMPLETED -> handleOrderCompleted(event);
            case CANCELLED -> handleOrderCancelled(event);
            default -> log.debug("Order {} transitioned to {} — no action required",
                    event.orderNumber(), event.newStatus());
        }
    }

    private void handleOrderCompleted(OrderStateChangeEvent event) {
        log.info("Order {} completed for tenant {} at {}",
                event.orderNumber(), event.tenantId(), event.timestamp());
        // Extension point: trigger email notification, webhook, analytics update
    }

    private void handleOrderCancelled(OrderStateChangeEvent event) {
        log.info("Order {} cancelled for tenant {} (was {})",
                event.orderNumber(), event.tenantId(), event.previousStatus());
        // Extension point: trigger refund workflow, customer notification
    }
}
