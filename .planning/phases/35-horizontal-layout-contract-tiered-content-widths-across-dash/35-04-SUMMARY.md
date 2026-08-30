---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 04
subsystem: ui
tags: [layout, design-tokens, react, jest, dashboard, width-tier, falsifiability]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 02
    provides: "WIDTH_TIER_CLASS, the data-width-tier declaration shape, and the Shell tier on the dashboard band these eight surfaces sit inside"
provides:
  - "the Index tier declared on the eight remaining dashboard surfaces: finance, marketing, kitchen, staff, onboarding approvals, media review, the webhooks list and the webhooks delivery log"
  - "twenty declarations, one per RENDER BRANCH rather than one per page, so no loading/error/forbidden branch paints undeclared"
  - "the A-3 exception written at the delivery-log site, addressed to the reader who would undo it"
  - "the A-8 low-confidence flag written at the approvals site and routed to plan 35-13"
  - "19 jsdom cases across 7 mounted suites, each declaration assertion recorded failing before it passed"
affects: [35-08, 35-10, 35-11, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "per-branch tier declaration: every early return gets the marker, sub-components rendered INSIDE the root do not (nesting a cap inside a cap is the defect)"
    - "capTokens: a classList TOKEN filter for width caps, never a substring, paired with a non-vacuity probe built from WIDTH_TIER_CLASS rather than a written-out class string"
    - "the resolution lives at the site in prose, described rather than spelled, so plan 35-10's occurrence gate is not satisfied by a comment"

key-files:
  created: []
  modified:
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/marketing/page.tsx
    - frontend/app/dashboard/__tests__/marketing-status-filter-honesty.test.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/app/dashboard/kitchen/__tests__/page.test.tsx
    - frontend/app/dashboard/staff/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
    - frontend/app/dashboard/onboarding/approvals/page.tsx
    - frontend/app/dashboard/onboarding/approvals/__tests__/page.test.tsx
    - frontend/components/dashboard/media/ReviewQueue.tsx
    - frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx
    - frontend/app/dashboard/webhooks/page.tsx
    - frontend/app/dashboard/webhooks/__tests__/webhooks-page.test.tsx
    - frontend/app/dashboard/webhooks/[id]/page.tsx
    - frontend/app/dashboard/webhooks/__tests__/delivery-log.test.tsx

key-decisions:
  - "A-3: /dashboard/webhooks/[id] takes Index despite being a bracketed detail route, because the reason to open it is a wide timestamp-heavy log table; narrowing it would make that table scroll MORE than at today's band. The exception is written at the site, not only in planning docs"
  - "A-8: approvals is Index and is the phase's lowest-confidence call; the honest reason is recorded — a silent default would have shipped it as Index anyway, so choosing it deliberately leaves a marker a reader can argue with"
  - "A-9: the media review declaration goes on ReviewQueue.tsx, the component that owns the band, not the thin page file — putting it on the page would have wrapped a component that already has a root"
  - "The tier is declared on EVERY render branch, not just the loaded one, and NOT on sub-components rendered inside the root — nesting a cap inside a cap is the double-cap defect"
  - "No chart-band cap added to finance: A-4 resolves that additively and only if plan 35-12's 2560 capture shows it is needed"
  - "docs/metrics.json and the prose doc gates are NOT touched: plan 35-11 owns them, and five plans regenerating the same manifest in one shared working tree is a guaranteed collision"

patterns-established:
  - "An absence assertion that already holds before the change is a PRESERVATION assertion, not evidence of the change — it is labelled as such and its fail direction is armed separately"
  - "A git identity check must be run from the repo root: the same pathspec under a cd into a subdirectory matches nothing and passes vacuously"

requirements-completed: []
requirements-progressed: [UIX-08]

# Metrics
duration: 55min
completed: 2026-08-29
---

# Phase 35 Plan 04: Index Tier, Part 2 Summary

**The eight remaining dashboard surfaces now declare the Index tier — twenty declarations, one per render branch — and the two calls that could plausibly be "corrected" back carry their reasoning at the site rather than in a planning document nobody will read at the moment they matter.**

## Performance

- **Duration:** ~55 min
- **Tasks:** 3 of 3
- **Files modified:** 15 (0 created)
- **Commits:** 5

## What changed

### Task 1 — finance, marketing, kitchen, staff (TDD)

Ten declarations across four surfaces. **Attribute only** — no class, no wrapper node — per the
in-place shape 35-02 documented. `WIDTH_TIER_CLASS.index` is the empty string, so a mechanical
application cannot narrow anything, which is why this is safe to apply eight times without
measuring eight layouts.

The declaration count is **per render branch, not per page**:

| Surface | Branches | Declarations |
|---|---|---|
| finance | loading / forbidden / loaded | 3 |
| marketing | promo-loading / announcements-loading / loaded | 3 |
| kitchen | loaded only (#536 removed the loading early-return) | 1 |
| staff | `StaffLoading` skeleton / forbidden / loaded | 3 |

Sub-components rendered *inside* a root — `KdsBoardSkeleton`, the webhooks `ActionButtons`,
approvals' `Header` — were deliberately **not** tiered. Nesting a declaration inside a declared
band is the double-cap defect, and for a tier whose class is the empty string it would also be a
marker no assertion could interpret.

### Task 2 — approvals, media review, both webhooks surfaces (TDD)

Ten more declarations. Two sites carry more than a one-liner:

**A-3, the delivery log**, gets the exception written out and addressed to the reader who would
undo it. It is a bracketed `[id]` route deliberately tiered unlike the other bracketed routes;
without the note, the next reader sees an inconsistency and fixes it. The note says why the fix
would be a regression: the page's body is a wide multi-column timestamp-heavy table with its own
scroll region, and narrowing it to the reading width would make that table scroll **more** than
it does at today's band.

**A-8, approvals**, records that it is the phase's lowest-confidence tier call, that it is flagged
for human verification in plan 35-13, and the honest reason Index was chosen — a silent default
would have shipped it as Index regardless, so choosing it deliberately is the truthful version of
the same outcome and leaves something a reader can argue with instead of a silence they cannot.

**A-9, media review**: the declaration is on `ReviewQueue.tsx`, the component that owns the band,
not on the thin page file. The site also records that this surface owns `e2e/media-review-320.spec.ts`
— the tightest viewport in the suite — and that the Index tier being inert at 320px is *reasoned,
not run*, with the run deferred to plan 35-12.

### Task 3 — the arms

Tasks 1 and 2 were **committed before any arm ran**, so no restore could eat a fix.

## Verification — every criterion in both directions

### The TDD gates (fail direction by construction)

| Suite set | RED | GREEN |
|---|---|---|
| Task 1: kitchen + staff + marketing + finance | rc=1, **6 failed / 78 passed** | rc=0, **84 / 84** |
| Task 2: webhooks ×2 + approvals + media | rc=1, **9 failed / 28 passed** | rc=0, **37 / 37** |

Every RED failure printed `Received: null` against `data-width-tier` — i.e. the pages were
genuinely undeclared, not declared-wrongly.

**Which RED cases passed, and why that is stated rather than glossed.** Four of the new cases
passed in the RED direction:

- the four `capTokens(root)).toEqual([])` no-cap assertions — the pages had no cap before the
  change either, so these are **preservation** assertions, not change detectors. Armed in ARM B;
- the webhooks displaced-goods control (the responsive card/table split) — preservation. Armed in ARM C;
- the delivery log's `not.toBe("detail")` case — `null` is not `"detail"`, so **it is incapable of
  failing on an undeclared page** and is not evidence on its own. It is paired with the positive
  declaration assertion above it and armed in ARM A.

### Opening clean arm

```
git diff --quiet -- <all 15 files>            rc=0   (run from the REPO ROOT — see Deviation 2)
index declarations across the 8 surfaces      20
npx jest <this plan's 9 suites>               9 passed / 121 tests   rc=0
```

### ARM A — the delivery log mis-tiered to the reading tier (plan-specified)

The value chosen deliberately because A-3 is where a wrong tier would survive longest: both tiers
render plausibly and the mistake reads as a consistency fix.

```
BROKEN    <div data-width-tier="detail" className="space-y-6">      (line 289)

  ✕ declares the index width tier, with no cap of its own, on the delivery log's root band
      Expected the element to have attribute:
        data-width-tier="index"
      Received:
        data-width-tier="detail"

  ✕ takes the index tier and NOT the reading tier its route shape would imply
      Expected: not "detail"

  2 failed, 5 passed   rc=1

RESTORED  wrong-tier matches=[] rc=1 | index declarations=3 | worktree==HEAD rc=0 | 7/7 rc=0
```

Both halves fired, which is the point of the pair: the positive assertion catches *any* wrong
value, and the negative one names the specific wrong value a route-shape correction would reach for.

### ARM B — the tree's existing max-width idiom layered over the kitchen tier

The *plausible* drift, not an implausible one: `max-w-7xl` is this tree's dominant width idiom, so
this is the shape a later edit would actually take. It also exercises 35-02's finding that twMerge
will not resolve a caller's cap against a tier class — both would survive.

```
BROKEN    className="space-y-6 max-w-7xl"                            (line 627)

  ✕ adds no width cap of its own to the board's root band
      - Expected  - 1
      + Received  + 3
      - Array []
      + Array [
      +   "max-w-7xl",

  1 failed, 51 passed   rc=1

RESTORED  max-w-7xl matches=[] rc=1 | index declarations=1 | worktree==HEAD rc=0
```

### ARM C — the displaced good removed (webhooks responsive split)

```
BROKEN    className="hidden overflow-x-auto"        (sm:block dropped, line 322)

  ✕ leaves the responsive card/table split untouched while declaring the tier
      Expected the element to have class:
        sm:block
      Received:
        hidden overflow-x-auto

  1 failed, 5 passed   rc=1

RESTORED  className="hidden overflow-x-auto sm:block" present by content | worktree==HEAD rc=0
```

This matters because a widened band changes **when** the table overflows, and this split is what
keeps the surface card-stacked at 375px (COMMS-06).

### ARM D — one branch's declaration removed, the others left in place

The arm that proves the per-branch assertions are independent — i.e. that a single missing branch
cannot hide behind the loaded root passing.

```
BROKEN    the tier removed from StaffLoading's root only
          index declarations now 2 (was 3)

  ✕ declares the same tier on the skeleton branch, so the first paint is not undeclared

  1 failed, 18 passed   rc=1
```

Precisely isolated: the loaded-root and access-denied cases stayed green, so the failure is
attributable to exactly the branch that lost its declaration.

```
RESTORED  index declarations=3 | StaffLoading root carries the tier (read back by content)
          worktree==HEAD rc=0
```

### Closing clean arm

```
git diff --quiet -- <all 15 files>       rc=0   (repo-root cwd)
index declarations                       20     (unchanged from the opening arm)
npx jest app/dashboard components/dashboard/media
                                         23 suites / 282 tests   rc=0
npx tsc --noEmit -p tsconfig.json        rc=0
```

The closing arm is the only proof the four restores landed. Verified by **content** (a unique
token per arm) and by **blob identity**, never by `git diff --stat`.

### The type-check, shown able to fail before it was trusted

`tsc` returning rc=0 with no output is indistinguishable from a misconfigured project that
compiles nothing, so it was run against a deliberately broken input **inside this plan's own file
set**:

```
BROKEN    className={NOT_A_SYMBOL}   in components/dashboard/media/ReviewQueue.tsx
          components/dashboard/media/ReviewQueue.tsx(217,45): error TS2304:
            Cannot find name 'NOT_A_SYMBOL'.
          rc=2
RESTORED  arm token matches=[] rc=1 | index declarations=2 | worktree==HEAD rc=0 | tsc rc=0
```

That also proves the type-check reaches the files this plan edited, which "rc=0 on the whole
project" alone does not.

### The resolution written at each site, with a control

```
EVIDENCE   'WIDTH TIER' found in all 8 surface files
             finance:114  marketing:682  kitchen:606  staff:397
             approvals:256  ReviewQueue:177  webhooks:247  webhooks/[id]:228
CONTROL    the same search over app/dashboard/customers/page.tsx (a dashboard page this
           plan does not own) -> matches=[] rc=1   (a clean absence, not rc=2 VOID)
```

The first control I reached for named `app/dashboard/settings/page.tsx`, which **does not exist** —
`rg` returned rc=2 with an IO error, i.e. VOID, not "clean". No conclusion was drawn from it; the
control was re-run against a file proven to exist. A missing-file control would have reported the
absence I wanted while proving nothing.

### Attribution — ORCH-03 cited correctly at every site

```
files citing 'ORCH-03 (orchestrator ...)'          8 / 8
files carrying the FALSE 'user decision' phrasing  0 / 8
CONTROL  the false-attribution search against a probe containing
         "ORCH-03 (user decision, 2026-08-29)"     rc=0   (the search CAN match)
```

CONTEXT.md §4b is explicit that these five were resolved by the **orchestrator** and that citing
them as user decisions is a false attribution. The 0/8 above is evidence rather than a claim
because the search is shown matching the phrasing it forbids.

### Lint

```
npx eslint <the 15 touched files>   rc=0, 0 errors, 3 warnings
```

All three warnings are pre-existing (`ordersPayload` unused, `itemNames` unused, one stale
`eslint-disable`). Attributed rather than assumed:

```
lines this plan's 4 commits ADD containing 'ordersPayload':      0
lines this plan's 4 commits ADD containing 'itemNames':          0
lines this plan's 4 commits ADD containing 'set-state-in-effect': 0
CONTROL   lines added containing 'data-width-tier':             37
```

The control is load-bearing — without it, three zeros are a statement about the grep.

## Deviations from Plan

### 1. [Rule 3 — Blocking, cross-plan] `scripts/docs-freshness.sh` is VOID on the shared branch, and not because of this plan

- **Found during:** Task 3 close
- **Issue:** The gate exits **rc=2 (VOID)**, not rc=1:
  ```
  ERROR: count-test-blocks.mjs could not count family 'jest' (rc=2):
  VOID: frontend/app/dashboard/onboarding/__tests__/page.test.tsx:652:
        describe.each multiplies every block inside it; this counter cannot
        resolve that statically
  ```
  So the docs manifest **cannot currently be regenerated at all** on
  `feature/35-horizontal-layout-contract`, by any plan.
- **Attribution, measured:** the offending file is `app/dashboard/onboarding/__tests__/page.test.tsx`
  (plan 35-05's file, **not** `onboarding/approvals/__tests__/page.test.tsx`, which is mine), and
  the construct arrived in commit `5f9e39b4 test(35-05): assert the Detail tier on all three
  onboarding branches (RED)`. A search for `describe.each|it.each|test.each` across **all seven**
  test files this plan touched returns `matches=[] rc=1`; the same pattern over 35-05's file
  returns three hits at lines 652/688/689 — so the empty result is about my files, not the pattern.
- **Action:** none taken here. That file is outside this plan's declared set, and `describe.each`
  is a legitimate construct — the counter's POLICY is what needs extending. **Plan 35-11 owns the
  docs loop and cannot run until this is resolved.** Raised here so it is not discovered at the
  end of the wave.
- **This plan's contribution to the delta, for 35-11:** **19 new literal `it(` blocks** —
  kitchen 2, marketing 3, staff 3, approvals 3, ReviewQueue 2, webhooks list 3, delivery log 3.
  Counted from the diff, never by arithmetic.

### 2. [Instrument defect, self-caught] A `git diff --quiet` that could not fail

- **Found during:** the ARM B restore
- **Issue:** `cd frontend && … git diff --quiet -- frontend/app/dashboard/kitchen/page.tsx` — the
  `cd` at the head of the compound command persists, so the pathspec resolves relative to
  `frontend/` and matches **nothing**, which git reports as rc=0. A restore check that passes
  because it examined no file is indistinguishable from a restore that worked.
- **Fix:** re-run from the repo root, and proven able to fail:
  ```
  EVIDENCE  worktree==HEAD (repo-root cwd)                                    rc=0
  CONTROL   the same pathspec under a cd into frontend/                        rc=0  ← vacuous
  CONTROL   append a newline to the file, then re-check                        rc=1  ← can fail
  CONTROL   restore, re-check                                                  rc=0
  ```
  Every identity check reported in this summary was re-run from the repo root. The same defect
  recurred once more (the tsc arm's restore check) and was caught and re-run the same way.

### 3. [Deliberate scope hold] STATE.md, ROADMAP.md and REQUIREMENTS.md not updated

Five wave-3 plans are executing **in one shared working tree** (confirmed: `git status` at this
plan's start showed three files modified by sibling plans). Those three planning files are not in
any wave-3 plan's declared set, and five concurrent writers would collide. Plan 35-03, which
finished during this run, took the same course — its final commit `788961d5` is
`35-03-SUMMARY.md` and nothing else. Consolidation belongs to the orchestrator.

Every commit in this plan staged files **by name**. No `git add .`, no `git add -A`, no
`git stash`, no `git clean`.

### 4. [Correction to the plan's own success criteria] Seven of eight are asserted, not six

The plan's `<success_criteria>` says "Six of the eight have a jsdom assertion; the two that do not
have their coverage boundary stated". That under-counts its own task list: Task 1 names finance as
the only surface without a mounted page test, and Task 2's four surfaces all have one. The shipped
result is **7 of 8 asserted, 1 not** (finance). Recorded rather than silently satisfied.

## Coverage boundaries, stated rather than implied

- **Finance has no jsdom assertion.** It has no mounted page test — only a badge-contrast test —
  and the plan explicitly forbids building a mounting harness for it. Its three declarations are
  covered by plan 35-10's static gate and by a browser spec in plan 35-08 **that no current tree
  executes**. Per-PR CI runs only `public-layout.spec.ts` + `public-a11y.spec.ts`
  (`ci-cd.yaml:531`), and issue **#683** records the nightly full-suite lane as **dark**. The
  honest phrasing is *"covered by a spec that no current tree executes"* — **not** "covered
  nightly". Nothing on a pull request exercises them today.
- **The same is true of every non-finance declaration at the browser level.** What this plan
  proves is that the attribute is APPLIED (jsdom, 19 cases, each shown failing first). It does not
  prove any band renders at any particular width in a browser. No measurement was taken.
- **The kitchen board's live-reflow hazard (T-35-13) is unmitigated by anything in this plan.**
  A wider band changes the `AnimatePresence` reflow on every STOMP order transition. That is a
  runtime property jsdom cannot observe. `e2e/kitchen-flow.spec.ts` and `e2e/stomp-relay.spec.ts`
  must be re-run in plan 35-12; the hazard is recorded at the site so it is not lost.
- **`e2e/media-review-320.spec.ts` was not run.** The Index tier adds no cap, so it is inert at
  320px *by construction* — but that is reasoning, not a run. Re-run in 35-12 (T-35-15).
- **No production build.** This plan's close is deliberately targeted: `npx jest` over its own
  projects plus `npx tsc --noEmit`. Five wave-3 plans running five full Next builds would prove
  each branch in isolation and never prove the wave. **The consolidated `npx jest && npm run build`
  is plan 35-08's**, once the wave has merged; plan 35-06 keeps a real build because its width
  VALUE changes and it must read the emitted value out of the stylesheet. A narrowed arm, not a
  skipped one.
- **No CSS was read.** 35-02 established that `max-w-detail` and `max-w-marketing` are already in
  the shipped stylesheet even though nothing applies them — so "the rule is in the CSS" is not
  evidence about a surface. This plan applies the **index** tier, whose class is the empty string,
  so there is no rule to read out and no stylesheet claim is made.
- **Mobile:** unchanged by construction. The edit is one attribute on an existing element on each
  branch — no class, no DOM node, no breakpoint rule. The webhooks responsive split is asserted
  preserved by name and armed (ARM C). No 390px browser run was done here.
- **CLS/LCP:** not measured. All eight surfaces are authenticated and sit outside every budget in
  `e2e/perf-budgets.ts`, which target public surfaces only.
- **SEO: N/A.** No public surface, metadata, route or markup semantics changed.
- **AI agent-readiness: N/A.** No API surface, endpoint, error shape or OpenAPI contract changed.
- **Accessibility:** no role, name, focus order or tab stop changed. The A11Y-3 risk CONTEXT.md
  names — the `overflow-x-auto` region's `scrollable-region-focusable` state — turns on *when* a
  region overflows, which a browser decides; that belongs to 35-12's axe pass, not to jsdom.

## Cited decisions

**ORCH-03 (orchestrator decision, 2026-08-29)** is why the Index tier is declared in the DOM at
all rather than implemented as the absence of a cap; it is cited at all eight sites, verified
above with a control. ORCH-01/02/04/05 concern public and marketing surfaces this plan does not
touch. The seven PATTERNS resolutions **A-3 through A-9** are each written at their own site, not
only in planning documents — A-9 in this plan's file set is `/dashboard/media/review`
(PATTERNS §2 also numbers `/dashboard/page.tsx` A-9; that surface belongs to plan 35-03).

## Known Stubs

None. No placeholder, no empty state, no TODO, no hardcoded empty value. Every surface this plan
touched renders exactly what it rendered before, at the same width, with one additional attribute.

## Threat Flags

None. No endpoint, input, credential, data flow or dependency was added.

- **T-35-12** (widened index surfaces disclosing more) — still `accept`, and still true: column and
  card sets are chosen by the component and gated by the API and Postgres RLS, identical at every
  width. A width expresses no tenant boundary.
- **T-35-13** (kitchen live-reflow) — `mitigate`, and the mitigation is **not complete in this
  plan**: the hazard is recorded at the site and the two specs are named, but they are re-run in
  35-12. Stated as outstanding rather than claimed as done.
- **T-35-14** (a mis-tiered delivery log reading as correct) — `mitigate`, and **complete**: the
  written exception is at the site, the jsdom assertion exists in both a positive and a
  value-specific form, and ARM A records the fail direction with real output.
- **T-35-15** (media review at 320px) — `mitigate`, **outstanding**: reasoned inert, not run.
  35-12 owns the run.
- **T-35-SC** — nothing was installed, so the Package Legitimacy Gate correctly did not run rather
  than being skipped.

ASVS L2: V4 does not apply — no authorisation decision is made by a width. V5 does not apply — the
tier is a static literal from a closed union, never a runtime value. V14: the tier is an attribute,
never `style={{ maxWidth }}`, so the CSP's `'unsafe-inline'` allowance is not newly leaned on.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `dfce2027` | test | assert the Index tier on kitchen, marketing and staff before it exists (RED) |
| `28824608` | feat | declare the Index tier on finance, marketing, kitchen and staff (GREEN) |
| `f20ed901` | test | assert the Index tier on approvals, media review and both webhooks surfaces (RED) |
| `bfc86632` | feat | declare the Index tier on approvals, media review and both webhooks surfaces (GREEN) |

## TDD Gate Compliance

Both cycles complete and in order: `test(dfce2027)` → `feat(28824608)`, then `test(f20ed901)` →
`feat(bfc86632)`. Each RED gate was observed failing with recorded output before its GREEN commit
— 6 failures then 9, every one printing `Received: null` against the tier attribute rather than a
module-resolution error, so the RED was substantive rather than structural. No REFACTOR commit was
needed; neither implementation required cleanup after going green.

## Requirement progress — recorded truthfully

**UIX-08: in progress, not complete.** Its three plans are 35-03, 35-04 and 35-08. 35-02 shipped
the *mechanism*; 35-03 declared the five surfaces CONTEXT.md names; **this plan declares the
remaining eight**. What remains before UIX-08 can be called done is **35-08's browser spec** —
and the honest state of that is that the per-PR gate runs only the two public specs, and the
nightly lane that would run an Index-tier assertion is **dark (#683)**. So the requirement's own
acceptance — *"resource-index surfaces use the available width"* — is asserted in jsdom and
**not yet measured in any browser on any tree**.

## Self-Check: PASSED

All 15 claimed files exist on disk; all 4 claimed commit shas resolve. Run with a control
(`deadbee` correctly ABSENT), so the FOUND results are about this repository rather than about a
check incapable of failing.
