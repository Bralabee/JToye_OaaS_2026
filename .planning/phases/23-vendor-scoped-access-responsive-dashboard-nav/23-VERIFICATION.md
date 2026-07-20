---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
verified: 2026-07-20T00:00:00Z
status: gaps_found
score: 3/5 roadmap success criteria verified (VSA-01, VSA-03, MOBL-01 pass; VSA-02, VSA-04 fail)
overrides_applied: 0
gaps:
  - truth: "VSA-02: application-layer enforcement denies-by-default for shop-scoped reads/writes without a valid grant (single-entity reads)"
    status: failed
    reason: >
      @Cacheable on ShopService.getShopById / ProductService.getProductById wraps
      the method with a cache key that has NO user component
      (TenantAwareCacheKeyGenerator: "tenant:{tenantId}:{method}:{params}"), and
      Spring's cache interceptor runs BEFORE the method body — so require() at
      ShopService.java:100 / inside ProductService.getProductById:118 never
      executes on a cache hit. Once ANY user in a tenant holds an explicit
      per-shop grant (SHOP_MANAGER/STAFF on shop A), ShopAccessService's own
      isGroupAdmin() logic (line 156-158: "Once a user holds ANY explicit grant,
      they are scoped even under strict-scoping OFF") makes that user NOT an
      implicit admin — so a legitimate require(shopB, STAFF) call for them
      should deny. But if shop B was already cached (fetched earlier by any
      other authorized caller, TTL 15min for shops / 10min for products), the
      scoped user's request is served from cache with no gate check at all. This
      is a LIVE bypass under the default (non-test) configuration, not a future
      strict-scoping-ON concern — the executor SUMMARY's "no-op in the operative
      default" framing is incorrect. Compounding: CacheConfig is
      `@Profile("!test")`, and ShopAccessEnforcementIntegrationTest runs under
      `@ActiveProfiles("test")` — the proving test cannot observe this bug class
      by construction (green-by-construction blind spot). List/search paths
      (getAllProducts, getAllShops, search) are NOT affected — they filter via
      `findByShopIdIn(granted, ...)` at query time and are never cached.
    artifacts:
      - path: "core-java/src/main/java/uk/jtoye/core/shop/ShopService.java"
        issue: "Line 92 @Cacheable(\"shops\") wraps getShopById; require() at line 100 runs only on cache miss"
      - path: "core-java/src/main/java/uk/jtoye/core/product/ProductService.java"
        issue: "Line 108 @Cacheable(\"products\") wraps getProductById; internal require() call runs only on cache miss"
      - path: "core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java"
        issue: "Key format tenant:{tenantId}:{method}:{params} has no caller/user component — one authorized fetch poisons the cache for every other user in the tenant"
      - path: "core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java"
        issue: "@Profile(\"!test\") means the bug is live in dev/staging/prod but structurally invisible to the Testcontainers proof suite"
    missing:
      - "Move require()/grant check outside the @Cacheable boundary for getShopById/getProductById (gate in a non-cached wrapper, or cache only post-authorization per-caller), or fold caller scope into the cache key"
      - "A Testcontainers proof with TWO DIFFERENT scoped users (not just a strict-scoping toggle) hitting the same already-cached shopId/productId, asserting the second (unauthorized) caller is denied"
  - truth: "VSA-02: shop-scoped access enforcement covers all shop-scoped read surfaces, including realtime channels"
    status: failed
    reason: >
      The STOMP kitchen topic /topic/kitchen/{tenantId}/{shopId} is validated by
      TenantChannelInterceptor.validateSubscription, which checks ONLY the
      tenantId path segment; the shopId segment is never checked against
      ShopAccessService/grantedShopIds (zero references in that file). KDS
      publishes to this topic (OrderStateChangeListener). 23-03 gated the SSE
      fan-out for the same order/kitchen data, but STOMP is the real KDS
      transport in production — any authenticated tenant user (any role, any
      grant, or none) can subscribe to ANY shop's kitchen feed within their own
      tenant, defeating the shop-scoping boundary for this channel entirely.
    artifacts:
      - path: "core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java"
        issue: "validateSubscription checks only the tenantId segment of /topic/kitchen/{tenantId}/{shopId}; no shop-grant check"
    missing:
      - "Extend validateSubscription to parse the shopId segment and call ShopAccessService.require(shopId, ShopRole.STAFF) (or check membership in grantedShopIds()) before permitting the STOMP SUBSCRIBE frame"
  - truth: "VSA-04: staff management screen and its /api/v1/staff backend are GROUP_ADMIN-only"
    status: failed
    reason: >
      ShopAccessService.isSystemPrincipal() (lines 298-309) returns true — and
      isGroupAdmin() (line 144) unconditionally trusts it as an implicit
      GROUP_ADMIN — whenever the caller has no authenticated JWT principal OR
      the JWT subject is not a UUID (a service/client-credentials token or a
      scope-only test token). StaffController carries no @PreAuthorize or other
      role restriction as a backstop; requireGroupAdmin() is the ONLY gate. A
      bearer token with a non-UUID `sub` therefore gets unrestricted GROUP_ADMIN
      on /api/v1/staff — full user_directory PII read plus the ability to
      self-grant any role on any shop. This directly contradicts locked decision
      D-04, which explicitly rejected fail-open behaviour as "unacceptable for
      an auth boundary." The design comment on isSystemPrincipal() assumes such
      callers are only trusted internal paths (schedulers/listeners with no JWT
      at all); it does not distinguish that case from an externally-presented,
      authenticated-but-non-UUID-subject JWT, which IS a real external caller on
      a real HTTP endpoint.
    artifacts:
      - path: "core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java"
        issue: "isSystemPrincipal() (lines 298-309) fail-opens any non-UUID-subject JWT to full GROUP_ADMIN via isGroupAdmin() (line 144)"
      - path: "core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java"
        issue: "No @PreAuthorize/role check independent of ShopAccessService — entirely dependent on the flawed system-principal bypass for its authorization guarantee"
    missing:
      - "Distinguish 'no JWT principal at all' (true internal/scheduler caller) from 'authenticated JWT with a non-UUID subject' (external service/client-credentials token) — only the former should bypass shop-scoping; the latter must be denied by default on user-facing endpoints such as /api/v1/staff"
      - "Add a test asserting a non-UUID-subject bearer token receives 403 on GET/POST/DELETE /api/v1/staff"
human_verification:
  - test: "23-06 and 23-07 screens (staff management, products/orders/marketing/kitchen shop-context narrowing) in a real browser at desktop and 375px"
    expected: "No visual regression, narrowing behaves as coded, no occlusion"
    why_human: "Only 23-05 (switcher + MOBL-01) was live browser-verified per the phase record; 23-06/23-07 were not — already-accepted per phase notes, restated here for completeness"
    status: "already-accepted (not re-litigated); does not affect gaps_found status which is driven by the three bypasses above"
---

# Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav Verification Report

**Phase Goal:** Establish a second authorization boundary INSIDE a tenant (Vendor→Shop), layered UNDER the untouched RLS tenant wall — VSA-01..04 + MOBL-01.
**Verified:** 2026-07-20
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths — 8 Concrete Code Claims

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | V52 migration: ENABLE+FORCE RLS on shop_staff/shop_staff_aud/user_directory, routed via `current_tenant_id()`, no raw `::uuid` cast | VERIFIED | `core-java/src/main/resources/db/migration/V52__shop_staff.sql` — all 3 tables ENABLE+FORCE RLS, all policies use `current_tenant_id()` (V51 safe helper), zero raw casts |
| 2 | ShopAccessService exposes require()/isGroupAdmin()/grantedShopIds()/evictMembership(); strict-scoping config-injected default OFF | VERIFIED (with caveat) | `ShopAccessService.java` — all 4 methods present; `@Value("${jtoye.access.strict-scoping:false}")` at line 74-75, config-injected not hardcoded. Caveat: `isSystemPrincipal()` design flaw — see VSA-04 gap |
| 3 | require()/grant-set gate present in ShopService, ProductService, OrderService, OrderSseService, PromotionService, AnnouncementService, BulkImportService | VERIFIED | Confirmed via grep in all 7 files — 12/12/14/2/6/6/3 call sites respectively, covering create/update/delete/list/transition paths |
| 4 | ShopAccessDeniedException→403, LastGroupAdminException→409 registered, 403 `type` distinct from RLS/not-found type | VERIFIED | `GlobalExceptionHandler.java`: `.../errors/shop-access-denied` (403) vs `.../errors/not-found` (404) vs `.../errors/forbidden` (generic 403) vs `.../errors/last-group-admin` (409) — all 4 types distinct |
| 5 | StaffManagementService enforces GROUP_ADMIN, guards last GROUP_ADMIN (409), calls evictMembership after grant/revoke | VERIFIED (artifact); FAILS as a security boundary — see VSA-04 gap | `StaffManagementService.java` — `requireGroupAdmin()` at top of list/grant/revoke; `wouldDowngradeLastGroupAdmin` + `countByTenantIdAndRole` guard; `evictAfterCommit` registers post-commit eviction. The *code* does what it says; the gate it relies on (`isGroupAdmin()`/`isSystemPrincipal()`) fail-opens for non-UUID-subject tokens |
| 6 | shop-context.ts exports get/set/subscribe, dispatches 'shopcontext:change'; shop-switcher.tsx gates "apply to all" on GROUP_ADMIN AND "all" context in same guard | VERIFIED | `frontend/lib/shop-context.ts` — all 3 exports present, `dispatchEvent(new Event("shopcontext:change"))`. `shop-switcher.tsx:149` — single guard `isGroupAdmin && selected === ALL_SHOPS_CONTEXT` |
| 7 | use-shop-context.ts exists, consumed by products/orders/marketing/kitchen page.tsx | VERIFIED | All four `page.tsx` files import and call `useShopContext()` |
| 8 | /dashboard/staff page + Staff nav item in sidebar.tsx | VERIFIED | `frontend/app/dashboard/staff/page.tsx` exists; `sidebar.tsx:44` — `{ name: "Staff", href: "/dashboard/staff", icon: UserCog }` |

**Score:** 8/8 concrete artifact-level claims verified as EXISTING and WIRED at the code-shape level. However, three of these artifacts (2, 3/list-vs-single-read split, 5) do not deliver the security *behaviour* they're wired to provide — see Gaps below.

### Roadmap Success Criteria

| # | Truth (roadmap SC) | Status | Evidence |
|---|------|--------|----------|
| 1 | VSA-01: shop_staff mapping table, RLS-proven | VERIFIED | V52 migration sound (see claim 1); backfill correctly deferred to JIT provision per D-04 (documented, not a gap) |
| 2 | VSA-02: application-layer enforcement, deny-by-default for shop-scoped reads/writes | **FAILED** | Gate code exists at every write path and every list/search path (sound), BUT three concrete bypasses defeat "deny-by-default": (a) `@Cacheable` short-circuits `require()` on getShopById/getProductById cache hits, live in default config; (b) STOMP kitchen topic subscription checks tenant only, not shop — any tenant user reads any shop's KDS feed; (c) see VSA-04 — the same gate underpins VSA-02's `requireGroupAdmin()` path used elsewhere too |
| 3 | VSA-03: shop-context switcher, all shop-scoped screens operate on it, "apply to all" GROUP_ADMIN-gated | VERIFIED | Switcher + `useShopContext()` wired into all 4 shop-scoped screens; single-guard "apply to all"; **live-verified in browser** (23-05 SUMMARY: desktop + 375px screenshots, passing Playwright 375px case) |
| 4 | VSA-04: staff management screen — list/grant/revoke, GROUP_ADMIN only | **FAILED** | `StaffManagementService` code is internally correct (idempotent grant, last-admin guard, cache eviction), but its sole gate (`requireGroupAdmin()` → `isGroupAdmin()` → `isSystemPrincipal()`) fail-opens to full GROUP_ADMIN for any JWT with a non-UUID subject, with no `@PreAuthorize` backstop on `StaffController`. Contradicts locked decision D-04 (fail-open explicitly rejected) |
| 5 | MOBL-01: sidebar no longer overlays at 375px | VERIFIED | `frontend/e2e/dashboard-mobile.spec.ts` — 375px viewport test asserts no horizontal overflow, sidebar hidden, tab bar + switcher visible; **live-verified in browser** (23-05) |

**Score:** 3/5 roadmap success criteria verified.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/.../db/migration/V52__shop_staff.sql` | shop_staff/_aud/user_directory, RLS | VERIFIED | Sound RLS shape, safe helper, no raw cast |
| `core-java/.../security/access/ShopAccessService.java` | require/isGroupAdmin/grantedShopIds/evictMembership | VERIFIED, WIRED | Design flaw in `isSystemPrincipal()` (see gap 3) |
| `core-java/.../security/access/StaffManagementService.java` | GROUP_ADMIN-gated staff CRUD | VERIFIED, WIRED | Gate itself is compromised by ShopAccessService flaw |
| `core-java/.../exception/{ShopAccessDeniedException,LastGroupAdminException}.java` + handler | 403/409 distinct types | VERIFIED, WIRED | Confirmed distinct `type` URIs |
| `core-java/.../shop/ShopService.java`, `.../product/ProductService.java` (single-read paths) | deny-by-default getShopById/getProductById | ⚠️ HOLLOW | `require()` present in source but bypassed by `@Cacheable` on cache hit (gap 1) |
| `core-java/.../websocket/TenantChannelInterceptor.java` | shop-scoped STOMP subscription | ⚠️ MISSING SHOP CHECK | Tenant-only check; shopId segment unchecked (gap 2) |
| `frontend/lib/shop-context.ts`, `hooks/use-shop-context.ts`, `components/dashboard/shop-switcher.tsx` | switcher + persistence + hook | VERIFIED, WIRED, DATA-FLOWING | Live-verified 23-05 |
| `frontend/app/dashboard/staff/page.tsx` + sidebar nav | staff management UI | VERIFIED, WIRED | Not browser-verified (23-06, already-accepted gap) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| ShopService/ProductService/OrderService/etc. | ShopAccessService | `require()`/`grantedShopIds()` calls | WIRED (source-level) | Present at every mutating and list/search call site |
| ShopService.getShopById / ProductService.getProductById | ShopAccessService.require() | `@Cacheable` method body | **NOT ENFORCED on cache hit** | Cache interceptor runs before body; gap 1 |
| STOMP SUBSCRIBE /topic/kitchen/{tenantId}/{shopId} | ShopAccessService | TenantChannelInterceptor | **NOT WIRED (shopId unchecked)** | gap 2 |
| StaffController | ShopAccessService.requireGroupAdmin() | direct call, no @PreAuthorize backstop | **PARTIAL — fails open for non-UUID JWT subjects** | gap 3 |
| shop-switcher.tsx / page.tsx x4 | use-shop-context.ts / shop-context.ts | import + hook call | WIRED, DATA-FLOWING | Live-verified |
| sidebar.tsx | /dashboard/staff | nav item | WIRED | Confirmed |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| VSA-01 | 23-01, 23-02 | shop_staff mapping table (V52) | SATISFIED | V52 migration sound |
| VSA-02 | 23-02, 23-03 | Application-layer enforcement, deny-by-default | **BLOCKED** | Cache bypass + STOMP bypass defeat deny-by-default guarantee |
| VSA-03 | 23-05, 23-07 | Dashboard shop-context switcher | SATISFIED | Live-verified, correctly gated |
| VSA-04 | 23-04, 23-06 | Staff management screen, GROUP_ADMIN only | **BLOCKED** | System-principal fail-open (gap 3) defeats the GROUP_ADMIN-only guarantee |
| MOBL-01 | 23-05, 23-06 | Sidebar no occlusion at 375px | SATISFIED | Live-verified Playwright 375px test |

**REQUIREMENTS.md currently marks VSA-02 and VSA-04 as "Complete."** This is **not supported by the evidence** above and should be corrected to reflect the open gaps (e.g. "In Progress — bypasses found in verification") until the three findings are closed.

### Data-Flow Trace (Level 4) — relevant slice

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `useShopContext()` consumers (products/orders/marketing/kitchen) | `contextShopId` | `localStorage` via `shop-context.ts`, hydrated post-mount | Yes, real user selection | ✓ FLOWING |
| `getAllProducts`/`getAllShops` list paths | `granted` (Set<UUID>) | `ShopAccessService.grantedShopIds()` → DB query `findByShopIdIn` | Yes, real query-time scoping | ✓ FLOWING |
| `getShopById`/`getProductById` single reads | cached `ShopDto`/`ProductDto` | Redis cache keyed without caller identity | Real data, but **wrong caller** can receive it | ⚠️ HOLLOW GATE (data is real, but authorization decision is skipped) |

### Anti-Patterns Found

No `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` debt markers found in the phase's core new files (`ShopAccessService.java`, `StaffManagementService.java`, `shop-context.ts`, `use-shop-context.ts`, `shop-switcher.tsx`, `staff/page.tsx`). The three findings below are **logic/authorization defects**, not code-smell markers, and are treated as gaps rather than anti-pattern findings:

| File | Line | Pattern | Severity | Impact |
|---|---|---|---|---|
| `core-java/.../shop/ShopService.java` | 92-100 | `@Cacheable` ahead of `require()` | 🛑 Blocker | Cache-hit bypass of shop-scope gate |
| `core-java/.../product/ProductService.java` | 108-118 | Same pattern | 🛑 Blocker | Same |
| `core-java/.../websocket/TenantChannelInterceptor.java` | validateSubscription | Tenant-only check, shopId segment unchecked | 🛑 Blocker | Cross-shop KDS feed leak within tenant |
| `core-java/.../security/access/ShopAccessService.java` | 298-309 | `isSystemPrincipal()` fail-open on non-UUID JWT subject | 🛑 Blocker | Unrestricted GROUP_ADMIN for non-UUID-subject tokens on `/api/v1/staff` |

### Known, Already-Accepted Items (not re-litigated, recorded for completeness)

- **CI blocker (pre-existing, not new):** `docs/api/openapi-snapshot.json` lacks the 3 `/api/v1/staff` endpoints (confirmed: `grep -c "staff"` on the snapshot returns 0). `OpenApiSnapshotTest` will fail in `integrationTest` until `./gradlew :core-java:updateOpenApiSnapshot` is run and the diff committed. Documented as a known open item in `deferred-items.md` (23-06 entry) — could not be executed under the low-footprint/no-Docker execution constraint.
- **No SECURITY.md yet** — `security_enforcement=true` on this phase means `/gsd:secure-phase 23` is still pending. Given the three findings in this report, that step is now load-bearing, not optional.
- **23-06/23-07 screens not browser-verified** — only 23-05 (switcher + MOBL-01) had live desktop/375px screenshots and a passing Playwright 375px case. This was already recorded as an accepted gap in the phase's own SUMMARYs and is not escalated further here.

### What Is Genuinely Sound (do not re-litigate in gap-closure)

- **V52 RLS shape** — ENABLE+FORCE RLS, routed through the safe `current_tenant_id()` helper, zero raw `::uuid` casts, correct Envers `_aud` mirror shape, `user_directory` FORCE RLS load-bearing for PII. Solid.
- **Typed-error distinctness** — `shop-access-denied` (403) / `not-found` (404) / `forbidden` (403 generic) / `last-group-admin` (409) are four genuinely distinct RFC 7807 `type` URIs; the tenant-boundary signal is not blurred with the in-tenant shop gate.
- **Query-level read-scoping on list/search paths** — `getAllProducts`, `getAllShops`, `search` all filter via `findByShopIdIn(granted, ...)` at the SQL layer, not client-side. This is real, unaffected by the cache bypass (lists are never `@Cacheable`), and the 23-07 client-side re-narrowing on top of it is genuinely cosmetic (cannot widen the already-authorized set) — that specific claim in the 23-07 SUMMARY is accurate.
- **Frontend switcher + hook wiring** — `shop-context.ts`, `use-shop-context.ts`, `shop-switcher.tsx`, and all four consuming `page.tsx` files are correctly wired, with the "apply to all" affordance correctly single-guarded on `isGroupAdmin && selected === ALL_SHOPS_CONTEXT`.
- **MOBL-01** — the 375px responsive nav is real, live-tested, and does not occlude content; this was the one surface in the phase that received full live-browser verification.

## Gaps Summary

Three concrete authorization bypasses were found in code that a parallel review flagged and this verification independently confirmed by reading the exact lines cited:

1. **Cache-hit bypass (VSA-02).** `@Cacheable` on `getShopById`/`getProductById` runs before `require()` can execute; the cache key has no user component, so one user's authorized read poisons the cache for every other user in the tenant for up to 10-15 minutes. This is live in the default (non-test) profile — not a "future strict-scoping-ON" concern as the phase's own SUMMARY claimed. The proving Testcontainers suite runs under the `test` profile, where caching is disabled by `@Profile("!test")`, so it structurally cannot catch this bug class.
2. **STOMP kitchen-topic bypass (VSA-02).** `TenantChannelInterceptor` checks only the tenant segment of the KDS topic path; any tenant user can subscribe to any shop's kitchen feed regardless of shop_staff grants, even though the equivalent SSE path was correctly gated in 23-03.
3. **System-principal fail-open (VSA-04).** `isSystemPrincipal()` treats any JWT with a non-UUID subject as a trusted internal caller and grants it unconditional GROUP_ADMIN, with `StaffController` carrying no independent `@PreAuthorize` backstop — contradicting the phase's own locked decision D-04 that fail-open is unacceptable for this boundary.

All three are BLOCKER-level: they defeat the core promise of VSA-02 (deny-by-default enforcement) and VSA-04 (GROUP_ADMIN-only staff management) respectively, even though the surrounding artifacts (services, DTOs, migration, exception types) are all present, substantive, and correctly wired at the source level. REQUIREMENTS.md's "Complete" marking for VSA-02 and VSA-04 should be corrected pending fixes. VSA-01, VSA-03, and MOBL-01 are genuinely achieved and should not be re-opened by the gap-closure plan.

---

_Verified: 2026-07-20_
_Verifier: Claude (gsd-verifier)_
