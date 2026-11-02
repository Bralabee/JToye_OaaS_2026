---
phase: 30-live-stripe-test-mode-execution
plan: 27
subsystem: payments
tags: [stripe, billing, per-location, staging, evidence, pay-02, d-04]

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-25: per-location quantity (StripeBillingService.locationQuantity) and the after-commit BillingLocationSync, on main as #1067"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-26: scripts/staging-seed.sh --stage billing and --stage billing-sync-check (PR 7 part 2, #1087)"
provides:
  - "PR 7 part 2 (#1087) merged as squash 2fe0837b and deployed to staging with digest parity MATCH on core-java, edge-go and frontend"
  - "A TEST SAAS_HYBRID_STANDARD subscription on the staging tenant, sub_1UXBDhDpS9ICNeRthdSjxKoZ, quantity 1 = 1 location (all-shops); its invoice in_1UXBDhDpS9ICNeRtw8DKqYXs, read from Stripe by id, is paid with amount_paid 3900 and livemode false"
  - "The live per-location sync observed on staging: Stripe quantity 1, then 2, then 1 around a probe shop that was created and deleted"
  - "evidence/30-pay02-billing.json (ids, quantities, amounts, statuses, livemode only), leak-scanned with controls"
  - "The issue #102 PAY-02 evidence comment (issuecomment-6567846593), read back; #102 stays OPEN for the billing UI (UXT-033)"
affects: [30-28, 30-29, 30-30, 30-31, 30-32]

actuals:
  tokens: 8674      # chars/4 over the realized diff: this branch 24202 chars (2fe0837b..fec97a8c) + Task 1's commit 2f61ff06 10495 chars
  tasks: 3
  commits: 1        # MEASURED: git rev-list --count 2fe0837b..HEAD before this SUMMARY's own commit (Task 1's 2f61ff06 sits on the merged feat/30-billing-per-location and is inside squash 2fe0837b)
plan_head_before: 2fe0837b0565edf97738184e0ede02f9ea513278
plan_head_after: fec97a8c57c4f2b07c1014e3cf5a903fdc9ff512

tech-stack:
  added: []
  patterns:
    - "Evidence JSON fields that carry a verdict (livemode) are DERIVED from the reads, never written as literals; a non-false read yields MISMATCH or unparsed:<value>, never false"
    - "A gitleaks positive control must plant a string a gitleaks rule matches (a fabricated sk_test_ key); a bare real vault value with no key context is not one"

key-files:
  created:
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-pay02-billing.json"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-27-pay02-live.txt"
  modified:
    - "HANDOFF.md"
    - "docs/CHANGELOG.md (Task 1, on #1087)"

key-decisions:
  - "The staging tenant held no subscription, so the first --stage billing run SUBSCRIBED it. The subscription is left ACTIVE as the staging billing evidence; it is TEST mode and cancellable through the billing API."
  - "Round-1 findings on #1087 are recorded as observed or not observed, and none is patched here. Finding 8 (the after-commit sync is synchronous, so the 90 s poll models an asynchrony that does not exist) was OBSERVED: both polls hit at elapsed=0s. Finding 2's premise held (the n, n+1, n cycle made no new invoice). 30-28 edits scripts/staging-seed.sh next."
  - "The feat/30-pay03-staging-loop branch has no upstream, so a bare git push cannot target main. The next PR (PR 8, PAY-03) pushes it."

patterns-established:
  - "A per-location billing proof reads the subscription and the invoice BY ID, independently of the stage that printed the BILLING line, then replays the stage to show it creates nothing"

requirements-completed: [PAY-02]

