---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 11
subsystem: auth
tags: [rbac, vendor-scoped-access, stomp, websocket, kds, fail-closed, rls, testcontainers, CR-02]

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-02)
    provides: ShopAccessService enforcement seam + per-user membership cache + strict-scoping switch
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-08)
    provides: Fail-closed identity discipline (an absent Authentication is the internal-caller bypass — the reason STOMP identity must be explicit)
provides:
  - Explicit-identity ShopAccessService.canAccessShop(tenantId, userId, realmAdmin, shopId) — a no-ambient-state, no-write shop-read decision for non-request threads
  - Shared isGroupAdminForUser decision helper so the HTTP (isGroupAdmin) and STOMP (canAccessShop) boundaries provably cannot drift
  - TenantChannelInterceptor grant-checks the kitchen topic's {shopId} segment at SUBSCRIBE (CR-02 closed on the primary KDS transport)
affects: [23-13, 23-14, 24-image-architecture, 25-mutating-mcp-tools]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Explicit-identity authorization variant for non-request threads: identity arrives as parameters, never from SecurityContextHolder (which on a STOMP thread would take the internal-caller bypass and fail OPEN)"
    - "One shared private decision ladder shared by the HTTP and STOMP gates so they cannot drift"
    - "finally-block TenantContext cleanup inside the interceptor's own validate method — the inbound channel thread is pooled and afterMessageHandled does not run for a rejected preSend"
    - "Falsifiability gate: cases demonstrated RED against a gate-disabled interceptor before the fix was accepted"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/websocket/TenantChannelInterceptorShopGateIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java
    - core-java/src/test/java/uk/jtoye/core/websocket/TenantChannelInterceptorTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessEnforcementIntegrationTest.java

key-decisions:
  - "STOMP subscriber identity resolved explicitly from accessor.getUser() (the CONNECT-set session principal), never SecurityContextHolder — the ambient path would hit the retained auth==null internal bypass (23-08) and fail OPEN"
  - "isGroupAdmin() and canAccessShop() funnel through one private isGroupAdminForUser(userId, realmAdmin) so the HTTP and STOMP decision ladders cannot silently diverge"
  - "canAccessShop performs NO writes (no onRequest / no JIT / no directory upsert) — a SUBSCRIBE is a read; and asserts the pinned tenant equals the requested tenant so an unpinned RLS GUC cannot return zero rows and fail OPEN under strict-scoping OFF"
  - "realm_access.roles is re-parsed inside the interceptor (the one place, documented) because the CONNECT path builds the principal with no authority conversion"
  - "TenantContext pinned around the read and cleared in a finally inside validateSubscription — pooled thread hazard (T-23-11-04)"

patterns-established:
  - "Explicit-identity shop-read gate for non-request transports"
  - "Shared decision helper across two enforcement boundaries"

requirements-completed: [VSA-02]

# Metrics
duration: 20min
completed: 2026-07-20
---

# Phase 23 Plan 11: Shop-Gate the KDS STOMP Transport (CR-02) Summary

**Closed CR-02 — the real KDS live channel (STOMP `/topic/kitchen/{tenant}/{shopId}`) was not shop-gated, so a STAFF user granted only shop A could `SUBSCRIBE` to shop B's kitchen feed within its own tenant and receive every live order state change. The interceptor now grant-checks the shop segment at SUBSCRIBE against the subscriber's explicit identity, using the same decision ladder as the HTTP boundary, without breaking day-one ungranted KDS clients.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-07-20T22:19:43Z
- **Completed:** 2026-07-20T22:39:31Z
- **Tasks:** 3
- **Files modified:** 5 (4 modified, 1 created)

## Accomplishments

- **CR-02 closed on the primary transport.** `TenantChannelInterceptor.validateSubscription` now, AFTER the existing tenant-wall check passes, grant-checks the kitchen topic's `parts[4]` shop segment. The KDS live channel enforces the same shop boundary the SSE fan-out already did (23-03) — one level down from the tenant leak (AUDIT-W0-01) that was fixed for tenants but left open for shops.
- **Explicit-identity gate that cannot fail open.** New `ShopAccessService.canAccessShop(tenantId, userId, realmAdmin, shopId)` decides shop-read access from parameters only — never `SecurityContextHolder`, which on the STOMP thread (identity lives on the session principal, not the security context) would take the retained `auth == null` internal-caller bypass from 23-08 and return `true`. The interceptor resolves the subject from `accessor.getUser()` and re-parses `realm_access.roles` (the CONNECT path applies no authority conversion), passing both explicitly.
- **HTTP and STOMP ladders provably cannot drift.** The substantive membership + strict-scoping decision is factored into one private `isGroupAdminForUser(userId, realmAdmin)`; both `isGroupAdmin()` (request context) and `canAccessShop()` (explicit context) call it. If the two boundaries ever diverge, it would be a single-helper change, not two silently-independent ladders.
- **Day-one preservation proven end-to-end.** An ungranted user under strict-scoping OFF is still an implicit GROUP_ADMIN and can still subscribe — proven against a real `ShopAccessService` + real Postgres, the case a mocked gate cannot prove and the one that would otherwise break every existing KDS client.
- **Pooled-thread hygiene.** `TenantContext` is pinned around the RLS-scoped read and cleared in a `finally` inside `validateSubscription` (not `afterMessageHandled`, which does not run for a rejected `preSend`), asserted by a no-leak test on both the permit and deny paths.
- **Falsifiability gate.** Cases 1, 4 and 7 were demonstrated RED against a gate-disabled interceptor before the fix was accepted (output below).

