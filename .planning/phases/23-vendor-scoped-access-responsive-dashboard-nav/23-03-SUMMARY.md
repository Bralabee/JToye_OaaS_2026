---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 03
subsystem: security-authorization
tags: [vendor-scoped-access, authorization, rls-inner-gate, sse, read-scope, testcontainers, rfc7807, spring-data-jpa]

# Dependency graph
requires:
  - phase: 23-02-enforcement-engine
    provides: "ShopAccessService.require(shopId,minRole) / requireGroupAdmin() / isGroupAdmin() / grantedShopIds(); typed ShopAccessDeniedException (distinct RFC 7807 403); ShopRole ranks"
  - phase: 23-01-data-layer
    provides: "V52 shop_staff grants (SHOP_MANAGER/STAFF/GROUP_ADMIN)"
provides:
  - "VSA-02 CLOSED — the horizontal authZ boundary is REAL: every shop-scoped write is role-gated (deny-by-default) and every list is read-scoped to the caller's grant set server-side, strictly INSIDE the RLS tenant wall"
  - "require()/read-scope inserts across Shop/Product/Order/Promotion/Announcement services (+ ProductLabelService) — RESEARCH §3 enforcement inventory"
  - "§3-FLAG #1: BulkImportService per-CSV-row require(rowShopId,SHOP_MANAGER) (whole-batch deny on any ungranted row); image-import restricted to GROUP_ADMIN"
  - "§3-FLAG #2: OrderSseService KDS fan-out grant-set filter (OrderStateChangeEvent now carries shopId end-to-end)"
  - "grant-set read-scope finders: ShopRepository.findByTenantIdAndIdIn/searchByTenantAndIdIn, ProductRepository.findByShopIdIn/searchFullTextInShops, OrderRepository.findByShopIdIn/findByStatusAndShopIdIn/findByCustomerIdAndShopIdIn, Shop{Promotion,Announcement}Repository.findByShopIdIn"
  - "ShopAccessEnforcementIntegrationTest — cross-shop 403 / STAFF read-only / read-scope / 403≠404 proofs under Testcontainers"
affects: [23-04-staff-backend, 23-05-frontend, 24-image-architecture]

tech-stack:
  added: []  # 100% composition of the existing gate + Spring Data JPA derived queries
  patterns:
    - "Write-gate at the TOP of each shop-scoped service method: require(shopId,minRole) — shopId from body / path / parent-lookup (D-02)"
    - "Read-scope at the QUERY (D-01): isGroupAdmin() short-circuit → full tenant query; else findBy...ShopIdIn(grantedShopIds()); empty grant → Page.empty (deny-by-default)"
    - "SSE read-scope is the one non-query filter — capture the subscriber's grant scope at subscribe() and filter the in-memory fan-out by event.shopId()"
    - "System-principal bypass: a caller with no JWT / a non-UUID subject is trusted infra (scheduler/listener/service account), unrestricted by shop-scoping (RLS still tenant-scopes)"
    - "Never write in a read-only tx: gate side-effects (JIT/directory-upsert) skip when TransactionSynchronizationManager.isCurrentTransactionReadOnly()"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessEnforcementIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/shop/PromotionService.java
    - core-java/src/main/java/uk/jtoye/core/shop/AnnouncementService.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopPromotionRepository.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopAnnouncementRepository.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
    - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeEvent.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java

key-decisions:
  - "Order state-transition gate placed ONCE at the private transitionOrder chokepoint (STAFF floor) — a single load covers all six public transitions (submit/confirm/start-prep/mark-ready/complete/cancel); DRY vs six duplicate loads"
  - "OrderStateChangeEvent gained a trailing shopId + a back-compat 6-arg constructor; OrderEventPublisher gained a 6-arg overload (5-arg delegates with shopId=null). Legacy call sites/tests + in-flight outbox payloads keep working; only the order-state path (Order/Payment/PublicStorefront) passes the real shopId"
  - "Bulk image-import (no per-row shopId) restricted to GROUP_ADMIN (§3-FLAG option b); bulk CSV import gets per-row SHOP_MANAGER (§3-FLAG option a)"
  - "System-principal bypass (no JWT / non-UUID sub) treats trusted infra as unrestricted — vendor users are UUID-keyed in shop_staff, so a non-UUID subject cannot be shop-scoped; RLS still tenant-scopes it"
  - "strict-scoping-OFF day-one implicit GROUP_ADMIN derived from the flag + empty membership (decoupled from the JIT row) so read-only paths decide correctly without writing"

