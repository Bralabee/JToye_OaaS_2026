---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 10
subsystem: auth
tags: [caching, redis, spring-cache, rls, postgres, testcontainers, vendor-scoped-access, product-catalogue]

requires:
  - phase: 23 (23-02)
    provides: ShopAccessService.require(shopId, minRole) gate + typed shop-403; TenantAwareCacheKeyGenerator (tenant-only key)
  - phase: 23 (23-03)
    provides: shop-scoped reads narrowed to grantedShopIds across Shop/Product/Order
  - phase: 23 (23-08)
    provides: require(null, role) → typed 403 (null-shop WRITE half = GROUP_ADMIN-only); fail-closed principal (build on it — do not revert)
provides:
  - "CR-01 closed: getShopById/getProductById run the shop-access gate on EVERY call, outside the @Cacheable boundary — a warm per-tenant cache entry can no longer be served to a different out-of-grant user"
  - "Caching relocated (not deleted) onto dedicated ShopCacheLoader/ProductCacheLoader beans reached through the Spring proxy — cache key + all 13 evictions unchanged"
  - "WR-08 closed (null-shop READ half, pairs with 23-08 WRITE half): scoped users see legacy shop_id IS NULL products in lists, search, by-id and the PPDS label route; zero-grant users still see nothing"
  - "WR-07 closed: a malformed CSV shop_id is a per-row validation error (400/row), not a ShopAccessDeniedException (403)"
  - "Caching-ENABLED two-scoped-user regression proof that defeats the @Profile(\"!test\") blindness"
affects: [23-15 (phase gate — VSA-02 closure evidence + metrics/docs reconcile)]

tech-stack:
  added: []
  patterns:
    - "Split @Cacheable from an authorization gate by extracting the cached load onto a SEPARATE bean (nested @Component) — the gate runs on every call in the public method, the load stays cached; crossing the proxy makes the caching interceptor fire (not a self-invocation, WR-01)"
    - "Keep the cached method NAME identical after extraction so TenantAwareCacheKeyGenerator produces the same key and existing TenantCacheEvictor call sites (methodName arg) stay valid with zero edits"
    - "An `OR shop_id IS NULL` read branch must be EXPLICITLY tenant-scoped with load-bearing parentheses (tenant_id = :tid AND (shop_id IN (:ids) OR shop_id IS NULL)) — the null branch is not implicitly tenant-local the way an IN(grant set) is, so RLS-bypass (table-owner connection) would otherwise leak cross-tenant null-shop rows"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessCacheBypassIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
    - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/product/BulkImportServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessEnforcementIntegrationTest.java

decisions:
  - "Self-invocation trap avoided via a dedicated cached-loader bean (nested public static @Component), NOT a @Lazy self-reference — obviously correct, no proxy trickery, and keeps @Cacheable textually inside the service file"
  - "Null-shop READ policy: reads of a shop_id IS NULL product are tenant-wide-visible to any granted scoped user (pairs with 23-08's GROUP_ADMIN-only WRITE half) — surfaced for user acceptance"
  - "Null-shop finders are explicitly tenant-scoped (defense-in-depth beyond RLS), mirroring ShopRepository.findByTenantIdAndIdIn"

metrics:
  duration: ~45m
  tasks: 3
  files: 10
  completed: 2026-07-20
---

# Phase 23 Plan 10: Cache-Bypass Gate Fix (CR-01) + Null-Shop Reads (WR-08) + CSV Validation (WR-07) Summary

Moved the shop-access gate outside the `@Cacheable` boundary so a warm per-tenant read cache can
no longer serve a shop/product to an out-of-grant user (CR-01), restored legacy `shop_id IS NULL`
product visibility for scoped users (WR-08), and turned a malformed-CSV `shop_id` from a 403 into a
per-row 400 (WR-07) — proven by a caching-ENABLED, two-scoped-user Testcontainers test that defeats
the `@Profile("!test")` blindness and was demonstrated RED before the fix.

## What Changed

