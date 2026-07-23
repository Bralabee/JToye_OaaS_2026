---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 02
subsystem: security-authorization
tags: [spring-security, spring-cache, redis, rfc7807, jit-provision, testcontainers, multi-tenancy, authorization]

# Dependency graph
requires:
  - phase: 23-01-data-layer
    provides: "ShopStaffRepository (insertGroupAdminIfAbsent race-safe / existsByTenantIdAndUserId / countByTenantIdAndRole / findByTenantIdAndUserId) + UserDirectoryRepository.upsertSeen (throttled) + ShopRole rank-ordered enum + V52 shop_staff/user_directory RLS tables"
  - phase: "16.1-pre-prod-hardening"
    provides: "GlobalExceptionHandler RFC 7807 ProblemDetail convention (handleAccessDenied /forbidden, handleResourceNotFound /not-found, handleIdempotencyConflict 409)"
  - phase: "issue-83-rbac"
    provides: "KeycloakRealmRoleConverter (realm admin → ROLE_admin authority) — the D-03 implicit-GROUP_ADMIN bridge reads this"
provides:
  - "ShopAccessService — the single in-tenant enforcement seam: require(shopId,minRole) / requireGroupAdmin() / isGroupAdmin() / grantedShopIds() / resolveMembership(userId) / evictMembership(userId)"
  - "Membership value record (isGroupAdmin + immutable perShopRole map) — the cached decision source"
  - "ShopAccessDeniedException → distinct RFC 7807 403 type https://jtoye.uk/errors/shop-access-denied (NOT AccessDeniedException; carries shopId+requiredRole props)"
  - "LastGroupAdminException → 409 type https://jtoye.uk/errors/last-group-admin (typed contract used by 23-04)"
  - "D-12 strict-scoping config flag + D-09 directory-upsert throttle interval (jtoye.access.*, safe defaults, all profiles)"
  - "shopMembership Redis cache (5-min TTL backstop) evictable per-user for immediate revocation (D-05)"
affects: [23-03-enforcement-sweep, 23-04-staff-backend]

# Tech tracking
tech-stack:
  added: []  # no new external dependencies — 100% composition of existing Spring Security / Spring Cache / Redis / Testcontainers
  patterns:
    - "JIT lazy-provision + throttled directory upsert live INSIDE the @Transactional service (Pitfall 4), entered on first require()/grantedShopIds() — NOT in JwtTenantFilter (no tx/GUC there)"
    - "Distinct RFC 7807 type per trust-signal: shop-403 (/shop-access-denied) ≠ RLS-404 (/not-found) ≠ generic-403 (/forbidden) — never blur the tenant boundary"
    - "Realm-admin ⇒ implicit GROUP_ADMIN by reading the existing ROLE_admin authority (never re-parsing realm_access)"
    - "Config-injected policy switch (strict-scoping) via @Value with a safe default, toggled in tests via ReflectionTestUtils on the AopTestUtils-unwrapped target"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/exception/ShopAccessDeniedException.java
    - core-java/src/main/java/uk/jtoye/core/exception/LastGroupAdminException.java
    - core-java/src/main/java/uk/jtoye/core/security/access/Membership.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessJitProvisionTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessErrorTypeTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/resources/application.yml
    - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java

key-decisions:
  - "ShopAccessDeniedException extends RuntimeException, NOT AccessDeniedException — so the generic handleAccessDenied (/forbidden) does NOT swallow it; the dedicated handler emits the distinct /shop-access-denied type (T-23-02-03)"
  - "JIT + directory upsert placed inside ShopAccessService.onRequest() (first enforcement call of the request), NOT JwtTenantFilter — the filter has no active transaction and no pinned tenant GUC (RESEARCH Pitfall 4)"
  - "grantedShopIds() returns an EMPTY set as the GROUP_ADMIN 'unrestricted' sentinel; callers MUST short-circuit on isGroupAdmin() (documented) — a GROUP_ADMIN has no finite id set to filter by"
  - "directory upsert is best-effort (try/catch, throttled by cutoff) — a directory write must never fail a real request (D-09)"
  - "VSA-01 CLOSED here (JIT bridge + realm-admin bridge + JIT idempotency test complete the 23-01 data layer); VSA-02 LEFT PENDING (the enforcement engine is built, but the require() sweep across shop/product/order/marketing services is 23-03 — closing it now would be a false-green, mirrors 23-01's VSA-01 discipline)"