coverage:
  - id: D1
    description: "PR 7 (#1087) merged as squash 2fe0837b; main run 41375140960 deployed it, with Deploy to Staging and Staging Edge Probe green and digest parity MATCH for all three services"
    requirement: PAY-02
    verification:
      - kind: other
        ref: "gh pr view 1087 --json state,mergeCommit -> MERGED 2fe0837b0565edf97738184e0ede02f9ea513278"
        status: pass
      - kind: other
        ref: "bash scripts/check-deploy-digest-parity.sh --context jtoye-stg-aks --tag 2fe0837b… -> rc 0, 3 MATCH (control --tag 2fcb0ea0… -> rc 1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The staging tenant's TEST subscription has quantity equal to its location count under all-shops, and its invoice, read from Stripe by id, is paid with amount_paid 3900 x quantity and livemode false"
    requirement: PAY-02
    verification:
      - kind: other
        ref: "30-27-PLAN.md Task 3 <verify> 1 (staging-seed.sh --stage billing, replay run) -> rc 0; fail arms (stage rc 1, quantity!=locations, amount 3901, livemode=true, status=open, no line) each rc 1"
        status: pass
      - kind: other
        ref: "staging-seed.sh --stage stripe-get --path /v1/subscriptions/sub_1UXBDhDpS9ICNeRthdSjxKoZ and /v1/invoices/in_1UXBDhDpS9ICNeRtw8DKqYXs -> rc 0, equal to the BILLING line"
        status: pass
    human_judgment: false
  - id: D3
    description: "The live sync is observed: adding a probe location raises the Stripe quantity by one, and deleting it restores it, each read from Stripe"
    requirement: PAY-02
    verification:
      - kind: other
        ref: "staging-seed.sh --stage billing-sync-check -> rc 0, BILLING-SYNC before=1 after-add=2 after-remove=1 livemode=false; probe DELETE 204; replay counted locations=1"
        status: pass
    human_judgment: false
  - id: D4
    description: "evidence/30-pay02-billing.json holds only ids, quantities, amounts, statuses and livemode, and is leak-scanned with controls"
    requirement: PAY-02
    verification:
      - kind: other
        ref: "30-27-PLAN.md Task 3 <verify> 2 -> jq_rc=0 fields_ok=true suspicious=0 rc 0; arms livemode/qty/prefix/email/key/unparseable -> rc 1, missing -> VOID rc 2; here-string x@y.z control -> 1"
        status: pass
      - kind: other
        ref: "staging-secrets.sh --leak-scan -> 0 found rc 0 (planted real vault value control -> 1 found rc 1); gitleaks detect --no-git -> rc 0 (fabricated sk_test_ control -> rc 1)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Issue #102 carries the PAY-02 staging evidence with no closing keyword, names what is still open, and the stored comment equals what was posted"
    requirement: PAY-02
    verification:
      - kind: other
        ref: "gh api repos/Bralabee/JToye_OaaS_2026/issues/comments/6567846593 -> diff against the posted file rc 0; attribution=0 closing=0 (controls 1/1); #102 state OPEN"
        status: pass
    human_judgment: false

duration: 13min (Task 3); the plan ran 2026-11-01T23:49Z to 2026-11-02T02:55Z, including the owner's review and merge
completed: 2026-11-02
status: complete
---

# Phase 30 Plan 27: PR 7 Merged, Per-Location Billing Evidenced on Staging Summary

