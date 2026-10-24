---
phase: 30-live-stripe-test-mode-execution
plan: 04
subsystem: payments
tags: [stripe, playwright, staging, e2e, ci, deploy, digest-parity, evidence]

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-03 the staging card-checkout spec and its RED arms on 4208b08e; PR #1029"
provides:
  - "Issue #957 proven fixed on the delivered staging runtime: merge 43d047fb, run 39803113150 green through Deploy to Staging and Staging Edge Probe, digest parity rc 0"
  - "Live checkout chunks carry exactly one pk_test_ key (distinct_pk=1, length 107), rc 0; the same command was rc 1 in 30-03"
  - "One TEST card payment captured on staging and read back from Stripe: ORD-1AAC0CDE-20261022-D69764AE, pi_3UTPKkDpS9ICNeRt0VWSmyrM, livemode false"
  - "Issue #957 evidence comment https://github.com/Bralabee/JToye_OaaS_2026/issues/957#issuecomment-6346480557 (no closing keyword, no attribution, issue still OPEN)"
  - "Branch fix/958-awaiting-payment-gate cut from origin/main 43d047fb, carrying this record for PR 2"
affects: [30-05, 30-09, 30-18]

actuals:
  tokens: 7200
  tasks: 3
  commits: 1

plan_head_before: 43d047fb5514b19a0d24daff1e3ad940e6623d75
plan_head_after: 3e69bca606602a3f3fe31d3fca53bc8dc5dcd631

tech-stack:
  added: []
  patterns:
    - "A staging green arm names its runtime by digest (parity against the merge sha, and a fail arm against the previous main sha) before the functional check runs"
    - "A Stripe read-back by secret key goes through curl -K - (stdin config), so the key never reaches argv or output"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-04-957-live.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-04-payment-step.png
  modified: []

key-decisions:
  - "The spec ran with .env STRIPE_PUBLISHABLE_KEY (orchestrator instruction), compared in-process to the repository variable the image was built from: equal, so the plan's gh-variable form and this run use the same key"
  - "The run was resolved from the main-branch run list by headSha, because gh run list --commit returned [] for a run that exists; empty was treated as VOID, never as 'no run'"
  - "A second, independent Stripe read with the staging secret key was added to the spec's publishable-key read, plus a 404 fail arm"

patterns-established:
  - "gh run list --commit is not a reliable resolver here: it returned [] for an existing push run (and for every PR run under --workflow)"

requirements-completed: [PAY-03, PAY-04]

coverage:
  - id: D1
    description: "The merge commit's CI run is green through Deploy to Staging and Staging Edge Probe, including the #957 key-guard and live-chunk steps"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "gh run view 39803113150 --json jobs (plan verify 1: rc 0 on 39803113150; rc 1 on PR run 39791106322 where both jobs are skipped; VOID on empty or nonexistent id)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The running staging pods are the merge commit's images"
    verification:
      - kind: other
        ref: "bash scripts/check-deploy-digest-parity.sh --context jtoye-stg-aks --tag 43d047fb… (rc 0, 3 MATCH; rc 1 with --tag 11e812ef…)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The live checkout chunks carry exactly one pk_test_ key, no residue, no secret literal"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "scripts/check-frontend-stripe-key-baked.sh against app-staging (rc 0, distinct_pk=1; rc 1 with --expect-prefix pk_live_; rc 2 with an absent control; 30-03 RED rc 1 distinct_pk=0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "A storefront card order on staging shows the Stripe payment step, pays with 4242, captures, and Stripe reports the intent succeeded and livemode false"
    requirement: PAY-03
    verification:
      - kind: e2e
        ref: "frontend/e2e/staging-card-checkout.spec.ts on app-staging (rc 0, 1 passed, STAGING-CARD … payment_status=CAPTURED livemode=false; 30-03 RED rc 1 at l.213)"
        status: pass
      - kind: other
        ref: "GET /v1/payment_intents/pi_3UTPKkDpS9ICNeRt0VWSmyrM with the staging secret key (200, succeeded, livemode false, charge captured; altered id -> 404)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Issue #957 carries a clean evidence comment and stays OPEN for the owner"
    verification:
      - kind: other
        ref: "gh issue view 957 comments[-1] (diff vs posted file 0 lines; attribution=0 evidence_lines=2 state=OPEN; planted attribution and stripped evidence both fail the predicate)"
        status: pass
    human_judgment: false
  - id: D6
    description: "UI a11y backstop: the Stripe iframe's selected tab and focus ring in #c2410c read as AA against white"
    verification: []
    human_judgment: true
    rationale: "axe cannot enter the cross-origin iframe. The executor read the selected Card tab as pass (5.18:1 computed). The focus ring is not in the screenshot, because the capture is taken before any field is focused, so that half is unverified and the owner's look at evidence/30-04-payment-step.png is the backstop"

