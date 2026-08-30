---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 06
subsystem: ui
tags: [tailwind, layout, design-tokens, react, jest, marketing, landing, public-chrome, cls]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 02
    provides: "WIDTH_TIER_CLASS — the tier -> class map, and the in-place application doctrine"
provides:
  - "the landing page's four content bands at the Marketing tier — the ONLY real width change in phase 35 (1152px -> 1280px)"
  - "the Marketing tier declared on both shared public rails, so content and chrome agree by contract rather than by luck"
  - "a queryable data-width-tier on chrome that renders on EVERY public route, including the whole /shop/** subtree"
  - "a calibrated overflow model for the dish rail, reproducing both browser measurements the spec records"
affects: [35-08, 35-09, 35-10, 35-11, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "paired-rail-declaration: chrome and content declare the SAME tier, with prose at each site saying they move together"
    - "count-the-bands: a partial migration is the likeliest defect, so the NUMBER of tiered bands is pinned, not merely their existence"
    - "scope-the-count-to-main: once chrome declares the same tier, a document-wide count is wrong in a way that looks right"
    - "calibrate-then-predict: a structural model is trusted only after it reproduces the browser numbers already on record"

key-files:
  created: []
  modified:
    - frontend/app/page.tsx
    - frontend/app/__tests__/landing.test.tsx
    - frontend/components/public/public-header.tsx
    - frontend/components/public/__tests__/public-header.test.tsx
    - frontend/components/public/public-footer.tsx
    - frontend/components/public/__tests__/public-footer-legal.test.tsx
    - .planning/phases/35-horizontal-layout-contract-tiered-content-widths-across-dash/deferred-items.md

key-decisions:
  - "The rails were done FIRST and the landing second, per the plan: if the tier value were wrong, doing the rails first surfaces it as a visible change on four public routes rather than on one"
  - "The band count is asserted scoped to the MAIN landmark, because the chrome now declares the same tier and a document-wide query returns six"
  - "The stock token's ABSENCE is asserted separately from the tier class's presence, because twMerge was MEASURED not to resolve the two against each other — a half-done rename leaves two live caps"
  - "No wrapper node anywhere: the migration is in place on the four elements that already existed, and a structural parent-identity assertion enforces it"
  - "STATE.md, ROADMAP.md, REQUIREMENTS.md and docs/metrics.json deliberately untouched — five agents share one checkout on one branch"

patterns-established:
  - "A preservation control must PASS in the RED run; if it reds with everything else it is a restatement of the change, not a control"
  - "An empty result from a path that may not exist is not evidence — check the path exists before drawing the conclusion"

requirements-completed: []
requirements-progressed: [UIX-07, UIX-09]

# Metrics
duration: 75min
completed: 2026-08-29
---

# Phase 35 Plan 06: Marketing Tier on the Landing Page Summary

**The landing page and the chrome wrapped around it now share a left edge — the content
band moved from 1152px to 1280px, which is what the header and footer rails were already
rendering at, and all six elements say so in an attribute a test can read.**

## Performance

- **Duration:** ~75 min
- **Tasks:** 3 of 3
- **Files modified:** 7 (0 created, 7 modified — 6 in the declared set, plus the phase's shared
  `deferred-items.md`, whose edit was absorbed by a sibling's commit; see deviation 5)
- **Commits:** 4 implementation + 1 docs (this SUMMARY)

## What changed

### The defect, restated from the measurement

`components/public/public-header.tsx:79` and `public-footer.tsx:83` rendered their rails at
1280px. `app/page.tsx` rendered its four content bands at 1152px. **The landing content sat
128px inside its own chrome** — the nav and the hero did not share a left edge. Nothing
detected it, because both values were locally reasonable and neither element knew about the
other. That is the specific mechanical reason the page read as confined, and it is ORCH-04
(**orchestrator decision, 2026-08-29** — CONTEXT.md section 4b), not a user decision.

Nothing invented a number. 1280px is what the chrome was already doing and what the three
sibling marketing routes were already doing. `max-w-7xl` **is** exactly 1280px, so the two
rail edits are byte-identical renames — same rendered width, newly declared. Only the
landing's width actually moves.

### Task 1 — the rails (TDD), done first and deliberately

Both rails swap the stock scale token for `WIDTH_TIER_CLASS.marketing` and gain
`data-width-tier="marketing"`, in place on the elements that already existed. Prose at each
site records that rail and content are deliberately equal and must move together, and names
the 128px inset being replaced.

Rails first because the plan says so and the reason is good: if the tier value were ever
wrong, doing the rails first surfaces it as a visible change on **four public routes** —
`/`, `/for-operators`, `/track`, `/business-model-guide`, plus the whole `/shop/**` subtree
for the footer — rather than on one.

**The joiner was measured, not assumed inert**, and the controls are the interesting part:

```
HEADER  cn('mx-auto','max-w-marketing','px-4 sm:px-6 lg:px-8')
        -> mx-auto max-w-marketing px-4 sm:px-6 lg:px-8              5 in / 5 out
FOOTER  cn('mx-auto','max-w-marketing','px-4 sm:px-6 lg:px-8 py-12')
        -> mx-auto max-w-marketing px-4 sm:px-6 lg:px-8 py-12        6 in / 6 out

CONTROL 1  twMerge('max-w-7xl','max-w-6xl','mx-auto','px-4')
           -> mx-auto max-w-6xl px-4                                 4 in / 3 out  COLLAPSES
CONTROL 2  twMerge('max-w-7xl','max-w-marketing')
           -> max-w-7xl max-w-marketing                              2 in / 2 out  BOTH SURVIVE
```

Control 1 makes the pass-through a statement about the tier class rather than about a merge
that never does anything. Control 2 is the direct, measured confirmation of 35-02's
hand-off note 4, and it is **why the tests assert the stock token's absence separately**: a
rename that adds the tier class without removing the old one leaves two live caps on one
element, they resolve by cascade to the same 1280px, and the half-done state is invisible to
every rendered-width check. ARM C below proves that empirically.

### Task 2 — the four landing bands (TDD)

All four migrated in place — hero, kitchen row, how-it-works, trust strip. **No wrapper node
anywhere**: this page is dense with GSAP hooks and scroll-reveal, and a new node between a
section and its band is exactly the change that moves a boundingBox assertion for no reason.
One explanation at the first band, three consistent applications after it (four copies of one
explanation is drift waiting to happen, and plan 35-10's gate counts literals).

The joiner measured per band: **8/8, 6/6, 5/5, 5/5** tokens, order preserved.

Deliberately untouched, and pinned by a passing control rather than by intent: the hero
sub-paragraph's `max-w-xl` reading measure and the landing search form's `max-w-xl`. Both are
nested inside the hero band, so widening the band cannot widen them; both are typographic
measures or a CLS-sensitive control rather than page bands (PATTERNS 1c, CONTEXT §5).

### Task 3 — the arms

Tasks 1 and 2 were **committed before any arm ran**, so no restore could eat a fix. Every arm
was applied and reverted with the editor, never with `git checkout` or `git stash` — five
agents share this checkout, and a blanket restore there is how another session's work is lost.

## Verification — every criterion in both directions

### The TDD gates (fail direction by construction)

| Suite | RED | GREEN |
|---|---|---|
| `public-header.test.tsx` + `public-footer-legal.test.tsx` | rc=1, **9 failed / 23 passed** | rc=0, 7 suites / **75** |
| `landing.test.tsx` | rc=1, **6 failed / 10 passed** | rc=0, **16/16** |

The passes inside the RED runs are the point. In the rail RED, "keeps the sticky chrome"
passed — it asserts the `<header>`, not the rail, so it proves the instrument works before
any absence is trusted. In the landing RED, "does NOT touch the hero reading measure or the
search form width" passed — a preservation control that reds alongside everything else is a
restatement of the change, not a control.

### Opening clean arm

```
npx jest components/public app/__tests__/landing.test.tsx
8 suites / 91 tests   rc=0        (74 before this plan; +17)
git diff --quiet HEAD -- <all six files>   rc=0 on every one
```

### ARM A — one band left behind (plan-specified)

The trust strip reverted to the old token, the other three left alone. This is the arm that
proves a **partial migration** — the likeliest real defect here and invisible to a spot check
— is detectable.

```
BROKEN    <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">   (band 4 of 4)
          ✕ renders exactly four Marketing-tier bands, so a partial migration reds
              Expected length: 4 / Received length: 3
          ✕ leaves no page band on the narrower stock token it was renamed from
              Received + 3      (one straggler, named by its className)
          ✕ keeps the auto margin and every padding class on all four bands
              Expected length: 4 / Received length: 3
          ✕ shares its tier with the chrome — content and rails now agree
              Expected length: 6 / Received length: 5
          4 failed, 12 passed   rc=1
RESTORED  max-w-6xl in app/page.tsx: matches=[] rc=1 | tier decls = 4
          git diff --quiet HEAD rc=0 | 16/16 rc=0
```

### ARM B — the tier attribute removed, the class left (plan-specified)

Proves the assertion measures the **declaration**, not merely the width: the class was still
there and the rail still rendered at 1280px, and it went red anyway.

```
BROKEN    header rail: data-width-tier deleted, className untouched
          4x  no element in the public header declares a width tier
          ✕ (landing) shares its tier with the chrome
              Expected length: 6 / Received length: 5
          5 failed, 22 passed   rc=1
RESTORED  tier decls in header = 1 | git diff --quiet HEAD rc=0 | 27/27 rc=0
```

Precisely isolated: "keeps the sticky chrome" stayed green throughout, because it asserts the
banner rather than the rail.

### ARM C — the half-done rename (added; the highest-value arm)

The stock token put **back beside** the tier class on the footer rail — the exact defect
Control 2's twMerge measurement predicts is invisible.

```
BROKEN    cn("mx-auto max-w-7xl", WIDTH_TIER_CLASS.marketing, "px-4 sm:px-6 lg:px-8 py-12")
          ✕ no longer carries the stock scale token it was renamed from
              Expected: false / Received: true
          1 failed, 20 passed   rc=1
RESTORED  max-w-7xl in public-footer.tsx: matches=[] rc=1
          git diff --quiet HEAD rc=0 | 21/21 rc=0
```

**Exactly one assertion fired.** The tier declaration, the tier class, the padding ledger and
the header/footer pairing assertion all stayed green — because both caps really do survive
twMerge, so the element renders at 1280px either way. Only the absence assertion can see it.
That is the whole justification for writing the shed token out as a literal in two test files.

### ARM D — a preserved padding class dropped (added)

```
BROKEN    cn("mx-auto", WIDTH_TIER_CLASS.marketing, "px-4 lg:px-8 py-12")   (sm:px-6 removed)
          ✕ keeps the auto margin and every padding class on all four bands
              Expected: true / Received: false
          1 failed, 15 passed   rc=1
RESTORED  16/16 rc=0
```

### ARM E — the hero reading measure swept up in the migration (added)

The realistic drift: someone widens the copy along with the band. This is the fail direction
for displaced good 1, which otherwise had none.

```
BROKEN    <p className="mt-4 max-w-4xl text-lg text-slate-600">
          ✕ does NOT touch the hero reading measure or the search form width
              Expected: true / Received: false
          1 failed, 15 passed   rc=1
RESTORED  16/16 rc=0
```

### ARM F — a wrapper node inserted around the hero band (added)

The fail direction for the in-place doctrine, which had none anywhere in the phase.

```
BROKEN    <div className="w-full"> wrapped around the hero band
          ✕ keeps the hero band's own motion hooks and section chrome intact
              Object.is equality on parentElement
          1 failed, 15 passed   rc=1
RESTORED  16/16 rc=0
```

**Worth reading.** Every count assertion stayed green — still four tier-declaring bands, still
six document-wide. A new DOM node is **invisible to every width assertion in this suite**, and
only the structural parent-identity check catches it. A phase that says "prefer in place"
without an assertion of that shape is stating a preference, not a contract.

### Closing clean arm

```
by content   arm residue (max-w-6xl|max-w-4xl|w-full) in app/page.tsx: matches=[] rc=1
             hero measure "mt-4 max-w-xl text-lg" present: 1
             tier decls  page=4  header=1  footer=1
by blob      git diff --quiet HEAD -- <all six files>  rc=0 on every one
by re-run    npx jest components/public app/__tests__/landing.test.tsx
             8 suites / 91 tests   rc=0
```

The closing arm is the only proof the restores landed. Verified by content **and** by blob
identity, never by `git diff --stat`.

### Regression re-runs

| Check | Result |
|---|---|
| Full Jest suite | **141 suites / 1506 tests**, rc=0 (see deviation 1 — took three runs, for a reason worth recording) |
| `npm run build` | **rc=0** |
| `npx tsc --noEmit -p tsconfig.json` | rc=0 |
| `npx eslint` over the six touched files | rc=0, no output |
| `scripts/check-e2e-typecheck.sh` | rc=0, 30 e2e files clean |
| `scripts/check-branch-behind-base.sh` | rc=0 — 38 ahead, **0 behind** `origin/main` |
| `scripts/check-doc-metrics.sh` | rc=0, 37/37 claims |
| `scripts/docs-freshness.sh` | **rc=2 (VOID)** — not mine, see deviation 2 |

### The Marketing value, read out of the generated stylesheet

```
stylesheet: .next/static/chunks/1_zber2rs3e0c.css   96747 bytes

EVIDENCE   .max-w-marketing{max-width:1280px}                    rc=0
CONTROL 1  total `max-width:` occurrences in the file             26
CONTROL 2  @media wrapping the marketing rule -> matches=[]      rc=1  (unconditional)
CONTROL 3  max-width:72rem / 1152px  -> .max-w-6xl{max-width:72rem}   rc=0
CONTROL 4  max-width:9999px -> matches=[]                        rc=1  (the probe can be empty)
```

**Control 3 is stated rather than buried.** The 1152px rule is *still in the shipped CSS*,
because `components/legal/policy-page.tsx` still uses `max-w-6xl`. So "1152 is gone from the
stylesheet" would have been a false claim, and the honest evidence is at the element, not in
the CSS. This is 35-02's warning ("the rule is in the stylesheet" is not evidence a surface
applies it) reproduced from the opposite direction. Logged as **D-35-06-a**.

Control 2 is the structural mitigation for **T-35-20**: the rule is emitted with no media
query, so a 1280px cap cannot bind against a 390px parent.

### The tier read out of the compiled artefacts, not just jsdom

```
CLIENT   rg -uu -o '"data-width-tier":"marketing"' .next/static/chunks   rc=0
CONTROL  '"data-width-tier":"prose"' -> matches=[]                       rc=1

SERVER   the landing route's SSR chunk, located by a string unique to app/page.tsx
         ("Independent UK kitchens") -> .next/server/chunks/ssr/_1hw6c4p._.js
           data-width-tier":"marketing"   x4      <- the four bands
           max-w-marketing                x1      <- one cn() call site, class passed by reference
           max-w-6xl                      matches=[]  rc=1
```

Four tier declarations and zero stock tokens, read out of the **built server artefact** for
this exact route. See deviation 3 for the probe that had to be corrected before this number
was trusted.

## The dish-scroller assertion — which branch it takes

The orchestrator flagged `e2e/marketing-dish-scroller.spec.ts:94` as the inverted risk: it
measures `scrollWidth − clientWidth > 2` and branches at `:111`/`:163` to an honest
"row fits → affordance must be silent" assertion. A wider band reduces but does not eliminate
the chance the rail now fits.

**The spec is correct and was not touched.** The branch it takes is unchanged by this plan at
both viewports the repo actually runs.

All model inputs are read from source, none invented: card `grow basis-[220px] min-w-[220px]`
(`shop-card.tsx:88`), container `flex … gap-4` = 16px (`dish-scroller.tsx:175`, and the
`role="region"` IS that flex container, so its children are the cards), band padding
`px-4 / sm:px-6 / lg:px-8` = 16/24/32 per side, viewports 390 and 1440
(`playwright.config.ts:84,109`). Because the cards **grow**, `scrollWidth == clientWidth`
unless the minimum widths cannot fit: overflow iff `236N − 16 > clientWidth`.

**Calibration first — the model reproduces the two numbers the spec's own docblock records as
MEASURED on a real rebuilt stack** (pre-change tree, band 1152, N=3):

```
@390    model overflows=true      spec docblock canRight=true
@1440   model overflows=false     spec docblock canRight=false
```

Only then the prediction, at the current seed of N=3 published shops:

```
@390    before=true   after=true    branch = OVERFLOW  (unchanged)
@1440   before=false  after=false   branch = row-fits  (unchanged)
@1920   before=false  after=false   branch = row-fits  (unchanged)
@2560   before=false  after=false   branch = row-fits  (unchanged)
```

- **mobile project (390px): the OVERFLOW branch**, before and after. A 1280px cap cannot bind
  at 390px, so this arm is inert by construction.
- **desktop project (1440px): the `!canScroll` "row fits → affordance must be silent" branch**,
  before and after. It was **already** taking that branch before this change — 33-03 measured
  exactly that and re-stated the spec for it.

**The entire flip window is N=5 published shops at 1440px** — before: overflows (5 cards need
1164px, band gave 1088px); after: fits (band gives 1216px). At N≤4 and N≥6 the branch is the
same either way. And even at N=5 the spec does **not** red: it measures overflow per run and
branches, so the flip changes which branch executes, not the verdict. That is precisely what
the 33-03 re-statement was built for.

Fail direction of the model itself, so it is not a machine that says "fits" to everything:
`@1440 N=8 → overflows=true`, `@390 N=2 → overflows=true`, `@390 N=1 → overflows=false`.

**Stated as what it is:** a structural prediction with named inputs, calibrated against
recorded browser output. It is **not** a browser measurement. The real run is owed to 35-12.

## Owed browser re-runs — and which of them can actually block

Not run here, deliberately: they need a rebuilt runtime, and a browser result read off a
stale container is worse than no result. Handed to **plan 35-12**.

**This surface is the exception in phase 35, and the distinction is precise.** `/` is
`PUBLIC_ROUTES[0]` in `e2e/public-layout.spec.ts:132` and the first route in
`e2e/public-a11y.spec.ts:169`, and `.github/workflows/ci-cd.yaml:531` runs **exactly those two
specs** on `push` **and** `pull_request`, gated on the frontend paths filter, which this change
satisfies. So for this plan the no-horizontal-overflow assertion at 390px and the WCAG 2.1 AA
scan on `/` are **genuinely blocking on the PR** — the only tier in this phase with real
per-PR cover. The other three specs below are nightly-only, and **#683 records that lane as
dark**: the honest phrasing for them is *"covered by a spec that no current tree executes"*,
never *"covered nightly"*.

| Spec | What it asserts on `/` | Lane |
|---|---|---|
| `e2e/public-layout.spec.ts` | no horizontal overflow at 390px, aspect conformance, image decoding | **per-PR, blocking** |
| `e2e/public-a11y.spec.ts` | WCAG 2.1 AA scan (LGL-02) | **per-PR, blocking** |
| `e2e/landing-webperf.spec.ts` | CLS vs `LANDING_CLS_KNOWN_BASELINE` 0.1793, LCP, client-JS ceiling | nightly — dark (#683) |
| `e2e/marketing-motion.spec.ts` | boundingBox positions at 375px on this route | nightly — dark (#683) |
| `e2e/near-you-row.spec.ts` | the kitchen row's located/unlocated states | nightly — dark (#683) |
| `e2e/marketing-dish-scroller.spec.ts` | the inverted-risk affordance branch above | nightly — dark (#683) |

## CLS — what this plan did not fix, and the one thing 35-09 must catch

`/` carries pre-existing CLS debt of **0.1793** against a `CLS_BUDGET` of 0.1
(`e2e/perf-budgets.ts:35,49-56`). This plan does not fix it and does not claim to.

**Mobile: inert by construction, and the construction is verified.** `.max-w-marketing` is
emitted with no media query (CONTROL 2 above, `rc=1` on the `@media` probe), so it cannot bind
against the 375px viewport the 0.1793 baseline was measured at. The padding classes are
unchanged. The mobile content box is identical before and after. Structural, not observed —
the observation is 35-12's.

**Desktop: a specific, mechanical risk, flagged rather than assumed away.** The single recorded
shift fires at ~1516 ms with its `sources` being hero elements — the search form, the category
chips, the paragraph and both persona doors. Every one sits inside the band this plan widened.
CLS is **area-weighted**: the impact fraction is the union of the shifting region's visible
area over the viewport area, so a hero region ~11% wider yields a ~11% larger impact fraction
for an *identical* vertical displacement. The distance factor is unchanged; the impact factor
is not. Nothing in the repo measured desktop CLS on `/` before 35-09, which is exactly why
ORCH-02 exists. Logged as **D-35-06-b** so 35-09 shapes its arm to catch this rather than
rediscovering it.

## Displaced goods — each one named and accounted for

The plan's ledger had three. All three are preserved, none traded, and each now has an
executable assertion and a recorded fail direction rather than a promise.

1. **The narrower reading measure of the hero copy.** PRESERVED. `max-w-xl` on the hero
   sub-paragraph, nested inside the band and unaffected by the band's width. Asserted by
   "does NOT touch the hero reading measure or the search form width"; **fail direction:
   ARM E**.
2. **The horizontal padding at small viewports.** PRESERVED. Every band keeps `mx-auto`,
   `px-4`, `sm:px-6`, `lg:px-8`; only the cap token changed, and a cap cannot bind at a
   viewport narrower than itself. Asserted per band by the padding ledger; **fail direction:
   ARM D**. The same ledger runs on both rails (the footer's adds `py-12`).
3. **The landing's recorded CLS position.** PRESERVED BY CONSTRUCTION at mobile (the rule is
   unconditional — measured against the generated stylesheet, not argued) and MEASURED at
   desktop by 35-09, with the area-weighting risk above handed over explicitly.

Two goods the plan did not enumerate, preserved and asserted anyway:

4. **The search form's own width.** `max-w-xl`, named in CONTEXT §5 as CLS-sensitive. Covered
   by the same assertion and the same ARM E.
5. **The absence of new DOM nodes.** The hero band is still the direct child of its section
   and the three GSAP hooks still resolve. **Fail direction: ARM F** — which showed this good
   is invisible to every other assertion in the suite.

**BETTERED:** content and chrome now share a left edge, which they did not before, and they
say so in a way a test and a browser can both read.

## Deviations from Plan

### 1. [Instrument defect, self-caught] The full Jest suite took three runs, and the reason is the finding

- **Found during:** Task 3, regression re-run (a)
- **Issue:** Run 1 → `FAIL app/dashboard/webhooks/__tests__/delivery-log.test.tsx`, 2 failed /
  1504 passed. Run 2 → a **different** suite, `FAIL app/dashboard/kitchen/__tests__/page.test.tsx`,
  1 failed / 1505 passed. Neither file is in this plan's declared set; both are in **35-04's**.
- **Not assumed — proven.** Each failing suite was re-run in isolation moments later and passed
  (`delivery-log` 7/7 rc=0, including 35-04's own new tier assertions; `kitchen` rc=0). The
  failing suite **moved** between runs, tracking whichever sibling file was mid-save. Wave 3's
  five agents share **one checkout on one branch** — confirmed directly by `git status`, which
  showed 35-03/04/05's dashboard files dirty on disk while this plan ran.
- **Fix:** none applied — nothing was broken. Run 3, after the siblings committed:
  **141 suites / 1506 tests, rc=0.**
- **Why it is recorded:** a single red run here would have read as a regression from this plan.
  A shared checkout makes any full-suite number a measurement of *the wave*, not of *the plan*,
  and the discriminator is the isolation re-run — not the count.

### 2. [Rule 3 — Blocking, out of scope] `docs-freshness.sh` is VOID and cannot measure this plan's drift

- **Issue:** `scripts/docs-freshness.sh` exits **2 (VOID)**, not 1 (drift):
  `VOID: frontend/app/dashboard/onboarding/__tests__/page.test.tsx:652: describe.each multiplies
  every block inside it; this counter cannot resolve that statically`.
- **Attributed by content and by history, not by inference:** `describe.each` at `:652` and
  `:688`, introduced by commit `5f9e39b4` — *test(35-05): assert the Detail tier on all three
  onboarding branches (RED)*. The file is in 35-05's set and no other.
- **Consequence, stated so nobody misreads it:** the counter is failing **closed**, correctly.
  The reading is *unmeasured*, never *no drift*. `check-doc-metrics.sh` passing (37/37) is not
  reassurance — it compares prose to `docs/metrics.json`, and the manifest is what is stale.
- **Fix:** not fixed here. The offending file belongs to a plan running concurrently, and
  `docs/metrics.json` + the three prose docs are all in **35-11's** declared set. This plan's
  contribution was measured directly instead, against the parent of its own first commit
  (`6dd5ec2b`): **+17 Jest blocks** — landing `9→16`, header `6→11`, footer `10→15`. All plain
  `it(` blocks, no `it.each`, no `describe.each`, so the declaration-site count and the executed
  count agree at 17 and neither of the repo's two counters has to resolve a table.
- **Logged:** appended to `deferred-items.md` under 35-07's existing D-35-07-a section, whose
  guess that "35-06 also appears to add blocks" is now a measured number.

### 3. [Instrument defect, self-caught] An empty grep against a stub file

- While first reading the tier out of the server build I probed `.next/server/app/page.js` and
  got `matches=[] rc=1`. **No conclusion was drawn from it.** The file exists but is 1119 bytes
  — a loader stub, not the compiled page; the real code is under `.next/server/chunks/ssr/`.
  The correct chunk was located by a string unique to `app/page.tsx` and gave the 4/1/0 result
  above.
- Recorded because the bad run and a genuine absence are byte-identical in output. "Check the
  path exists before believing its emptiness" is the same class as 35-02's deviation 3.

### 4. [Rule 3 — Blocking] `STATE.md`, `ROADMAP.md`, `REQUIREMENTS.md` and `docs/metrics.json` left untouched

- **Issue:** five agents, one checkout, one branch. A named-path `git add` of a shared file in
  that situation sweeps whatever a sibling has uncommitted on disk into this plan's commit —
  this repository has already had 369 lines of another session's work swept into main that way.
  UIX-09 is also 35-05's and 35-07's requirement, so all three agents would read-modify-write
  the same row.
- **Fix:** none of the four touched. This follows the convention 35-03 established for the wave.
  The truthful requirement text is below for the orchestrator to apply once, after the wave.
- **Every commit stages named paths only** — never `git add .` or `-A` — and each was verified
  after the fact to contain only this plan's files (2 + 2 + 1 + 1), with **zero file deletions**
  (checked with a control commit that does delete a file, so the probe is known to be capable of
  reporting one).

### 5. [Observed, not caused] This plan's `deferred-items.md` content landed in 35-07's commit

- **Found during:** the final metadata commit.
- **What happened:** the three sections this plan appended to the shared
  `deferred-items.md` were **already committed** when it came to stage them — swept into
  `388bfda7 docs(35-07): complete the marketing tier + storefront skeleton-parity plan` by a
  sibling's named-path `git add` of the same shared file.
- **Verified by content, with a control, rather than assumed benign:** commit `388bfda7`
  contains all three markers (`D-35-06-a`, `D-35-06-b`, "Confirmed independently by plan
  35-06") — 3 matches; its **parent** contains 0, so the probe is known to be capable of
  reporting absence; and the on-disk copy is byte-identical to `HEAD`
  (`git diff --quiet` rc=0). **Nothing was lost or altered — only the attribution is wrong.**
- **Why it is recorded rather than corrected:** rewriting a sibling's merged commit to move
  three sections between authors would be a worse trade than an inaccurate blame line, and the
  file's content is what any later reader needs.
- **This is deviation 4's hazard actually occurring**, from the other direction — the shared
  checkout let a sibling's commit absorb this plan's work, rather than the reverse. It is the
  concrete argument for why `STATE.md`, `ROADMAP.md`, `REQUIREMENTS.md` and `docs/metrics.json`
  were left alone: had this plan staged any of them, the same mechanism would have swept a
  sibling's half-written row into a commit claiming to be 35-06's.

### 6. [Rule 3 — Blocking] Three arms beyond the plan's two

- The plan specified ARMs A and B. Four assertions had **no fail direction at all**: the stock
  token's absence, the padding ledger, the hero-measure preservation and the no-new-DOM-node
  structural check. Falsifiability is a standing acceptance criterion on this project, not an
  optional extra.
- ARMs C, D, E and F added, each recorded in both directions. **ARM C and ARM F both changed
  what this plan claims** — C by demonstrating that a half-done rename is invisible to
  everything except the absence assertion, F by demonstrating that a wrapper node is invisible
  to every width assertion in the suite.

## Coverage boundaries, stated rather than implied

- **What is proven here:** the class is APPLIED and the tier DECLARED (jsdom, 91 tests), the
  rule EXISTS at 1280px in the generated stylesheet, and the four declarations plus zero stock
  tokens are present in the **compiled SSR chunk for this route**. What is **not** proven is
  that a band renders at 1280px in a browser. That is 35-08's spec and 35-12's run.
- **This plan tests application, never the value.** Every assertion reads
  `WIDTH_TIER_CLASS.marketing` from the vocabulary module, so it proves the surface applies
  *whatever the vocabulary says*. Only `content-tier.test.tsx`'s derivation assertion proves the
  vocabulary says the right thing. This is 35-02's ARM F property, inherited knowingly.
- **The dish-scroller branch is predicted, not measured** — calibrated against recorded browser
  output, but a model. Named as such above.
- **Desktop CLS is unmeasured by anything on any current tree.** Not "fine" — unmeasured. 35-09.
- **`components/legal/policy-page.tsx` now carries the defect this plan fixed** on five public
  policy pages, and no plan in this phase owns that file. D-35-06-a.
- **SEO: N/A WITH REASON.** `/` is public and indexable, and this was checked rather than
  waved: the change alters no title, description, canonical, Open Graph tag, JSON-LD block,
  sitemap entry or crawlable link. The metadata export and the `shopListStructuredData` call in
  `app/page.tsx` are untouched, and the footer's link graph is unchanged.
- **AI agent-readiness: N/A.** No API surface changed.

## Cited decisions

**ORCH-04 (orchestrator decision, 2026-08-29)** is the whole of this plan and is cited by that
name in `app/page.tsx`, `public-header.tsx`, `public-footer.tsx` and all three test files —
never as "user decision". ORCH-02 bears on this plan's hand-off to 35-09 and is cited there.
ORCH-01/03/05 do not bear on this file set.

## Known Stubs

None. This plan ships no placeholder, no empty state, no TODO and no hardcoded empty value. It
adds no data path, no component and no branch — six existing elements changed one class and
gained one attribute each.

## Threat Flags

None. This plan adds no endpoint, no input, no credential, no data flow and no dependency.

- **T-35-20** (a width change reintroducing horizontal overflow at 390px on the most-measured
  public route) — mitigated **structurally and verifiably**: the marketing rule is emitted with
  no media query, proven by an `@media` probe returning `rc=1` against the generated stylesheet,
  so an unconditional 1280px cap cannot bind against a 390px viewport. The browser verification
  is `public-layout.spec.ts`, which is **per-PR blocking** on this change.
- **T-35-21** (partial band migration) — mitigated by the count assertion, **proven by ARM A**
  in both directions (`Expected length: 4 / Received length: 3`).
- **T-35-22** (rail and content drifting apart again) — mitigated by declaring the SAME tier on
  both rails and all four bands, by the paired prose at each site, and by the "shares its tier
  with the chrome" assertion, **proven by ARM B** (6 → 5).
- **T-35-23** (landing markup / information disclosure) — still `accept`, and re-checked rather
  than inherited: no metadata, structured data, credential or tenant value is touched.
- **T-35-SC** — nothing was installed, so the Package Legitimacy Gate correctly did not run
  rather than being skipped.

ASVS L2 V14: the tier is applied as a utility class, never `style={{ maxWidth }}`, so the CSP's
`'unsafe-inline'` style allowance is not newly relied on.

## For the orchestrator to apply after the wave

**Requirement text, truthful as of this plan:**

- **UIX-07: still in progress.** 35-06 is not in UIX-07's plan list in `REQUIREMENTS.md:261`
  (which names 35-01, 35-02, 35-10, 35-11) even though this plan's frontmatter claims it. The
  requirement is unaffected either way: **35-10 and 35-11 remain**. Not complete.
- **UIX-09: in progress, 1 of 4 plans.** `REQUIREMENTS.md:263` assigns it to 35-05, 35-06, 35-07
  and 35-09. 35-06 has landed the Marketing tier on `/` and on both shared public rails —
  the one surface in the phase whose width value actually changes. **35-05 (Detail), 35-07 (the
  remaining marketing surfaces) and 35-09 (the desktop CLS arm) remain.** Not complete.
- **`docs/metrics.json`:** this plan contributes **+17 jest blocks, +0 jest files**. Regenerate
  with `scripts/docs-freshness.sh --write` after 35-05's `describe.each` VOID is cleared —
  never by arithmetic.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `feef9279` | test | assert the Marketing tier on both public rails before it exists (RED) |
| `0122899b` | feat | declare the Marketing tier on both shared public rails (GREEN) |
| `d6bd553e` | test | assert the Marketing tier on the four landing bands before it exists (RED) |
| `ccf374da` | feat | move the four landing bands to the Marketing tier (GREEN) |

Every commit message was written through a **quoted heredoc** and read back with
`git log -1 --format=%B`. Backticks inside a double-quoted `-m` string execute and silently
drop the phrase; the corruption is invisible at write time.

## TDD Gate Compliance

Both cycles complete and in order: `test(feef9279)` → `feat(0122899b)`, then
`test(d6bd553e)` → `feat(ccf374da)`. Each RED gate was observed failing with recorded output
before its GREEN commit — 9 substantive assertion failures for the rails, 6 for the landing —
and in **both** RED runs a deliberate control passed, which is what makes the failures evidence
rather than noise. No REFACTOR commit was needed; neither implementation required cleanup after
going green.

## Self-Check: PASSED

All 8 claimed files exist on disk; all 4 claimed commit shas resolve. Both halves were run with
a control — a path that must not exist reported MISSING, and `deadbee` reported MISSING — so the
FOUND results are statements about this repository rather than about a check incapable of
failing.
