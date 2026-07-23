---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
reviewed: 2026-07-20T11:41:50Z
depth: deep
diff_base: e21e74b
files_reviewed: 34
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
  - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
  - core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java
  - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryRepository.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaff.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopRole.java
  - core-java/src/main/java/uk/jtoye/core/security/access/Membership.java
  - core-java/src/main/java/uk/jtoye/core/security/access/dto/GrantStaffRequest.java
  - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffMemberDto.java
  - core-java/src/main/java/uk/jtoye/core/security/access/dto/DirectoryEntryDto.java
  - core-java/src/main/java/uk/jtoye/core/exception/ShopAccessDeniedException.java
  - core-java/src/main/java/uk/jtoye/core/exception/LastGroupAdminException.java
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
  - core-java/src/main/java/uk/jtoye/core/shop/PromotionService.java
  - core-java/src/main/java/uk/jtoye/core/shop/AnnouncementService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
  - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeEvent.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java
  - core-java/src/main/resources/db/migration/V52__shop_staff.sql
  - core-java/src/main/resources/application.yml
  - frontend/lib/shop-context.ts
  - frontend/lib/shops-api.ts
  - frontend/lib/staff-api.ts
  - frontend/hooks/use-shop-context.ts
  - frontend/components/dashboard/shop-switcher.tsx
  - frontend/components/dashboard/dashboard-shell.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/app/dashboard/staff/page.tsx
  - frontend/app/dashboard/products/page.tsx
  - frontend/app/dashboard/orders/page.tsx
  - frontend/app/dashboard/marketing/page.tsx
  - frontend/app/dashboard/kitchen/page.tsx
findings:
  critical: 8
  warning: 12
  info: 3
  total: 23
status: issues_found
---

# Phase 23: Code Review Report

**Reviewed:** 2026-07-20T11:41:50Z
**Depth:** deep (cross-file: call chains, cache proxy semantics, transport parity)
**Files Reviewed:** 34 source files (`.planning/` docs excluded)
**Status:** issues_found

## Summary

Phase 23 builds a second authorization boundary (Vendor → Shop) under the existing
RLS tenant wall. The **data layer is sound**: V52 carries `ENABLE + FORCE` RLS on all
three tables, every policy routes through the safe `current_tenant_id()` helper with no
raw `::uuid` cast, the `_aud` mirror has a tenant predicate, and the functional unique
index correctly treats `NULL shop_id` as the zero-UUID. The typed 403
(`/shop-access-denied`) is genuinely distinct from the RLS 404 and the generic
`/forbidden`, and `ShopAccessDeniedException` deliberately avoids extending
`AccessDeniedException` so the specific handler wins. List read-scoping is real
query-level narrowing (`findByShopIdIn`, `searchFullTextInShops`,
`findByTenantIdAndIdIn`), not post-hoc filtering.

The **enforcement layer is not sound.** Eight defects allow the gate to be bypassed,
silently no-op, or crash:

- The gate is placed *inside* two `@Cacheable` method bodies whose cache key is
  per-**tenant**, not per-user — on a warm cache the gate never executes (CR-01).
- The actual KDS live transport is STOMP, and the STOMP subscription interceptor
  validates only the tenant segment of `/topic/kitchen/{tid}/{shopId}`. The phase
  gated the SSE fan-out and left the primary transport wide open (CR-02).
- `isSystemPrincipal()` maps "I cannot parse this identity" to "unrestricted
  GROUP_ADMIN", and that path is reachable over HTTP including `/api/v1/staff` (CR-03).
- `require(null, role)` throws NPE (not 403) — reachable because `Product.shop_id` is
  nullable by design (CR-04).
- Role changes silently no-op and report success, so downgrades never apply (CR-05).
- The last-GROUP_ADMIN guard is a check-then-act race (CR-06).
- Turning `strict-scoping` ON tightens nothing for existing users, because JIT already
  wrote permanent tenant-wide GROUP_ADMIN rows for all of them (CR-07).

Compounding this: the enforcement integration test runs under `@ActiveProfiles("test")`,
where `CacheConfig` is `@Profile("!test")`. **The entire CR-01 bug class is invisible to
the proving test by construction** — the code comments even acknowledge the cache is a
test no-op and treat that as a footnote rather than a coverage hole.

Separately, `resolveMembership` is `@Cacheable` but only ever invoked via
self-invocation, so with Spring's default proxy-mode caching the membership cache, its
5-minute TTL, and the entire D-05 eviction path are dead code (WR-01).

---

## Critical Issues

### CR-01: `@Cacheable` short-circuits the shop gate — cross-shop read on a warm cache

**File:** `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:91-100`
**File:** `core-java/src/main/java/uk/jtoye/core/product/ProductService.java:107-120`

**Issue:** Both methods are annotated `@Cacheable(keyGenerator = "tenantAwareCacheKeyGenerator")`
and place `shopAccessService.require(...)` inside the method body. Spring's cache
interceptor runs **before** the method body. On a cache hit the body — and therefore the
gate — is never executed.

`TenantAwareCacheKeyGenerator` (`config/TenantAwareCacheKeyGenerator.java:45`) builds the
key as `tenant:{tenantId}:{method}:{params}`. There is **no user/grant component**, so
one tenant-wide cache entry is shared by every user in the tenant.

Failure scenario:
1. A GROUP_ADMIN (or any user granted shop A) calls `GET /shops/{A}` → entry cached at
   `tenant:T:getShopById:A` (TTL 15 min; `products` TTL 10 min).