## Task Commits

1. **Task 1: explicit-identity `canAccessShop` + shared decision helper** — `4f22e4c` (feat)
2. **Task 2: gate the kitchen topic's shop segment in `validateSubscription`** — `ce66f01` (feat)
3. **Task 3: prove the STOMP shop gate incl. day-one preservation** — `a5f191a` (test)

## Files Created/Modified

- `core-java/.../security/access/ShopAccessService.java` — added `canAccessShop(...)` (read-only, no-ambient-state, tenant-pin fail-closed guard) + private `isGroupAdminForUser(...)`; refactored `isGroupAdmin()`'s tail to delegate to it.
- `core-java/.../websocket/TenantChannelInterceptor.java` — `ShopAccessService` collaborator; `validateShopSubscription` (shop-segment presence/UUID/identity/grant checks) invoked for the `kitchen` feature after the tenant check; `subscriberJwt` / `parseSubject` / `hasRealmAdminRole` helpers; `finally`-block `TenantContext` cleanup.
- `core-java/.../websocket/TenantChannelInterceptorTest.java` — 2-arg constructor + mocked `ShopAccessService`; 9 new SUBSCRIBE shop-gate cases (28 total, green).
- `core-java/.../security/access/ShopAccessEnforcementIntegrationTest.java` — 6 `canAccessShop` Testcontainers cases incl. the genuine-grant proof (12 total, green).
- `core-java/.../websocket/TenantChannelInterceptorShopGateIntegrationTest.java` (created) — end-to-end day-one preservation + CR-02 closure through the real service (2 cases, green).

## `/topic/` Destination Inventory (acceptance requirement)

Grep of `core-java/src/main` + `frontend` for `/topic/` (2026-07-20):

| Destination | Site | Carries a shop segment? | Gated? |
|-------------|------|-------------------------|--------|
| `/topic/kitchen/{tenantId}/{shopId}` | **publisher** `OrderStateChangeListener.java:110` (`simpMessagingTemplate.convertAndSend(topic, event)`) | **YES** (`parts[4]`) | **YES — this plan** |
| `/topic/kitchen/{tenantId}/{shopId}` | subscriber `frontend/app/dashboard/kitchen/page.tsx:277` | YES | client of the gated topic |
| `/topic/kitchen/{tid}/{shopId}` | doc comment `shop/dto/ShopDto.java:10` | (documentation only) | n/a |
| `/topic/kitchen/t/s` | `frontend/hooks/__tests__/use-stomp.test.ts` | (test fixture only) | n/a |

**`kitchen` is the ONLY `/topic/` destination that carries a shop segment**, and it is the ONLY STOMP `SimpMessagingTemplate.convertAndSend` publisher in the backend (the other `convertAndSend` hits are AMQP `rabbitTemplate`, not STOMP topics). A future shop-scoped topic MUST be added to the `KITCHEN_FEATURE`/`isShopScopedFeature` branch or it re-opens CR-02 under a new name — noted in code.

## Pre-fix RED Evidence (falsifiability gate)

Cases 1, 4 and 7 were run against a gate-disabled interceptor (the shop-segment dispatch temporarily neutralised while keeping the 2-arg constructor so the suite compiles). Result: **3 tests, 3 failed** — the ungranted subscribe was PERMITTED, the malformed shop was PERMITTED, and the gate was never consulted:

- **Case 1 `shouldRejectSubscriptionToUngrantedShop`** — `java.lang.AssertionError: Expecting code to raise a throwable.` (the ungranted-shop SUBSCRIBE completed with no exception — the live leak).
- **Case 4 `shouldRejectMalformedShopSegment`** — `java.lang.AssertionError: Expecting code to raise a throwable.` (a non-UUID shop segment was silently permitted).
- **Case 7 `shouldPassShopAndSubjectThroughToTheGate`** — `Wanted but not invoked: shopAccessService.canAccessShop(<UUID>, <UUID>, <Boolean>, <UUID>); Actually, there were zero interactions with this mock.` (the gate was never wired into the path).

Post-fix: `TenantChannelInterceptorTest` **28/28 green**; `ShopAccessEnforcementIntegrationTest` **12/12** (6 new `canAccessShop` cases); `TenantChannelInterceptorShopGateIntegrationTest` **2/2**. Refactor no-regression confirmed: `ShopAccessFailClosedIntegrationTest` 7/7, `ShopAccessJitProvisionTest` 4/4, `ShopAccessCacheBypassIntegrationTest` 4/4, full `:core-java:test` unit suite green.

## Decisions Made

