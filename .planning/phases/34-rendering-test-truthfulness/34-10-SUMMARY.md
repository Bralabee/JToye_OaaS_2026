---
phase: 34-rendering-test-truthfulness
plan: 10
subsystem: release-engineering
tags: [closeout, metrics, coverage, runtime-parity, falsifiability, e2e, deferred-register]

requires:
  - phase: 34-rendering-test-truthfulness
    provides: "all nine prior plans' specs and Jest suites, which are what the regenerated manifest counts and what invalidated the skip-budget gate's spec digest"
  - phase: 34-rendering-test-truthfulness
    provides: "34-06's MAX_SKIPS 6 and its deleted onboarding ALLOW, whose S-3 falsification was deferred to this plan"
  - phase: 34-rendering-test-truthfulness
    provides: "34-07's ssr-routes.conf, which this plan's deferred register POINTS AT rather than duplicating"
  - phase: 34-rendering-test-truthfulness
    provides: "34-08's check-go-coverage.sh and 34-09's check-jacoco-coverage.sh, the two gates whose artefacts this plan generated and ran"
provides:
  - "docs/metrics.json regenerated ONCE on the assembled tree, with README.md / AGENTS.md / CLAUDE.md reconciled to it"
  - "the phase's deferred / N-A register — eight entries, each with a measurement and a removal condition"
  - "a delivered runtime proven to match the branch by identity AND content"
  - "check-e2e-skip-budget re-earned at 6/6 on a digest-matching report, with its S-3 stale-ALLOW arm executed"
  - "D-34-10-08: a measured fresh-volume provisioning defect in infra/db/init/00-create-db.sql that no previous run could have surfaced"
affects: [any future phase that adds tests, the next handoff, whoever fixes D-34-10-08]

tech-stack:
  added: []
  patterns:
    - "regenerate a shared manifest from ONE writer at the end, never per-worktree — and never arithmetically"
    - "a gate that VOIDs in a worktree is not a failing gate; establish where it is meant to run before reading its verdict"
    - "when an environmental condition would breach a budget, fix the ENVIRONMENT — re-adding an exemption recreates the lie the exemption was deleted for"

key-files:
  created:
    - .planning/phases/34-rendering-test-truthfulness/34-10-SUMMARY.md
  modified:
    - docs/metrics.json
    - README.md
    - AGENTS.md
    - CLAUDE.md
    - HANDOFF.md
    - .planning/phases/34-rendering-test-truthfulness/deferred-items.md

decisions:
  - "The stack was RESEEDED rather than the budget relaxed. The demo tenant's onboarding was LIVE, tripping onboarding-blocked-flow.spec.ts:173's terminal guard and putting the suite at 7 skips for a purely environmental reason. Re-adding an ALLOW would have recreated exactly the false exemption 34-06 deleted after measuring its stated cause to be untrue."
  - "HANDOFF.md's stale gate count was fixed HERE rather than deferred: it was stale because this phase added three gates, which is the same obligation as docs/metrics.json and belongs to the closeout."
  - "D-34-10-08 was unblocked WITHOUT editing any committed file. The defect is real and is recorded with its measurement, but infra/db/init/00-create-db.sql is outside this plan's files_modified and the fix needs its own falsification arm."
  - "check-alert-metrics' predicted red-then-green is recorded as a prediction that DID NOT FIRE, not as a remedy applied. It was green first time because the E2E suite had just placed real orders."

metrics:
  duration: ~2h15m
  tasks_completed: 3
  commits: 3
  completed: 2026-08-29
---

# Phase 34 Plan 10: Phase Closeout Summary

Reconciled the count manifest and every prose site to the assembled tree, recorded eight
deferred/N-A items with removal conditions, rebuilt the runtime and proved it matches the
branch by content and identity, and re-earned the skip-budget gate at 6/6 on a fresh
297-test run — finding, on the way, a provisioning defect that only a `down -v` could expose.

## What shipped

Three end-of-phase obligations that no individual plan could discharge, because each needs
the whole tree assembled:

1. **The count manifest** — `docs/metrics.json` regenerated once, by its single declared
   writer, with the prose in three documents reconciled to it and both runner oracles
   agreeing.
2. **The deferred register** — everything the phase deliberately did not do, with the
   measurement or quoted source behind each, and what would remove it.
3. **Runtime parity + the skip budget** — a rebuilt, content-verified runtime and a
   full-suite run that re-earns the gate every spec edit in this phase had invalidated.

## Task 1 — the count manifest and every prose site

**Both halves of the loop were shown to FAIL before being trusted.**