**PR 7 (#1087) is merged as `2fe0837b` and is running on staging; all three images match by digest. The staging tenant is now billed per location in Stripe TEST mode: subscription `sub_1UXBDhDpS9ICNeRthdSjxKoZ` has quantity 1 for its 1 shop. Its invoice `in_1UXBDhDpS9ICNeRtw8DKqYXs`, read back from Stripe by id, is paid at 3900 with livemode false. A probe shop moved the Stripe quantity 1 → 2 → 1 live.**

## Performance

- **Duration:** about 13 min for Task 3 (02:42:21Z to 02:55Z). Task 1 was recorded at 2026-11-01T23:49:59Z, and the owner merged at 01:57:30Z.
- **Started:** 2026-11-01T23:49:59Z (Task 1 record)
- **Completed:** 2026-11-02T02:55Z
- **Tasks:** 3 of 3 (Task 1 tracer, Task 2 owner checkpoint, Task 3 auto)
- **Files modified:** 2 evidence files created and HANDOFF.md edited on this branch; Task 1 changed docs/CHANGELOG.md and HANDOFF.md on #1087

## Accomplishments

- **Merge and deploy.**
  - #1087 is MERGED as squash `2fe0837b0565edf97738184e0ede02f9ea513278`. Round 1 admitted 0 of 8 findings, all of them recorded on the PR.
  - In main run 41375140960, Deploy to Staging and Staging Edge Probe both succeeded.
  - The deploy log's digest parity step shows core-java `sha256:33e66ab2…`, edge-go `sha256:2ed2db6b…` and frontend `sha256:a959e8f8…` all MATCH.
  - I re-read parity from the operator host at 02:42:59Z: rc 0. The control against the previous main commit, `2fcb0ea0`, gave rc 1 with all 3 NOT IN THE EXPECTED SET. No rollback was needed.
- **First `--stage billing` run (rc 0).** The subscription status was NONE, so the stage subscribed: it made a tok_visa TEST payment method and called `POST /api/v1/billing/subscription`. It printed `BILLING sub=sub_1UXBDhDpS9ICNeRthdSjxKoZ quantity=1 locations=1 invoice=in_1UXBDhDpS9ICNeRtw8DKqYXs amount_paid=3900 status=paid livemode=false at=2026-11-02T02:43:40Z`.
- **Independent reads by id** with `--stage stripe-get`:
  - The subscription is active, has 1 item, quantity 1, unit_amount 3900, livemode false, and its latest_invoice is the same `in_` id.
  - The invoice is paid, with amount_paid = amount_due = 3900 gbp, billing_reason subscription_create, livemode false, and it names the same `sub_`.
  - Both are equal to the BILLING line.
- **Live sync (rc 0).** The quantity read 1 before, 2 after the probe shop `02e46895…` was created, and 1 after it was deleted (HTTP 204). Both polls hit their target at elapsed=0s. Listing the subscription's invoices afterwards shows exactly 1, so the cycle created no new invoice.
- **Replay** (plan `<verify>` 1, verbatim, rc 0): the same `sub_` and `in_` ids, read back with no payment method and no subscribe. It counted locations=1, which also shows the probe shop is gone.
- **Evidence and comment.**
  - `evidence/30-pay02-billing.json` was built with `jq -S`, with its livemode fields derived from the reads. It passes `<verify>` 2 and both leak scans; each check's control fails as it should.
  - The issue #102 comment is posted and its read-back is byte-identical. #102 stays OPEN for UXT-033 (no billing UI under `frontend/app`) and for the missing scheduled quantity reconcile.

## Task Commits

1. **Task 1: PR 7 is open, current with main, its body clean.** `2f61ff06` (docs), on `feat/30-billing-per-location`, now inside squash `2fe0837b`. PR #1087.
2. **Task 2: Owner runs the D3 review series and squash-merges PR 7.** No commit of this plan. The owner merged it as `2fe0837b`, after base merges `9765baef` and `07de3c4f`.
3. **Task 3: The staging tenant is billed per location, read from Stripe by id, and the sync is seen live.** `fec97a8c` (docs), on `feat/30-pay03-staging-loop`.

**Plan metadata:** this SUMMARY's commit, then the STATE/ROADMAP/REQUIREMENTS commit.

## Files Created/Modified

- `.planning/phases/30-live-stripe-test-mode-execution/evidence/30-pay02-billing.json`: the PAY-02 staging evidence, holding the subscription, quantity, locations, invoice, amount_paid, invoice status, livemode, unit amount, the by-id reads, the sync reads before, after the add and after the remove, the replay, the rule and UTC times.
- `.planning/phases/30-live-stripe-test-mode-execution/evidence/30-27-pay02-live.txt`: Task 1's record, then Task 2's merge record and Task 3's full run log, with every rc, both directions of each check, and the leak scans with their controls.
- `HANDOFF.md`: the title line and a new RESUME HERE bullet for 30-28 on this branch. The previous bullet is marked superseded. The RabbitMQ Watch line now carries the 4.4.0 release (2026-10-20) and the 2026-12-31 exemption expiry under #724.

## Falsification record (both directions)

| Check | Real tree | Fail direction |
|---|---|---|
| Digest parity | `--tag 2fe0837b…`: rc 0, 3 MATCH | `--tag 2fcb0ea0…`: rc 1, 3 NOT IN THE EXPECTED SET |
| `<verify>` 1 (BILLING parse) | rc 0 on the replay line | stage rc 1, quantity ≠ locations, amount 3901, livemode=true, status=open, no BILLING line: each rc 1 |
| `<verify>` 2 (evidence JSON) | jq_rc=0 fields_ok=true suspicious=0, rc 0 | livemode=true, quantity=2, a `cus_` subscription, an email, an `sk_test_` key, unparseable JSON: each rc 1; missing file: VOID rc 2 |
| Suspicious-string grep | 0 on the evidence | here-string `x@y.z`: 1 |
| staging-secrets `--leak-scan` | 32 checked, 0 found, rc 0 | the real `stripe-credentials--connect-webhook-secret` value appended to a mode-600 scratch copy: `LEAK …`, 1 found, rc 1; copy shredded |
| gitleaks 8.30.1 `--no-git` | rc 0, no leaks | a fabricated `sk_test_` key: rc 1, leaks found: 1 |
| `check-handoff-contract.sh` | rc 0 (38 state claims, 0 behind) | a planted "#724 is CLOSED": rc 1 `FAIL: H-2 … the forge says open`; restored, sha256 equals HEAD's blob, clean again rc 0 |
| #102 comment scan | attribution=0 closing=0 | the strings "Closes #102" and "Co-Authored-By: x": 1 and 1 |

## Observations against the round-1 findings on #1087

These are recorded only; nothing in `scripts/staging-seed.sh` was patched in this plan.

- **Finding 8, OBSERVED.** Both sync polls hit their target at elapsed=0s. The after-commit sync completes on the request thread before the 201/204 returns, so the 90 s deadline only costs time when the sync fails.
- **Finding 2, premise held.** After the n, n+1, n cycle the subscription has exactly 1 invoice, and the replay read the first one. The latent false-FAIL after a renewal or an `always_invoice` proration was not triggered.
- **Findings 1 and 3, not observed.** The start state was NONE, which is the subscribe path, and `before=1` equals `locations=1`.
- **Findings 4 to 7, not exercised.** No create failed, no deadline ran out, the rule is all-shops, and the vendor stage was not run.

## Decisions Made

- I recorded which `--stage billing` path ran, as the task asked. The tenant had no subscription, so the first run SUBSCRIBED. The second run read it back.
- The subscription is left ACTIVE as the staging billing evidence, as the plan's reversibility note says.
- The new branch has no upstream. It was created with `--track` defaulting to origin/main, and I unset that so a bare push cannot target main.
- **PAY-02's checkbox in REQUIREMENTS.md stays unticked.**
  - `requirements.ready-ids` returned PAY-02 as ready, since 30-25, 30-26 and 30-27 all have SUMMARYs.
  - But `requirements.mark-complete` ticked only the checkbox (traceability `applied: false`). The traceability row records PAY-02 as "unticked pending owner acceptance", and the requirement's own line says it "Closes #102's remainder", which UXT-033 still leaves open.
  - So I reverted the tick and added the 30-27 staging evidence to the traceability row. Ticking the checkbox is the owner's call, at phase verification. `requirements-completed` above copies the plan's `requirements` field verbatim, as the template requires.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The first evidence build wrote `sync.livemode: true`**
- **Found during:** Task 3, while building the evidence JSON
- **Issue:** I wrote `($slm=="false"|not|not)`, which evaluates "is the string false" and so gives `true`. The top-level `livemode` was also a literal `false`, not derived from anything.
- **Fix:** both fields are now derived. `sync.livemode` comes from the BILLING-SYNC line. The top-level `livemode` is `false` only if the BILLING line, the subscription read and the invoice read all say false; otherwise it is `MISMATCH`. An unparsed read yields `unparsed:<value>`.
- **Files modified:** evidence/30-pay02-billing.json (before its first commit)
- **Verification:** `<verify>` 2 rc 0, and its livemode=true arm rc 1.
- **Committed in:** fec97a8c

**2. [Rule 3 - Blocking] The gitleaks positive control needed a different plant**
- **Found during:** Task 3, leak scans
- **Issue:** a real `whsec_` value appended with no key context gives gitleaks rc 0, so it cannot serve as gitleaks's "control rc 1".
- **Fix:** I planted a fabricated `sk_test_` key with 32 random alphanumerics. gitleaks gave rc 1. The real-value plant is still the staging-secrets control.
- **Committed in:** recorded in fec97a8c (30-27-pay02-live.txt)

---

**Total deviations:** 2 auto-fixed (1 Rule 1, 1 Rule 3). **Impact:** both make the evidence checks able to fail. No scope change.

## Issues Encountered

- **The base-python hook blocked a Python edit.** The first HANDOFF edit called `/usr/bin/python3` and was refused. The whole Bash call was blocked, including the backup copy. I re-ran it under `conda activate jtoye-ops` with the backup re-made first.
- **`staging-secrets.sh --leak-scan` without the subscription pin** refuses with `REFUSED [subscription]`. This is correct; I re-ran it with `JTOYE_AZURE_SUBSCRIPTION_ID` set.

## Known Stubs

None. This plan changes evidence and docs only.

## Threat Flags

None. The run stayed inside T-30-104 to T-30-106: the head was the PR head, merged by the owner; the evidence holds ids, amounts and statuses only and is leak-scanned; every object reads livemode false. No package was installed (T-30-SC).

## User Setup Required

None.

## Next Phase Readiness

- Ready for 30-28 (PAY-03 PR 8: connect-onboard, settlement-mode, destination money loop) on `feat/30-pay03-staging-loop`.
- Still open on issue #102: the billing UI (UXT-033) and a scheduled quantity reconcile.
- **RabbitMQ deadline:** the horizon exemption for both rabbitmq rows expires on 2026-12-31 under #724. RabbitMQ 4.4.0 has been published since 2026-10-20.

## Self-Check: PASSED

- FOUND: evidence/30-pay02-billing.json and evidence/30-27-pay02-live.txt (`[ -f ]`), HANDOFF.md edited.
- FOUND: fec97a8c on HEAD (`git merge-base --is-ancestor`). 2f61ff06 exists as an object (`git cat-file -t`: commit), and the squash that contains it, 2fe0837b, is on HEAD.
- `git rev-list --count origin/main..HEAD` = 1, rc 0. Current branch: feat/30-pay03-staging-loop.
- `gsd_run check evaluation-scope --plan 30-27 --commits-only`: status resolved, 1 commit on this branch.
- Both Task 3 `<verify>` blocks ran verbatim with rc 0, and each has recorded fail arms.
- Gates at fec97a8c: check-handoff-contract rc 0, docs-freshness rc 0, check-doc-metrics rc 0, check-branch-behind-base rc 0.

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-11-02*
