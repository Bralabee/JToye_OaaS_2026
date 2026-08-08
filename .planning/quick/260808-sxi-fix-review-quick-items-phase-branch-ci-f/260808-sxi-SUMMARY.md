---
phase: quick-260808-sxi
plan: 01
subsystem: ci-and-test-truthfulness
tags: [ci, github-actions, playwright, e2e, roadmap, non-vacuity]
requires: []
provides:
  - "push-triggered CI on slash-style phase branches (phase/**)"
  - "live dish-scroller arrow assertions with structural non-vacuity guards"
  - "ROADMAP Phase 33 row and domain claim matching measured reality"
affects: [.github/workflows/ci-cd.yaml, frontend/e2e/marketing-dish-scroller.spec.ts, .planning/ROADMAP.md]
tech-stack:
  added: []
  patterns:
    - "toHaveCount(1) before toBeHidden — a hidden-assertion is vacuous on a zero-match locator"
key-files:
  created: []
  modified:
    - .github/workflows/ci-cd.yaml
    - frontend/e2e/marketing-dish-scroller.spec.ts
    - .planning/ROADMAP.md
decisions:
  - "Kept 'phase-*' alongside 'phase/**' — hyphenated phase branches exist in history and may recur"
  - "Arrow count asserted as exactly 1 per side, not >=1 — a count of 2 means the wrapper scoping that excludes the streaming staging-buffer copy has regressed"
  - "ROADMAP A-record claim written from a fresh dig at execution time (162.255.119.30, Namecheap parking range), not from the planning-time citation alone"
metrics:
  duration: "~12 minutes"
  completed: "2026-08-08"
  tasks: 3
  commits: 3
---

# Quick 260808-sxi: Fix Review Quick Items (CI filter, vacuous locator, stale ROADMAP) Summary

