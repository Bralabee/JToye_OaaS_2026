package uk.jtoye.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry AOP processing so {@code @Retryable} + {@code @Recover}
 * annotations on service beans are wired up — required by
 * {@link uk.jtoye.core.order.StockService#decrementForOrder} for the CQ-01
 * stock race fix (3 retries, 50ms backoff on
 * {@code ObjectOptimisticLockingFailureException}).
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
