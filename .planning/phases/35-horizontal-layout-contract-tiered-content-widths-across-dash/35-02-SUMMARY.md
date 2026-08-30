---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 02
subsystem: ui
tags: [tailwind, layout, design-tokens, react, jest, dashboard, contract-test]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 01
    provides: "lib/layout-widths.ts (the numbers + the WidthTier union) and the generated max-w-* tier utilities"
provides:
  - "frontend/components/layout/content-tier.tsx — the ONLY place the three tier class literals exist in the tree"
  - "WIDTH_TIER_CLASS: the tier -> class map, typed Record<WidthTier, string> so a tier cannot gain a member without a class"
  - "ContentTier: the WRAPPER application shape, for surfaces with no existing band element"
  - "the documented IN-PLACE application shape, which every wave-3 plan should prefer"
  - "the Shell tier applied at dashboard-shell.tsx — a declared 1700px cap inherited by all 21 dashboard routes"
  - "retirement of the DEAD container class at the tree's only consumer, closing 35-01's interim fluid-dashboard state"
affects: [35-03, 35-04, 35-05, 35-06, 35-07, 35-08, 35-09, 35-10, 35-11, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "tier-class-literals-in-components: numbers in lib/, class strings in components/, because Tailwind's content globs exclude lib/"
    - "declared-tier-marker: data-width-tier makes 'uncapped' a queryable declaration rather than an absence (ORCH-03)"
    - "displaced-goods ledger as an it.each: every class an edit preserves is asserted by name, not assumed"

key-files:
  created:
    - frontend/components/layout/content-tier.tsx
    - frontend/components/layout/__tests__/content-tier.test.tsx
  modified:
    - frontend/components/dashboard/dashboard-shell.tsx
    - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/REQUIREMENTS.md

key-decisions:
  - "The tier class literals live in components/layout/content-tier.tsx and nowhere else; the tests assert the DERIVATION (max-w- + tier key) rather than restating the strings, so the single-occurrence property survives its own test suite"
  - "The Shell tier is applied IN PLACE on the existing band div — no wrapper, no new DOM node — because that element wraps every authenticated route (T-35-06)"
  - "cn() joins the band's classes; twMerge was measured to drop none of the eight tokens, so the joiner change is inert rather than assumed inert"
  - "The removal of the shed class is asserted with classList.contains (a token match) and never a substring, because the naive search matches 57 files in this tree"

patterns-established:
  - "Every falsifiability control ships in the same suite as the thing it controls: an absence assertion is paired with a presence assertion through the same code path"
  - "A restore is verified by CONTENT and by blob identity (git diff --quiet), then by a re-run to green — never by git diff --stat"

requirements-completed: []
requirements-progressed: [UIX-07, UIX-08]

# Metrics
duration: 50min
completed: 2026-08-29
---

# Phase 35 Plan 02: Tier Vocabulary + the Shell Tier Summary

**The contract now has a vocabulary the DOM can be queried on, and the dashboard band — the one line all 21 authenticated routes inherit their width from — declares the Shell tier instead of carrying a class that had stopped generating anything.**

## Performance

- **Duration:** ~50 min
- **Tasks:** 3 of 3
- **Files modified:** 9 (2 created, 7 modified)
- **Commits:** 5

## What changed

### Task 1 — the tier vocabulary (TDD)

`frontend/components/layout/content-tier.tsx` exports `WIDTH_TIER_CLASS`, a
`Record<WidthTier, string>` typed against the union 35-01 declared, and `ContentTier`, the
wrapper application shape. This is the **only** place in the tree where the three tier utility
strings appear as literals — measured, with a control:

```
rg -uu -n max-w-shell     app components -> components/layout/content-tier.tsx:85   rc=0  (1 hit)
rg -uu -n max-w-detail    app components -> components/layout/content-tier.tsx:87   rc=0  (1 hit)
rg -uu -n max-w-marketing app components -> components/layout/content-tier.tsx:88   rc=0  (1 hit)
CONTROL  rg -uu -c mx-auto app components -> 30 files
```

The control is load-bearing: without it "1 hit" is a statement about the pattern, not the tree.

The test file writes **no full class literal**. It asserts each capped tier's class is
``max-w-${tier}`` — the derivation from that tier's own `theme.extend.maxWidth` key — which is
both a stronger assertion than restating the string and what keeps the single-occurrence
property from being destroyed by the suite that guards it. That mattered: had the test restated
the strings, plan 35-10's static gate would have been counting its own test file.

`index` maps to the empty string on purpose (ORCH-03, orchestrator decision 2026-08-29) and the
tier is still written into the DOM, because "uncapped" implemented as an absence is a contract
no assertion can distinguish from a forgotten cap (PATTERNS.md F-3).

### Task 2 — the Shell tier at the one call site (TDD)

`dashboard-shell.tsx:55` was still carrying the `container` class after 35-01 disabled the core
plugin — so on the branch the dashboard band was **fluid, uncapped**. That is now closed. The
edit is IN PLACE per the phase doctrine: the class is swapped on the element that already
existed and `data-width-tier="shell"` is added beside it. **No DOM node was added**, so nothing
in the existing CLS, bounding-box, motion or scroll-reveal behaviour has a new element to
notice.

The displaced-goods ledger is recorded at the site in prose (auto margins already duplicated by
the `mx-auto` on the same element; the 2rem padding already dead, a components-layer rule losing
to later equal-specificity padding utilities, read out of the generated CSS ordering rather than
inferred; `width:100%` equivalent under a block parent with border-box preflight) and made
executable as an `it.each` over all seven preserved classes.

The joiner change was **measured, not assumed inert**:

```
$ node -e "twMerge(clsx('mx-auto','max-w-shell','p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8 dark:text-slate-100'))"
EMITTED: mx-auto max-w-shell p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8 dark:text-slate-100
token count: 8
```

Eight tokens in, eight out, in order. `tailwind-merge` 3.6.0 does not recognise `max-w-shell` as
a member of its `max-w` conflict group (the tier key is not a t-shirt size), so it passes through
untouched — **which also means twMerge will NOT resolve a caller's `max-w-*` against a tier
class.** Wave 3 should not rely on override-by-merge.

### Task 3 — the arms

Seven arms, all recorded in both directions. Tasks 1 and 2 were **committed before any arm ran**,
so no restore could eat a fix.

## Verification — every criterion in both directions

### The TDD gates themselves (fail direction by construction)

| Suite | RED | GREEN |
|---|---|---|
| `content-tier.test.tsx` | rc=1, `Cannot find module '../content-tier'` | rc=0, **19/19** |
| `dashboard-shell.test.tsx` | rc=1, **13 failed / 7 passed** | rc=0, **20/20** |

The 7 that passed in the dashboard RED run are the six pre-existing cases **plus the
non-vacuity control**, which is the point — it proves the token instrument works before any
absence assertion is trusted, the same shape 35-01's CSS suite used.

### Opening clean arm

```
npx jest components/layout components/dashboard/__tests__/dashboard-shell.test.tsx \
         components/dashboard/__tests__/shop-switcher.test.tsx
3 passed / 67 tests   rc=0
git diff --quiet (both source files)   rc=0
```

### ARM A — the shed class put back on the band (plan-specified)

```
BROKEN    "container", added as a cn() argument beside the tier class
          ✕ no longer carries the shadcn width class
              Expected: false / Received: true
          1 failed, 19 passed   rc=1
RESTORED  shed class as a cn arg: matches=[] rc=1 | WIDTH_TIER_CLASS.shell present rc=0
          worktree==HEAD rc=0 | 20/20 rc=0
```

Precisely isolated — only the removal assertion moved; the control and all seven preserved-class
cases stayed green.

### ARM B — the index tier given a cap (plan-specified)

```
BROKEN    index: "max-w-shell"
          ✕ maps the index tier to no class at all
              Expected: "" / Received: "max-w-shell"
          ✕ renders no max-width class for the index tier
              Expected pattern: not /max-w-/
              Received string:  "mx-auto max-w-shell"
          2 failed, 17 passed   rc=1
RESTORED  index: "" rc=0 | max-w-shell occurrences in module back to 1 (the arm made it 2)
          worktree==HEAD rc=0 | 19/19 rc=0
```

Both halves fired — the map value and the rendered DOM — and the "can see a max-width class"
control stayed green throughout, which is what makes the absence an assertion rather than a
tautology.

### ARM C — a preserved class dropped (plan-specified)

```
BROKEN    "p-4 pb-20 sm:pb-20 md:pb-8 dark:text-slate-100"   (sm:p-8 removed)
          ✕ keeps the sm:p-8 declaration it already had
              Expected the element to have class: sm:p-8
              Received: mx-auto max-w-shell p-4 pb-20 sm:pb-20 md:pb-8 dark:text-slate-100
          1 failed, 19 passed   rc=1
RESTORED  full class string present by content rc=0 | worktree==HEAD rc=0 | 20/20 rc=0
```

### ARMs D–G — added beyond the plan (falsifiability is a standing criterion)

The plan specified three arms. Four more assertions had no recorded fail direction, and one of
them (ARM F) turned out to expose a real coverage boundary.

| Arm | Broken input | Result |
|---|---|---|
| **D** map completeness (T-35-05) | deleted the `index` entry | `tsc` rc=2 — `TS2741: Property 'index' is missing … but required in type 'Record<WidthTier, string>'`; **and** 2 failed / 17 passed, the diff naming the missing key. Both signals matter: the type catches a MISSING key, the runtime test catches a STRAY one |
| **E** client directive | added a real `"use client"` at the top | 1 failed / 18 passed. The printed `Received string` shows the docblock stripped to nothing with only the directive and the imports left — proving the check is not satisfied by its own prose |
| **F** plausible drift | `shell: "max-w-7xl"` (the tree's existing idiom) | 1 failed / 38 passed — and **the whole dashboard-shell suite stayed green.** See "Coverage boundaries" below; this is the arm worth reading |
| **G** the tier declaration | removed `data-width-tier` from the band, left the class | 13 failed / 7 passed, `no element declares the shell width tier`. ORCH-03's marker is asserted in its own right, not merely as a lookup convenience |

### Closing clean arm

```
npx jest components/layout components/dashboard
12 suites / 179 tests   rc=0
git diff --quiet (both source files)   rc=0
```

The closing arm is the only proof the restores landed. Verified by content (unique token per
file) **and** by blob identity, never by `git diff --stat`.

### The class-context search (verification item 5), with its own fail direction

```
PATTERN  (className|class|cn\()[^\n]*["'`\s]container[\s"'`]

CONTROL 1  naive substring `rg -uu -c container app components` -> 57 files
           (PATTERNS F-1's warning, reproduced)
CONTROL 2  the class-context pattern WITH tests included -> rc=0, 3 hits, all in
           dashboard-shell.test.tsx: the token assertion and the deliberate control probe
EVIDENCE   the same pattern over shipped source (-g '!**/__tests__/**')
           -> matches=[] rc=1
```

Control 2 is what makes the empty result evidence: the pattern demonstrably matches when the
class is there. The three test-file hits are deliberate — one is the assertion, two are the
non-vacuity probe that proves the assertion can fail. **Wave-3 and 35-10 note:** a
class-context gate for this token must exclude `__tests__/`, or it will red on the very control
that makes the check honest.

### The build, and the value read out of the shipped stylesheet

`npm run build` **rc=0**. This is the first build in which the shell utility is reachable from
scanned content, so the first whose CSS can contain the rule.

```
stylesheet: .next/static/chunks/3otvctlq2qoxs.css   96686 bytes

EVIDENCE   .max-w-shell{max-width:1700px}                       rc=0
           .max-w-detail{max-width:1100px}                      rc=0
           .max-w-marketing{max-width:1280px}                   rc=0
CONTROL    occurrences of `max-width:` in the file               24
CONTROL    @media wrapping the shell rule -> []                 rc=1  (unconditional, correct)
CONTROL    `max-width:1400px` or `.container{`   -> []          rc=1  (absent, correct)
```

And the tier declaration survives the build into the shipped client bundle, not just jsdom:

```
rg -uu -l data-width-tier .next/static/chunks -> .next/static/chunks/3oh_vtlkdm_qp.js   rc=0
rg -uu -o '"data-width-tier":"shell"'         -> "data-width-tier":"shell"              rc=0
CONTROL   '"data-width-tier":"prose"'         -> []                                     rc=1
```

**A finding wave 3 needs:** `max-w-detail` and `max-w-marketing` are in the stylesheet *already*,
even though **no surface applies either of them**. Tailwind's scanner extracts candidate strings
from file content, and the literals live in `content-tier.tsx`, which is scanned. Two
consequences: (a) wave-3 plans need no config change to use those tiers, and (b) **"the rule is
in the shipped CSS" is NOT evidence that any surface applies it** — a 35-10-style gate phrased
that way would be vacuous.

### Repo-wide gates

| Check | Result |
|---|---|
| Full Jest suite | **140 suites / 1393 tests**, rc=0 — no existing test regressed |
| `npm run build` | rc=0 |
| `npx tsc --noEmit -p tsconfig.json` | rc=0 |
| `npm run lint` | rc=0, **0 errors** / 34 pre-existing warnings; scoped run over the four touched files rc=0 with no output |
| `scripts/docs-freshness.sh` | rc=1 → regenerated → rc=0 |
| `scripts/check-doc-metrics.sh` | rc=1 (10 named FAILs) → prose updated → rc=0, 37/37 claims |
| `scripts/check-e2e-typecheck.sh` | rc=0, 30 e2e files clean |
| `scripts/check-gate-enforcement.sh` | rc=0, 39 gates |
| `scripts/check-branch-behind-base.sh` | rc=0, 14 ahead / **0 behind** origin/main |

## Deviations from Plan

### 1. [Rule 3 - Blocking] Four arms the plan did not specify

- **Found during:** Task 3
- **Issue:** The plan specified arms A/B/C, which between them cover the removal assertion, the
  index-tier claim and the displaced-goods ledger. Four other assertions — map completeness
  (T-35-05's stated mitigation), the no-client-directive check, the class-derivation check and
  the tier marker itself — had **no fail direction at all**. An assertion observed only passing
  may be incapable of failing, which is a standing acceptance criterion on this project, not an
  optional extra.
- **Fix:** ARMs D–G above, each with real output in both directions.
- **Why it was worth it:** ARM F changed what this plan claims (see Coverage boundaries).

### 2. [Rule 3 - Blocking] Both halves of the docs loop, not in the plan's file set

- **Issue:** The new Jest blocks red `docs-freshness.sh` (rc=1) and then `check-doc-metrics.sh`
  (rc=1, 10 named FAILs across README/CLAUDE/AGENTS). Same class as 35-01 deviation 3.
- **Fix:** Regenerated via `scripts/docs-freshness.sh --write` — never hand-edited, never by
  arithmetic — then the prose claims updated to match. `jest_blocks` 1361→1394, `jest_files`
  139→140, total 3345→3378.
- **Commit:** `f5c1ec7e`

### 3. [Instrument defect, self-caught] An `ls` glob that pointed at the wrong directory

While first reading the built CSS I used `ls .next/static/css/*.css`, following 35-01's summary,
and got nothing. The `[ -z ]` guard fired and printed `VOID: no stylesheet found` with `exit 2`
rather than reporting a clean absence — so **no conclusion was drawn from the bad run**. The
stylesheet on this tree is under `.next/static/chunks/`, found with `find`, and every CSS
assertion above was run against the located file. This is the guard 35-01 added after its own
empty-variable incident doing exactly its job.

### 4. [Instrument defect, self-caught] A control that counted lines, not occurrences

The first `max-width:` control printed `1`, because `rg -c` counts matching **lines** and the
minified stylesheet is one line — a control reporting `1` on a file with 24 matches is
indistinguishable from a broken instrument. Re-run as `rg -o … | wc -l`, giving 24. Recorded
because the wrong form would have looked like a passing control.

## Coverage boundaries, stated rather than implied

- **ARM F is the one to read.** Setting `shell: "max-w-7xl"` — a *plausible* drift, since 7xl is
  this tree's existing max-width idiom — left the **entire dashboard-shell suite green (38
  passed)**. That suite reads `WIDTH_TIER_CLASS.shell` from the vocabulary module, so it proves
  the band applies *whatever the vocabulary says*, and only `content-tier.test.tsx`'s derivation
  assertion proves the vocabulary says the right thing. Neither suite alone would catch a wrong
  cap on the dashboard. Wave-3 plans that assert against `WIDTH_TIER_CLASS.<tier>` inherit this
  property: they are testing application, never the value.
- **No browser on any current tree measures the rendered dashboard band.** Per-PR CI runs only
  `public-layout.spec.ts` + `public-a11y.spec.ts` (`ci-cd.yaml:531`); the Shell/Index/Detail
  browser assertions belong to the nightly lane, and **#683 records that lane as dark**. The
  honest phrasing for those is *"covered by a spec that no current tree executes"*, never
  *"covered nightly"*. What this plan proves is that the class is APPLIED (jsdom) and that the
  rule EXISTS at 1700px (shipped CSS). It does not prove a band renders at 1700px in a browser.
- **`e2e/dashboard-mobile.spec.ts` is not evidence about this change.** It measures `main`, which
  sits OUTSIDE the edited element (PATTERNS.md B-4). A green mobile run there says nothing about
  the band, and this summary does not claim otherwise.
- **Mobile identity remains structural, not observed.** The shell rule is emitted with no media
  query (asserted above, rc=1 on the `@media` probe), so it cannot bind against a 390px parent.
  That is a property of the stylesheet; the browser arm belongs to later plans.
- **CLS/LCP not measured here.** The dashboard is authenticated and outside every perf budget in
  `e2e/perf-budgets.ts`, all of which target public surfaces. ORCH-02's desktop CLS arm belongs
  to 35-09 and concerns `/`.
- **SEO: N/A.** No public surface, metadata, route or markup semantics changed.
- **AI agent-readiness: N/A.** No API surface changed.
- **Web performance:** the change is one class swap and one attribute on an existing element. No
  new DOM node, no new dependency, no image, no bundle growth beyond the attribute string. The
  widened band exposes more of an already-rendered table; it renders no extra rows or columns.

## Cited decisions

**ORCH-03 (orchestrator decision, 2026-08-29)** is the reason `index` maps to the empty string
while still declaring itself in the DOM; it is cited in `content-tier.tsx`'s docblock and in the
test that asserts it. ORCH-01/02/04/05 do not bear on this plan's file set — all four concern
public/marketing surfaces this plan does not touch.

## Known Stubs

None. This plan ships no placeholder, no empty state and no TODO. `ContentTier` is exported
without a consumer, which is deliberate rather than a stub: it is the WRAPPER shape, and the
doctrine says wave-3 plans should prefer the IN-PLACE shape and reach for it only where no band
element exists. Its behaviour is fully asserted regardless of whether a surface uses it.

## Threat Flags

None. This plan adds no endpoint, no input, no credential, no data flow and no dependency.

- **T-35-05** (a tier in the union with no class, rendering uncapped and silently) — mitigated by
  `Record<WidthTier, string>` plus the key-set test, **proven by ARM D in both directions**.
- **T-35-06** (the shared band wrapping every authenticated route) — mitigated by an IN-PLACE
  edit adding no DOM node, by the seven enumerated preserved-class assertions, and **proven by
  ARM C**.
- **T-35-07** (tier as an injected value) — still `accept`, and still true: `tier` is a
  compile-time member of a closed union. `ContentTierProps` records in its own docblock that this
  disposition must be revisited if a later plan makes tier dynamic from request data.
- **T-35-08** (a widened band disclosing more) — still `accept`: the columns are chosen by the
  component and gated by the API and Postgres RLS, identical at every width.
- **T-35-SC** — nothing was installed, so the Package Legitimacy Gate correctly did not run
  rather than being skipped.

ASVS L2 V14: the tier is applied as a utility class, never `style={{ maxWidth }}`, so the CSP's
`'unsafe-inline'` allowance is not newly leaned on.

## What wave 3 (35-03 / 04 / 05 / 06 / 07) must know

1. **Import the vocabulary, never the string.** `import { WIDTH_TIER_CLASS } from
   "@/components/layout/content-tier"`. Writing `max-w-detail` into a surface file breaks the
   single-occurrence property that plan 35-10's gate reads.
2. **Prefer IN PLACE.** If the surface already has a band carrying `mx-auto max-w-*`, swap the
   class and add `data-width-tier` on that same element. Only reach for `<ContentTier>` when
   there is no band element — it adds a DOM node, which is the shape that can move CLS and
   bounding-box assertions.
3. **`max-w-detail` and `max-w-marketing` are already in the shipped CSS** (see above), so
   applying them needs no config change — and correspondingly, "the rule is in the stylesheet" is
   not evidence your surface applies it. Assert the applied class or the rendered attribute.
4. **twMerge will not resolve a caller's `max-w-*` against a tier class.** The tier keys are not
   t-shirt sizes, so tailwind-merge treats them as unknown and keeps both. If a surface needs to
   override a tier, remove the tier rather than layering over it.
5. **A class-context gate for the shed `container` token must exclude `__tests__/`** — the
   dashboard-shell suite deliberately contains three hits, one assertion and two control probes.
6. **Do not touch `dashboard-shell.tsx`'s mobile top bar or the `max-w-[55%]` switcher clamp.**
   Both are now asserted by `dashboard-shell.test.tsx` (the bar is proven to be a *sibling* of
   the band, not a descendant), and `shop-switcher.test.tsx` reasons about the clamp at 375px.
7. **Say "covered by a spec that no current tree executes", never "covered nightly"** (#683).
8. **Expect both docs gates to red** on any plan that adds Jest blocks. Regenerate with
   `scripts/docs-freshness.sh --write`, then fix the prose in README/CLAUDE/AGENTS. Never
   arithmetic.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `ebc32def` | test | assert the tier vocabulary before it exists (RED) |
| `6e8c5d7e` | feat | the tier vocabulary — one class map, one wrapper (GREEN) |
| `722746f3` | test | assert the Shell tier on the dashboard band before it exists (RED) |
| `669470cd` | feat | apply the Shell tier at the dashboard's one width call site (GREEN) |
| `f5c1ec7e` | docs | regenerate test metrics for the tier vocabulary suite |

## TDD Gate Compliance

Both cycles complete and in order: `test(ebc32def)` → `feat(6e8c5d7e)`, then
`test(722746f3)` → `feat(669470cd)`. Each RED gate was observed failing with recorded output
before its GREEN commit — the first as a module-resolution failure, the second as 13 substantive
assertion failures with the non-vacuity control already passing. No REFACTOR commit was needed;
neither implementation required cleanup after going green.

## Requirement progress — recorded truthfully

- **UIX-07: in progress, 2 of 4 plans.** 35-01 declared the contract; 35-02 gave it a vocabulary
  and applied one tier. 35-10 (the scattered-literal gate) and 35-11 (the documented standard)
  remain. **Not complete.**
- **UIX-08: still not started on its own limbs.** The `data-width-tier` marker and the
  `index` → empty-string entry that UIX-08 names now exist and are asserted in both directions,
  but **no index surface declares the tier yet** — that is 35-03/35-04's work and 35-08's spec.
  The mechanism shipping is not the requirement shipping.

## Self-Check: PASSED

All 10 claimed files exist on disk; all 5 claimed commit shas resolve. Run with a control
(`deadbee` correctly ABSENT), so the FOUND results are about this repository rather than about a
check incapable of failing.