patterns-established:
  - "shopMembership cache keyed tenant:{tid}:resolveMembership:{sub} (tenant-isolated by the key generator); evict-on-write via TenantCacheEvictor.evictEntity for D-05 immediate revocation"
  - "Testcontainers concurrency proof for ON CONFLICT DO NOTHING: two threads each set their own SecurityContext + TenantContext, race a real @Transactional service call, assert exactly one committed row"

requirements-completed: [VSA-01]  # VSA-02 pending — enforcement engine ready; require() sweep is 23-03

# Metrics
duration: 7min
completed: 2026-07-19
---

# Phase 23 Plan 02: Vendor-Scoped Access Enforcement Engine + Typed Errors Summary

**ShopAccessService is now the single in-tenant authorization seam — require/requireGroupAdmin/isGroupAdmin/grantedShopIds over a per-user shopMembership cache, the realm-admin⇒GROUP_ADMIN bridge, race-safe JIT lazy-provision (D-04) + throttled directory upsert (D-09) inside the transaction, and the D-12 strict-scoping off-ramp — backed by two typed RFC 7807 errors: a shop-403 type provably distinct from the RLS 404 and the generic 403, plus a last-GROUP_ADMIN 409.**

## Performance

- **Duration:** ~7 min
- **Started:** 2026-07-19T10:34:06Z
- **Completed:** 2026-07-19T10:40Z
- **Tasks:** 3
- **Files:** 6 created + 3 modified

## Accomplishments
- **Typed error contract (Task 1)** — `ShopAccessDeniedException` (a `RuntimeException`, deliberately NOT `AccessDeniedException`, carrying nullable `shopId` + `requiredRole`) mapped by a dedicated `handleShopAccessDenied` to a **distinct** 403 `type` `https://jtoye.uk/errors/shop-access-denied` with machine-parseable `shopId`/`requiredRole` properties; `LastGroupAdminException` → 409 `.../last-group-admin` (mirrors the idempotency 409). The RLS 404 (`/not-found`) and generic 403 (`/forbidden`) handlers are untouched. Config keys `jtoye.access.strict-scoping` (D-12, default OFF) + `directory-upsert-interval` (D-09, default `PT1H`) added under the existing `jtoye:` block with env-overridable safe defaults.
- **Enforcement engine (Task 2)** — `ShopAccessService` (`@Service @Transactional`) composes: a `@Cacheable("shopMembership")` per-user resolver (tenant-isolated key), the D-03 realm-admin bridge (reads the existing `ROLE_admin` authority), D-04 race-safe JIT GROUP_ADMIN provision + D-09 throttled directory upsert placed **inside** the transactional service (`onRequest()`, Pitfall 4 — not the filter), the D-12 strict-scoping guard, and `evictMembership` for 23-04's D-05 immediate revocation. `Membership` is an immutable value record. `shopMembership` registered in `CacheConfig` with a 5-min TTL backstop.
- **Proof (Task 3)** — `ShopAccessJitProvisionTest` (Testcontainers, **4/4**): two concurrent first-requests → **exactly one** GROUP_ADMIN row (ON CONFLICT DO NOTHING); strict-scoping OFF preserves day-one auto-provision; strict-scoping ON denies an ungranted non-admin with the typed 403 and provisions nothing; a realm-admin is an implicit GROUP_ADMIN with no `shop_staff` row. `ShopAccessErrorTypeTest` (unit, **4/4**): the shop-403 type ≠ RLS-404 type AND ≠ generic-403 type, the props are present, and the 409 carries its own type.

## Task Commits

Each task was committed atomically:

1. **Task 1: Typed errors + GlobalExceptionHandler wiring + config keys** — `3d563f2` (feat)
2. **Task 2: ShopAccessService — membership cache, realm-admin bridge, JIT provision, directory upsert, strict-scoping** — `66b1fc0` (feat)
3. **Task 3: JIT idempotency + strict-scoping + error-type distinctness tests** — `0a555ac` (test)

**Plan metadata:** committed with this SUMMARY + STATE.md + ROADMAP.md + REQUIREMENTS.md (docs: complete plan)