2. A `SHOP_MANAGER` scoped only to shop B calls `GET /shops/{A}` → cache hit → the DTO is
   returned with `require()` never invoked. Same for `GET /products/{id}` on any
   out-of-grant product.

The `ShopService.java:94-97` comment claims "a DENY always throws before any lookup and
nothing is cached for an unauthorized caller" — true for the *miss* path, irrelevant to
the *hit* path, which is the exploitable one. The residual note about the cache being
"per-tenant, not per-shop" understates this: it is a full gate bypass, not a granularity
nit.

Unverifiable by the current suite: `CacheConfig` is `@Profile("!test")`
(`config/CacheConfig.java:44`) and `ShopAccessEnforcementIntegrationTest` runs
`@ActiveProfiles("test")`.

**Fix:** Move the gate outside the cached method so it runs on every call, and include
the caller in the key.

```java
// ShopService — gate in an uncached wrapper, cache only the pure load.
public Optional<ShopDto> getShopById(UUID shopId) {
    shopAccessService.require(shopId, ShopRole.STAFF);   // ALWAYS runs
    return loadShopCached(shopId);
}

@Cacheable(value = "shops", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
public Optional<ShopDto> loadShopCached(UUID shopId) { ... }   // must be called via the proxy
```

For `ProductService.getProductById` the gate needs the loaded entity, so either (a) load
uncached, gate, then map; or (b) cache the entity and gate on `product.getShopId()` after
the cached fetch returns. Add a regression test that runs with caching **enabled** (a
non-`test` profile or an explicitly registered `ConcurrentMapCacheManager`) asserting a
warm-cache cross-shop read still 403s.

---

### CR-02: KDS STOMP subscriptions are not shop-gated — the primary live transport bypasses VSA entirely

**File:** `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java:108-138`
**File:** `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java:109-110`
**File:** `frontend/app/dashboard/kitchen/page.tsx:277,321`

**Issue:** The phase added `ShopScope` filtering to the SSE fan-out
(`OrderSseService.java:63-99`) but the kitchen board's live channel is **STOMP**, not SSE:
the page subscribes to `/topic/kitchen/${tenantId}/${selectedShopId}` (`kitchen/page.tsx:277`)
via `useStomp`, and `OrderStateChangeListener` publishes each `OrderStateChangeEvent` to
that exact topic.

`validateSubscription` splits the destination and checks only `parts[3]` (the tenant
segment) against the session tenant. The `{shopId}` segment is never checked against the
subscriber's grants.

Failure scenario: a `STAFF` user granted only shop A opens a WebSocket, sends
`SUBSCRIBE /topic/kitchen/{ownTenant}/{shopB-id}`, and receives every live order state
change for shop B — order ids, order numbers, and status transitions — in real time. No
gate, no 403. Shop ids are discoverable (they appear in DTOs and public storefront URLs).

