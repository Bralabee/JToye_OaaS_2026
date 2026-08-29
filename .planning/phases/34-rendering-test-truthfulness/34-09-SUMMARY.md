---
phase: 34-rendering-test-truthfulness
plan: 09
subsystem: testing
tags: [coverage, jacoco, gradle, ci, gates, falsifiability, no-regression-guardrail]

requires:
  - phase: 34-rendering-test-truthfulness
    provides: "34-07's ci-cd.yaml wiring and its measured finding that check-gate-enforcement.sh counts a gate named in a WORKFLOW COMMENT as wired — avoided here by construction and re-proven by the unwiring arm"
  - phase: 34-rendering-test-truthfulness
    provides: "34-08's check-go-coverage.sh, whose header order, void()/fail() shape, exit-code contract and floor(measurement)-2 rule this gate reuses so the two coverage gates read as one family"
provides:
  - "core-java/build.gradle.kts: the core jacoco plugin at a pinned 0.8.12, CSV/XML/HTML at explicit build-local paths, and jacocoAggregateReport over BOTH suites' execution data"
  - "scripts/check-jacoco-coverage.sh — five assertions over the aggregate CSV, floors 85/69/85/85, everything that is not a real measurement VOIDs rather than scoring 0%"
  - "the cross-job .exec hand-off in ci-cd.yaml, the gate under the suite's own if:, and a skip notice that states in words that Java coverage was not evaluated"
  - "the measured fact that the CI aggregate is 0.10-0.56 points BELOW this machine's while the unit half is identical to the hundredth — RESEARCH assumption A2 retired by measurement"
  - "the measured fact that jacocoTestReport's task graph excludes the test task entirely, so a report can be regenerated from a downloaded .exec at zero test cost"
affects: [34-10, any future plan that adds core-java code or edits the top of build.gradle.kts]

tech-stack:
  added: ["org.jacoco 0.8.12 (core Gradle plugin — no version coordinate, no dependency entry)"]
  patterns:
    - "measure the thing that is true, not the thing that is cheap to measure: the aggregate of both suites, with the 25-point gap to the unit-only figure written into the build file, the gate header and the CI skip notice"
    - "a gate must be unsatisfiable by the WRONG artefact, not merely by a MISSING one — J-4 compares the aggregate against the unit-only report, because a stale unit-only CSV is well-formed, plausible, and 25 points wrong"
    - "calibrate a threshold where it will RUN, then record both numbers and the delta — CI was lower on all four counters"
    - "execute a workflow's run block, do not read it: the skip notice's quoting bug was invisible to actionlint and unreachable on a push"

key-files:
  created:
    - scripts/check-jacoco-coverage.sh
  modified:
    - core-java/build.gradle.kts
    - .github/workflows/ci-cd.yaml
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - k8s/LOCAL.md
    - infra/dependency-horizons.yaml

key-decisions:
  - "AGGREGATE, not a unit floor, decided by measurement and not preference. `test` alone is 62.12% line where `test` + `integrationTest` is 87.55% — a 25.43-point gap. A '60% line' gate on the unit suite would have sat two points under the real figure with no power to catch anything, and would have published a number wrong by a quarter of the codebase."
  - "J-4 (aggregate must STRICTLY exceed the unit-only report) is the load-bearing assertion and the unit CSV is REQUIRED, not optional. Proven necessary by regenerating the aggregate from the unit .exec alone and putting both .exec files back: every structural check passes, the CSV is a real well-formed JaCoCo report of the wrong thing, it reads 62.12%, and a naive 60% floor would have PASSED it."
  - "executionData names test.exec and integrationTest.exec EXPLICITLY rather than globbing jacoco/*.exec as the plan specified. Every Test task gets a JacocoTaskExtension, so generateOpenApiSpec and updateOpenApiSnapshot also drop .exec files there; a glob would make the gated number depend on which unrelated command a developer ran first. A future third suite is excluded until named, which under-reports and reds the gate — the safe direction to fail."
  - "CI generates BOTH reports, not just the aggregate. MEASURED with --dry-run: jacocoTestReport's graph is compileJava -> processResources -> classes -> jacocoTestReport with the test task absent entirely (the plugin wires reports with mustRunAfter, never dependsOn), so the unit report is re-derived from the downloaded .exec at zero test cost."
  - "Report output locations are set explicitly rather than left to the plugin's naming convention, so the gate's input path is a fact in version control instead of an inference about Gradle internals."
  - "Floors recalibrated on the CI figure, same rule (floor(measurement) - 2). Only INSTRUCTION moved, 86 -> 85; the other three were already at the CI-derived value. A2 was right to be rated MED — a floor flush against the local number would have had 0.56 of a point of headroom."
  - "The gate is named in ci-cd.yaml ONLY on its two run: lines and in no comment (34-07's measured trap), re-proven by deleting the step and watching check-gate-enforcement.sh go red naming it."

