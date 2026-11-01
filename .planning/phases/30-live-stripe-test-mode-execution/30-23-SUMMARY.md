---
phase: 30-live-stripe-test-mode-execution
plan: 23
status: complete
completed: 2026-11-01
requirements: [PAY-01]
pr: 1065
branch: agy/30-21-22-refund-e2e
head: b8d3192253e51ad84a7c7bc726bcdc539a66bb22
evidence: evidence/30-23-pay01-branch-nightly.txt
---

# 30-23 — the owner's CI keys, a real nightly on the PR branch, the ALLOW retired

**A dedicated CI Stripe sandbox exists and a dispatched nightly on PR 6's branch moved TEST money through the refund flow end to end: the payment and refund events are rows in `processed_stripe_events`, the Stripe report is 1/1 with zero skips, and the skip budget reads 4 of 4 against the measured count.**

## What was done

- **Task 1 (owner, side by side in Chrome):** sandbox `jtoye-ci` (`acct_1UWtvjD29H13ktT5`) created under the J'Toye Digital Ltd account, separate from the staging sandbox (`acct_1U0tklDpS9ICNeRt`). Keys verified without printing a value (prefixes, `livemode=false`, a different account from the CLI login) and stored: repository secret `STRIPE_TEST_SECRET_KEY`, repository variable `CI_STRIPE_PUBLISHABLE_KEY` (read back equal). The files were shredded.
- **Task 2 and 3:** four dispatched runs on the branch (`agy/30-21-22-refund-e2e`, PR 6's branch, not the plan's `feat/30-pay01-refund-e2e`). Each run found a real defect in the nightly's Stripe phase and each was fixed on the branch as a fast-forward, with the cause measured first:
  1. `stripe listen` (CLI 1.55.6) refuses to start without an events selection → `--all-snapshot`.
  2. The skip-budget gate went VOID after an upstream failure (round-1 recorded finding 9, observed) → gates Phase 1 alone when the Stripe run was skipped.
  3. The recreated core-java moved to host port 9091 (compose publishes 9090-9091) → pinned to 9090.
  4. The CLI forwards events in the account's default API version (`2026-07-29.dahlia`) while stripe-java 34.0.0 pins `2026-09-30.endive`; the typed deserializer returns empty across majors and the event was silently refused. Staging works because its dashboard endpoints are pinned on endive. → `stripe listen --latest` (forwards `2026-10-28.endive`, the same major), the forwarded version printed by the step.
  5. The cart-identity gate timed out once core-java ran with the Stripe key → moved before the recreate.
  Three 30-24 merges from the base (#1066, #1072, #1073) were taken along the way, counts regenerated, every CHANGELOG entry kept. The refund ALLOW had already been retired in the round-1 fix (`0ebfaf66`, MAX_SKIPS 4); run 41297105542 is the measured confirmation.
- PR #1065 marked ready on `b8d31922`.

## Deviations

- The plan expected the first run red on S-3; the ALLOW was already gone, so the S-3 arm did not apply. The five defects above were not in the plan; each was fixed as observed, per the checkpoint's instruction.
- The fix pushes after the round-2 Review-Record on `0ebfaf66` reopen the review series: round 3 is the owner's (ruling 2026-10-25).

## Recorded, not fixed

- `GlobalExceptionHandler` maps `IllegalStateException` to a 400 without logging (why defect 4 left no trace in core-java).
- Antigravity's `-agy-p30-w21` worktree is behind the branch; `git pull --ff-only` there.

## Next

30-24: CI green on the head, round 3, squash-merge in the 30-04 ship shape, watch main's scheduled nightly.