This is the same leak class as AUDIT-W0-01 (cited in `OrderSseService`'s own javadoc),
one level down: fixed for tenants, still open for shops.

**Fix:** Extend `validateSubscription` to grant-check the shop segment for
`/topic/kitchen/**`:

```java
// after the tenant check, for kitchen topics:
if (parts.length >= 5 && "kitchen".equals(parts[2])) {
    UUID destShop = UUID.fromString(parts[4]);
    // TenantContext is pinned by propagateTenantContext; SecurityContext must be
    // populated from accessor.getUser() before this call.
    if (!shopAccessService.isGroupAdmin()
            && !shopAccessService.grantedShopIds().contains(destShop)) {
        throw new MessageDeliveryException("Shop subscription denied");
    }
}
```
Note the STOMP CONNECT path sets `accessor.setUser(new JwtAuthenticationToken(jwt))` but
does not populate `SecurityContextHolder`, so `ShopAccessService` would see no principal
and hit the CR-03 fail-open. Resolve the principal explicitly from the accessor rather
than relying on `SecurityContextHolder` here.

---

### CR-03: `isSystemPrincipal()` fails OPEN — an unparseable JWT subject becomes unrestricted GROUP_ADMIN

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:298-309`
**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:143-145`

**Issue:**

```java
private boolean isSystemPrincipal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
        return true;                 // ← no principal ⇒ full GROUP_ADMIN
    }
    return parseSub(jwt) == null;    // ← unparseable sub ⇒ full GROUP_ADMIN
}
```

`isGroupAdmin()` returns `true` unconditionally when this is true. Two reachable paths:

1. **Non-UUID subject over HTTP.** Any token that passes JWT validation (correct issuer +
   `aud=core-api`) but carries a non-UUID `sub` is treated as a trusted system caller and
   bypasses shop scoping completely. `StaffController` carries **no** `@PreAuthorize` scope
   requirement (unlike `ProductController`'s `SCOPE_catalog:write`), so such a token can
   `GET /api/v1/staff` — returning every colleague's email/display name from
   `user_directory` (PII) — and `POST /api/v1/staff/grant` to award itself or anyone a
   tenant-wide GROUP_ADMIN. `requireGroupAdmin()` passes at
   `ShopAccessService.java:122-124`.

2. **Null `Authentication`.** Any gated service invoked on a thread without a populated
   `SecurityContextHolder` gets GROUP_ADMIN. The class javadoc asserts "every gated
   controller sits behind Spring Security", but that reasoning does not cover
   non-`DelegatingSecurityContext` async execution or the STOMP path (CR-02), where
   `accessor.setUser(...)` is set but `SecurityContextHolder` is not.

The javadoc reasoning ("a gate call with no JWT principal is a trusted internal path") is
the security-relevant claim and it is not enforced by anything in code.

**Fix:** Fail closed and make the system path explicit rather than inferred.

```java
/** Explicit, non-inferable marker set only by trusted internal entry points. */
private static final ThreadLocal<Boolean> SYSTEM_CALL = ThreadLocal.withInitial(() -> false);

public static <T> T asSystem(Supplier<T> work) {
    SYSTEM_CALL.set(true);
    try { return work.get(); } finally { SYSTEM_CALL.remove(); }
}

private boolean isSystemPrincipal() {
    return SYSTEM_CALL.get();   // never derived from a missing/odd principal
}
```
Then: (a) wrap the genuine internal callers (schedulers, AMQP listeners, outbox flusher)
in `asSystem`; (b) treat a request-scoped principal with an unparseable `sub` as a
**denial**, not an escalation; (c) add `@PreAuthorize` scope requirements to
`StaffController` consistent with issue #206.

---

### CR-04: `require(null, role)` throws NPE → 500 instead of a typed 403, on a designed-nullable field

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:107`
**File:** `core-java/src/main/java/uk/jtoye/core/product/ProductService.java:68,115,220`
**File:** `core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java:73`

**Issue:** `require()` does `membership.perShopRole().get(shopId)`. `perShopRole` is built
with `Map.copyOf(...)` (`ShopAccessService.java:201`), which returns
`ImmutableCollections.MapN` — and `MapN.get(null)` throws `NullPointerException` in both
the populated and empty cases. Verified directly:

```
non-empty get(null) THREW java.lang.NullPointerException: Cannot invoke "Object.equals(Object)" because "o" is null
empty     get(null) THREW java.lang.NullPointerException
```

`Product.shopId` is nullable by design — `@Column(name = "shop_id")` with no
`nullable=false` (`product/Product.java:78`), and `CreateProductRequest.shopId` is
documented `"null = available on all tenant shops"` (`product/dto/CreateProductRequest.java:83`).
The products UI still offers an explicit "All Shops" option (`products/page.tsx`), and
`BulkImportService.importFromImages` creates products with no shop assignment.

Failure scenarios for any non-GROUP_ADMIN caller (i.e. every scoped user, and every user
once strict-scoping is ON):
- `GET /products/{id}` on any legacy/unassigned product → NPE → 500 (should be 403).
- `GET /products/{id}/label` on the same → 500.
- `POST /products` with no `shopId` → 500 (should be 403).
- `PUT/DELETE /products/{id}` on an unassigned product → 500.

Note this is a hard 500 on a path where a clean typed 403 was the whole point of D-01/D-13,
and it also breaks the RFC 7807 agent-readiness contract for those routes.

**Fix:** Handle the null shop explicitly, and decide the policy deliberately:

```java
public void require(UUID shopId, ShopRole minRole) {
    onRequest();
    if (isGroupAdmin()) return;
    if (shopId == null) {
        // Tenant-wide/unassigned resource: only a GROUP_ADMIN may act on it.
        throw new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN);
    }
    ShopRole role = membership.perShopRole().get(shopId);
    if (role == null || !role.satisfies(minRole)) {
        throw new ShopAccessDeniedException(shopId, minRole);
    }
}
```
Add a unit test asserting `require(null, STAFF)` throws `ShopAccessDeniedException`, not
`NullPointerException`.

---

### CR-05: Role change silently no-ops and reports success — downgrades never take effect

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java:132-149`
**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java:62-73`
**File:** `frontend/app/dashboard/staff/page.tsx:148-184`

**Issue:** `insertGrantIfAbsent` is `INSERT ... ON CONFLICT (tenant_id, user_id,
COALESCE(shop_id, zero)) DO NOTHING`. The conflict target **excludes `role`**. When a grant
already exists for `(user, shop)` with a *different* role, the insert no-ops. The service
then re-selects the canonical row (line 137-141) — which still carries the **old** role —
and returns it with `created=false` → HTTP **200 OK** with a well-formed `StaffMemberDto`.

Failure scenario (security-relevant, and the direction that matters):
1. Alice holds `SHOP_MANAGER` on shop A.
2. A GROUP_ADMIN decides Alice should be `STAFF`. The staff screen's grant form
   (`staff/page.tsx:325-337`) lets them pick person=Alice, shop=A, role=STAFF and click
   "Grant access".
3. Server returns 200. The UI shows the toast `"Access granted"` (line 162) and reloads.
4. Alice still has `SHOP_MANAGER`. The intended downgrade never happened, and the operator
   has positive confirmation that it did.

The same holds for upgrades (STAFF → SHOP_MANAGER silently fails), so **no role can ever
be changed through the API** — the only workaround is revoke-then-grant, which the UI does
not suggest and the copy actively contradicts ("Granting the same access twice is safe —
it will not create a duplicate", line 342-343).

**Fix:** Make the grant an upsert on role, keeping idempotency:

```sql
INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, created_by)
VALUES (:id, :tenantId, :userId, CAST(:shopId AS uuid), CAST(:role AS varchar), now(), :createdBy)
ON CONFLICT (tenant_id, user_id, COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid))
DO UPDATE SET role = EXCLUDED.role
WHERE shop_staff.role IS DISTINCT FROM EXCLUDED.role
```
Return `created=true` only for a genuine insert; return 200 for a true no-change replay and
200 for an applied change. Run the D-11 downgrade guard before the upsert (it already is).
Note the upsert must go through Hibernate or be paired with an explicit audit write — see
WR-02.

---

### CR-06: Last-GROUP_ADMIN guard is a check-then-act race — concurrent revokes lock the tenant out

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java:166-178`
**File:** `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java:193-199`

