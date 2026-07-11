package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Fan-out consumer for real-time SSE pushes (#92 / P2-1).
 *
 * <p>Listens on this instance's own {@code AnonymousQueue}
 * ({@code RabbitMQConfig#orderEventsFanoutQueue()}), which is bound to the
 * {@code order.events} topic exchange alongside the durable competing-consumer
 * queue. Because every replica declares its own copy of that queue, every
 * replica receives every order event — the property SSE needs, since emitters
 * are held per-JVM and a dashboard may be attached to any replica behind the
 * load balancer.</p>
 *
 * <p>Deliberately does <em>not</em> share the durable queue's listener: side
 * effects that must run exactly once fleet-wide (customer email, business
 * metrics, the KDS publish to the shared STOMP relay) stay in
 * {@link OrderStateChangeListener}. This listener is DB-free and touches only
 * the in-JVM emitter registry; tenant scoping is enforced inside
 * {@link OrderSseService#broadcast} which routes by {@code event.tenantId()}.</p>
 */
@Component
public class OrderSseFanoutListener {
    private static final Logger log = LoggerFactory.getLogger(OrderSseFanoutListener.class);

    private final OrderSseService sseService;

    public OrderSseFanoutListener(OrderSseService sseService) {
        this.sseService = sseService;
    }

    @RabbitListener(queues = "#{orderEventsFanoutQueue.name}")
    public void onOrderStateChange(OrderStateChangeEvent event) {
        log.debug("Fan-out order event received on this instance: order={} tenant={} {} -> {}",
                event.orderNumber(), event.tenantId(), event.previousStatus(), event.newStatus());
        sseService.broadcast(event);
    }
}
