package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class OrderEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishStateChange(UUID orderId, UUID tenantId, String orderNumber,
                                   OrderStatus previousStatus, OrderStatus newStatus) {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, tenantId, orderNumber, previousStatus, newStatus, OffsetDateTime.now()
        );

        String routingKey = "order.state." + newStatus.name().toLowerCase();

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_EVENTS_EXCHANGE,
                    routingKey,
                    event
            );
            log.info("Published order state change: {} -> {} for order {}",
                    previousStatus, newStatus, orderNumber);
        } catch (Exception e) {
            log.error("Failed to publish order state change event for order {}: {}",
                    orderNumber, e.getMessage());
        }
    }
}
