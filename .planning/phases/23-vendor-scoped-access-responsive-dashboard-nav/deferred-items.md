# Phase 23 — Deferred Items

Out-of-scope discoveries logged during execution (per executor SCOPE BOUNDARY). These are NOT fixed in the plan that discovered them.

> **Tracked as GitHub issues — v2.3 milestone backlog review, 2026-07-26.**
> This file is archived to `.planning/milestones/v2.3-phases/` at milestone close, so every
> still-open entry below was filed as an issue first. Nothing here is dropped.
>
> | Entry | Issue |
> |---|---|
> | WR-04 — products/marketing narrow client-side over one server-paginated page | **#280** |
> | WR-03 — post-revocation SSE window (bounded 5 min by `SSE_TIMEOUT`) | **#281** |
> | IN-01 — `fetchMyShops` hard-codes `size=200` | **#282** |
> | `asSystem()` marker for the retained `auth == null` bypass | **#283** |
> | T-23-08-06 — `@Async`/`@Scheduled`/`@RabbitListener` SecurityContext propagation | **#284** |
> | Bulk-revoke of JIT rows in the staff screen | **#285** |
> | Vendor-authenticated Playwright E2E (live run) | **#286** |
> | GCR-W1 — `BulkImportService` cross-tenant `@CacheEvict(allEntries=true)` | **#287** |
> | GCR-W2 — `ShopSwitcher` blank `<select>` for a zero-access non-GROUP_ADMIN | **#288** |
> | GCR-I1 — STOMP shop-gate hard-coded to the `kitchen` topic | **#289** |
> | GCR-I2 — masked directory email rendered twice in the grant picker | **#290** |
> | 23-06 — `docs-freshness` counts ~5 phantom Jest blocks (`RegExp.test(`) | **#291** |
>
> Already tracked elsewhere: `@PreAuthorize` scope backstop on `StaffController` → **#206**.
> Found obsolete by the review (no issue filed): the AGENTS.md-stale-at-V37 entry — `AGENTS.md:107`
> now reads V59. See the annotation on that entry below.

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

## 23-15 (phase gate) — RESOLVED items

- **OpenAPI snapshot regen — RESOLVED.** 23-15 Task 1 ran `./gradlew :core-java:updateOpenApiSnapshot`
  (commit `adc1c58`); `docs/api/openapi-snapshot.json` now carries all four `/api/v1/staff` endpoints
  (`grep -c "api/v1/staff"` → 4, incl. `/me`); `OpenApiSnapshotTest` passes in check mode inside a
  now-green `integrationTest` (331/0 after 23-16). The one known red CI gate is closed.
- **docs-freshness count bump — RESOLVED.** 23-15 Task 2 ran `scripts/docs-freshness.sh --write`:
  `docs/metrics.json` moved 1511 → **1573** (`java_test_methods` 1010 → 1064, `java_test_files`
  175 → 180, `jest_blocks` 357 → 365, `schema_version` 56 → **57** [V57 shipped in 23-14]); CLAUDE.md +
  AGENTS.md line-15 count prose reconciled to match; check mode exits 0. The pre-existing `RegExp.test(`
  counting quirk (manifest reads ~5 blocks above `npx jest` — 365 vs 360) was deliberately NOT "fixed"
  (would shift the committed baseline; see the 23-06 entry above).
- **CSV whole-batch abort — NOT a deferral (RESOLVED in 23-10).** 23-10 (WR-07) delivered per-row
  handling: a malformed CSV `shop_id` is now a per-row 400 and the batch continues, not a
  `ShopAccessDeniedException` that aborts the whole batch. No inconsistency remains to defer.

## 23-15 (phase gate) — conscious deferrals (tracked, not dropped)

- **WR-04 — products/marketing screens narrow client-side over a single server-paginated page.**
  Highest-priority deferral: a genuine user-visible correctness defect (wrong counts, a possible false
  empty state, unreachable rows past the first page). It is NOT a security bypass — the underlying set
  is already grant-scoped server-side (23-03); the defect is cosmetic pagination correctness. Closing it
  needs new `?shopId=` API surface on the products + marketing list endpoints plus gating and two screen
  reworks — its own plan, out of the phase-gate scope. (Disclosed as an accepted caveat when VSA-03 was
  marked complete at 23-07.)
- **WR-03 — post-revocation SSE window.** A revoked user's open KDS SSE stream can linger until the
  connection turns over; bounded at 5 minutes by `SSE_TIMEOUT` (accepted at 23-11). Immediate for the
  HTTP gate + STOMP subscribe; the residual is only the already-open SSE stream.
- **IN-01 — `fetchMyShops` hard-codes `size=200`.** The switcher's shop list is fetched with a fixed
  page size (23-13); a tenant with >200 shops would not see the tail. No known tenant approaches this;
  a paged fetch is the follow-up.
- **`asSystem()` ThreadLocal marker for the retained `auth == null` bypass.** 23-08 fails closed on
  identity *shape* but retains the narrow, non-externally-reachable `auth == null` internal bypass
  (measured blast radius: 62 no-principal test files; Spring Security 401s before any gated service).
  Replacing it with an explicit `asSystem()` system-principal marker is a larger, separate change than a
  gap-closure fix warrants.
