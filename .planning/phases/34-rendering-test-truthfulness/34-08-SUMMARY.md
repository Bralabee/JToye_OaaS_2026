---
phase: 34-rendering-test-truthfulness
plan: 08
subsystem: testing
tags: [coverage, ci, gates, go, jest, falsifiability, no-regression-guardrail]

requires:
  - phase: 34-rendering-test-truthfulness
    provides: "34-02's frontend/hooks/use-theme.ts and 34-03's rewritten hooks/use-customer-session.ts — the two modules the scope widening exists to make visible, and the reason the baseline had to be re-measured rather than taken from RESEARCH"
  - phase: 34-rendering-test-truthfulness
    provides: "34-07's ci-cd.yaml wiring, and its measured finding that check-gate-enforcement.sh counts a gate named in a WORKFLOW COMMENT as wired"
provides:
  - "scripts/check-go-coverage.sh — the first consumer the edge-go coverage profile has ever had; G-1 structural parse, G-2 total extraction, G-3 numeric floor at 65.0%"
  - "frontend/jest.config.js — collectCoverageFrom widened to hooks/**, plus a coverageThreshold of 63/55/60/64 with both measurements and the margin rule recorded above it"
  - "the Go gate wired into ci-cd.yaml's test job and --coverage added to the Jest step, with --json --outputFile preserved"
  - "the measured fact that `go tool cover -func` reports 0.0% at rc=0 for an empty AND for a mode-line-only profile — the reason the research-supplied gate shape was insufficient"
affects: [34-09, 34-10, any future plan that adds frontend or edge-go code]

tech-stack:
  added: []
  patterns:
    - "structural validation BEFORE numeric validation: first confirm the file really is the kind of artefact it claims to be, and only then believe its numbers — a tool that reports 0 at rc=0 for garbage turns a broken run into a code regression"
    - "scope decision recorded and applied before the threshold number is chosen, so the number cannot silently fix the scope"
    - "floor(measurement) - 2 as ONE stated rule for four counters, rather than four separately-negotiated numbers"
    - "never name a gate script in a workflow comment beside its own step — 34-07's measured trap, avoided by construction and re-proven by the wiring arm here"

key-files:
  created:
    - scripts/check-go-coverage.sh
  modified:
    - frontend/jest.config.js
    - .github/workflows/ci-cd.yaml
    - .gitignore

key-decisions:
  - "G-1's structural parse is the load-bearing part of the Go gate and is NOT what RESEARCH proposed. MEASURED, Go 1.26: `go tool cover -func` on an EMPTY file and on a mode-line-only file both exit 0 reporting 'total: (statements) 0.0%'. The rc-then-total shape RESEARCH supplied would have reported a broken or skipped test run as a 0% coverage regression — an infrastructure failure diagnosed as a code problem. The gate requires a mode line plus >= 1 data line before believing any percentage."
  - "The Jest scope was widened to hooks/** BEFORE any threshold number was chosen, and both measurements are written into the config. The widening happened to RAISE all four counters (9 files, 313 statements entered), but the direction was not predictable — hooks/ holds untested files too — and the floor is set from the post-widening measurement either way."
  - "34-RESEARCH's 63.76 / 57.06 / 60.71 / 65.10 was NOT reused. It was measured earlier the same day, before waves 1 and 2 landed. Today's pre-widening figure on the same scope is 64.6 / 57.69 / 61.49 / 65.96. A threshold set from a superseded number is a threshold set from nothing."
  - "Thresholds are floor(measurement) - 2 on every counter — one stated rule, not four negotiations. Margins 2.12 / 2.75 / 2.02 / 2.49 points, covering RESEARCH assumption A2 (CI is not this machine, rated MED)."
  - "The Go gate resolves the repo root and module dir itself and cds into edge-go, so the CI step carries NO working-directory. `go tool cover` MUST run inside the module (measured: from the repo root it fails with 'go.mod file not found'), and stating that fact in two places would let them drift."
  - "The gate's filename appears in ci-cd.yaml ONLY on its two run: lines and in no comment. 34-07 measured that check-gate-enforcement.sh does not strip comments, so a gate named in prose beside its own step makes the unwiring arm incapable of failing. Proven here: deleting the 4 step lines leaves the 27-line comment standing and the meta-gate still goes red."
  - "The count oracle's rc=1 is the PRE-EXISTING docs/metrics.json drift (runner 1272 vs manifest 1230), not this change. deferred-items.md says in terms: 'Do not fix it here or in 34-08/34-09: single-writer is the point.' Isolated with a control arm rather than asserted."
  - "edge-go/coverage.out added to .gitignore. It is generated output that the gate's own local run produces; CI uploads it as an artifact rather than committing it."

