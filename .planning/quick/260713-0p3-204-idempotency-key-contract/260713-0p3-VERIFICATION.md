---
phase: quick-260713-0p3
verified: 2026-07-13T00:40:58Z
status: passed
score: 8/8 must-haves verified
overrides_applied: 0
---

# Quick Task 260713-0p3: Uniform Idempotency-Key Contract (#204 / AI-2) Verification Report

**Task Goal:** Uniform `Idempotency-Key` header contract — audit doc, tenant-scoped dedup store (V50, ENABLE+FORCE RLS), orders+customers create coverage (header optional), Testcontainers proof (replay, concurrent race, 422, cross-tenant RLS under NOSUPERUSER downgrade), OpenAPI advertisement + regenerated snapshot.
**Verified:** 2026-07-13T00:40:58Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST /api/v1/orders with a repeated Idempotency-Key returns the ORIGINAL order and creates zero duplicate rows | VERIFIED | `OrderIdempotencyIntegrationTest.sameKeyReplay_returnsOriginalOrder_zeroDuplicates` — freshly re-run under Testcontainers, PASS (0 failures/errors), asserts same order id/number + `count(*)==1` |
| 2 | Two concurrent POST /api/v1/orders with the SAME key produce exactly one order row and never a 500 | VERIFIED | `concurrentSameKey_exactlyOneRow_no500` — CountDownLatch-gated 2-thread race, freshly re-run, PASS; asserts one winner (order) + other either replays same id or `IdempotencyConflictException` (409), never an unexpected exception; `count(*)==1` |
| 3 | POST /api/v1/customers with a repeated Idempotency-Key returns the original customer and creates no duplicate row | VERIFIED | `CustomerIdempotencyIntegrationTest.sameKeyReplay_returnsOriginalCustomer_noDuplicate` — freshly re-run, PASS; asserts same customer id (not a 409 from `uq_customers_tenant_email`) + `count(*)==1` |
| 4 | Same Idempotency-Key reused with a DIFFERENT request body returns 422 | VERIFIED | `OrderIdempotencyIntegrationTest.sameKeyDifferentPayload_returns422` — freshly re-run, PASS; asserts `IdempotencyPayloadMismatchException` thrown, mapped to 422 in `GlobalExceptionHandler` (handler registered above the catch-all `Exception` handler) |
| 5 | Tenant B cannot read/replay/forge tenant A's idempotency_keys row; same key under a different tenant is a fresh create — proven under NOSUPERUSER role-downgrade | VERIFIED | `IdempotencyKeysRlsPolicyIntegrationTest` (3/3, freshly re-run) — provisions `rls_test_role NOSUPERUSER NOBYPASSRLS LOGIN`, `SET LOCAL ROLE` per tx: `tenantB_cannotReadTenantAKey` (0 rows visible), `sameKeyDifferentTenant_freshCreateNotReplay` (1 row inserted, not a replay), `tenantB_cannotForgeTenantARow` (INSERT with tenant_id=A under tenant B's GUC throws `DataAccessException` with "row-level security" in message, from the `WITH CHECK` clause) |
| 6 | The Idempotency-Key header is advertised in OpenAPI on orders.create + customers.create and the committed snapshot matches (openapi-compat gate green) | VERIFIED | `docs/api/openapi-snapshot.json` lines 4429, 5428 show `"name": "Idempotency-Key"` on customers.create and orders.create operations; `IdempotencyHeaderCustomizer` gates on `hasMethodAnnotation(Idempotent.class)`; `OpenApiSnapshotTest` freshly re-run via `integrationTest`, PASS (1/1, 0 failures) — live spec byte-matches the committed snapshot |
| 7 | docs/idempotency.md commits the existing-coverage audit and documents the adoption pattern for future endpoints | VERIFIED | `docs/idempotency.md` (143 lines) — AC-1 truth table of 7 mutating endpoints + mechanism/behavior, generic contract semantics, adoption recipe with code sample, 201-hardcode limitation documented, refund/guest-checkout carve-outs, edge-go no-header compatibility note, TTL/pruning ops note |
| 8 | Refund and guest-checkout idempotency continue to work unchanged | VERIFIED | `git diff --stat 32e24e8..102c0db` on `RefundController.java`/`RefundService.java`/`PublicStorefrontService*` returns empty (zero changes); `OrderServiceTest`/`CustomerServiceTest` freshly re-run, PASS (service signatures untouched, no Mockito test breakage) |

**Score:** 8/8 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/main/resources/db/migration/V50__idempotency_keys.sql` | tenant-scoped dedup store, ENABLE+FORCE RLS, composite PK | VERIFIED | 54 lines; `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + `PRIMARY KEY (tenant_id, endpoint, idempotency_key)` present; mirrors V47 shape exactly, no `_aud` mirror |
| `core-java/.../common/idempotency/IdempotencyService.java` | reserve-first execute() with GUC pin | VERIFIED | 210 lines; `INSERT ... ON CONFLICT DO NOTHING` + `set_config('app.current_tenant_id'` present; wired from both controllers |
| `core-java/.../common/idempotency/Idempotent.java` | marker annotation | VERIFIED | 34 lines, `@Retention(RUNTIME) @Target(METHOD)`, `String endpoint()` |
| `core-java/.../config/IdempotencyHeaderCustomizer.java` | springdoc OperationCustomizer | VERIFIED | 37 lines, `hasMethodAnnotation(Idempotent.class)` gate confirmed |
| `core-java/.../common/idempotency/OrderIdempotencyIntegrationTest.java` | Testcontainers proof (replay+race+422) | VERIFIED | 266 lines (>120 min); 3/3 tests freshly PASS |
| `core-java/.../common/idempotency/CustomerIdempotencyIntegrationTest.java` | Testcontainers proof (replay) | VERIFIED | 124 lines; 1/1 test freshly PASS |
| `core-java/.../common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` | NOSUPERUSER role-downgrade RLS proof | VERIFIED | 177 lines; `SET LOCAL ROLE` present; 3/3 tests freshly PASS |
| `docs/idempotency.md` | AC-1 audit + adoption pattern | VERIFIED | 143 lines; contains "Idempotency-Key", audit table, adoption recipe |

