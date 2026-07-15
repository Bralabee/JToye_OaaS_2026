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
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

/**
 * The NEW vendor order-email path (COMMS-02). Consumes {@code order.state.*}
 * from its OWN durable {@code order.notifications} queue — a SEPARATE queue on
 * the same {@code order.events} exchange, so it does NOT compete with the
 * incumbent {@code order.state-changes} queue that
 * {@code OrderStateChangeListener} drains to email the CUSTOMER.
 *
 * <p><b>Additive, never a replacement (Pitfall 5):</b> the customer stays on the
 * untouched legacy path; this listener dispatches the SAME order event to the
 * VENDOR only ({@code tenants.contact_email}, D-04) via
 * {@link NotificationDispatchService}. So the customer is emailed exactly once
 * (by the legacy path) and the vendor is now emailed too — "order = customer +
 * vendor" is finally wired, with no duplicate.
 *
 * <p>Runs the {@code OrderStateChangeListener} tenant preamble verbatim: a
 * {@code @RabbitListener} thread carries no tenant context, so it pins BOTH the
 * {@code TenantContext} ThreadLocal AND the transaction-local Postgres GUC
 * before any tenant-scoped read, in a {@code try/finally} that clears the
 * ThreadLocal.
 */
@Component
public class OrderNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationListener.class);

    private final NotificationDispatchService dispatchService;
    private final EntityManager entityManager;

    public OrderNotificationListener(NotificationDispatchService dispatchService, EntityManager entityManager) {
        this.dispatchService = dispatchService;
        this.entityManager = entityManager;
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_NOTIFICATIONS_QUEUE)
    @Transactional
    public void handleOrderNotification(OrderStateChangeEvent event) {
        log.info("Order notification (vendor) received: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());
        withTenant(event.tenantId(),
                () -> dispatchService.dispatch("order.state.changed", event.tenantId(), event));
    }

    /** Pin TenantContext + the RLS GUC (OrderStateChangeListener §83-90) before any tenant-scoped read. */
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