`scripts/docs-freshness.sh` (tree → manifest) exited **1** naming the drift, then **0**.

`scripts/check-doc-metrics.sh` (prose → manifest) is the interesting one: it was **green
before the regeneration**, because prose and manifest were *equally stale*. Regenerating
made it go red with **16 FAIL lines** across README.md (6 — two separate
`total_logical_invocations` sites), CLAUDE.md (5) and AGENTS.md (5); after the edits it is
green at **37/37 claims across 3 docs**. That ordering is the only way the gate's fail
direction is observable at all here.

### Manifest delta, and which plan produced each part

| metric | before | after | source of the change |
|---|---:|---:|---|
| `jest_blocks` | 1230 | **1272** | 34-01/34-03 session-store + callback suites, 34-05 theme/scope suites |
| `jest_files` | 120 | **124** | +4 suites |
| `playwright_blocks` | 113 | **120** | 34-05's eleven-route sweep, 34-01/34-07 ssr-coverage specs |
| `playwright_specs` | 22 | **25** | +3 specs |
| `total_logical_invocations` | 3188 | **3237** | +49 |

Java (1716/271), Go (81/11) and MCP (48/8) are **unchanged** — no plan in this phase added
a test in those languages, and the manifest says so rather than being nudged toward a
rounder number.

**No count was computed arithmetically.** 34-05 added a `for`-loop-driven block that is ONE
declaration and eleven executions; hand-adding eleven would have been wrong in a way that
looks right. (Worth recording for the next reader: the plan describes the counter as
grepping literal `it(` / `test(`, but the tree's `docs-freshness.sh` delegates to
`scripts/count-test-blocks.mjs`, which masks comments/strings, rejects member access and
expands `.each` tables. The hazard the plan warns about is real; the mechanism has since
been made smarter.)

### The runners, not just the static counter

| oracle | runner | manifest | rc |
|---|---|---|---:|
| `check-test-count-oracle.sh playwright` | 120 blocks / 25 specs | 120 / 25 | 0 |
| `check-test-count-oracle.sh jest` | 1272 blocks / 124 files | 1272 / 124 | 0 |

The Jest run itself: **124 suites passed, 1272 tests passed, 0 failed.**

`scripts/check-claims.sh` (the wider prose gate) is green at **47/47 across 6 docs**.
`.planning/codebase/` quotes no counts — verified with `rg -uu` **plus a positive control**
(96 matches for a token that must be there), because an empty search is evidence about the
search, not about the tree.

### Falsification (bracketed clean → arm → clean)

Breaking `jest_blocks` to 9999 in the committed manifest:

```
clean:   jest-oracle 0, docs-freshness 0, check-doc-metrics 0
armed:   jest-oracle 1  ("runner says 1272, docs/metrics.json says 9999")
         docs-freshness 1
         check-doc-metrics 1  (README/CLAUDE/AGENTS jest_blocks all named)
restore: hash a9fccdef… == clean hash  (BY CONTENT, not `diff --stat`)
closing: 0 / 0 / 0
```

## Task 2 — the deferred / N-A register

