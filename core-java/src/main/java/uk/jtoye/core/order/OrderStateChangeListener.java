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
import uk.jtoye.core.notification.CustomerEmailContext;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.onboarding.TraderIdentityService;
import uk.jtoye.core.product.AllergenCatalog;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;
import uk.jtoye.core.websocket.StompDestinations;

import java.util.List;

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
    private final ShopRepository shopRepository;
    private final TraderIdentityService traderIdentityService;

    public OrderStateChangeListener(OrderRepository orderRepository,
                                     EmailNotificationService emailService,
                                     EntityManager entityManager,
                                     BusinessMetricsService metrics,
                                     SimpMessagingTemplate simpMessagingTemplate,
                                     JdbcTemplate jdbcTemplate,
                                     ShopRepository shopRepository,
                                     TraderIdentityService traderIdentityService) {
        this.orderRepository = orderRepository;
        this.emailService = emailService;
        this.entityManager = entityManager;
        this.metrics = metrics;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.shopRepository = shopRepository;
        this.traderIdentityService = traderIdentityService;
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
                        String topic = StompDestinations.kitchen(event.tenantId(), order.getShopId());
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

            // ASVS V7 / T-31.1-88: the order number and status only, never the recipient.
            log.info("Sending {} notification for order {}", event.newStatus(), event.orderNumber());

            switch (event.newStatus()) {
                case PENDING, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED -> { }
                default -> {
                    log.debug("No email template for status {}", event.newStatus());
                    return;
                }
            }

            // #789/#785 (Pitfall 8): everything the email says about the seller and the order's
            // allergens is read HERE, while TenantContext and the GUC are pinned to the event's
            // tenant. The @Async send runs with neither, and a lookup there reads zero rows under
            // FORCE RLS. The context is an immutable value: nothing is fetched after this point.
            CustomerEmailContext context = emailContext(event, order);

            switch (event.newStatus()) {
                case PENDING -> emailService.sendOrderConfirmation(event, email, context);
                case CONFIRMED -> emailService.sendOrderConfirmed(event, email, context);
                case PREPARING -> emailService.sendOrderPreparing(event, email, context);
                // #502: READY copy depends on how the order is fulfilled. The
                // fulfilment type is read from the Order this method already
                // loaded (it travels in the context) rather than added to
                // OrderStateChangeEvent — the event is a persisted outbox payload,
                // so widening the record would leave in-flight rows deserializing
                // with a null field and no way to tell "collection" from "old
                // payload". ONE send per transition by construction: there is a
                // single call site, and the branch lives inside the send.
                case READY -> emailService.sendOrderReady(event, email, context);
                case COMPLETED -> emailService.sendOrderCompletedNotification(event, email, context);
                case CANCELLED -> emailService.sendOrderCancelledNotification(event, email, context);
                default -> { }
            }
        }, () -> log.warn("Order {} not found for email notification", event.orderNumber()));
    }

    /**
     * The seller and allergen record for one order, read under the event's tenant.
     *
     * <p>The shop is read with an explicit tenant predicate as well as RLS. The seller comes from
     * {@link TraderIdentityService#findPublicSeller} — the SAME read the public shop page uses
     * (31.1-24), so the email and the storefront name the seller identically, company number only
     * for a company. The allergen record is the V63 order-line snapshot (never a live product
     * join) and the V69 acknowledgement mask.
     *
     * <p>A failed seller read must not cost the customer the email: the email then says the seller
     * has not provided details (the 31.1-24 missing state), which is true of what the platform can
     * show, rather than not arriving at all.
     */
    private CustomerEmailContext emailContext(OrderStateChangeEvent event, Order order) {
        // Order lines first, while the listener's own session is the active one.
        OrderAllergenSnapshot.OrderAllergenView view = OrderAllergenSnapshot.viewOf(order.getItems());
        List<String> acknowledged = order.getAllergenAckMask() == null
                ? null
                : AllergenCatalog.namesFor(order.getAllergenAckMask());

        Shop shop = order.getShopId() == null
                ? null
                : shopRepository.findByIdAndTenantId(order.getShopId(), event.tenantId()).orElse(null);
        SellerIdentityDto seller = null;
        if (shop != null) {
            try {
                seller = traderIdentityService.findPublicSeller(event.tenantId(), shop);
            } catch (RuntimeException e) {
                log.warn("Seller identity read failed for order {}: {}", event.orderNumber(),
                        e.getClass().getSimpleName());
            }
        }

        return new CustomerEmailContext(
                shop == null ? null : shop.getName(),
                shop == null ? null : shop.getEmail(),
                seller,
                order.getPlacedVia(),
                acknowledged,
                view.declaredNames(),
                view.flags(),
                order.getFulfilmentType());
    }
}