patterns-established:
  - "Every VOID arm run against the artefact class it guards, including the two that a naive gate would have PASSED (empty, mode-line-only) — with the tool's own contradicting output recorded beside them as the control"
  - "A threshold is not trusted until it has been raised above its measurement and observed red while the SUITE still passes — 'Tests: 1272 passed' in every fail arm proves the threshold and not a test is what fires"
  - "When a plan's acceptance criterion cannot be satisfied, replace it with a strictly stronger form, run that, and say both — never report the unsatisfiable one as met"

requirements-completed: [TRUTH-02]

metrics:
  duration: "~72 min (npm ci 23:17 → last task commit 23:29, plus measurement, ten break arms and the closing sweep)"
  completed: 2026-08-28
  tasks: 3
  commits: 3
  files-created: 1
  files-modified: 3
---

# Phase 34 Plan 08: Coverage Consumers Summary

The edge-go coverage profile CI has produced for months now has a reader that fails
closed, and the frontend has a Jest-enforced floor measured against a scope widened to
include this phase's own hooks — with the scope decision recorded before the numbers and
every threshold observed red before being trusted.

## What Was Built

**`scripts/check-go-coverage.sh`** (198 lines, `100755` in the git index — the on-disk
mode is 775 under this machine's umask, which is why the CI step still runs `chmod +x`
like every other gate step in the file). Structural sibling of
`check-e2e-skip-budget.sh`: same header order (WHY THIS EXISTS / WHAT IT ENFORCES /
INPUT / EXIT CODES / USAGE, plus a MEASURED stanza), `set -uo pipefail`, `void()` /
`fail()`, `--help` served from its own header, unknown argument is VOID.

| id | asserts |
|----|---------|
| G-1 | the profile exists, is non-empty, opens with a `mode:` line and carries >= 1 coverage data line — anything else VOIDs, never 0% |
| G-2 | `go tool cover -func` succeeds and emits a numeric `total:` line |
| G-3 | that total is at or above `MIN_TOTAL_PERCENT` (65.0), compared with awk numerics |

On failure it prints a per-package table computed from the profile, because a total that
moved is nearly always one package.

**`frontend/jest.config.js`**. `hooks/**/*.{js,jsx,ts,tsx}` added to
`collectCoverageFrom`, plus a `coverageThreshold` of 63 / 55 / 60 / 64, each with its
measurement, date, margin and the scope decision in the comment directly above.

**`.github/workflows/ci-cd.yaml`**. One new `test`-job step after `Run Go tests`, and
`--coverage` added to the Jest step with `--json --outputFile` preserved byte-for-byte.
Both carry a falsification record in the `:840-846` house format.

## Measurements — all re-taken, none carried over

### Go (`edge-go`), 2026-08-28, Go 1.26

```
github.com/jtoye/edge/cmd/edge              49.8%   (128/257 stmts)
github.com/jtoye/edge/docs                   0.0%   (0/1 stmts)
github.com/jtoye/edge/internal/auth         88.6%   (31/35 stmts)
github.com/jtoye/edge/internal/core         80.0%   (92/115 stmts)
github.com/jtoye/edge/internal/middleware   79.8%   (91/114 stmts)
github.com/jtoye/edge/internal/whatsapp     92.6%   (25/27 stmts)
------------------------------------------------------------------
total                                       66.8%   (367/549 stmts, 311 blocks)
```

**Identical to RESEARCH's figures to the tenth of a point**, on an independent re-run —
so the research number is reproduced rather than restated. Floor 65.0, margin 1.8 points
(~10 of 549 statements).