## Files Created/Modified
- `exception/ShopAccessDeniedException.java` — typed shop-scope 403 (RuntimeException, shopId+requiredRole), distinct from the generic 403
- `exception/LastGroupAdminException.java` — typed 409 for the last-GROUP_ADMIN guard (thrown by 23-04)
- `security/access/Membership.java` — `record(boolean isGroupAdmin, Map<UUID,ShopRole> perShopRole)` value type
- `security/access/ShopAccessService.java` — the enforcement seam (require/requireGroupAdmin/isGroupAdmin/grantedShopIds/resolveMembership/evictMembership + JIT + upsert + strict-scoping)
- `common/GlobalExceptionHandler.java` — added `handleShopAccessDenied` (distinct 403) + `handleLastGroupAdmin` (409)
- `resources/application.yml` — `jtoye.access.strict-scoping` (D-12) + `directory-upsert-interval` (D-09)
- `config/CacheConfig.java` — registered `shopMembership` cache (5-min TTL backstop)
- `test/.../ShopAccessJitProvisionTest.java` — Testcontainers JIT idempotency + strict-scoping both directions + realm-admin bridge
- `test/.../ShopAccessErrorTypeTest.java` — unit proof the three type URIs are pairwise distinct

## Decisions Made
- **`ShopAccessDeniedException extends RuntimeException` (not `AccessDeniedException`):** the shape analog `TenantAccessDeniedException` extends `AccessDeniedException` and is caught by the generic `handleAccessDenied` (`/forbidden`). To emit a **distinct** type the new exception must NOT be an `AccessDeniedException` (otherwise the more-general handler could swallow it); a dedicated handler then owns the `/shop-access-denied` type. (RESEARCH §6.)
- **JIT + directory upsert inside the service, not the filter (Pitfall 4):** `JwtTenantFilter` runs before any transaction and before `TenantSetLocalAspect` pins the tenant GUC, so a JDBC write there would run without RLS context. Both side effects hang off `ShopAccessService.onRequest()`, entered on the first `require()`/`grantedShopIds()` where a tx + pinned GUC already exist.
- **`grantedShopIds()` empty-set sentinel for GROUP_ADMIN:** a GROUP_ADMIN reads all shops, so there is no finite id set; the method returns an empty set and callers short-circuit on `isGroupAdmin()` (documented on the method). This keeps the 23-03 read-scope helper simple.
- **VSA-01 closed, VSA-02 left pending (anti-false-green):** VSA-01's remaining acceptance (JIT "backfill", realm-admin bridge, JIT idempotency test) is delivered here on top of 23-01's data layer, so VSA-01 fully closes. VSA-02's engine is built, but its acceptance also requires `require()` inserted across shop/product/order/marketing services + cross-shop 403 proofs — that sweep is 23-03. Marking VSA-02 complete now would be a false-green (mirrors 23-01's VSA-01 decision).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Verify-command path corrected for the repo's build layout + test-tag routing**
- **Found during:** Task 1 (running the plan's `<verify>` command)
- **Issue:** The plan's `<verify>` commands `cd core-java && ./gradlew compileJava` / `... ./gradlew test --tests "*ShopAccessJitProvisionTest" --tests "*ShopAccessErrorTypeTest"` do not run as literally written in this repo: (a) there is no `core-java/gradlew` — the wrapper is at the repo ROOT (multi-project build); and (b) `ShopAccessJitProvisionTest` is `@Tag("testcontainers")`, which the default `test` task **excludes** (`excludeTags("testcontainers")`, build.gradle.kts:99) — it runs only under the dedicated `integrationTest` task. The literal `test --tests "*ShopAccessJitProvisionTest"` would report "No tests found".
- **Fix:** Ran from the repo root: `./gradlew :core-java:compileJava` (both build tasks), `./gradlew :core-java:test --tests "*ShopAccessErrorTypeTest"` (the plain unit test), and `./gradlew :core-java:integrationTest --tests "*ShopAccessJitProvisionTest"` (the Testcontainers test against real Postgres 15). Identical routing deviation to 23-01. No production/code change; verification path only.
- **Files modified:** none (execution-command adjustment)
- **Verification:** compileJava exits 0 (×2); ShopAccessErrorTypeTest 4/4; ShopAccessJitProvisionTest 4/4. Results under `core-java/build-local/test-results/{test,integrationTest}/`.
- **Committed in:** n/a (no file change)

---

**Total deviations:** 1 (Rule 3 blocking — verify-command routing, no code change)
**Impact on plan:** None on deliverables. All intended verification (compileJava + JIT idempotency under real Postgres + error-type distinctness) ran and is green; only the task-invocation path differed from the plan's literal string due to the repo's root wrapper + testcontainers-tag routing.

## Issues Encountered
- **Transactional-proxy field toggling in tests:** the JIT test toggles `strict-scoping` per case. `ShopAccessService` is a CGLIB `@Transactional` proxy, so `@Autowired` yields the proxy; `ReflectionTestUtils.setField` on a proxy would miss the target field. Resolved by unwrapping with `AopTestUtils.getTargetObject(...)` once and setting the field on the real target (a standard Spring-Test idiom). No production change.
- **`@Cacheable` in the `test` profile:** `CacheConfig` (and thus `@EnableCaching` + the `tenantAwareCacheKeyGenerator` bean) is `@Profile("!test")`, so `@Cacheable` is a no-op in tests and the missing key-generator bean is never resolved at startup — exactly as the existing `ShopService.getShopById` relies on. Confirmed the context boots and `evictMembership` degrades to no-op (no CacheManager) safely.

## User Setup Required
None — no external service configuration. The two new config keys (`jtoye.access.strict-scoping`, `directory-upsert-interval`) ship with safe defaults (OFF / `PT1H`) and are env-overridable (`ACCESS_STRICT_SCOPING`, `DIRECTORY_UPSERT_INTERVAL`); no secret rotation.

## Known Stubs
None. `ShopAccessService` is fully wired to the 23-01 repository contracts (`insertGroupAdminIfAbsent`, `existsByTenantIdAndUserId`, `findByTenantIdAndUserId`, `upsertSeen`); no placeholder/empty-value returns. `grantedShopIds()`'s empty-set-for-GROUP_ADMIN is an intentional documented sentinel (consumed by the 23-03 read-scope helper), not a stub.

## Threat Flags
None. This plan adds no new network endpoint, auth path, or schema surface beyond the plan's `<threat_model>` register — `ShopAccessService` is an internal service (no controller); the mitigations for T-23-02-01 (JIT only ever grants the caller's OWN sub a tenant-wide GROUP_ADMIN, never a client-supplied role/shop), T-23-02-02 (strict-scoping ON deny-by-default), T-23-02-03 (distinct type URIs), and T-23-02-04 (throttled upsert) are all implemented and proven by Task 3. T-23-02-05 (evict-after-commit) is wired (`evictMembership`) and exercised fully in 23-04.