- **STOMP identity is explicit, never ambient** — resolved from `accessor.getUser()` and passed as parameters; `SecurityContextHolder`/`TenantContext` are never read for identity in `canAccessShop`. This is the whole point: the ambient path fails OPEN on the STOMP thread (T-23-11-02).
- **One shared decision helper** (`isGroupAdminForUser`) so the HTTP and STOMP gates cannot drift (plan success criterion).
- **No writes on the subscribe path** — `onRequest()` (JIT + directory upsert) is deliberately not called; a SUBSCRIBE must not mutate state.
- **Tenant-pin fail-closed guard** — `canAccessShop` asserts the pinned tenant equals the requested tenant, so an unpinned RLS GUC (which would answer the `shop_staff` read with zero rows → "no grants" → implicit GROUP_ADMIN under strict-scoping OFF) cannot pass silently. The genuine-grant integration case asserts a real row comes back, not merely a denial.
- **Identity-missing = DENIAL** — an absent or non-UUID subscriber subject is rejected (a SUBSCRIBE always follows an authenticated CONNECT); this is the CR-03 defect class one transport down (T-23-11-03).

## Deviations from Plan

None — plan executed as written. No architectural changes (Rule 4). One correctness safeguard added within the plan's stated intent: `canAccessShop`'s tenant-pin assertion (Rule 2, directly mitigating the plan's RLS fail-open note). No package installs (no new dependencies).

## Deferred with Reason

- **WR-03 — post-revocation SSE window** (threat register T-23-11-06, disposition **accept**): a client whose grant is revoked mid-session keeps an already-open SSE stream until `SSE_TIMEOUT` (bounded at **5 minutes**). STOMP is the primary transport and is now gated at SUBSCRIBE; broadcast-time re-evaluation of an already-open stream needs a working membership cache, which is **plan 23-14's** scope. Deferred there.
- **Copy correction handed to plan 23-13** — `frontend/app/dashboard/staff/page.tsx:396` tells operators *"Changes take effect immediately on the person's next request."* That is **false for an already-open stream** (see WR-03): a revoked user's live SSE stream persists up to 5 minutes, and a live STOMP subscription persists until the client re-subscribes. Plan 23-13 should reword this to reflect the bounded propagation window.

## Issues Encountered

- **Test/impl coupling from the constructor-signature change.** Adding `ShopAccessService` to `TenantChannelInterceptor`'s constructor means the interceptor commit (Task 2) and the test-update commit (Task 3) only compile together; the plan's own Task 2 verify (`:core-java:test --tests "*TenantChannelInterceptorTest"`) depends on Task 3's rewritten test. Both were therefore verified together after Task 3 landed (same pattern noted in 23-08). The final branch head compiles and is green.
- **`ReflectionTestUtils.setField(AopTestUtils.getTargetObject(x), ...)` inline mis-inferred the generic type** to `Class` (ClassCastException at runtime). Fixed by caching into a typed `ShopAccessService target()` field first, exactly as `ShopAccessEnforcementIntegrationTest` does.
- **`shop_staff.shop_id` FK → `shops`.** The end-to-end scoped case must seed a real `shops` row for the granted shop (via `ShopService` as a realm-admin); the ungranted shop needs no row (the gate reads only `shop_staff`).

## Known Stubs

None — no placeholder/empty-data patterns introduced; both boundaries are live and proven.

## Test-count Delta (for the phase-gate reconcile — 23-15)

Net new Java `@Test` methods on this branch from this plan: **+17** (9 new interceptor unit cases, 6 new `canAccessShop` enforcement cases, 2 new end-to-end integration cases; the pre-existing `shouldAllowOwnTenantSubscription` was renamed to `shouldAllowSubscriptionToGrantedShop`, not removed). `docs/metrics.json` is reconciled at the phase gate (23-15), consistent with 23-08/09/10.

## Next Phase Readiness

- CR-02 is closed and proven on the live transport; the KDS STOMP channel enforces the shop boundary at SUBSCRIBE.
- **Plan 23-14** owns broadcast-time re-evaluation (WR-03) via the membership cache.
- **Plan 23-13** owns the staff-page copy correction (bounded propagation window).
- **Unchanged pre-existing phase blocker (not this plan's scope):** `docs/api/openapi-snapshot.json` still lacks the 3 `/api/v1/staff` endpoints; `OpenApiSnapshotTest` runs inside `integrationTest`. Plan 23-15 closes it via `./gradlew :core-java:updateOpenApiSnapshot`.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-20*

## Self-Check: PASSED

- Files verified present: ShopAccessService.java, TenantChannelInterceptor.java, TenantChannelInterceptorTest.java, TenantChannelInterceptorShopGateIntegrationTest.java, ShopAccessEnforcementIntegrationTest.java, 23-11-SUMMARY.md
- Commits verified in git: 4f22e4c (Task 1), ce66f01 (Task 2), a5f191a (Task 3)
- Acceptance greps: `shopAccessService` in interceptor = 4 (≥1, was 0); `SecurityContextHolder`/`TenantContext.get` inside `canAccessShop` body = 0