- **`@PreAuthorize` scope backstop on `StaffController` (issue #206).** Deferred to the #206
  scoped-credentials work — adding an undefined scope here would break the live frontend, and D-10
  already forbids the `hasRole('admin')` form (23-08).
- **T-23-08-06 — `@Async` / `@Scheduled` / `@RabbitListener` without SecurityContext propagation.**
  An internal async call inherits no `Authentication` and would take the retained `auth == null` bypass.
  No gated service is currently reached from such a path (measured), so this is a tracked residual, not
  an open hole (23-08).
- **Bulk-revoke of JIT rows in the staff screen.** A convenience affordance to revoke many JIT-provisioned
  rows at once (23-14). Not an authorization boundary — single grant/revoke is fully functional; this is
  UX polish.
- **Vendor-authenticated Playwright E2E (live run).** `dashboard-mobile.spec` 375px live run +
  `/dashboard/staff` click-through require a real Keycloak login; `E2E_VENDOR_PASSWORD` is not available
  in the execution session (a documented limitation carried since 23-05 / 23-07 / 23-13) and port-3000
  needs a frontend rebuild to serve the post-change image. `docs-freshness` counts static `test()` blocks
  by grep (it never runs Playwright), so the count reconcile is unaffected. The Java + Jest suites
  (integrationTest 331/0, jest 360/360) are the load-bearing anti-false-green proof; run the live spec at
  the phase PR after a rebuild + creds.
- **AGENTS.md schema-version prose is stale at V37 — ✅ OBSOLETE, verified 2026-07-26.** This no longer
  reproduces: `AGENTS.md:107` now reads **"Current schema version: V59"**, matching `CLAUDE.md:108`. It was
  brought forward by a later phase's doc pass without this entry being closed. Recorded as obsolete by the
  v2.3 milestone backlog review; **no issue filed** and nothing to carry into v2.4. Original entry below,
  left verbatim for provenance.
  <br>~~AGENTS.md schema-version prose is stale at V37 (pre-existing, out of scope).~~ `AGENTS.md` line ~107
  reads "Current schema version: V37 …" — frozen long before Phase 23 and never maintained past V37
  (unlike the CLAUDE.md schema narrative, updated here to V57). This is unrelated to Phase 23 and out of
  the count-sync scope the 260715-fcq quick task established (which syncs the line-15 test-count prose,
  reconciled here). Not fixed per SCOPE BOUNDARY; logged so it is not silently dropped. Reconstructing the
  full V38→V57 narrative in AGENTS.md is its own doc task.

## Gap-closure code review (`23-REVIEW-gapclosure.md`, 2026-07-21)

A standard-depth adversarial review of the 26 gap-closure production files found **1 blocker (fixed) + 2 warnings + 2 info**. The security-critical access-control core (fail-closed principal ladder, shared HTTP/STOMP decision funnel, cache-bypass relocation, last-admin lock, strict-scoping de-honour + bootstrap-admin guard) was confirmed to hold up with no re-introduced bypass.

- **[FIXED — not deferred] BLOCKER: V57 grant_source backfill was RLS-unsafe.** A bare no-GUC `UPDATE` against the FORCE-RLS `shop_staff` table updated zero rows under the RLS-bound migration role → `SET NOT NULL` bricked boot on any non-fresh DB. **Closed by plan 23-17** (V44 tenant-loop `set_config` pattern + a two-tenant stepwise-Flyway regression test, RED→GREEN; `integrationTest` 332/0). Recorded here for traceability only.
- **GCR-W1 (review WR-01) — `BulkImportService` cross-tenant `@CacheEvict(allEntries=true)`.** `BulkImportService.java:65,153` still carry the tenant-wide products-cache blast the wave removed from `createProduct`/`updateProduct`, and it is unnecessary on a create-only path (no cached entry to evict). NOT a security hole — over-eviction (a same-tenant-and-cross-tenant performance cost), never under-eviction/leak. Deferred: fix to a scoped/no evict in a follow-up (small, non-boundary).
- **GCR-W2 (review WR-02) — `ShopSwitcher` blank `<select>` for a zero-access non-GROUP_ADMIN.** A non-admin whose grants were all revoked falls through to a controlled `<select value="all">` with no matching option — a blank, broken control with no explanatory notice. UX-degradation, not an access issue (the backend still denies). Deferred: render an explicit "no shop access — contact your group admin" empty state.
- **GCR-I1 (review IN-01) — STOMP shop-gate hard-coded to the `kitchen` topic.** `TenantChannelInterceptor` only shop-gates `/topic/kitchen/{tenant}/{shopId}`; any FUTURE shop-scoped `/topic/` destination must be added to the gate branch or it re-opens a CR-02-class gap. `kitchen` is currently the ONLY shop-segment topic (grep-confirmed in 23-11), so this is a latent maintenance hazard, not a live gap. Deferred: generalise the gate (or add a guard test that fails when a new shop-segment topic is introduced ungated).
- **GCR-I2 (review IN-02) — masked directory email rendered twice in the staff grant picker.** Cosmetic double-render on `frontend/app/dashboard/staff/page.tsx`. Deferred: trivial de-dupe.
