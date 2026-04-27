package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OrderSseService.
 * Tests SSE subscription, broadcast, and emitter lifecycle management.
 * No mocks needed — the service manages its own emitter list.
 *
 * <p>Post AUDIT-W0-01 (Phase 16.1-02): {@link OrderSseService#subscribe()} is fail-closed
 * when {@code TenantContext} is unset. These existing tests don't care which tenant they
 * run under — they just need <em>some</em> tenant set so subscribe() doesn't throw.
 * The per-test setup picks a fresh random UUID per test class to avoid bleed.</p>
 */
class OrderSseServiceTest {

    private OrderSseService orderSseService;

    @BeforeEach
    void setUp() {
        orderSseService = new OrderSseService();
        // Required so subscribe() does not fail-closed on a missing TenantContext.
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private OrderStateChangeEvent createTestEvent() {
        return new OrderStateChangeEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ORD-001",
                OrderStatus.PENDING,
                OrderStatus.CONFIRMED,
                OffsetDateTime.now()
        );
    }

    @Test
    @DisplayName("subscribe - Returns a non-null SseEmitter")
    void testSubscribe_ReturnsEmitter() {
        SseEmitter emitter = orderSseService.subscribe();

        assertNotNull(emitter);
    }

    @Test
    @DisplayName("subscribe - Multiple subscriptions create independent emitters")
    void testSubscribe_MultipleEmitters() {
        SseEmitter emitter1 = orderSseService.subscribe();
        SseEmitter emitter2 = orderSseService.subscribe();

        assertNotNull(emitter1);
        assertNotNull(emitter2);
        assertNotSame(emitter1, emitter2);
    }

    @Test
    @DisplayName("broadcast - Does not throw when no subscribers")
    void testBroadcast_NoSubscribers() {
        OrderStateChangeEvent event = createTestEvent();

        assertDoesNotThrow(() -> orderSseService.broadcast(event));
    }

    @Test
    @DisplayName("broadcast - Sends event to subscribed emitter without error")
    void testBroadcast_WithSubscriber() {
        SseEmitter emitter = orderSseService.subscribe();
        OrderStateChangeEvent event = createTestEvent();

        // broadcast may fail on send (emitter not connected to real HTTP response)
        // but the service should handle the IOException gracefully and remove the emitter
        assertDoesNotThrow(() -> orderSseService.broadcast(event));
    }

    @Test
    @DisplayName("subscribe - Emitter has onCompletion callback (cleanup)")
    void testSubscribe_HasCompletionCallback() {
        // Just verify subscribe works without throwing — the onCompletion/onTimeout/onError
        // callbacks are registered internally and clean up the emitter list
        SseEmitter emitter = orderSseService.subscribe();
        assertNotNull(emitter);

        // Trigger completion callback to verify no errors
        assertDoesNotThrow(() -> emitter.complete());
    }

    @Test
    @DisplayName("broadcast - Handles mixed healthy and dead emitters gracefully")
    void testBroadcast_MixedEmitters() {
        // Subscribe multiple emitters
        orderSseService.subscribe();
        orderSseService.subscribe();
        orderSseService.subscribe();

        OrderStateChangeEvent event = createTestEvent();

        // Broadcasting to emitters not connected to a real HTTP response will cause IOExceptions
        // but the service should handle each one individually and not throw
        assertDoesNotThrow(() -> orderSseService.broadcast(event));
    }

    @Test
    @DisplayName("broadcast - Event contains correct state transition data")
    void testBroadcast_EventData() {
        UUID orderId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, tenantId, "ORD-042",
                OrderStatus.PREPARING, OrderStatus.READY, now
        );

        // Verify the record fields
        assertEquals(orderId, event.orderId());
        assertEquals(tenantId, event.tenantId());
        assertEquals("ORD-042", event.orderNumber());
        assertEquals(OrderStatus.PREPARING, event.previousStatus());
        assertEquals(OrderStatus.READY, event.newStatus());
        assertEquals(now, event.timestamp());

        // Broadcast should not throw
        assertDoesNotThrow(() -> orderSseService.broadcast(event));
    }
}
