---
phase: 30-live-stripe-test-mode-execution
plan: 22
subsystem: e2e-stripe-refund-nightly
tags: [pay-01, stripe, test-mode, playwright, e2e-nightly, stripe-cli, supply-chain, antigravity-adoption, pr-1065, not-met]
status: halted

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-21: S-5, --lint-conf and the repeatable --report union gate (PR #1065)"
provides:
  - "NOT MET, halted: PR #1065 head 217003b5 meets every 30-22 must-have that can be checked without the CI keys except two: the spec accepts >= 1 refund where the must-have says exactly one, and a failing run's trace carries the sk_test_ into a public artifact (T-30-87)"
  - "Measured as valid on that head: the STRIPE_E2E collection switch, the DRAFT test unchanged, check-stripe-ci-keys.sh with its 6-arm selftest, all eight structural properties of the nightly Stripe phase, gate enforcement, the Stripe CLI 1.55.6 pin equal to the release's published checksum, and A12"
  - "evidence/30-21-22-adopt-1065.txt (shared with 30-21)"
affects: [30-23, 30-24]

estimate:
  tokens: 80000
  tasks: 2
actuals:
  tokens: 14292     # chars/4 over the 30-22 files in origin/main...217003b5 (57167 chars); the code is Antigravity's, on PR #1065
  tasks: 2          # implemented on PR #1065; re-measured here, two gaps found
  commits: 0        # MEASURED: git rev-list --count 3e2e24c2..HEAD before this SUMMARY's own commit. The shared evidence commit daa19b95 is counted under 30-21. Code commits e6316b47, 39b4f136 and 217003b5 live on PR #1065
plan_head_before: 3e2e24c234b712b7a3a601aac220663d5d9b22aa
plan_head_after: 3e2e24c234b712b7a3a601aac220663d5d9b22aa

tech-stack:
  added: ["stripe-cli 1.55.6 (CI only, pinned by sha256; on PR #1065)"]
  patterns:
    - "A secret masked in the Actions log is not masked in an uploaded artifact: Playwright traces (retain-on-failure) keep APIRequestContext request headers in plaintext"
    - "Settle 'does the CLI read the env var' under an empty HOME, and watch for credentials found outside HOME (here the desktop keyring)"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/30-22-SUMMARY.md
  modified:
    - frontend/e2e/stripe-refund-flow.spec.ts (new, on PR #1065)
    - frontend/e2e/vendor-refund-flow.spec.ts, frontend/playwright.config.ts (on PR #1065)
    - scripts/check-stripe-ci-keys.sh (new), scripts/check-test-count-oracle.sh (on PR #1065)
    - .github/workflows/e2e-nightly.yml, .github/workflows/ci-cd.yaml (on PR #1065)
    - docs/metrics.json, CLAUDE.md, AGENTS.md, README.md, docs/CHANGELOG.md, HANDOFF.md (on PR #1065)

key-decisions:
  - "30-22 is NOT adopted as complete. Two gaps, both on PR #1065, are reported rather than fixed: this session does not commit to Antigravity's branch"
  - "The T-30-87 gap blocks 30-23 Task 1/2: once the owner stores the CI sandbox key, the first failing branch nightly would publish it in the e2e-nightly-report artifact of a PUBLIC repository for 14 days"

requirements-completed: []

coverage:
  - id: D1
    description: "The refund spec pays for its own TEST order and reads exactly one succeeded 100-pence refund back from Stripe, with charge livemode false"
    requirement: PAY-01
    verification:
      - kind: other
        ref: "code read, frontend/e2e/stripe-refund-flow.spec.ts:304 toBeGreaterThanOrEqual(1); l.311-323 livemode fallback"
        status: fail
    human_judgment: false
  - id: D2
    description: "The Stripe spec is collected only when STRIPE_E2E is exactly 1 and only on desktop; the DRAFT test stays byte-identical; the counts gates agree"
    requirement: PAY-01
    verification:
      - kind: other
        ref: "plan T1 verify 1 rc 0 (default 0, keyed desktop 1, mobile 0, DRAFT 2); STRIPE_E2E=1/true/yes/0 controls; main vs head listing diff = exactly the 2 moved entries; docs-freshness + check-doc-metrics rc 0 with break arms"
        status: pass
    human_judgment: false
  - id: D3
    description: "check-stripe-ci-keys.sh refuses empty, live, restricted, swapped and malformed keys and never prints a value"
    requirement: PAY-01
    verification:
      - kind: unit
        ref: "bash scripts/check-stripe-ci-keys.sh --selftest 6/6 rc 0; arms: live accepted -> 5/6 rc 1, value echoed -> no-echo FAIL rc 1"
        status: pass
    human_judgment: false
  - id: D4
    description: "The nightly's Stripe phase has the eight structural properties, the pinned CLI equals the published checksum, and the secret never reaches a public artifact"
    requirement: PAY-01
    verification:
      - kind: other
        ref: "8/8 on head (main 1/8); sha256 = stripe-linux-checksums.txt, corrupted copy rc 1; trace probe: marker in 4 trace files"
        status: fail
    human_judgment: false
  - id: D5
    description: "The refund E2E passes for real against Stripe TEST mode in the nightly, and its events land in processed_stripe_events"
    requirement: PAY-01
    verification: []
    human_judgment: true
    rationale: "Needs the owner's CI sandbox keys and a dispatched nightly; that is plan 30-23 Task 2, and cannot run here"

duration: 25min
completed: 2026-11-01
---

# Phase 30 Plan 22: NOT MET on PR #1065 head 217003b5

**NOT MET: (1) must-have 1 says the spec reads exactly one refund for its PaymentIntent, but the spec asserts at least one (`stripe-refund-flow.spec.ts:304`, `toBeGreaterThanOrEqual(1)`, then `refunds[0]`), so a duplicate refund would pass. (2) T-30-87 is not fully mitigated: Playwright keeps traces on failure, the spec sends `Authorization: Bearer <sk_test_>` through APIRequestContext, the nightly uploads `frontend/test-results/`, and the repository is PUBLIC. Reproduced locally: a failing trace stores the header in plaintext (4 files). Both gaps are on Antigravity's PR and are reported, not fixed. 30-23 must not ask for the CI keys until gap 2 is fixed.**

Antigravity wrote this plan on `agy/30-21-22-refund-e2e`: `e6316b47` (spec), `39b4f136` (key guard and nightly) and `217003b5` (review fixes). It is DRAFT PR #1065. The rest of the head was re-measured here and is sound. The live refund cannot be measured without the owner's CI keys, and by design it is 30-23's first step. Evidence: `evidence/30-21-22-adopt-1065.txt`.

## Performance

- **Duration:** about 25 min of the adoption session (the 30-22 share)
- **Started:** 2026-11-01T00:20:05Z (npm ci in the verification checkout)
- **Completed:** 2026-11-01T00:35Z
- **Tasks:** 2 implemented on PR #1065. Re-measured: 2 gaps, everything else MET
- **Files modified:** 14 on PR #1065 (30-21 and 30-22 together)

## Must-haves: re-measured on 217003b5

| # | Must-have (short) | Command or read | rc / counts | Verdict |
|---|---|---|---|---|
| 1 | The spec makes its own paid TEST order, refunds £1.00 through the UI, reads **exactly one** refund (100, succeeded) and its charge's livemode false, never skips, and fails with a reason when the key or card mode is missing | code read. `rg -uu 'test\.skip'` on the spec gives rc 1; control on vendor-refund-flow gives rc 0. `check-e2e-typecheck.sh` rc 0 (arm: one type error, rc 1). The spec contains no SQL or seeding | Own order: met (storefront card order, fresh `Idempotency-Key`, `pm_card_visa` + `return_url`). CAPTURED is read by polling and `paymentReference` equals the intent. Vendor confirm returns 200, and the UI refund is £1.00. Never skips, and fails with a reason: met. **Exactly one: not met (`>= 1`).** Charge livemode: read on the normal path, but a fallback to the PaymentIntent's livemode can bypass it | **NOT MET** |
| 2 | The paid test leaves vendor-refund-flow (the DRAFT test is unchanged), and `stripe-*.spec.ts` is collected only when `STRIPE_E2E` is exactly `"1"`, with no grepInvert override | plan T1 verify 1, verbatim, plus controls | rc 0: default 0, keyed desktop 1, mobile 0, DRAFT 2. `STRIPE_E2E=1` gives 1 entry; `true`, `yes` and `0` give 0. Main lists 434 and the head 432; the difference is exactly the two moved entries. DRAFT block sha256 `52633a627f2a9a6a` on both | MET |
| 3 | The main run stays keyless. A Stripe phase follows it: the key guard and masking, the CLI pinned by version and sha256, `stripe listen` on both routes with its PID captured, only core-java recreated with the key, the whsec and `ci-nightly`, `acceptsCardPayments` true, its own JSON report, and the forwarder killed by PID in an `always()` step | plan T2 verify 2 (8/8) and a YAML step walk | 8/8 on the head; 1/8 on origin/main (fail direction). Step 1 masks both values, then runs the guard. The frontend bakes only the pk. core-java gets the four `STRIPE_*` values only before the recreate (compose maps all four, l.435-441). The whsec is masked before it is written. The verdict requires >= 1 passed, 0 failed/timedOut/interrupted, and 0 skipped. The kill is by PID in steps 24 and 30 | MET |
| 4 | The `payment_intent.succeeded` and refund events are rows in `processed_stripe_events` (psql in the postgres container), and the skip gate runs over the union of both reports | YAML read, `PaymentService.dispatch` read, 30-21 union probes | The events are found through `/v1/events` (curl `-K`, a mode-600 file, removed on EXIT) and counted with `psql -tAc`. Every non-foreign event gets its dedup row, `charge.refunded` included. The gate has `if: always() && stack-up success` and both `--report` flags. A missing report is VOID (probe rc 2) | MET (structure). The live run is 30-23's |
| 5 | The CI sandbox is separate from staging: only `secrets.STRIPE_TEST_SECRET_KEY` and `vars.CI_STRIPE_PUBLISHABLE_KEY` are read, and no staging key is referenced | `rg` over the nightly | Only those two, plus `GITHUB_TOKEN` for escalation. `rg keyvault\|key-vault\|stripe-credentials\|STRIPE_STAGING\|jtoye-stg` gives rc 1 | MET |
| 6 | Idempotency edge: each run creates its own order and reads only its own intent's refunds | code read | `crypto.randomUUID()` gives a fresh key. Refunds are read with `?payment_intent=<own intent>` and events are filtered by their own ids | MET |
| 7 | Concurrency edge: the concurrency group stops shared daemons; an interrupted Stripe phase leaves no `report-stripe.json`, which is VOID; the forwarder is still killed | YAML read and probe | The `e2e-nightly` group has `cancel-in-progress: false`. The union gate is VOID on a missing or empty report (rc 2). The forwarder is killed by PID in `always()` and again at teardown | MET |
| — | T-30-SC: the pinned CLI | `gh release view`, published checksums, local `sha256sum`, corrupted copy | v1.55.6 from stripe/stripe-cli (an Organization, not a fork), published 2026-10-30. The pin equals `stripe-linux-checksums.txt` and the local hash. A copy with one byte changed is refused (rc 1) | MET |
| — | T-30-87: the secret or whsec in logs or argv | YAML read and a trace probe | Logs: both keys and the whsec are masked, the CLI reads the key from the environment (A12) and curl uses a `-K` file. **Artifact: a failing trace keeps the Authorization header (probe: 4 files), and the artifact is public** | **NOT MET** |
| — | A12 | the verified binary, empty HOME, a deliberately invalid `sk_test_` | rc 1 `401 … sk_test_****2706`; the masked tail is the probe's own. The environment key is read, and it wins over a stored login | Settled |

Also green on the head: `check-stripe-ci-keys.sh --selftest` 6/6, with two arms (a live key accepted gives 5/6; an echoed value fails no-echo). Manual probes refuse unset, `rk_test_`, a dash, and an unset pk, with no value printed. `check-gate-enforcement.sh` rc 0 (arm with both references removed: rc 1). docs-freshness and doc-metrics rc 0 (two arms, rc 1). `check-test-count-oracle.sh playwright` 196/196 and 50/50 (arm without `STRIPE_E2E=1`: 195, rc 1). Bare `tsc` rc 0. gitleaks over `origin/main..217003b5` found no leaks. The `(#1065)` CHANGELOG heading is cited (`check-changelog-cites-pr.sh` rc 0; control rc 1). The lockfile is byte-identical to main.

## Accomplishments (on PR #1065, measured)

- The refund E2E is no longer a skip in the keyless suite. It is a separate desktop spec that only a keyed phase collects, and the main run's listing lost exactly that one test in each of its two projects.
- The nightly has a fail-closed Stripe phase: the key guard, the pinned CLI, the forwarder, the recreated core-java, the zero-skip verdict, the event rows, and the union skip gate.

## Task Commits

On PR #1065, not on this branch:

1. **Task 1: spec, collection switch, counts** — `e6316b47` (feat); round-1 fixes in `217003b5` (`return_url`, `STRIPE_E2E=1` in the test-count oracle)
2. **Task 2: key guard, nightly Stripe phase, ops-contracts step** — `39b4f136` (feat), `8d9dde4a` (ci); round-1 fix in `217003b5` (the nightly aligned with the plan)

**Adoption record:** `daa19b95` (evidence, counted under 30-21) and this SUMMARY's commit.

## Files Created/Modified (PR #1065)

- `frontend/e2e/stripe-refund-flow.spec.ts`: the real-Stripe refund E2E (`STRIPE-REFUND …` line, `STRIPE_E2E_EVIDENCE` ids file)
- `frontend/e2e/vendor-refund-flow.spec.ts`: the paid test removed; the DRAFT test byte-identical
- `frontend/playwright.config.ts`: the `testIgnore` union of the `STAGING_E2E` and `STRIPE_E2E` switches
- `scripts/check-stripe-ci-keys.sh`: the key-mode guard with `--selftest`
- `scripts/check-test-count-oracle.sh`: lists with `STRIPE_E2E=1` (not in the plan's file list; round-1 finding 1)
- `.github/workflows/e2e-nightly.yml`: the Stripe phase, the reworded header, the union gate
- `.github/workflows/ci-cd.yaml`: the key-guard selftest step
- `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`: specs 49 to 50 (blocks unchanged at 196)
- `docs/CHANGELOG.md` (#1065), and `HANDOFF.md` (the gate count 62 to 63, plus a header line)

## Decisions Made

- 30-22 is not ticked. Its SUMMARY is `status: halted`, so 30-23 (which depends on it) reads as blocked until the gaps are fixed and this plan is re-summarized `complete`.

## Deviations from Plan

As written on PR #1065:

1. **Exactly one refund** became `>= 1` (this is the NOT MET above). There is also no 30 s poll on `/v1/refunds`, just a single GET.
2. **CAPTURED** is polled on the vendor API (`GET /api/v1/orders/{id}`) for 30 s, not on public tracking for 60 s. Must-have 1 is still met through the forwarded webhook.
3. The spec waits for the heading `/Refunds \(\d+\)/`, not "Refunds (1)". `text=£1.00` is matched first-anywhere. There is **no assertion that the page shows the Refunded status** (the action asks for one). The confirm response's CONFIRMED status is not read.
4. **The livemode fallback:** when `refund.charge` is not a string, the PaymentIntent's livemode stands in for the charge's.
5. The spec reaches the public API at `/public/shops/...` rather than `/api/v1/public/...`. Both are mapped (`PublicStorefrontController` l.52), so this is harmless.
6. **The nightly verdict** checks "passed >= 1, 0 failed, 0 skipped" rather than "the refund title passed". Since the run is one file with one test, the two are equivalent.
7. **The header comments are reworded** so the token `continue-on-error` is absent from the file. The plan's own check counts comments, and main's header named the token.
8. **Not in the file list:** `scripts/check-test-count-oracle.sh` and `HANDOFF.md`, both from round-1 gate findings.
9. The key guard also accepts `STRIPE_TEST_PUBLISHABLE_KEY` as a fallback name for the pk.

**Total deviations:** 9. Item 1 and the T-30-87 gap are blocking. The rest are recorded.

## Issues Encountered

- **The base moved during the measurement:** #1068 (`025ed690`, 00:17:07Z) leaves #1065 1 behind, with conflicts in `AGENTS.md`, `CLAUDE.md` and `docs/CHANGELOG.md`. A base merge (with `docs-freshness --write`) is needed before 30-23 dispatches or merges.
- **Round-1 recorded item 1** cites an upstream `stripe_1.55.6_checksums.txt`. The release has no such asset; the published file is `stripe-linux-checksums.txt`. The pin is still correct.
- **Disclosure:** the A12 control (no key in the environment) found the operator's own stored Stripe CLI login, apparently in the desktop keyring, and printed a real `stripe listen` signing secret into this session's tool output. It is not in any file.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: secret-in-artifact | frontend/playwright.config.ts (trace retain-on-failure) + .github/workflows/e2e-nightly.yml (upload frontend/test-results/) + frontend/e2e/stripe-refund-flow.spec.ts (Bearer header via APIRequestContext) | On any failed Stripe-phase run, trace.zip holds the CI sandbox sk_test_ in plaintext in a 14-day artifact of a PUBLIC repo. Fix options: `test.use({ trace: "off" })` in the Stripe spec, or Stripe calls through Node `fetch` (not traced), or exclude the Stripe run's test-results from the upload |

## User Setup Required

None yet. **Do not store the CI keys** (30-23 Task 1) until the T-30-87 fix is on PR #1065.

## Next Phase Readiness

The owner's next steps, in order:

1. On PR #1065 (Antigravity's branch or the owner's own): fix the trace leak, and change `toBeGreaterThanOrEqual(1)` to exactly 1 (ideally polled for 30 s, plus the Refunded-status assertion). Merge `origin/main` (resolve the three doc conflicts, then run `docs-freshness --write`). Push.
2. Run a D3 round on the fix commits (`/review-verify 1065`; round 3+ is the owner's decision under §14.4).
3. Then re-run this plan's two failed checks on the new head, re-summarize 30-22 as `complete`, and start 30-23: the CI sandbox keys, branch nightly 1 (S-3 red expected), the ALLOW retired from the measured count, nightly 2 green, and the PR marked ready.
4. 30-24: squash-merge, deploy watch, the main nightly, and the #61 comment.

Note: 30-23 and 30-24 name `feat/30-pay01-refund-e2e` as PR 6's branch. PR 6 is #1065 on `agy/30-21-22-refund-e2e`, so dispatch with `--ref agy/30-21-22-refund-e2e`, or bring the commits to the planned branch. That is the owner's choice, recorded in HANDOFF.

## Self-Check: PASSED

- FOUND: `evidence/30-21-22-adopt-1065.txt` and `daa19b95` (on HEAD)
- FOUND: `e6316b47`, `39b4f136`, `8d9dde4a` and `217003b5` on `origin/agy/30-21-22-refund-e2e`
- The NOT MET items are stated in the first lines and in the coverage block (D1 and D4 are `fail`)

---
*Phase: 30-live-stripe-test-mode-execution*
*Halted: 2026-11-01*