**One-liner:** push CI now matches phase/** branches, the dish-scroller arrow locator interpolates
its side parameter with four toHaveCount(1) guards making vacuity structurally impossible (proven
by a live break arm failing AT the guard), and ROADMAP's two stale facts now match measurement.

## Commits

| Task | Commit | Files | What |
| ---- | ------ | ----- | ---- |
| 1 | 77d6fabe | .github/workflows/ci-cd.yaml | push branches filter: [main, 'phase-*', 'phase/**'] |
| 2 | 075c27a5 | frontend/e2e/marketing-dish-scroller.spec.ts | interpolate ${side} in arrow testid + 4 non-vacuity toHaveCount(1) guards |
| 3 | 333b9aba | .planning/ROADMAP.md | Phase 33 row 3/8 -> 5/8; jtoye.co.uk registered-but-parked (dated 2026-08-08) |

## Verification evidence (both directions, real output)

### Task 1 — CI filter

- Pass: `rg -uu -F --count-matches "'phase/**'" .github/workflows/ci-cd.yaml` -> **1** (rc=0)
- Fail (pinned baseline 6528e562): same pattern piped from `git show` -> **0** (rc=1, empty output = no match)
- YAML parse: PyYAML resolved the push branches list as `['main', 'phase-*', 'phase/**']`, exit 0
  (bare `on` parses as boolean True; the check handled both keys)
- actionlint (installed at /home/sanmi/go/bin/actionlint): rc=0, no findings

**NOT executed (unprovable locally):** the live trigger itself. It is proven the first time a push
lands on phase/33-the-consumer-product after this merges, when
`gh run list --branch phase/33-the-consumer-product` becomes non-empty. The fix branch itself
(fix/33-review-quick-items) matches neither pattern, so an absent run on it is expected and is not
a failure of the fix.

### Task 2 — arrow locator (LIVE PATH ran; stack probe curl rc=0)

Static checks first:
- (a) `dish-scroll-${side}` in the spec -> **1** (rc=0)
- (b) same pattern against baseline 6528e562 -> **0** (rc=1) — instrument distinguishes fixed from broken
- (c) `toHaveCount(1)` occurrences -> **4**
- (d) `npx playwright test marketing-dish-scroller --list` rc=0 — 3 test blocks, listed as 5
  project invocations (mobile runs 2 of the 3; @desktop-only runs on desktop only)
- dish-scroller.tsx diff vs 6528e562: **empty** (component never touched, including during the break arm)

Live arms, in order (commit 075c27a5 made BEFORE the arms so the restore target was committed state):
1. **Green arm:** 5/5 passed, rc=0 (mobile: discloses 1.0s, region 569ms; desktop: discloses 648ms,
   region 571ms, @desktop-only arrows 590ms)
2. **Break arm** (spec locator mutated to `dish-scroll-X${side}`, component untouched): rc=1,
   2 failed — BOTH at the count guard, not a vacuous pass:
   `Error: arrow locator must resolve — a zero-match locator makes toBeHidden vacuous` /
   `expect(locator).toHaveCount(expected) failed` / `getByTestId('dish-scroll-Xright')` (and Xleft)
   `Expected: 1, Received: 0`. The mobile "discloses" invocation passed under break because at
   390px the row overflows and that test's arrow guards live in the !canScroll branch — expected.
3. **Restore** via `git checkout -- <spec>`, verified BY CONTENT: (a) prints 1 again, mutation
   token `dish-scroll-X` prints 0, `git diff --numstat` empty
4. **Clean-state-last:** 5/5 passed, rc=0

### Task 3 — ROADMAP facts (both re-measured before writing)

- Measurement (a): `ls .planning/phases/33-the-consumer-product/*-SUMMARY.md | wc -l` -> **5**
  (33-00..33-04), matching the planning-time count, so the row reads **5/8**
- Measurement (b): `dig +short jtoye.co.uk A` -> **162.255.119.30** (single record, Namecheap
  parking range), so per plan "no A records" became "parking A records only — nothing serving the app"
- Pass: 5/8 row -> **1**; old 3/8 row -> **0**; `never registered` on jtoye.co.uk lines -> **0**;
  `parked` on jtoye.co.uk lines -> **1** (line 307's auth.jtoye.co.uk mention untouched)
- Fail (baseline 6528e562): `never registered` -> **1**; 3/8 row -> **1**
- Scope proof: `git diff -U0 6528e562 -- .planning/ROADMAP.md` shows **exactly 2 hunks**
  (line 349 row; lines 367-368 -> 367-369 sentence); numstat **4 insertions / 3 deletions**

## Deviations from Plan

**1. [Instrument substitution] `rg -uu --count-matches` used where the plan wrote `grep -Fc`**
- **Found during:** Task 1 first verify arm
- **Issue:** the repo's block-blind-search hook rejects count/absence checks that rely on the
  gitignore-honouring `grep`/`rg` shell functions, including on named files
- **Fix:** all count greps run as `rg -uu -F --count-matches` (or `-Fc` on piped input) — the
  hook's sanctioned form, strictly stronger (ignores .gitignore and hidden status). Zero-match
  prints nothing with rc=1; recorded as count=0 in every fail direction above
- **Files modified:** none (verification instrument only)

**2. [Rounding of an expectation, not a change] `--list` reports 5 invocations for 3 tests**
- The plan's check (d) said "lists 3 tests"; the config runs the spec under mobile and desktop
  projects, so 3 test blocks list as 5 invocations. The 3-block count is what the metric gate
  counts; recorded as matching intent.

No other deviations — all edits exactly as planned; no emoji in any edit; no Co-Authored-By;
all three message bodies written via quoted heredoc and read back intact via `git log -1 --format=%B`.

## docs/metrics.json

Untouched (diff vs 6528e562 empty). No test()/it() blocks added or removed — the four guards are
assertions inside existing test() blocks, so the counted metrics are unchanged.

## Known Stubs

None.

## Threat Flags

None beyond the plan's register: T-Q-01 (push-filter widening) accepted as planned — 'phase/**'
matches same-repo branches only, fork pushes still route through the unchanged pull_request trigger.

## Self-Check: PASSED

- .github/workflows/ci-cd.yaml — FOUND, contains 'phase/**'
- frontend/e2e/marketing-dish-scroller.spec.ts — FOUND, interpolated + 4 guards
- .planning/ROADMAP.md — FOUND, 5/8 row + parked claim
- Commits 77d6fabe, 075c27a5, 333b9aba — FOUND on fix/33-review-quick-items
