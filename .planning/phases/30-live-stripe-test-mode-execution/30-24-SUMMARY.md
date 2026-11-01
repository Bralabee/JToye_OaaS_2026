---
phase: 30-live-stripe-test-mode-execution
plan: 24
status: complete
completed: 2026-11-01
requirements: [PAY-01]
pr: 1065
merge: 6b3ff3e1cab0
evidence: evidence/30-24-pr6-merge-and-main-nightly.txt
---

# 30-24 — PR 6 merged, and the nightly on main moves TEST money through the refund flow

**PR 6 (#1065) is merged as `6b3ff3e1` after a three-round review series ending in a Review-Record on `b8d31922`, and a nightly dispatched on main runs the Stripe phase for real: 1/1, `STRIPE-REFUND` printed, the payment and refund events are rows in `processed_stripe_events`, the skip budget reads 4 of 4, and the uploaded artifact holds no secret.**

## What was done

- Three merges from the base along the way (#1066, #1072, #1073), each with regenerated counts and every CHANGELOG entry kept; the branch was 0 behind at merge time.
- Pre-merge sitting in the 30-04 shape: head equality, 0 behind, every required check green, the staging publishable-key gate rc 0 (it needs `STRIPE_PK_VAR` in the env; UNSET is a red, not a pass), squash body scanned.
- Squash-merged; deploy run 41299627034 started; nightly on main dispatched as run 41299664811 and green end to end (Phase 1 430/434 with 4 declared skips; Stripe phase 1/1; forwarded API version `2026-10-28.endive`).

## Deviations

- The plan's "watch main's nightly" was satisfied by a dispatched run on `6b3ff3e1` rather than waiting for the schedule; tonight's scheduled run is the second proof.
- `scripts/check-stripe-pk-var.sh --environment staging` is red when run bare (the variable is read from the env); recorded for the ship-shape text.

## Next

30-26 onward (30-25's SUMMARY merged via #1067). The record branch `feat/30-pay01-refund-e2e` carries 30-20..30-24 and needs its own PR and review series.