**Issue:**

```java
if (row.getRole() == ShopRole.GROUP_ADMIN
        && shopStaffRepository.countByTenantIdAndRole(tenantId, ShopRole.GROUP_ADMIN) <= 1) {
    throw new LastGroupAdminException(...);
}
...
shopStaffRepository.delete(row);
```

Under READ COMMITTED there is no lock, no `SELECT ... FOR UPDATE`, and no DB-level
constraint backing the invariant. Two concurrent `DELETE /api/v1/staff/{id}` requests
targeting two *different* GROUP_ADMIN rows both read `count == 2`, both pass the guard,
and both delete → the tenant ends with **zero** GROUP_ADMINs. `wouldDowngradeLastGroupAdmin`
(line 193-199) has the identical shape.

Impact depends on configuration:
- `strict-scoping = false` (default): survivable — ungranted users fall back to implicit
  GROUP_ADMIN (`ShopAccessService.java:158`).
- `strict-scoping = true` (the mode the feature exists for): **permanent lockout**. Every
  remaining user is scoped, `requireGroupAdmin()` denies all of them, `/api/v1/staff` is
  403 for everyone, and there is no in-app recovery unless someone happens to hold the
  Keycloak realm `admin` role. Recovery requires direct DB access.

The javadoc explicitly names this as the risk the guard exists to prevent
(`LastGroupAdminException.java:5-9`), so the guard not holding is a defect against its own
stated contract.

**Fix:** Serialize the check with a row lock, or enforce it in the database.

```java
// Repository — lock all tenant-wide GROUP_ADMIN rows for the duration of the tx.
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM ShopStaff s WHERE s.tenantId = :tenantId AND s.shopId IS NULL AND s.role = 'GROUP_ADMIN'")
List<ShopStaff> lockTenantGroupAdmins(@Param("tenantId") UUID tenantId);
```
Call it at the top of `revoke()` / `grant()` before counting. A concurrent revoke then
blocks, re-reads `count == 1`, and correctly 409s. A DB-level alternative is a deferred
constraint trigger asserting `count(*) >= 1` per tenant.

---

### CR-07: Enabling `strict-scoping` tightens nothing — JIT has already granted everyone permanent GROUP_ADMIN

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:262-276`
**File:** `core-java/src/main/resources/application.yml:97-101`

**Issue:** While `strict-scoping` is OFF (the default), `onRequest()` writes a **real,
persistent** tenant-wide GROUP_ADMIN row into `shop_staff` for every authenticated caller
on their first write-capable request:

```java
if (!strictScoping && !isRealmAdmin()
        && !shopStaffRepository.existsByTenantIdAndUserId(tenantId, sub)) {
    shopStaffRepository.insertGroupAdminIfAbsent(UUID.randomUUID(), tenantId, sub);
}
```

Flipping `ACCESS_STRICT_SCOPING=true` only stops *new* provisioning. Every user (and every
service account — see WR-09) who has ever made a write request retains an explicit
tenant-wide GROUP_ADMIN grant, which `resolveMembership` honours unconditionally
(`ShopAccessService.java:190-195`). The config comment claims ON "makes ungranted
non-admins deny-by-default **so a vendor can genuinely tighten**" — for any tenant with
existing usage, it does not.

Failure scenario: a vendor with 20 staff runs for three months on the default, then
enables strict-scoping to lock down access before onboarding contractors. Nothing changes:
all 20 still hold GROUP_ADMIN and can read/write every shop, create/delete shops, and
manage staff. The operator has no signal that the control did not engage.

Worse, the individual cleanup path is obstructed by CR-05 (roles cannot be changed) and
CR-06's guard (the last revoke 409s), so de-provisioning 20 auto-grants is manual and
error-prone.

**Fix:** Distinguish an implicit day-one grant from a deliberate one, and make the switch
transitional.
1. Mark JIT rows — e.g. `created_by IS NULL` already distinguishes them, or add an explicit
   `provisioned` / `source = 'JIT'` column.
2. On enabling strict-scoping, do not honour JIT-sourced tenant-wide GROUP_ADMIN rows;
   honour only operator-created grants (`created_by IS NOT NULL`), keeping at least one
   (oldest, or realm-admin) as the bootstrap admin.
3. Surface the JIT rows distinctly in the staff screen ("auto-granted on first sign-in")
   with a bulk-revoke action, so the tightening is a visible, reviewable act.
4. Add an integration test: seed N JIT users under strict OFF, flip strict ON, assert they
   are now scoped.

---

### CR-08: Frontend GROUP_ADMIN detection disagrees with the backend model — "All shops" is unreachable for most admins

**File:** `frontend/lib/shops-api.ts:39-47`
**File:** `frontend/components/dashboard/shop-switcher.tsx:44-57,91-107,130`

**Issue:** `isGroupAdminFromSession()` derives GROUP_ADMIN status **solely** from the
Keycloak realm `admin` role in the access token. The backend's authority model is broader:
a GROUP_ADMIN is anyone holding a `NULL`-shop `GROUP_ADMIN` row in `shop_staff` — which,
under the default `strict-scoping = false`, is **every JIT-provisioned user** (CR-07), plus
anyone explicitly granted GROUP_ADMIN via the staff screen. Realm `admin` is only the
*bridge*, not the definition (`ShopAccessService.java:143-159`).

Consequences for a genuine tenant GROUP_ADMIN who is not a realm admin — the default
day-one user:

- `shop-switcher.tsx:44-45`: `fallback = ga || fetched.length === 0 ? ALL_SHOPS_CONTEXT : fetched[0].id`.
  With `ga === false` and a non-empty shop list, the fallback is `fetched[0].id`, and line 57
  (`if (next !== saved) setShopContext(next)`) **persists it**. Every user is silently pinned
  to their first shop on first load — the dashboard narrows from "all shops" to one shop with
  no user action. This is exactly the regression-by-omission the Incremental Betterment
  Doctrine names as a defect: today's working cross-shop view is removed by default.
- `shop-switcher.tsx:130`: the `<option value="all">All shops</option>` renders only when
  `isGroupAdmin`, so they cannot select it back.
- `shop-switcher.tsx:91-107`: a GROUP_ADMIN whose tenant has exactly one shop gets the
  **pinned label with no dropdown at all**.
- D-06 ("a GROUP_ADMIN lands on All shops") is therefore false for the majority of admins.

The `MyShops.isGroupAdmin` doc comment ("True for a GROUP_ADMIN. Realm `admin` ⇒ implicit
GROUP_ADMIN") states the implication in the opposite direction from what the code does.

**Fix:** Have the server answer the question rather than inferring it client-side. Add the
caller's effective scope to a response the switcher already fetches:

```java
// e.g. GET /api/v1/shops returns a header, or add GET /api/v1/staff/me
public record MyAccessDto(boolean groupAdmin, Set<UUID> grantedShopIds) {}
```
`shopAccessService.isGroupAdmin()` is already the single source of truth — expose it.
Then drop `isGroupAdminFromSession()` and `decodeJwtPayload` entirely (client-side JWT
parsing for an authorization display decision is the wrong shape even for a UI hint).

---

## Warnings

### WR-01: The membership cache never engages — `@Cacheable` self-invocation makes D-05 dead code

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:185-202,106,147,174`
**File:** `core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java:90-95`

