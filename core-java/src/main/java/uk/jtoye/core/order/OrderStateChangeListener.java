package uk.jtoye.core.order;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.security.TenantContext;

@Component
public class OrderStateChangeListener {
    private static final Logger log = LoggerFactory.getLogger(OrderStateChangeListener.class);

    private final OrderSseService sseService;
    private final OrderRepository orderRepository;
    private final EmailNotificationService emailService;
    private final EntityManager entityManager;

    public OrderStateChangeListener(OrderSseService sseService,
                                     OrderRepository orderRepository,
                                     EmailNotificationService emailService,
                                     EntityManager entityManager) {
        this.sseService = sseService;
        this.orderRepository = orderRepository;
        this.emailService = emailService;
        this.entityManager = entityManager;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
    @Transactional(readOnly = true)
    public void handleOrderStateChange(OrderStateChangeEvent event) {
        log.info("Order state change received: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());

        // Broadcast to SSE clients for real-time UI updates
        sseService.broadcast(event);

        // Set tenant context at both ThreadLocal and DB session level
        TenantContext.set(event.tenantId());
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, event.tenantId().toString());
                stmt.execute();
            }
        });

        try {
            sendEmailForState(event);
        } finally {
            TenantContext.clear();
        }
    }

    private void sendEmailForState(OrderStateChangeEvent event) {
        orderRepository.findById(event.orderId()).ifPresentOrElse(order -> {
            String email = order.getCustomerEmail();
            if (email == null || email.isBlank()) {
                log.debug("No customer email for order {}, skipping notification", event.orderNumber());
                return;
            }

            log.info("Sending {} notification for order {} to {}", event.newStatus(), event.orderNumber(), email);

            switch (event.newStatus()) {
                case PENDING -> emailService.sendOrderConfirmation(event, email);
                case CONFIRMED -> emailService.sendOrderConfirmed(event, email);
                case PREPARING -> emailService.sendOrderPreparing(event, email);
                case READY -> emailService.sendOrderReady(event, email);
                case COMPLETED -> emailService.sendOrderCompletedNotification(event, email);
                case CANCELLED -> emailService.sendOrderCancelledNotification(event, email);
                default -> log.debug("No email template for status {}", event.newStatus());
            }
        }, () -> log.warn("Order {} not found for email notification", event.orderNumber()));
    }
}