The gate's per-package aggregator was validated against `go test`'s own per-package
output: all six figures identical. That is a positive control on the table, not a
decoration — an aggregator that mis-attributes blocks would print a plausible wrong table
on exactly the run where someone needs it.

### Jest (frontend), 2026-08-28, on this tree with waves 1 and 2 landed

|            | pre-widening (app/components/lib/types) | post-widening (+ hooks/) |
|------------|----------------------------------------:|-------------------------:|
| Statements | 64.6%   (4029/6236)                     | **65.12%** (4265/6549)   |
| Branches   | 57.69%  (1930/3345)                     | **57.75%** (1980/3428)   |
| Functions  | 61.49%  (837/1361)                      | **62.02%** (890/1435)    |
| Lines      | 65.96%  (3721/5641)                     | **66.49%** (3939/5924)   |

124 suites / 1272 tests in **both** runs — the widening changes what is MEASURED, never
what is EXECUTED.

**RESEARCH's pre-widening figures (63.76 / 57.06 / 60.71 / 65.10) are superseded**, not
contradicted: they were measured earlier the same day, before this phase's own tests
landed. Waves 1 and 2 moved the same scope to 64.6 / 57.69 / 61.49 / 65.96.

Thresholds: `floor(measurement) - 2` → **63 / 55 / 60 / 64**, margins 2.12 / 2.75 / 2.02
/ 2.49 points.

### SCOPE ARM — the widening actually changed the measured scope

`--coverageReporters=json-summary`, compared programmatically across the two runs:

```
files pre: 189   post: 198   added: 9   removed: 0
statements pre: 6236   post: 6549   delta: +313
```

Before: **0** paths containing `/hooks/`. After: **9** —
`use-cart-count`, `use-count-up`, **`use-customer-session`**, `use-order-events`,
`use-shop-context`, `use-stomp`, `use-stored-state`, **`use-theme`**, `use-toast`.
The two this phase owns are among them, which is the whole point of the decision.

**Positive control on the "0 before":** the same query over the same summary file
reported `files: 189` including `frontend/lib/accessibility-statement.ts`, so the zero is
a fact about the glob, not a broken query. An unvalidated zero is a bug in the check.

## Every Arm, Both Directions

Bracketed clean → arms → clean. Each tree-mutating arm was run **after** the commit it
targets, restored by pathspec, and verified by `git hash-object`.

### Task 1 — the Go gate (pre-arm hash `78870d8c719fa1097c9126bcb637b52b33ddd219`)

| arm | how it was broken | result |
|-----|-------------------|--------|
| clean (opening) | — | `PASS: … 66.8% >= 65.0% floor (311 coverage blocks)` **rc=0** |
| VOID: profile absent | `GO_COVERPROFILE=/nonexistent/path/coverage.out` | `VOID: coverage profile not found: … An absent profile is not 0% coverage.` **rc=2** |
| VOID: empty profile | a 0-byte file | `VOID: coverage profile is EMPTY (0 bytes) … not that nothing is covered.` **rc=2** |
| VOID: junk line | `this is junk` | `VOID: unparseable coverage profile … first line is 'this is junk', expected a 'mode:' header.` **rc=2** |
| VOID: mode line, no data | `mode: set` alone | `VOID: … carries a mode line but ZERO coverage data lines. 'go tool cover' would report 0.0% at rc=0 for this file` **rc=2** |
| VOID: unknown argument | `--no-such-flag` | `VOID: unknown argument: --no-such-flag (try --help)` **rc=2** |
| VOID: no module dir | `GO_MODULE_DIR` at a dir with no `go.mod` | `VOID: no go.mod in … 'go tool cover' cannot resolve package paths there` **rc=2** |
| FAIL: floor raised | `MIN_TOTAL_PERCENT` 65.0 → 99.9 | `FAIL: edge-go total coverage 66.8% is BELOW the floor of 99.9%` + the six-package table **rc=1** |
| clean (closing) | — | **rc=0**, `git status --short` 0 lines |

Restore verified by content: `78870d8c…` before and after, and
`grep -n '^MIN_TOTAL_PERCENT=' → 108:MIN_TOTAL_PERCENT=65.0`. (That line is **109** on
the final tree — the Task 2 commit added one header line when it corrected the
`perf-budgets.ts` path. The 108 is what the arm actually printed at the time it ran, and
is left as measured rather than retro-fitted; re-measured now, `grep -n` returns
`109:MIN_TOTAL_PERCENT=65.0`. The restore was verified by hash, not by line number.)