**Issue:** `resolveMembership` is `@Cacheable("shopMembership")`, but its only call sites are
`this.resolveMembership(...)` from within the same bean (`require()` line 106,
`isGroupAdmin()` line 147, `grantedShopIds()` line 174). `@EnableCaching` is used in default
**proxy mode** (`CacheConfig.java:43`, no `AdviceMode.ASPECTJ`, no `exposeProxy`), so
self-invocation never passes through the caching interceptor.

Net effect: the cache is never populated or read; the 5-minute `shopMembership` TTL is dead
config; `evictMembership()` and `StaffManagementService.evictAfterCommit()` evict keys that
never exist; and the D-05 "immediate revocation with no stale-allow window" property is
unproven because the mechanism it guards is inert.

This currently fails *safe* (every gate call re-reads `shop_staff`), which is why it has not
surfaced as a bug — but the moment someone "fixes the performance" by making the cache work,
the untested eviction path becomes load-bearing with no coverage behind it.

**Fix:** Either (a) delete the `@Cacheable`, the `shopMembership` cache config, and
`evictMembership`, and document that membership is resolved per call; or (b) make it real by
injecting a self-reference and adding a test that asserts a second `require()` in the same
request does not re-query, plus a test that a revoke makes the next call re-resolve:

```java
@Lazy private final ShopAccessService self;   // or ObjectProvider<ShopAccessService>
...
Membership membership = self.resolveMembership(currentUserId());
```
Option (b) also requires verifying `Membership` (a record with `Map<UUID, ShopRole>`)
round-trips through the Redis JSON serializer — note that a deserialized `LinkedHashMap`
would *not* reproduce the CR-04 NPE, making that bug cache-state-dependent.

---

### WR-02: Grants bypass Envers — `shop_staff_aud` records revokes but never grants

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java:40-73`
**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopStaff.java:34`
**File:** `core-java/src/main/resources/db/migration/V52__shop_staff.sql:62-84`

**Issue:** `ShopStaff` is `@Audited` and V52 ships the `shop_staff_aud` mirror, but both write
paths (`insertGroupAdminIfAbsent`, `insertGrantIfAbsent`) are `nativeQuery = true` INSERTs
that bypass the Hibernate session entirely. Envers never sees them. Only
`shopStaffRepository.delete(row)` in `revoke()` (`StaffManagementService.java:178`) produces an
audit revision.

Result: the audit table for a security-authorization model records *only* removals. "Who
granted whom GROUP_ADMIN on which shop, and when" — the single most forensically valuable
question about this table — is unanswerable from the audit trail. The `created_by`/`created_at`
columns on the live row give a partial answer, but they are overwritten-in-place semantics with
no history, and JIT rows carry `created_by = NULL`.

**Fix:** Either write an explicit `shop_staff_aud` row alongside each native insert in the same
transaction, or restructure the grant as a Hibernate `save()` guarded by a pre-check plus
`DataIntegrityViolationException` catch for the race (losing ON CONFLICT elegance but gaining
audit correctness). If the audit mirror is genuinely not wanted, remove `@Audited` and the
`shop_staff_aud` table rather than shipping a half-populated audit log that implies completeness.

---

### WR-03: SSE shop scope is snapshotted at subscribe and never re-evaluated — up to 5 minutes of post-revocation data

