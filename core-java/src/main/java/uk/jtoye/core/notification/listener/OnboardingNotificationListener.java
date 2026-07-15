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
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

/**
 * Binds the previously-DEAD onboarding channel (COMMS-01). Phase 21 emitted
 * onboarding-stall events to the {@code onboarding.events} exchange but left it
 * UNBOUND, so every stall was silently discarded at the exchange. This listener
 * consumes the now-bound {@code onboarding.notifications} queue and dispatches a
 * VENDOR email ({@code tenants.contact_email}, D-04 — onboarding is vendor-only,
 * there is no J'Toye platform operator), so an onboarding stall finally lands in
 * the vendor's inbox instead of vanishing.
 *
 * <p>Same tenant preamble as {@code OrderStateChangeListener} §83-90 (pin
 * {@code TenantContext} + the transaction-local GUC before any tenant-scoped read).
 */
@Component
public class OnboardingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OnboardingNotificationListener.class);

    private final NotificationDispatchService dispatchService;
    private final EntityManager entityManager;

    public OnboardingNotificationListener(NotificationDispatchService dispatchService, EntityManager entityManager) {
        this.dispatchService = dispatchService;
        this.entityManager = entityManager;
    }

    @RabbitListener(queues = RabbitMQConfig.ONBOARDING_NOTIFICATIONS_QUEUE)
    @Transactional
    public void handleOnboardingNotification(OnboardingStateChangeEvent event) {
        log.info("Onboarding notification received: onboarding={} tenant={} status={}",
                event.onboardingId(), event.tenantId(), event.status());
        withTenant(event.tenantId(),
                () -> dispatchService.dispatch("onboarding.state.changed", event.tenantId(), event));
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
