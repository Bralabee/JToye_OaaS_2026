package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.config.RabbitMQConfig;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-broker proof of issue #92's acceptance criterion (1): at the 3-replica
 * floor the manifests set, EVERY replica — and therefore every dashboard's SSE
 * connection, wherever the load balancer pinned it — receives EVERY order event.
 *
 * <p>Simulates three core-java replicas the way production produces them: each
 * "replica" gets its own AMQP connection, declares its own per-instance
 * {@link AnonymousQueue} via the production {@link RabbitMQConfig} bean methods,
 * and binds it to the shared {@code order.events} topic exchange. A single event
 * is published once through the production JSON converter (same code path as
 * {@code OrderEventPublisher}).</p>
 *
 * <p>Also pins the durable queue's unchanged competing-consumer semantics: with
 * two consumers attached to {@code order.state-changes}, one published event is
 * delivered exactly once fleet-wide — emails and metrics must not multiply by
 * replica count.</p>
 */
@Tag("testcontainers")
@Testcontainers
class OrderEventFanoutTopologyIntegrationTest {

    private static final int REPLICAS = 3;

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.12-management-alpine"));

    private final RabbitMQConfig config = new RabbitMQConfig();

    private CachingConnectionFactory newConnectionFactory() {
        CachingConnectionFactory cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        return cf;
    }

    @Test
    @DisplayName("one published order event reaches ALL 3 replicas' fan-out queues AND exactly ONE durable consumer")
    void everyReplicaReceivesEveryOrderEvent() throws Exception {
        // Publish through the production converter (same code path as OrderEventPublisher).
        MessageConverter publishConverter = config.jsonMessageConverter();
        // Consume through a converter that trusts the event package: a production
        // @RabbitListener resolves the payload type from the annotated method
        // signature (INFERRED precedence) and never consults the __TypeId__
        // trusted-packages check; a raw fromMessage() call in this test does, so
        // grant the one package explicitly rather than trust-all.
        MessageConverter consumeConverter =
                new org.springframework.amqp.support.converter.Jackson2JsonMessageConverter(
                        "uk.jtoye.core.order");

        List<CachingConnectionFactory> factories = new ArrayList<>();
        List<SimpleMessageListenerContainer> containers = new ArrayList<>();
        List<List<OrderStateChangeEvent>> receivedPerReplica = new ArrayList<>();
        CountDownLatch fanoutLatch = new CountDownLatch(REPLICAS);
        AtomicInteger durableDeliveries = new AtomicInteger();
        CountDownLatch durableLatch = new CountDownLatch(1);

        CachingConnectionFactory publisherCf = newConnectionFactory();
        factories.add(publisherCf);

        try {
            // Shared topology, exactly as the production beans declare it.
            RabbitAdmin publisherAdmin = new RabbitAdmin(publisherCf);
            publisherAdmin.declareExchange(config.orderEventsExchange());
            publisherAdmin.declareExchange(config.deadLetterExchange());
            publisherAdmin.declareQueue(config.orderEventsQueue());
            publisherAdmin.declareQueue(config.deadLetterQueue());
            publisherAdmin.declareBinding(
                    config.orderEventsBinding(config.orderEventsQueue(), config.orderEventsExchange()));
            publisherAdmin.declareBinding(config.deadLetterBinding());

            // Three simulated replicas: own connection each (the fan-out queue is
            // exclusive, so declare + consume must share a connection, exactly as
            // one JVM's CachingConnectionFactory does in production).
            for (int i = 0; i < REPLICAS; i++) {
                CachingConnectionFactory cf = newConnectionFactory();
                factories.add(cf);
                RabbitAdmin admin = new RabbitAdmin(cf);
                AnonymousQueue fanoutQueue = config.orderEventsFanoutQueue();
                admin.declareQueue(fanoutQueue);
                admin.declareBinding(config.orderEventsFanoutBinding(fanoutQueue, config.orderEventsExchange()));

                List<OrderStateChangeEvent> received = new CopyOnWriteArrayList<>();
                receivedPerReplica.add(received);
                containers.add(startContainer(cf, fanoutQueue.getName(), message -> {
                    received.add((OrderStateChangeEvent) consumeConverter.fromMessage(message));
                    fanoutLatch.countDown();
                }));
            }

            // Two competing consumers on the durable queue (the fleet's
            // OrderStateChangeListener instances).
            for (int i = 0; i < 2; i++) {
                CachingConnectionFactory cf = newConnectionFactory();
                factories.add(cf);
                containers.add(startContainer(cf, RabbitMQConfig.ORDER_EVENTS_QUEUE, message -> {
                    durableDeliveries.incrementAndGet();
                    durableLatch.countDown();
                }));
            }

            // Publish ONE event through the production converter + routing key scheme
            // (OrderEventPublisher uses "order.state." + status).
            UUID orderId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            OrderStateChangeEvent event = new OrderStateChangeEvent(
                    orderId, tenantId, "ORD-92-FANOUT",
                    OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
            RabbitTemplate template = new RabbitTemplate(publisherCf);
            template.setMessageConverter(publishConverter);
            template.convertAndSend(RabbitMQConfig.ORDER_EVENTS_EXCHANGE, "order.state.confirmed", event);

            assertTrue(fanoutLatch.await(15, TimeUnit.SECONDS),
                    "every replica's fan-out queue must receive the event — a miss means some dashboards go blind");
            assertTrue(durableLatch.await(15, TimeUnit.SECONDS),
                    "the durable competing-consumer queue must still receive the event");

            // Let any wrong duplicate deliveries surface before counting.
            Thread.sleep(1_000);

            for (int i = 0; i < REPLICAS; i++) {
                List<OrderStateChangeEvent> received = receivedPerReplica.get(i);
                assertEquals(1, received.size(), "replica " + i + " must receive the event exactly once");
                OrderStateChangeEvent got = received.get(0);
                assertEquals(orderId, got.orderId());
                assertEquals(tenantId, got.tenantId(), "tenant id must survive the round-trip — SSE routes by it");
                assertEquals("ORD-92-FANOUT", got.orderNumber());
                assertEquals(OrderStatus.CONFIRMED, got.newStatus());
            }
            assertEquals(1, durableDeliveries.get(),
                    "durable queue must stay competing-consumer: side effects (email, metrics) run once fleet-wide");
        } finally {
            containers.forEach(SimpleMessageListenerContainer::stop);
            factories.forEach(CachingConnectionFactory::destroy);
        }
    }

    private SimpleMessageListenerContainer startContainer(CachingConnectionFactory cf,
                                                          String queueName,
                                                          java.util.function.Consumer<Message> handler) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(cf);
        container.setQueueNames(queueName);
        container.setMessageListener(handler::accept);
        container.afterPropertiesSet();
        container.start();
        return container;
    }
}