duration: "~7 min (16:56Z to 17:03Z; the merge run was already terminal)"
completed: 2026-10-22
status: complete
---

# Phase 30 Plan 04: Issue #957 Is Fixed on the Delivered Staging Runtime Summary

**Merge 43d047fb deployed to staging through run 39803113150, with Deploy to Staging and Staging Edge Probe both success. All three staging pods are that commit's images by digest. The live checkout chunks now carry one `pk_test_` key: rc 0, where the same command gave rc 1 in 30-03. One 4242 TEST card order was paid and captured: `ORD-1AAC0CDE-20261022-D69764AE`, intent `pi_3UTPKkDpS9ICNeRt0VWSmyrM`. Stripe reads that intent back as succeeded with `livemode` false.**

## Performance

- **Duration:** about 7 min. The merge run was already terminal when this continuation started, so no watch loop ran.
- **Started:** 2026-10-22T16:56:36Z
- **Completed:** 2026-10-22T17:03Z
- **Tasks:** 3. Task 1 is the owner's and is recorded from the continuation. Tasks 2 and 3 ran here.
- **Files:** 2 evidence files created, plus this SUMMARY.

## Accomplishments

- **Deploy.** Run https://github.com/Bralabee/JToye_OaaS_2026/actions/runs/39803113150 (push, main, 43d047fb) succeeded. Build and Push succeeded on all four legs. Deploy to Staging succeeded, including steps 7 and 8 (the publishable-key guard selftest and the guard), digest parity at step 17, and smoke tests. Its rollback step was skipped because nothing failed. Staging Edge Probe succeeded, including step 4, which reads the key from the live chunks.
- **Runtime parity** (operator host, `--context jtoye-stg-aks`). `check-deploy-digest-parity.sh --tag 43d047fb…` matched all 3 services, rc 0. The fail arm with `--tag 11e812ef…` gave 3 NOT IN THE EXPECTED SET, rc 1. The frontend pod `frontend-6c585fd968-lpsmj` runs `jtoye-frontend:43d047fb…`, digest `sha256:198ae6ca…`, started 16:50:37Z.
- **Live chunks, GREEN.** `chunks=22 distinct_pk=1 keys=pk_test_(length=107) residues=0 secrets=0 control=1`, rc 0. The 30-03 RED was `distinct_pk=0 residues=1`, rc 1. Fail arm: `--expect-prefix pk_live_` gave rc 1. VOID arm: an absent control gave rc 2.
- **Staging spec, GREEN on the first and only attempt.** rc 0, `1 passed`, 16.6 s. Tracking moved from `DRAFT/PENDING` to `PENDING/CAPTURED` within 5 s. Output: `STAGING-CARD order=ORD-1AAC0CDE-20261022-D69764AE intent=pi_3UTPKkDpS9ICNeRt0VWSmyrM payment_status=CAPTURED livemode=false at=2026-10-22T16:59:28.309Z`. Leak counts over the full output were 0 for the key value and 0 for the client-secret pattern. The pattern's positive control counted 1.
- **Stripe read-back, two ways.** The spec read the intent with the publishable key and the client secret: HTTP 200, so both keys belong to one account. I also read it separately with the staging secret key, passed through a stdin config: `succeeded`, `livemode=False`, 950 GBP, `metadata.order_number` equal to the order, charge `ch_3UTPKkDpS9ICNeRt0HnGrNrd` with `captured=True` and `livemode=False`. Altering one character of the id gave 404.
- **Issue #957 comment** https://github.com/Bralabee/JToye_OaaS_2026/issues/957#issuecomment-6346480557 was posted from a scanned file. The read-back diff against that file was 0 lines. Attribution 0, the evidence lines are present, and the issue is still OPEN.

