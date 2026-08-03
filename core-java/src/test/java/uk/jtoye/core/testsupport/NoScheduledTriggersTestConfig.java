package uk.jtoye.core.testsupport;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Makes every {@code @Scheduled} trigger INERT in a test's application context,
 * so a test that drives a scheduled method by hand owns the whole timeline
 * (issue #418).
 *
 * <h2>Why parking the interval is not enough</h2>
 *
 * <p>The suites here park their worker's interval at a day
 * ({@code registry.add("payment.outbox.flush-interval-ms", () -> "86400000")})
 * and treat that as "scheduling is off". It is not.
 * {@code @Scheduled(fixedDelayString = ...)} leaves {@code initialDelay} at its
 * default of <b>0</b>, so the <em>first</em> execution fires at context refresh
 * regardless of the delay; parking the interval suppresses only the second and
 * later runs. The task therefore starts on the {@code scheduling-N} thread at
 * the same moment the TestContext framework hands control to the first
 * {@code @BeforeEach}, and on a loaded runner it is still running when the test
 * body starts writing rows.
 *
 * <p>Measured on this repo (2026-08-03), a full context with the intervals
 * parked at 86400000 registers <b>10</b> live scheduled tasks, and
 * {@code PaymentEventOutboxFlusher.flushPending} demonstrably executes. Absence
 * of a scheduled task from the CI logs is not absence of execution — a pass
 * over an empty tenant list logs nothing at all.
 *
 * <h2>What this does instead</h2>
 *
 * <p>Removes the {@code internalScheduledAnnotationProcessor} bean definition,
 * which is the bean {@code @EnableScheduling} registers to find and schedule
 * {@code @Scheduled} methods. With it gone nothing is ever scheduled: no
 * startup run, no periodic run, for any worker in the context — not just the
 * one the test happens to be thinking about. The workers remain ordinary beans
 * and their methods remain directly callable, which is exactly how these tests
 * already drive them.
 *
 * <p>Spring Boot's {@code TaskSchedulingAutoConfiguration} is conditional on
 * that same bean NAME, but its condition is evaluated during configuration-class
 * parsing, which runs before a plain {@link BeanFactoryPostProcessor}. So the
 * {@code taskScheduler} bean may still be created; it simply never receives a
 * task. That is harmless and deliberately not fought.
 *
 * <p>Usage: {@code @Import(NoScheduledTriggersTestConfig.class)} on the test
 * class. Spring Boot's {@code ImportsContextCustomizerFactory} honours
 * {@code @Import} directly on a {@code @SpringBootTest} class and folds it into
 * the context cache key.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoScheduledTriggersTestConfig {

    /**
     * {@code static} so the enclosing configuration class is not instantiated
     * early — the standard shape for a {@link BeanFactoryPostProcessor} declared
     * as a {@code @Bean}.
     */
    @Bean
    static BeanFactoryPostProcessor removeScheduledAnnotationProcessor() {
        return beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(
                            TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                registry.removeBeanDefinition(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            }
        };
    }
}