Note: the two exception classes (`IdempotencyConflictException`, `IdempotencyPayloadMismatchException`) were relocated from the PLAN's declared `common/idempotency/` package to `uk.jtoye.core.exception` (documented deviation in SUMMARY.md, matching house convention — all 6 pre-existing custom exceptions live there). Both exist, compile, and are correctly wired into `GlobalExceptionHandler`. This is a non-functional relocation, not a scope reduction — treated as satisfying the artifact must-have.

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `OrderController.createOrder` / `CustomerController.create` | `IdempotencyService.execute` | header-present branch | WIRED | `grep` confirms `idempotencyService.execute(` calls in both controllers, gated on `idempotencyKey == null \|\| idempotencyKey.isBlank()` |
| `IdempotencyService.execute` | `idempotency_keys` table | reserve-first insert | WIRED | `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` present and exercised by passing tests |
| `IdempotencyService.execute` | `app.current_tenant_id` GUC | defensive `set_config` | WIRED | `pinTenantGuc()` issues `SELECT set_config('app.current_tenant_id', ?, true)` at top of tx before any store access |
| `IdempotencyHeaderCustomizer` | `Idempotent` annotation | `hasMethodAnnotation` gate | WIRED | confirmed; snapshot shows header present on exactly the 2 target ops (+ pre-existing refund op) |
| `GlobalExceptionHandler` | `IdempotencyConflictException` / `IdempotencyPayloadMismatchException` | 409 / 422 RFC 7807 mapping | WIRED | both `@ExceptionHandler` methods present, positioned above the catch-all `handleGenericException(Exception)` |

### Behavioral Spot-Checks / Testcontainers Proof (re-executed live, not trusted from SUMMARY)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Order replay/race/422 | `./gradlew :core-java:integrationTest --tests '...OrderIdempotencyIntegrationTest'` | 3/3 tests, 0 failures/errors (fresh XML timestamp 2026-07-13 01:39:30) | PASS |
| Customer replay | `./gradlew :core-java:integrationTest --tests '...CustomerIdempotencyIntegrationTest'` | 1/1 test, 0 failures/errors | PASS |
| Cross-tenant RLS (NOSUPERUSER) | `./gradlew :core-java:integrationTest --tests '...IdempotencyKeysRlsPolicyIntegrationTest'` | 3/3 tests, 0 failures/errors | PASS |
| OpenAPI snapshot compat gate | `./gradlew :core-java:integrationTest --tests uk.jtoye.core.integration.OpenApiSnapshotTest` | 1/1 test, 0 failures/errors (fresh timestamp 2026-07-13 01:40:18) | PASS |
| `@WebMvcTest` slice coupling fix | `./gradlew :core-java:test --tests OrderControllerShopFilterTest --tests OrderControllerPaginationTest` | BUILD SUCCESSFUL | PASS |
| Regression: create-service Mockito units | `./gradlew :core-java:test --tests OrderServiceTest --tests CustomerServiceTest` | BUILD SUCCESSFUL | PASS |
| Full compile | `./gradlew :core-java:compileJava :core-java:compileTestJava` | BUILD SUCCESSFUL, 19 pre-existing `@MockBean` deprecation warnings only, 0 errors | PASS |
| docs-freshness gate | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 1199)` exit 0 | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| #204 | 260713-0p3-PLAN.md | Uniform Idempotency-Key contract | SATISFIED | All 8 truths verified above |
| AI-2 | 260713-0p3-PLAN.md | Same issue, AI-readiness track tag | SATISFIED | Same evidence |

### Anti-Patterns Found

None. Scanned all 14 created/modified idempotency-specific files for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` — zero hits. No stub returns, no empty handlers, no hardcoded-empty response bodies.

### Human Verification Required

None. All must-haves are backend/API-level and were verified via live Testcontainers execution (not SUMMARY narration) and static wiring checks. No UI, visual, or external-service-dependent behavior in this slice.

### Gaps Summary

None. All 8 must-have truths verified, all 8 required artifacts present and substantive (with one documented, non-functional package relocation), all 5 key links wired, all Testcontainers tests re-executed live and green (7/7 idempotency-specific tests + 1/1 OpenAPI snapshot test), compile clean, docs-freshness green at the claimed 1199/schema-50 baseline, and CLAUDE.md narrative resynced. No regression to refund, guest-checkout, or the pre-existing Mockito unit test suites.

---

_Verified: 2026-07-13T00:40:58Z_
_Verifier: Claude (gsd-verifier)_
