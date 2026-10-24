---
phase: 30-live-stripe-test-mode-execution
plan: 08
subsystem: staging-ops
tags: [staging, stripe, test-mode, orders, issue-958, d-01, deploy, parity, live-gate, review]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-05: the 409 awaiting-payment gate; 30-06: the staff UI badge and gated actions; 30-07: the staging-seed.sh hand tools (list-drafts, vendor-order, order-transition, cancel-drafts, stripe-get)"
provides:
  - "PR #1030 (issue #958, Phase 30 PR 2) reviewed to zero admissible findings and squash-merged as 7b117162"
  - "Staging runs 7b117162 by digest (core-java, edge-go, frontend)"
  - "Live proof on staging: an unpaid card DRAFT's SUBMIT is refused 409 awaiting-payment and the order stays DRAFT; a vendor NONE DRAFT submits 200 and a CAPTURED order confirms 200"
  - "Issue #1031 [data] (late payment_failed overwrites CAPTURED; server CANCEL leaves the intent payable), filed from review round 1"
  - "Issue #1038 (stale-DRAFT cleanup deletes card orders and leaves their PaymentIntents payable), found during the live gate"
affects: [30-09, 30-10, 30-32]

actuals:
  tasks: 3
  commits: 5        # on the PR branch for this plan: fe7e659d, 29601edf (Task 1), f439df8c, 86291e96, 095b5d30 (Task 2 review fixes); plus this SUMMARY commit on fix/108-stripe-timeouts

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-08-958-live.txt
    - .planning/phases/30-live-stripe-test-mode-execution/30-08-SUMMARY.md
  modified:
    - .gitleaksignore
    - docs/CHANGELOG.md
    - HANDOFF.md
    - scripts/gates/object-store-residue-allowlist.conf
    - frontend/components/dashboard/orders/awaiting-payment-badge.tsx
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/components/dashboard/kitchen/kds-new-lane.tsx
    - docs/metrics.json

requirements-completed: []
---

# Plan 30-08 Summary: PR 2 merged, deployed, and the awaiting-payment gate proven on staging

**On the delivered runtime, an unpaid card order cannot be pushed to the kitchen, and a paid order and a vendor's own order still move.** PR #1030 squash-merged as `7b117162`. Staging runs those images by digest. Submitting a fresh unpaid card DRAFT on staging returned `http=409 type=https://jtoye.uk/errors/awaiting-payment`, and the order stayed DRAFT. The two controls both returned 200.

## Task 1: PR open (2026-10-22)

PR #1030 opened at the 30-07 head with every per-PR suite green:
- core-java: 1947 unit and 1369 integration tests.
- Jest: 3207 tests.
- lint rc 0, the seed selftest (41 arms), and the env, gate, docs and behind-base checks.

gitleaks found one hit in the branch range: 30-07's own break-arm evidence quoting a synthetic stub client secret. It is pinned by commit fingerprint in `.gitleaksignore` (fe7e659d). The CHANGELOG and HANDOFF were updated in 29601edf. Body scans found no attribution and no closing keyword, and the fail arms of each scan went red.

## Task 2: review series and merge (2026-10-22)

- **Before review.** Operational Contracts was red: 30-06 had shifted an allow-listed `frontend/types/api.ts` comment from line 558 to 564. Renumbered in f439df8c; the residue gate went from rc 1 to rc 0. The first Frontend E2E failure was a CI Google Fonts fetch error, which was green on rerun.
- **Round 1** (`/code-review 1030 --comment`, on f439df8c). 8 findings, 4 of them admissible.
  - Three fixed: RED 86291e96, GREEN 095b5d30.
    - An unpaid card DRAFT offers no Cancel in the list.
    - The badge is silent on closed orders (new `orderStatus` prop).
    - Start Prep is gated too.
  - One filed as issue #1031 [data].
  - Jest went to 3212/3212 and the counts from 6593 to 6598.