**THE TWO ARMS THAT JUSTIFY THE DESIGN, with the tool's own contradicting output beside
them.** Run in the same session, immediately after those arms:

```
go tool cover -func=<empty file>     -> rc=0  out=[total:	(statements)	0.0%]
go tool cover -func=<mode: set only> -> rc=0  out=[total:	(statements)	0.0%]
```

Both would have sailed through `rc != 0 -> VOID` and `[ -z "$total" ] -> VOID`, landed on
the numeric comparison with `total=0.0`, and been reported as a catastrophic coverage
regression. A broken or skipped test run would have been diagnosed as a code problem, and
the person reading the red would have gone looking for deleted tests. Only the junk-line
arm makes `go` itself fail (rc=2, "bad mode line") — the two dangerous shapes are silent.

### Task 2 — the Jest thresholds (pre-arm hash `dd133e728efad9789fdf977898ee13ff7abe440d`)

Every counter raised to 90 (above its measurement) once, one at a time:

| arm | Jest's own message | rc | suite |
|-----|--------------------|----|-------|
| statements 63 → 90 | `"global" coverage threshold for statements (90%) not met: 65.12%` | **1** | `Tests: 1272 passed` |
| branches 55 → 90 | `"global" coverage threshold for branches (90%) not met: 57.75%` | **1** | `Tests: 1272 passed` |
| functions 60 → 90 | `"global" coverage threshold for functions (90%) not met: 62.02%` | **1** | `Tests: 1272 passed` |
| lines 64 → 90 | `"global" coverage threshold for lines (90%) not met: 66.49%` | **1** | `Tests: 1272 passed` |

`Tests: 1272 passed` in all four is the point: the suite is green and the run is red, so
what fires is the threshold and not a test. Every restore hashed back to
`dd133e72…`; closing clean arm rc=0 at 65.12 / 57.75 / 62.02 / 66.49; `git status --short`
0 lines.

### Task 3 — the wiring (pre-arm hash `bd0d473b44987359d038fa9368aaa8805e310839`)

| arm | how it was broken | result |
|-----|-------------------|--------|
| clean (opening) | — | `gates: 38, workflows: 6, exempt: 6` → **rc=0** |
| WIRING | the four step lines deleted from the committed file | `FAIL: 1 gate(s) are referenced by no workflow … check-go-coverage.sh` **rc=1** |
| clean (closing) | — | **rc=0**, `git status --short` 0 lines, hash back to `bd0d473b…` |

**The wiring arm was measured precisely, on a copy, because 34-07 shipped a defect here
and caught it only in this same arm.** The `sed` deletes exactly 4 lines
(1643 → 1639); the diff is exactly the `- name:` line, `run: |`, and the two command
lines. Afterwards:

```
grep -c 'Plan 34-08 (TRUTH-02, #110)'  -> 1   (the 27-line falsification comment survives)
grep -c 'check-go-coverage'            -> 0   (the filename is gone from the workflow)
```

That is why the meta-gate can fail. `check-gate-enforcement.sh` searches workflow files
by filename with `grep -qF` and does **not** strip comments, so had the comment named the
script — as 34-07's `frontend-e2e` comment did — the meta-gate would have printed
`PASS … rc=0` over a deleted step. On the clean tree the filename appears at exactly two
places, both `run:` lines: `ci-cd.yaml:114` and `:115`.

### YAML

Parsed with **PyYAML 6.0.3 under `conda run -n jtoye-ops`** (the machine's base-python
guard blocks an undeclared interpreter and this repo declares no `.conda-env`, so the env
is named rather than guessed). rc=0, 13 jobs, `test` job 17 steps. Both edits confirmed in
the **parsed structure**, not merely in the bytes:

```
name: Enforce the edge-go coverage floor (#110)
run : 'chmod +x ./scripts/check-go-coverage.sh\n./scripts/check-go-coverage.sh\n'
working-directory: <none — repo root>

run : 'npm test -- --ci --watchAll=false --coverage --json --outputFile="$RUNNER_TEMP/jest-report.json"'
  has --coverage: True   has --json: True   has --outputFile: True
```

