package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.notification.EmailNotificationService;

@Component
public class OrderStateChangeListener {
    private static final Logger log = LoggerFactory.getLogger(OrderStateChangeListener.class);

    private final OrderSseService sseService;
    private final OrderRepository orderRepository;
    private final EmailNotificationService emailService;

    public OrderStateChangeListener(OrderSseService sseService,
                                     OrderRepository orderRepository,
                                     EmailNotificationService emailService) {
        this.sseService = sseService;
        this.orderRepository = orderRepository;
        this.emailService = emailService;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
    public void handleOrderStateChange(OrderStateChangeEvent event) {
        log.info("Order state change received: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());

        // Broadcast to SSE clients for real-time UI updates
        sseService.broadcast(event);

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

        sendEmailNotification(event, true);
    }

    private void handleOrderCancelled(OrderStateChangeEvent event) {
        log.info("Order {} cancelled for tenant {} (was {})",
                event.orderNumber(), event.tenantId(), event.previousStatus());

        sendEmailNotification(event, false);
    }

    private void sendEmailNotification(OrderStateChangeEvent event, boolean completed) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            String email = order.getCustomerEmail();
            if (completed) {
                emailService.sendOrderCompletedNotification(event, email);
            } else {
                emailService.sendOrderCancelledNotification(event, email);
            }
        });
    }
}