- **Round 2** (`/review-verify 1030`, on 095b5d30). Zero admissible. The Review-Record names `095b5d306d98f991fae7a761133b174854ccc028`.
- **Merge.** At 23:03:57Z the head matched the PR head, behind-base was rc 0, main was idle and the squash-message scan was rc 0. Squash-merged at 2026-10-22T23:04:16Z as `7b117162e80a943af4186f2255c40329cd8d145c`. Issue #958 is left open for the owner.

## Task 3: deploy, parity, live arms (2026-10-24)

- **Deploy.** ci-cd run 39869345195: Deploy to Staging success, Staging Edge Probe success.
  - The run initially ended red on one job, Frontend E2E. Its browser-install step got an apt 403 from packages.microsoft.com and no test ran.
  - No job depends on that one, so it was rerun alone and passed. The run is now success.
  - The rollback rule was not triggered.
- **Parity.** Pod `imageID` equals the ghcr digest of tag `7b117162…` for core-java (`eb456f80…`), edge-go (`f5cc414c…`) and frontend (`11f476f7…`).
- **Live arms.** Times are UTC, outputs are verbatim in the evidence file, and the PII pattern found 0 customer matches.

  | time | arm | result |
  |---|---|---|
  | 22:20:56 | list-drafts | count=0: the planned target `…0C094A2C` no longer exists (deviation 1) |
  | — | vendor-order | `ORD-1AAC0CDE-20261024-C555E294` status=DRAFT payment_status=NONE |
  | 22:23:19 | submit C555E294 | http=200 (D-01 control) |
  | 22:23:23 | confirm D69764AE | http=200 (paid control) |
  | 22:25:34 | fresh unpaid card order | `ORD-1AAC0CDE-20261024-C6DA71F1`, intent `requires_payment_method`, livemode false |
  | 22:25:37 | list-drafts | count=1 card=1, payment_status=PENDING |
  | 22:25:40 | submit C6DA71F1 | **http=409 type=https://jtoye.uk/errors/awaiting-payment**, detail names the order, no `#` |
  | 22:25:42 | list-drafts | still DRAFT, PENDING |
  | 22:26:02 | cancel-drafts C6DA71F1 | intent canceled, order CANCELLED; Stripe reads `canceled`/`abandoned` |
  | 22:26:08 | list-drafts | count=0 |

## Deviations

1. **The planned 409 target had been deleted.** `ScheduledCleanupService.cleanupStaleDraftOrders` runs at 03:00Z and deletes DRAFTs older than 24 h.
   - It deleted `ORD-…0C094A2C` and the six 2026-10-19 swarm DRAFTs.
   - Their 7 PaymentIntents are still `requires_payment_method` in Stripe, so they stay payable with no order behind them.
   - Before trusting the 0, it was checked against a control: the same vendor read across all eight statuses sees 6 orders, including both CAPTURED ones.
   - The 03:00Z log line is gone: the pod was rescheduled at 03:42Z. The attribution rests on the code path and the timing.
   - Filed as issue #1038.
   - Owner ruling 2026-10-24: prove the 409 on a fresh unpaid order and cancel it straight after. That used a scratch, uncommitted copy of `staging-seed.sh` with two lines changed: a one-off Idempotency-Key, and a return before the `pm_card_visa` confirm.
2. **kubectl auth.** kubelogin's `azurecli` mode follows the ambient az default.
   - The AKS token was minted with `az account get-access-token --subscription c483d353-…` (JToye Digital Production).
   - It was passed to kubectl as its bearer token through a scratchpad shim.
   - The kubeconfig and the az default are unchanged.

## For 30-09

- D-02 hand-cancellation has no orders left to cancel: `cancel-drafts` looks each order up first, and the cleanup deleted them.
- The 7 orphaned intents listed in issue #1038 need cancelling in Stripe TEST another way, so 30-09's route must change before it runs.

## Left on staging

- `ORD-…C555E294`: a vendor NONE order, now PENDING (T-30-127, accepted).
- `ORD-…D69764AE`: now CONFIRMED.
- `ORD-…C6DA71F1`: CANCELLED, its intent canceled.
