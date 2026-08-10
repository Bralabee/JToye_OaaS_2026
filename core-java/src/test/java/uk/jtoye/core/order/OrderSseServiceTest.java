package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.Membership;
import uk.jtoye.core.security.access.ShopAccessService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
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

    /** The stable, still-granted subscriber every lifecycle test in this class runs as. */
    private static final UUID SUBSCRIBER = UUID.randomUUID();

    private OrderSseService orderSseService;

    @BeforeEach
    void setUp() {
        // Phase 23 (VSA-02 §3-FLAG #2): subscribe() now captures the caller's shop scope.
        // These lifecycle tests run as a GROUP_ADMIN so every emitter sees all events
        // (permits any shopId incl. the null shopId of the legacy 6-arg test events),
        // preserving the pre-scoping broadcast/prune behaviour.
        ShopAccessService shopAccessService = Mockito.mock(ShopAccessService.class);
        Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(true);
        // Phase 28 (#281 / D-09): subscribe() now refuses an emitter whose owner it cannot
        // identify, and broadcast() re-checks that owner's CURRENT grant before every emit.
        // These lifecycle tests are not about revocation, so the subscriber is a stable
        // tenant-wide GROUP_ADMIN throughout — identifiable, and still granted on every
        // re-check, which preserves the pre-#281 broadcast/prune behaviour under test.
        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.of(SUBSCRIBER));
        Mockito.when(shopAccessService.resolveMembership(SUBSCRIBER))
                .thenReturn(new Membership(true, false, Map.of()));
        orderSseService = new OrderSseService(shopAccessService);
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

    // --- Heartbeat (#92): keep-alive below the 60s ingress proxy-read-timeout ---

    @Test
    @DisplayName("heartbeat - no subscribers: no-op, no exception")
    void testHeartbeat_NoSubscribers() {
        assertDoesNotThrow(() -> orderSseService.sendHeartbeats());
    }

    @Test
    @DisplayName("heartbeat - live emitter survives and stays registered")
    void testHeartbeat_KeepsLiveEmitter() throws Exception {
        // An emitter not yet attached to an HTTP response buffers early sends
        // instead of failing, so this exercises the healthy-send path.
        orderSseService.subscribe();

        assertDoesNotThrow(() -> orderSseService.sendHeartbeats());
        assertEquals(1, totalRegisteredEmitters(), "healthy emitter must not be pruned by the heartbeat");
    }

    @Test
    @DisplayName("heartbeat - dead (completed) emitter is pruned instead of leaking until timeout")
    void testHeartbeat_PrunesDeadEmitter() throws Exception {
        SseEmitter emitter = orderSseService.subscribe();
        // complete() marks the emitter dead: subsequent send() throws IllegalStateException.
        emitter.complete();

        assertDoesNotThrow(() -> orderSseService.sendHeartbeats());
        assertEquals(0, totalRegisteredEmitters(), "dead emitter must be pruned on heartbeat send failure");
    }

    @Test
    @DisplayName("broadcast - dead (completed) emitter is pruned and does not abort delivery to the rest")
    void testBroadcast_PrunesDeadEmitterWithoutAbortingLoop() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        SseEmitter dead = orderSseService.subscribe();
        orderSseService.subscribe(); // healthy sibling under the same tenant
        dead.complete();

        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), tenantId, "ORD-DEAD-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());

        // Pre-#92 this threw IllegalStateException out of the AMQP listener (the
        // completed emitter was only guarded against IOException), starving the
        // healthy emitter and sending the delivery to retry/DLQ.
        assertDoesNotThrow(() -> orderSseService.broadcast(event));
        assertEquals(1, totalRegisteredEmitters(), "only the dead emitter should have been pruned");
    }

    @Test
    @DisplayName("heartbeat - scheduled every 25s by default (safely below the 60s proxy idle timeout)")
    void testHeartbeat_IsScheduled() throws Exception {
        Method method = OrderSseService.class.getMethod("sendHeartbeats");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "sendHeartbeats must be @Scheduled or no keep-alive ever fires");
        assertEquals("${jtoye.sse.heartbeat-interval-ms:25000}", scheduled.fixedRateString());
    }

    @SuppressWarnings("unchecked")
    private int totalRegisteredEmitters() throws Exception {
        Field field = OrderSseService.class.getDeclaredField("emittersByTenant");
        field.setAccessible(true);
        // Phase 23: each tenant bucket is now a Map<SseEmitter, ShopScope>, so count keys.
        Map<UUID, Map<SseEmitter, ?>> map = (Map<UUID, Map<SseEmitter, ?>>) field.get(orderSseService);
        return map.values().stream().mapToInt(Map::size).sum();
    }
}
