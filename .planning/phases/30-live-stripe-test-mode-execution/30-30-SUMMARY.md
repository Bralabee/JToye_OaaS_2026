---
phase: 30-live-stripe-test-mode-execution
plan: 30
subsystem: payments
tags: [stripe, connect, managed-risk, idempotency, staging, pay-03, review-series, deploy-parity]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-28 --stage connect-onboard --link-out and --stage money-loop --mode destination; 30-29 the direct loop, payout and run-id replay; 30-12 StripeConnectService account creation and the Connect webhook route"
provides:
  - "PR 8 #1089 merged 0ffad575 (04:41Z) and deployed with digest parity; the RED refusal recorded (money-loop --mode destination REFUSED [connect-status], tenant NONE)"
  - "Issue #1090 [observed]: Stripe refused the legacy type=express create (platform not the losses collector). Owner RULED option A, Managed Risk, 2026-11-02"
  - "Fix PR #1091 merged 40d91e0e: controller properties losses.payments=stripe, fees.payer=account, requirement_collection=stripe, stripe_dashboard.type=full (quick task 261102-apf)"
  - "Fix PR #1092 merged de5d86f4: the account-create idempotency key names the body (16-hex SHA-256 of the sorted form params), after Stripe replayed the saved refusal under the tenant-only key"
  - "Staging tenant 1aac0cde-ab0c-4172-8fc8-4cfcbdc378b4 holds acct_1UXIfkDK5xypxMXe, status PENDING; the single-use onboarding link is in $HOME/.local/state/jtoye/connect-onboard.url (mode 600, outside the repo)"
affects: [30-31, 30-32]

actuals:
  tokens: 0         # not measured for this plan: three sessions of orchestration, two fix PRs and two review series; the fix PRs' own diffs are on #1091 and #1092
  tasks: 3
  commits: 2        # on docs/30-phase-close before this SUMMARY: 40ad9376 (stale key wording), plus this record commit; the product commits are the squash merges 0ffad575, 40d91e0e, de5d86f4
plan_head_before: 2fe0837b0565edf97738184e0ede02f9ea513278
plan_head_after: de5d86f428dfd9a607dd6304f58ad49b0bfe2c7b

tech-stack:
  added: []
  patterns:
    - "A Stripe connected account is created under Managed Risk controller properties, never the deprecated type field; the dashboard is full because the Express dashboard with Stripe as losses collector is preview-only"
    - "A Stripe idempotency key for a create names the body it protects (tenant + 16-hex SHA-256 of the flattened, sorted form params): a refused first attempt can no longer block a corrected body for 24 h, and an identical SDK retry still replays"