**Fail direction executed:** a copy with an unterminated flow sequence appended raises
`yaml.parser.ParserError: while parsing a flow sequence`, rc=1. The parse check is not
incapable of failing.

Both oracle steps still read `$RUNNER_TEMP/jest-report.json` via `--report` and nothing
depends on the Jest step's stdout shape — read out of the parsed YAML, not assumed.

### Diff confinement

```
@@ -84,0  +85,32 @@
@@ -131,0 +164,21 @@
@@ -133   +186   @@
```

Job `test` spans **17–221** after the edit and **17–168** before it (`integration-tests`
starts at 222 now, 169 before — measured on both sides with `git show HEAD:…`). Every old
position (84, 131, 133) is inside the old range and every new one (85–116, 164–184, 186)
inside the new. No hunk falls in `integration-tests`, `frontend-e2e` or `ops-contracts`,
so 34-09's JaCoCo work in the same file stays disjoint.

### Flag counts

| pattern | before | after |
|---------|-------:|------:|
| `outputFile=` | 1 (rc=0) | **1 (rc=0)** — unchanged |
| `--coverage` on a `run:` line | 0 | **1** |
| `--coverage` anywhere | 0 (rc=1) | 4 |

The plan's criterion was "the bare `--coverage` count increased by exactly 1"; it
increased by 4, because the explanatory comment the same task requires necessarily
mentions the flag three times. **Stated rather than silently substituted:** the criterion
as written measures comment prose, so the strictly stronger form — occurrences on `run:`
lines, which is what "the flag is actually passed" means — was measured instead and moved
by exactly 1. The bare count is recorded above so a reader can check both.

The `0 (rc=1)` before is not an unvalidated zero: `outputFile=` returned 1 at rc=0 from
the same instrument on the same file in the same session.

## Local reproduction of what CI will do, end to end

```
cd edge-go && go test -v -coverprofile=coverage.out ./...      rc=0
cd .. && ./scripts/check-go-coverage.sh                        rc=0   66.8%
cd frontend && npm test -- --ci --watchAll=false --coverage \
    --json --outputFile=<report>                               rc=0   124 suites / 1272 tests
bash scripts/check-test-count-oracle.sh jest --report <report>  rc=1  (see below)
bash scripts/check-gate-enforcement.sh                         rc=0   38 gates / 6 exempt
```

### The one criterion that could not be satisfied, and its control arm

The plan requires the count oracle to exit 0. It exits **1**:

```
jest it/test blocks   runner=1272   manifest=1230
FAIL: … the runner says 1272, docs/metrics.json says 1230.
```

This is the **pre-existing** manifest drift, and it is already adjudicated:
`deferred-items.md:102-140` records it in terms — *"Do not fix it here or in 34-08/34-09:
single-writer is the point"* — with 34-10 the declared single writer. 34-08 adds no test
block of any kind, so it cannot have caused it, and `docs-freshness.sh` reports the same
`total_logical_invocations: 3237` that 34-07 recorded.

**Asserted with a control arm rather than argued.** The same oracle, against a report from
a run **without** `--coverage`:

```
with    --coverage : rc=1   runner=1272 manifest=1230   {"suites":124,"tests":1272,"passed":1272,"success":true}
without --coverage : rc=1   runner=1272 manifest=1230   {"suites":124,"tests":1272,"passed":1272,"success":true}
```

Identical rc, identical message, identical runner-visible counts — despite the reports
differing in size (1,850,269 vs 525,599 bytes). So `--coverage` did not break the report
the oracle reads, which is the question T-34-08-04 actually asks. Reported as unsatisfied
with the stronger claim proven, never as met.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The gate shape RESEARCH supplied cannot distinguish a broken run from zero coverage**

- **Found during:** Task 1, probing `go tool cover` before writing the gate.
- **Issue:** the interfaces block prescribes `rc != 0 -> VOID`, `empty $total -> VOID`,
  else compare. MEASURED: an empty profile and a mode-line-only profile both give rc=0
  and a non-empty total of `0.0`. Neither VOID fires; the comparison runs; the gate
  reports a 0% coverage regression. The plan's own acceptance criterion asks for exactly
  the opposite behaviour, so the criterion and the supplied shape contradict each other.
