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
