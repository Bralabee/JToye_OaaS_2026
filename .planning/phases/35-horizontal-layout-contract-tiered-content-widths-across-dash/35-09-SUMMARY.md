---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 09
subsystem: testing
tags: [playwright, core-web-vitals, cls, perf-budgets, landing, marketing, measurement, falsifiability]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 06
    provides: "the Marketing tier on the four landing bands — the surface this plan measures, and the D-35-06-b hypothesis it was asked to test"
provides:
  - "the landing route's FIRST layout-stability instrument at a viewport where a marketing max-width can bind"
  - "LANDING_CLS_DESKTOP_CONTROL 0.1316 — the measured pre-change desktop control, from a genuine three-arm A/B"
  - "LANDING_CLS_DESKTOP_RECORD 0.0362 — the improvement this phase shipped, ratcheted so it cannot rot back"
  - "a measured refutation of D-35-06-b: desktop CLS FELL 72%, it did not rise on area weighting"
  - "a live demonstration that the four pre-existing blocks pass clean over a runtime four hours behind the branch"
affects: [35-08, 35-11, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "three-arm-isolation: a third arm identical to the treatment except ONE number turns correlation into causation across a 55-file diff"
    - "scope-control-beside-the-guard: a non-vacuity guard is paired with an assertion proving its scope discriminates, so the guard cannot itself be vacuous"
    - "ratchet-the-delivered-good: assert the pre-change control AND the shipped value, so inherited slack cannot hide a regression"
    - "measure-the-instrument-not-only-the-comparison: author a real shift, rebuild, and read the number the arm reports"

key-files:
  created: []
  modified:
    - frontend/e2e/perf-budgets.ts
    - frontend/e2e/landing-webperf.spec.ts

key-decisions:
  - "The declared control is the CONTROL arm's number (0.1316), not the treatment's — it is what the route did before the phase touched it"
  - "A SECOND constant ratchets the shipped value, because the control-only bound leaves 3.6x slack and ARM B measured a real regression that passes it"
  - "CLS_BUDGET (0.1) and LANDING_CLS_KNOWN_BASELINE (0.1793) are untouched — the mobile debt stays visible"
  - "ARM B was authored in a scratch build tree, never in the repository, so no revert could eat a sibling's work"
  - "STATE.md / ROADMAP.md / REQUIREMENTS.md / docs/metrics.json deliberately untouched, per the convention 35-03 set for this phase"

patterns-established:
  - "A stale runtime is detected STRUCTURALLY (the tier attribute is absent) before any score is read — a cheaper and clearer signal than a score comparison"
  - "When a comment's claim and the measurement disagree, the comment is the defect: fix it in a separate commit that changes no constant"

requirements-completed: []
requirements-progressed: [UIX-07, UIX-09]

# Metrics
duration: 95min
completed: 2026-08-29
---

# Phase 35 Plan 09: The Desktop CLS Arm on the Landing Route Summary

**The landing route now has a layout-stability instrument at a viewport where this phase's
width change can actually bind — and the first thing it measured contradicted the
prediction it was built to test: desktop CLS did not rise on area weighting, it FELL 72%,
from 0.1316 to 0.0362, caused by the tier value alone.**

## Performance

- **Duration:** ~95 min
- **Tasks:** 3 of 3
- **Files modified:** 2 (0 created, 2 modified — both in the declared set)
- **Commits:** 3 implementation + 1 docs (this SUMMARY)
- **Branch:** `feature/35-horizontal-layout-contract`, 51 ahead / **0 behind** `origin/main`

## The problem, restated from the measurement rather than from the plan

`landing-webperf.spec.ts` had four blocks and **one** `test.use`, pinning 375px. That
`test.use` overrides the Playwright project's viewport, so the `desktop` project was
running all four blocks at 375px as well. Measured before any edit:

```
npx playwright test e2e/landing-webperf.spec.ts --project=desktop --grep '@desktop-only' --list
  rc=1   Error: No tests found.   Total: 0 tests in 0 files

CONTROL — the same query WITHOUT the tag
  rc=0   Total: 4 tests in 1 file
```

The control is the point: the emptiness was about the tag, not about a broken locator. And
`rg -uu -n 'test.use\(' e2e/landing-webperf.spec.ts` returned exactly one line — `375`.

So the phase's only real width change (1152px → 1280px, unconditional, no media query) had
**no instrument anywhere in the repository that could see it**. That is ORCH-02
(**orchestrator decision, 2026-08-29** — CONTEXT.md §4b), not a user decision.

## The measurement — every run of every arm, with its conditions

### Rig

| | |
|---|---|
| Viewport | 1440x900, no `isMobile` (the config's `desktop` project) |
| Network | CDP `Network.emulateNetworkConditions` — latency 40 ms, 1.5 Mbps down, 750 kbps up |
| CPU | `Emulation.setCPUThrottlingRate` rate 4 |
| Sampling | after the h1 is visible AND every `<img>` is `complete`, then the file's own buffered `PerformanceObserver` |
| Helpers | `throttle` and `measureVitals` **copied verbatim** from `landing-webperf.spec.ts:46,59` — they are module-private there |
| Serving | `next start` from a fresh `next build`, on the host, against the live Compose core API |
| Ordering | arms **interleaved** in one process (control, treatment, mech, control, …) so drift could not land on one arm |
| Workers | 1 (`fullyParallel: false`) |

### Arms, and how each was proven to be what it claims

| Arm | Source | Tier value | Port | Built from | Stylesheet evidence |
|---|---|---|---|---|---|
| **CONTROL** | merge base `96c8d794` | — (`max-w-6xl`) | 3001 | `git archive 96c8d794` | **no** `max-w-marketing` rule at all |
| **TREATMENT** | branch `b16d0874` | 1280 | 3011 | `git archive HEAD` | `.max-w-marketing{max-width:1280px}` |
| **MECH** | branch `b16d0874` + one number | 1152 | 3012 | `git archive HEAD`, `MARKETING_MAX_PX 1280→1152` | `.max-w-marketing{max-width:1152px}` |

`diff -rq` between the TREATMENT and MECH source trees reports **exactly one file**
(`lib/layout-widths.ts`) — the class name, the `data-width-tier` attribute, both public rails
and every other byte are identical.

### Every run

```
RESULT arm=CONTROL   run=1 cls=0.1316 lcp=804 shifts=1 heroBand=1152     (probe 1)
RESULT arm=TREATMENT run=1 cls=0.0362 lcp=804 shifts=1 heroBand=1280
RESULT arm=CONTROL   run=2 cls=0.1316 lcp=800 shifts=1 heroBand=1152
RESULT arm=TREATMENT run=2 cls=0.0362 lcp=800 shifts=1 heroBand=1280
RESULT arm=CONTROL   run=3 cls=0.1316 lcp=800 shifts=1 heroBand=1152
RESULT arm=TREATMENT run=3 cls=0.0362 lcp=804 shifts=1 heroBand=1280

RESULT arm=CONTROL   run=1 cls=0.1316 lcp=808 shifts=1 heroBand=1152     (probe 2, three arms)
RESULT arm=TREATMENT run=1 cls=0.0362 lcp=808 shifts=1 heroBand=1280
RESULT arm=MECH      run=1 cls=0.1316 lcp=792 shifts=1 heroBand=1152
RESULT arm=CONTROL   run=2 cls=0.1316 lcp=800 shifts=1 heroBand=1152
RESULT arm=TREATMENT run=2 cls=0.0362 lcp=796 shifts=1 heroBand=1280
RESULT arm=MECH      run=2 cls=0.1316 lcp=800 shifts=1 heroBand=1152
RESULT arm=CONTROL   run=3 cls=0.1316 lcp=796 shifts=1 heroBand=1152
RESULT arm=TREATMENT run=3 cls=0.0362 lcp=804 shifts=1 heroBand=1280
RESULT arm=MECH      run=3 cls=0.1316 lcp=796 shifts=1 heroBand=1152
```

**Six control runs, six treatment runs, three mech runs. Spread within every arm: 0.0000.**
`heroBand` is read in-browser from the h1's nearest `max-width`-constrained ancestor, so the
change is confirmed to BIND at this viewport rather than assumed to.

### The committed instrument reproduces the probe

The temp probe was deleted, but the fidelity gap it opens was closed empirically rather than
asserted: the **committed** describe, run against the same treatment server, reports

```
web-vitals-desktop | / — LCP=808ms CLS=0.0362 at 1440x900, 4x CPU ·
                     Marketing band 1280px, 4 in main / 6 document-wide ·
                     control 0.1316 · record 0.0362
```

Same number, same instrument. The constant is not measured one way and asserted another.

## The specific question 35-06 handed over — answered

> *"CLS is area-weighted … a hero region ~11% wider yields a ~11% larger impact fraction for
> an identical vertical displacement … so desktop CLS could rise on area alone."* (D-35-06-b)

**Measured: it did not rise. It fell 72%.** And the two candidate explanations the
orchestrator asked to be distinguished are both refuted, in the same data:

- **NOT a new shift.** All three arms record **exactly one** layout-shift entry, at
  ~1520 ms, with the same five hero sources. The count, the timing and the region are
  unchanged. The same shift got lighter.
- **NOT area weighting.** Area weighting predicts a rise; the measurement is a fall, and the
  MECH arm shows the fall tracks the tier value alone.

The mechanism visible in the shift entry's own `prev`/`cur` rects — the persona-door grid:

```
CONTROL / MECH   prev x=168 y=605 w=540 h=220  ->  cur x=168 y=633 w=540 h=248   (+28px TALLER)
TREATMENT        prev x=104 y=545 w=604 h=220  ->  cur x=104 y=633 w=604 h=220   (height unchanged)
```

At a 540px column the block **reflows to an extra text line during hydration**; at 604px it
does not. Settled page height agrees: 2262px at 1152 vs 2234px at 1280. A height change makes
every box below it unstable too; the wider band removes the reflow and with it the cascade.

**Stated as a boundary, not glossed:** the full `impact x distance` arithmetic is NOT
reconstructed. The `LayoutShift` API caps `sources` at five entries, so the complete set of
unstable elements is not observable and the decomposition cannot be derived from what it
reports. What *is* observable — one entry, same time, same region, height growth present in
one arm and absent in the other — is reported; the rest is not guessed at.

## The control, and its relationship to the 375px figure

**Declared control: `LANDING_CLS_DESKTOP_CONTROL = 0.1316`** — the CONTROL arm, i.e. what the
route did before this phase touched it.

`perf-budgets.ts` records **0.1793 on `/` at 375px**. The two are **not comparable and neither
was asserted against the other**: CLS normalises by viewport, so the numbers describe
different layouts at different widths and their difference (0.0477) means nothing on its own.
The mobile record was measured where a 1280px cap provably cannot bind; the desktop control
was measured where it does. The mobile arm keeps asserting against 0.1793, untouched.

**`CLS_BUDGET` is not raised and neither is the mobile record.** 0.1 stays declared and unmet
at 375px so the pre-existing debt stays visible. Desktop now measures **under** it (0.0362),
which is recorded, not celebrated — the phase did not set out to fix CLS.

### Portability of the number — checked, not assumed

The declared numbers were measured on host-served `next start` arms rather than in a
container, which is a real threat to their portability. Measured directly: the **containerised
Compose runtime on :3000** (whose landing code also predates the change) scores

```
RESULT arm=TREATMENT run=1..3  cls=0.1316  lcp=792/800/808  heroBand=1152  url=http://localhost:3000
```

byte-identical to the host-served merge-base build. The host rig reproduces the container for
this measurement, and the number is origin-independent (0.0362 on both `127.0.0.1:3011` and
`localhost:3011`).

## Verification — every criterion in both directions

### Task 1 — the declared constants

| Check | Direction | Real output |
|---|---|---|
| `npx tsc --noEmit -p tsconfig.e2e.json` | pass | rc=0 |
| `scripts/check-no-measured-placeholders.sh` | pass | rc=0, `matches: 0` |

**The placeholder gate is green and CANNOT SEE THIS FILE — proven, not inferred.** The plan's
verify pairs that gate with `perf-budgets.ts`, so its scope was tested rather than trusted:

```
ARM 1  the forbidden token appended to frontend/e2e/perf-budgets.ts
       rc=0   matches: 0   PASS                        <- structurally blind
       restored: SHA256 MATCH

ARM 2  the SAME token appended to .env.example (a file the gate DOES scan)
       rc=1   matches: 1
       .env.example:473:JTOYE_SCOPE_PROBE=<<MEASURED>>
       FAIL: an unfilled placeholder reached shipped config
       restored: SHA256 MATCH

CLOSING rc=0  matches: 0   PASS ; git status clean
```

So the gate is capable of failing, and its pass over `perf-budgets.ts` is a statement about
**scope**, not about this constant. Scope: `application*.yml`, `.env.example`, `k8s/` only.
The evidence that the constant is measured rather than invented is the three-arm A/B above,
not that gate.

### Task 2 — the arm exists (RED → GREEN on the instrument itself)

| | RED (before) | GREEN (after) |
|---|---|---|
| `--project=desktop --grep '@desktop-only' --list` | rc=1, **Total: 0 tests in 0 files** | rc=0, **Total: 1 test** |
| control: same query, no tag | rc=0, 4 tests | rc=0, 5 tests |
| `--project=mobile --list` | 4 tests | **4 tests** — the new block is never enumerated |

### Task 3 — five arms, each recorded in both directions

**ARM A — the declared control lowered below the measured value.** Proves the comparison is live.

```
BROKEN    LANDING_CLS_DESKTOP_CONTROL = 0.0100
          Error: / desktop CLS 0.0362 regressed past LANDING_CLS_DESKTOP_CONTROL
                 (0.01 + LANDING_CLS_TOLERANCE 0.02 = 0.0300) — the phase made the landing
                 route less stable at the viewport where its width actually changed
          Expected: < 0.03   Received: 0.03616344307270233     rc=1
RESTORED  by content: LANDING_CLS_DESKTOP_CONTROL = 0.1316
          by blob:    git diff --quiet HEAD rc=0
```

**ARM A′ — the same for the ratchet**, because a second live assertion needs its own fail direction.

```
BROKEN    LANDING_CLS_DESKTOP_RECORD = 0.0100
          Error: / desktop CLS 0.0362 regressed past LANDING_CLS_DESKTOP_RECORD
                 (0.01 + LANDING_CLS_TOLERANCE 0.02 = 0.0300) …
          Expected: < 0.03   Received: 0.03616344307270233     rc=1
RESTORED  by content and by blob (git diff --quiet rc=0)
```

**ARM B — THE ARM THAT MATTERS.** A real desktop-only hydration shift, authored in the
scratch treatment tree, rebuilt, and measured. `0px` server-side, `160px` after mount
(`lg:h-0` → `lg:h-40`), above the fold, in the hero's left column. Verified in the served
HTML before measuring (`data-arm-b-shift` present ×1, `lg:h-0` present, `h-40` rules generated).

```
BROKEN (rebuilt runtime)
  DESKTOP  Error: / desktop CLS 0.0962 regressed past LANDING_CLS_DESKTOP_RECORD
                  (0.0362 + 0.02 = 0.0562)
           Expected: < 0.0562   Received: 0.09624754801097393        rc=1

  THE CONTROL THAT MATTERS — the MOBILE arm over the SAME shifted runtime:
           ✓ holds LCP and CLS at a throttled mobile profile
           ✓ every shop logo reserves its box
           ✓ records the / client-JS baseline
           (block 4 fails for an unrelated environmental reason — see deviation 2)

RESTORED  component deleted, page.tsx re-extracted from HEAD, rebuilt
  by content   ArmBShift refs 0 · arm-b-shift.tsx absent · data-arm-b-shift 0 files ·
               WIDTH_TIER_CLASS.marketing sites 4
  by identity  page.tsx byte-identical to HEAD (cmp), and cmp PROVED capable of
               reporting a difference against the merge-base copy
  by tree      diff -rq vs a fresh `git archive HEAD` reports only the two e2e/ files
               this plan itself committed — zero residue in anything that renders
  by artefact  data-arm-b-shift in the REBUILT served HTML: 0 (probe control:
               "Independent UK kitchens" present ×2, so the probe can find things)
  by re-run    rc=0, CLS back to 0.0362, band 1280px
```

**ARM B is the finding that changed this plan.** 0.0962 is an unambiguous regression — 2.7x
the shipped value — and it **passes** the control-only bound of 0.1516. Without the ratchet
the arm would have gone green over exactly the class of defect it exists to detect. The
control-only assertion the plan specified is, on its own, incapable of failing on a realistic
regression. That is measured, not argued.

**The other half of ARM B is the whole thesis of ORCH-02, demonstrated live:** the mobile
blocks report a clean pass over a runtime carrying a deliberate 160px above-the-fold layout
shift, because `lg:` cannot bind at 390px. A mobile-only CLS gate is not a weak instrument
here — it is a blind one.

**ARM C — the vacuity arm.** The describe pointed at a route that does not exist.

```
BROKEN    page.goto(`${BASE}/this-route-does-not-exist-35-09`)
          Error: no landing h1 inside <main> — this arm measured something that is not the
                 landing page, and a CLS of 0 from a page that did not render is not a pass
          Locator: locator('main').getByRole('heading', { level: 1 })
          Expected: 1   Received: 0                                  rc=1
RESTORED  by content (all five goto sites re-listed) and by blob (git diff --quiet rc=0)
```

**Read the 404 page's own numbers, because they are the justification for the scoping:**
`http=404`, 15,634 bytes, **`<h1>` count 1**, **`<main>` count 0**, tier declarations 0. The
page HAS an h1. An unscoped `getByRole("heading", {level:1})` would have passed, the test
would have measured a clean score on a 404, and that score would have been cited as evidence
about this phase. Only the `main`-scoped form catches it.

**ARM D — the stale-runtime arm (added).** No file edited; only the runtime changed.

```
against the MERGE-BASE build (:3001)   and   against the Compose image (:3000)
  Error: no Marketing-tier band inside <main> — the surface whose width changed is not on this page
  Expected: > 0   Received: 0                                        rc=1 (both)
```

Both stale runtimes red **structurally, before any score is read**. This corrected a claim
this plan had already written into two files — see deviation 1.

### Closing clean arms

```
(a) desktop arm, runtime built from this exact commit (:3011)
    rc=0   1 passed   CLS=0.0362  band 1280px  4 in main / 6 document-wide

(b) the WHOLE file on the mobile project, canonical Compose runtime (:3000)
    rc=0   4 passed   — the pre-existing arm is unchanged and healthy

(c) the whole file on DESKTOP against the STALE Compose image (:3000)
    rc=1   4 passed, 1 failed
    ✓ 1..4  the four pre-existing 375px blocks
    ✘ 5     @desktop-only … no Marketing-tier band inside <main>
```

**Arm (c) is this plan's thesis in one run.** The four pre-existing blocks report a clean pass
over a frontend image four hours behind the branch. The block added here is the only thing in
the file that notices.

### Repository state

```
git status --short                          (clean — no output, rc=0)
npx tsc --noEmit -p tsconfig.e2e.json       rc=0
npx eslint e2e/perf-budgets.ts e2e/landing-webperf.spec.ts   rc=0, no output
scripts/check-e2e-typecheck.sh              rc=0, 30 e2e files clean
scripts/check-e2e-baseurl-contract.sh       rc=0, 26 specs, 15 fallbacks, 0 divergent
scripts/check-playwright-mobile-contract.sh rc=0, 2 projects, 0 blind
scripts/check-no-measured-placeholders.sh   rc=0, matches 0
scripts/check-branch-behind-base.sh         rc=0 — 51 ahead, 0 behind origin/main
scripts/check-doc-metrics.sh                rc=0, 37/37 claims
scripts/docs-freshness.sh                   rc=1 (DRIFT, not VOID) — 35-11's, see below
```

## What this arm covers, and what no current tree executes

Stated in the form CONTEXT §5 requires, and **not** as "covered nightly":

- `.github/workflows/ci-cd.yaml:531` runs **only** `public-layout.spec.ts` and
  `public-a11y.spec.ts` per PR. **This block is NOT in that set.**
- Issue **#683** records the nightly full-suite lane as **dark**.
- Therefore the honest statement is: **the desktop CLS arm is covered by a spec that no
  current tree executes.** It is not blocking on the PR and it is not running nightly.

That boundary is written into the spec's own docblock so a later reader cannot mistake it,
and it cuts both ways: the arm cannot block a merge, and it also cannot flake one.

**What IS proven, and where:** the number, the direction, the causation and the arm's ability
to fail were all measured here, on runtimes built from named commits, and are recorded above.
What is **not** proven is that any scheduled job will ever run this block again. That is #683
and #686, neither of which this plan owns.

## Displaced goods

The plan displaces nothing — it adds a describe and two constants. The goods it could have
damaged were each checked rather than assumed:

1. **The mobile arm's assertions.** PRESERVED and untouched: `LANDING_CLS_KNOWN_BASELINE`,
   `LANDING_CLS_TOLERANCE`, `CLS_BUDGET`, `LCP_BUDGET_MS` and all four existing blocks are
   byte-identical. Verified by `--project=mobile --list` returning the same four blocks, and
   by 4/4 green on the canonical runtime.
2. **The e2e skip budget** (`MAX_SKIPS 6`, already at 7/6 per #686). PRESERVED: the new block
   adds **zero** skips. It is tagged `@desktop-only`, so the mobile project's `grepInvert`
   means it is never ENUMERATED rather than enumerated-then-skipped (#420).
3. **The declared-budgets-are-imported convention.** PRESERVED and extended: the new
   assertions import their constants; no number is restated in the spec.
4. **35-08's width contract.** NOT ENCROACHED. Guard (4) asserts the band is wider than 375px
   — a floor proving the arm ran at desktop — deliberately **not** the tier's value, so this
   plan does not duplicate the width contract 35-08 owns and the two cannot drift.

**BETTERED:** the landing route's layout stability is measurable at the viewport where its
width actually changed, and the improvement the phase delivered is now asserted rather than
merely observed.

## Deviations from Plan

### 1. [Rule 1 — Bug] Two comments made a claim the arms measured wrong

- **Found during:** Task 3, ARM D.
- **Issue:** Both files said a frontend built before this phase "serves the 1152px band,
  scores 0.1316, sails under the control bound and reds the ratchet". Measured against the
  merge-base build **and** the Compose image, that is not what happens: a runtime predating
  35-06 carries no `data-width-tier` at all, so non-vacuity guard (2) reds first with
  *"no Marketing-tier band inside `<main>`"* and **no score is ever read**.
- **Fix:** both sites rewritten to the measured behaviour, plus the distinction that only a
  runtime carrying the attribute with an older tier VALUE reaches the ratchet. Committed
  separately (`73278b0d`) and verified to change **no `export const` line** — a claim proven
  with `git diff HEAD~1 HEAD | rg '^[+-]export const'` returning nothing.
- **Why it is recorded:** a docblock that describes a failure mode the code does not have is
  the same defect class as an unfalsifiable assertion — it will be trusted and it is wrong.

### 2. [Rule 2 — Missing critical functionality] A second constant, because the specified one cannot fail on a realistic regression

- **Issue:** the plan specifies the no-regression form against the control. Measured, that
  bound is `0.1516` while the route scores `0.0362` — **3.6x slack**. ARM B then measured a
  genuine, deliberate, above-the-fold desktop hydration shift at **0.0962**, which **passes**
  it. So the plan-specified assertion, alone, would have gone green over the exact defect the
  arm exists to detect.
- **Fix:** `LANDING_CLS_DESKTOP_RECORD = 0.0362` added and asserted alongside the control.
  The shared `LANDING_CLS_TOLERANCE` is reused — **no new tolerance was invented**, and
  neither bound was widened. Both assertions are live and both have recorded fail directions
  (ARM A, ARM A′).
- **Defensibility, on evidence:** spread 0.0000 across six treatment runs; 55% headroom;
  reproduced identically on two origins and by two different instruments.
- **This is a tightening, not a substitution.** The plan's criterion is still asserted, first,
  with its own message.

### 3. [Observed, not caused] The post-grant mobile block fails on the host-served arms and passes on Compose

- **Found during:** ARM B's mobile control.
- **What happens:** `landing-webperf.spec.ts:341` ("POST-GRANT") fails
  `getByRole('heading', {name: /near you/i})` `Expected: 1 Received: 0` against **both**
  host-served arms.
- **Attributed with a control in both directions, not guessed:** it fails identically against
  the **pristine merge-base** build (which contains none of phase 35), and it **passes**
  against the canonical Compose stack (`rc=0, 1 passed`). So it is a property of the
  measurement rig, not of the tree and not of this plan.
- **Two hypotheses raised and REFUTED, recorded so nobody re-runs them:** (i) a cross-origin
  artefact of serving on `127.0.0.1` while `NEXT_PUBLIC_API_URL` names `localhost` — refuted,
  it fails on `localhost:3011` too; (ii) a missing `Permissions-Policy` under
  `output: standalone` — refuted, all three servers emit
  `geolocation=(self)` identically.
- **Not fixed and not deferred:** there is nothing to fix in the tree. The block is green in
  the canonical runtime, it is not in this plan's file set, and this plan's desktop arm never
  exercises the post-grant path.

### 4. [Rule 3 — Blocking] The measurement ran in scratch trees, never in the repository

- Both A/B arms and ARM B's deliberate shift were built from `git archive` into
  `/tmp/.../scratchpad/35-09`, with `next start` on ports 3001/3011/3012.
- **Why:** ARM B has to author a real layout shift in `app/page.tsx`, which is **35-06's**
  file. Doing it in a scratch tree means the repository is never edited, so no restore can
  eat anything, and the rebuild is 15 s rather than a Docker image build.
- The repository working tree was verified clean at every stage; `git status --short` is
  empty at close.

### 5. [Instrument defect, self-caught] `cp -al` hardlinked the SOURCE, so patching the mech arm edited the treatment arm

- **Found during:** building the MECH arm.
- **What happened:** `cp -al treatment mech` hardlinks every file, so writing
  `MARKETING_MAX_PX = 1152` into `mech/` truncated the **same inode** and changed `treatment/`
  too. `diff -rq` then reported the two trees as identical, which is what surfaced it —
  the expected difference was missing.
- **Not assumed benign — scoped by measurement:** the repository's own
  `frontend/lib/layout-widths.ts` still read `1280` and `git status` was clean, so the
  contamination was confined to the scratch trees; and the treatment `.next` had been built
  **before** the patch, so the already-recorded treatment numbers described 1280 (confirmed
  independently by the in-browser `heroBand=1280` and the `max-w-marketing{max-width:1280px}`
  rule in its stylesheet).
- **Fix:** both trees re-extracted from `git archive` with `rm` first so new inodes are
  created, `sed -i` used for the patch (it writes a temp and renames, breaking hardlinks),
  and **inode identity plus link count printed as the check** — three distinct inodes, link
  count 1 each. The treatment arm was then **rebuilt and re-measured from the restored
  source**, and reproduced 0.0362 exactly.
- **Why it is recorded:** a hardlink copy is the fast way to duplicate `node_modules` and a
  silent way to corrupt a control arm. The tell was a `diff` that reported *no* difference
  where one was guaranteed — which is only a tell if you know what the answer must be.

### 6. [Rule 3 — Blocking] `pkill -f 'next start -p 3011'` killed the shell that ran it

- The pattern appears in the invoking shell's own command line, so `pkill -f` matched the
  enclosing process and terminated it (exit 144). Nothing was destroyed — the `rm -rf` that
  followed never ran, which was confirmed by reading the `.next` mtime rather than assumed.
- **Fix:** servers are stopped by the PID recorded at spawn, never by matching a pattern the
  caller's own command line contains.

### 7. [Rule 3 — Blocking] Port 3002 is Grafana, and `next start` reported HTTP 302 rather than an error

- The treatment arm's first server never bound: `EADDRINUSE 127.0.0.1:3002`
  (`jtoye-grafana` publishes it). `curl` returned **302 → /login**, i.e. a plausible-looking
  HTTP response from an entirely different service.
- **Caught by reading the serve log, not the status code** — the status code was the thing
  that lied. Moved to 3011/3012 and added an explicit `EADDRINUSE|Ready in` probe of both
  serve logs before measuring.

### 8. [Rule 3 — Blocking] `STATE.md`, `ROADMAP.md`, `REQUIREMENTS.md` and `docs/metrics.json` left untouched

- Follows the convention 35-03 set and 35-06 documented for this phase. `docs/metrics.json`
  is **35-11's** declared file and the orchestrator reserved its regeneration explicitly;
  UIX-09 is shared with 35-05/06/07 so a partial requirement edit would be wrong; and
  `state.record-session` is recorded as corrupting `STATE.md` mid-plan.
- Truthful text for the orchestrator is at the end of this summary.
- **Every commit staged named paths only** — never `git add .` or `-A` — and each was checked
  after the fact for file deletions (`git diff --diff-filter=D HEAD~1 HEAD`): **zero** on all
  three.

## Known Stubs

None. This plan ships no placeholder, no empty state, no TODO and no hardcoded empty value.
Both declared numbers were measured; the file's own history (`LANDING_BUNDLE_BASELINE_BYTES`,
where an invented 461,000 was caught by its own assertion) is why that matters here.

## Threat Flags

None. This plan adds no endpoint, no input, no credential, no data flow and no dependency.

| Threat | Disposition | Evidence |
|---|---|---|
| **T-35-32** a baseline taken from whichever run happened to pass | **mitigated** | three arms, fifteen runs, every one recorded; spread 0.0000; the MECH arm isolates causation across a 55-file diff |
| **T-35-33** tolerance / budget widening | **mitigated** | `LANDING_CLS_TOLERANCE` reused unchanged; no new tolerance declared; `CLS_BUDGET` and `LANDING_CLS_KNOWN_BASELINE` untouched; the second constant TIGHTENS and is proven load-bearing by ARM B |
| **T-35-34** a vacuous zero | **mitigated** | four guards run before the score is trusted, including a scope control; ARM C proves the guard fires on a 404 that *does* have an h1 |
| **T-35-35** a stale runtime | **mitigated, and better than planned** | ARM D: both stale runtimes red structurally before any score is read; closing arm (c) shows the four pre-existing blocks passing clean on the same stale image |
| **T-35-SC** package installs | **accept** | nothing installed; the Package Legitimacy Gate correctly did not run rather than being skipped |

ASVS L2: no category applies — a measurement and two constants, touching no endpoint, input,
credential or data flow.

## Cross-Cutting Quality Contracts

- **Web performance:** this plan **IS** the web-performance contract for the phase's only real
  width change. Measured at a throttled profile, never unthrottled localhost.
- **SEO:** N/A, checked rather than waved — no markup, metadata, canonical, Open Graph,
  JSON-LD, sitemap entry or link is touched. Only `frontend/e2e/**` changed.
- **AI agent-readiness:** N/A. No API surface changed.
- **Security:** threat register above; no category applies.
- **Falsifiable evidence + runtime parity:** five arms, all recorded in both directions; ARM B
  introduces a real shift against a rebuilt runtime rather than only moving a number; and the
  runtime-parity half is not merely respected but **instrumented** — the arm detects a stale
  frontend structurally, and closing arm (c) demonstrates it on the live Compose image.

## For the orchestrator to apply after the phase

**Block delta — measured with a control, never by arithmetic:**

```
frontend/e2e/landing-webperf.spec.ts   test( blocks   4 -> 5      (+1)
frontend/e2e/*.spec.ts                 file count    26 -> 26     (+0)
jest files changed by this plan                                    0
CONTROL: public-layout.spec.ts (untouched)  8 -> 8, so the counter CAN report no change
```

So **35-09 contributes +1 `playwright_blocks`, +0 `playwright_specs`, +0 jest blocks, +0 jest
files.**

`scripts/docs-freshness.sh` is now **rc=1 (DRIFT)** — no longer the rc=2 VOID that 35-06
recorded, so the `describe.each` blocker is cleared and 35-11 can regenerate. Its numbers:

```
committed docs/metrics.json   jest_blocks 1394  jest_files 140  playwright_blocks 122  specs 26  total 3378
computed from source          jest_blocks 1504  jest_files 141  playwright_blocks 123  specs 26  total 3489
```

The `playwright_blocks` 122 → 123 delta is **entirely this plan's**. Regenerate with
`scripts/docs-freshness.sh --write`, never by hand.

**Requirement text, truthful as of this plan:**

- **UIX-07: still in progress.** This plan supplies the falsifiable web-performance evidence
  for the contract but does not complete it — **35-10 and 35-11 remain**.
- **UIX-09: in progress, 2 of 4 plans.** `REQUIREMENTS.md` assigns it to 35-05, 35-06, 35-07
  and 35-09. 35-06 landed the Marketing tier on `/`; 35-09 has now measured it at a viewport
  where it binds. **35-05 (Detail) and 35-07 (the remaining marketing surfaces) remain.**
  Not complete.

**One thing worth surfacing at the 35-13 gate:** desktop CLS on `/` **improved 72%** as a
side effect of ORCH-04, and the mechanism is a hydration reflow the wider band removes. That
is a user-visible good nobody asked for and nothing else in the phase would have found.

## Cited decisions

**ORCH-02 (orchestrator decision, 2026-08-29)** is the whole of this plan and is cited by that
name in both files — never as "user decision". **ORCH-04** is the change being measured and is
named where the measurement refers to it. ORCH-01/03/05/06 do not bear on this file set.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `3aa28f5d` | test | declare the measured desktop CLS control for the landing route |
| `3ef2cd51` | test | add the desktop-viewport CLS arm on the landing route |
| `73278b0d` | docs | correct two stale-runtime claims the arms measured wrong |

Every commit message was written through a **quoted heredoc** and read back with
`git log -1 --format=%B`. Backticks inside a double-quoted `-m` string execute and silently
drop the phrase, and the corruption is invisible at write time.

## TDD Gate Compliance

The plan's frontmatter is `type: tdd` and Task 2 is `tdd="true"`, but **this plan ships no
behaviour** — it adds an instrument and two measured constants, and there is no implementation
for a test to drive. Stated plainly rather than dressed up:

- **There is no `feat(` gate and there should not be.** Manufacturing one would mean inventing
  production code this plan does not need.
- **The RED gate is real and was observed before its GREEN**, on the instrument itself: the
  desktop project found **0 tests in 0 files** for `@desktop-only` before the commit and 1
  after, with a passing control (the same query without the tag found 4) proving the emptiness
  was about the tag rather than the tooling.
- **The substantive fail direction is Task 3's five arms**, every one recorded in both
  directions, one of which (ARM B) introduces a real layout shift against a rebuilt runtime
  rather than moving a number — and which **changed what this plan shipped** by showing the
  plan-specified assertion could not fail on it.

## Self-Check: PASSED

```
FILES
  frontend/e2e/perf-budgets.ts                      FOUND
  frontend/e2e/landing-webperf.spec.ts              FOUND
  CONTROL: frontend/e2e/tmp-3509-cls-probe.spec.ts  MISSING  (deleted, as intended)
COMMITS
  3aa28f5d FOUND   3ef2cd51 FOUND   73278b0d FOUND
  CONTROL: deadbee MISSING
CONSTANTS, read back from the committed file
  LANDING_CLS_DESKTOP_CONTROL = 0.1316   LANDING_CLS_DESKTOP_RECORD = 0.0362
  LANDING_CLS_KNOWN_BASELINE  = 0.1793   CLS_BUDGET = 0.1   (both unchanged)
```

Both halves were run with a control — a path that must not exist reported MISSING and
`deadbee` reported MISSING — so the FOUND results are statements about this repository rather
than about a check incapable of failing.
