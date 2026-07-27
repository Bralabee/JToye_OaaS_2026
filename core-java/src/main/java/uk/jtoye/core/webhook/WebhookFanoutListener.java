package uk.jtoye.core.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Consumes the {@code webhook.deliveries} fanout queue (declared by 22-04, bound
 * to all four lifecycle families) and, for EACH matching ACTIVE subscription,
 * synchronously INSERTs one {@code PENDING} {@link WebhookDelivery} row carrying
 * the versioned {@link WebhookEventEnvelope} serialized ONCE to a stable string
 * (COMMS-05, RESEARCH Pattern 2).
 *
 * <p><b>No inline HTTP:</b> delivery is the {@link WebhookDeliveryWorker}'s job.
 * A synchronous WebClient POST + retry here would block the Rabbit consumer
 * thread → head-of-line block within the queue, and lose durability across
 * restarts. The two-stage (durable enqueue, async deliver) split is the only
 * shape that satisfies "one failing subscription never stalls others".
 *
 * <p><b>Tenant GUC preamble:</b> a {@code @RabbitListener} runs on a Rabbit
 * thread with no tenant context, so each fanout runs inside its OWN
 * {@link TransactionTemplate} transaction with {@link TenantContext} set AND the
 * Postgres {@code app.current_tenant_id} GUC pinned before any tenant-scoped
 * read/write (the {@code OrderStateChangeListener} §83-90 pattern). A
 * {@code TransactionTemplate} (not a {@code @Transactional} private method)
 * avoids the Spring self-invocation NULL-tenant trap.
 *
 * <p>One envelope {@code id} is generated per incoming event and shared across
 * all matching subscriptions (Stripe semantics — one event, fanned to N
 * endpoints, each dedupes on {@code X-JToye-Event-Id}).
 */
@Component
@RabbitListener(queues = RabbitMQConfig.WEBHOOK_DELIVERIES_QUEUE)
public class WebhookFanoutListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookFanoutListener.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public WebhookFanoutListener(WebhookSubscriptionRepository subscriptionRepository,
                                 WebhookDeliveryRepository deliveryRepository,
                                 WebhookProperties properties,
                                 ObjectMapper objectMapper,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @RabbitHandler
    public void onOrderState(OrderStateChangeEvent event) {
        fanout(event.tenantId(), WebhookEventType.ORDER_STATE_CHANGED,
                "order." + event.newStatus().name().toLowerCase(Locale.ROOT),
                event.timestamp(), event);
    }

    @RabbitHandler
    public void onRefund(RefundEvent event) {
        fanout(event.tenantId(), WebhookEventType.ORDER_REFUNDED,
                "order.refunded", event.occurredAt(), event);
    }

    @RabbitHandler
    public void onOnboarding(OnboardingStateChangeEvent event) {
        fanout(event.tenantId(), WebhookEventType.ONBOARDING_STATE_CHANGED,
                "onboarding." + event.status().name().toLowerCase(Locale.ROOT),
                event.occurredAt(), event);
    }

    @RabbitHandler
    public void onPayment(PaymentEvent event) {
        fanout(event.tenantId(), WebhookEventType.PAYMENT_EVENT,
                "payment." + event.type().name().toLowerCase(Locale.ROOT),
                event.occurredAt(), event);
    }

    /**
     * Any unexpected payload type is acknowledged and dropped here, deliberately.
     *
     * <p>This queue DOES have a dead-letter exchange ({@link RabbitMQConfig#WEBHOOK_DELIVERIES_DLX}
     * → {@link RabbitMQConfig#WEBHOOK_DELIVERIES_DLQ}); an earlier version of this comment claimed
     * it did not, which is how a converter defect that dead-lettered every single message went
     * unexamined for weeks. Reaching this handler is a no-op by choice — an unrecognised but
     * well-formed payload is not worth a dead letter — but a payload the converter cannot
     * deserialize at all never reaches any handler and DOES dead-letter.
     */
    @RabbitHandler(isDefault = true)
    public void onOther(Object event) {
        log.debug("event=webhook_fanout_ignored type={}",
                event == null ? "null" : event.getClass().getName());
    }

    private void fanout(UUID tenantId, WebhookEventType family, String type,
                        OffsetDateTime occurredAt, Object data) {
        if (tenantId == null) {
            log.warn("event=webhook_fanout_skipped reason=null_tenant type={}", type);
            return;
        }
        TenantContext.set(tenantId);
        try {
            transactionTemplate.executeWithoutResult(status ->
                    insertPendingRows(tenantId, family, type, occurredAt, data));
        } finally {
            TenantContext.clear();
        }
    }

    private void insertPendingRows(UUID tenantId, WebhookEventType family, String type,
                                   OffsetDateTime occurredAt, Object data) {
        pinTenantGuc(tenantId);

        List<WebhookSubscription> matching = subscriptionRepository.findByTenantId(tenantId).stream()
                .filter(s -> s.getStatus() == WebhookSubscription.Status.ACTIVE)
                .filter(s -> s.getEventTypes() != null && s.getEventTypes().contains(family.name()))
                .toList();
        if (matching.isEmpty()) {
            return;
        }

        // One envelope id per event, serialized ONCE — these exact bytes are what
        // the worker signs and POSTs (Pitfall 6). Shared across subscriptions.
        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new WebhookEventEnvelope(
                    eventId, type, tenantId, occurredAt, properties.getEnvelope().getVersion(), data));
        } catch (JsonProcessingException e) {
            log.error("event=webhook_fanout_serialize_failed tenant={} type={}: {}",
                    tenantId, type, e.getMessage());
            return;
        }

        for (WebhookSubscription sub : matching) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setTenantId(tenantId);
            delivery.setSubscriptionId(sub.getId());
            delivery.setEventId(eventId);
            delivery.setEventType(type);
            delivery.setPayload(payload);
            delivery.setStatus(WebhookDelivery.Status.PENDING);
            deliveryRepository.save(delivery);
        }
        log.info("event=webhook_fanout tenant={} type={} subscriptions={}",
                tenantId, type, matching.size());
    }

    /**
     * Pin the RLS tenant GUC on the active transaction's connection (the
     * {@code set_config(...,true)} value is transaction-local). Required so the
     * FORCE-RLS INSERT into webhook_delivery passes WITH CHECK under the
     * non-superuser app role.
     */
    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
