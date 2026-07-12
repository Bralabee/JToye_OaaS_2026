package uk.jtoye.core.order;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.BusinessMetricsService;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.security.TenantContext;

/**
 * Competing-consumer listener on the durable {@code order.state-changes}
 * queue: at N replicas each event is handled by exactly ONE instance, which is
 * the required semantic for everything in here — customer email, business
 * metrics, and the KDS WebSocket publish (the STOMP relay broker fans that out
 * to every replica's WS clients, so publishing once is correct; publishing
 * from every replica would duplicate).
 *
 * <p><b>Idempotency (QA-council FIX-2 / H1):</b> the transactional outbox is
 * at-least-once BY DESIGN, so this consumer dedups on the semantic key
 * {@code (tenant_id, order_id, new_status)} — the guard-veto-hardened order
 * state machine (#177) never revisits a state, so the key occurs at most once
 * per legitimate lifecycle. The {@code INSERT … ON CONFLICT DO NOTHING} into
 * {@code processed_order_events} (V47, FORCE RLS) mirrors the
 * {@code processed_stripe_events} precedent: 0 rows inserted ⇒ duplicate
 * delivery ⇒ skip ALL side effects. The INSERT sits inside this listener's
 * transaction on purpose — if a side effect throws INTO THIS TRANSACTION
 * (e.g. the order lookup or metrics), the dedup row rolls back too and broker
 * redelivery retries cleanly (DLQ bounds it). Precision note (Stage-4
 * independent verification): the email send is dispatched {@code @Async} and
 * {@code EmailNotificationService} catches {@code MailException} internally,
 * so an SMTP outage does NOT reach this transaction — email delivery is
 * at-most-once once the dedup row commits, exactly as it was pre-dedup.
 *
 * <p>SSE broadcasting deliberately does NOT live here (#92): emitters are
 * per-JVM, so it moved to {@link OrderSseFanoutListener}, which consumes a
 * per-instance fan-out queue and therefore runs on every replica. It is also
 * deliberately NOT deduped — SSE status re-broadcast is idempotent at the UI
 * (state overwrite), and per-replica fan-out queues make shared dedup wrong
 * there.</p>
 */
@Component
public class OrderStateChangeListener {
    private static final Logger log = LoggerFactory.getLogger(OrderStateChangeListener.class);

    private final OrderRepository orderRepository;
    private final EmailNotificationService emailService;
    private final EntityManager entityManager;
    private final BusinessMetricsService metrics;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final JdbcTemplate jdbcTemplate;

    public OrderStateChangeListener(OrderRepository orderRepository,
                                     EmailNotificationService emailService,
                                     EntityManager entityManager,
                                     BusinessMetricsService metrics,
                                     SimpMessagingTemplate simpMessagingTemplate,
                                     JdbcTemplate jdbcTemplate) {
        this.orderRepository = orderRepository;
        this.emailService = emailService;
        this.entityManager = entityManager;
        this.metrics = metrics;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    // Not readOnly: the dedup INSERT below must be able to write (FIX-2).
    @RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
    @Transactional
    public void handleOrderStateChange(OrderStateChangeEvent event) {
        log.info("Order state change received: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());

        // Tenant context FIRST — ThreadLocal AND DB session GUC (N1 fix).
        // Pre-FIX-2 the KDS findById below ran BEFORE the GUC was set, so RLS
        // hid the order and the STOMP broadcast silently never fired.
        TenantContext.set(event.tenantId());
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, event.tenantId().toString());
                stmt.execute();
            }
        });

        try {
            // TOCTOU-safe dedup (H1 fix) — single atomic statement, same shape
            // as the Stripe webhook guard (PaymentService.handleWebhookEvent).
            int inserted = jdbcTemplate.update(
                    "INSERT INTO processed_order_events (tenant_id, order_id, new_status) "
                            + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    event.tenantId(), event.orderId(), event.newStatus().name());
            if (inserted == 0) {
                log.info("Duplicate delivery of order event {} {} -> {} — skipping side effects",
                        event.orderNumber(), event.previousStatus(), event.newStatus());
                return;
            }

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
