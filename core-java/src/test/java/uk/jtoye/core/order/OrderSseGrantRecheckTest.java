package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.Membership;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #281 / D-09 — the per-emit grant re-check: a user whose shop grant is revoked
 * mid-stream receives no further events on an ALREADY-OPEN SSE connection.
 *
 * <p>Constructs {@link OrderSseService} directly with a mocked
 * {@link ShopAccessService} — no Spring context, no Testcontainers — so this stays in
 * the fast (default) Gradle {@code test} task, mirroring
 * {@code OrderSseServiceTenantIsolationTest}. The off-thread, cache-MISS,
 * RLS-enforced half that a mock structurally CANNOT prove lives in
 * {@code OrderSseGrantRecheckIntegrationTest}.
 *
 * <p><strong>Why every security arm here is paired with a liveness arm.</strong> The
 * dominant risk in this change was never the security half — it was that a re-check
 * written the obvious way resolves "no grants" for EVERY subscriber (the exact shape
 * of forgetting the tenant pin on the {@code @RabbitListener} thread) and silently
 * kills the KDS for everyone, while passing every security assertion in the change
 * perfectly (T-28-14). A revoked-user test that goes green on its own is the tell,
 * not the reassurance. So each arm below is written so that the paired arm can fail
 * INDEPENDENTLY, and both directions were run:
 *
 * <ul>
 *   <li>with the re-check skipped entirely, {@link #securityArm_revokedSubscriberReceivesNothing}
 *       goes RED and the liveness arm stays green;</li>
 *   <li>with the re-check denying unconditionally,
 *       {@link #livenessArm_stillGrantedSubscriberStillReceives} goes RED and the
 *       security arm stays green.</li>
 * </ul>
 */
class OrderSseGrantRecheckTest {

    private final ShopAccessService shopAccessService = Mockito.mock(ShopAccessService.class);
    private final OrderSseService service = new OrderSseService(shopAccessService);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    /** Subscriber A — the one whose grant is revoked mid-stream. */
    private static final UUID USER_A = UUID.randomUUID();
    /** Subscriber B — the control: still granted, on the SAME broadcast. */
    private static final UUID USER_B = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------
    // The pair: revoked is blocked, still-granted is delivered — one broadcast
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("security arm — a subscriber whose shop grant was revoked mid-stream receives nothing")
    void securityArm_revokedSubscriberReceivesNothing() throws Exception {
        Spies spies = twoScopedSubscribersOnTheSameShop();

        // A's grant on SHOP is revoked; B's survives. Both connections stay open.
        revoke(USER_A);
        stillGranted(USER_B);

        service.broadcast(eventForShop(SHOP));

        verify(spies.a, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("liveness arm — a still-granted subscriber DOES receive the same broadcast")
    void livenessArm_stillGrantedSubscriberStillReceives() throws Exception {
        Spies spies = twoScopedSubscribersOnTheSameShop();

        // Identical world to the security arm above — deliberately, so the two arms
        // differ ONLY in which subscriber they assert about.
        revoke(USER_A);
        stillGranted(USER_B);

        service.broadcast(eventForShop(SHOP));

        verify(spies.b, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ---------------------------------------------------------------------
    // The GROUP_ADMIN path — both directions
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("group admin — a revoked tenant-wide GROUP_ADMIN row stops delivery on the open stream")
    void groupAdminArm_revokedTenantWideGrantStopsDelivery() throws Exception {
        // Subscribes as an unrestricted user whose status IS backed by a shop_staff
        // tenant-wide GROUP_ADMIN row, so the re-check can see it being taken away.
        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.of(USER_A));
        Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(true);
        Mockito.when(shopAccessService.resolveMembership(USER_A))
                .thenReturn(new Membership(true, false, Map.of()));

        TenantContext.set(TENANT);
        SseEmitter spy = subscribeAndSpy();
        TenantContext.clear();

        // The operator revokes the tenant-wide grant: no rows left at all.
        revoke(USER_A);

        service.broadcast(eventForShop(SHOP));

        verify(spy, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("group admin liveness — an admin NOT backed by shop_staff (realm admin) keeps receiving")
    void groupAdminLivenessArm_realmAdminIsNotDeniedByAShopStaffRecheck() throws Exception {
        // The realm-admin bridge and the day-one implicit admin are unrestricted WITHOUT
        // any shop_staff row — resolveMembership returns an empty membership for them even
        // when nothing has been revoked. A re-check that reads that emptiness as revocation
        // would kill the KDS for exactly the accounts that run it (T-28-14). This arm is the
        // one that fails if the re-check ever stops distinguishing "no row" from "row gone".
        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.of(USER_B));
        Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(true);
        Mockito.when(shopAccessService.resolveMembership(USER_B))
                .thenReturn(new Membership(false, false, Map.of()));

        TenantContext.set(TENANT);
        SseEmitter spy = subscribeAndSpy();
        TenantContext.clear();

        service.broadcast(eventForShop(SHOP));

        verify(spy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ---------------------------------------------------------------------
    // Controls that must SURVIVE the change, not be assumed to
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("deny-by-default — a null event shopId is still never delivered to a scoped subscriber")
    void nullEventShopId_isStillNeverDeliveredToAScopedSubscriber() throws Exception {
        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.of(USER_A));
        Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(false);
        Mockito.when(shopAccessService.grantedShopIds()).thenReturn(Set.of(SHOP));

        TenantContext.set(TENANT);
        SseEmitter spy = subscribeAndSpy();
        TenantContext.clear();

        // Fully granted — so if this arm goes red it is the null-shopId rule that broke,
        // not the re-check.
        stillGranted(USER_A);

        service.broadcast(new OrderStateChangeEvent(
                UUID.randomUUID(), TENANT, "ORD-LEGACY-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now()));

        verify(spy, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("fail-closed — a re-check that throws denies THAT emit only, never the whole loop")
    void recheckFailure_deniesOnlyThatEmitter() throws Exception {
        Spies spies = twoScopedSubscribersOnTheSameShop();

        Mockito.when(shopAccessService.resolveMembership(USER_A))
                .thenThrow(new IllegalStateException("cache deserialization blew up"));
        stillGranted(USER_B);

        service.broadcast(eventForShop(SHOP));

        verify(spies.a, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(spies.b, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("subscribe — refuses to attach an emitter whose owner cannot be identified")
    void subscribe_refusesAnUnidentifiableOwner() {
        // A machine-client token has a non-UUID sub, so it resolves to no vendor user.
        // Such an emitter could never be re-checked, so it is never attached.
        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.empty());
        TenantContext.set(TENANT);

        assertThrows(IllegalStateException.class, service::subscribe);
        assertEquals(0, totalRegisteredEmitters(), "no emitter may be registered by a refused subscribe");
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private record Spies(SseEmitter a, SseEmitter b) {
    }

    /**
     * Two SCOPED subscribers (not group admins), both granted on {@link #SHOP} at
     * subscribe time, both attached to the same tenant bucket. The world both the
     * security arm and the liveness arm run in, built identically for each.
     */
    private Spies twoScopedSubscribersOnTheSameShop() throws Exception {
        Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(false);
        Mockito.when(shopAccessService.grantedShopIds()).thenReturn(Set.of(SHOP));

        TenantContext.set(TENANT);
        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.of(USER_A));
        SseEmitter spyA = subscribeAndSpy();

        Mockito.when(shopAccessService.currentVendorUserId()).thenReturn(Optional.of(USER_B));
        SseEmitter spyB = subscribeAndSpy();
        TenantContext.clear();

        return new Spies(spyA, spyB);
    }

    /** The user's grant on {@link #SHOP} is gone: no tenant-wide row, no per-shop row. */
    private void revoke(UUID userId) {
        Mockito.when(shopAccessService.resolveMembership(userId))
                .thenReturn(new Membership(false, false, Map.of()));
    }

    /** The user still holds an explicit per-shop grant on {@link #SHOP}. */
    private void stillGranted(UUID userId) {
        Mockito.when(shopAccessService.resolveMembership(userId))
                .thenReturn(new Membership(false, false, Map.of(SHOP, ShopRole.STAFF)));
    }

    private OrderStateChangeEvent eventForShop(UUID shopId) {
        return new OrderStateChangeEvent(
                UUID.randomUUID(), TENANT, "ORD-RECHECK-1",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now(), shopId);
    }

    /**
     * Subscribe, then swap the live emitter for a Mockito spy so {@code send()} can be
     * verified without a real HTTP response. Reuses {@code OrderSseServiceTenantIsolationTest}'s
     * helper shape, which preserves the captured {@code ShopScope} object — so the spy
     * inherits the original's userId and grant snapshot.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private SseEmitter subscribeAndSpy() throws Exception {
        SseEmitter real = service.subscribe();
        SseEmitter spy = Mockito.spy(real);

        Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
        f.setAccessible(true);
        Map<UUID, Map> map = (Map<UUID, Map>) f.get(service);
        Map bucket = map.get(TENANT);
        Object scope = bucket.remove(real);
        bucket.put(spy, scope);
        return spy;
    }

    @SuppressWarnings("unchecked")
    private int totalRegisteredEmitters() {
        try {
            Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
            f.setAccessible(true);
            Map<UUID, Map<SseEmitter, ?>> map = (Map<UUID, Map<SseEmitter, ?>>) f.get(service);
            return map.values().stream().mapToInt(Map::size).sum();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
