package uk.jtoye.core.boot4;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-broker proof that a message a Boot-3.5 pod left on the broker is dispatched by its
 * {@code __TypeId__} to the matching class-level {@code @RabbitHandler} on Boot 4 (Phase 38, plan
 * 38-08, BOOT4-06, T-38-20/T-38-21).
 *
 * <p>The bytes are the 38-01 {@code jackson2-golden/amqp/PaymentEvent.body}, with every property
 * of {@code PaymentEvent.headers.tsv} ({@code contentType}, {@code contentEncoding},
 * {@code __TypeId__}), published RAW to a real RabbitMQ 4.3.4 broker, so no Boot-4 code touches
 * them before the consumer does. The consumer is a test-scoped listener of the same SHAPE as
 * {@code WebhookFanoutListener}: a class-level {@code @RabbitListener} with one
 * {@code @RabbitHandler} per trusted payload type and an {@code isDefault} catch-all. That shape
 * cannot infer the payload type from a method signature, so the application's converter must
 * resolve {@code __TypeId__} against {@code TRUSTED_PAYLOAD_PACKAGES} to pick the handler; a
 * refusal or a wrong resolution would land in the catch-all or nowhere.
 *
 * <p>It runs on the application's own {@code rabbitListenerContainerFactory} (converter, retry
 * interceptor and all), on a dedicated test queue that nothing production-side binds. The test
 * profile keeps every production listener stopped ({@code auto-startup=false}); only this listener
 * starts ({@code autoStartup = "true"} on the endpoint overrides the factory), so no production
 * consumer and none of its database side effects is involved.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import({NoScheduledTriggersTestConfig.class, AmqpTypeIdDispatchIntegrationTest.DispatchProbeConfig.class})
class AmqpTypeIdDispatchIntegrationTest {

    static final String PROBE_QUEUE = "boot4.typeid-dispatch.probe";

    /** How long a delivery may take before its absence is a finding, not a slow broker. */
    private static final long DEADLINE_SECONDS = 20;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    // Same image string as the other real-broker tests (the Testcontainers library stays at 1.21.4).
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.4-management-alpine"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Registered AFTER the support method, which points the app at a dead broker port.
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    /** What the probe listener received, per handler. Reset before each test. */
    static final class Received {
        static final List<Object> payment = new CopyOnWriteArrayList<>();
        static final List<Object> others = new CopyOnWriteArrayList<>();
        static final List<Object> fallback = new CopyOnWriteArrayList<>();
        static volatile CountDownLatch paymentLatch = new CountDownLatch(1);

        static void reset() {
            payment.clear();
            others.clear();
            fallback.clear();
            paymentLatch = new CountDownLatch(1);
        }
    }

    /**
     * The probe: WebhookFanoutListener's shape, on a test queue. Handlers for every trusted payload
     * type plus the catch-all; each records what it got.
     */
    @RabbitListener(queues = PROBE_QUEUE, containerFactory = "rabbitListenerContainerFactory", autoStartup = "true")
    static class TypeIdDispatchProbe {

        @RabbitHandler
        public void onOrderState(OrderStateChangeEvent event) {
            Received.others.add(event);
        }

        @RabbitHandler
        public void onRefund(RefundEvent event) {
            Received.others.add(event);
        }

        @RabbitHandler
        public void onOnboarding(OnboardingStateChangeEvent event) {
            Received.others.add(event);
        }

        @RabbitHandler
        public void onPayment(PaymentEvent event) {
            Received.payment.add(event);
            Received.paymentLatch.countDown();
        }

        @RabbitHandler(isDefault = true)
        public void onOther(Object event) {
            Received.fallback.add(event);
        }
    }

    @TestConfiguration
    static class DispatchProbeConfig {
        /** Durable + auto-delete: RabbitMQ 4.x refuses a transient non-exclusive queue. */
        @Bean
        Queue typeIdDispatchProbeQueue() {
            return new Queue(PROBE_QUEUE, true, false, true);
        }

        @Bean
        TypeIdDispatchProbe typeIdDispatchProbe() {
            return new TypeIdDispatchProbe();
        }
    }

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private MessageConverter jsonMessageConverter;

    @BeforeEach
    void reset() {
        Received.reset();
    }

    @Test
    @DisplayName("a Boot-3.5 PaymentEvent message on a real broker reaches the PaymentEvent @RabbitHandler by __TypeId__, equal to the golden event")
    void jackson2PaymentMessage_isDispatchedByTypeId() throws Exception {
        assertEquals("org.springframework.amqp.support.converter.JacksonJsonMessageConverter",
                jsonMessageConverter.getClass().getName(), "the application converter under test");

        Message raw = InFlightFixtures.amqpMessage("PaymentEvent");
        assertEquals("uk.jtoye.core.payment.PaymentEvent", raw.getMessageProperties().getHeaders().get("__TypeId__"));
        rabbitTemplate.send("", PROBE_QUEUE, raw);

        assertTrue(Received.paymentLatch.await(DEADLINE_SECONDS, TimeUnit.SECONDS),
                "the PaymentEvent @RabbitHandler received NOTHING within " + DEADLINE_SECONDS + "s. "
                        + "Catch-all got " + Received.fallback + "; other handlers got " + Received.others
                        + ". A refused or mis-resolved __TypeId__ lands there or is dead-lettered.");
        assertEquals(1, Received.payment.size(), "exactly one delivery to the PaymentEvent handler");
        InFlightFixtures.assertSameEvent(GoldenSamples.paymentEvent(),
                assertInstanceOf(PaymentEvent.class, Received.payment.get(0)));

        // Let a wrong second delivery surface before asserting the catch-all stayed empty.
        Thread.sleep(1_000);
        assertTrue(Received.fallback.isEmpty(), "the isDefault handler must receive nothing, got " + Received.fallback);
        assertTrue(Received.others.isEmpty(), "no other typed handler may receive it, got " + Received.others);
    }
}