key-files:
  created:
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-30-pay03-open.txt"
    - ".planning/quick/261102-apf-fix-1090-stripe-refuses-connected-accoun/ (PLAN, SUMMARY, evidence/1090-red-green.txt; merged in #1091)"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/payment/StripeConnectService.java (via #1091, #1092)"
    - "core-java/src/test/java/uk/jtoye/core/payment/StripeConnectServiceTest.java (via #1091, #1092)"
    - "docs/api/openapi-snapshot.json (3 description lines, #1091)"
    - "HANDOFF.md, docs/CHANGELOG.md, docs/metrics.json, README.md, CLAUDE.md, AGENTS.md (#1091, #1092)"

key-decisions:
  - "RULED 2026-11-02 (owner): option A, Managed Risk. Stripe collects losses; the vendor pays Stripe's processing fee and the application fee is the platform's share only, which docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md already assumed (pass-through Stripe fees). Option B (platform liability acknowledgement in the Dashboard, Express kept) was declined"
  - "Issue #1090 was filed [observed] under the admission test and closed by the 10:41Z staging run that created the account; the 7 + 7 review findings of #1091 and #1092 were recorded on the PRs, none filed"
  - "The idempotency key is derived from the body rather than versioned by hand, so no release has to remember to bump it; the trade (a retry after body drift can create a second account where the old key refused) is recorded on #1092 round 1"

patterns-established:
  - "Verdict line unchanged from 30-28: CONNECT-ONBOARD tenant=… account=acct_… status=PENDING; the link goes to --link-out only (mode 600) and the owner is told the path"

requirements-completed: []

duration: "2026-11-02 04:21Z to 10:41Z, across three sessions (Task 1-2 04:21Z-04:41Z; Task 3 blocked 05:29Z, resumed 07:40Z, done 10:41Z)"
completed: 2026-11-02
---

# Phase 30 Plan 30: PR 8 merged, the staging refusal recorded, and the owner's onboarding link ready Summary

**PR 8 #1089 merged and deployed with parity. The staging money loop was refused as planned (no connected account). The connect-onboard stage then hit a real defect: Stripe refused the legacy `type=express` create because the platform had never accepted loss liability. The owner ruled Managed Risk, two fix PRs landed the same morning (controller properties, then a body-fingerprinted idempotency key after Stripe replayed the saved refusal), and the third run created `acct_1UXIfkDK5xypxMXe` for the staging tenant. The single-use onboarding link is in a mode-600 file outside the repo for 30-31.**

## Accomplishments

- **Task 1 (04:21Z):** `--stage money-loop --mode destination` on staging at `2fe0837b` returned rc 1 `REFUSED [connect-status]`, 0 `LOOP-ORDER`, no order and no Stripe object; the transient operator was deleted (204). Evidence `evidence/30-30-pay03-open.txt` §1.
- **Task 2 (04:41Z):** PR 8 #1089 (30-27 evidence + 30-28/30-29 stages, selftest 88/0, no product change) merged `0ffad575` after a round-1 review that admitted 0 of 8 (all recorded with the operational guards for 30-31/30-32). Main run 41389859836: Deploy to Staging and Staging Edge Probe success; digest parity MATCH for the three images, re-read live, control against `2fe0837b` rc 1.
- **Task 3, attempt 1 (05:29Z, RED):** `--stage connect-onboard` rc 1. core-java answered 502 with Stripe's "You tried to create an Accounts v1 connected account using the legacy `type` field with your platform as the losses collector". Nothing written. Filed as issue #1090 [observed] (every vendor's onboarding would fail the same way in production).
- **Owner ruling (07:4xZ):** option A, Managed Risk. Recorded in memory and on #1090.
- **Fix PR #1091** (`fix/1090-connect-managed-risk`, quick task 261102-apf): `setType(EXPRESS)` replaced by controller properties `losses.payments=stripe`, `fees.payer=account`, `requirement_collection=stripe`, `stripe_dashboard.type=full`; test-first (22/2 on the old service, 22/0 after); "Express" wording to "connected account"; OpenAPI snapshot regenerated (3 lines); HANDOFF.md's stale "#1089 OPEN" claim fixed after it redded `check-handoff-contract` H-2 on the PR. Round 1 code-review 0 of 7 admissible, round 2 review-verify 0; merged `40d91e0e` 08:47Z; run 41415635209 deployed; parity MATCH (control `0ffad575` rc 1).
- **Task 3, attempt 2 (09:21Z, RED again):** rc 1, 502 `Keys for idempotent requests can only be used with the same parameters they were first used with`. Stripe had saved the 05:29Z refusal under `connect-account-create-<tenantId>`. Round-1 finding 1 of #1091, now observed.
- **Fix PR #1092** (`fix/1090-connect-idempotency-key`, `/gsd-fast`): `accountCreateIdempotencyKey(tenantId, params)` appends 16 hex of SHA-256 over the flattened, key-sorted form params; new test `accountCreateIdempotencyKey_followsTheBody`; break arm with a constant fingerprint reds exactly that test (23/1), restored byte-identical (23/0); metrics 3164 / 6997 and the three prose copies moved. Round 1 code-review 0 of 7 admissible; merged `de5d86f4`; run 41425376834 deployed; parity MATCH (control `40d91e0e` rc 1).
- **Task 3, attempt 3 (10:41Z, GREEN):** rc 0. `CONNECT-ONBOARD tenant=1aac0cde-ab0c-4172-8fc8-4cfcbdc378b4 account=acct_1UXIfkDK5xypxMXe status=PENDING`. Link file `$HOME/.local/state/jtoye/connect-onboard.url`, mode 600, 70 bytes, exactly one `https://…stripe.com/` line, absent from every output and from the evidence (fail arm: a scratch copy with the URL pasted in counts 1). Issue #1090 closed with the evidence.

