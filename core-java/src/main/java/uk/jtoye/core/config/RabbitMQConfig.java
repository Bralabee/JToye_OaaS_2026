package uk.jtoye.core.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
public class RabbitMQConfig {
    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    public static final String ORDER_EVENTS_EXCHANGE = "order.events";
    public static final String ORDER_EVENTS_QUEUE = "order.state-changes";
    public static final String ORDER_EVENTS_ROUTING_KEY = "order.state.changed";
    public static final String ORDER_EVENTS_ROUTING_PATTERN = "order.state.*";
    /** Name prefix for the per-instance SSE fan-out queue (#92) — aids broker-console debugging. */
    public static final String ORDER_EVENTS_FANOUT_QUEUE_PREFIX = "order.state-changes.sse.";

    public static final String DLX_EXCHANGE = "order.events.dlx";
    public static final String DLQ_QUEUE = "order.state-changes.dlq";

    public static final String PAYMENT_EVENTS_EXCHANGE = "payment.events";
    public static final String PAYMENT_EVENTS_QUEUE = "payment.events";
    public static final String PAYMENT_EVENTS_DLX = "payment.events.dlx";
    public static final String PAYMENT_EVENTS_DLQ = "payment.events.dlq";

    /**
     * Onboarding notification exchange (Phase 21 / D-01 seam). Carries
     * {@code onboarding.state.*} events (currently the MANUAL_REVIEW stall
     * emitted from {@code GateChainRunner}) written through the shared V46
     * transactional outbox. Declared as an unbound topic exchange this phase:
     * there is NO queue/binding yet, so a published message is discarded
     * cleanly at the exchange until Phase 24 (#205 webhook delivery) attaches
     * the subscription. Its own constant so the outbox flusher can dispatch the
     * correct payload type (Pitfall 1 — an unrecognised exchange would be
     * deserialized as a PaymentEvent and poison-dead-lettered).
     */
    public static final String ONBOARDING_EVENTS_EXCHANGE = "onboarding.events";

    // --- Phase 22 (22-04) notification CONSUMER topology ---
    // Second durable queues that fan lifecycle events out to email WITHOUT
    // competing with the incumbent consumers (see the topology section below).
    public static final String ORDER_NOTIFICATIONS_QUEUE = "order.notifications";
    public static final String ONBOARDING_NOTIFICATIONS_QUEUE = "onboarding.notifications";
    public static final String PAYMENT_NOTIFICATIONS_QUEUE = "payment.notifications";
    public static final String REFUND_NOTIFICATIONS_QUEUE = "refund.notifications";
    public static final String ONBOARDING_EVENTS_ROUTING_PATTERN = "onboarding.state.*";
    public static final String PAYMENT_EVENTS_ROUTING_PATTERN = "payment.*";
    /** Refund routing key ({@link uk.jtoye.core.payment.RefundEvent}); matches NO existing binding today. */
    public static final String ORDER_REFUNDED_ROUTING_KEY = "order.refunded";