**File:** `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:29-45,63-67`

**Issue:** `ShopScope` is captured once in `subscribe()` and stored per-emitter. A grant revoked
mid-stream has no effect until the client reconnects — bounded by `SSE_TIMEOUT` (5 minutes per
the javadoc). During that window a revoked user continues receiving live order state changes for
shops they no longer have access to.

The code documents this as accepted ("takes effect on the next reconnect"), but it directly
contradicts D-05's stated "immediate revocation with no stale-allow window", and the staff screen
tells the operator "Changes take effect immediately on the person's next request"
(`staff/page.tsx:396-397`) — which is false for an already-open stream.

**Fix:** Re-check scope at broadcast time (cheap once WR-01 is resolved), or have
`StaffManagementService.evictAfterCommit` also call a new
`orderSseService.closeEmittersForUser(userId)` so the client is forced to reconnect and
re-snapshot. At minimum, correct the UI copy to state the actual bound.

---

### WR-04: Products and marketing narrow client-side over a single page — wrong counts, wrong empty state, missing rows

**File:** `frontend/app/dashboard/products/page.tsx:112-121,366-372,386-395`
**File:** `frontend/app/dashboard/marketing/page.tsx:489-509,597-601,745-752`

**Issue:** `visibleProducts = products.filter((p) => p.shopId === contextShopId)` filters the
**current page** of a server-paginated list. The orders page got this right — it passes
`?shopId=` and narrows server-side (`orders/page.tsx:291-294`, backed by
`OrderController.java:94-99`) — but products and marketing did not, even though
`ProductRepository.findByShopIdIn` already exists.

Concrete failures in a shop context:
- **Wrong count.** `visibleCount = visibleProducts.length` (line 119) renders "4 products in
  Shop A" when Shop A has 60 products and only 4 happen to be on page 1.
- **False empty state.** A page whose 20 rows all belong to other shops renders "No products in
  this shop" (line 389) while pagination controls still show 12 pages.
- **Unreachable rows.** A user must page through the entire tenant catalogue to find their own
  shop's products.
- Marketing has the identical pattern for both promotions and announcements.

Not a security issue — the underlying set is already grant-scoped server-side — but it is a
visible correctness regression on the primary catalogue screen.

**Fix:** Add `?shopId=` to `GET /api/v1/products` (and the marketing list endpoints) routed to
the existing `findByShopIdIn` / `findByShopIdIn(pageable)` finders, gate it with
`require(shopId, STAFF)` exactly as `OrderService.getOrdersByShop` does, and have the pages
refetch on `contextShopId` change (the orders page pattern at `orders/page.tsx:270-273`).
Remove the client-side filters and restore `totalElements` as the count source.

---