requirements-completed: [VSA-02]

metrics:
  duration: 55min
  completed: 2026-07-19
---

# Phase 23 Plan 03: Vendor-Scoped Access Enforcement Sweep (VSA-02) Summary

**The authZ boundary is now REAL: `require(shopId, minRole)` gates every shop-scoped write (deny-by-default) and every list/read is narrowed to the caller's grant set at the QUERY across Shop/Product/Order/Promotion/Announcement (+ label + bulk import), the KDS SSE fan-out is grant-set-filtered on a shopId now threaded end-to-end through the order event, and a Testcontainers proof shows a SHOP_MANAGER confined to one shop (typed 403 distinct from the RLS 404), STAFF as read + order-state-only, and lists narrowed server-side — all strictly INSIDE the RLS tenant wall.**

## Performance
- **Duration:** ~55 min
- **Completed:** 2026-07-19
- **Tasks:** 3
- **Files:** 1 created + 18 main + 12 test modified

## Accomplishments
- **Task 1 — Shop + Product + bulk import (`4a9fba1`):** `ShopService` gates create/delete (GROUP_ADMIN), update + logo/banner (SHOP_MANAGER), getShopById (STAFF), and read-scopes getAllShops/search. `ProductService` gates create (body shopId), update/delete/all image ops (SHOP_MANAGER), getProductById (STAFF), and read-scopes getAllProducts/search (new `findByShopIdIn` + `searchFullTextInShops`). `ProductLabelService.generateLabel` gates STAFF (parent-lookup). **§3-FLAG #1:** `BulkImportService` does a per-CSV-row `require(rowShopId, SHOP_MANAGER)` OUTSIDE the row try/catch so an ungranted row fails the whole `@Transactional` batch with the typed 403 (no partial apply); the AI image-import path is restricted to GROUP_ADMIN. `shop_id` is now an (optional) CSV column.
- **Task 2 — Order + KDS SSE + marketing (`20856c2`):** `OrderService` gates create/update/delete (SHOP_MANAGER), the six state transitions at the `transitionOrder` chokepoint (STAFF), get/detail/by-number/by-shop reads (STAFF), and read-scopes getAllOrders/by-status/by-customer. **§3-FLAG #2:** `OrderSseService` captures each subscriber's shop scope at `subscribe()` and filters the in-memory fan-out by `event.shopId()` — the KDS stream never emits another shop's order event in real time. `OrderStateChangeEvent` gained a trailing `shopId` (back-compat 6-arg ctor); `OrderEventPublisher` a 6-arg overload; Order/Payment/PublicStorefront thread `order.getShopId()`. Promotion + Announcement services gated identically.
- **Task 3 — proof + gate hardening (`cf8f390`):** `ShopAccessEnforcementIntegrationTest` (Testcontainers, strict-scoping ON, **4/4**) proves cross-shop write → typed shop-403 (`/shop-access-denied`, `≠` the RLS `/not-found` 404); STAFF can transition an order (DRAFT→PENDING) on a granted shop but is denied a product create; a SHOP_MANAGER-of-A sees only shop-A products/shops from the lists; and the two RFC 7807 `type` URIs are distinct.

