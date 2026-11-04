package uk.jtoye.core.order;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.notification.CustomerEmailContext;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.notification.consent.ConsentGate;
import uk.jtoye.core.notification.consent.NotificationCategory;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * #821 (owner ruling 2026-11-04, persona close-out): an order left PENDING too long raises a vendor alert
 * and sends the customer ONE email saying the shop has not yet accepted it. <b>No auto-cancel</b>: the
 * order stays exactly as it is, and the shop can still accept it.
 *
 * <h2>What "alert" means</h2>
 * The sweep stamps {@code orders.unaccepted_notified_at} (V91). That stamp IS the vendor alert: the kitchen
 * board's New lane and the dashboard read it as "overdue for acceptance". The board re-reads once a minute
 * (the sweep ticks once a minute too), so no STOMP frame is needed; and StompPublishCallSiteTest allows exactly
 * one STOMP publish site, the status listener. It is also the notified-once ledger.
 *
 * <h2>Once per order, even with two sweepers</h2>
 * Each order is claimed with a conditional {@code UPDATE ... WHERE unaccepted_notified_at IS NULL AND
 * status = 'PENDING'} and acted on only when that statement changed exactly one row. A second replica (or a
 * rerun) finds the stamp and does nothing. The stamp is committed BEFORE the email is sent, so a failure to
 * send is at-most-once, never a repeat: the same trade the status-email listener makes (its dedup row
 * commits before the {@code @Async} send).
 *
 * <h2>Per tenant, pinned — read before editing the loop</h2>
 * {@code orders} is under FORCE row-level security. A sweep with no tenant pinned would read and update
 * ZERO rows and report success (the V25 -> V44 -> V57 -> V78 -> V79 -> V80 class). So, as
 * {@code ClientIpDigestRetentionSweep} does, it iterates the {@code tenants} registry (no RLS, by design)
 * and does each tenant's work in its own transaction with {@link TenantContext} set and the GUC pinned.
 * The proof is {@code UnacceptedOrderSweepIntegrationTest}: two tenants, NOSUPERUSER role.
 *
 * <h2>What is NOT swept</h2>
 * <ul>
 *   <li>a VOIDED order ({@code voided_at IS NOT NULL}): it is not waiting for anyone;</li>
 *   <li>an order still awaiting its payment ({@link OrderService#isAwaitingUpfrontOrDepositPayment}): the
 *       shop cannot accept it yet, so "the shop has not accepted" would be untrue;</li>
 *   <li>an order already alerted.</li>
 * </ul>
 *
 * <p>"Left PENDING" is measured from {@code updated_at}. The V5 trigger {@code update_orders_updated_at}
 * stamps it on every UPDATE of the row, so for a PENDING order it is the moment it became PENDING
 * (placement for cash, payment for card) or the last time anyone touched it while it waited. That can only
 * DELAY an alert (a touch restarts the clock), never raise one early, and a touch (an allergy-note
 * acknowledgement, say) is itself evidence somebody at the shop has seen the order. Measuring from
 * {@code created_at} instead would alert a card order the moment it was paid if the customer dawdled at the
 * checkout.
 */
@Component
public class UnacceptedOrderSweep {

    private static final Logger log = LoggerFactory.getLogger(UnacceptedOrderSweep.class);

    public static final String THRESHOLD_PROPERTY = "jtoye.orders.unaccepted-alert.threshold";

    /** Per tenant per run; the next run takes the rest. Keeps one tenant's backlog from holding a transaction. */
    private static final int BATCH = 200;

    private static final String CANDIDATES_SQL =
            "SELECT id FROM orders "
                    + "WHERE tenant_id = :tenantId AND status = 'PENDING' AND voided_at IS NULL "
                    + "AND unaccepted_notified_at IS NULL "
                    + "AND updated_at < now() - make_interval(secs => :secs) "
                    + "ORDER BY updated_at LIMIT " + BATCH;

    private static final String CLAIM_SQL =
            "UPDATE orders SET unaccepted_notified_at = now(), version = version + 1 "
                    + "WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING' AND voided_at IS NULL "
                    + "AND unaccepted_notified_at IS NULL";

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final OrderEmailContextFactory contextFactory;
    private final EmailNotificationService emailService;
    private final ConsentGate consentGate;
    private final boolean enabled;
    private final long thresholdSeconds;

    @Autowired
    public UnacceptedOrderSweep(OrderRepository orderRepository,
                                EntityManager entityManager,
                                PlatformTransactionManager transactionManager,
                                OrderEmailContextFactory contextFactory,
                                EmailNotificationService emailService,
                                @Nullable ConsentGate consentGate,
                                @Value("${jtoye.orders.unaccepted-alert.enabled:true}") boolean enabled,
                                @Value("${" + THRESHOLD_PROPERTY + ":10m}") Duration threshold) {
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.contextFactory = contextFactory;
        this.emailService = emailService;
        this.consentGate = consentGate;
        this.enabled = enabled;
        if (threshold.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException(THRESHOLD_PROPERTY + " must be at least 1 minute, was " + threshold);
        }
        this.thresholdSeconds = threshold.toSeconds();
    }

    /** One order alerted: everything the after-commit side effects need, as values. */
    private record Alerted(OrderStateChangeEvent event, String email, CustomerEmailContext context, UUID shopId) {
    }

    /**
     * Alert every tenant's overdue PENDING orders.
     *
     * @return how many orders were alerted across all tenants
     */
    @Scheduled(fixedDelayString = "${jtoye.orders.unaccepted-alert.sweep-interval-ms:60000}")
    public int sweep() {
        if (!enabled) {
            return 0;
        }
        int total = 0;
        for (UUID tenantId : listTenantIds()) {
            try {
                total += sweepTenant(tenantId);
            } catch (Exception e) {
                // One tenant's failure must not stop the others; the order stays unstamped and is retried.
                log.error("event=unaccepted_order_sweep_failed tenant={} — continuing: {}", tenantId,
                        e.getClass().getSimpleName());
            }
        }
        if (total > 0) {
            log.info("event=unaccepted_orders_alerted count={}", total);
        }
        return total;
    }

    private int sweepTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            List<Alerted> alerted = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                return SystemPrincipal.asSystem(() -> claim(tenantId));
            });
            if (alerted == null || alerted.isEmpty()) {
                return 0;
            }
            // After commit: the stamps are durable, so nothing below can cause a second alert.
            for (Alerted a : alerted) {
                if (a.email() != null) {
                    emailService.sendOrderAwaitingAcceptance(a.event(), a.email(), a.context());
                }
            }
            return alerted.size();
        } finally {
            // ALWAYS: pooled threads; a stale tenant left behind is a cross-tenant read waiting to happen.
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Alerted> claim(UUID tenantId) {
        List<Object> ids = entityManager.createNativeQuery(CANDIDATES_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("secs", (double) thresholdSeconds)
                .getResultList();
        List<Alerted> alerted = new ArrayList<>();
        for (Object raw : ids) {
            UUID orderId = raw instanceof UUID u ? u : UUID.fromString(raw.toString());
            Order order = orderRepository.findByIdAndTenantId(orderId, tenantId).orElse(null);
            if (order == null || OrderService.isAwaitingUpfrontOrDepositPayment(order)) {
                continue;
            }
            int claimed = entityManager.createNativeQuery(CLAIM_SQL)
                    .setParameter("id", orderId)
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
            if (claimed != 1) {
                continue; // another sweeper, or the shop accepted it between the read and the claim
            }
            String email = order.getCustomerEmail();
            if (email != null && email.isBlank()) {
                email = null;
            }
            // Consent is read HERE, while the tenant is pinned: the @Async send runs with neither.
            if (email != null && consentGate != null
                    && !consentGate.allows(tenantId, email, NotificationCategory.ORDERS)) {
                log.info("Unaccepted-order email suppressed for order {} (recipient opted out of ORDERS)",
                        order.getOrderNumber());
                email = null;
            }
            CustomerEmailContext context = email == null ? null
                    : contextFactory.build(tenantId, order.getOrderNumber(), order);
            OrderStateChangeEvent event = new OrderStateChangeEvent(order.getId(), tenantId,
                    order.getOrderNumber(), OrderStatus.PENDING, OrderStatus.PENDING, OffsetDateTime.now(),
                    order.getShopId());
            alerted.add(new Alerted(event, email, context, order.getShopId()));
        }
        return alerted;
    }


    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        List<Object> raw = transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
        List<UUID> ids = new ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                ids.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
            }
        }
        return ids;
    }

    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
