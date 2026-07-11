package uk.jtoye.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the per-instance SSE fan-out queue declaration (#92 / P2-1).
 *
 * <p>These properties ARE the fix: uniqueness per instantiation is what turns the
 * single competing-consumer delivery into a true fan-out at N replicas, and
 * exclusive/auto-delete is what stops crashed replicas from leaking queues (and
 * stale bindings) on the broker.</p>
 */
class RabbitMQConfigFanoutQueueTest {

    private final RabbitMQConfig config = new RabbitMQConfig();

    @Test
    @DisplayName("fan-out queue - unique name per instance (each replica gets its own copy of every event)")
    void fanoutQueueNameIsUniquePerInstance() {
        AnonymousQueue replicaA = config.orderEventsFanoutQueue();
        AnonymousQueue replicaB = config.orderEventsFanoutQueue();

        assertNotEquals(replicaA.getName(), replicaB.getName(),
                "two replicas must never share the fan-out queue — that IS the competing-consumer bug");
        assertTrue(replicaA.getName().startsWith(RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX));
        assertTrue(replicaB.getName().startsWith(RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX));
    }

    @Test
    @DisplayName("fan-out queue - exclusive, auto-delete, non-durable (no broker litter after pod restarts)")
    void fanoutQueueIsEphemeral() {
        AnonymousQueue queue = config.orderEventsFanoutQueue();

        assertTrue(queue.isExclusive(), "exclusive: only the declaring connection may consume");
        assertTrue(queue.isAutoDelete(), "auto-delete: broker drops the queue when the replica dies");
        assertFalse(queue.isDurable(), "non-durable: buffered UI pushes are worthless after a restart");
    }

    @Test
    @DisplayName("durable queue - unchanged: shared name, durable, DLX-backed (competing-consumer side effects)")
    void durableQueueSemanticsPreserved() {
        Queue durable = config.orderEventsQueue();

        assertEquals(RabbitMQConfig.ORDER_EVENTS_QUEUE, durable.getName());
        assertTrue(durable.isDurable());
        assertFalse(durable.isExclusive());
        assertFalse(durable.isAutoDelete());
        assertEquals(RabbitMQConfig.DLX_EXCHANGE, durable.getArguments().get("x-dead-letter-exchange"),
                "email/metrics deliveries must keep their retry -> DLQ path");
    }
}