## Task Commits

1. **Task 1 (owner): review series and squash-merge.** No executor commit. PR #1029 was merged as `43d047fb`. Round 1 had 0 of 9 findings admissible on c1e5bb50. Round 2 had 0 admissible. The Review-Record names 24ea1ab3, which equals the PR's `headRefOid` read back here.
2. **Tasks 2 and 3: GREEN evidence and the #957 comment.** `3e69bca6` (test).

**Plan metadata:** the SUMMARY commit on top of `3e69bca6`.

## Files Created/Modified

- `.planning/phases/30-live-stripe-test-mode-execution/evidence/30-04-957-live.txt`: Task 1 facts, every job and key step, parity in both directions, the RED and GREEN pairs side by side, the Stripe read-backs, the a11y reading, and the comment record.
- `.planning/phases/30-live-stripe-test-mode-execution/evidence/30-04-payment-step.png`: the payment step, captured by the spec before any card data was typed.

## Verification (both directions)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| Plan verify 1 (both jobs exactly success) | PR run 39791106322: `skipped,skipped`, rc 1. Empty RUN_ID: VOID 2. Run 1: 404, VOID 2 | 39803113150: `Deploy to Staging=success,Staging Edge Probe=success`, rc 0 |
| Digest parity | `--tag 11e812ef…`: rc 1 | `--tag 43d047fb…`: rc 0 |
| Plan verify 2 (live chunks) | 30-03 on 4208b08e: rc 1. Today with `pk_live_`: rc 1. Absent control: rc 2 | rc 0, distinct_pk=1 |
| Plan verify 3 (staging spec) | 30-03: rc 1, `1 failed`, no STAGING-CARD line | rc 0, `1 passed`, verdict `payment_status=CAPTURED livemode=false at=` |
| Stripe secret-key read | altered intent id: 404 | 200, succeeded, livemode false |
| Tree clean after the run | n/a (structural) | `git status --porcelain -- frontend/`: rc 0, lines 0 |
| Comment scan (`check-dply02 --scan-text`) | body plus planted "Closes #957" and "Co-Authored-By: x": findings 2, rc 1 | findings 0, rc 0 |
| gitleaks (comment and evidence) | planted realistic `sk_test_` fake: leaks 1, rc 1 | no leaks, rc 0 (also `--staged` before the commit: rc 0) |
| Read-back equals file | one appended line: `diff` rc 1 | `diff` rc 0, 0 lines |
| Plan verify Task 3 #1 | planted attribution: 1. Evidence tokens stripped: 0 | attribution 0, evidence_lines 2, state OPEN |
| Plan verify Task 3 #2 | before the commit the branch was 0 ahead of origin/main | run after the SUMMARY commit (see Self-Check) |

**UI a11y backstop.** By eye, the selected "Card" tab draws its label, icon and border in the darker orange and reads clearly against white. **Contrast read by eye: pass for the selected tab.** Computed, #c2410c on white is 5.18:1; the control, #f97316, is 2.80:1. The focus ring is **unverified**: the screenshot is taken before any field is focused, by design. The full-page capture also paints the sticky site header across the Payment Element. That is how a fullPage screenshot renders a fixed header, and the spec typed into every field and paid through the same page. Recorded, not judged.

## Decisions Made

