package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for AUDIT-W0-01 (cross-tenant SSE leak).
 *
 * <p>Constructs {@link OrderSseService} directly — no Spring context, no Testcontainers —
 * because the service has no DB or Spring dependency apart from its {@code @Service}
 * annotation. This keeps the regression suite in the fast (default) Gradle test task.</p>
 */
class OrderSseServiceTenantIsolationTest {

    private final OrderSseService service = new OrderSseService();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("subscribe — refuses to attach an emitter when TenantContext is unset")
    void subscribeRequiresTenant() {
        TenantContext.clear();
        assertThrows(IllegalStateException.class, service::subscribe);
    }

    @Test
    @DisplayName("broadcast — only delivers to emitters of the event's tenant")
    void broadcastIsTenantScoped() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Subscribe under tenant A; reach into the bucket and replace the live emitter
        // with a Mockito.spy so we can verify send() count without a real HTTP response.
        TenantContext.set(tenantA);
        SseEmitter realA = service.subscribe();
        SseEmitter spyA = Mockito.spy(realA);
        replaceEmitterInBucket(tenantA, realA, spyA);

        TenantContext.set(tenantB);
        SseEmitter realB = service.subscribe();
        SseEmitter spyB = Mockito.spy(realB);
        replaceEmitterInBucket(tenantB, realB, spyB);
        TenantContext.clear();

        OrderStateChangeEvent eventForA = new OrderStateChangeEvent(
                UUID.randomUUID(), tenantA, "ORD-A-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
        service.broadcast(eventForA);

        verify(spyA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(spyB, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("broadcast — no-op when no subscribers exist for the event's tenant")
    void broadcastNoOpForUnknownTenant() {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(tenantA);
        service.subscribe();
        TenantContext.clear();

        OrderStateChangeEvent eventForOther = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-X-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
        assertDoesNotThrow(() -> service.broadcast(eventForOther));
    }

    @Test
    @DisplayName("cleanup — completing the only emitter for a tenant removes the bucket")
    @SuppressWarnings("unchecked")
    void cleanupRemovesEmptyBucket() throws Exception {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(tenantA);
        SseEmitter emitter = service.subscribe();
        TenantContext.clear();

        // SseEmitter#complete() in a unit test does not dispatch the registered onCompletion
        // callbacks because no Handler is attached (Spring wires the Handler from the
        // response writer). To exercise the cleanup path deterministically we reach into
        // ResponseBodyEmitter#completionCallback (a Runnable that runs all registered
        // delegates) and invoke it directly. This is the same Runnable Spring would invoke
        // once the response is fully flushed in production.
        Field cbField = emitter.getClass().getSuperclass().getDeclaredField("completionCallback");
        cbField.setAccessible(true);
        Runnable completionCallback = (Runnable) cbField.get(emitter);
        completionCallback.run();

        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        Map<UUID, Set<SseEmitter>> map = (Map<UUID, Set<SseEmitter>>) f.get(service);
        assertFalse(map.containsKey(tenantA), "empty tenant bucket should be evicted from outer map");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private void replaceEmitterInBucket(UUID tenant, SseEmitter from, SseEmitter to) throws Exception {
        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        Map<UUID, Set<SseEmitter>> map = (Map<UUID, Set<SseEmitter>>) f.get(service);
        Set<SseEmitter> bucket = map.get(tenant);
        assertNotNull(bucket, "tenant bucket should exist before swap");
        bucket.remove(from);
        bucket.add(to);
    }
}
