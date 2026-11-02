---
phase: 30-live-stripe-test-mode-execution
plan: 28
subsystem: payments
tags: [stripe, connect, destination-charge, refund, transfer-reversal, staging-seed, bash, selftest, pay-03]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-12 Express onboarding (StripeConnectService.createOrResumeExpressOnboarding, capabilities requested) and the PAY-03 settlement routing (resolveSettlementRouting; RefundService sets reverse_transfer on a destination charge)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-06 / 29-18 staging-seed.sh mechanism: azure_guard_once, the sk_test_ refusal, the key in a mode-600 curl config, assert_test_mode, the per-run realm-admin operator deleted on every exit path, the stubbed selftest harness"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-26 / 30-27 billing stages (unit-separator jq projections, every verdict printed only after its checks)"
provides:
  - "scripts/staging-seed.sh --stage connect-onboard --link-out FILE: a per-run realm-admin operator reads the tenant; an ENABLED account is printed and nothing else is called; otherwise POST /api/v1/admin/tenants/{id}/stripe/connect, the operator is deleted, and the single-use onboarding URL goes only to FILE (mode 600). Ends `CONNECT-ONBOARD tenant=… account=acct_… status=…`"
  - "scripts/staging-seed.sh --stage settlement-mode [--set DESTINATION_CHARGE_PLATFORM_MOR|DIRECT_CHARGE_VENDOR_MOR]: GET the shop, PUT the same body with settlementPolicyMode alone changed, read back. Ends `SETTLEMENT-MODE shop=staging-test-kitchen mode=…[ previous=…]`"
  - "scripts/staging-seed.sh --stage money-loop --mode destination [--refund-pennies N] [--evidence FILE]: refusals [connect-status], [mode-mismatch], [plain-charge] and [stripe-mode]; lines LOOP-WAIT, LOOP-ORDER, LOOP-CAPTURE, LOOP-TRANSFER, LOOP-ACCEPT, LOOP-REFUND-READ, LOOP-REFUND-LOCAL, LOOP-REFUND, LOOP-EVIDENCE"
  - "operator_up / operator_down / read_kv / operator_login extracted from vendor_stage for reuse (30-29 and the payout stages can use them)"
  - "15 selftest arms: onboard-new, onboard-enabled, onboard-no-out, mode-read, mode-set, mode-set-invalid, loop-not-enabled, loop-mode-mismatch, loop-plain-charge, loop-intent-fields, loop-live, loop-destination-ok, loop-refund-full, loop-refund-mismatch, loop-evidence-clean. dry-run-tools now covers 13 stages"
affects: [30-29, 30-30, 30-31, 30-32]

actuals:
  tokens: 30990     # chars/4: 121113 committed diff chars (19cc2197..2624be15) + 2849 chars of evidence committed with this SUMMARY
  tasks: 2
  commits: 4        # MEASURED: git rev-list --count 19cc2197..HEAD before this SUMMARY's own commit
plan_head_before: 19cc2197c393c7df9dea4793ef726955811ef1ab
plan_head_after: 2624be15000adf8c211fd33f2d5711040907b4cc

tech-stack:
  added: []
  patterns:
    - "A stage whose admin read comes before its writes deletes the realm-admin operator (operator_down) right after that read. No admin exists while an order, a refund or an onboarding link exists"
    - "Every bound that can be checked from an anonymous read is checked before any secret is read or any write is made. The refund must be strictly below the menu total before the Key Vault read"
    - "A Stripe object without a livemode field (Refund) is never passed to assert_test_mode. TEST mode is read through the object that has one (its charge)"
    - "Each by-id stub route answers its own fixture when an arm wrote one, else the generic stripe-get fixture. Shared harness routes cannot capture another stage's arms"

key-files:
  created:
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-red-task1.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-red-task1.json"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-red-task2.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-red-task2.json"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-task1-arms.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-task2-arms.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-28-verify.txt"
  modified:
    - "scripts/staging-seed.sh"