    // --- Phase 22 (22-05) webhook fanout CONSUMER queue (consumed by WebhookFanoutListener) ---
    public static final String WEBHOOK_DELIVERIES_QUEUE = "webhook.deliveries";
    public static final String WEBHOOK_DELIVERIES_DLX = "webhook.deliveries.dlx";
    public static final String WEBHOOK_DELIVERIES_DLQ = "webhook.deliveries.dlq";

    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange(ORDER_EVENTS_EXCHANGE);
    }

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue orderEventsQueue() {
        return QueueBuilder.durable(ORDER_EVENTS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding orderEventsBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with(ORDER_EVENTS_ROUTING_PATTERN);
    }

    // --- Per-instance SSE fan-out topology (#92 / P2-1) ---
    //
    // The durable ORDER_EVENTS_QUEUE above is a competing-consumer queue: at N
    // replicas each order event is delivered to exactly ONE instance. That is
    // the correct semantic for its side effects (customer email, business
    // metrics, the KDS publish to the shared STOMP relay), but it silently
    // starved SSE — emitters live per-JVM, so dashboards attached to the other
    // N-1 replicas missed the event. This AnonymousQueue is exclusive,
    // auto-delete and uniquely named per JVM; every replica declares its own
    // and binds it to the same topic exchange, so EVERY replica receives EVERY
    // order event and can serve its locally attached SSE clients.
    //
    // No DLX on purpose: fan-out events are fire-and-forget UI pushes. A
    // client that missed one re-syncs on its next fetch/reconnect.

    @Bean
    public AnonymousQueue orderEventsFanoutQueue() {
        return new AnonymousQueue(new Base64UrlNamingStrategy(ORDER_EVENTS_FANOUT_QUEUE_PREFIX));
    }

    @Bean
    public Binding orderEventsFanoutBinding(AnonymousQueue orderEventsFanoutQueue,
                                            TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsFanoutQueue)
                .to(orderEventsExchange)
                .with(ORDER_EVENTS_ROUTING_PATTERN);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange());
    }

    // --- Payment events topology ---

    @Bean
    public TopicExchange paymentEventsExchange() {
        return new TopicExchange(PAYMENT_EVENTS_EXCHANGE);
    }

    @Bean
    public FanoutExchange paymentDeadLetterExchange() {
        return new FanoutExchange(PAYMENT_EVENTS_DLX);
    }

    @Bean
    public Queue paymentEventsQueue() {
        return QueueBuilder.durable(PAYMENT_EVENTS_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_EVENTS_DLX)
                .build();
    }

    @Bean
    public Queue paymentDeadLetterQueue() {
        return QueueBuilder.durable(PAYMENT_EVENTS_DLQ).build();
    }

    @Bean
    public Binding paymentEventsBinding(Queue paymentEventsQueue, TopicExchange paymentEventsExchange) {
        return BindingBuilder.bind(paymentEventsQueue)
                .to(paymentEventsExchange)
                .with("payment.*");
    }

    @Bean
    public Binding paymentDeadLetterBinding(Queue paymentDeadLetterQueue, FanoutExchange paymentDeadLetterExchange) {
        return BindingBuilder.bind(paymentDeadLetterQueue)
                .to(paymentDeadLetterExchange);
    }

    // --- Onboarding events topology (Phase 21 / D-01 seam) ---
    //
    // Deliberately a lone TopicExchange with NO Queue and NO Binding this
    // phase. The outbox flusher publishes the MANUAL_REVIEW stall event here;
    // with no bound queue RabbitMQ discards it cleanly (a topic exchange drops
    // messages that match no binding), so nothing dead-letters while the
    // consumer side is still absent. Phase 24 (#205) adds the durable queue +
    // binding + @RabbitListener without touching the producer.

    @Bean
    public TopicExchange onboardingEventsExchange() {
        return new TopicExchange(ONBOARDING_EVENTS_EXCHANGE);
    }

    // ===================================================================
    // Phase 22 (22-04 / 22-05) — notification + webhook CONSUMER topology
    // ===================================================================
    //
    // Each new consumer gets its OWN durable queue bound to an EXISTING
    // exchange (RESEARCH Pattern 1). A second @RabbitListener on an existing
    // queue would STEAL messages from the incumbent via competing-consumer
    // semantics, so:
    //   • order.notifications does NOT reuse ORDER_EVENTS_QUEUE
    //     (order.state-changes) — the incumbent OrderStateChangeListener keeps
    //     emailing the CUSTOMER; this new queue drives the VENDOR order email.
    //   • payment.notifications does NOT reuse PAYMENT_EVENTS_QUEUE — the
    //     incumbent PaymentEventAuditListener keeps its audit copy.
    // Producers and PaymentEventOutboxFlusher.publishRow are UNTOUCHED
    // (Pitfall 3 — all four dispatch branches already exist; this plan adds
    // consumers only, never a new outbox event type).
    //
    // First-deploy backlog (RESEARCH Assumption A5) — ACCEPTED, no cutoff:
    // binding onboarding.notifications flushes any onboarding-stall events
    // already sitting in the shared outbox that were discarded while the
    // exchange was unbound. Those are genuine unresolved stalls the vendor was
    // NEVER notified about, so delivering them is the point of COMMS-01;
    // at-least-once is the outbox contract and the ConsentGate still applies.

    // (1) VENDOR order email — SECOND durable queue on order.events (order.state.*).
    @Bean
    public Queue orderNotificationsQueue() {
        return QueueBuilder.durable(ORDER_NOTIFICATIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Binding orderNotificationsBinding(Queue orderNotificationsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderNotificationsQueue)
                .to(orderEventsExchange)
                .with(ORDER_EVENTS_ROUTING_PATTERN);
    }

    // (2) ONBOARDING vendor email — BINDS the previously-unbound onboarding.events exchange
    //     (Phase 21 dead channel). No DLX: onboarding.events has none, and a
    //     repeatedly-failing best-effort notification is dropped after the
    //     retry interceptor exhausts — the vendor email is not a durable
    //     side effect the way the order state machine is.
    @Bean
    public Queue onboardingNotificationsQueue() {
        return QueueBuilder.durable(ONBOARDING_NOTIFICATIONS_QUEUE).build();
    }

    @Bean
    public Binding onboardingNotificationsBinding(Queue onboardingNotificationsQueue,
                                                  TopicExchange onboardingEventsExchange) {
        return BindingBuilder.bind(onboardingNotificationsQueue)
                .to(onboardingEventsExchange)
                .with(ONBOARDING_EVENTS_ROUTING_PATTERN);
    }

    // (3) PAYMENT email — SECOND durable queue on payment.events (payment.*);
    //     does NOT compete with PaymentEventAuditListener.
    @Bean
    public Queue paymentNotificationsQueue() {
        return QueueBuilder.durable(PAYMENT_NOTIFICATIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_EVENTS_DLX)
                .build();
    }

    @Bean
    public Binding paymentNotificationsBinding(Queue paymentNotificationsQueue, TopicExchange paymentEventsExchange) {
        return BindingBuilder.bind(paymentNotificationsQueue)
                .to(paymentEventsExchange)
                .with(PAYMENT_EVENTS_ROUTING_PATTERN);
    }

    // (4) REFUND email — order.refunded matches NO binding today (discarded).
    //     Bind exactly order.refunded; do NOT widen order.state.* to order.*
    //     (Pitfall 2 — would double-deliver order-state events).
    @Bean
    public Queue refundNotificationsQueue() {
        return QueueBuilder.durable(REFUND_NOTIFICATIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Binding refundNotificationsBinding(Queue refundNotificationsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(refundNotificationsQueue)
                .to(orderEventsExchange)
                .with(ORDER_REFUNDED_ROUTING_KEY);
    }

    // (5) WEBHOOK fanout — ONE durable queue bound to ALL FOUR families;
    //     consumed by 22-05's WebhookFanoutListener.
    //
    // WR-05: bound to its own DLX (mirroring order.notifications /
    // payment.notifications). If insertPendingRows() throws a TRANSIENT error
    // (e.g. a DB connection blip — NOT "no matching subscriptions"), the retry
    // interceptor exhausts and the message would otherwise be silently DROPPED
    // with no webhook_delivery row and no trace. Dead-lettering makes that
    // failure observable/replayable rather than a permanent silent loss for
    // every subscribed vendor endpoint.
    @Bean
    public FanoutExchange webhookDeliveriesDeadLetterExchange() {
        return new FanoutExchange(WEBHOOK_DELIVERIES_DLX);
    }

    @Bean
    public Queue webhookDeliveriesQueue() {
        return QueueBuilder.durable(WEBHOOK_DELIVERIES_QUEUE)
                .withArgument("x-dead-letter-exchange", WEBHOOK_DELIVERIES_DLX)
                .build();
    }

    @Bean
    public Queue webhookDeliveriesDeadLetterQueue() {
        return QueueBuilder.durable(WEBHOOK_DELIVERIES_DLQ).build();
    }

    @Bean
    public Binding webhookDeliveriesDeadLetterBinding(Queue webhookDeliveriesDeadLetterQueue,
                                                      FanoutExchange webhookDeliveriesDeadLetterExchange) {
        return BindingBuilder.bind(webhookDeliveriesDeadLetterQueue)
                .to(webhookDeliveriesDeadLetterExchange);
    }

    @Bean
    public Binding webhookDeliveriesOrderStateBinding(Queue webhookDeliveriesQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(webhookDeliveriesQueue).to(orderEventsExchange).with(ORDER_EVENTS_ROUTING_PATTERN);
    }

    @Bean
    public Binding webhookDeliveriesRefundBinding(Queue webhookDeliveriesQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(webhookDeliveriesQueue).to(orderEventsExchange).with(ORDER_REFUNDED_ROUTING_KEY);
    }

    @Bean
    public Binding webhookDeliveriesOnboardingBinding(Queue webhookDeliveriesQueue,
                                                      TopicExchange onboardingEventsExchange) {
        return BindingBuilder.bind(webhookDeliveriesQueue).to(onboardingEventsExchange)
                .with(ONBOARDING_EVENTS_ROUTING_PATTERN);
    }

    @Bean
    public Binding webhookDeliveriesPaymentBinding(Queue webhookDeliveriesQueue, TopicExchange paymentEventsExchange) {
        return BindingBuilder.bind(webhookDeliveriesQueue).to(paymentEventsExchange).with(PAYMENT_EVENTS_ROUTING_PATTERN);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer((args, cause) -> {
                    log.error("RabbitMQ message processing failed after 3 retries: {}", cause.getMessage());
                    throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                            "Exhausted retries — routing to DLQ", cause);
                })
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAdviceChain(retryInterceptor());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
