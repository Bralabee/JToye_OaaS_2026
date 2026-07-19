package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderSseService {
    private static final Logger log = LoggerFactory.getLogger(OrderSseService.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes — clients auto-reconnect (#92)

    /** SSE comment payload sent as keep-alive; comments are ignored by EventSource parsers. */
    static final String HEARTBEAT_COMMENT = "keep-alive";

    private final ShopAccessService shopAccessService;

    public OrderSseService(ShopAccessService shopAccessService) {
        this.shopAccessService = shopAccessService;
    }

    /**
     * A subscriber's shop scope, snapshotted at {@link #subscribe()} time
     * (Phase 23, VSA-02 §3-FLAG #2). A GROUP_ADMIN sees every shop's events; a
     * scoped user sees only events for shops in their grant set. The snapshot is
     * per-connection — a grant revoked mid-stream takes effect on the next
     * reconnect (≤ 5-min emitter timeout), while the membership cache handles
     * immediate revocation on the request path (D-05).
     */
    private record ShopScope(boolean groupAdmin, Set<UUID> shopIds) {
        boolean permits(UUID shopId) {
            // A null event shopId (legacy/unknown) is only ever delivered to a
            // GROUP_ADMIN — never leaked to a scoped subscriber (deny-by-default).
            return groupAdmin || (shopId != null && shopIds.contains(shopId));
        }
    }

    /**
     * Per-tenant emitter registries. Outer map is mutation-safe (CHM); inner maps
     * are CHM so concurrent add/remove during broadcast iteration is safe. Each
     * emitter is associated with its captured {@link ShopScope}.
     * AUDIT-W0-01: previously a flat unbounded shared list that leaked every tenant's
     * events to every subscriber — replaced with per-tenant routing; Phase 23 adds
     * per-shop grant filtering within a tenant.
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<SseEmitter, ShopScope>> emittersByTenant =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe() {
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId == null) {
            throw new IllegalStateException(
                    "SSE subscribe attempted without TenantContext — refusing to attach a tenant-less emitter");
        }

        // §3-FLAG #2: capture the caller's shop scope now. GROUP_ADMIN → all shops;
        // else the exact grant set. Filtered live in broadcast() so a STAFF/SHOP_MANAGER
        // scoped to shop A never receives a shop-B order event on the KDS stream.
        boolean groupAdmin = shopAccessService.isGroupAdmin();
        Set<UUID> granted = groupAdmin ? Set.of() : Set.copyOf(shopAccessService.grantedShopIds());
        ShopScope scope = new ShopScope(groupAdmin, granted);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // Atomic insert: add the emitter inside the map mutation lambda so concurrent
        // cleanup cannot remove the bucket between our lookup and our add (which would
        // orphan the new emitter — broadcasts would never reach this client).
        ConcurrentHashMap<SseEmitter, ShopScope> bucket = emittersByTenant.compute(tenantId, (k, existing) -> {
            ConcurrentHashMap<SseEmitter, ShopScope> map = (existing != null) ? existing : new ConcurrentHashMap<>();
            map.put(emitter, scope);
            return map;
        });

        Runnable cleanup = () -> removeEmitter(tenantId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.debug("SSE client subscribed for tenant {} (groupAdmin={}, grantedShops={}), tenant-bucket size: {}",
                tenantId, groupAdmin, granted.size(), bucket.size());
        return emitter;
    }

    public void broadcast(OrderStateChangeEvent event) {
        ConcurrentHashMap<SseEmitter, ShopScope> bucket = emittersByTenant.get(event.tenantId());
        if (bucket == null || bucket.isEmpty()) {
            log.debug("No SSE subscribers for tenant {} — skipping broadcast", event.tenantId());
            return;
        }
        log.debug("Broadcasting order state change to (≤{}) SSE clients for tenant {}",
                bucket.size(), event.tenantId());
        for (var entry : bucket.entrySet()) {
            SseEmitter emitter = entry.getKey();
            ShopScope scope = entry.getValue();
            // §3-FLAG #2: grant-set filter — skip emitters not scoped to this event's shop.
            if (!scope.permits(event.shopId())) {
                continue;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("order-state-change")
                        .data(event));
            } catch (Exception e) {
                // Catch Exception, not just IOException: a completed/timed-out emitter
                // throws IllegalStateException, which previously aborted the whole loop
                // (remaining emitters never got the event and the AMQP delivery was
                // retried/DLQ'd). Any send failure just means THIS client is gone.
                removeEmitter(event.tenantId(), emitter);
            }
        }
    }

    /**
     * Keep-alive heartbeat (#92): sends an SSE comment to every connected emitter.
     *
     * <p>The default nginx-ingress {@code proxy-read-timeout} is 60s; an idle SSE
     * connection with no traffic gets severed by the proxy without either end
     * noticing promptly. A 25s comment keeps every hop's idle clock reset (safely
     * below 60s even if a beat is skipped by scheduler jitter) and doubles as a
     * dead-connection probe — send failures prune the emitter immediately instead
     * of leaking it until the 5-minute emitter timeout.</p>
     *
     * <p>Heartbeats carry no order data (an SSE comment), so they are sent to every
     * emitter regardless of shop scope — no leak.</p>
     */
    @Scheduled(fixedRateString = "${jtoye.sse.heartbeat-interval-ms:25000}")
    public void sendHeartbeats() {
        emittersByTenant.forEach((tenantId, bucket) -> {
            for (SseEmitter emitter : bucket.keySet()) {
                try {
                    emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                } catch (Exception e) {
                    removeEmitter(tenantId, emitter);
                }
            }
        });
    }

    /**
     * Atomic remove: drop the emitter and (if the bucket is now empty) the bucket
     * itself, all under the same map mutation. Free the bucket entry once empty so
     * long-lived JVMs don't leak per-tenant maps.
     */
    private void removeEmitter(UUID tenantId, SseEmitter emitter) {
        emittersByTenant.computeIfPresent(tenantId, (k, v) -> {
            v.remove(emitter);
            return v.isEmpty() ? null : v;
        });
    }
}