key-decisions:
  - "--link-out is required at parse time for connect-onboard, so a missing one is a usage error before any guard or call. The behaviour list asked for this only for a PENDING tenant (onboard-no-out). Requiring it always is stricter: 0 calls of any kind, not only 0 writes. An ENABLED tenant leaves the file uncreated"
  - "The money loop reads the public menu FIRST, then runs the preflight. The --refund-pennies bound (0 < N < total) is then a usage error with 0 Key Vault reads and 0 writes, not even the operator's Keycloak user"
  - "This run's Stripe refund is found by metadata.refund_id == the local refund row id (RefundService stamps it), not by position in the intent's refund list. The local row's stripeRefundId, when present, must agree"
  - "LOOP-ORDER is printed only after the intent read proves a destination charge (transfer_data.destination = the account, fee > 0, settlement_mode). A plain charge produces no LOOP line at all"
  - "--mode accepts only destination in this plan. 30-29 adds direct"

patterns-established:
  - "Money-loop verdicts: LOOP-ORDER mode=… order=… intent=pi_… amount=… application_fee=…, LOOP-CAPTURE charge=ch_… captured=true amount=…, LOOP-TRANSFER transfer=tr_… destination=acct_… amount=…, LOOP-ACCEPT order=… status=CONFIRMED, LOOP-REFUND refund=re_… amount=… status=succeeded transfer_reversal=trr_… local_status=succeeded"
  - "Evidence entry .modes.<mode> = {run_id, order, intent, charge, transfer, refund, transfer_reversal, account, amounts{captured, application_fee, transfer, refund, transfer_reversal}, statuses{…}, livemode: false, at}, written jq -S, mode 600, earlier keys kept"

requirements-completed: [PAY-03]   # contributes; requirements.ready-ids returns PAY-03 blocked (30-29..30-32 declare it), so the box stays unchecked

coverage:
  - id: D1
    description: "--stage connect-onboard starts or resumes Express onboarding through the admin API. The URL goes only to the mode-600 --link-out file, never to stdout, stderr or argv. An ENABLED account gets no POST. The operator is deleted after the call"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#onboard-new"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#onboard-enabled"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#onboard-no-out"
        status: pass
      - kind: other
        ref: "evidence/30-28-task1-arms.txt: onboarding URL echoed to stdout -> onboard-new red (71/1), restore sha256 matches HEAD"
        status: pass
    human_judgment: false
  - id: D2
    description: "--stage settlement-mode reads, or sets and reads back, staging-test-kitchen's settlementPolicyMode with a PUT of the GET body that changes only that field. A dropped PUT fails. Only the two loop modes are accepted"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#mode-read"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#mode-set"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#mode-set-invalid"
        status: pass
    human_judgment: false
  - id: D3
    description: "The destination loop refuses before any write unless the tenant is ENABLED and the shop routes as a destination charge. It refuses a plain platform charge (Pitfall 6) and any livemode-true object. It names the wrong destination, fee or settlement_mode field. Order, capture and transfer are each read by id"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-not-enabled"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-mode-mismatch"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-plain-charge"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-intent-fields"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-live"
        status: pass
      - kind: other
        ref: "evidence/30-28-task1-arms.txt: [plain-charge] made a no-op -> loop-plain-charge red; connect-status preflight skipped -> loop-not-enabled red (each 71/1, restored by sha256)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The loop accepts the order and issues a strictly partial refund through the vendor API with an Idempotency-Key. It reads the refund by id (succeeded, transfer_reversal), its charge for livemode, the transfer's reversal, and polls the local row until it equals Stripe's status, else fails naming both"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-destination-ok"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-refund-full"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-refund-mismatch"
        status: pass
      - kind: other
        ref: "evidence/30-28-task2-arms.txt: transfer_reversal check made always-true -> loop-destination-ok red; -ge made -gt -> loop-refund-full red; local poll accepts any status -> loop-refund-mismatch red (each 74/1, restored by sha256)"
        status: pass
    human_judgment: false
  - id: D5
    description: "--evidence adds .modes.destination with ids, amounts and statuses only: no key, client secret, URL or e-mail. Written jq -S, mode 600, earlier content kept. A non-object file is refused untouched. gitleaks is clean over the branch range, and its control fires"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#loop-evidence-clean"
        status: pass
      - kind: other
        ref: "evidence/30-28-verify.txt: gitleaks git origin/main..HEAD rc 0 (7 commits); control (a Stripe-shaped live key committed in a throwaway repo) rc 1"
        status: pass
    human_judgment: false
  - id: D6
    description: "The destination loop produces PAY-03 evidence against the real staging tenant, a real connected account and Stripe TEST"
    requirement: PAY-03
    verification: []
    human_judgment: true
    rationale: "This plan proves the tool against stubs only. Onboarding the connected account (a human completes the Stripe-hosted form), the live loop and its evidence are plans 30-30 to 30-32."

