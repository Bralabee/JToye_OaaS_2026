package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.Membership;
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
     * scoped user sees only events for shops in their grant set.
     *
     * <p><strong>Phase 28 (#281 / D-09) — the snapshot is no longer the whole
     * decision.</strong> It used to be, and this javadoc used to say that "a grant
     * revoked mid-stream takes effect on the next reconnect (≤ 5-min emitter
     * timeout)". That is no longer true, and was the whole of #281: the snapshot has
     * no temporal dimension, so an already-open connection kept delivering to a user
     * whose grant had been taken away. {@link #broadcast} now re-checks the
     * subscriber's CURRENT grant before EVERY emit, so from the next broadcast a
     * revoked user receives nothing. The socket itself may still linger to
     * {@link #SSE_TIMEOUT} (5 minutes) — but it delivers no events while it does.
     *
     * <p>The residual is a different, stated number: eviction of the
     * {@code shopMembership} cache is post-commit and node-local
     * ({@code ShopAccessService.evictMembershipAfterCommit}), so on a REPLICA that
     * did not serve the revoke, the re-check can read a cached allow until that
     * entry expires — bounded by the {@code shopMembership} TTL of <strong>5
     * minutes</strong> ({@code CacheConfig.java:97}). That is the cross-replica
     * revocation latency; it is not rounded away.
     *
     * @param groupAdmin  the subscriber was unrestricted at subscribe time (the full
     *                    {@code isGroupAdmin()} ladder: realm admin, a tenant-wide
     *                    GROUP_ADMIN grant, or the day-one implicit admin).
     * @param shopIds     the exact grant set, for a scoped subscriber only (empty and
     *                    unused when {@code groupAdmin}).
     * @param userId      WHO the subscriber is — the key the per-emit re-check
     *                    resolves. An emitter whose owner cannot be identified is
     *                    never attached (see {@link #subscribe()}), because a
     *                    connection this feature cannot police is one it cannot close.
     * @param grantBacked meaningful only when {@code groupAdmin}: the unrestricted
     *                    status was backed by a live {@code shop_staff} tenant-wide
     *                    GROUP_ADMIN row, i.e. by a fact a revoke can REMOVE and the
     *                    re-check can therefore SEE. False means the status came from
     *                    somewhere {@code shop_staff} does not record — the realm-admin
     *                    bridge, or the day-one implicit admin under strict-scoping OFF
     *                    — where re-checking {@code shop_staff} would deny a user whose
     *                    access nothing has actually revoked. Denying them would not be
     *                    "more secure"; it would be a dead KDS for the realm admins who
     *                    run it (T-28-14), so those subscribers are not re-checked and
     *                    the reason is written down rather than implied.
     */
    private record ShopScope(boolean groupAdmin, Set<UUID> shopIds, UUID userId, boolean grantBacked) {
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

        // #281 (D-09): an emitter is only attachable if we can say WHO owns it — the
        // per-emit re-check is keyed by that id, so an unidentifiable owner would create
        // a connection this feature structurally cannot police. Refuse it, in the same
        // fail-closed shape as the tenant-less refusal above. (In practice this rejects
        // exactly the non-UUID-subject machine-client token; a vendor user always has a
        // UUID sub, and Spring Security has already rejected the anonymous case with 401.)
        UUID userId = shopAccessService.currentVendorUserId().orElseThrow(() -> new IllegalStateException(
                "SSE subscribe attempted without an identifiable vendor user — refusing to attach an "
                        + "emitter whose grant could never be re-checked"));

        // §3-FLAG #2: capture the caller's shop scope now. GROUP_ADMIN → all shops;
        // else the exact grant set. Filtered live in broadcast() so a STAFF/SHOP_MANAGER
        // scoped to shop A never receives a shop-B order event on the KDS stream.
        boolean groupAdmin = shopAccessService.isGroupAdmin();
        Set<UUID> granted = groupAdmin ? Set.of() : Set.copyOf(shopAccessService.grantedShopIds());
        // Record WHY an unrestricted subscriber is unrestricted, so the re-check knows
        // whether it is looking at a fact a revoke can remove. A tenant-wide GROUP_ADMIN
        // shop_staff row is revocable and visible to resolveMembership; the realm-admin
        // bridge and the day-one implicit admin are neither. See ShopScope#grantBacked.
        boolean grantBacked = groupAdmin && shopAccessService.resolveMembership(userId).isGroupAdmin();
        ShopScope scope = new ShopScope(groupAdmin, granted, userId, grantBacked);

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

        // #281 (D-09): pin the tenant ONCE for the whole emit loop, BEFORE any grant
        // lookup. This method runs on OrderSseFanoutListener's @RabbitListener thread,
        // which carries no SecurityContext, no TenantContext and no tenant GUC — so an
        // unpinned resolveMembership would read shop_staff under FORCE RLS with no
        // tenant, resolve "no grants" for EVERY subscriber, and silently deliver nothing
        // to anyone (T-28-14: a dead KDS that passes every security assertion here).
        //
        // TenantContext is the DOMINANT control: ShopAccessService is @Transactional, and
        // TenantSetLocalAspect issues set_config('app.current_tenant_id', ?, true) from
        // TenantContext before the transactional call and before every repository call.
        // A break arm must therefore neutralise THIS set, not the aspect's set_config
        // (trap_tenant_pin_is_under_a_global_aspect).
        //
        // Save/restore rather than a bare clear(): broadcast() is also reachable from a
        // thread that already carries a tenant (tests, and any future in-process caller),
        // and clobbering a caller's context would be a bug introduced by a fix.
        UUID previousTenant = TenantContext.get().orElse(null);
        TenantContext.set(event.tenantId());
        try {
            for (var entry : bucket.entrySet()) {
                SseEmitter emitter = entry.getKey();
                ShopScope scope = entry.getValue();
                // §3-FLAG #2: grant-set filter — skip emitters not scoped to this event's shop.
                if (!scope.permits(event.shopId())) {
                    continue;
                }
                // #281 (D-09): and now the TEMPORAL half — the snapshot above says what the
                // subscriber could see when they connected; this says whether they still can.
                // Deliberately ANDed with the snapshot, never substituted for it: the re-check
                // can only ever REMOVE an emit, never add one, so no revocation-era widening
                // (a mid-stream promotion, the day-one implicit-admin rule) can hand a
                // subscriber an event their captured scope already excluded.
                if (!stillPermitted(scope, event.shopId())) {
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
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * #281 (D-09): does {@code scope}'s subscriber STILL hold the grant that admitted
     * this event, as of right now? Called once per emit, under the tenant pinned by
     * {@link #broadcast}.
     *
     * <p>Mirrors the shape of the snapshot rather than re-deriving policy — it asks only
     * whether the specific {@code shop_staff} fact that backed the subscription still
     * holds, and never re-applies a widening rule:
     * <ul>
     *   <li>an unrestricted subscriber backed by a tenant-wide GROUP_ADMIN row stays
     *       permitted only while {@link Membership#isGroupAdmin()} is still true;</li>
     *   <li>an unrestricted subscriber NOT backed by such a row (realm admin, day-one
     *       implicit admin) is not re-checked — {@code shop_staff} does not record their
     *       status, so its absence is not evidence of revocation. Revoking a realm admin
     *       is a token-layer change, bounded by {@link #SSE_TIMEOUT} (5 minutes) on an
     *       open stream;</li>
     *   <li>a scoped subscriber stays permitted only while an explicit per-shop grant on
     *       THIS shop survives.</li>
     * </ul>
     *
     * <p><strong>Fail-closed on a lookup failure.</strong> A resolve that throws (a DB
     * blip, a cache deserialization fault) denies THIS emitter for THIS event and nothing
     * else: it must not be allowed to escape and abort the loop for the other
     * subscribers, and it must not be read as an allow. The emitter is deliberately NOT
     * removed — a failed grant lookup says nothing about whether the client is alive, and
     * the next broadcast re-asks.
     */
    private boolean stillPermitted(ShopScope scope, UUID shopId) {
        try {
            Membership current = shopAccessService.resolveMembership(scope.userId());
            if (scope.groupAdmin()) {
                return !scope.grantBacked() || current.isGroupAdmin();
            }
            return shopId != null && current.perShopRole().containsKey(shopId);
        } catch (Exception e) {
            log.warn("SSE grant re-check failed for user {} shop {} — denying this emit (fail-closed): {}",
                    scope.userId(), shopId, e.toString());
            return false;
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
