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

import java.math.BigInteger;
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
 *   <li>an order still awaiting its payment (the {@link OrderService#isAwaitingUpfrontOrDepositPayment} rule,
 *       in SQL): the shop cannot accept it yet, so "the shop has not accepted" would be untrue;</li>
 *   <li>an order the shop placed itself ({@code placed_via = 'VENDOR'}): see {@link #ALERTABLE_PREDICATE};</li>
 *   <li>an order already alerted;</li>
 *   <li>an order PENDING for longer than {@link #MAX_AGE_PROPERTY} (default 2 hours): an alert is only true
 *       while the shop could still be expected to react, and the first run after a deploy (or after a long
 *       outage) must never email the customer of every old PENDING order in the table. Such an order is NOT
 *       emailed, but it IS stamped, silently ({@link #SILENT_STAMP_SQL}): the stamp is the notified-once ledger,
 *       so stamping it takes it out of the candidate scan for good instead of re-reading its audit history
 *       every minute, and the INFO line {@code unaccepted_orders_stamped_silently} says how many each tenant
 *       had, so "the customer was never told" can be told apart from "the sweep never ran".</li>
 * </ul>
 *
 * <p>"Left PENDING" is measured from the moment the order BECAME PENDING, never from its last touch
 * (review of PR 1122, finding 4): {@link #PENDING_SINCE_SQL} is the earliest Envers revision of the order
 * whose status is PENDING (placement for a cash order; the payment that moved a card order out of DRAFT),
 * and falls back to {@code created_at} for an order with no such revision. {@code updated_at} is NOT the
 * clock: the V5 trigger resets it on every UPDATE, so an allergy-note acknowledgement, a payment-link touch or
 * a vendor edit would restart it, and a shop that read the note but never accepted would avoid the alert.
 * Plain {@code created_at} is wrong for a card order, which is a DRAFT until paid and would be alerted the
 * moment it was captured if the customer dawdled at the checkout.
 *
 * <p>Only ACTIVE tenants are swept: a SUSPENDED or OFFBOARDED tenant's traffic is rejected by
 * {@code TenantStatusInterceptor}, and telling its customers "the shop has not accepted your order" after the
 * business has been suspended or offboarded would be wrong.
 */
@Component
public class UnacceptedOrderSweep {

    private static final Logger log = LoggerFactory.getLogger(UnacceptedOrderSweep.class);

    public static final String THRESHOLD_PROPERTY = "jtoye.orders.unaccepted-alert.threshold";
    public static final String MAX_AGE_PROPERTY = "jtoye.orders.unaccepted-alert.max-age";
    public static final String SWEEP_INTERVAL_PROPERTY = "jtoye.orders.unaccepted-alert.sweep-interval-ms";

    /**
     * The alert window ({@code threshold} to {@code max-age}) must be at least this many sweep intervals wide.
     * The sweep runs a fixed delay after the previous run ENDS, so runs land more than one interval apart; an
     * order turning {@code threshold} old just after a run must still be inside the window at the next one, and
     * the one after that (a slow run or a restart), or it passes through unalerted (review of PR 1123).
     */
    static final int MIN_WINDOW_INTERVALS = 3;

    /**
     * Longest max age handed to the database. Anything longer is "effectively unbounded" and is clamped, so
     * {@code make_interval} can never be given a value PostgreSQL refuses.
     */
    private static final long MAX_AGE_CAP_SECONDS = Duration.ofDays(3650).toSeconds();

    /** Per tenant per run; the next run takes the rest. Keeps one tenant's backlog from holding a transaction. */
    private static final int BATCH = 200;

    /**
     * EVERY skip predicate lives here, in the statement that picks the batch, so the batch holds only orders the
     * sweep will act on. A skip made afterwards in Java stamps nothing, so the skipped row is picked again next
     * run and, with {@link #BATCH} or more of them at the head of the ORDER BY, no real overdue order behind them
     * is ever reached (review of PR 1119, finding 1).
     *
     * <ul>
     *   <li>not voided, still PENDING, not yet alerted, older than the threshold;</li>
     *   <li>not placed by the shop (V69 {@code placed_via = 'VENDOR'}): the shop made it, so "the shop has not
     *       accepted your order" would be false, and the alert copy says the customer was told. A NULL channel
     *       (a pre-V69 row) is a customer order and stays in;</li>
     *   <li>not awaiting payment: the same rule as {@link OrderService#isAwaitingUpfrontOrDepositPayment}
     *       (payment status PENDING or FAILED, or a payment reference that is neither CAPTURED nor AUTHORIZED),
     *       written in SQL. The shop cannot accept such an order yet.</li>
     * </ul>
     *
     * <p>The AGE bounds are deliberately NOT part of this predicate: the lower bound (older than the threshold)
     * and the upper bound (younger than the max age) are applied to {@link #PENDING_SINCE_SQL} by
     * {@link #CANDIDATES_SQL}, and an order past the upper bound is stamped without an email by
     * {@link #SILENT_STAMP_SQL}. The predicate says which orders the sweep may ever act on; the bounds say what
     * it does with them.
     */
    private static final String ALERTABLE_PREDICATE =
            "status = 'PENDING' AND voided_at IS NULL AND unaccepted_notified_at IS NULL "
                    + "AND placed_via IS DISTINCT FROM 'VENDOR' "
                    + "AND COALESCE(payment_status, 'NONE') NOT IN ('PENDING', 'FAILED') "
                    + "AND (payment_reference IS NULL OR COALESCE(payment_status, 'NONE') IN ('CAPTURED', 'AUTHORIZED'))";

    /**
     * When the order became PENDING: its earliest PENDING audit revision, else {@code created_at}. Correlated on
     * the outer {@code orders} row (alias {@code o}); {@code orders_aud} is keyed (id, rev) and read under the
     * pinned tenant like {@code orders}.
     */
    static final String PENDING_SINCE_SQL =
            "COALESCE((SELECT to_timestamp(min(r.revtstmp) / 1000.0) FROM orders_aud a "
                    + "JOIN revinfo r ON r.rev = a.rev WHERE a.id = o.id AND a.status = 'PENDING'), o.created_at)";

    /**
     * Orders PENDING for longer than the max age: stamped, never emailed (review of PR 1123). Same pick as
     * {@link #CANDIDATES_SQL}, upper bound instead of lower, {@link #BATCH} per tenant per run (the next run
     * takes the rest), and run BEFORE the candidate read in the same transaction so an old order never reaches
     * the email path. The stamp repeats {@link #ALERTABLE_PREDICATE}, so a race with an accept or a void leaves
     * that order alone. Narrow like {@link #CLAIM_SQL}: it touches only the stamp, no version bump, no audit row.
     */
    private static final String SILENT_STAMP_SQL =
            "UPDATE orders SET unaccepted_notified_at = now() "
                    + "WHERE tenant_id = :tenantId AND " + ALERTABLE_PREDICATE + " AND id IN ("
                    + "SELECT id FROM (SELECT o.id, " + PENDING_SINCE_SQL + " AS pending_since FROM orders o "
                    + "WHERE o.tenant_id = :tenantId AND " + ALERTABLE_PREDICATE + ") t "
                    + "WHERE pending_since < now() - make_interval(secs => :maxSecs) "
                    + "ORDER BY pending_since LIMIT " + BATCH + ")";

    private static final String CANDIDATES_SQL =
            "SELECT id FROM (SELECT o.id, " + PENDING_SINCE_SQL + " AS pending_since FROM orders o "
                    + "WHERE o.tenant_id = :tenantId AND " + ALERTABLE_PREDICATE + ") t "
                    + "WHERE pending_since < now() - make_interval(secs => :secs) "
                    + "AND pending_since >= now() - make_interval(secs => :maxSecs) "
                    + "ORDER BY pending_since LIMIT " + BATCH;

    /**
     * The claim: a narrow UPDATE that touches ONLY the stamp. It deliberately does NOT bump {@code version}: the
     * {@code unaccepted_notified_at IS NULL} predicate (repeated with the rest of {@link #ALERTABLE_PREDICATE},
     * so a race with an accept or a void re-checks them) is the once-only guarantee, and a version bump would
     * make a vendor Accept that loaded the order a moment earlier fail with an optimistic-lock error for a
     * change that does not matter to it (review of PR 1119, finding 3).
     *
     * <p>It also writes no {@code orders_aud} row, deliberately: this is an operational stamp, not a business
     * revision of the order (nobody decided anything), exactly like {@code ClientIpDigestRetentionSweep}'s native
     * UPDATE of {@code client_ip_digest}, which is not audited either. Who accepted, cancelled or voided the
     * order is audited by the transition that does it.
     */
    private static final String CLAIM_SQL =
            "UPDATE orders SET unaccepted_notified_at = now() "
                    + "WHERE id = :id AND tenant_id = :tenantId AND " + ALERTABLE_PREDICATE;

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final OrderEmailContextFactory contextFactory;
    private final EmailNotificationService emailService;
    private final ConsentGate consentGate;
    private final boolean enabled;
    private final long thresholdSeconds;
    private final long maxAgeSeconds;

    @Autowired
    public UnacceptedOrderSweep(OrderRepository orderRepository,
                                EntityManager entityManager,
                                PlatformTransactionManager transactionManager,
                                OrderEmailContextFactory contextFactory,
                                EmailNotificationService emailService,
                                @Nullable ConsentGate consentGate,
                                @Value("${jtoye.orders.unaccepted-alert.enabled:true}") boolean enabled,
                                @Value("${" + THRESHOLD_PROPERTY + ":10m}") Duration threshold,
                                @Value("${" + MAX_AGE_PROPERTY + ":2h}") Duration maxAge,
                                @Value("${" + SWEEP_INTERVAL_PROPERTY + ":60000}") long sweepIntervalMs) {
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
        if (sweepIntervalMs < 1) {
            throw new IllegalStateException(SWEEP_INTERVAL_PROPERTY + " must be at least 1 ms, was " + sweepIntervalMs);
        }
        // Fail fast: a window [now - maxAge, now - threshold] that is empty, inverted or narrower than a few sweep
        // intervals would let orders slip through unalerted, which is worse than refusing to start. BigInteger,
        // so no value an operator can type overflows the arithmetic (Long.MAX_VALUE seconds is legal input).
        BigInteger window = BigInteger.valueOf(maxAge.toSeconds()).subtract(BigInteger.valueOf(threshold.toSeconds()));
        BigInteger required = BigInteger.valueOf(MIN_WINDOW_INTERVALS)
                .multiply(BigInteger.valueOf(sweepIntervalMs)).add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1000));
        if (maxAge.isNegative() || window.compareTo(required) < 0) {
            throw new IllegalStateException(MAX_AGE_PROPERTY + " (" + maxAge + ") minus " + THRESHOLD_PROPERTY + " ("
                    + threshold + ") must be at least " + MIN_WINDOW_INTERVALS + " x " + SWEEP_INTERVAL_PROPERTY
                    + " (" + sweepIntervalMs + " ms), so an order cannot pass through the window between two runs");
        }
        this.maxAgeSeconds = Math.min(maxAge.toSeconds(), MAX_AGE_CAP_SECONDS);
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
            int[] silent = {0};
            List<Alerted> alerted = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                return SystemPrincipal.asSystem(() -> {
                    // Old orders first: stamped, never emailed, and out of the scan below.
                    silent[0] = stampSilently(tenantId);
                    return claim(tenantId);
                });
            });
            if (silent[0] > 0) {
                log.info("event=unaccepted_orders_stamped_silently tenant={} count={} "
                        + "(PENDING past {}; not emailed)", tenantId, silent[0], MAX_AGE_PROPERTY);
            }
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

    /** Stamps this tenant's over-age PENDING orders without alerting anyone; returns how many (at most {@link #BATCH}). */
    private int stampSilently(UUID tenantId) {
        return entityManager.createNativeQuery(SILENT_STAMP_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("maxSecs", (double) maxAgeSeconds)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<Alerted> claim(UUID tenantId) {
        List<Object> ids = entityManager.createNativeQuery(CANDIDATES_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("secs", (double) thresholdSeconds)
                .setParameter("maxSecs", (double) maxAgeSeconds)
                .getResultList();
        List<Alerted> alerted = new ArrayList<>();
        for (Object raw : ids) {
            UUID orderId = raw instanceof UUID u ? u : UUID.fromString(raw.toString());
            Order order = orderRepository.findByIdAndTenantId(orderId, tenantId).orElse(null);
            if (order == null) {
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
                entityManager.createNativeQuery("SELECT id FROM tenants WHERE status = 'ACTIVE'").getResultList());
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