Seven entries **appended**; the three already in `deferred-items.md` (34-05's surviving
overflow shape, 34-07's gate-enforcement finding, the metrics-ownership note) are untouched.
The file went 137 → 402 lines at this task, and 517 after Task 3's record. Claims were
**re-measured, not copied**:

- **D-34-10-01 `middleware.ts` → `proxy.ts`, OUT OF SCOPE.** next **16.3.3** emits
  `_log.warnOnce(...)` at `dist/build/index.js:730` — a warning, not an error.
  `middleware.ts:19` is `export default auth((req) => {` and mints the CSP `x-nonce`.
  `next-auth@5.0.0-beta.32` has **zero** proxy references (`rg -uu -c` → 0, positive
  control `next-auth` → 2; whole-package scan → 0, positive control `middleware` → 5 files).

  **A finding the plan did not anticipate, and the strongest of the four reasons:** Next's
  own upgrade guide, shipped inside the package at `version-16.md:616`, states the `edge`
  runtime is **NOT** supported in `proxy` and that the proxy runtime is `nodejs`,
  unconfigurable. `middleware.ts` declares no `runtime`, so it runs on **edge** today. The
  migration is a runtime change to the file gating every page's CSP, not a rename.
  Assumption **A4** (codemod not run) is named as an assumption.

- **D-34-10-02 mcp-server coverage, N/A.** 0 coverage references in
  `mcp-server/package.json` (positive control `vitest` → 3); `@vitest/coverage-v8` absent.
  Outside ROADMAP criterion 4, which names exactly three tiers. Disqualifying on its own:
  the package could not be legitimacy-checked — slopcheck is blocked by
  `block-base-python.py` with no bypass (assumption **A5**).
- **D-34-10-03 SSR fixture server, DEFERRED.** Assumption **A6** named: the env-var seam is
  verified, the server was never built or run.
- **D-34-10-04 zero #507 conversions — a FINDING, not an omission.** `ssr-routes.conf`
  classifies all **38** routes: **4 SSR, 13 STATIC, 21 CLIENT** (independently confirmed by
  `check-ssr-coverage-contract.sh`). The three highest-impact public routes are already
  server components. All five remaining public client routes re-verified `"use client"` on
  line 1, with the browser-only dependency confirmed in the file
  (`cart-provider.tsx:77` `window.localStorage.getItem`; `track/page.tsx:109`
  `sessionStorage.getItem("jtoye-track-email")`). Per-route reasons are **pointed at, not
  duplicated** — a copy here would drift; one in the conf fails the build.
- **D-34-10-05 the six skips are #304's (4) and #61's (2)**, measured from a report's own
  annotations. Records what 34-06 *did* do, so the entry is not misread as inaction.
- **D-34-10-06 #453 is an unadjudicated PRODUCT decision** — the platform has no
  cross-tenant operator identity by design, so the adjudicator role does not exist to be
  assigned. Writing code first would force the answer by implementation.
- **D-34-10-07 #286 and #110 NARROWED**, with exactly what remains of each. It also
  reconciles a wording difference rather than leaving two documents apparently disagreeing:
  34-09-SUMMARY says "three of the four coverage tiers" while ROADMAP criterion 4
  (`.planning/ROADMAP.md:901-903`) names **three** — the fourth is mcp-server, recorded as
  N/A in D-34-10-02.

## Task 3 — runtime parity, the full suite, the skip budget

### Identity

| service | image id | `.Metadata.LastTagTime` |
|---|---|---|
| frontend | `e42abfb8…` → `25caad77…` | 08-28 16:38:52 → 08-29 00:22:30 UTC |
| core-java | `7ae227f7…` → `b7ddf2c5…` | 08-28 16:40:21 → 08-29 00:22:30 UTC |

Both running containers hold the new ids. **`.Created` is demonstrably useless here** and
this was observed rather than asserted: across the second build it stayed at
`2026-08-29T01:16:50+01:00` while `LastTagTime` advanced — precisely why the contract names
`LastTagTime`.

### Content, read out of the RUNNING artefacts

- **`/shop` served bytes: 54,263**, 1 `<h1>`, 1 `application/ld+json`, **28** `nonce=`
  attributes. `ssr-routes.conf` records **54,184 / 5 occurrences** live versus **39,438 / 0**
  with no backend, so the byte count distinguishes "the server rendered the shops" from
  "the server rendered a shell" — which a 200 cannot. Negative control (a bogus token) → **0**.
- **`BUILD_ID` from the running container** = `BkRQArypQ5LEbAO7M7fTf` = the freshly built
  image's.
- **core-java, from INSIDE `/app/app.jar`:** `out-of-order: true` in
  `BOOT-INF/classes/application.yml`; 956 files. **jacoco entries in the shipped jar: 0** —
  34-09's plugin stayed test-time and did not contaminate the runtime artefact.
- **Honest scope note:** `git diff --name-only origin/main..HEAD -- core-java/` returns
  exactly one file, `build.gradle.kts`. core-java's *runtime* content is expected to be
  unchanged; the rebuild was still required because the gate measures build **inputs**.

`check-runtime-freshness.sh`: **FAIL** ("2 of 4 running built service(s) do not match the
source tree (0 unverified)", naming core-java and frontend) → **PASS** (4 FRESH, 0
unverified). Run from the MAIN checkout, because the compose project name comes from the
directory.

`docker ps` for core-java, quoted: `0.0.0.0:9090->9090/tcp, [::]:9090->9090/tcp` — the
published **range** 9090-9091 resolved to 9090, so no measurement below is against a port
the frontend never calls.

### The stack had to be reseeded, and that is the finding

The demo tenant `00000000-…-0001` (Mama Ade's Kitchen) had `vendor_onboarding.status =
LIVE`. `onboarding-blocked-flow.spec.ts:173` skips the desktop arm when the target tenant
is LIVE/terminal, which would have put the suite at **7** skips against a ceiling of 6 —
for an environmental reason. The stack was reseeded (`down -v` then up, as the nightly does
at `:439`), after which `vendor_onboarding` was empty and 5 shops / 22 products were
seeded. **Re-adding an ALLOW was the wrong answer** and would have recreated exactly the
false exemption 34-06 deleted.

### Full suite — verdict read FROM the report

```
total=297  passed=291  failed=0  skipped=6
```

Skips attributed from the report's own structure, not assumed from the conf:

| spec | skips | owner |
|---|---:|---|
| `stomp-relay.spec.ts` (2 tests × 2 projects) | 4 | #304 |
| `vendor-refund-flow.spec.ts` (1 test × 2 projects) | 2 | #61 |
| **total** | **6** | |

`onboarding-blocked-flow.spec.ts` is **not** among them — 34-06's removal of the false
exemption vindicated on a real run.

`check-e2e-skip-budget.sh`: **VOID (rc=2, no report)** → **PASS**, with
`specDigest f13669e3d06374c2… matches the tree (content, not mtime)`, 297 tests, 6 skipped,
budget 6, and its own S-4 self-test firing on a known title and declining a constructed
absent one.

**S-3 ARM** (the falsification 34-06 deferred here), against the SAME report:

```
clean:   rc=0
armed:   rc=1  "FAIL: S-3 stale ALLOW 'onboarding-blocked-flow.spec.ts' matches no
                skipped test — the exemption outlived its cause"
restore: 8decb1e1… == clean hash (BY CONTENT)
closing: rc=0
```

### Gate sweep — 40 gates, all rc=0

39 `check-*.sh` + `docs-freshness.sh`. Non-trivial verdicts:

| gate | verdict |
|---|---|
| `check-runtime-freshness` / `check-infra-exposure` / `check-container-config-drift` | **VOID from a worktree, PASS from the main checkout.** The first takes its compose project name from the directory; the other two parse `docker compose config` and need `.env` to interpolate. Environmental, not defects — and each was re-run in the right place rather than waved through. |
| `check-go-coverage` | VOID (absent profile — "an absent profile is not 0% coverage") → **PASS 66.8% ≥ 65.0%**, 311 blocks |
| `check-jacoco-coverage` | VOID **twice** (missing `.exec`, then missing unit-only CSV) → **PASS**: INSTRUCTION 88.07, BRANCH 71.95, LINE 87.55, METHOD 87.53 vs floors 85/69/85/85; J-4 confirms the aggregate exceeds the unit-only report. The 62.12 → 87.55 line gap matches 34-09's calibration exactly. |
| `check-alert-metrics` | **Predicted red-then-green; it was green first time and the remedy was never run.** The E2E suite had just placed real orders, so the counter was already non-zero. Recorded as a prediction that did not fire. |
| `check-branch-behind-base` | PASS — 63 ahead, 0 behind `origin/main` |

`./gradlew :core-java:jacocoTestReport` completed in **6s** after the suites had run,
confirming 34-09's finding that the report regenerates from existing `.exec` at zero test cost.

## Deviations from Plan

**1. [Rule 1 — Bug] HANDOFF.md's gate count was stale and the gate was red**

- **Found during:** Task 3's gate sweep — the only genuine `FAIL` in it.
- **Issue:** `check-handoff-contract.sh` H-1: *"HANDOFF.md's resume block says 'EXPECT 37 x
  rc=0' but the repo has 40 gate script(s)"*. Stale because **this phase added three gates**
  (34-07 ssr-coverage, 34-08 go-coverage, 34-09 jacoco). 37 + 3 = 40.
- **Fix:** updated the claim to 40 and replaced the dated note beneath it, which described a
  2026-08-24 run and would otherwise have kept asserting a skip-budget VOID this plan just
  re-earned. The new note also records which three gates must be run from the main checkout.
- **Falsified:** the claim was then broken to 41 and the gate went rc=1 naming it; restored
  and re-verified by `git hash-object`; closing arm rc=0. So the 40 is enforced, not merely typed.
- **Why in scope:** identical in kind to `docs/metrics.json` — a prose count made stale by
  this phase's own additions, which is what the closeout plan exists to reconcile.
- **Files:** `HANDOFF.md` · **Commit:** `4dd4503e`

**2. [Rule 3 — Blocking] A fresh volume cannot provision itself (D-34-10-08)**

- **Found during:** Task 3, reseeding the stack. core-java crash-looped
  `Restarting (1)` on `FATAL: password authentication failed for user "jtoye_app"` (28P01),
  **zero migrations applied**.
- **Root cause, measured:** `infra/db/init/00-create-db.sql:44` creates `jtoye_app` with
  `DB_PASSWORD`, but since the SEC-04/#552 split `DB_MIGRATION_USER=jtoye_app` and Flyway
  authenticates with `DB_MIGRATION_PASSWORD` — the same file says so at `:56`, and its own
  comment at `:42-43` is stale (`DB_USER` is now `jtoye_runtime`). Compared **by digest
  only**, never by value: `04897cf11fda…` vs `0bdf45585f5a…` — different.
- **Why nobody had seen it:** the long-lived local volume predates the split, so only
  destroying it exposes the defect. Same class as the V64 finding already in CLAUDE.md —
  *"a provisioning step only a human can perform is not provisioning."*
- **Fix applied:** environment only — `ALTER ROLE jtoye_app PASSWORD` to the migration
  credential, preserving the split's intent. Verified **by function** (authenticating as
  `jtoye_app`), not by the ALTER's exit code. core-java then reached `healthy` and applied
  V1..V64.
- **NOT fixed in code, deliberately:** `infra/db/init/00-create-db.sql` is outside this
  plan's `files_modified`, and the real fix needs an arm nobody has run — a `down -v` cycle
  where the two credentials deliberately differ. Recorded as **D-34-10-08** with that
  removal condition.
- **Files:** none committed · **Commit:** `4dd4503e` (the register entry)

**3. [Environmental] Three gates VOID from a worktree**

Not a defect and not fixed: `check-runtime-freshness`, `check-infra-exposure` and
`check-container-config-drift` VOID in a worktree and PASS from the main checkout. Each was
re-run there rather than reported as a pass or as a failure. Now documented in HANDOFF.md so
the next reader does not re-diagnose it.

## Known Stubs

None. This plan created no code.

## Threat Flags

None. No new network endpoint, auth path, file-access pattern or schema change.

The phase-wide supply-chain assertion holds:
`git diff --name-only origin/main -- frontend/package.json frontend/package-lock.json
edge-go/go.mod edge-go/go.sum mcp-server/package.json` prints **nothing** — with a positive
control on the same pathspec form (`-- frontend/` returns 20+ files) proving the diff
direction works. `npm ci` was run in the worktree to obtain the runners; `ci` reproduces the
committed lockfile and cannot add a package or modify `package.json`.

`E2E_VENDOR_PASSWORD` was sourced from `.env` as the nightly does and never echoed; DB
credentials were compared by SHA-256 digest only. No artefact or secret is staged:
`e2e-artifacts/` and `edge-go/coverage.out` are ignored by explicit `.gitignore` rules
(`:185`, `:211`), confirmed with `git check-ignore -v`.

## Commits

| # | Hash | Subject |
|---|---|---|
| 1 | `8ea8c124` | docs(34-10): reconcile the count manifest and every prose site to the tree |
| 2 | `52b12f7c` | docs(34-10): record what this phase deliberately did not do, with evidence |
| 3 | `4dd4503e` | docs(34-10): record runtime parity, re-earn the skip budget, reconcile HANDOFF |

## Next Phase Readiness

- **D-34-10-08 is the one thing that should not wait.** A fresh deployment where
  `DB_PASSWORD != DB_MIGRATION_PASSWORD` cannot start. It is currently masked everywhere a
  volume predates the SEC-04 split or the two credentials happen to be equal.
- **The skip budget is re-earned but perishable.** Every spec edit changes the report's
  `specDigest` and VOIDs the gate again. The next phase that touches a spec owns a fresh run.
- **The JaCoCo floors have 2.03–2.78 points of headroom** and CI measures 0.10–0.56 points
  below this machine. If the gate reds, the answer is a test, not a smaller number.
- **`.planning/STATE.md` and `.planning/ROADMAP.md` were deliberately NOT touched** — the
  orchestrator owns those writes.

## Self-Check: PASSED

Every file and commit claimed above was verified to exist on this tree:

- `docs/metrics.json` contains `playwright_blocks` and `total_logical_invocations` (1 each).
- `.planning/phases/34-rendering-test-truthfulness/deferred-items.md` — 517 lines
  (plan asks ≥ 40), 9 `REMOVE WHEN` markers, 6 `ssr-routes.conf` references (plan asks ≥ 1),
  8 `D-34-10-0*` entries (the seven from Task 2 plus D-34-10-08 from Task 3), and all three
  pre-existing entries intact. Two figures in an earlier draft of this section were stale —
  5 references and "7 plus one" — and were corrected against the executed check rather than
  left to read plausibly.
- Commits `8ea8c124`, `52b12f7c`, `4dd4503e` all present in `git log`.
- Commit messages were passed via `-F` files and read back with `git log -1 --format=%B`;
  no backtick corruption (the messages deliberately contain none).
