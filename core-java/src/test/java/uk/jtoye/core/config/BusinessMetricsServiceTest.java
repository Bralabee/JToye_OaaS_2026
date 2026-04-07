package uk.jtoye.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BusinessMetricsServiceTest {

    private BusinessMetricsService metricsService;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new BusinessMetricsService(registry);
    }

    @Test
    @DisplayName("recordOrderCreated increments counter")
    void recordOrderCreated() {
        metricsService.recordOrderCreated();
        metricsService.recordOrderCreated();

        Counter counter = registry.find("jtoye.orders.created").counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }

    @Test
    @DisplayName("recordOrderCompleted increments counter and revenue")
    void recordOrderCompleted() {
        metricsService.recordOrderCompleted(1599L); // £15.99

        Counter completed = registry.find("jtoye.orders.completed").counter();
        Counter revenue = registry.find("jtoye.revenue.pennies").counter();
        assertNotNull(completed);
        assertNotNull(revenue);
        assertEquals(1.0, completed.count());
        assertEquals(1599.0, revenue.count());
    }

    @Test
    @DisplayName("recordOrderCancelled increments counter")
    void recordOrderCancelled() {
        metricsService.recordOrderCancelled();

        Counter counter = registry.find("jtoye.orders.cancelled").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    @DisplayName("recordPaymentFailure increments counter")
    void recordPaymentFailure() {
        metricsService.recordPaymentFailure();

        Counter counter = registry.find("jtoye.payments.failed").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    @DisplayName("fulfillment timer records duration")
    void fulfillmentTimer() {
        Timer.Sample sample = metricsService.startFulfillmentTimer();
        metricsService.recordFulfillmentTime(sample);

        Timer timer = registry.find("jtoye.orders.fulfillment").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }
}