patterns-established:
  - "Bracket every tree-mutating arm clean -> arms -> clean, restore by pathspec, verify by git hash-object, and run the closing clean arm — done twice here (82c8928e for the gate, 0234f9ed for the workflow)"
  - "A VOID arm is only interesting if the artefact it feeds is PLAUSIBLE: the unit-only-aggregate arm regenerated a genuine report rather than corrupting a file"
  - "Every absence used as evidence carries a positive control — the UP-TO-DATE matcher, the exemption search and the pathspec query were each shown to match something before their zero was believed"

requirements-completed: [TRUTH-02]

metrics:
  duration: "~2h 30m (22:33Z start; two full Gradle suites at 1m18s and 22m25s, ten break arms, and a 42m56s CI calibration run)"
  completed: 2026-08-29
  tasks: 3
  commits: 6
  files-created: 1
  files-modified: 5
---

# Phase 34 Plan 09: Aggregate Java Coverage Summary

**Java coverage is now measured over the unit suite AND the Testcontainers suite and gated at 85/69/85/85, with the 25-point gap to the unit-only figure written into three separate places and a run that cannot measure it saying so instead of passing.**

## Performance

- **Duration:** ~2h 30m
- **Started:** 2026-08-28T22:33Z
- **Completed:** 2026-08-29T00:05Z (wall clock includes the 42m56s CI calibration run it waited on)
- **Tasks:** 3 (plus 3 deviation commits)
- **Files created:** 1
- **Files modified:** 5

## Accomplishments