**Task 1 — gate outside the cache (CR-01).** `ShopService.getShopById` /
`ProductService.getProductById` no longer carry `@Cacheable`. The cached by-id load was extracted
onto dedicated `ShopCacheLoader` / `ProductCacheLoader` beans (nested `public static @Component`).
The public methods now run `shopAccessService.require(...)` on **every** invocation and only then
delegate to the cached loader — so the authorization decision is re-evaluated on a cache HIT, and
`TenantAwareCacheKeyGenerator`'s user-agnostic per-tenant key can no longer poison the entry for
other tenant users. Because the loader is a **separate bean**, the call crosses the Spring proxy and
the caching interceptor genuinely fires (this is deliberately not a self-invocation, WR-01). The
cached method names were kept (`getShopById` / `getProductById`), so the cache key and all 13
`TenantCacheEvictor.evictEntity(...)` call sites are byte-for-byte unchanged.

**Task 2 — null-shop reads (WR-08) + CSV validation (WR-07).** Scoped users now see legacy
`shop_id IS NULL` products in `getAllProducts`, full-text `search`, `getProductById`, and the PPDS
`/label` route (the gate is skipped for a null-shop product — RLS still confines it to the tenant).
The new finders are explicitly tenant-scoped. A malformed CSV `shop_id` is now a per-row validation
error and the batch continues, instead of throwing `ShopAccessDeniedException`.

**Task 3 — the proof.** `ShopAccessCacheBypassIntegrationTest` supplies its own `CacheManager` +
`tenantAwareCacheKeyGenerator` via a nested `@EnableCaching @TestConfiguration`, so caching is truly
on under `@ActiveProfiles("test")`. Two different scoped principals (userX on shop A, userY on shop
B); userX reads and populates the cache, then userY reads the same cached shop/product and is denied.

## Pre-fix RED Evidence (falsifiability gate)

The new test was run against the **pre-fix** source (`git checkout HEAD --` on the four
service/unit-test files, new test file left in place; cache key is identical pre/post because the
cached method names were preserved):

```
> Task :core-java:integrationTest
ShopAccessCacheBypassIntegrationTest > warmCacheDoesNotBypassShopGate()    FAILED
    java.lang.AssertionError: Expecting code to raise a throwable.   (line 162 — the userY shop-denial assertion)
ShopAccessCacheBypassIntegrationTest > warmCacheDoesNotBypassProductGate() FAILED
    java.lang.AssertionError: Expecting code to raise a throwable.   (line 194 — the userY product-denial assertion)
4 tests completed, 2 failed
```

Both failures are exactly "the out-of-grant second user received the cached DTO **instead of** a
denial" — the live bypass. Crucially, in the SAME RED run
`cachingIsActuallyEnabledInThisContext()` and `authorizedCallerStillServedFromCache()` **PASSED**,
proving caching was genuinely enabled (so the two failures are the real bypass, not a "caching off"
artifact). After Tasks 1–2 the same file is 4/4 green.

## Self-Invocation Trap — approach used, and the proof it worked

Approach chosen: **dedicated cached-loader bean** (nested `public static @Component`
`ShopCacheLoader` / `ProductCacheLoader`), not a `@Lazy` self-reference. This is obviously correct
(no proxy trickery), and — because the nested `@Component` lives inside the service source file —
`@Cacheable` also remains textually in `ShopService.java`/`ProductService.java` (grep counts 5 and
3), so caching is provably relocated, not deleted.

The Task-3 cache-population assertions are the proof the proxy actually fires: `cachingIsActually…`
and `authorizedCallerStillServedFromCache` passed post-fix, and cases 1 & 2 assert
`cacheManager.getCache("shops"/"products").get("tenant:{tid}:getShopById|getProductById:{id}")` is
non-null **before** asserting the denial. A self-invocation mistake would have left the cache empty
and failed those assertions.

## Null-Shop READ Policy — explicit decision for user acceptance

This plan implements the **READ half** of the null-shop policy whose **WRITE half** shipped in
**23-08**:

- **WRITE (23-08):** writes to a `shop_id IS NULL` (tenant-wide/unassigned) resource are
  **GROUP_ADMIN-only** — a scoped caller gets the typed 403.
- **READ (this plan, 23-10):** reads of a `shop_id IS NULL` resource are **tenant-wide-visible to any
  granted scoped user**.

