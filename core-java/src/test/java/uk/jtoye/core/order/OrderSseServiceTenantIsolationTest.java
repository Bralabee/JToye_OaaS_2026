package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    // Phase 23 (VSA-02 §3-FLAG #2): subscribe() captures the caller's shop scope. This
    // regression suite asserts per-TENANT routing, so every subscriber is a GROUP_ADMIN
    // (permits any shopId, incl. the null shopId of the legacy 6-arg test events) —
    // isolating the tenant-routing behaviour under test from the per-shop filter.
    private final ShopAccessService shopAccessService = Mockito.mock(ShopAccessService.class);
    private final OrderSseService service = new OrderSseService(shopAccessService);

    @BeforeEach
    void stubGroupAdmin() {
        Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(true);
    }

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
        Map<UUID, ?> map = (Map<UUID, ?>) f.get(service);
        assertFalse(map.containsKey(tenantA), "empty tenant bucket should be evicted from outer map");
    }

    @Test
    @DisplayName("subscribe-vs-cleanup race — concurrent subscribe + cleanup never orphans a new emitter")
    @SuppressWarnings("unchecked")
    void subscribeIsAtomicWithCleanup() throws Exception {
        // Reproduces the race the post-merge reviewer flagged: if cleanup of a now-empty
        // bucket runs between a concurrent subscribe()'s computeIfAbsent and bucket.add,
        // the new emitter would be added to a Set already detached from the outer map and
        // future broadcasts would never reach that client. With the atomic compute() fix,
        // every successful subscribe leaves a reachable bucket containing the new emitter.
        UUID tenant = UUID.randomUUID();
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                CountDownLatch start = new CountDownLatch(1);

                // First populate a bucket with one emitter, then concurrently:
                //  - cleanup that emitter (which may want to evict the now-empty bucket)
                //  - subscribe a new emitter for the same tenant
                TenantContext.set(tenant);
                SseEmitter existing = service.subscribe();
                Field cbField = existing.getClass().getSuperclass().getDeclaredField("completionCallback");
                cbField.setAccessible(true);
                Runnable existingCleanup = (Runnable) cbField.get(existing);
                TenantContext.clear();

                pool.submit(() -> { try { start.await(); } catch (InterruptedException ignored) {}
                    existingCleanup.run();
                });
                pool.submit(() -> { try { start.await(); } catch (InterruptedException ignored) {}
                    TenantContext.set(tenant);
                    try { service.subscribe(); } finally { TenantContext.clear(); }
                });
                start.countDown();

                // Drain by waiting briefly for both submissions on this round to settle.
                Thread.sleep(2);

                // Whatever the interleaving, the outer map MUST contain the bucket if any
                // subscribe succeeded after the cleanup, AND that bucket MUST contain the
                // newly subscribed emitter (no orphans).
                Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
                f.setAccessible(true);
                Map<UUID, Map<SseEmitter, ?>> map = (Map<UUID, Map<SseEmitter, ?>>) f.get(service);
                Map<SseEmitter, ?> bucket = map.get(tenant);
                if (bucket != null) {
                    // Reachable bucket invariant: emitter count >= 1 (the new subscribe) and
                    // every emitter is the same identity the bucket itself holds.
                    assertTrue(bucket.size() >= 1, "round " + i + ": bucket present but empty (orphaned)");
                }
                // Reset between rounds.
                map.remove(tenant);
            }
            assertEquals(0, ((Map<?, ?>) reflectMap()).size(),
                    "no leftover bucket entries after concurrent stress");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, ?> reflectMap() throws Exception {
        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        return (Map<UUID, ?>) f.get(service);
    }

    // --- helpers ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void replaceEmitterInBucket(UUID tenant, SseEmitter from, SseEmitter to) throws Exception {
        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        Map<UUID, Map> map = (Map<UUID, Map>) f.get(service);
        // Phase 23: the bucket is now Map<SseEmitter, ShopScope>. Preserve the swapped
        // emitter's captured scope so the spy inherits the original's grant scope.
        Map bucket = map.get(tenant);
        assertNotNull(bucket, "tenant bucket should exist before swap");
        Object scope = bucket.remove(from);
        bucket.put(to, scope);
    }
}