- **Fix:** added G-1, a structural parse (mode line + >= 1 data line) ahead of any
  numeric read, with both arms executed and the tool's contradicting output recorded.
- **Files modified:** `scripts/check-go-coverage.sh`
- **Commit:** `a4131a21`

**2. [Rule 1 - Bug] The budget anti-pattern precedent is at a different path than the plan states**

- **Found during:** Task 2, verifying a citation before writing it into a config comment.
- **Issue:** the plan (and my first draft of the Go gate header) cite
  `frontend/lib/perf-budgets.ts`. `ls` rc=2, no such file. The file is
  `frontend/e2e/perf-budgets.ts`, and its warning at `:64-70` reads *"Raising a budget
  until the tree passes is how a budget stops meaning anything."*
- **Fix:** both comments now cite `frontend/e2e/perf-budgets.ts:64-70` and quote the real
  sentence rather than a paraphrase I had invented.
- **Files modified:** `scripts/check-go-coverage.sh`, `frontend/jest.config.js`
- **Commit:** `a8ed1950`

**3. [Rule 2 - Hygiene] `edge-go/coverage.out` was untracked and not ignored**

- **Found during:** Task 1, `git status --short` after the first `go test -coverprofile`.
- **Issue:** running the gate locally — which every future reader of this plan will do —
  leaves a ~20 KB generated artefact untracked, one blanket `git add` away from a commit.
  It was in neither `.gitignore` nor `edge-go/.gitignore` (which does not exist).
- **Fix:** `.gitignore` entry with the reason. Confirmed effective: the file vanished from
  `git status --short` while remaining on disk for the gate to read.