## Deferred Items
- **docs-freshness count bump** deferred to the Phase 23 last-plan reconcile (per the 22-07 / 23-01 precedent). This plan added 2 Java test files / 8 test methods (4 unit `@Test` + 4 `@Tag("testcontainers")` `@Test`); `docs/metrics.json` + CLAUDE.md prose counts are reconciled once at the phase gate.

## Next Phase Readiness
- **23-03 (enforcement sweep + UI):** the enforcement contract is ready — `require(shopId, minRole)` / `requireGroupAdmin()` for writes, `grantedShopIds()` + `isGroupAdmin()` for read-scoping, per the RESEARCH §3 endpoint inventory. Insert `require()` at the top of `ShopService`/`ProductService`/`OrderService`/`PromotionService`/`AnnouncementService` methods; handle the two §3-FLAG items (bulk import per-row, SSE fan-out grant-set filter).
- **23-04 (staff backend):** `LastGroupAdminException` (409) + `evictMembership(userId)` (D-05, call AFTER the grant/revoke DB write commits) are ready; `ShopStaffRepository.countByTenantIdAndRole` (from 23-01) backs the last-GROUP_ADMIN guard.
- **No blockers.** Docker/Testcontainers verified working; Java 21; the full Spring context boots cleanly with the new `ShopAccessService` bean + config keys.

## Self-Check: PASSED
- All 6 created files verified present on disk (see below).
- All 3 task commits (`3d563f2`, `66b1fc0`, `0a555ac`) verified in git log.
- Verification green: compileJava 0; ShopAccessErrorTypeTest 4/4; ShopAccessJitProvisionTest 4/4 (concurrent-JIT idempotency, strict OFF/ON, realm-admin bridge).

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-19*
