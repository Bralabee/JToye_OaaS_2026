---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 16
subsystem: testing
tags: [jwt, spring-security, testcontainers, mockmvc, vendor-scoped-access, fail-closed]

# Dependency graph
requires:
  - phase: 23-08
    provides: "ShopAccessService fail-closed gate (CR-03) that denies non-UUID-subject authenticated principals"
  - phase: 23-14
    provides: "final gap-closure state of ShopAccessService (strict-scoping tightening) the migrated tests exercise"
provides:
  - "The full ./gradlew :core-java:integrationTest task is genuinely GREEN (331 tests, 0 failed) — the phase gate 23-15 was blocked on"
  - "7 legacy integration classes migrated from @WithMockUser / non-UUID .jwt() to the production UUID-subject JWT auth shape"
affects: [23-15, phase-23-pr, phase-gate-ci]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "UUID-subject JWT test auth: MockMvc jwt() post-processor (subject=UUID, ROLE_admin => implicit GROUP_ADMIN) mirrors ShopAccessEnforcementIntegrationTest.authenticate(); the production auth shape a fail-closed shop gate requires"

key-files:
  created: []
  modified:
    - core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/LocationHeaderContractTest.java
    - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductSearchFtsIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingGoLiveIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/ScopedCatalogAccessIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/tenant/TenantLifecycleAdminIntegrationTest.java

key-decisions:
  - "Test-only migration — ShopAccessService (and all main source) left untouched; 23-08's fail-closed boundary is preserved, not relaxed"
  - "Access intent preserved per class: general operators -> UUID-subject JWT with ROLE_admin (day-one implicit GROUP_ADMIN); deny/scope tests kept scoped (no over-grant)"
  - "VSA-02/VSA-04 NOT marked complete here — their closure resumes in 23-15 on the now-green suite (anti-false-green)"

patterns-established:
  - "adminJwt()/operatorJwt() MockMvc RequestPostProcessor helper returning a UUID-subject JWT; SecurityContext authenticateAsAdmin() for non-MockMvc direct-service tests"

requirements-completed: []  # VSA-02/VSA-04 closure is 23-15's job (see Deviations / Next); this plan only removes the test-debt that blocked the gate

# Metrics
duration: ~59min
completed: 2026-07-21
---

# Phase 23 Plan 16: Legacy Integration Auth Migration Summary

**Migrated 7 legacy integration classes from `@WithMockUser` / non-UUID `.jwt()` subjects to the production UUID-subject JWT auth shape, turning the full `:core-java:integrationTest` task from 13 failures to 0 — with zero production-code change, so 23-08's fail-closed shop-access boundary is preserved, not relaxed.**

## Performance

- **Duration:** ~59 min (dominated by the sequential Testcontainers runs — full `integrationTest` alone was 33m 5s)
- **Started:** 2026-07-21T11:17:35Z
- **Completed:** 2026-07-21T12:16:42Z
- **Tasks:** 3 (2 code tasks + 1 verification gate)
- **Files modified:** 7 (all `core-java/src/test/`)

## Accomplishments

- **Full `:core-java:integrationTest` is genuinely green:** 80 classes, **331 tests completed, 0 failed, 0 errors, 1 skipped** — the phase gate that 23-15 Tasks 2-3 were blocked on. The census's 13 failures across 7 classes are gone.
- **23-08's fail-closed gate preserved:** every migrated test now authenticates the way production does (a UUID-subject Keycloak `Jwt`), rather than the fix weakening `ShopAccessService.requireVendorUserId()`. `git diff` for this plan is 100% under `core-java/src/test/`.
- **Access intent preserved per method** (not blanket-admin): operator/create paths → UUID-subject JWT with `ROLE_admin` (day-one implicit GROUP_ADMIN); the scope-gate deny tests in `ScopedCatalogAccessIntegrationTest` still 403 via the `@PreAuthorize` scope gate (no over-grant); RBAC negatives in `TenantLifecycleAdminIntegrationTest` keep their `user`-role token.
- **`:core-java:test` (unit suite) remains green** — the migration touched no unit-covered code.

## Task Commits

1. **Task 1: Migrate the five `@WithMockUser` integration classes to UUID-subject JWT auth** — `20ece8a` (test)
2. **Task 2: Fix the two `.jwt()` classes carrying a non-UUID subject** — `edb4b63` (test)
3. **Task 3: Prove the whole `integrationTest` task is green** — verification gate, no commit

## Files Created/Modified