- `core-java` has a JaCoCo report over **both** of its test tasks for the first time, and the number it publishes is 87.55% rather than the 62.12% a unit-only report would have called "coverage".
- A gate that **cannot be satisfied by the wrong artefact**. A well-formed, freshly-generated, entirely plausible unit-only report is refused, not just a missing one.
- The path-filtered integration job's SUCCESS can no longer be misread: it prints, in words, that Java coverage was not evaluated on that run.
- The floors were calibrated against a real 42m56s CI run, which turned out to be **lower on all four counters** than this machine.
- Two defects introduced by this plan, found and fixed: 20 broken doc citations (caught by CI's own citation gate) and a workflow quoting bug that **no gate in this repository could have caught**.

## Task Commits

1. **Task 1: jacoco plugin + aggregate report** — `41450dfc` (build)
2. **Task 2: the aggregate floor gate** — `b29c848c` (feat)
3. **Task 3: CI hand-off, gate wiring, skip notice** — `0cfb6483` (ci)
4. *Deviation:* re-point the shifted `build.gradle.kts` citations — `33439a7f` (docs)
5. *Deviation:* fix the skip notice's unterminated quote — `9fde76f7` (fix)
6. *Task 3 calibration:* floors set against the CI run — `b426b858` (fix)

## Files Created/Modified

- `scripts/check-jacoco-coverage.sh` (**created**, 370 lines, `100755` in the index) — J-1..J-5 over the aggregate CSV.
- `core-java/build.gradle.kts` — `jacoco` in `plugins`, `toolVersion = "0.8.12"`, explicit report locations, `jacocoAggregateReport`, and the measurement-in-a-comment stanza carrying both rows and the rejected alternative.
- `.github/workflows/ci-cd.yaml` — one new step in job `test`, three in `integration-tests`, the extended skip notice, and the coverage CSVs added to the integration artifact.
- `.planning/codebase/STACK.md`, `.planning/codebase/INTEGRATIONS.md`, `k8s/LOCAL.md`, `infra/dependency-horizons.yaml` — line citations re-pointed (see Deviation 1).

## The Measurements

### Local, 2026-08-29, JaCoCo 0.8.12 / Gradle 8.10.2 / JDK 21, 16 cores

| counter | `test` only | `test` + `integrationTest` | delta |
|---|---:|---:|---:|
| INSTRUCTION | 62.55% (30437/48657) | **88.06%** (42847/48657) | +25.51 |
| BRANCH | 51.03% (1539/3016) | **71.88%** (2168/3016) | +20.85 |
| LINE | 62.12% (6826/10989) | **87.55%** (9621/10989) | +25.43 |
| METHOD | 65.01% (2023/3112) | **87.53%** (2724/3112) | +22.52 |

- `:core-java:test :core-java:jacocoTestReport` — rc=0, 1m18s.
- `:core-java:integrationTest :core-java:jacocoAggregateReport` — rc=0, **22m25s**, `607 tests, 0 failures, 1 skipped, 132 classes`, `availableProcessors=16, maxParallelForks=4, forkEvery=4`.
- Both CSVs carry **405 class rows** and **identical denominators**. That equality is measured, not assumed, and it is what licenses the same-class-set assertion inside J-4.
- 34-RESEARCH measured `88.07 / 71.95 / 87.55 / 87.53` earlier the same week. This is an independent re-run on a tree that has moved since and it **reproduces those figures to within 0.07 of a point** — the research number is confirmed, not restated.

### CI CALIBRATION — run `33219707778`, job `99010987759`, conclusion **SUCCESS**, gate rc=0

RESEARCH assumption A2 ("a threshold just below today's local number will not flake in CI", MED) is **retired by measurement**, and it was right to doubt.

| counter | local | CI | delta | floor | CI margin |
|---|---:|---:|---:|---:|---:|
| INSTRUCTION | 88.06 | **87.50** | −0.56 | 85 | 2.50 |
| BRANCH | 71.88 | **71.78** | −0.10 | 69 | 2.78 |
| LINE | 87.55 | **87.03** | −0.52 | 85 | 2.03 |
| METHOD | 87.53 | **87.05** | −0.48 | 85 | 2.05 |

CI is lower on **all four**. Runner: `nproc=4`, so `maxParallelForks` resolved to **2** against 4 here, and the suite took **42m56s** against 22m25s.

Two facts worth keeping, both of which say *where* the drift is:

- The **unit half is identical on both machines to the hundredth** — `62.55 / 51.03 / 62.12 / 65.01`. All of the drift is in the Testcontainers half, where the fork topology differs.
- Both runs report the **same 10989-line denominator**, so `9621` vs `9564` covered lines is a real difference in what *executed*, not two different codebases. That is an independent confirmation of J-4's same-class-set assertion across machines.

Floors recalibrated on the CI figure with the same rule, `floor(measurement) − 2`. Only INSTRUCTION moved (86 → 85); the other three were already at the CI-derived value.

The run also proves the hand-off end to end: job `test` step 8 (`Upload the unit suite's JaCoCo execution data`) SUCCESS, and this job's steps 8–10 (download → generate both reports → gate) all SUCCESS.

## Every Arm, Both Directions

Bracketed **clean → arms → clean**, every tree-mutating arm run *after* the commit it targets, restored by pathspec, verified by `git hash-object`.

### The gate (pre-arm hash `82c8928e3dd73e140bbde92cddbc5594f0909e85`)

| arm | how it was broken | result |
|---|---|---|
| clean (opening) | — | four ratios + `PASS` **rc=0** |
| VOID: aggregate CSV absent | `JACOCO_AGGREGATE_CSV=/nonexistent/path/agg.csv` | `VOID: aggregate CSV not found … An absent report is not 0% coverage.` **rc=2** |
| VOID: empty CSV | a 0-byte file | `VOID: aggregate CSV is EMPTY (0 bytes) … not that nothing is covered.` **rc=2** |
| VOID: header-only CSV | just the 13-column header | `VOID: … is HEADER-ONLY (1 line) — it describes zero classes.` **rc=2** |
| VOID: wrong header | `this,is,junk` + a data row | `VOID: unparseable aggregate CSV … its header is 'this,is,junk' … Refusing to sum columns whose meaning is unknown.` **rc=2** |
| VOID: no execution data | `JACOCO_EXEC_DIR` at a directory with no `.exec` | `VOID: execution data missing for the 'test' suite … A missing suite is not 0% coverage.` **rc=2** |
| VOID: unknown argument | `--no-such-flag` | `VOID: unknown argument: --no-such-flag (try --help)` **rc=2** |
| **VOID: a genuinely unit-only "aggregate"** | see below | **rc=2** |
| FAIL: LINE floor raised | `MIN_LINE_PERCENT` → 99.9 | `FAIL: aggregate LINE coverage 87.55% is BELOW the floor of 99.9%`, `1 counter(s) below floor` **rc=1** |
| FAIL: all four raised | all four → 99.9 | four `FAIL:` lines with the real numbers, `4 counter(s) below floor` **rc=1** |
| clean (closing) | — | **rc=0**, `git status --short` empty, hash `82c8928e…` identical |

**THE ARM THAT JUSTIFIES THE DESIGN.** The last VOID is not a corrupted file. `integrationTest.exec` was moved aside, `jacocoAggregateReport` was **regenerated**, and both `.exec` files were then put back — so J-1 passes, the CSV is a real, well-formed, freshly-generated JaCoCo report, and every structural check is satisfied. It reads:

```
INSTRUCTION 62.55% LINE 62.12% (covered lines 6826/10989)
```

A naive `60% line` floor — the shape RESEARCH warned about — **would have PASSED this**, publishing a figure wrong by a quarter of the codebase. J-4 refuses it instead:

```
  counter       unit-only     AGGREGATE       floor
  INSTRUCTION     62.55%       62.55%        86%
  LINE            62.12%       62.12%        85%
VOID: the 'aggregate' report does NOT exceed the unit-only report (aggregate covered
30437/1539/6826/2023 vs unit 30437/1539/6826/2023) — it was produced from the unit
execution data alone, or it is stale. … the two differ by roughly 25 points.
```

Note it VOIDs rather than FAILs. "Could not measure" is not "coverage regressed", and classifying it as the latter would send someone hunting for tests nobody deleted.

Restored by regenerating the true report: `lines covered: aggregate 9621/10989` again, rc=0.

### The workflow wiring (pre-arm hash `0234f9eda097aa4a86b1e81c16f3d5fbbdb5c2f1`)

| arm | result |
|---|---|
| clean (opening) | `gates: 39  workflows: 6  exempt: 6 declared` — `PASS` **rc=0** |
| the five gate-step lines deleted | `FAIL: 1 gate(s) are referenced by no workflow and carry no exemption: check-jacoco-coverage.sh` **rc=1** |
| clean (closing) | hash `0234f9ed…` identical, **rc=0** |

The unwiring arm is only capable of failing because the gate's filename appears **nowhere in a comment** — `rg -uu -n 'check-jacoco-coverage' .github/workflows/ci-cd.yaml` returns exactly two lines, both `run:` content (`381`, `382`). That is 34-07's measured trap avoided by construction.

**Not exempted, wired:** `rg -uu -c 'check-jacoco-coverage' scripts/gates/gate-enforcement.conf` → no output, rc=1. Positive control on the same query: `rg -uu -c 'check-runtime-freshness'` → `1`, rc=0, so the zero is about the conf and not about the search.

### The four guard expressions

Not eyeballed — collapsed programmatically over the suite step and the three new ones:

```
      4         if: github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true'
```

One uniq group, count 4. Byte-identical.

### The workflow parses

`actionlint .github/workflows/ci-cd.yaml` → **rc=0**, no output. Fail direction executed on a copy with one bogus step key: **rc=1**, `unexpected key "bogus-key-here" for step to run shell command`. (actionlint is *not* wired into this repo's CI; it was used here as a local instrument.)

### Diff containment

Hunk headers: `@@ -70,0 +71,23 @@`, `@@ -261,0 +285,8 @@`, `@@ -286,0 +318,66 @@`, `@@ -292 +389,4 @@`.
Post-edit job boundaries: `test` 17–244, `integration-tests` 245–447, `frontend-e2e` 448–, `ops-contracts` 845–. Every hunk falls in 17–447. **No hunk in `frontend-e2e` or `ops-contracts`** — 34-07 and 34-08 own those.

### The skip notice says it

`rg -uu -c 'coverage' .github/workflows/ci-cd.yaml`: **21 → 35** lines (base `5c7ef757` vs now). The new sentence, executed rather than read:

```
JAVA COVERAGE WAS NOT EVALUATED ON THIS RUN.
  The Java coverage floor is enforced over the AGGREGATE of the unit suite and this
  integration suite, because the unit suite alone covers 62.12% of lines where the two
  together cover 87.55% (measured 2026-08-29). This job skipped its half, so no
  aggregate could be computed and none was. THIS JOB'S SUCCESS SAYS NOTHING ABOUT
  COVERAGE — do not read it as a coverage result, and do not quote a number from it.
  Could not measure is not measured and fine.
```

### PATH ARM — build-local, never the stale directory

```
ls -ld core-java/build-local/reports/jacoco
  drwxrwxr-x 4 sanmi sanmi 4096 Aug 29 00:03 core-java/build-local/reports/jacoco

ls -ld core-java/build
  ls: cannot access 'core-java/build': No such file or directory
ls -ld core-java/build/reports/jacoco
  ls: cannot access 'core-java/build/reports/jacoco': No such file or directory
```

The stale directory does not exist in this worktree at all, so the recorded stale-artifact trap could not have been sprung here — but every path in the build file and the gate is `build-local` regardless, because it *does* exist in the main checkout.

### The build executed, it was not skipped

`rg -uu --count-matches 'UP-TO-DATE|FROM-CACHE|NO-SOURCE'` on the unit run's log: **no output, rc=1**.
**Positive control** on the same matcher against a synthetic three-line file: **3, rc=0**. So the zero is evidence about the build, not about the pattern.

The aggregate run's log has 6 such markers, and **all six are compile tasks** — `compileJava`, `processResources`, `classes`, `compileTestJava`, `processTestResources`, `testClasses`. No Test task and no report task is among them. `6 actionable tasks: 2 executed, 4 up-to-date`.

### No dependency was added

`rg -uu -n 'implementation' core-java/build.gradle.kts | rg -uu -i -c 'jacoco'` → rc=1, no match.
`./gradlew :core-java:dependencies --configuration jacocoAnt` resolves `org.jacoco:org.jacoco.ant:0.8.12` and its three siblings — the pinned version is the one that actually executed, not just the one declared.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] Twenty `build.gradle.kts` line citations invalidated by the plugins insertion**

- **Found during:** Task 3 (CI calibration run 33219707778)
- **Issue:** adding `jacoco` plus its three explanatory comment lines cost four lines at the top of `core-java/build.gradle.kts`, silently shifting every `file:line` citation below it. `STACK.md:100` claimed the Stripe SDK sat at `:141`, where the file now says `implementation("org.springframework.boot:spring-boot-starter-webflux")`. `check-doc-citations.sh` caught all 20 — 15 in `STACK.md`, 5 in `INTEGRATIONS.md`.
- **Control arm, not assumption:** the `Operational Contracts` job is **SUCCESS** on the plan's base commit `5c7ef757` (run 33217331040, job 99003777236) and **FAILURE** on mine, and the failing step is the citation gate. The breakage is unambiguously this plan's.
- **Fix:** every citation at old line ≥ 5 moved by exactly +4; the two citing `:2` (the Spring Boot plugin line, above the insertion) correctly untouched. `k8s/LOCAL.md:1963` fixed too even though the gate classes it UNCHECKABLE — a citation the gate cannot verify is still one a human follows. `infra/dependency-horizons.yaml`'s `java-toolchain` site moved `:9 → :13`.
- **Verification:** gate goes `verified=53 violations=20 rc=1` → `verified=73 violations=0 rc=0`. All 20 became **verified**, not merely absent. `check-dependency-horizons.sh`'s advisory `pin-not-at-site` counter falls **18 → 17**, exactly the one row I caused; the other 17 predate this plan and were left alone.
- **Closed in CI, not just locally:** run `33222182624` on the fixed tree reports `Operational Contracts :: completed :: success`. The control arm therefore runs in both directions on the real instrument — SUCCESS at base `5c7ef757`, FAILURE at `0cfb6483`, SUCCESS again at `b426b858`.
- **Committed in:** `33439a7f`

**2. [Rule 1 — Bug] The skip notice had an unterminated quote and would have failed the job**

- **Found during:** Task 3, while extracting the step's text to quote in this summary
- **Issue:** the apostrophe in `THIS JOB'S SUCCESS` was written with the single-quote escape dance **inside a string that is already double-quoted**. Tokenising the tail: the first `"` closes the string, `'` opens a single-quoted one, `"` is literal, `'` closes it, and the `"` at end of line then opens a string that never closes.
- **Why nothing would have caught it:** `actionlint` is rc=0 on **both** versions (it defers run blocks to shellcheck, which is not installed here) and actionlint does not run in this repo's CI at all. The step only executes on a **path-filtered pull_request** — precisely the path a push to a phase branch never takes; in run 33219707778 it reports `skipped`. The first person to see it would have been whoever opened a docs-only PR.
- **Fix:** a plain apostrophe, which needs no escaping inside double quotes.
- **Verification:** the step's `run` block extracted and **executed**. Before: `skipnotice.sh: line 12: unexpected EOF while looking for matching '"'`, **rc=2**. After: the full twelve-line notice renders through `Could not measure is not measured and fine.`, **rc=0**.
- **Committed in:** `9fde76f7`

### Design deviations from the plan text

**3. [Rule 2 — Missing critical] CI generates BOTH reports, not just the aggregate**

The plan's Task 3 says `run ./gradlew :core-java:jacocoAggregateReport`. The gate's J-4 — which the same plan mandates ("asserting the aggregate CSV's totals exceed the unit-only CSV's") — needs the unit CSV present in the same job, so the step runs `:core-java:jacocoTestReport :core-java:jacocoAggregateReport`. Cost measured before adopting: `--dry-run` shows `jacocoTestReport`'s graph is `compileJava → processResources → classes → jacocoTestReport` with the `test` task **absent entirely**, and deleting the CSV and running the report alone regenerated it at 62.12% with no test task in the log. Zero tests re-executed.

**4. [Rule 2 — Missing critical] `executionData` names the two `.exec` files instead of globbing**

The plan specified `include("jacoco/*.exec")`. Every `Test` task gets a `JacocoTaskExtension`, so `generateOpenApiSpec` and `updateOpenApiSnapshot` also drop `.exec` files in that directory; a glob would make the gated number depend on which unrelated command a developer happened to run first, against a floor calibrated on exactly two suites. Named explicitly, with the trade-off written in the file: a future third suite is excluded until added here, which under-reports and turns the gate red — the safe direction to fail.

**5. [Rule 3 — Blocking] The download step is `continue-on-error`**

The two jobs run in parallel with no `needs:` between them. `needs: [test]` would serialize a ~12–18 minute job in front of a ~43 minute one and add a new way for a required check not to report, which the job's own comment says it exists to avoid. The download happens *after* the integration suite, so the ordering holds by a wide margin — and if it ever inverts, the correct outcome is the gate's own VOID naming the missing suite rather than an opaque "unable to find any artifacts". **Under no ordering does a missing half produce a PASS**, which is the invariant that matters.

**6. Report output locations set explicitly**

Rather than relying on the plugin's naming convention (`reports/jacoco/<testTaskName>/<reportTaskName>.csv`), all six destinations are declared. The gate's input path is then a fact in version control instead of an inference about Gradle internals.

**7. The aggregate and unit CSVs added to the `integration-test-results` artifact**

So the numbers behind any given run survive the log's retention.

---

**Total deviations:** 2 auto-fixed bugs (both Rule 1), 3 design strengthenings (2× Rule 2, 1× Rule 3), 2 minor additions.
**Impact on plan:** no scope creep. Deviations 3–5 are all required for the plan's own stated design to function; 1 and 2 are defects this plan introduced and had to repair.

## Issues Encountered

**`scripts/docs-freshness.sh` is rc=1 on this tree — PRE-EXISTING, and deliberately not fixed here.**

`.planning/phases/34-rendering-test-truthfulness/deferred-items.md:110` says in terms: *"Do not fix it here or in 34-08/34-09: single-writer is the point."* Plan **34-10** owns `docs/metrics.json`.

Isolated with a control rather than asserted: the drift is entirely in `jest_blocks` 1230→1272, `jest_files` 120→124, `playwright_blocks` 113→120, `playwright_specs` 22→25 — all from waves 1–3. Java counts are **unchanged** at 1716/271. And this plan touched no counted test file at all:

```
git diff --name-only 5c7ef757..HEAD -- '*.test.ts' '*.test.tsx' '*.spec.ts' \
    'core-java/src/test/**' 'edge-go/**/*_test.go' 'mcp-server/**'
  (no output)

# positive control on the same pathspec form
git diff --name-only 5c7ef757..HEAD -- '.github/workflows/ci-cd.yaml'
  .github/workflows/ci-cd.yaml
```

**The calibration branch `phase-34-09-jacoco-calibration` exists on the remote.** It was pushed solely to obtain the CI measurement RESEARCH A2 asks for (the workflow's push trigger is `[main, 'phase-*', 'phase/**']`, which the executor's `worktree-agent-*` branch does not match). It carries the same commits as this worktree branch and is safe to delete once the phase branch absorbs them.

## Next Phase Readiness

- Three of the four coverage tiers named in ROADMAP criterion 4 are now closed: Go (34-08), Jest (34-08), Java (this plan). Issue #110's coverage narrowing is complete.
- **34-10 must still run `scripts/docs-freshness.sh --write`** and update the prose in `CLAUDE.md` / `AGENTS.md` / `README.md`. Both docs gates are red on the branch until it does, and that is by design.
- **A warning for anyone editing the top of `core-java/build.gradle.kts`:** inserting lines there silently invalidates 20 citations in `STACK.md`, `INTEGRATIONS.md`, `k8s/LOCAL.md` and `infra/dependency-horizons.yaml`. `check-doc-citations.sh` catches it in the `Operational Contracts` job; run it locally before pushing.
- The gate's floors are CI-calibrated with margins of 2.03–2.78 points. If it goes red, the aggregate HTML report at `core-java/build-local/reports/jacoco/aggregate/html/index.html` names the class. The answer is a test, not a smaller number.

## Self-Check: PASSED

Every file and every commit hash claimed above was verified to exist on this tree.

```
ls -1 <8 claimed paths>            -> all 8 listed, rc=0, no "No such file"
git log --format=%h 5c7ef757..HEAD -> b426b858 9fde76f7 33439a7f 0cfb6483 b29c848c 41450dfc
```

Working tree clean apart from this summary at the moment of the check.

---
*Phase: 34-rendering-test-truthfulness*
*Completed: 2026-08-29*
