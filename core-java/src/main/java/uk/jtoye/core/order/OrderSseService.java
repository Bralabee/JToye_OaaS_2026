package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderSseService {
    private static final Logger log = LoggerFactory.getLogger(OrderSseService.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

    /**
     * Per-tenant emitter sets. Outer map is mutation-safe (CHM); inner sets are
     * {@code newSetFromMap(CHM)} to allow concurrent add/remove during broadcast iteration.
     * AUDIT-W0-01: previously a flat unbounded shared list that leaked every tenant's
     * events to every subscriber — replaced here with per-tenant routing.
     */
    private final ConcurrentHashMap<UUID, Set<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();

    public SseEmitter subscribe() {
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId == null) {
            throw new IllegalStateException(
                    "SSE subscribe attempted without TenantContext — refusing to attach a tenant-less emitter");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // Atomic insert: add the emitter inside the map mutation lambda so concurrent
        // cleanup cannot remove the bucket between our lookup and our add (which would
        // orphan the new emitter — broadcasts would never reach this client).
        Set<SseEmitter> bucket = emittersByTenant.compute(tenantId, (k, existing) -> {
            Set<SseEmitter> set = (existing != null)
                    ? existing
                    : Collections.newSetFromMap(new ConcurrentHashMap<>());
            set.add(emitter);
            return set;
        });

        Runnable cleanup = () -> {
            // Atomic remove: drop the emitter and (if the bucket is now empty) the bucket
            // itself, all under the same map mutation. Free the bucket entry once empty so
            // long-lived JVMs don't leak per-tenant maps.
            emittersByTenant.computeIfPresent(tenantId, (k, v) -> {
                v.remove(emitter);
                return v.isEmpty() ? null : v;
            });
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.debug("SSE client subscribed for tenant {}, tenant-bucket size: {}", tenantId, bucket.size());
        return emitter;
    }

    public void broadcast(OrderStateChangeEvent event) {
        Set<SseEmitter> bucket = emittersByTenant.get(event.tenantId());
        if (bucket == null || bucket.isEmpty()) {
            log.debug("No SSE subscribers for tenant {} — skipping broadcast", event.tenantId());
            return;
        }
        log.debug("Broadcasting order state change to {} SSE clients for tenant {}",
                bucket.size(), event.tenantId());
        for (SseEmitter emitter : bucket) {
            try {
                emitter.send(SseEmitter.event()
                        .name("order-state-change")
                        .data(event));
            } catch (IOException e) {
                bucket.remove(emitter);
            }
        }
    }
}