## Task Commits

- Product: `0ffad575` (#1089), `40d91e0e` (#1091), `de5d86f4` (#1092), all squash merges on main.
- Record (this branch, `docs/30-phase-close`): `40ad9376` the three stale tenant-only-key mentions; this SUMMARY with the evidence file, STATE and ROADMAP.

## Deviations from Plan

**1. [Blocking defect, owner ruling] The plan assumed the create would succeed.** It could not: Stripe's refusal is independent of our code path and surfaced only against live Stripe (the 30-28 selftest stubs curl). Resolved by the ruling and #1091. Impact: Task 3 took three attempts and ~5 hours instead of one run.

**2. [Second observed defect] The idempotency key replayed the saved refusal.** #1091 round 1 had flagged it; it was recorded on the doc's "validation failures save nothing" wording and then observed. Resolved by #1092. Lesson recorded on both PRs: when a key guards a body that has just been refused, the doc's distinction between validation and execution is not something to rely on; name the body in the key.

**3. [Gate] HANDOFF.md's capitalised "#1089 OPEN" redded docs-freshness on #1091.** Fixed in #1091 (`fc233866`); the gate was shown to fail on the old file and pass on the new one.

**4. [Record] `.planning/STATE.md` quick-task row for the `/gsd-fast` task has the tool's fallback shape** (ordinal id, no directory). Recorded on #1092; left as the tool writes it.

## Carried to 30-31 / 30-32 (recorded on #1091 and #1092, not filed)

- The vendor now receives a FULL Stripe Dashboard login (not Express). The return page copy does not say so. 30-31 is the owner walking that page.
- A full-dashboard vendor can refund outside the platform; `RefundService` drops metadata-less `refund.*` events under UC-5 LOCKED. Premise changed; owner to re-open UC-5 or restrict refunds in Stripe. 30-32's refund arm is where this is observed.
- `--stage payout` may be refused for a Managed Risk account (the selftest stub still answers `type: express`). 30-32 observes it.
- Refund the destination order BEFORE switching the shop to `DIRECT_CHARGE_VENDOR_MOR`; after the first live `--set`, read the storefront; run the direct loop before `--stage payout`; one `--run-id` per loop.
- ADR-0001 lines 5 and 156 still describe Express accounts; comments in `scripts/staging-seed.sh`, `k8s/base/configmap.yaml:447` and two frontend file headers still say Express.
- A retry after body drift can create a second connected account where the tenant-only key refused it (#1092 round 1 finding 1); if observed, reconcile on `account.updated` by `metadata.tenant_id`.

## Threat Flags

- T-30-30-01 (mitigated): the onboarding URL is a bearer credential to the vendor's Stripe onboarding; it exists only in the mode-600 file, never in stdout, stderr, scratch output or evidence (asserted both directions).
- T-30-30-02 (recorded): with `fees.payer=account` the vendor pays Stripe's processing fee; the application fee must stay the platform's share only (javadoc + CHANGELOG state it; no computation changed).

## Self-Check: PASSED

- Evidence file present, URL-free, with the three attempts and both parity reads: yes.
- Plan `<automated>` verify: mode 600, one Stripe URL line, URL absent from evidence, CONNECT-ONBOARD line present: PASS; fail arm counted 1.
- Issue #1090 CLOSED by the run; PRs #1089, #1091, #1092 MERGED.