- The spec used `.env` `STRIPE_PUBLISHABLE_KEY`, as the orchestrator instructed. I compared it in-process to the repository variable the image was built from, and they are equal. The plan's `gh variable get` form would have passed the identical key.
- I added a second Stripe read with the secret key. The publishable-key read already proves the key pair; this read adds the charge's capture state and the order metadata.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The plan's run resolver returned empty**
- **Found during:** Task 2
- **Issue:** `gh run list --workflow ci-cd.yaml --commit 43d047fb…` returned `[]` with rc 0. So did `--commit` without the workflow filter, although run 39803113150 exists for that sha.
- **Fix:** I treated the empty result as VOID, as the plan says, not as "no run". I resolved the run with `gh run list --workflow ci-cd.yaml --branch main` matched on headSha, then confirmed it with `gh run view` (headSha, event, workflow).
- **Files modified:** none (recorded in the evidence)
- **Committed in:** 3e69bca6

**2. [Process] The comment body is in the session scratchpad,** not `/tmp/claude-30-04/957.md`, because the session's temp-file rule takes precedence. This is the same deviation 30-03 made for the PR body. The content and the scan are unchanged.

**3. [Process] Key source.** The spec key came from `.env`, as the orchestrator instructed, not from `gh variable get` as the plan wrote. I showed the two are equal (above).

**4. [Process] Root pin.** I ran the orchestrator's `<project_root_pin>` logic from a scratchpad copy with its comments and the closing advisory echo lines removed. The checks and exit paths are identical. It printed PIN OK before the first write and before the commit.

**Total deviations:** 1 auto-fixed (blocking: the instrument) and 3 process notes. **Impact:** none on scope.

## Issues Encountered

- `gh run list --commit` cannot be trusted as a resolver on this repository: it returned `[]` for an existing run. A gate that reads "none" as "nothing to wait for" would pass vacuously. The plan's VOID rule caught it.
- The shell's default kube context is `jtoye-staging`, not `jtoye-stg-aks`. Every kubectl call and parity run here named `--context jtoye-stg-aks` explicitly.
- A gitleaks control built from low-entropy a-z/0-9 runs is not flagged (rc 0). The control that counts uses a realistic-entropy fake.
- After capture the order reads `status=PENDING`, coming from DRAFT. That is #958's territory, the next PR on this branch, and is not asserted here.

## Known Stubs

None.

## Threat Flags

None. No code changed. T-30-14 is satisfied: the head equals the Review-Record head (24ea1ab3), and the pre-merge gates were run by the owner/orchestrator. T-30-15 is satisfied: digest parity in both directions plus the live chunk read. T-30-16 did not apply, because the deploy was green and the rollback rule was not triggered. T-30-17 is satisfied: the comment carries ids only, and it was scanned, gitleaks-checked and read back.

## User Setup Required

None.

## Next Phase Readiness

- Branch `fix/958-awaiting-payment-gate` is pushed with this record. No PR is open, because the next plan continues on this branch.
- Staging data: one CAPTURED TEST order, `ORD-1AAC0CDE-20261022-D69764AE`. Plan 30-09 still cancels the 30-03 DRAFT, `ORD-1AAC0CDE-20261022-0C094A2C`.
- The owner may close issue #957 after reading the comment. The focus-ring half of the a11y backstop is the owner's to judge.

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-10-22*

## Self-Check: PASSED

- FOUND: the evidence file, the screenshot and this SUMMARY on disk. Control: a nonexistent path is reported MISSING.
- FOUND: 3e69bca6 and 43d047fb are ancestors of HEAD. Control: deadbeef is reported MISSING.
- Artifact marker: the evidence file contains `STAGING-CARD` 6 times.
- Secret-shape scan (`sk_test_`/`pk_test_` followed by 10 alphanumerics, or `_secret_` followed by an alphanumeric, case-insensitive) matched 2 lines in the evidence file and 0 in the SUMMARY. Both evidence lines are benign: the planted control literal `pi_FAKE_secret_abc123`, and the variable name `STRIPE_SECRET_KEY`. Neither is a key or a client secret.