duration: 33min
completed: 2026-11-02
---

# Phase 30 Plan 28: The destination-charge money loop, onboarding entry and mode switch Summary

**`scripts/staging-seed.sh` now has `--stage connect-onboard` (the onboarding URL goes only to a mode-600 file), `--stage settlement-mode` (GET, then a one-field PUT, then a read-back) and `--stage money-loop --mode destination`. The loop refuses unless the account is ENABLED and the shop routes as a destination charge. It reads the intent, charge and transfer by id, issues a strictly partial refund through the vendor API, and reads back the refund's transfer_reversal and the local row. The selftest is 75/75, and all six break arms went red on their target.**

## Performance

- **Duration:** about 33 min
- **Started:** 2026-11-02T03:03:42Z
- **Completed:** 2026-11-02T03:36:06Z
- **Tasks:** 2 (Task 1 tracer, Task 2 auto; both TDD)
- **Files modified:** 1 source (`scripts/staging-seed.sh`, +1249/-45 lines including the selftest), 7 evidence files

## Accomplishments

- **`--stage connect-onboard --link-out FILE`.** The vendor stage's operator lifecycle is extracted into `operator_up` and `operator_down`. The stage reads the tenant from `GET /api/v1/admin/tenants`.
  - An ENABLED account: it prints `CONNECT-ONBOARD … status=ENABLED` and calls nothing else. The link file is not created.
  - Otherwise: it sends `POST /api/v1/admin/tenants/{id}/stripe/connect` and deletes the operator. It writes the URL to FILE (created mode 600 before a byte lands in it) and prints `CONNECT-ONBOARD tenant=… account=acct_… status=PENDING`.
- **`--stage settlement-mode [--set MODE]`.** It finds the shop by slug and reads `GET /api/v1/shops/{id}`.
  - With `--set`, the GET body goes back through `PUT` with only `settlementPolicyMode` changed, then the shop is read again. A read-back that differs is a failure.
  - Without `--set` it is read-only.
  - The value must be DESTINATION_CHARGE_PLATFORM_MOR or DIRECT_CHARGE_VENDOR_MOR.
- **`--stage money-loop --mode destination`** runs these steps in order:
  - Reads the public menu, and checks `--refund-pennies` (default 100) is below the total.
  - Preflight: the operator reads the tenant and is deleted. `[connect-status]` unless ENABLED with an `acct_`. Then the shop read: `[mode-mismatch]` unless null or DESTINATION.
  - Places one storefront order (`loop-<UTC>-order`) and confirms it with `pm_card_bypassPending` (`loop-<UTC>-confirm`).
  - Reads the tracking until CAPTURED, printing every read.
  - Reads the intent by id. `[plain-charge]` without `transfer_data`. The run fails on the field name if `transfer_data.destination`, `application_fee_amount` or `metadata.settlement_mode` is wrong.
  - Reads the charge by id (captured, `amount_captured`, `transfer`) and the transfer by id (destination, amount = amount minus fee). This prints LOOP-ORDER, LOOP-CAPTURE and LOOP-TRANSFER.
  - The vendor accepts the order (LOOP-ACCEPT) and issues a refund with `loop-<UTC>-refund`.
  - Finds this run's refund by `metadata.refund_id` and reads it by id until it is succeeded. It must carry a `transfer_reversal`.
  - Reads the refund's charge for livemode and the transfer's reversal amount.
  - Polls the order detail until the local refund status equals Stripe's, then prints LOOP-REFUND.
  - With `--evidence`, adds `.modes.destination`.

## Task Commits

1. **Task 1 RED:** `47e0ed53` test(30-28): add failing selftest arms for connect-onboard, settlement-mode and the destination money loop
2. **Task 1 GREEN:** `1fecf6f2` feat(30-28): connect-onboard, settlement-mode and the destination money loop to the transfer
3. **Task 2 RED:** `0308f18a` test(30-28): add failing selftest arms for the loop's partial refund, reversal and evidence (also carries the Task 1 break-arm record)
4. **Task 2 GREEN:** `2624be15` feat(30-28): the destination loop's accept, partial refund, transfer reversal and evidence

No REFACTOR commit: nothing needed cleaning after GREEN.

## TDD record