### WR-05: `grant()` validates neither that `shopId` belongs to the tenant nor that `userId` is known

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java:110-141`
**File:** `core-java/src/main/java/uk/jtoye/core/security/access/dto/GrantStaffRequest.java:12-22`

**Issue:** The only validation is `@NotNull` on `userId`/`role` and the GROUP_ADMIN-must-be-
tenant-wide rule (line 118-121). Missing:

- **`shopId` tenant ownership.** `shop_staff.shop_id REFERENCES shops(id)` — PostgreSQL
  referential-integrity checks bypass RLS, so the FK accepts *another tenant's* shop id. A grant
  naming a foreign shop is written successfully. Downstream, that id lands in the user's
  `grantedShopIds()` and is passed to `findByShopIdIn`, where RLS filters it out — so there is no
  data leak, but the operator gets a silent "successful" grant that confers nothing, with no
  error and no way to tell why the user sees no data.
- **`userId` directory membership.** The DTO javadoc says the `sub` "MUST already appear in the
  tenant's `user_directory`", but nothing enforces it. Arbitrary UUIDs can be granted.

**Fix:**

```java
if (shopId != null && !shopRepository.existsByIdAndTenantId(shopId, tenantId)) {
    throw new ResourceNotFoundException("Shop not found: " + shopId);
}
if (!userDirectoryRepository.existsById(new UserDirectoryId(tenantId, userId))) {
    throw new ResourceNotFoundException("Unknown user: " + userId);
}
```

---

### WR-06: Two `ShopSwitcher` instances hold independent state — sidebar and mobile bar diverge

**File:** `frontend/components/dashboard/sidebar.tsx:81-84`
**File:** `frontend/components/dashboard/dashboard-shell.tsx:36-38`
**File:** `frontend/components/dashboard/shop-switcher.tsx:28-33,69-73`

**Issue:** `ShopSwitcher` is mounted twice (sidebar + mobile top bar). It keeps `selected` in
local `useState` and — unlike `useShopContext` — never subscribes to `shopcontext:change`. So:

- Changing the shop in one switcher updates `localStorage` and every consumer *page*, but the
  other switcher's own `<select>` keeps displaying the stale value until remount. At tablet
  breakpoints where both can be reachable, the chrome contradicts itself.
- Both instances independently call `fetchMyShops()` on mount → two `GET /api/v1/shops?size=200`
  plus two `getSession()` calls per dashboard load.
- Both run the hydration effect and can both call `setShopContext(next)` (line 57), each
  dispatching a `shopcontext:change` event.

**Fix:** Have `ShopSwitcher` read its selection from `useShopContext()` rather than local state
(the hook already subscribes to both events), and lift the `fetchMyShops()` result into a shared
provider or a cached fetch so it runs once.

---

### WR-07: A malformed CSV `shop_id` returns 403 shop-access-denied instead of 400

**File:** `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:334-345`

**Issue:** `parseShopId` catches `IllegalArgumentException` from `UUID.fromString` and throws
`ShopAccessDeniedException(null, SHOP_MANAGER)`. A typo in a CSV cell is a **validation** error,
not an authorization denial. The importer receives 403 `/shop-access-denied` with a `requiredRole`
property, telling them they lack permission when in fact their file is malformed. The deny-by-
default instinct is right; the status code is wrong, and it violates the machine-parseable-error
contract (a client keying on `/shop-access-denied` will prompt for access it already has).

Note also that `importFromCsv` aborts the **whole batch** on the first bad cell (the gate sits
outside the per-row try/catch, line 99-108) while every other row error is collected into
`result.getErrors()` — inconsistent with the endpoint's documented "returns created products and
per-row errors" contract.

**Fix:** Throw a validation exception mapped to 400 (e.g. `IllegalArgumentException`, already
handled), or better, record it as a `RowError` so it joins the per-row error report:

```java
} catch (IllegalArgumentException e) {
    throw new IllegalArgumentException("Row " + rowNum + ": shop_id is not a valid UUID: " + raw);
}
```

---

### WR-08: Scoped users lose all shop-less products — silent catalogue disappearance

**File:** `core-java/src/main/java/uk/jtoye/core/product/ProductService.java:127-141`
**File:** `core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java:17-21`

**Issue:** `findByShopIdIn(granted, pageable)` matches only rows whose `shop_id` is in the grant
set; `shop_id IS NULL` never matches an `IN` list. Because `Product.shopId` is nullable and was
optional before this phase, every pre-existing product created with the UI's "All Shops" default —
plus everything `importFromImages` creates — has `shop_id = NULL`.

Failure scenario: a tenant's catalogue is entirely legacy (all `shop_id NULL`). A GROUP_ADMIN
grants Bob `SHOP_MANAGER` on shop A. Bob's product list immediately shows **zero products**, with
no explanation and no error. The same products remain visible to the GROUP_ADMIN, so the
inconsistency is confusing to diagnose. The search path (`searchFullTextInShops`) behaves
identically.

This may be the intended deny-by-default reading, but combined with CR-04 (opening one of those
products 500s) it means unassigned products are simultaneously invisible and crash-inducing for
scoped users.

**Fix:** Decide and document the policy for shop-less products. Options: (a) a data migration
assigning legacy products to a shop, with a clear rule for multi-shop tenants; (b) treat
`shop_id IS NULL` as tenant-wide-visible-read for any granted user
(`WHERE shop_id IN (:ids) OR shop_id IS NULL`) while keeping writes GROUP_ADMIN-only; or (c) block
creation of shop-less products going forward (`@NotNull` on `CreateProductRequest.shopId`) and
migrate the backlog. Whichever is chosen, surface it in the UI rather than showing an empty list.

---

### WR-09: Strict-scoping breaks machine/service accounts, and JIT gives them permanent GROUP_ADMIN

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:266-276,298-309`

**Issue:** Keycloak service-account tokens (the MCP server, edge, any `SCOPE_*` client-credentials
client from issue #206) carry a UUID `sub`, so they are **not** treated as system principals and
fall through to the normal membership path. Two consequences:

- With `strict-scoping = false`, `onRequest()` JIT-provisions a **persistent tenant-wide
  GROUP_ADMIN row for each service account** on its first write. These rows then appear in the
  staff screen's grant list as opaque UUIDs with no directory entry (`staff/page.tsx:423`
  falls back to rendering the raw `userId`), and they survive the strict-scoping flip (CR-07).
- With `strict-scoping = true`, service accounts have no grants and no realm `admin`, so **every
  shop-scoped call from the MCP server / integrations 403s**. There is no documented provisioning
  path for a non-human principal, and the staff screen cannot grant to them because they never
  appear in `user_directory` (it is populated from interactive-login JWT claims).

**Fix:** Classify machine principals explicitly (e.g. presence of a `client_id` claim / absence of
`preferred_username`, or a dedicated scope) and route them past the shop gate deliberately —
tenant-RLS-scoped but shop-unscoped — rather than letting them acquire GROUP_ADMIN rows by
accident. Add an integration test covering a client-credentials token under strict-scoping ON.

---

### WR-10: `user_directory` exposes every colleague's email to all users under the default configuration

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java:85-96`
**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:253-260`

**Issue:** `onRequest()` upserts each user's email + display name into `user_directory`, and
`list()` returns the whole tenant's directory to any caller passing `requireGroupAdmin()`. With
`strict-scoping = false` (default), a fully-ungranted user is an implicit GROUP_ADMIN
(`ShopAccessService.java:158`) — and `list()` is `@Transactional(readOnly = true)`, so
`onRequest()` returns early (line 244) and never materialises a JIT row that would scope them.
Such a user therefore passes the gate and reads every colleague's email.

This is consistent with the deliberate day-one "everyone can do everything" posture, but it is a
**new PII surface** created by this phase: before V52 this data did not exist in the platform at
all. FORCE RLS correctly protects it cross-tenant; within a tenant it is broadly readable by
default.

**Fix:** Note the exposure explicitly in the phase's threat model / privacy record, and consider
returning only `displayName` + a masked email in the picker unless the caller holds an explicit
(non-JIT) GROUP_ADMIN grant. Also confirm `user_directory` is covered by the GDPR erasure sweep
(`GdprService`) — it holds email PII and, unlike the other tables, has no `_aud` mirror to scrub.