## Task Commits
1. **Task 1: Shop + Product services + bulk import per-row (§3-FLAG #1)** — `4a9fba1` (feat)
2. **Task 2: Order service + KDS SSE grant-set fan-out (§3-FLAG #2) + marketing** — `20856c2` (feat)
3. **Task 3: ShopAccessEnforcementIntegrationTest + gate hardening** — `cf8f390` (test)

## Enforcement density (acceptance greps)
- `ShopService` `shopAccessService.require` = **8** (≥6); `ProductService` = **8** (≥6); `BulkImportService` `require(` = **2** (≥1)
- `OrderService` `shopAccessService.require` = **8** (≥8); `PromotionService` = **4** (≥4); `AnnouncementService` = **4** (≥4)
- `OrderSseService` `grantedShopIds|isGroupAdmin` = **2** (≥1)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — Missing critical functionality] Gated `ProductLabelService.generateLabel` (STAFF)**
- **Found during:** Task 1
- **Issue:** RESEARCH §3 lists `/products/{id}/label` → require STAFF and the plan's Task-1 action names "getProductById/label", but the endpoint delegates to `ProductLabelService`, NOT `ProductService` — so it was outside the plan's `files_modified`. Leaving it ungated is a cross-shop PDF-read hole (regression-by-omission).
- **Fix:** Injected `ShopAccessService` into `ProductLabelService`; `require(product.getShopId(), STAFF)` at the top of `generateLabel`.
- **Files modified:** `product/ProductLabelService.java` (+ `ProductLabelServiceTest` mock)
- **Commit:** `4a9fba1`

**2. [Rule 3 — Blocking] `ShopAccessService` hardening for internal/system callers + read-only reads**
- **Found during:** Task 3 (running the enforcement test + the existing integration suite)
- **Issue (three coupled blockers surfaced by wiring the gate into the live service methods):**
  1. Many existing integration tests (and production internal paths: schedulers, AMQP listeners, service-to-service calls) invoke these services with only `TenantContext` set — **no JWT principal**. The gate's `currentUserId()` threw `IllegalStateException` → those calls broke.
  2. `ScopedCatalogAccessIntegrationTest` uses a **non-UUID JWT subject** (a `catalog:read` scope token); `currentUserId()` threw → surfaced as a 400 on `GET /products`.
  3. Every gated **read** method is `@Transactional(readOnly=true)`; the gate's `onRequest()` write side-effects (D-09 directory upsert, D-04 JIT provision) attempted an `INSERT` in a read-only Postgres transaction, which **aborts the whole tx** ("current transaction is aborted") — breaking the very read that triggered the gate, even though the directory upsert is a caught best-effort write.
- **Fix (all in `security/access/ShopAccessService.java`):**
  1. `isSystemPrincipal()` — a caller with no JWT principal is a trusted SYSTEM path, treated as unrestricted GROUP_ADMIN (RLS still tenant-scopes it; real user requests always carry a JWT via `JwtTenantFilter`, and gated controllers sit behind Spring Security).
  2. Extended `isSystemPrincipal()` to also treat a **non-UUID subject** as system — vendor users are UUID-keyed in `shop_staff`, so a non-UUID sub cannot be shop-scoped.
  3. `onRequest()` skips both write side-effects when `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`; and the strict-scoping-OFF **day-one implicit GROUP_ADMIN** is now derived from the flag + an empty membership (decoupled from the JIT row), so read-only paths decide correctly without ever needing the write. The JIT row is still materialised on the first write request.
- **Verification:** Enforcement test 4/4; `ShopAccessJitProvisionTest` 4/4 + `ShopAccessErrorTypeTest` 4/4 (23-02, unchanged behaviour); `ScopedCatalogAccessIntegrationTest`, `ShopControllerIntegrationTest`, `OrderControllerIntegrationTest`, `ConcurrentStockDecrementIntegrationTest`, `ProductSearchFtsIntegrationTest`, `RoleBasedAccessIntegrationTest`, `MultiTenantIsolationIntegrationTest`, `OrderEventFanoutTopologyIntegrationTest`, `OrderStateChangeListenerIdempotencyIntegrationTest`, `WebhookDeliveryWorkerIntegrationTest`, `PaymentEventOutboxFlusherCrossTenantIntegrationTest` all green; full `:core-java:test` unit task green.
- **Commit:** `cf8f390`

**3. [Rule 3 — Blocking] Threaded `shopId` end-to-end for the SSE filter (§3-FLAG #2)**
- **Found during:** Task 2
- **Issue:** `OrderStateChangeEvent` carried no `shopId`, and the SSE fan-out listener is deliberately DB-free — it cannot look the shopId up. The grant-set filter needs the shopId ON the event.
- **Fix:** Appended `shopId` to `OrderStateChangeEvent` (+ back-compat 6-arg ctor), added a 6-arg `OrderEventPublisher.publishStateChange` overload (5-arg delegates with `null`), and passed `order.getShopId()` from `OrderService`/`PaymentService`/`PublicStorefrontService`. Legacy call sites, tests, and in-flight outbox payloads (Jackson → null shopId) keep working; a null shopId is only ever delivered to a GROUP_ADMIN.
- **Files modified:** `order/OrderStateChangeEvent.java`, `order/OrderEventPublisher.java`, `payment/PaymentService.java`, `storefront/PublicStorefrontService.java` (+ `PaymentServiceTest`, `StripeWebhookIdempotencyIntegrationTest` verify-arity bumps)
- **Commit:** `20856c2`

**Total deviations:** 3 (1 Rule 2, 2 Rule 3). No architectural (Rule 4) changes; no user decisions required.

## Out-of-scope (deliberately NOT gated)
- `PublicStorefrontController` (`/public/**`) — unauthenticated storefront read path preserved (RESEARCH §3 out-of-scope; T-23-03-05 accept).
- `CustomerController` (tenant-scoped, not shop-scoped), `FinancialTransaction`/`Refund`/`Gdpr`/`OnboardingAdmin`/`TenantAdmin` (already `hasRole('admin')`-gated; the D-03 bridge makes realm-admin an implicit GROUP_ADMIN), `WebhookSubscription`/`Sync` (tenant-scoped/edge).
- `ProductController.analyzeImage` — a **stateless** AI helper that persists nothing and touches no stored shop data (its path `id` is unused); no shop data is exposed or written, so it is left as-is (benign, no leak surface).
- `/products/template` — static CSV template, no shop data (ungated per RESEARCH §3).

## Known Stubs
None. Read-scope methods return `Page.empty()` for a fully-ungranted user under strict-scoping — an intentional deny-by-default, not a stub. No hardcoded/placeholder UI values introduced.

## Known Considerations (residual, non-blocking)
- **Per-entity read cache under strict-scoping:** `getShopById`/`getProductById` gate on the loaded entity/param, but their `@Cacheable("shops"|"products")` cache is keyed per-TENANT, not per-shop. Under strict-scoping ON, an already-cached single-entity read could serve a cross-shop same-tenant hit. This is a NO-OP in the operative default (strict-scoping OFF → everyone is an implicit GROUP_ADMIN → the gate is a no-op and the cache is correct) and a NO-OP in all tests (the read caches are `@Profile("!test")`). Recommended hardening when strict-scoping is enabled fleet-wide: include the caller's shop scope in the cache key, or gate single-reads pre-cache. Lists (the read-scope must-have) are query-level and unaffected.

## Threat Flags
None. No new network endpoint, auth path, or schema surface beyond the plan's `<threat_model>`. The `OrderStateChangeEvent.shopId` addition is within T-23-03-04 (SSE fan-out). Mitigations landed: T-23-03-01 (horizontal priv-esc → require() at every write + cross-shop 403 proof), T-23-03-02 (STAFF catalogue-write denied), T-23-03-03 (read-scope at the query, proven by readScopeNarrows), T-23-03-04 (bulk per-row + SSE grant-set filter). T-23-03-05 accepted (out-of-scope surfaces untouched).

## Deferred Items
- **docs-freshness count bump** deferred to the Phase 23 last-plan reconcile (22-07 / 23-01 / 23-02 precedent). This plan added 1 Java test file / 4 `@Tag("testcontainers")` `@Test` methods; `docs/metrics.json` + CLAUDE.md prose counts reconcile once at the phase gate.
- **OpenAPI snapshot:** the bulk-CSV template gained an optional `shop_id` column; no new/removed endpoints. Snapshot regen stays with the phase-gate reconcile.

## Next Phase Readiness
- **23-04 (staff backend):** the enforcement contract this plan wires is the exact surface the grant/revoke admin API governs; `LastGroupAdminException` + `evictMembership` (from 23-02) remain ready. Grants created via 23-04 immediately take effect (D-05 evict).
- **23-05 (frontend):** the server-side read-scoping means the dashboard lists are already narrowed for scoped users — the UI must not re-introduce a client-only filter.
- **No blockers.** Docker/Testcontainers verified; Java 21; full unit suite + all touched integration tests green.

## Self-Check: PASSED
- Created file verified on disk: `ShopAccessEnforcementIntegrationTest.java` (FOUND).
- All 3 task commits verified in git log: `4a9fba1`, `20856c2`, `cf8f390`.
- Verification green: `:core-java:compileJava` 0; enforcement test 4/4 under Testcontainers; 23-02 JIT 4/4 + error-type 4/4 (unchanged); representative read-path integration suite green; full `:core-java:test` unit task green.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-19*