**Task 1, RED (`47e0ed53`):**
- TAP output: 13 not ok, 59 ok, rc 1 (`evidence/30-28-red-task1.txt`).
- Classifier: `RED_EVIDENCE_OK`, reason `target_test_failed`, target `onboard-new`.
- Semantic assessment: the target ran and failed its first planned assertion (rc 2, want 0), because `--stage connect-onboard` was not a stage. The 12 other new or extended arms failed the same way (unknown stage, unknown `--set` / `--mode`). The 59 existing arms passed, so this was not a harness fault.

**Task 1, GREEN (`1fecf6f2`):**
- 72/72 PASS.
- The plan's verify command: rc 0, missing 0, FAIL lines 0.
- Tracer gate: no `blocking-human` gate, auto-advance off, `end-of-phase` mode with an automated-only verify. The verify was re-run as CLEAN-1 of the break-arm bracket (72/0): tracer verified end to end, then the expansion task started.

**Task 1 break arms** (`evidence/30-28-task1-arms.txt`). Each was committed first, bracketed CLEAN-1 72/0 and CLEAN-2 72/0, and restored from the HEAD blob, with sha256 `20384159…` matching HEAD each time:

| Arm | Change | Result |
|-----|--------|--------|
| accept an intent without transfer_data | `refuse plain-charge` made a no-op | loop-plain-charge red (71/1): "stderr lacks 'REFUSED [plain-charge]'" |
| skip the connect-status preflight | its condition made `false` | loop-not-enabled red (71/1): "rc=0 (want 1)" |
| print the onboarding URL | `echo "DEBUG onboarding url=$url"` | onboard-new red (71/1): "the onboarding URL … was printed (1 hit)" |

**Task 2, RED (`0308f18a`):**
- TAP output: 4 not ok, 71 ok, rc 1 (`evidence/30-28-red-task2.txt`).
- Classifier: `RED_EVIDENCE_OK`, target `loop-destination-ok`.
- Semantic assessment: the target ran the Task 1 loop to LOOP-TRANSFER, then failed the first refund-half assertion (no LOOP-ACCEPT verdict).
  - loop-refund-full and loop-evidence-clean failed on the unknown `--refund-pennies` / `--evidence` flags.
  - loop-refund-mismatch failed because the loop never polled a refund (rc 0).

**Task 2, GREEN (`2624be15`):**
- 75/75 PASS.
- Both tasks' verify commands: rc 0, missing 0, FAIL lines 0.

**Task 2 break arms** (`evidence/30-28-task2-arms.txt`). Bracketed CLEAN-1 75/0 and CLEAN-2 75/0. Each restore's sha256 `395dca13…` matched HEAD.

