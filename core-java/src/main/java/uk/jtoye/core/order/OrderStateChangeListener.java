package uk.jtoye.core.order;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.BusinessMetricsService;
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
    private final BusinessMetricsService metrics;
    private final SimpMessagingTemplate simpMessagingTemplate;

    public OrderStateChangeListener(OrderSseService sseService,
                                     OrderRepository orderRepository,
                                     EmailNotificationService emailService,
                                     EntityManager entityManager,
                                     BusinessMetricsService metrics,
                                     SimpMessagingTemplate simpMessagingTemplate) {
        this.sseService = sseService;
        this.orderRepository = orderRepository;
        this.emailService = emailService;
        this.entityManager = entityManager;
        this.metrics = metrics;
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
    @Transactional(readOnly = true)
    public void handleOrderStateChange(OrderStateChangeEvent event) {
        log.info("Order state change received: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());

        // Broadcast to SSE clients for real-time UI updates
        sseService.broadcast(event);

        // WebSocket broadcast to KDS topic (fire-and-forget per D-06)
        try {
            orderRepository.findById(event.orderId()).ifPresent(order -> {
                if (order.getShopId() != null) {
                    String topic = "/topic/kitchen/" + event.tenantId() + "/" + order.getShopId();
                    simpMessagingTemplate.convertAndSend(topic, event);
                    log.debug("WebSocket broadcast to {} for order {}", topic, event.orderNumber());
                }
            });
        } catch (Exception e) {
            log.warn("WebSocket broadcast failed for order {}: {}", event.orderNumber(), e.getMessage());
        }

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
        // Track business metrics
        switch (event.newStatus()) {
            case PENDING -> metrics.recordOrderCreated();
            case COMPLETED -> orderRepository.findById(event.orderId())
                    .ifPresent(o -> metrics.recordOrderCompleted(o.getTotalAmountPennies()));
            case CANCELLED -> metrics.recordOrderCancelled();
            default -> {} // no metric for intermediate states
        }

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
