---
phase: 30-live-stripe-test-mode-execution
plan: 22
subsystem: e2e-stripe-refund-nightly
tags: [pay-01, stripe, test-mode, playwright, e2e-nightly, stripe-cli, supply-chain, secret-in-artifact, antigravity-adoption, pr-1065]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-21: S-5, --lint-conf and the repeatable --report union gate (PR #1065)"
provides:
  - "PR #1065 head 1f69a7f0: every 30-22 must-have is MET or, for the live refund run, UNCHECKABLE here by design (30-23 Task 2)"
  - "Gap 1 closed (T-30-87): Stripe calls through Node fetch (frontend/e2e/stripe-test-api.ts), top-level test.use trace/video off, the Stripe run's Playwright output under $RUNNER_TEMP, and an always() check-stripe-ci-keys.sh --scan-artifacts step before the upload"
  - "Gap 2 closed: pollRefundReadback settles only on EXACTLY one succeeded 100-pence refund with a charge id, on two consecutive reads within 30 s; the charge livemode is always read; the page must show Refunds (1), £1.00 and the Refunded badge"
  - "check-stripe-ci-keys.sh --scan-artifacts and --lint, selftest 6 -> 18 arms; frontend/__tests__/stripe-refund-readback.test.ts (11 cases, every PR)"
  - "evidence/30-22-fix-1065.txt (this fix) beside evidence/30-21-22-adopt-1065.txt (the adoption)"
affects: [30-23, 30-24]

estimate:
  tokens: 80000
  tasks: 2
actuals:
  tokens: 12889     # chars/4 over this session's code diff on #1065: 217003b5..29d419c4 (40537) + 7537f8df..1f69a7f0 (8320) + the CHANGELOG resolution (2700). The original 30-22 code (Antigravity's, 14292 by the same measure) is in the halted record
  tasks: 2          # both plan tasks now meet their must-haves; the code is on PR #1065
  commits: 1        # MEASURED: git rev-list --count 7087d4b3..HEAD on feat/30-pay01-refund-e2e at SUMMARY write (55dfc17f, evidence + WINDOWS), before this SUMMARY's own commit. Code commits live on PR #1065: b7a827ac, 29d419c4, 7537f8df (merge), 1f69a7f0 (first-parent count 217003b5..1f69a7f0 = 4)
plan_head_before: 7087d4b3643320d301e073c90157d775025f27e9
plan_head_after: 55dfc17f3464dc6fbe72a6f0acc698985b5e8677

tech-stack:
  added: ["stripe-cli 1.55.6 (CI only, pinned by sha256; on PR #1065)"]
  patterns:
    - "A secret masked in the Actions log is not masked in an uploaded artifact. Playwright's request fixture leaks request headers through the trace AND, on a transport error, through the call log in the error message (JSON report, error-context.md). trace off closes only the first; keep secrets out of Playwright entirely (Node fetch)"
    - "Scan at the execution point: delete any artifact file holding a secret (zip members expanded) immediately before upload, fail the job, VOID on nothing-to-scan"
    - "A refund read-back settles on exactly one, on two consecutive reads; more than one is a violation at once, never something to wait out"
    - "A structural lint over a workflow must match non-comment lines only: the first lint-real run matched the header comment naming the step"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-22-fix-1065.txt
    - frontend/e2e/stripe-test-api.ts (on PR #1065)
    - frontend/__tests__/stripe-refund-readback.test.ts (on PR #1065)
  modified:
    - .planning/phases/30-live-stripe-test-mode-execution/30-22-SUMMARY.md
    - .planning/WINDOWS.md (53, 54 fixed)
    - frontend/e2e/stripe-refund-flow.spec.ts, scripts/check-stripe-ci-keys.sh, .github/workflows/e2e-nightly.yml (on PR #1065)
    - docs/CHANGELOG.md, docs/metrics.json, README.md, CLAUDE.md, AGENTS.md (on PR #1065, after the base merge)

key-decisions:
  - "Owner ruling 2026-11-01 ('you fix it. you tackle the gaps.'): the two gaps were fixed on #1065 from a detached scratch checkout and pushed as a fast-forward; Antigravity's worktree was not touched"
  - "Node fetch is the necessary layer for T-30-87; trace off, the $RUNNER_TEMP output and the pre-upload scan are kept as independent layers, each measured on its own"
  - "The Stripe run's Playwright output is excluded from the upload rather than redacted: it also stops the Stripe run emptying frontend/test-results/ (the main run's failure traces) before the upload"
  - "The CHANGELOG conflict was resolved by keeping all three entries (#1065 above #1067 and #1068); AGENTS.md/CLAUDE.md differed only in counts and were regenerated from docs/metrics.json"

requirements-completed: []

coverage:
  - id: D1
    description: "The refund spec pays for its own TEST order and reads exactly one succeeded 100-pence refund back from Stripe, with charge livemode false; the page shows Refunds (1) and Refunded"
    requirement: PAY-01
    verification:
      - kind: unit
        ref: "frontend/__tests__/stripe-refund-readback.test.ts: RED b7a827ac 8/11 failed (duplicate: Expected violated, Received settled); GREEN 29d419c4 11/11"
        status: pass
      - kind: other
        ref: "spec read on 1f69a7f0: 30 s poll, two-read settle, charge always read, exact Refunds (1) / £1.00 / Refunded locators, CONFIRMED read; e2e typecheck 58 clean; ESLint rc 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "The Stripe spec is collected only when STRIPE_E2E is exactly 1 and only on desktop; the DRAFT test stays byte-identical; the counts gates agree"
    requirement: PAY-01
    verification:
      - kind: other
        ref: "plan T1 verify 1 on 1f69a7f0: default 0, keyed desktop 1, mobile 0, DRAFT 2; docs-freshness + check-doc-metrics rc 0 (fail direction rc 1, 12 stale claims, before the prose edit); test-count oracle playwright 196/196 50/50, jest 3272/3272 276/276"
        status: pass
    human_judgment: false
  - id: D3
    description: "check-stripe-ci-keys.sh refuses bad keys, never prints a value, and its scan deletes any artifact holding a Stripe secret"
    requirement: PAY-01
    verification:
      - kind: unit
        ref: "--selftest 18/18 rc 0; arm A (zip members unread) 17/18 rc 1 scan-zip; arm B (no delete) rc 1 scan-plain-deleted; restores sha256-equal"
        status: pass
    human_judgment: false
  - id: D4
    description: "The nightly's Stripe phase has the eight structural properties plus the scan before upload, the pinned CLI equals the published checksum, and the secret never reaches an uploaded artifact"
    requirement: PAY-01
    verification:
      - kind: other
        ref: "yaml verify 9/9 (8 + scan-before-upload); leak probe RED 4 files / GREEN 0 with the real module and test.use; layer arms fetch-only 0, trace-off-only 3; positive control rc 0; scan over the real RED artifacts deletes the same 4 (rc 1), GREEN rc 0; CLI pin from the adoption record"
        status: pass
    human_judgment: false
  - id: D5
    description: "The refund E2E passes for real against Stripe TEST mode in the nightly, and its events land in processed_stripe_events"
    requirement: PAY-01
    verification: []
    human_judgment: true
    rationale: "Needs the owner's CI sandbox keys and a dispatched nightly: plan 30-23 Task 2, by design. Uncheckable here"

duration: 22min
completed: 2026-11-01
---

# Phase 30 Plan 22: Stripe test-mode refund E2E (complete on PR #1065 head 1f69a7f0)

**The refund E2E reads back exactly one refund for its PaymentIntent: polled for up to 30 s, settled on two consecutive reads, and checked against the page's `Refunds (1)` and `Refunded`. The CI sandbox's `sk_test_` can no longer reach the public nightly artifact. Stripe calls run on Node `fetch` and never enter Playwright; traces are off; the Stripe run's output is not uploaded; and a pre-upload scan deletes any file still holding a secret.**

The original 30-22 code is Antigravity's (`e6316b47`, `39b4f136`, `8d9dde4a`, `217003b5`). The adoption measured it in `evidence/30-21-22-adopt-1065.txt` and found two gaps, recorded in the halted version of this SUMMARY. Under the owner's ruling (2026-11-01), this session fixed both on #1065 (`b7a827ac` RED, `29d419c4` fix), merged `origin/main` (`7537f8df`), regenerated the counts (`1f69a7f0`) and pushed. Evidence: `evidence/30-22-fix-1065.txt`.

## Performance

- **Duration:** about 22 min (00:45Z to 01:07Z)
- **Started:** 2026-11-01T00:45Z
- **Completed:** 2026-11-01T01:07Z
- **Tasks:** 2. Both now meet their must-haves; the live run is 30-23's
- **Files modified on #1065 in this session:** 9 (3 new or changed for the fix, 1 test, 5 docs and counts)

## Must-haves: re-measured on 1f69a7f0

| # | Must-have (short) | Evidence | Verdict |
|---|---|---|---|
| 1 | Own paid TEST order, £1.00 UI refund, **exactly one** refund read back from Stripe (100, succeeded), charge livemode false, never skips, fails with a reason | The order, capture, confirm and UI flow are unchanged from the adoption (MET there). The read-back is now `pollRefundReadback` (exactly one, two-read settle, 30 s, a second refund fails at once). The charge is always read: `GET /v1/charges/<id>` must be 200, belong to this intent and have `livemode` false. The PaymentIntent fallback is gone. The page must show `Refunds (1)`, `£1.00` and `Refunded` (strict, exact). The confirm must return `CONFIRMED`. Jest RED 8/11, GREEN 11/11. `rg 'test\.skip'` gives rc 1 | **MET** (structure). The live pass is 30-23's |
| 2 | Collected only under `STRIPE_E2E=1`, desktop only, DRAFT test unchanged, no grepInvert override | T1 verify 1 on `1f69a7f0`: default 0, keyed desktop 1, mobile 0, DRAFT 2; 432 tests in 45 files | MET |
| 3 | The main run is keyless, and the Stripe phase has the guard, the pinned CLI, the forwarder PID, the core-java-only recreate, `acceptsCardPayments` true, its own report and the PID kill | yaml verify: the 8 properties are True. This session added `--output "$RUNNER_TEMP/stripe-test-results"` to the Stripe run | MET |
| 4 | Events counted in `processed_stripe_events`, and the union skip gate runs over both reports | Unchanged from the adoption. `report-stripe.json` stays in `e2e-artifacts/` for the union gate and for 30-23's artifact read | MET (structure). Live: 30-23 |
| 5 | A CI sandbox separate from staging | No new secret or variable. The scan step reads only the job's key and the `.env` whsec | MET |
| 6 | Idempotency edge | Unchanged. The read-back is still keyed by the spec's own intent | MET |
| 7 | Concurrency edge | Unchanged | MET |
| — | T-30-87: the secret is not in logs, argv **or an uploaded artifact** | RED probe: 4 files held the fake key (2 `trace.zip`, `error-context.md`, and the JSON report via the call log). GREEN: 0 (rc 1). Layer arms: `fetch` alone gives 0; trace off alone gives 3. Positive control rc 0. The scan over the real RED artifacts deletes the same 4 files (rc 1). Selftest 18/18, with 2 break arms. yaml check 9/9 | **MET** |
| — | T-30-SC, A12 | Unchanged from the adoption (pin equal to `stripe-linux-checksums.txt`; a corrupted copy is refused; the env key is read) | MET |

## Accomplishments

- **Gap 1 (T-30-87) is closed in depth.** The probe found a second leak path the adoption had not recorded. On a transport error, `APIRequestContext`'s call log prints `Authorization: Bearer <key>` into the error message, so the key lands in `report-stripe.json` (uploaded) and in `error-context.md`. `trace: "off"` alone leaves it there, as measured. So the secret now never enters Playwright: `stripe-test-api.ts` calls Stripe with Node `fetch` and redacts the key in its own errors. Three more layers sit on top: `test.use(STRIPE_SPEC_ARTIFACTS)` turns traces and video off; the Stripe run's output goes to `$RUNNER_TEMP`; and `--scan-artifacts` runs as an `always()` step before the upload. `--lint` holds all four in place.
- **Gap 2 is closed.** The read-back rule is a pure, PR-tested function. A duplicate refund, a `has_more` page, a missing charge id, or a duplicate arriving on the confirming read each fail. The page assertions use exact, strict locators taken from `OrderDetailPanel` (`Refunds ({n})`, the `Refunded` badge).
- **Found and fixed on the way:** at `217003b5` the Stripe run used Playwright's default output dir. Playwright empties that dir at the start of a run, so the Stripe phase deleted the main run's failure traces before the upload.
- **Merged up to `origin/main` `bf98cde5`** (#1068, #1067): 0 behind before the push. The changelog contract is green again (it was red on `217003b5` for #1068).

## Task Commits

On PR #1065 (`agy/30-21-22-refund-e2e`), pushed `217003b5..1f69a7f0`:

1. `b7a827ac` test(30-22): RED. The read-back must be exactly one refund, polled (8/11 fail)
2. `29d419c4` fix(30-22): the secret stays out of every uploaded artifact, and the read-back is exactly one
3. `7537f8df` Merge origin/main (#1068, #1067)
4. `1f69a7f0` docs(30-22): counts after the merge (6778; Jest 3272/276; 50 specs)

Record (this branch, not pushed): `55dfc17f` (evidence and WINDOWS 53/54), and this SUMMARY's commit.

## Files Created/Modified (PR #1065, this session)

- `frontend/e2e/stripe-test-api.ts`: the Node `fetch` Stripe client, `STRIPE_SPEC_ARTIFACTS`, `requireStripeTestSecretKey`, `judgeRefundReadback` and `pollRefundReadback`
- `frontend/e2e/stripe-refund-flow.spec.ts`: Stripe calls through the module, top-level `test.use`, the polled exact-one read-back, the charge always read, the page and confirm assertions
- `frontend/__tests__/stripe-refund-readback.test.ts`: 11 cases, run on every PR (the spec itself runs only in the keyed nightly)
- `scripts/check-stripe-ci-keys.sh`: `--scan-artifacts` and `--lint`; selftest from 6 to 18 arms
- `.github/workflows/e2e-nightly.yml`: `--output "$RUNNER_TEMP/stripe-test-results"`, the scan step before the upload, and a FAIL-CLOSED line
- `docs/CHANGELOG.md` (the #1065 entry updated, both sides kept), `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`

## Decisions Made

- `fetch` is the necessary layer and the others are defence in depth. Each layer was measured alone, so the reason to keep it is on record.
- Exclusion over redaction for the Stripe run's `test-results`. The run's failure reason still reaches the uploaded `report-stripe.json`, which the scan has cleared.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Security] A second leak path for T-30-87 (the call log)**
- **Found during:** the gap-1 RED probe
- **Issue:** the plan's mitigation and the adoption's fix options assumed a trace-only leak. The JSON report and `error-context.md` carry the header too.
- **Fix:** the Node `fetch` client, plus the pre-upload scan as the execution-point authority
- **Files modified:** `frontend/e2e/stripe-test-api.ts`, `scripts/check-stripe-ci-keys.sh`, `.github/workflows/e2e-nightly.yml`
- **Commit:** `29d419c4`

**2. [Rule 1 - Bug] The Stripe run emptied `frontend/test-results/` before the upload**
- **Found during:** the nightly read
- **Fix:** `--output "$RUNNER_TEMP/stripe-test-results"`
- **Commit:** `29d419c4`

**3. [Rule 1 - Bug] The first `--lint` matched a comment**
- **Found during:** selftest `lint-real` (rc 1)
- **Fix:** every nightly match is anchored to non-comment lines
- **Commit:** `29d419c4` (fixed before the commit; the RED run is recorded in the evidence file)

**Not changed, recorded:** the PR body still describes the 6-arm selftest and the `>= 1` read-back. It is not proof; this SUMMARY and the evidence file are. The deviations 2, 5, 6, 8 and 9 from the halted record still stand (CAPTURED polled on the vendor API; `/public/...` path; the verdict counts rather than names the title; the oracle and HANDOFF not in the file list; the pk fallback name).

**Total deviations:** 3 auto-fixed (1 security, 2 bugs). None changes the plan's design.

## Issues Encountered

- main moved during the work (#1067 after #1068). One merge brought in both.
- Record slip: the ledger edit briefly dropped the newline after the JSON fence (`windows status` failed to parse it). Repaired and re-read (open 21, fixed 33, total 54) before the commit.

## Known Stubs

None.

## Threat Flags

None new. The scan step reads two secrets that the job already holds and prints only paths.

## User Setup Required

30-23 Task 1 (the owner): the CI keys can now be stored, because the trace fix is on the branch. Create the CI Stripe sandbox, run `gh secret set STRIPE_TEST_SECRET_KEY` and `gh variable set CI_STRIPE_PUBLISHABLE_KEY`, then say "ci keys set".

## Next Phase Readiness

1. Run D3 round 3 on #1065: `/review-verify 1065`, over the fix commits only. This is the orchestrator's step; round 3+ is the owner's decision under §14.4.
2. Then 30-23: the CI keys, branch nightly 1 (S-3 red expected), the ALLOW retired from the measured count, nightly 2 green, and #1065 marked ready. Dispatch on `agy/30-21-22-refund-e2e`.
3. Then 30-24.

Antigravity's `-agy-p30-w21` worktree is behind the pushed branch. Run `git pull --ff-only` there.

## Self-Check: PASSED

- FOUND: `evidence/30-22-fix-1065.txt`, and `55dfc17f` on HEAD
- FOUND on `origin/agy/30-21-22-refund-e2e`: `b7a827ac`, `29d419c4`, `7537f8df`, `1f69a7f0`; PR #1065 `headRefOid` = `1f69a7f09df5ec53138b8675aee731e07bfb202f`
- WINDOWS 53 and 54 read `fixed`; the ledger parses

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-11-01*
