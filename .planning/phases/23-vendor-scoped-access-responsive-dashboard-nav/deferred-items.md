# Phase 23 — Deferred Items

Out-of-scope discoveries logged during execution (per executor SCOPE BOUNDARY). These are NOT fixed in the plan that discovered them.

## 23-01

- **docs-freshness count bump (phase-gate reconcile).** 23-01 added 1 Java test file
  (`ShopStaffRlsPolicyIntegrationTest`) with 3 `@Test` methods. `docs/metrics.json`
  (`total_logical_invocations` 1456 / `java_test_methods` 989 / `java_test_files` 170)
  and the CLAUDE.md prose counts are NOT updated per-plan. Following the Phase 22
  precedent (STATE decision 22-07: "whole-repo artifacts reconciled once at the last
  plan"), the `scripts/docs-freshness.sh --write` reconcile + CLAUDE.md count update
  is deferred to the **last plan of Phase 23**, after all phase test additions land,
  so the count is bumped once instead of drifting every plan. `schema_version` stays
  56 (V52 < HEAD V56; out-of-order slot).

## 23-01 — RESOLVED in 23-06

The deferred count bump below was **executed at the phase gate by 23-06**:
`scripts/docs-freshness.sh --write` reconciled `docs/metrics.json` to
**1511** total logical invocations and the CLAUDE.md prose was updated to match;
check mode exits 0. `schema_version` stayed 56 as predicted.

## 23-07

- **docs-freshness count bump (same phase-gate reconcile).** 23-07 added 3 Jest files
  with 16 `it` blocks (`hooks/__tests__/use-shop-context.test.tsx` 4,
  `app/dashboard/__tests__/products-orders-shop-scope.test.tsx` 6,
  `app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx` 6). Measured drift at
  23-07 close (`scripts/docs-freshness.sh`, read-only): recorded 1456 vs computed
  **1504** — `java_test_methods` 989→1010, `java_test_files` 170→175,
  `java_controllers` 20→21 (23-01..23-04), `jest_blocks` 324→350, `jest_files` 51→55
  (23-05's 10 + 23-07's 16), `playwright_blocks` 39→40 (23-05). Deliberately NOT
  written here: **23-06 is still pending** and will move the numbers again, so
  `--write` stays at the last plan of the phase per the 23-01 entry. `schema_version`
  unchanged at 56.

  **RESOLVED in 23-06** — final reconciled figures (23-06 added 7 more Jest blocks
  in 1 file): `java_test_methods` 1010 / `java_test_files` 175 / `java_controllers` 21
  / `jest_blocks` 357 / `jest_files` 56 / `playwright_blocks` 40 / total **1511**.

## 23-06

- **OpenAPI snapshot regen is STILL OPEN and will fail CI (inherited from 23-04).**
  `docs/api/openapi-snapshot.json` does not yet contain the three `/api/v1/staff`
  endpoints (`grep -c "api/v1/staff"` → 0). `OpenApiSnapshotTest` runs in *check*
  mode inside `integrationTest`, so the phase PR fails until someone runs
  `./gradlew :core-java:updateOpenApiSnapshot` and commits the diff. 23-04 deferred
  this to "the phase-gate reconcile"; 23-06 owns that gate but **could not execute
  it** — the task boots the full Spring context against a throwaway Testcontainers
  Postgres, which the low-footprint execution constraint forbids (no Docker). This
  is the one known red gate left in Phase 23. Not a code defect; a regeneration step.

- **docs-freshness counts ~5 blocks more than Jest executes (pre-existing, script
  methodology).** The manifest records `jest_blocks` 357 while `npx jest` runs 352.
  The script counts textual `\b(it|test)\(` occurrences, which also matches
  `RegExp.prototype.test(` calls — e.g. `frontend/__tests__/link-graph.test.ts:47,117,119`
  and `app/dashboard/kitchen/__tests__/page.test.tsx:210`. Pre-existing (not introduced
  by Phase 23 — 23-06's own file counts 7 and runs 7), and the gate remains
  self-consistent (manifest == computed), so this is NOT fixed here per SCOPE BOUNDARY.
  Fixing it would shift the committed baseline and is its own change.

## 23-12

- **OpenAPI snapshot regen: the staff surface is now FOUR endpoints, not three
  (updates the 23-06 entry above).** 23-12 added `GET /api/v1/staff/me` (CR-08), so
  `docs/api/openapi-snapshot.json` must now pick up **four** `/api/v1/staff` endpoints
  — `GET /` (list), `GET /me` (new), `POST /grant`, `DELETE /{id}` — when
  `./gradlew :core-java:updateOpenApiSnapshot` is finally run. Plan **23-15** owns that
  regeneration + the `docs-freshness --write` phase-gate reconcile. Still the one known
  red gate; `OpenApiSnapshotTest` runs in check mode inside `integrationTest`, so scoped
  test runs (`--tests "*StaffManagementIntegrationTest"` etc.) stay green while the full
  `integrationTest` task remains red until 23-15. Not a code defect; a regeneration step.

- **Test-count drift for the last-plan reconcile (23-15).** 23-12 added integration
  test methods only (Java): +5 in `StaffManagementIntegrationTest` (3 grant-validation
  + 2 `/me`), +2 more in `StaffManagementIntegrationTest` (email-mask), +2 in
  `GdprErasureIntegrationTest`. No new test *files*. Per the 23-01/23-07 precedent the
  `docs/metrics.json` + CLAUDE.md count bump is deferred to the phase's last plan.