| Arm | Change | Result |
|-----|--------|--------|
| stop checking transfer_reversal (the plan's arm) | the `trr_` check made `true` | loop-destination-ok red (74/1): "no reversal: the failure does not name transfer_reversal" |
| let a full refund through | `-ge` made `-gt` | loop-refund-full red (74/1): "950: rc=1 (want 2)" |
| accept any local refund status | the poll breaks on the first read | loop-refund-mismatch red (74/1): "rc=0 (want 1)" |

On the first arm: with the check disabled, the run still ended rc 1, because the next step looks the reversal up on the transfer and finds none. The arm went red on its naming assertion. So a missing reversal is caught twice, and the arm proves that the named check is the one that reports it.

**gitleaks** (`evidence/30-28-verify.txt`):
- `gitleaks git --log-opts=origin/main..HEAD` over 7 commits, with the repo's config and `.gitleaksignore`: rc 0, no leaks.
- Control: a Stripe-shaped live key, composed at run time and committed in a throwaway repo: rc 1, 1 leak.
- `gitleaks dir` on the HEAD script: rc 0.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The new refund stub route captured an existing arm's read**
- **Found during:** Task 2 RED.
- **Issue:** `GET /v1/refunds/*` answered `re-<id>.json`, which does not exist for the stripe-get stage. So stripe-get-livemode-absent read `{}` and was refused [stripe-mode]. That is a harness regression, not a RED signal.
- **Fix:** each new by-id route (charges, transfers, the refund list, refunds) answers its own fixture when one exists, else the generic stripe-get fixture. RED was re-recorded with only the 4 target arms failing.
- **Files modified:** `scripts/staging-seed.sh` (selftest stub).
- **Commit:** `0308f18a`.

**2. [Rule 1 - Bug] The menu-first reorder ran before WORKDIR existed**
- **Found during:** Task 2 GREEN, first run: 9 arms red, with `/ml-pub-shop.json: Permission denied`.
- **Issue:** moving the menu read ahead of `vendor_login` meant nothing had called `ensure_workdir` yet. A live run would have tried to write to `/`.
- **Fix:** `loop_menu` calls `need_tool curl` and `ensure_workdir` itself.
- **Commit:** `2624be15`.

**3. [Rule 2 - Correctness] Checks added beyond the behaviour list**
- `loop-intent-fields`: a wrong destination, a zero fee and a wrong `metadata.settlement_mode` each fail on the field name.
- `mode-set-invalid`: SPLIT_DEPOSIT_VENDOR_MOR, and `--set` on another stage, are usage errors.
- `mode-set` includes a dropped-PUT sub-run: the stub ignores the PUT, so the read-back must fail.
- An order whose confirmation names a connected account (a direct charge) is refused [mode-mismatch] under `--mode destination`.
- The evidence entry is refused if it carries a key, secret, URL or e-mail shape, and a non-object `--evidence` file is refused before the loop starts.
- **Commits:** `47e0ed53`, `1fecf6f2`, `0308f18a`, `2624be15`.

**4. [Plan files_modified] `.gitleaksignore` not changed**
- Every key-shaped stub value (the client secret `pi_loop1` + `_secret_STUBCSlp1`, the onboarding URL) is composed at run time (the 29-18 rule). gitleaks found nothing over the range, and `git diff --quiet 19cc2197..HEAD -- .gitleaksignore` is rc 0. No allow-list entry was needed, and none was added.

**5. [Requirement tick withheld] REQUIREMENTS.md: PAY-03 left unchecked**
- `requirements.ready-ids` returns PAY-03 `blocked`, because 30-29 to 30-32 declare it. As in 30-12, 30-13 and 30-17, `requirements-completed: [PAY-03]` means "contributes to PAY-03".

**Total deviations:** 5. That is 2 bugs fixed (one harness, one in the stage), 1 set of added correctness checks, 1 file the plan listed but did not need, and 1 requirement tick withheld. **Impact:** no scope change.

## Notes for the live runs (30-30 to 30-32)

- **Refund routing follows the shop's CURRENT mode** (RefundService calls `resolveSettlementRouting` at refund time), as the plan says. So refund each mode's order BEFORE `--stage settlement-mode --set` switches it. This is recorded for 30-32's gap list.
- **The settlement-mode PUT echoes the whole ShopDto.** That includes read-only fields (`id`, `tenantId`, `createdAt`, `hoursSource`, `legacyOpeningHours`) and `published`.
  - Spring Boot turns `FAIL_ON_UNKNOWN_PROPERTIES` off (the project's own note in `ProductPatchRequest`), so the unknown fields are ignored.
  - `published` is sent back as its current value.
  - The read-back proves only the mode. The first live `--set` should be followed by a read of the storefront, to confirm nothing else moved.
- **The connect POST can answer DISABLED.** The stage still writes the link (it is how the vendor fixes the requirements) and prints `status=DISABLED`, rc 0. Any other status is a FAIL.
- **The refund amount is checked against the menu total.** That total is the dish price plus a delivery fee (0 for COLLECTION). The captured amount is then asserted equal to it, so the two bounds cannot drift.

## Known Stubs

None. The selftest stubs are the harness and do not ship.

## Threat Flags

None. The stages call existing endpoints only: admin tenants and connect, shop GET and PUT, the storefront order, order confirm, refund and detail. All of them are covered by T-30-107 to T-30-111 in the plan's threat model.

## Next Phase Readiness

Ready for 30-29: the direct-charge half of the loop and the payout stage, on this branch. The destination path is the reference. 30-29 adds `--mode direct` with `Stripe-Account` on every connected-account call.

## Self-Check: PASSED

- FOUND: `scripts/staging-seed.sh` and all 7 evidence files (`30-28-red-task1.{txt,json}`, `30-28-red-task2.{txt,json}`, `30-28-task1-arms.txt`, `30-28-task2-arms.txt`, `30-28-verify.txt`).
- FOUND (ancestors of HEAD): `47e0ed53`, `1fecf6f2`, `0308f18a`, `2624be15`.
- TDD gates: `test(30-28)` and `feat(30-28)` commits are both present, and each GREEN descends from its RED.
- Working `scripts/staging-seed.sh` sha256 equals the HEAD blob after both arm brackets.
