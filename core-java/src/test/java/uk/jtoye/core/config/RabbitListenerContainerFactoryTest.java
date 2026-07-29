package uk.jtoye.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The permanent regression guard for the inert-container-factory class (27-04, AC-2 / AC-9).
 *
 * <p><b>The defect this exists to stop returning.</b> {@link RabbitMQConfig} declares a bean named
 * {@code rabbitListenerContainerFactory}, and Boot's
 * {@code RabbitAnnotationDrivenConfiguration.simpleRabbitListenerContainerFactory} is
 * {@code @ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")}. Boot's factory
 * therefore backed off, {@code SimpleRabbitListenerContainerFactoryConfigurer} never ran, and the
 * whole {@code spring.rabbitmq.listener.simple.*} family was a silent no-op — including the
 * {@code auto-startup=false} that 22 test files register. These tests fail on the pre-27-04 tree.
 *
 * <p>Deliberately an UNTAGGED plain unit test: no broker, no Postgres, no cluster. It runs in the
 * fast {@code :core-java:test} task, following {@code StompCredentialResolutionTest}'s precedent.
 */
class RabbitListenerContainerFactoryTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger configLogger;

    @BeforeEach
    void attachAppender() {
        configLogger = (Logger) LoggerFactory.getLogger(RabbitMQConfig.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        configLogger.addAppender(logAppender);
        configLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        configLogger.detachAppender(logAppender);
    }

    /** The runner supplies every collaborator EXCEPT the configurer — see AC-9 below. */
    private ApplicationContextRunner runnerWithoutConfigurer(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(RabbitMQConfig.class)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(MessageConverter.class, Jackson2JsonMessageConverter::new)
                .withPropertyValues(properties);
    }

    // =====================================================================================
    // The config layer is actually applied — the criterion that fails on the pre-27-04 tree.
    // =====================================================================================

    @Test
    void mediaFactoryAppliesTheConfiguredPrefetchAndConcurrency() {
        runnerWithoutConfigurer(
                "jtoye.rabbit.media-prefetch=7",
                "jtoye.rabbit.media-concurrency=3",
                "jtoye.rabbit.media-max-concurrency=5"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SimpleRabbitListenerContainerFactory factory =
                    ctx.getBean("mediaRabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);
            SimpleMessageListenerContainer container = factory.createListenerContainer();

            // Distinctive values, so a factory that ignored the config layer could not
            // coincidentally match them (the container default is 250 / 1).
            assertThat(ReflectionTestUtils.getField(container, "prefetchCount"))
                    .as("the media container must take its prefetch from jtoye.rabbit.media-prefetch")
                    .isEqualTo(7);
            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(3);
            assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(5);
        });
    }

    @Test
    void defaultFactoryAppliesTheConfiguredPrefetchAndConcurrency() {
        runnerWithoutConfigurer(
                "jtoye.rabbit.default-prefetch=11",
                "jtoye.rabbit.default-concurrency=2"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SimpleRabbitListenerContainerFactory factory =
                    ctx.getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);
            SimpleMessageListenerContainer container = factory.createListenerContainer();

            assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(11);
            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(2);
        });
    }

    @Test
    void shippedDefaultsKeepTheEightNonMediaQueuesAtTodaysEffectiveBehaviour() {
        // No property overrides: the RabbitListenerProperties defaults must reproduce the
        // pre-27-04 effective behaviour for the untouched queues, which is what makes the
        // factory repair provably behaviour-preserving rather than a silent tuning change.
        runnerWithoutConfigurer().run(ctx -> {
            SimpleMessageListenerContainer container = ctx
                    .getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class)
                    .createListenerContainer();

            assertThat(ReflectionTestUtils.getField(container, "prefetchCount"))
                    .as("250 is AbstractMessageListenerContainer.DEFAULT_PREFETCH_COUNT — today's effective value")
                    .isEqualTo(250);
            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers"))
                    .as("concurrency 1 preserves per-order ordering on order.state-changes")
                    .isEqualTo(1);
        });
    }

    // =====================================================================================
    // D-13 / J2 — the BEHAVIOURAL assertion that replaces 27-03's diff-scan T5.5.
    //
    // 27-03's criterion greps the diff of RabbitMQConfig.java for removed lines containing
    // setDefaultRequeueRejected / AmqpRejectAndDontRequeueException / maxAttempts(3). This plan
    // RELOCATES setDefaultRequeueRejected(false), which produces exactly such a removed line —
    // so that criterion fires RED on a correct change. These assertions are what 27-03 adopts
    // instead: they assert the DLQ routing contract on the built container, which is the thing
    // the diff scan was trying to protect.
    // =====================================================================================

    @Test
    void bothFactoriesPreserveTheDlqRoutingContract() {
        runnerWithoutConfigurer().run(ctx -> {
            for (String beanName : new String[]{"rabbitListenerContainerFactory", "mediaRabbitListenerContainerFactory"}) {
                SimpleMessageListenerContainer container = ctx
                        .getBean(beanName, SimpleRabbitListenerContainerFactory.class)
                        .createListenerContainer();

                assertThat(ReflectionTestUtils.getField(container, "defaultRequeueRejected"))
                        .as("%s: requeue-on-reject must stay FALSE or a dead-letter becomes an "
                                + "infinite requeue loop", beanName)
                        .isEqualTo(false);

                Advice[] chain = (Advice[]) ReflectionTestUtils.getField(container, "adviceChain");
                assertThat(chain)
                        .as("%s: the retry advice chain is half the DLQ contract — 3 attempts, then "
                                + "AmqpRejectAndDontRequeueException, then the dead-letter exchange", beanName)
                        .isNotEmpty();
                assertThat(chain).anyMatch(RetryOperationsInterceptor.class::isInstance);
            }
        });
    }

    // =====================================================================================
    // AC-9 — the ObjectProvider guard is load-bearing.
    //
    // The runner supplies ConnectionFactory ITSELF. That is the whole point, and it is why this
    // replaces the withdrawn rename-based form: with core-java/src/test/resources/application-test.yml
    // renamed away there is no @Bean AMQP ConnectionFactory anywhere in the project, so parameter 0
    // fails BEFORE the configurer parameter is considered — the hard-injected and guarded forms then
    // fail identically and the criterion cannot discriminate. That renamed file also carries the
    // whole H2 datasource, so the "boots with its WARN" arm could not boot either.
    // =====================================================================================

    @Test
    void contextStartsWithNoConfigurerBeanAndSaysSoOnce() {
        runnerWithoutConfigurer().run(ctx -> {
            assertThat(ctx)
                    .as("the ObjectProvider guard must let the context start with no configurer bean")
                    .hasNotFailed();
            assertThat(ctx).hasBean("rabbitListenerContainerFactory");
            assertThat(ctx).hasBean("mediaRabbitListenerContainerFactory");
        });

        // Assert on the MESSAGE TEXT, not the level: a level assertion would still pass if the
        // message were reworded into something an operator cannot act on.
        long warnings = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("SimpleRabbitListenerContainerFactoryConfigurer"))
                .filter(e -> e.getFormattedMessage().contains("RabbitAutoConfiguration"))
                .count();

        // VOID guard: capturing NOTHING at all is a misconfigured appender, not evidence that the
        // warning is absent. "Saw no WARN because we captured nothing" is not "the WARN is absent".
        assertThat(logAppender.list)
                .as("no log events captured at all — the appender is misconfigured, so this arm is "
                        + "VOID rather than passing")
                .isNotEmpty();
        assertThat(warnings)
                .as("the configurer-absent path must name BOTH the missing bean and the "
                        + "auto-configuration whose exclusion is the likely cause")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void configurerIsActuallyInvokedWhenThePropertyFamilyIsAvailable() {
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                mock(SimpleRabbitListenerContainerFactoryConfigurer.class);

        new ApplicationContextRunner()
                .withUserConfiguration(RabbitMQConfig.class)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(MessageConverter.class, Jackson2JsonMessageConverter::new)
                .withBean(SimpleRabbitListenerContainerFactoryConfigurer.class, () -> configurer)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ctx.getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);
                    ctx.getBean("mediaRabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);
                });

        // Deleting the configurer.configure(...) line turns this RED ("Wanted but not invoked"),
        // while the configurer-ABSENT test above still passes — which is what proves the two arms
        // are independent rather than two views of one assertion.
        verify(configurer, org.mockito.Mockito.times(2)).configure(any(), any());
    }

    @Test
    void recordWhichApplicationTestYamlThisClasspathResolves() {
        // A RECORDED OBSERVATION, deliberately not a criterion (27-04 AC-9 says so explicitly).
        // Two files are named application-test.yml — src/main/resources (which excludes
        // RabbitAutoConfiguration) and src/test/resources (which does not, and which carries the
        // H2 datasource). Gradle puts test resources first, so the main-resources exclusion is
        // SHADOWED. That shadowing is undocumented and one rename away from reversing, which is
        // precisely why the ObjectProvider guard exists rather than a hard parameter.
        java.net.URL resolved = getClass().getClassLoader().getResource("application-test.yml");
        System.out.println("[27-04 AC-9 observation] ClassLoader.getResource(\"application-test.yml\") -> " + resolved);

        assertThat(resolved)
                .as("this is an observation about the build, not an assertion about 27-04's change; "
                        + "it is asserted non-null only so a silent classpath change is visible")
                .isNotNull();
    }
}
