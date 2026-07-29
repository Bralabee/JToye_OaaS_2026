package uk.jtoye.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry-exhaustion counter and its cardinality guard (phase 27, plan 27-03, D-05).
 *
 * <p><b>What this counter is for.</b> {@code onboarding.notifications} has no
 * {@code x-dead-letter-exchange} and cannot be given one without a 406 at startup
 * (see {@link RabbitMQDeadLetterTopologyTest}), so a message it exhausts retries on is simply
 * DROPPED — no DLQ receives it, and therefore no queue-depth alert can ever see it. Counting at the
 * interceptor is the only place that catches it, because the interceptor runs before the broker
 * decides where (or whether) to park the message.
 *
 * <p>Plain unit test: no broker, no Spring context, no database.
 */
class RabbitMQRetryExhaustedCounterTest {

    private final RabbitMQConfig config = new RabbitMQConfig();

    /** An ObjectProvider that yields exactly one object — the shape Spring hands a bean method. */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args)          { return value; }
            @Override public T getObject()                        { return value; }
            @Override public T getIfAvailable()                   { return value; }
            @Override public T getIfUnique()                      { return value; }
        };
    }

    /** An ObjectProvider that yields nothing — the no-MeterRegistry-on-the-context case. */
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args)          { return null; }
            @Override public T getObject()                        { return null; }
            @Override public T getIfAvailable()                   { return null; }
            @Override public T getIfUnique()                      { return null; }
        };
    }

    private static Message messageFromQueue(String queueName) {
        MessageProperties props = new MessageProperties();
        props.setConsumerQueue(queueName);
        return new Message("{}".getBytes(), props);
    }

    /**
     * The arguments the retry advice is ACTUALLY invoked with in production — measured on the
     * running stack, not assumed.
     *
     * <p>Spring AMQP does not proxy {@code MessageListener.onMessage(Message)}. It applies the
     * advice chain to {@code AbstractMessageListenerContainer}'s internal {@code ContainerDelegate},
     * whose signature is {@code invokeListener(Channel, Object)} — so the array has length 2 and
     * <b>element 0 is a Channel proxy, not the Message</b>:
     *
     * <pre>DIAG args=2 c0=jdk.proxy2.$Proxy293 q=not-a-Message</pre>
     *
     * <p><b>This helper exists because the first version of this test did not have it.</b> It
     * passed {@code new Object[]{message}}, which is a shape production never produces, so the test
     * was green while every real increment landed on {@code queue="unknown"} — a fixture that
     * encoded the author's assumption rather than the runtime, and therefore could not fail on the
     * defect. The live end-to-end proof is what caught it. Keep the Channel stand-in in position 0.
     */
    private static Object[] containerDelegateArgs(Object payload) {
        Object channelStandIn = java.lang.reflect.Proxy.newProxyInstance(
                RabbitMQRetryExhaustedCounterTest.class.getClassLoader(),
                new Class<?>[]{java.io.Closeable.class},
                (proxy, method, methodArgs) -> null);
        return new Object[]{channelStandIn, payload};
    }

    /**
     * Reaches the recoverer the builder installed. {@code RetryInterceptorBuilder.stateless()}
     * wraps the lambda in a {@link RecoveryCallback} held on the interceptor, so there is no public
     * accessor; the field is read reflectively and the callback invoked with the arguments Spring
     * AMQP would pass.
     */
    private static void invokeRecoverer(RetryOperationsInterceptor interceptor, Object[] args) throws Throwable {
        Object recoverer = ReflectionTestUtils.getField(interceptor, "recoverer");
        assertThat(recoverer)
                .as("the interceptor must carry a recoverer — without one, retry exhaustion "
                        + "propagates the original exception and the message is REQUEUED, not "
                        + "dead-lettered. This assertion is VOID rather than passing if the field "
                        + "name changed in a Spring Retry upgrade.")
                .isNotNull();
        ((MethodInvocationRecoverer<?>) recoverer).recover(args, new IllegalStateException("boom"));
    }

    // =====================================================================================
    // normaliseQueueTag — the cardinality guard
    // =====================================================================================

    @Test
    @DisplayName("a plain queue name passes through unchanged — the tag must stay useful")
    void plainQueueNamePassesThrough() {
        assertThat(RabbitMQConfig.normaliseQueueTag("onboarding.notifications"))
                .isEqualTo("onboarding.notifications");
        assertThat(RabbitMQConfig.normaliseQueueTag("media.process")).isEqualTo("media.process");
    }

    @Test
    @DisplayName("the per-JVM SSE queue name COLLAPSES — otherwise one tag series leaks per restart, forever")
    void sseQueueNameCollapsesToTheFamily() {
        // The AnonymousQueue suffix is random and changes on every JVM restart. Left untouched,
        // this single tag would grow Micrometer's series count without bound in the metric added
        // to make failures visible — a monitoring change that becomes its own outage.
        Stream.of(
                RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "kP5foIsLRpyX0fWkNqTBGw",
                RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "Zn_7iF4QTQKtnBnyuIKn9Q",
                RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "aaaaaaaaaaaaaaaaaaaaaa"
        ).forEach(name -> assertThat(RabbitMQConfig.normaliseQueueTag(name))
                .as("every per-JVM SSE queue name must collapse to ONE tag value")
                .isEqualTo("order.state-changes.sse"));

        // And all three really are distinct inputs — otherwise the assertion above is trivial.
        assertThat(Stream.of(
                RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "kP5foIsLRpyX0fWkNqTBGw",
                RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "Zn_7iF4QTQKtnBnyuIKn9Q",
                RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "aaaaaaaaaaaaaaaaaaaaaa"
        ).distinct().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("null and blank become 'unknown' — never a null tag, which Micrometer rejects")
    void nullAndBlankBecomeUnknown() {
        assertThat(RabbitMQConfig.normaliseQueueTag(null)).isEqualTo("unknown");
        assertThat(RabbitMQConfig.normaliseQueueTag("")).isEqualTo("unknown");
        assertThat(RabbitMQConfig.normaliseQueueTag("   ")).isEqualTo("unknown");
    }

    // =====================================================================================
    // The interceptor's recoverer
    // =====================================================================================

    @Test
    @DisplayName("retry exhaustion increments the counter, tagged with the queue, AND still rethrows")
    void recovererCountsAndStillRethrows() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        RetryOperationsInterceptor interceptor = config.retryInterceptor(providerOf(registry));

        assertThat(registry.find(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC).counter())
                .as("the counter must not exist before the first exhaustion — otherwise the delta "
                        + "below is not measuring anything")
                .isNull();

        assertThatThrownBy(() ->
                invokeRecoverer(interceptor, containerDelegateArgs(messageFromQueue("onboarding.notifications"))))
                .as("THE RETHROW IS THE DEAD-LETTER MECHANISM. AmqpRejectAndDontRequeueException "
                        + "plus setDefaultRequeueRejected(false) is what routes a message to the "
                        + "DLX; swallowing it silently disables all four dead-letter queues.")
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Exhausted retries");

        assertThat(registry.get(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC)
                        .tag("queue", "onboarding.notifications").counter().count())
                .as("onboarding.notifications has NO DLX, so this counter is the ONLY signal that "
                        + "a message was dropped there")
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the SSE queue's exhaustion is counted under the collapsed tag, not the random name")
    void recovererTagsTheSseQueueWithTheCollapsedName() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        RetryOperationsInterceptor interceptor = config.retryInterceptor(providerOf(registry));
        String randomName = RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX + "kP5foIsLRpyX0fWkNqTBGw";

        assertThatThrownBy(() -> invokeRecoverer(interceptor, containerDelegateArgs(messageFromQueue(randomName))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(registry.get(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC)
                .tag("queue", "order.state-changes.sse").counter().count()).isEqualTo(1.0d);
        assertThat(registry.find(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC)
                .tag("queue", randomName).counter())
                .as("the raw per-JVM name must NOT appear as a tag value")
                .isNull();
    }

    @Test
    @DisplayName("a non-Message argument still counts, under 'unknown' rather than throwing")
    void nonMessageArgumentIsCountedAsUnknown() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        RetryOperationsInterceptor interceptor = config.retryInterceptor(providerOf(registry));

        assertThatThrownBy(() -> invokeRecoverer(interceptor, containerDelegateArgs("not a Message")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(registry.get(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC)
                .tag("queue", "unknown").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("with NO MeterRegistry the interceptor still builds and still rethrows — the null guard")
    void noRegistryStillBuildsAndStillRethrows() throws Throwable {
        // The metric is an addition; it must never become a precondition for dead-lettering.
        RetryOperationsInterceptor interceptor = config.retryInterceptor(emptyProvider());
        assertThat(interceptor).isNotNull();

        assertThatThrownBy(() ->
                invokeRecoverer(interceptor, containerDelegateArgs(messageFromQueue("media.process"))))
                .as("no registry must not turn a dead-letter into an NPE, and must not turn it "
                        + "into a silent success either")
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // =====================================================================================
    // THE ARGUMENT SHAPE ITSELF. This is the assertion whose absence let the defect ship.
    // =====================================================================================

    @Test
    @DisplayName("findMessage scans past the Channel — args[0] is NEVER the Message in production")
    void findMessageScansPastTheChannelArgument() {
        Message msg = messageFromQueue("media.process");
        Object[] real = containerDelegateArgs(msg);

        assertThat(real[0])
                .as("position 0 must NOT be a Message — if this ever becomes one, the fixture has "
                        + "drifted back to the assumed shape and stops testing anything")
                .isNotInstanceOf(Message.class);
        assertThat(RabbitMQConfig.findMessage(real))
                .as("the recoverer must find the Message wherever it sits in the argument list; "
                        + "an args[0] test returns null here and every increment lands on 'unknown'")
                .isSameAs(msg);

        // Batch listeners deliver a List<Message>.
        assertThat(RabbitMQConfig.findMessage(new Object[]{new Object(), java.util.List.of(msg)}))
                .isSameAs(msg);
        // And it degrades safely rather than throwing.
        assertThat(RabbitMQConfig.findMessage(null)).isNull();
        assertThat(RabbitMQConfig.findMessage(new Object[]{})).isNull();
        assertThat(RabbitMQConfig.findMessage(new Object[]{"x", java.util.List.of()})).isNull();
    }

    @Test
    @DisplayName("end-to-end shape: the counter is tagged with the REAL queue, not 'unknown'")
    void counterIsTaggedWithTheRealQueueUnderTheProductionArgumentShape() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        RetryOperationsInterceptor interceptor = config.retryInterceptor(providerOf(registry));

        assertThatThrownBy(() ->
                invokeRecoverer(interceptor, containerDelegateArgs(messageFromQueue("media.process"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(registry.get(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC)
                .tag("queue", "media.process").counter().count()).isEqualTo(1.0d);
        assertThat(registry.find(RabbitMQConfig.RETRIES_EXHAUSTED_METRIC)
                .tag("queue", "unknown").counter())
                .as("'unknown' is the value the pre-fix implementation produced for EVERY message; "
                        + "seeing it here means per-queue attribution is gone again")
                .isNull();
    }
}
