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

## 2. `GET /api/v1/media/review-queue` is not shop-scoped (found in 37-03)

- **Measured:** `MediaRedriveControllerTest` AC-4.8 stays green under
  `ACCESS_STRICT_SCOPING=true` with a fresh, ungranted vendor subject: the review queue returns the
  tenant's FAILED / flagged / stalled assets to a caller with no shop access.
- **Source:** `MediaAssetService.reviewQueue()` calls `mediaAssetRepository.findReviewQueue(...)`
  with no `ShopAccessService` call; its Javadoc says it is tenant-scoped by RLS only.
- **Why deferred:** a production authorization change, not a test conversion; outside 37-03's
  files. Under D-06 an ungranted user can still read this tenant-wide list (asset ids, product ids,
  failure reasons). Route to the 37-B shop-read work or a dedicated plan.
