package uk.jtoye.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Custom business metrics tracked via Micrometer/Prometheus.
 * Exposed at /actuator/prometheus for Grafana dashboards.
 */
@Service
public class BusinessMetricsService {

    private final Counter ordersCreated;
    private final Counter ordersCompleted;
    private final Counter ordersCancelled;
    private final Counter revenueTotal;
    private final Counter paymentFailures;
    private final Timer orderFulfillmentTimer;

    public BusinessMetricsService(MeterRegistry registry) {
        this.ordersCreated = Counter.builder("jtoye.orders.created")
                .description("Total orders created")
                .register(registry);
        this.ordersCompleted = Counter.builder("jtoye.orders.completed")
                .description("Total orders completed")
                .register(registry);
        this.ordersCancelled = Counter.builder("jtoye.orders.cancelled")
                .description("Total orders cancelled")
                .register(registry);
        this.revenueTotal = Counter.builder("jtoye.revenue.pennies")
                .description("Total revenue in pennies")
                .register(registry);
        this.paymentFailures = Counter.builder("jtoye.payments.failed")
                .description("Total payment failures")
                .register(registry);
        this.orderFulfillmentTimer = Timer.builder("jtoye.orders.fulfillment")
                .description("Order fulfillment time from creation to completion")
                .register(registry);
    }

    public void recordOrderCreated() {
        ordersCreated.increment();
    }

    public void recordOrderCompleted(long totalPennies) {
        ordersCompleted.increment();
        revenueTotal.increment(totalPennies);
    }

    public void recordOrderCancelled() {
        ordersCancelled.increment();
    }

    public void recordPaymentFailure() {
        paymentFailures.increment();
    }

    public Timer.Sample startFulfillmentTimer() {
        return Timer.start();
    }

    public void recordFulfillmentTime(Timer.Sample sample) {
        sample.stop(orderFulfillmentTimer);
    }
}