---

### WR-11: JIT cache eviction fires pre-commit — a concurrent request can cache the pre-insert state

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:269-276`

**Issue:** `evictMembership(sub)` is called inline inside the transaction, immediately after
`insertGroupAdminIfAbsent`, not after commit. `StaffManagementService` gets this right
(`evictAfterCommit`, line 207-218) and its javadoc explains exactly why ("so a re-resolve cannot
race the just-written row") — `onRequest()` does not follow the same rule.

A concurrent request on another thread can, between the evict and the commit, call
`resolveMembership`, read the not-yet-committed state (empty membership), and repopulate the cache
with it. That stale entry then persists for the full 5-minute TTL.

Currently latent because the cache never engages (WR-01), but it becomes live the moment WR-01 is
fixed.

**Fix:** Use the same `TransactionSynchronizationManager.registerSynchronization(...afterCommit)`
pattern as `StaffManagementService.evictAfterCommit`; factor it into a shared helper so both call
sites cannot drift.

---

### WR-12: Staff screen joins identity by email instead of `sub`

**File:** `frontend/app/dashboard/staff/page.tsx:125-146,423`

**Issue:** `isSelf(userId)` resolves the directory email for a `userId` and string-compares it to
`session.user.email`. The authoritative identity is the Keycloak `sub`, which is already the
`userId` on both sides — the email round-trip is unnecessary and fails whenever the NextAuth
session email differs in case or is absent (`sessionEmail` null ⇒ `isSelf` always false, so the
"Removing your own access…" warning at line 400-406 silently never renders). Line 423 similarly
falls back to displaying a raw UUID when a grant has no directory entry (JIT/service-account rows).

**Fix:** Expose the caller's `sub` on the session (or in the `MyAccessDto` from CR-08) and compare
`g.userId === session.sub` directly.

---

## Info

### IN-01: `fetchMyShops` hard-codes `size=200` — silent truncation

**File:** `frontend/lib/shops-api.ts:55`
A tenant with more than 200 shops gets a silently truncated switcher list, and
`shop-switcher.tsx:48` would then misclassify a valid saved selection as stale, showing the
"no longer available — access required" alert for a shop the user *can* access. Page through, or
add a dedicated unpaginated `/api/v1/shops/mine` endpoint.

### IN-02: Last-GROUP_ADMIN 409 copy says "remove" on the grant path

**File:** `frontend/app/dashboard/staff/page.tsx:165-168`
A 409 from `handleGrant` is a *downgrade* refusal, but the message reads "You cannot remove the
last group admin". Use distinct copy per path.

### IN-03: `LastGroupAdminException` message is duplicated verbatim at two call sites

**File:** `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java:128-129,168-169`
The string "Cannot ... the last GROUP_ADMIN in this tenant — grant another GROUP_ADMIN first"
appears twice with a one-word difference. Extract to a constant so the wording cannot drift.

---

## Verified Correct (no action)

Recorded so a re-review does not re-litigate these:

- **V52 RLS** (`V52__shop_staff.sql:46-109`) — `ENABLE` + `FORCE` on all three tables; every
  policy uses `current_tenant_id()`, never a raw `current_setting(...)::uuid` cast (would fail
  `RlsContractTest.noPolicyUsesRawTenantGucCast`); the `_aud` policy correctly admits
  `tenant_id IS NULL` while still tenant-filtering; idempotent DO-block DDL matches house style.
- **Functional unique index** (line 41-42) — `COALESCE(shop_id, zero-uuid)` correctly makes
  tenant-wide grants unique per user, and is a valid `ON CONFLICT` target.
- **Typed error distinctness** — `ShopAccessDeniedException extends RuntimeException` (not
  `AccessDeniedException`), so the specific `@ExceptionHandler` wins over `handleAccessDenied`;
  `/shop-access-denied` (403) is distinct from `/not-found` (404) and `/forbidden` (403).
  The RLS 404 is never blurred with the shop 403.
- **Query-level read-scoping** — `findByShopIdIn`, `findByStatusAndShopIdIn`,
  `findByCustomerIdAndShopIdIn`, `findByTenantIdAndIdIn`, `searchByTenantAndIdIn`,
  `searchFullTextInShops` are all genuine server-side narrowing; no post-hoc in-memory filtering
  on the backend. Empty grant sets short-circuit to `Page.empty` before hitting the DB (an empty
  `IN ()` would otherwise be a syntax error).
- **Orders shop context** — `orders/page.tsx` narrows via `?shopId=`, which
  `OrderController.java:94-99` routes to the gated `getOrdersByShop`. Correct pattern; the model
  the other screens should follow (WR-04).
- **`transitionOrder` chokepoint** (`OrderService.java:377-385`) — gating once at the single
  private method correctly covers all six public transition entry points.
- **Read-only transaction guard** (`ShopAccessService.java:244-246`) — correctly prevents the JIT
  insert from poisoning a read-only transaction; the read decision genuinely does not depend on
  the JIT row being written.
- **`Order.shopId`** is `nullable = false`, so CR-04 does not affect any order path.

---

_Reviewed: 2026-07-20T11:41:50Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: deep_
_Diff base: e21e74b..HEAD (21 commits)_
_Static review only — no build, test, container, or browser execution (low-footprint mode). The `Map.copyOf` null-key behaviour underpinning CR-04 was confirmed by direct JDK execution._
