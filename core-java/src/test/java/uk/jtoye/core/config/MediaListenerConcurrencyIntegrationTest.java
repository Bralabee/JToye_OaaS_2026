package uk.jtoye.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-broker proof that the media container factory can actually run more than one consumer
 * (27-04, T3/T8).
 *
 * <p><b>Why this needs a real broker.</b> Consumer count is a property of the AMQP connection, not
 * of the Java object: {@code SimpleMessageListenerContainer} only spawns its consumers when it
 * starts against a live broker. A unit test can assert the container was CONFIGURED with
 * concurrency N ({@code RabbitListenerContainerFactoryTest} does exactly that); only a broker can
 * show N consumers actually serving messages.
 *
 * <p><b>This test deliberately sets concurrency 2, which is NOT the shipped default.</b>
 * {@code jtoye.rabbit.media-concurrency} ships as 1, because Arm A measured peak container CPU of
 * 97.8% at concurrency 1 under a 1-CPU pin — one consumer already saturates one core on a
 * {@code 1000m} pod. So the shipped value is a measurement, while THIS test is about the
 * mechanism: it proves the factory is capable of scaling to {@code media-max-concurrency} when an
 * operator raises it, which is the capability 27-04 delivers. Asserting the shipped default here
 * instead would prove only that 1 == 1.
 *
 * <p><b>What makes it falsifiable.</b> Distinct AMQP {@code consumerTag}s are what the broker
 * assigns per consumer. On the pre-27-04 tree the factory ignored the config layer entirely, so
 * the container started with the container default of ONE consumer and this test would observe a
 * single tag.
 */
@Tag("testcontainers")
@Testcontainers
class MediaListenerConcurrencyIntegrationTest {

    private static final int CONCURRENCY = 2;
    private static final int MESSAGES = 40;
    private static final String QUEUE = "media.process.concurrency-probe";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.12-management-alpine"));

    private final RabbitMQConfig config = new RabbitMQConfig();

    /**
     * An {@link ObjectProvider} that reports the configurer as ABSENT.
     *
     * <p>That is the honest shape for this test: {@code SimpleRabbitListenerContainerFactoryConfigurer}
     * is a Boot auto-configuration bean and there is no Spring context here. It also exercises the
     * D-01 guard's fallback path — the factory must still apply {@code jtoye.rabbit.*} with no
     * configurer present, which is precisely what makes the guard safe rather than merely defensive.
     */
    /**
     * An empty {@link MeterRegistry} provider, for the same reason as {@link #noConfigurer()}:
     * there is no Spring context here, so there is no registry bean. Exercises the null guard in
     * {@code retryInterceptor(...)} — a missing registry must never stop the interceptor being
     * built, because the interceptor is what dead-letters and the counter is only observation.
     */
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noMeterRegistry() {
        return new ObjectProvider<>() {
            @Override
            public io.micrometer.core.instrument.MeterRegistry getObject() {
                throw new IllegalStateException("no MeterRegistry bean in this test");
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry getObject(Object... args) {
                return getObject();
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
                return null;
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry getIfUnique() {
                return null;
            }
        };
    }

    private static ObjectProvider<SimpleRabbitListenerContainerFactoryConfigurer> noConfigurer() {
        return new ObjectProvider<>() {
            @Override
            public SimpleRabbitListenerContainerFactoryConfigurer getObject() {
                throw new IllegalStateException("no configurer bean in this test");
            }

            @Override
            public SimpleRabbitListenerContainerFactoryConfigurer getObject(Object... args) {
                return getObject();
            }

            @Override
            public SimpleRabbitListenerContainerFactoryConfigurer getIfAvailable() {
                return null;
            }

            @Override
            public SimpleRabbitListenerContainerFactoryConfigurer getIfUnique() {
                return null;
            }
        };
    }

    private CachingConnectionFactory newConnectionFactory() {
        CachingConnectionFactory cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        return cf;
    }

    @Test
    @DisplayName("the media factory runs media-concurrency consumers, and more than one of them serves messages")
    void mediaFactoryRunsMultipleConsumersAgainstARealBroker() throws Exception {
        CachingConnectionFactory connectionFactory = newConnectionFactory();

        RabbitListenerProperties props = new RabbitListenerProperties();
        props.setMediaPrefetch(1);              // low prefetch: without it one consumer can hoard the burst
        props.setMediaConcurrency(CONCURRENCY);
        props.setMediaMaxConcurrency(CONCURRENCY);

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.declareQueue(new Queue(QUEUE, false, false, true));

        // The retry interceptor is now a bean-method PARAMETER rather than a self-invocation
        // (27-03 D-05c: retryInterceptor() gained an ObjectProvider<MeterRegistry> for the
        // jtoye.amqp.retries_exhausted counter, which made the old self-call fail to compile).
        // Built here with an EMPTY provider, exercising the no-registry null guard on the way:
        // this test is about consumer concurrency, not about metrics.
        SimpleMessageListenerContainer container = config
                .mediaRabbitListenerContainerFactory(
                        connectionFactory, config.jsonMessageConverter(), noConfigurer(),
                        config.retryInterceptor(noMeterRegistry()), props)
                .createListenerContainer();
        container.setQueueNames(QUEUE);

        Set<String> consumerTags = ConcurrentHashMap.newKeySet();
        CountDownLatch delivered = new CountDownLatch(MESSAGES);
        container.setupMessageListener((MessageListener) (Message message) -> {
            String tag = message.getMessageProperties().getConsumerTag();
            if (tag != null) {
                consumerTags.add(tag);
            }
            // Each message must take long enough that a single consumer cannot drain the burst
            // before the second one is handed anything — otherwise a PASSING single-consumer run
            // would be indistinguishable from a genuinely concurrent one.
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delivered.countDown();
        });

        container.start();
        try {
            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers"))
                    .as("the container must be CONFIGURED from jtoye.rabbit.media-concurrency")
                    .isEqualTo(CONCURRENCY);

            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            for (int i = 0; i < MESSAGES; i++) {
                template.convertAndSend(QUEUE, "probe-" + i);
            }

            assertThat(delivered.await(60, TimeUnit.SECONDS))
                    .as("all %d messages must be delivered; a timeout here is a broken harness, "
                            + "not evidence about concurrency", MESSAGES)
                    .isTrue();

            assertThat(consumerTags)
                    .as("at least %d DISTINCT AMQP consumer tags must have served messages — one tag "
                            + "means the factory started a single consumer and ignored the config layer, "
                            + "which is the pre-27-04 behaviour", CONCURRENCY)
                    .hasSizeGreaterThanOrEqualTo(CONCURRENCY);
        } finally {
            container.stop();
            connectionFactory.destroy();
        }
    }
}
