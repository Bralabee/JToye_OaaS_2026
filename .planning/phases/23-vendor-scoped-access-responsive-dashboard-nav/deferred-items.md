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
