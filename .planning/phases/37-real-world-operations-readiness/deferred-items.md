# Phase 37 deferred items

Out-of-scope discoveries logged by executors. Each names who found it, what was measured, and
where it must be resolved.

## 1. docs-freshness gate is red on the integration branch (found in 37-03)

- **Measured 2026-10-08 on `739b69d7`:** `bash scripts/docs-freshness.sh` exits 1:
  `docs/metrics.json` says `java_test_methods 2322 / java_test_files 346 / total 4868`; the
  source computes `2330 / 349 / 4876`.
- **Who moved it:** 37-02 added 3 test methods in 2 files (`ShopGrantsIntegrationTest` 2,
  `StrictScopingBootedValueIntegrationTest` 1); 37-03 added 5 in 1
  (`DemoDataSeederServiceAccountGrantIntegrationTest`).
- **Why deferred:** the fix is `scripts/docs-freshness.sh --write` plus the prose counts that
  `scripts/check-doc-metrics.sh` checks in `CLAUDE.md`, `AGENTS.md` and `README.md`. Every later
  37 plan adds tests too, so regenerating per plan only churns those files.
- **Must be resolved before the phase PR opens** (both gates run in
  `.github/workflows/docs-freshness.yml` and fail the build on drift). Re-measure at that point;
  do not copy the numbers above.
- **37-05 adds a second dimension:** migration `V76__user_directory_realm_admin_seen.sql`. So
  `schema_version` in `docs/metrics.json` (computed by `docs-freshness.sh` from the tracked
  migration files) moves from 75 to 76. The prose in `CLAUDE.md` / `AGENTS.md` that names the current
  schema version and the migration count ("V75", "75 Flyway migrations, V1 through V75") needs a V76
  entry at the same pre-PR step. 37-05 also added Java test methods
  (`StaffEffectiveAccessIntegrationTest` 9 in a new file, `MembershipSerializerRoundTripTest` 2,
  `StaffManagementIntegrationTest` 2 in nested classes). Re-measure; do not copy these numbers.

## 2. `GET /api/v1/media/review-queue` is not shop-scoped (found in 37-03)

- **Measured:** `MediaRedriveControllerTest` AC-4.8 stays green under
  `ACCESS_STRICT_SCOPING=true` with a fresh, ungranted vendor subject: the review queue returns the
  tenant's FAILED / flagged / stalled assets to a caller with no shop access.
- **Source:** `MediaAssetService.reviewQueue()` calls `mediaAssetRepository.findReviewQueue(...)`
  with no `ShopAccessService` call; its Javadoc says it is tenant-scoped by RLS only.
- **Why deferred:** a production authorization change, not a test conversion; outside 37-03's
  files. Under D-06 an ungranted user can still read this tenant-wide list (asset ids, product ids,
  failure reasons). Route to the 37-B shop-read work or a dedicated plan.
- **RESOLVED in 37-04** (deviation, Rule 2), because 37-04's flip made it live:
  RED `b2ff028a` (`MediaReviewQueueShopScopeIntegrationTest`, RED_EVIDENCE_OK), fix `4c55677b`.
  The queue now follows the product-list read rule: GROUP_ADMIN sees the tenant, any other caller
  only its granted shops' assets, an ungranted caller nothing, a shop-less asset GROUP_ADMIN only.
  AC-4.8 now reads as shopA's manager. The companion `POST /api/v1/media/{assetId}/keep` was
  checked and already required SHOP_MANAGER on the owning shop (WR-03,
  `MediaKeepShopScopeIntegrationTest`); no change. Evidence: 37-04-SUMMARY.md.

## 3. Comments in the frontend still describe the old OFF default (found in 37-04)

- `frontend/lib/shops-api.ts:9-12` ("under the default strict-scoping = false is every
  JIT-provisioned user"), `frontend/lib/staff-api.ts:19` and
  `frontend/app/dashboard/staff/page.tsx:~639` (copy about "before enabling strict-scoping").
- **Why deferred:** comment/copy only, no behaviour; RESEARCH §37-B.1 routes this copy to the
  D-07 / D-09 Staff-page work, which rewrites the same lines. Leaving it to them avoids two
  rewrites of one paragraph.
- **RESOLVED in 37-06.** `shops-api.ts` and `staff-api.ts` now describe strict scoping ON by
  default; the staff-page block (the automatic-grant badge and its comment) is gone, replaced
  by the server-computed access column (`916d101a`).

## 5. 37-06 frontend checks owed to the runtime gate

- 37-06 changed only frontend code. Two staff-page assertions in
  `frontend/e2e/dashboard-interface-corrections.spec.ts` were edited to follow the new page
  (no invite-denial sentence; the CLS test now waits for the "People" heading), and the B3
  backstop (Playwright at 375px: no horizontal overflow on the no-access page) was not
  written or run. Both need the rebuilt stack, because the People column reads `people[]`,
  which the running core-java does not serve until the 37-15 rebuild. Recorded in
  `.planning/WINDOWS.md` (entries 16 and 17).
- 37-06 also moved the Jest count (staff-page, no-access-page, confirm-action-dialog,
  interceptor and webhooks cases). The full suite reads 197 suites / 2270 tests at `034328bd`.
  Fold this into the §1 regeneration; re-measure, do not copy.

## 4. The flip is not on the shared runtime yet (by design, 37-04)

- 37-04 changed code and config only. The running compose stack was NOT rebuilt: per the plan's
  must-have, the flip reaches a shared runtime only at the 37-15 gate, after the owner has seen
  the list of existing users who hold only an automatic grant.
- So MCP `create_order` / `list_products` under strict ON with real Keycloak client-credentials
  tokens (37-03 coverage D4) is still proven by integration tests only; the live check is owed
  to the 37-15 gate, together with `scripts/check-runtime-freshness.sh`.