- **Files modified:** `.gitignore` (not in the plan's `files_modified`)
- **Commit:** `a4131a21`

### Not Fixed — Out of Scope

`docs/metrics.json` remains behind the tree (jest 1230 vs 1272, total 3188 vs 3237), so
`docs-freshness.sh` and `check-test-count-oracle.sh` are red. Already logged in
`deferred-items.md:102-140` by 34-07, already assigned to 34-10 as single writer, and that
entry explicitly forbids fixing it here. No new entry added — a second entry saying the
same thing would only invite a second diagnosis.

## Threat Model Outcomes

| id | disposition | evidence |
|----|-------------|----------|
| T-34-08-01 empty/unparseable profile read as 0% or clean | **mitigated** | four VOID arms executed (absent, empty, junk, mode-only), each rc=2 naming the input; the two silent shapes recorded beside `go tool cover`'s own contradicting rc=0 / 0.0% output |
| T-34-08-02 a threshold never observed failing | **mitigated** | all four Jest counters and the Go floor raised above measurement once, each red recorded verbatim, each restore hash-verified |
| T-34-08-03 a threshold lowered to go green | **mitigated** | every number sits in version control beside its measurement, date, margin rule and scope, so a lowering is a visible diff; both files name the anti-pattern and cite `e2e/perf-budgets.ts:64-70` |
| T-34-08-04 the count oracle broken by editing its Jest run | **mitigated** | `--json --outputFile` preserved (count 1 → 1); oracle re-run against the `--coverage` report and against a no-coverage control: identical rc, message and runner counts |
| T-34-08-05 coverage artefacts carrying source or secrets | **accept** | upload path unchanged; the profile holds file paths and integers, the summary holds counters |
| T-34-08-SC package installs | **mitigated** | `git diff --name-only <base> HEAD -- frontend/package.json frontend/package-lock.json edge-go/go.mod edge-go/go.sum core-java/build.gradle.kts` prints nothing, while the same command without a pathspec lists the 4 changed files — so the empty result is about the paths, not a broken instrument |

## Verification

| check | result |
|-------|--------|
| `bash scripts/check-go-coverage.sh` | **rc=0** — 66.8%, 311 blocks |
| every Go arm | rc=2 (six VOID) / rc=1 (floor arm), each naming its cause |
| `npx jest --coverage --ci --watchAll=false` | **rc=0** — 124 suites / 1272 tests, thresholds enforced |
| every Jest threshold arm | rc=1, Jest naming the counter, suite still 1272 passed |
| `npm test -- --ci --watchAll=false` (no coverage) | **rc=0** — 124 / 1272, unchanged verdict and counts |
| `npx eslint .` | **rc=0** — 34 warnings, 0 errors (pre-existing; rc is the verdict, not the trailing fixable count) |
| `npx eslint jest.config.js` | **rc=0** |
| `node -e require('./jest.config.js')` | **rc=0** — the config still loads |
| `bash -n scripts/check-go-coverage.sh` | **rc=0** |
| `bash scripts/check-gate-enforcement.sh` | **rc=0** — 38 gates, 6 exempt (was 37 after 34-07) |
| wiring arm | **rc=1** naming `check-go-coverage.sh` |
| `bash scripts/check-ssr-coverage-contract.sh` | **rc=0** (34-07's gate, unaffected) |
| `bash scripts/check-no-measured-placeholders.sh` | **rc=0** — 0 matches |
| `bash scripts/check-e2e-typecheck.sh` | **rc=0** — 29 files clean |
| workflow parses (PyYAML 6.0.3) | **rc=0**; fail direction rc=1 on a broken copy |
| dependency files | unchanged (empty, with a positive control) |
| `bash scripts/docs-freshness.sh` | **rc=1** — pre-existing, owned by 34-10 |

`shellcheck` is **not installed on this machine** (`command -v shellcheck` rc=1) —
recorded as unavailable rather than implied to have passed. `bash -n` is what ran.

## Success Criteria

- [x] `scripts/check-go-coverage.sh` reads the profile CI already produces, fails below a
      measured floor, and VOIDs on missing / empty / unparseable input — **all three
      exercised**, plus a fourth shape (mode line, zero data lines) that the plan did not
      name and that a naive gate would have passed as 0%.
- [x] `frontend/jest.config.js` collects from `hooks/**`, with the scope decision recorded
      and applied **before** the numbers were chosen, both measurements written down, and
      the widening proven to have changed the measured scope (0 → 9 hooks entries, against
      a validated positive control).
- [x] Every declared threshold raised above its measurement once and observed red — four
      Jest counters and the Go floor, five arms, each restore hash-verified.
- [x] The CI Jest step enforces the threshold in the same run the count oracle reuses;
      `--json --outputFile` preserved and the oracle proven still able to read the report.

## Known Stubs

None. Both gates are live, both floors are enforced by real consumers, and the CI wiring
is in place. Nothing is placeholdered — `check-no-measured-placeholders.sh` rc=0, 0
matches.

## Self-Check: PASSED

Artifacts on disk: `scripts/check-go-coverage.sh` (198 lines, `100755` in the index),
`frontend/jest.config.js` (106 lines), `.github/workflows/ci-cd.yaml` (1643 lines),
`.gitignore` (211 lines), and this file. Diffstat against the base `e1c8c574`: 4 files
changed, 330 insertions(+), 1 deletion(-).

All three commits resolve: `a4131a21`, `a8ed1950`, `2629b5ff`.

The existence and commit checks were each run with a **negative control** in the same
loop — `scripts/this-file-does-not-exist.sh` reported MISSING and `deadbee1` reported
MISSING — so a FOUND from either is a fact about the repository and not a check that
answers FOUND to everything.

Every hash quoted above was read from the command that produced it, not remembered:
gate `78870d8c719fa1097c9126bcb637b52b33ddd219`, jest config
`dd133e728efad9789fdf977898ee13ff7abe440d`, workflow
`bd0d473b44987359d038fa9368aaa8805e310839` — each printed identically before and after
its arms. Line citations re-read rather than recalled:
`ci-cd.yaml:114-115` (the only two occurrences of the gate filename in any workflow),
`jest.config.js:88-91` (the four threshold values),
`check-go-coverage.sh:109` (`MIN_TOTAL_PERCENT=65.0` — re-read on the final tree, and
one line below where the Task 1 arm found it; see that arm's note),
`frontend/e2e/perf-budgets.ts:64-70` (the budget anti-pattern sentence),
`deferred-items.md:102-140` (the metrics.json single-writer ruling).
