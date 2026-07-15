package uk.jtoye.core.notification.listener;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.notification.dispatch.NotificationDispatchService;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

/**
 * The NEW financial email path (COMMS-02). Two consumers, each on its OWN
 * durable queue:
 * <ul>
 *   <li>{@code payment.notifications} ({@code payment.*}) — a SECOND queue on
 *       {@code payment.events} that does NOT compete with the incumbent
 *       {@code PaymentEventAuditListener} (which keeps its audit copy). Payment
 *       was audit-ONLY before this plan; now it emails BOTH the customer and the
 *       vendor.</li>
 *   <li>{@code refund.notifications} ({@code order.refunded}) — closes a latent
 *       gap: {@code order.refunded} matched NO binding, so refund events were
 *       discarded entirely. Now they email BOTH the customer and the vendor.</li>
 * </ul>
 * Recipient resolution + consent gating live in {@link NotificationDispatchService}
 * (D-04). Same tenant preamble as {@code OrderStateChangeListener} §83-90.
 */
@Component
public class FinancialNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(FinancialNotificationListener.class);

    private final NotificationDispatchService dispatchService;
    private final EntityManager entityManager;

    public FinancialNotificationListener(NotificationDispatchService dispatchService, EntityManager entityManager) {
        this.dispatchService = dispatchService;
        this.entityManager = entityManager;
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_NOTIFICATIONS_QUEUE)
    @Transactional
    public void handlePaymentNotification(PaymentEvent event) {
        String eventType = event.type() == PaymentEvent.PaymentEventType.SUCCEEDED
                ? "payment.succeeded" : "payment.failed";
        log.info("Payment notification received: order={} tenant={} type={}",
                event.orderNumber(), event.tenantId(), event.type());
        withTenant(event.tenantId(),
                () -> dispatchService.dispatch(eventType, event.tenantId(), event));
    }

    @RabbitListener(queues = RabbitMQConfig.REFUND_NOTIFICATIONS_QUEUE)
    @Transactional
    public void handleRefundNotification(RefundEvent event) {
        log.info("Refund notification received: order={} tenant={} type={}",
                event.orderNumber(), event.tenantId(), event.type());
        withTenant(event.tenantId(),
                () -> dispatchService.dispatch("order.refunded", event.tenantId(), event));
    }

    private void withTenant(UUID tenantId, Runnable work) {
        TenantContext.set(tenantId);
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
        try {
            work.run();
        } finally {
            TenantContext.clear();
        }
    }
}