Rationale (Incremental Betterment): before Phase 23, every product created via the UI "All Shops"
default and everything `importFromImages` created carries `shop_id = NULL`. After 23-03's read
narrowing, a scoped user's catalogue silently showed **ZERO** of them (WR-08) while opening one
threw (CR-04) — simultaneously invisible and crash-inducing. Making them readable restores the
pre-phase visibility; RLS still confines them to the tenant, and the write side stays GROUP_ADMIN-only.
Zero-grant users still see nothing (`zeroGrantUser_seesEmpty_evenWhenTenantWideProductsExist`), so
deny-by-default did not widen. **If you would rather legacy null-shop products stay hidden from
scoped users, this is the decision to reject.**

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 – Bug] Null-shop read finders must be explicitly tenant-scoped**
- **Found during:** Task 2 (the pre-existing `readScopeNarrows` enforcement test went 2 → 4).
- **Issue:** The plan's `WHERE shop_id IN (:ids) OR shop_id IS NULL` is safe under production
  FORCE-RLS + a non-owner role, but the Testcontainers connection runs as the **table owner**, which
  bypasses the (non-FORCE) `products_rls_policy`. With RLS bypassed, the `OR shop_id IS NULL` branch
  matched other tenants' null-shop rows (unlike `shop_id IN (grant set)`, whose UUIDs are
  tenant-local). Relying on RLS alone for the null branch is exactly the leak `ShopRepository`
  already guards against with an explicit tenant filter.
- **Fix:** Made both new product finders explicitly tenant-scoped with load-bearing parentheses —
  `findTenantScopedInGrantSetOrTenantWide` (JPQL) and `searchFullTextInGrantSetOrTenantWide` (native,
  `p.tenant_id = :tenantId AND (p.shop_id IN (:ids) OR p.shop_id IS NULL) AND ...`); `ProductService`
  now passes the tenant id. Defense-in-depth beyond RLS, consistent with the existing repository.
- **Files modified:** ProductRepository.java, ProductService.java
- **Commit:** 7d60884

### Approach note (not a plan deviation)

The plan named a `@Component` collaborator as the preferred way to avoid the self-invocation trap and
`@Lazy` self-reference as the alternative. I used a **nested** `public static @Component` inside each
service file, which is the collaborator approach AND keeps `@Cacheable` in the service file (so the
`grep -c "@Cacheable" ShopService.java >= 1` acceptance criterion is met without a `@Lazy`
self-reference). The nested beans are component-scanned and proxied — proven by the Task-3
cache-population assertions.

## Deferred Issues

- **Metrics / docs reconcile + OpenAPI snapshot** are NOT updated here (this plan adds test methods
  and one integration class but no new endpoints). Consistent with the phase's existing handoff, the
  `docs/metrics.json` count reconcile (`scripts/docs-freshness.sh --write`) and
  `./gradlew :core-java:updateOpenApiSnapshot` are deferred to the phase gate (23-15) / phase PR.
- The GROUP_ADMIN tenant-wide `searchFullText` / `findAll` paths remain RLS-only (unchanged
  pre-existing behavior) — not in this plan's footprint.

## Verification

- `./gradlew :core-java:integrationTest --tests "*ShopAccessCacheBypassIntegrationTest"` — **4/4 green**
  (RED demonstrated pre-fix on cases 1 & 2).
- `./gradlew :core-java:integrationTest --tests "*ShopAccessEnforcementIntegrationTest"` — **6/6 green**
  (4 pre-existing VSA-02 cases + 2 new WR-08 cases; not regressed).
- `./gradlew :core-java:test` — **BUILD SUCCESSFUL** (full unit suite; includes the new WR-07 bulk-import
  case and the rewired ShopService/ProductService unit tests).
- Cache-population assertions present and passing — the proof is not green-by-construction.

## Self-Check: PASSED

- `core-java/.../ShopAccessCacheBypassIntegrationTest.java` — FOUND (335 lines, ≥150 required)
- `ProductRepository.findTenantScopedInGrantSetOrTenantWide` (grant-set + tenant-wide null-shop finder) — FOUND
- `require(shopId, ShopRole.STAFF)` in ShopService.java outside any `@Cacheable` boundary — FOUND (line 109)
- Commits e2aa1f2 (Task 1), 7d60884 (Task 2), a89c326 (Task 3) — all FOUND in git log
- No tracked files deleted across the three commits
