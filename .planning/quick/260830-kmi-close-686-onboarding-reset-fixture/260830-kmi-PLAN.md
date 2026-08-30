---
phase: quick-260830-kmi
plan: 01
subsystem: e2e-testing
tags: [playwright, fixtures, onboarding, skip-budget]
requires: []
provides:
  - "seed-e2e-fixtures.sh resets a terminal demo-tenant onboarding so ONBD-05 runs instead of skipping"
  - "fresh full-suite report re-earning check-e2e-skip-budget at 6/6"
affects: [scripts/seed-e2e-fixtures.sh]
created: 2026-08-30
branch: feature/686-skip-budget-fixture-reset
---

# Quick Task 260830-kmi: Close #686 — script the demo-tenant onboarding reset into the E2E seeder

## Problem

`onboarding-blocked-flow.spec.ts` (Phase 21 ONBD-05) deliberately skips when the demo
tenant's `vendor_onboarding` is LIVE/terminal, to avoid mutating a live demo. On
2026-08-30 that skip put the local suite at 7 skips vs budget 6 with one undeclared
(issue #686). It was cleared the same morning by a **manual, unscripted** reset of the
demo tenant's onboarding — the exact "provisioning only a human can perform" anti-pattern
V64/#647 documented. Next time anyone drives the demo tenant to LIVE (e.g. the 35-13
owner-gate reviewer resolving the parked FHRS gate, which the spec's own docblock invites),
the skip returns and nothing scripted can clear it.

`scripts/seed-e2e-fixtures.sh` exists precisely to close skip-causing fixture gaps
("establish the dev-DB fixtures the Playwright suite SKIPS without"). This gap belongs there.

## Scope decision (recorded per the issue's "needs a decision")

Issue #686 offered (1) a fresh/disposable tenant fixture or (2) declare + raise MAX_SKIPS.
This plan takes a third path that is (1)'s substance at (2)'s cost: **reset the existing
demo tenant's onboarding when — and only when — it is terminal**. A genuinely disposable
tenant needs a Keycloak user carrying the new tenant_id claim (KC24 managed-attribute
dance) per run; that is a phase-sized fixture for no additional coverage, since the spec
is idempotent in every non-terminal state. Option (2) is rejected by the issue itself —
it converts lost coverage into an accepted constant. MAX_SKIPS stays 6.

The dark-lane half of #686 ("the gate only runs in the nightly") is **#683's scope**: the
nightly runs `check-e2e-skip-budget.sh` at e2e-nightly.yml:343 and its `if: failure()`
escalation step files an issue on any step failure, the gate included. The lane's darkness
is the #687 flake (fixed, merged as PR #692) — tonight's scheduled run is the confirming
instrument. Recorded in the issue close, not duplicated here.

## Tasks

### Task 1: onboarding reset section in seed-e2e-fixtures.sh

After the promo/announcement seed (section 2), add section: for `SHOP_TENANT` (the tenant
the vendor journey runs under, already discovered from the promo shop), if
`vendor_onboarding.status IN ('LIVE','SUSPENDED','REJECTED','WITHDRAWN')`:
delete its `vendor_onboarding_gate` rows (FK `onboarding_id`), then the
`vendor_onboarding` row, and report what was reset. Any other state (or no row) is
re-runnable — touch nothing and say so.

Constraints:
- **Scope to SHOP_TENANT only.** Tenant `…0002` holds an unrelated WITHDRAWN row that is
  not the spec's concern; a table-wide sweep would destroy state other flows may assert.
- **Never touch `Shop.published`.** Demo shops are seed-published and the storefront specs
  depend on that; the onboarding state machine being "sole writer of Shop.published"
  applies to application flows, and this reset restores the pre-onboarding fixture state
  without unpublishing anything.
- Envers `_aud` mirrors keep their history rows — append-only audit, deliberately untouched.
- Prose stays in shell comments, never inside the unquoted heredocs (the backtick-execution
  trap recorded in this same script's header).
- Extend the end-of-run verification with the SPEC'S OWN predicate: the tenant's
  onboarding is absent-or-non-terminal (mirrors the spec's LIVE/terminal skip guard).

### Task 2: break arms (clean → arm → clean, restores proven by content)

Baseline (measured 2026-08-30 13:50Z): tenant 0001 = VERIFYING (re-runnable), 6 gate rows.

- **ARM A (reset fires):** UPDATE tenant 0001's row to `WITHDRAWN` by SQL. Run the spec →
  observe the SKIP annotation (the fail direction of coverage, observed not assumed). Run
  the seeder → verify by content the row is gone and its gate rows are gone. Run the spec →
  observe it RUNS (passes, no skip annotation).
- **ARM B (reset declines):** run the seeder again on the fresh non-terminal state →
  verify it reports "re-runnable, untouched" and the row count/state is unchanged
  (idempotence; the reset must not fire on a healthy state).
- **ARM C (verification can fail):** with a terminal row present and the reset section
  bypassed (env toggle or temporary edit under test only), the end-of-run verification
  must FAIL — proving the new predicate is load-bearing, not decorative.

### Task 3: re-earn the skip-budget gate

`check-e2e-skip-budget.sh` is rc=2 VOID (specDigest moved with PR #692). Run the FULL
suite against the 4/4 FRESH stack (`PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json
npx playwright test --reporter=json,list`), then the gate: expect rc=0, 6 skipped
(stomp-relay ×4 declared, vendor-refund ×2 declared), 0 undeclared, 0 failed.

### Task 4: ship

Atomic commits; PR; changelog entry heading citing the PR number (the gate that redded
PR #692 — add the entry as soon as the number exists); close #686 citing the evidence and
the #683 hand-off for the dark-lane half; SUMMARY.md + STATE.md quick-tasks row.

## Acceptance criteria (each with a fail direction)

1. Seeder resets a terminal onboarding → shown by ARM A both directions.
2. Seeder leaves non-terminal state alone → ARM B.
3. New verification predicate can fail → ARM C.
4. Gate rc=0 at 6/6 on a fresh full-suite report → currently rc=2, so the fail direction
   is already on record; the pass must name 6 skips, not merely exit 0.
