---
phase: 30-live-stripe-test-mode-execution
plan: 15
subsystem: stripe-connect-live
tags: [stripe, connect, webhook, staging, csi, key-vault, ledger, pay-03]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-14: the Connect endpoint we_1UWN2NDpS9ICNeRtxSQMsc2M and stripe-credentials--connect-webhook-secret in jtoye-stg-kv"
provides:
  - "PR 4 (#1046) squash-merged as ec73ddee (review 1 -> 0 -> 0 admissible) and deployed green (run 41150777130)"
  - "The Connect secret mounted in core-java: STRIPE_CONNECT_WEBHOOK_SECRET (38 chars, whsec_) and Secret key stripe-credentials/connect-webhook-secret, re-read on the 480782d7 pod"
  - "Live Connect route refuses an unsigned event (400 missing-header) and a wrong-secret signature (400, reached Stripe's verification); 0 ledger rows for either, beside a 1-row positive control in the same session"
  - "Platform route still settles: staging card checkout CAPTURED (ORD-1AAC0CDE-20261031-9BBA6DE2, pi_3UWhdoDpS9ICNeRt4B0SR5BX, livemode false)"
  - "Branch feat/30-pay-link-page for PR 5"
affects: [30-16, 30-31]

estimate:
  tasks: 3
actuals:
  tasks: 3
  commits: 2        # 263c086f (03:47Z record, rebased onto 480782d7) + this SUMMARY/state commit

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-15-connect-live.txt

key-decisions:
  - "The ledger read used Task 3's real payment_intent.succeeded event (evt_3UWhdoDpS9ICNeRt4WyI0zjZ) as the positive control, so both zero-row reads come from a statement shown to see a row that exists"
  - "The 03:43Z repair (delete the driver-synced Secret, restart core-java) was a staging-only action; the cause was fixed in CI by #1055/#1056 (issue #1054, CLOSED) and the secret was re-read after three later rollouts"

requirements-completed: [PAY-03]   # contributes; the positive Connect arm needs a connected account (30-31)

coverage:
  - id: D1
    description: "The deploy mounts the Connect secret"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-15-connect-live.txt (secret_key=0 at 02:08Z after the green deploy = the plan's verify FAILING; secret_key=1 at 03:46Z after repair and at 19:08Z on the 480782d7 pod)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The live Connect route stays shut to forgeries"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-15-connect-live.txt (unsigned 400, forged 400 with the PaymentService signature WARN; ledger: positive control 1 row, both forged ids 0 rows, 0 forged/probe ids in the table, one session as jtoye_backup)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The platform door still settles real TEST payments"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-15-connect-live.txt Task 3 (staging-card-checkout.spec.ts rc 0, 1 passed, payment_status=CAPTURED livemode=false)"
        status: pass
    human_judgment: false

completed: 2026-10-31
---

# Phase 30 Plan 15: PR 4 live on staging Summary

**PR 4 is merged and live. The Connect route on staging has its signing secret, refuses unsigned and forged events with nothing written to the ledger, and the platform route still settles a real TEST card payment.**

## Task results

**Task 1: merge.** PR #1046 squash-merged 2026-10-31T00:33Z as `ec73ddee` after review rounds admitting 1, 0 and 0. Round 1's admissible finding was fixed in 4db01b91 (an own-environment intent for a missing order now throws, so Stripe retries instead of the payment being acknowledged and lost); 8 findings were recorded on the PR. Run 41150777130 was green through Deploy to Staging and the Staging Edge Probe.

**Task 2: secret mounted, forgeries refused.**
- Digest parity at `ec73ddee`: all three services MATCH (rc 0); the planted-stale arm rc 1.
- **The plan's own verify caught a real defect.** After a fully green deploy it read `secret_key=0`: core-java ran without the Connect secret. Two causes, both measured: `kubectl apply -k` applies Deployments before SecretProviderClasses, so the new pod mounted the old SPC; and the AKS addon does not resync an existing synced Secret. The pod stayed Ready because the env ref is `optional: true`, so every gate stayed green. Repaired on staging at 03:43Z (delete the synced Secret, restart core-java), filed as issue #1054, fixed in CI by #1055 and #1056, and CLOSED.
- After the repair: unsigned -> 400 (`missing-header`); forged signature -> 400 with core-java logging Stripe's "No signatures found matching". The forged arm reaching Stripe's check is what proves a non-blank secret was loaded (a blank one refuses earlier).
- Re-read 2026-10-31T19:08Z on the pod running `480782d7`: `secret_key=1`, env 38 chars with the `whsec_` prefix, unsigned probe 400.
- Ledger, one session, as `jtoye_backup` (bypassrls t), same prepared statement: positive control `evt_3UWhdoDpS9ICNeRt4WyI0zjZ` 1 row; both forged ids 0 rows; 0 forged or probe ids in the table (4 rows total).

**Task 3: platform route positive control.** `staging-card-checkout.spec.ts` rc 0, 1 passed: `ORD-1AAC0CDE-20261031-9BBA6DE2` CAPTURED, livemode false. Its webhook event was processed at 19:08:41Z, five seconds before the spec read CAPTURED.

## Deviations

1. **The Key Vault object was not re-read in the same sitting as the merge** (plan Task 1 step 2). The last pre-merge read was 30-14 Task 3 (2026-10-30T21:09Z, 0 gaps); the post-merge re-read at 00:37Z shows the object enabled, updated 2026-10-30T21:08Z, so it existed at merge time. Nothing went wrong because of it, but the gate the plan put there was skipped.
2. **Task 2 failed its verify after a green deploy** and needed an out-of-plan staging repair (see above). Recorded as a finding, not glossed: the plan's check is the only thing that saw it.
3. **The work spanned two sittings.** The 03:47Z sitting left the ledger read and Task 3 outstanding on an unpushed branch; this sitting rebased it onto `480782d7`, re-read the wiring and completed both.
4. **Task 3 ran from the main checkout** (it has `frontend/node_modules`; this worktree does not). The spec and the lock file are byte-identical between the two (`git diff --quiet origin/main` rc 0).

## Next

30-16 (PR 5, PAY-04 / D-03): two owner decisions first (token in the path vs the fragment; how staging handles OTP), then the non-consuming payment-link preview the `/pay` page needs.

## Self-Check: PASSED

- `evidence/30-15-connect-live.txt` holds every result above with UTC times, has no OUTSTANDING section, and leak-scans rc 0 (control with the real connect secret planted: rc 1).
- `feat/30-pay-link-page` is 0 behind `origin/main` (480782d7) at commit time.
