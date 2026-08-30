---
phase: quick-260830-kmi
plan: 01
subsystem: e2e-testing
tags: [playwright, fixtures, onboarding, skip-budget]
status: complete
requires: []
provides:
  - "seed-e2e-fixtures.sh section 3: terminal demo-tenant onboarding reset (scoped, opt-out-able, verified)"
  - "fresh full-suite report re-earning check-e2e-skip-budget at 6/6 PASS"
affects: [scripts/seed-e2e-fixtures.sh]
key-files:
  created: []
  modified:
    - scripts/seed-e2e-fixtures.sh
decisions:
  - "Reset-in-place over a disposable tenant: a fresh tenant needs a per-run Keycloak user carrying the tenant_id claim (KC24 managed-attribute dance) for no added coverage — the spec is idempotent in every non-terminal state. MAX_SKIPS stays 6; option (2) of the issue (declare + raise) rejected as converting lost coverage into an accepted constant."
  - "RESET_ONBOARDING=0 preserves a terminal state on purpose (owner-gate reviewer inspecting LIVE) but verification still fails on it — opting out of the repair is not opting out of the truth."
  - "Scoped to SHOP_TENANT only (tenant …0002 holds an unrelated WITHDRAWN row); Shop.published and Envers _aud history deliberately untouched."
metrics:
  duration: "~25 min (13:51–14:16Z, 2026-08-30)"
  completed: "2026-08-30"
---

# Quick Task 260830-kmi: Close #686 — scripted demo-tenant onboarding reset Summary

The undeclared-skip cause behind #686 is now provisioned by script, not by hand:
`seed-e2e-fixtures.sh` deletes the demo tenant's `vendor_onboarding` row + gates when —
and only when — the status is LIVE/SUSPENDED/REJECTED/WITHDRAWN, and its end-of-run
verification asks the spec's own skip predicate (0 terminal rows). The skip-budget gate
was re-earned VOID → PASS on a fresh full-suite run: **323 total / 317 passed / 6 skipped
/ 0 failed**, all 6 declared (stomp-relay ×4, vendor-refund ×2), budget 6.

## Commits

| Task | Commit | Files |
| ---- | ------ | ----- |
| 1 — seeder reset + verification | `815178e8` | scripts/seed-e2e-fixtures.sh |
| 2/3 — arms + full-suite re-earn | (no code delta) | — |

## Break arms (clean → arm → clean, all outputs real)

Baseline measured first: tenant `…0001` VERIFYING, 3 gate rows (the 6 in the issue-era
table was the whole table across two tenants).

- **ARM B (declines on healthy state):** seeder on VERIFYING → rc=0,
  `onboarding: VERIFYING — re-runnable, untouched`; status and gate count unchanged by
  content afterwards.
- **ARM A fail direction (the skip is real):** `UPDATE … SET status='WITHDRAWN'`, spec run
  → `1 skipped` (the `-` marker, desktop project, rc=0 as skips do not fail a run).
- **ARM C (verification is load-bearing):** `RESET_ONBOARDING=0` with the WITHDRAWN row →
  rc=**1**, `LIVE/terminal onboarding rows for the tenant : 1 (expect 0…)`, row confirmed
  still present afterwards. The predicate can fail; the opt-out does not silence it.
- **ARM A repair direction:** default seeder run → rc=0,
  `was WITHDRAWN (terminal) — row + gates deleted`; verified by content: 0 onboarding
  rows, 0 gate rows for the tenant. Spec re-run → **1 passed (6.1s)** — the full journey
  (create with bad company number → inline fix → re-run checks → honest in-review), the
  same ~6.7s shape nightly run 33142364550 measured for the passing desktop arm.

## Gate re-earned (Task 3)

`check-e2e-skip-budget.sh` was rc=**2 VOID** before the run (specDigest moved with
PR #692 — failing closed as designed; that is the fail direction, observed not assumed).
After `PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json npx playwright test
--reporter=json,list` (10.5m against the 4/4 FRESH stack):

```
gate rc=0
tests     : 323 total, 6 skipped (budget 6)
freshness : specDigest 70ea7ef21fe9a9fe… matches the tree (content, not mtime)
declared  : 2 ALLOW entr(ies), 3 distinct skipped title(s)
PASS: all 6 skip(s) are declared and within the budget of 6.
```

## The dark-lane half of #686 (recorded, handed off — not silently dropped)

The gate's only CI home is `e2e-nightly.yml:343`, and that lane's darkness is **#683**.
Two facts recorded in the issue close: the nightly's `if: failure()` escalation step
(added with #661) fires on ANY step failure including the skip-budget step, so once the
lane is green a budget violation files an issue instead of vanishing; and the lane's
current red is the #687 flake, fixed and merged as PR #692 — the next scheduled run
(~02:25 UTC) is the confirming instrument. No new lane was added for the gate: it needs a
FULL-suite report, and the nightly is the only full-suite lane by design.

## Deviations from plan

**1. ARM C's toggle became a documented feature, not a test-only bypass.** The plan
allowed "env toggle or temporary edit under test only"; a temporary edit would have left
the arm unreproducible, so `RESET_ONBOARDING` ships documented (an owner-gate reviewer
inspecting a deliberately-LIVE state is a real use), with the verification deliberately
NOT gated on it.

**2. Gate-row baseline corrected 6 → 3.** The issue-era "6 gate rows" was a whole-table
count across two tenants; the per-tenant truth is 3. Recorded so nobody reads the
post-reset 0 against the wrong baseline.

## Self-check: PASSED

Commit `815178e8` on `feature/686-skip-budget-fixture-reset`; seeder + both spec runs +
gate all executed with real output in both directions; fresh report on disk at
`frontend/e2e-artifacts/report.json` (specDigest-matched).
