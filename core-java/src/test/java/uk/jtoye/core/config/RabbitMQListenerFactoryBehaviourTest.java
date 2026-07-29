package uk.jtoye.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The DLQ routing contract asserted on the BUILT container, and — the part 27-04's sibling test
 * cannot make — that the container carries the SAME interceptor bean the context published.
 *
 * <p><b>Why not a diff scan.</b> Plan 27-03's drafted criterion for this was
 * {@code git diff RabbitMQConfig.java | grep} for removed lines mentioning
 * {@code setDefaultRequeueRejected}. That form fires RED on a CORRECT change — 27-04 relocated
 * exactly that call — and it passes on an empty diff, on a missing file, and after the change is
 * committed. It cannot distinguish a refactor from a regression, which is the only thing it was
 * supposed to do.
 *
 * <p><b>Why this is not a duplicate of
 * {@link RabbitListenerContainerFactoryTest#bothFactoriesPreserveTheDlqRoutingContract()}.</b> That
 * test asserts the chain contains <em>an</em> instance of {@link RetryOperationsInterceptor}, which
 * a freshly-constructed one would also satisfy. This one asserts it is <em>the very bean</em> the
 * context published — the identity check that proves 27-03's D-05c wiring. That matters because the
 * published bean is the one holding the {@link MeterRegistry}: an interceptor built by a
 * self-invocation instead of injected would still be a {@code RetryOperationsInterceptor}, still
 * dead-letter correctly, and still count nothing on a context where the registry arrived later.
 * Identity is what separates "retry works" from "retry works AND is observable".
 */
class RabbitMQListenerFactoryBehaviourTest {

    private static final String[] FACTORY_BEANS =
            {"rabbitListenerContainerFactory", "mediaRabbitListenerContainerFactory"};

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RabbitMQConfig.class)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(MessageConverter.class, Jackson2JsonMessageConverter::new)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    @Test
    @DisplayName("both factories keep requeue-on-reject FALSE — otherwise a dead-letter is an infinite requeue loop")
    void requeueOnRejectStaysFalse() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            for (String bean : FACTORY_BEANS) {
                SimpleMessageListenerContainer container = ctx
                        .getBean(bean, SimpleRabbitListenerContainerFactory.class)
                        .createListenerContainer();
                assertThat(ReflectionTestUtils.getField(container, "defaultRequeueRejected"))
                        .as("%s: setDefaultRequeueRejected(false) is half the DLQ routing contract. "
                                + "Flip it to true and AmqpRejectAndDontRequeueException requeues "
                                + "the message instead of dead-lettering it — a hot loop that no "
                                + "queue-depth alert distinguishes from healthy traffic.", bean)
                        .isEqualTo(false);
            }
        });
    }

    @Test
    @DisplayName("the advice chain carries THE published RetryOperationsInterceptor bean, not merely one of its kind")
    void adviceChainCarriesTheInjectedInterceptorBeanItself() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            RetryOperationsInterceptor published = ctx.getBean(RetryOperationsInterceptor.class);

            for (String bean : FACTORY_BEANS) {
                SimpleMessageListenerContainer container = ctx
                        .getBean(bean, SimpleRabbitListenerContainerFactory.class)
                        .createListenerContainer();

                // FIELD NAME RECORDED RATHER THAN TRUSTED: SimpleMessageListenerContainer exposes
                // no public accessor for the chain (getAdviceChain() is protected on
                // AbstractMessageListenerContainer), so it is read reflectively. Verified against
                // the spring-amqp on this classpath at execution time; if a version bump renames
                // it, this assertion fails LOUDLY on a null rather than passing on nothing.
                Advice[] chain = (Advice[]) ReflectionTestUtils.getField(container, "adviceChain");
                assertThat(chain)
                        .as("%s: the advice chain field could not be read — this arm is VOID, not "
                                + "passing. Check the field name against the spring-amqp version.", bean)
                        .isNotNull()
                        .isNotEmpty();

                assertThat(chain)
                        .as("%s: the container must carry the SAME interceptor instance the context "
                                + "published. A self-invoked retryInterceptor() would still be a "
                                + "RetryOperationsInterceptor and would still dead-letter — but it "
                                + "would be a DIFFERENT object, and the one holding the MeterRegistry "
                                + "would not be the one on the chain, so jtoye_amqp_retries_exhausted_total "
                                + "would stay at zero through every real failure.", bean)
                        .anyMatch(a -> a == published);
            }
        });
    }

    @Test
    @DisplayName("the contract survives a REFACTOR: it is asserted on behaviour, not on the diff")
    void theAssertionIsAboutBehaviourNotSourceOrdering() {
        // 27-04 moved setDefaultRequeueRejected(false) to a different position inside the factory
        // builder, which is a correct change. The diff-scan criterion this test replaces went RED on
        // exactly that. There is nothing here that source position can affect: both properties are
        // read off the constructed container.
        runner().run(ctx -> {
            SimpleMessageListenerContainer container = ctx
                    .getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class)
                    .createListenerContainer();
            assertThat(ReflectionTestUtils.getField(container, "defaultRequeueRejected")).isEqualTo(false);
            assertThat((Advice[]) ReflectionTestUtils.getField(container, "adviceChain"))
                    .anyMatch(RetryOperationsInterceptor.class::isInstance);
        });
    }
}