- `.../integration/ShopControllerIntegrationTest.java` — 4 `@WithMockUser` methods → `adminJwt()` post-processor. 3 were failing (create-valid, list, create-without-tenant); `createShopWithoutTenantHeaderShouldReturn400` still returns 400 because the tenant-less `IllegalStateException` maps to 400 (only `MissingTenantContextException` is 500).
- `.../integration/LocationHeaderContractTest.java` — 7 methods → a single `operatorJwt()` (ROLE_admin + `SCOPE_catalog:write`) applied inside the shared `assertLocationDereferences`/`createShop` helpers. All are create-and-dereference happy paths, so one admin+write-scope operator token faithfully covers the shop gate, the finance/refund `hasRole('admin')` gate, and the product-create scope gate.
- `.../security/SecurityHeadersIntegrationTest.java` — 3 `@WithMockUser` methods → `adminJwt()` (1 was failing: `shopsEndpointHasSecurityHeaders`; the 401/golden-header tests are unchanged).
- `.../product/ProductSearchFtsIntegrationTest.java` — the single HTTP `@WithMockUser` method (`searchEndpointCaps...`) → `adminJwt()` on all four `perform`s; the service-level tests were never gated and are untouched.
- `.../onboarding/OnboardingGoLiveIntegrationTest.java` — the 3 go-live HTTP methods → `adminJwt()` (go-live is not shop-gated, so these already passed); the genuine failure `updateShopCannotPublish` (a direct `ShopService.updateShop` call) → a SecurityContext realm-admin via `authenticateAsAdmin()`, so the update CLEARS the gate and proves the sole-writer invariant on a successful update rather than on a denial.
- `.../security/ScopedCatalogAccessIntegrationTest.java` — `readOnlyJwt`/`operatorJwt`/`noScopeJwt` gained UUID subjects; scope-gate semantics unchanged.
- `.../tenant/TenantLifecycleAdminIntegrationTest.java` — `adminJwt`/`userJwt` gained UUID subjects; realm-role RBAC unchanged.

## Decisions Made

- **No production change.** The plan forbids editing `ShopAccessService` or any main source; every failure was resolved purely by putting the test principals into the production auth shape. Verified: `git diff --name-only 5101f9a..HEAD` is entirely `core-java/src/test/`.
- **Intent preservation over green-at-any-cost.** Per-class, the migration reproduces the access each test asserted (admin/scoped/deny), matching the threat register (T-23-16-02/03).
- **VSA-02/VSA-04 left NOT-complete** (see Deviations) — closure belongs to 23-15's reconcile, which now unblocks.

## Deviations from Plan

The census (13 failures / 7 classes) undercounted per-class in exactly the way Task 3 anticipated, and one failure was a different shape than "expected 2xx but was 403". Both were handled within the plan's rules (no production change, intent preserved) — recorded here for transparency, not as auto-fixes to production:

**1. [Census refinement — same class, different method] `OnboardingGoLiveIntegrationTest`'s failure was `updateShopCannotPublish`, not a go-live method**
- **Found during:** Task 1 (reading the class before migrating)
- **Detail:** The 3 go-live HTTP methods are NOT shop-gated (`goLive()` uses `CurrentTenant.require()` and the sole-writer `setPublished`, neither calls `ShopAccessService`), so they passed on `@WithMockUser`. The real casualty was `updateShopCannotPublish`, which calls `ShopService.updateShop` directly — the gate threw `ShopAccessDeniedException` and the test errored. Fixed by authenticating as a UUID-subject realm-admin on the SecurityContext (not MockMvc), so the update executes and the invariant is proven on success.
- **Files:** `OnboardingGoLiveIntegrationTest.java` — **Committed in:** `20ece8a`

**2. [Assertion shape, no status change] `createShopWithoutTenantHeaderShouldReturn400` stays 400 via a different path**
- **Found during:** Task 1
- **Detail:** With the old non-JWT principal this test 403'd at the gate. With the admin JWT the gate passes and the tenant-less request now surfaces `IllegalStateException("Tenant context not set")`, which `GlobalExceptionHandler.handleIllegalState` maps to **400** (only `MissingTenantContextException` is 500). The asserted status (400) was NOT changed — the migration changed *how* auth happens, not *what* is asserted.
- **Files:** `ShopControllerIntegrationTest.java` — **Committed in:** `20ece8a`

---

**Total deviations:** 0 production changes; 2 test-scoped observations documented above.
**Impact on plan:** None — both are consistent with the plan's intent-preservation and no-main-source rules. No scope creep.

## Issues Encountered

- The full `:core-java:integrationTest` task is slow (33m 5s, ~80 Spring contexts + Testcontainers); scoped `--tests` runs are what originally hid this regression. Verified the FULL task, not scoped runs — the plan's explicit final gate. Trailing "connection refused / pool timed out" lines in the log are Testcontainers teardown noise between context shutdowns, not test failures (BUILD SUCCESSFUL, exit 0).

## Threat Flags

None — no new security surface; this plan removes test debt and touches no production code.

## Next Phase Readiness

- **23-15 Tasks 2-3 are unblocked.** With the full `integrationTest` green (and `OpenApiSnapshotTest` already regenerated in `adc1c58`), 23-15's docs-freshness/metrics reconcile (schema 56→57, +9 Java `@Test`, +1 Jest) + planning-record reconcile + VSA-02/VSA-04 closure can resume on a now-green suite.
- **VSA-02/VSA-04 remain PENDING here by design** (anti-false-green) — this plan only closed the phase-gate regression; requirement closure is 23-15's final step.
- **Metrics note for 23-15:** the 7 migrated files change auth wiring only; test-method COUNTS are unchanged (no added/removed `@Test` methods in these 7 classes), so this plan does not itself move `docs/metrics.json`.

## Self-Check: PASSED

- SUMMARY.md present.
- All 7 modified test files present on disk.
- Both task commits present in history (`20ece8a`, `edb4b63`).
- Gate evidence: `./gradlew :core-java:integrationTest` → **BUILD SUCCESSFUL in 33m 5s**, aggregate **331 tests completed, 0 failed, 0 errors, 1 skipped** (80 classes). `./gradlew :core-java:test` → **BUILD SUCCESSFUL** (exit 0). `git diff --name-only 5101f9a..HEAD` → only `core-java/src/test/` paths (production untouched).

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-21*
