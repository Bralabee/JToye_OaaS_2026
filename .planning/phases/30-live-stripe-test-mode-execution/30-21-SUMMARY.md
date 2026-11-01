---
phase: 30-live-stripe-test-mode-execution
plan: 21
subsystem: e2e-skip-budget-gate
tags: [pay-01, skip-budget, s-5, playwright, ops-contracts, antigravity-adoption, pr-1065]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-20: PR 5 (#1064) merged as bff82097; the branch feat/30-pay01-refund-e2e cut from it"
provides:
  - "S-5 in scripts/check-e2e-skip-budget.sh: an ALLOW needs a contiguous comment block directly above it with a justification and a non-empty REMOVE WHEN:, else FAIL naming S-5 and the ALLOW"
  - "--selftest (14 arms, TAP via E2E_SKIP_SELFTEST_FORMAT=tap), --lint-conf [FILE] (S-5 alone, no report), repeatable --report FILE (titles unioned, counts summed, every report digest-checked, a missing one VOID)"
  - "Two ops-contracts steps in ci-cd.yaml with no if: (--selftest, --lint-conf), so a bare ALLOW cannot merge on any PR"
  - "Adopted from PR #1065 (Antigravity, agy/30-21-22-refund-e2e) head 217003b5; re-measured in evidence/30-21-22-adopt-1065.txt"
affects: [30-22, 30-23, 30-24]

estimate:
  tokens: 60000
  tasks: 2
actuals:
  tokens: 5840      # chars/4 over the 30-21 files in origin/main...217003b5 (check-e2e-skip-budget.sh 22387 + ci-cd.yaml 974); the code is Antigravity's, on PR #1065
  tasks: 2
  commits: 1        # MEASURED: git rev-list --count 6563598d..HEAD = daa19b95 (the adoption evidence). The two code commits f928ba16 and 8d9dde4a live on PR #1065, not on this branch
plan_head_before: 6563598d168b0a013997435a15dd715250d8c5a3
plan_head_after: daa19b95

tech-stack:
  added: []
  patterns:
    - "Adopting another session's branch: re-run the plan's own verify blocks in a detached run-only checkout of the exact head, with a break arm per criterion; the PR body, review comments and CI are context, never proof"
    - "Pitfall 9 arm: an exemption rule is proven only by an ALLOW that matches a SKIPPING test, so S-2 and S-3 stay quiet and only S-5 can fail"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-21-22-adopt-1065.txt
    - .planning/phases/30-live-stripe-test-mode-execution/30-21-SUMMARY.md
  modified:
    - scripts/check-e2e-skip-budget.sh (on PR #1065, f928ba16)
    - .github/workflows/ci-cd.yaml (on PR #1065, 8d9dde4a)

key-decisions:
  - "30-21 is adopted from PR #1065 head 217003b5: every must-have re-measured MET (owner ruling 2026-11-01: adopt if proven)"
  - "The PR body's description of --report ('a spec counts as skipped only if every report skipped it') is wrong about its own code; the code unions, as the plan requires. The CHANGELOG wording is right"

requirements-completed: []   # PAY-01 completes only when 30-23/30-24 show the refund E2E passing; this plan is the gate half

coverage:
  - id: D1
    description: "S-5 refuses an ALLOW without a justification or a REMOVE WHEN, each case in isolation, and only S-5 fires on a bare ALLOW that matches a skipping test"
    requirement: PAY-01
    verification:
      - kind: unit
        ref: "bash scripts/check-e2e-skip-budget.sh --selftest (14/14 PASS rc 0; RED arm with the S-5 call removed: 6 not ok, 8 ok)"
        status: pass
    human_judgment: false
  - id: D2
    description: "--lint-conf passes the real conf and refuses a bare re-added ALLOW; --report unions several digest-checked reports"
    requirement: PAY-01
    verification:
      - kind: unit
        ref: "--lint-conf rc 0 real / rc 1 bare stomp-relay ALLOW / rc 2 bad directive; union probes in evidence/30-21-22-adopt-1065.txt"
        status: pass
    human_judgment: false
  - id: D3
    description: "The selftest and the conf lint run on every PR in ops-contracts"
    requirement: PAY-01
    verification:
      - kind: other
        ref: "plan T2 verify 3 true=2 on the head, true=0 on origin/main; steps carry no if:; check-ci-docs-skip-wiring.sh rc 0 and --selftest rc 0"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-11-01
---

# Phase 30 Plan 21: the skip-budget gate refuses an exemption that gives no reason and no exit

**S-5 is in `check-e2e-skip-budget.sh`. Every `ALLOW` now needs a justification and a `REMOVE WHEN:` line in the comment block directly above it. A bare `ALLOW` that matches a skipping test fails on S-5 alone. The rule runs on every PR through `--selftest` and `--lint-conf` in ops-contracts. `--report` can be given more than once, so the nightly can gate its two runs together.**

Antigravity wrote the code on `agy/30-21-22-refund-e2e` (commits `f928ba16` and `8d9dde4a`, then `217003b5` for the review fixes). It is open as DRAFT PR #1065. Its D3 series is complete: round 1 admitted 4 and fixed 4, round 2 admitted 0, and the Review-Record is on 217003b5.

This plan is adopted from that head. Each criterion below was re-run in a detached checkout of 217003b5, with its fail direction. Full output with UTC times: `evidence/30-21-22-adopt-1065.txt`.

## Performance

- **Duration:** about 15 min of the adoption session (the 30-21 share)
- **Started:** 2026-11-01T00:15:27Z (verification checkout created)
- **Completed:** 2026-11-01T00:30Z
- **Tasks:** 2 of 2 (implemented on PR #1065, re-measured here)
- **Files modified:** 2 (on PR #1065)

## Must-haves: re-measured on 217003b5

| Must-have | Command (in the verification checkout) | rc / counts | Verdict |
|---|---|---|---|
| S-5: a contiguous comment block with a justification and a non-empty `REMOVE WHEN:`, else rc 1 naming S-5 and the ALLOW | `--lint-conf` on a copy of the real conf with a bare `ALLOW stomp-relay.spec.ts` after a blank line; the same copy with a justification but no REMOVE WHEN | rc 1 and rc 1, each printing `FAIL: S-5 ALLOW 'stomp-relay.spec.ts' lacks …` | MET |
| The selftest proves each refusal in isolation (bare-allow fails only S-5; no-remove-when, empty-remove-when, remove-when-only, blank-line-gap; full-block passes; S-1, S-2, S-3 and digest VOID still hold) | plan T1 verify, verbatim | rc 0, missing 0, FAIL lines 0 | MET |
| (fail direction) | the same selftest with the one `check_s5_block "$value"` call made a no-op, in TAP format | rc 1: not ok for bare-allow (the gate passed it, rc 0), no-remove-when, empty-remove-when, remove-when-only, blank-line-gap and lint-bare; ok for full-block, undeclared-skip, stale-allow, over-budget, digest-mismatch, lint-real-conf and both union arms. Restored by sha256 | RED_EVIDENCE_OK shape |
| `--lint-conf [FILE]` runs S-5 alone (rc 0 on the real conf, rc 1 on a bare ALLOW); `--report` is repeatable and every report must carry the tree digest | plan T2 verify 1 and 2, verbatim, plus probes | `PASS: lint 2 ALLOW block(s)` rc 0. Bare ALLOW rc 1. Bad directive rc 2. A skip only in report B is matched (rc 0). An undeclared skip only in B gives S-2 (rc 1). A missing B is VOID (rc 2). Counts are summed (S-1 fires at 2 > 1). `E2E_REPORT` is ignored under `--report` | MET |
| The selftest and the lint run on every PR in ops-contracts | plan T2 verify 3, verbatim | `true=2` on the head and `true=0` on origin/main. The job and both steps have no `if:`. `check-ci-docs-skip-wiring.sh` rc 0, and its `--selftest` rc 0 | MET |

Threats: T-30-83 and T-30-84 are mitigated as planned (S-5 runs on every PR, and every `--report` is digest-checked, so a mismatch is VOID). T-30-85 is shown by the RED arm above: the existing-rule arms passed on the RED tree. T-30-SC: this plan installs no package.

## Accomplishments

- An exemption can no longer be added silently. The gate refuses an `ALLOW` that does not say why the test is skipped and what removes the skip.
- The refusal shows on every PR, documentation-only PRs included, not only on nights the suite runs.
- The nightly can gate the keyless run and the Stripe run as one skip set (used by 30-22).

## Task Commits

The code lives on PR #1065, not on this branch:

1. **Task 1: S-5 and the selftest** — `f928ba16` (feat), with review fixes in `217003b5`
2. **Task 2: `--lint-conf`, repeatable `--report`, ops-contracts wiring** — `f928ba16` and `8d9dde4a` (ci)

**Adoption record:** `daa19b95` (this branch: the re-measurement evidence).

## Files Created/Modified

- `scripts/check-e2e-skip-budget.sh` (PR #1065): S-5, `--selftest`, `--lint-conf`, repeatable `--report`, and updated WHAT IT ENFORCES and USAGE blocks
- `.github/workflows/ci-cd.yaml` (PR #1065): two ops-contracts steps, plus 30-22's key-guard selftest step
- `evidence/30-21-22-adopt-1065.txt` (this branch): the re-measurement, shared with 30-22

## Decisions Made

- Adopted on the measured evidence, per the owner's ruling. No code was changed on PR #1065.

## Deviations from Plan

1. **Shared implementation commit.** Tasks 1 and 2 landed together in `f928ba16`, so there is no separate RED commit. The RED evidence above comes from this session's arm on the head, not from the original session. The original session's own RED record is not in the repository.
2. **PR body wording.** The PR body says a spec counts as skipped only if every report skipped it. The code does what the plan says: titles are unioned and counts are summed. Only the PR body is wrong, and it is not this branch's to edit. The owner may want it corrected before the merge.
3. **The header-only edit to the conf's SYNTAX note** is left to 30-23, as planned. The conf at the head is byte-identical to origin/main.

**Total deviations:** 3, all recorded. None changes behaviour.

## Issues Encountered

- `origin/main` moved during the measurement: #1068 (`025ed690`, 00:17:07Z). PR #1065 is now 1 behind, with merge conflicts in `AGENTS.md`, `CLAUDE.md` and `docs/CHANGELOG.md` (metric prose and the Unreleased list). This does not change any 30-21 must-have. 30-23 must merge the base into the PR branch first: rule 3 requires it, and the changelog contract is red until then (C-1 asks for #1068's entry).

## User Setup Required

None for this plan.

## Next Phase Readiness

- The gate half of PAY-01 is proven on the head. 30-22 is NOT MET (see `30-22-SUMMARY.md`), so 30-23 does not start until its two gaps are fixed on PR #1065.
- 30-23 uses this plan's `--lint-conf` for its S-5 break arms on the retired conf.

## Self-Check: PASSED

- FOUND: `.planning/phases/30-live-stripe-test-mode-execution/evidence/30-21-22-adopt-1065.txt`
- FOUND: `daa19b95` on HEAD. FOUND: `f928ba16`, `8d9dde4a` and `217003b5` on `origin/agy/30-21-22-refund-e2e`.

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-11-01*
