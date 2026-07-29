package uk.jtoye.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

// This project has no @ConfigurationPropertiesScan, so each @ConfigurationProperties bean is
// registered explicitly — the same idiom as MediaConfig and StorageConfig.
@Configuration
@EnableConfigurationProperties(RabbitListenerProperties.class)
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

    /**
     * Phase 24 (24-03 / IMG-02) — DEDICATED media-pipeline topology. The accept
     * writes a {@code media_event_outbox} row in the same tx; {@code MediaEventOutboxFlusher}
     * publishes it here after commit, and the async worker (24-04) consumes
     * {@link #MEDIA_EVENTS_QUEUE} to normalize the quarantined upload. A DEDICATED
     * exchange (not payment.events / order.events) means the flusher has exactly one
     * destination and NO closed-set dispatch — sidestepping the
     * {@code outbox_flusher_dispatch_trap} entirely.
     */
    public static final String MEDIA_EVENTS_EXCHANGE = "media.events";
    public static final String MEDIA_EVENTS_QUEUE = "media.process";
    public static final String MEDIA_EVENTS_ROUTING_KEY = "media.process";
    public static final String MEDIA_EVENTS_ROUTING_PATTERN = "media.*";
    public static final String MEDIA_EVENTS_DLX = "media.events.dlx";
    public static final String MEDIA_EVENTS_DLQ = "media.process.dlq";

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

    // ===================================================================
    // Phase 24 (24-03 / IMG-02) — dedicated media.events pipeline topology
    // ===================================================================
    //
    // Mirrors the payment topology (durable queue with its own DLX). The worker
    // (24-04) binds a @RabbitListener to MEDIA_EVENTS_QUEUE; the flusher publishes
    // to MEDIA_EVENTS_EXCHANGE with MEDIA_EVENTS_ROUTING_KEY. One exchange, one
    // queue, one payload type (MediaProcessingEvent) — no dispatch coupling.

    @Bean
    public TopicExchange mediaEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE);
    }

    @Bean
    public FanoutExchange mediaDeadLetterExchange() {
        return new FanoutExchange(MEDIA_EVENTS_DLX);
    }

    @Bean
    public Queue mediaEventsQueue() {
        return QueueBuilder.durable(MEDIA_EVENTS_QUEUE)
                .withArgument("x-dead-letter-exchange", MEDIA_EVENTS_DLX)
                .build();
    }

    @Bean
    public Queue mediaDeadLetterQueue() {
        return QueueBuilder.durable(MEDIA_EVENTS_DLQ).build();
    }

    @Bean
    public Binding mediaEventsBinding(Queue mediaEventsQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaEventsQueue)
                .to(mediaEventsExchange)
                .with(MEDIA_EVENTS_ROUTING_PATTERN);
    }

    @Bean
    public Binding mediaDeadLetterBinding(Queue mediaDeadLetterQueue, FanoutExchange mediaDeadLetterExchange) {
        return BindingBuilder.bind(mediaDeadLetterQueue)
                .to(mediaDeadLetterExchange);
    }

    /**
     * Packages whose types may be resolved from an inbound {@code __TypeId__} header.
     *
     * <p><b>These are matched by exact equality, NOT by prefix.</b> Spring AMQP's
     * {@code DefaultJackson2JavaTypeMapper.isTrustedPackage} compares the payload's package name
     * with {@code String.equals} against each entry, so {@code "uk.jtoye.core"} would NOT trust
     * {@code uk.jtoye.core.order.OrderStateChangeEvent}, and {@code "uk.jtoye.core.*"} matches
     * nothing at all. Every package contributing a {@code @RabbitHandler} payload type must be
     * listed individually. {@code RabbitMQConfigMessageConverterTest} fails the build if a new one
     * is introduced without being added here — do not rely on catching this at runtime, because
     * the runtime symptom is a silent dead-letter.
     */
    static final String[] TRUSTED_PAYLOAD_PACKAGES = {
            "uk.jtoye.core.order",
            "uk.jtoye.core.payment",
            "uk.jtoye.core.onboarding",
    };

    /**
     * The trusted-package allowlist is load-bearing, not decorative.
     *
     * <p>{@code DefaultJackson2JavaTypeMapper} defaults to {@code [java.util, java.lang]}, so
     * resolving a {@code __TypeId__} header to an application class is rejected. That is invisible
     * for a single-method {@code @RabbitListener} with a typed parameter — Spring infers the target
     * type from the method signature and never consults the mapper — but fatal for a class-level
     * {@code @RabbitListener} + {@code @RabbitHandler} listener, which MUST resolve the type to
     * select a handler. {@link uk.jtoye.core.webhook.WebhookFanoutListener} is the only such
     * listener, and every message routed to it dead-lettered from the day it shipped.
     *
     * <p>Scoped deliberately: trust-all ({@code "*"}) clears the allowlist entirely and would
     * restore a deserialization-gadget surface on a broker carrying tenant data.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES);
    }

    /** Micrometer name for retry exhaustion; exported as {@code jtoye_amqp_retries_exhausted_total}. */
    static final String RETRIES_EXHAUSTED_METRIC = "jtoye.amqp.retries_exhausted";

    /**
     * Collapses a consumer-queue name to a BOUNDED tag value.
     *
     * <p><b>Why this exists at all.</b> The SSE fan-out declares an {@link AnonymousQueue} per JVM
     * ({@link #ORDER_EVENTS_FANOUT_QUEUE_PREFIX} + a random suffix), and the suffix changes on
     * <em>every restart</em>. Tagging the counter with the raw name would leak one Micrometer
     * series per restart, forever — an unbounded-cardinality leak in the metric added to make
     * failures visible. Its Prometheus-side twin is the {@code metric_relabel_configs} drop on the
     * {@code rabbitmq-queues} scrape job.
     *
     * <p>Package-private and static so it is unit-testable without a broker.
     *
     * @param queue the raw {@code getConsumerQueue()} value, possibly null
     * @return the queue name, the collapsed SSE literal, or {@code "unknown"}
     */
    static String normaliseQueueTag(String queue) {
        if (queue == null || queue.isBlank()) {
            return "unknown";
        }
        if (queue.startsWith(ORDER_EVENTS_FANOUT_QUEUE_PREFIX)) {
            // Trailing '.' trimmed: the tag is the queue FAMILY, not a prefix.
            return ORDER_EVENTS_FANOUT_QUEUE_PREFIX.substring(0, ORDER_EVENTS_FANOUT_QUEUE_PREFIX.length() - 1);
        }
        return queue;
    }

    /**
     * Three attempts with exponential backoff, then reject-without-requeue so the broker
     * dead-letters the message.
     *
     * <p><b>The counter is the only visibility some queues have.</b> {@code x-dead-letter-exchange}
     * is a queue ARGUMENT, and redeclaring an existing durable queue with different arguments
     * returns {@code PRECONDITION_FAILED (406)} and kills the declaring channel — so a DLX cannot
     * simply be added to {@code onboarding.notifications} without breaking startup against every
     * broker that already has that queue. That queue therefore has no DLQ, and no queue-depth alert
     * can ever see a message it drops. This counter can: it increments at the interceptor,
     * regardless of whether a dead-letter exchange exists downstream.
     *
     * <p><b>The {@code log.error} and the rethrow are byte-identical to their previous form, and
     * must stay that way.</b> The rethrow IS the dead-letter mechanism — {@code
     * AmqpRejectAndDontRequeueException} combined with {@code setDefaultRequeueRejected(false)} is
     * what routes the message to the DLX. Swallowing it, or replacing it with a plain return,
     * silently disables all four dead-letter queues while every test still passes.
     *
     * <p>{@link ObjectProvider} rather than a hard parameter so the configuration still builds with
     * no {@link MeterRegistry} on the context — same null-guard idiom as
     * {@code PaymentEventOutboxFlusher}.
     */
    @Bean
    public RetryOperationsInterceptor retryInterceptor(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer((args, cause) -> {
                    if (registry != null) {
                        String queue = null;
                        if (args != null && args.length > 0 && args[0] instanceof Message m) {
                            queue = m.getMessageProperties().getConsumerQueue();
                        }
                        Counter.builder(RETRIES_EXHAUSTED_METRIC)
                                .description("Messages that exhausted the AMQP retry policy and were rejected without requeue")
                                .tag("queue", normaliseQueueTag(queue))
                                .register(registry)
                                .increment();
                    }
                    log.error("RabbitMQ message processing failed after 3 retries: {}", cause.getMessage());
                    throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                            "Exhausted retries — routing to DLQ", cause);
                })
                .build();
    }

    /**
     * Stable, assertable text for the configurer-absent path. A test asserts on this message
     * (27-04 AC-9), so do not reword it casually — and it deliberately names both the missing
     * bean and the auto-configuration whose exclusion is the likely cause, because that is the
     * one thing an operator reading this line at 3am needs in order to act on it.
     */
    static final String CONFIGURER_ABSENT_WARN =
            "SimpleRabbitListenerContainerFactoryConfigurer is not available — "
            + "spring.rabbitmq.listener.simple.* will NOT be applied. This usually means "
            + "RabbitAutoConfiguration is excluded on the active profile. Falling back to the "
            + "hand-built container configuration (jtoye.rabbit.* still applies).";

    /**
     * Applies Boot's configurer when it exists, then re-asserts this project's three deliberate
     * overrides on top of it, then the {@code jtoye.rabbit.*} tunables.
     *
     * <p><b>Order is load-bearing.</b> The configurer runs FIRST because it would otherwise
     * overwrite the overrides that follow it; the overrides run before the prefetch/concurrency
     * assignment for the same reason.
     *
     * <p><b>Why {@link ObjectProvider} rather than a hard parameter (D-01).</b> On today's Gradle
     * test classpath the bean does exist — but only because {@code src/test/resources/application-test.yml}
     * shadows the {@code src/main/resources} one that excludes {@code RabbitAutoConfiguration}
     * (both land on the classpath under the same name; Gradle puts test resources first, and
     * {@code ClassLoader.getResource} returns the first match only). That shadowing is
     * undocumented, one rename away from reversing, and the symptom of its reversal is a context
     * that will not start. The guard costs three lines and is correct under both classpath orders.
     */
    private SimpleRabbitListenerContainerFactory buildFactory(
            String factoryName,
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            ObjectProvider<SimpleRabbitListenerContainerFactoryConfigurer> configurerProvider,
            RetryOperationsInterceptor retryInterceptor,
            int prefetch,
            int concurrency,
            int maxConcurrency) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();

        SimpleRabbitListenerContainerFactoryConfigurer configurer = configurerProvider.getIfAvailable();
        boolean configurerPresent = configurer != null;
        if (configurerPresent) {
            configurer.configure(factory, connectionFactory);
        } else {
            log.warn(CONFIGURER_ABSENT_WARN);
        }

        // The connection factory is set unconditionally: in the configurer-absent path nothing
        // else would set it, and in the present path this is the same instance the configurer used.
        factory.setConnectionFactory(connectionFactory);

        // The three deliberate overrides, re-applied AFTER the configurer so it cannot undo them.
        // setDefaultRequeueRejected(false) + the retry advice chain are the DLQ routing contract:
        // 3 attempts, then AmqpRejectAndDontRequeueException, then the dead-letter exchange.
        // Dropping either would silently turn a dead-letter into an infinite requeue loop.
        factory.setMessageConverter(jsonMessageConverter);
        // The interceptor arrives as a PARAMETER, not as a self-invocation of retryInterceptor().
        // Once that bean method took an ObjectProvider<MeterRegistry> argument, calling it here
        // stopped compiling ("method retryInterceptor ... cannot be applied to given types").
        // Injecting it is also strictly better than the @Configuration CGLIB self-call it replaces:
        // it removes a hidden intra-class coupling, and it makes the SAME singleton — the one
        // carrying the meter registry — provably the one on the chain.
        factory.setAdviceChain(retryInterceptor);
        factory.setDefaultRequeueRejected(false);

        // Finally the config layer — the whole point of the repair above.
        factory.setPrefetchCount(prefetch);
        factory.setConcurrentConsumers(concurrency);
        if (maxConcurrency > concurrency) {
            factory.setMaxConcurrentConsumers(maxConcurrency);
        }

        // Runtime-readable proof of the EFFECTIVE values (27-04 AC-3). A correct application.yml
        // over a stale image is indistinguishable from a working change unless the running
        // process says what it actually bound.
        log.info("event=rabbit_factory_configured factory={} configurerPresent={} prefetch={} concurrency={} maxConcurrency={}",
                factoryName, configurerPresent, prefetch, concurrency, maxConcurrency);

        return factory;
    }

    /**
     * The default factory for the eight non-media endpoints.
     *
     * <p>The bean NAME is load-bearing and must not be changed: Boot's
     * {@code simpleRabbitListenerContainerFactory} is {@code @ConditionalOnMissingBean(name =
     * "rabbitListenerContainerFactory")}, so renaming this would un-back-off Boot's factory and
     * leave the application with two.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            ObjectProvider<SimpleRabbitListenerContainerFactoryConfigurer> configurerProvider,
            RetryOperationsInterceptor retryInterceptor,
            RabbitListenerProperties props) {
        return buildFactory("default", connectionFactory, jsonMessageConverter, configurerProvider,
                retryInterceptor,
                props.getDefaultPrefetch(), props.getDefaultConcurrency(), props.getDefaultConcurrency());
    }

    /**
     * The dedicated container for {@code media.process} — the only CPU-bound consumer (two WebP
     * encodes per message). Referenced by name from
     * {@code MediaProcessingWorker}'s {@code @RabbitListener(containerFactory = ...)}.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory mediaRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            ObjectProvider<SimpleRabbitListenerContainerFactoryConfigurer> configurerProvider,
            RetryOperationsInterceptor retryInterceptor,
            RabbitListenerProperties props) {
        return buildFactory("media", connectionFactory, jsonMessageConverter, configurerProvider,
                retryInterceptor,
                props.getMediaPrefetch(), props.getMediaConcurrency(), props.getMediaMaxConcurrency());
    }
}
