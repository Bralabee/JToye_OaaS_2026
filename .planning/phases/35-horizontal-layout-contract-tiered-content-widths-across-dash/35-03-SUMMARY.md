---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 03
subsystem: ui
tags: [layout, design-tokens, react, jest, dashboard, contract-test, index-tier]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 02
    provides: "the tier vocabulary (WIDTH_TIER_CLASS, ContentTier) and the documented IN-PLACE application shape"
provides:
  - "the Index tier declared on the five resource-index surfaces CONTEXT.md section 4 names"
  - "an executable no-cap assertion on an index root, with its fail direction recorded — 'uncapped' is now measured rather than assumed"
  - "a nesting guard (exactly one tier declaration per page) proven to fire on a SECOND declaration, not only on zero"
  - "the branch map for these five pages, keyed on guard conditions rather than line numbers"
affects: [35-08, 35-10, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "index-tier-in-place: data-width-tier on the existing loaded-branch root element, no class and no wrapper node"
    - "declaration-scoped no-cap assertion: the absence is asserted on the DECLARING element, never file-wide — these pages legitimately carry capped modal dialogs"
    - "non-vacuity control paired with every absence: the detector is shown firing on a real tier cap read from the vocabulary before the empty result is trusted"

key-files:
  created: []
  modified:
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/app/dashboard/customers/page.tsx
    - frontend/app/dashboard/shops/page.tsx
    - frontend/app/dashboard/orders/__tests__/page.test.tsx
    - frontend/app/dashboard/products/__tests__/page.test.tsx
    - frontend/app/dashboard/__tests__/page.test.tsx

key-decisions:
  - "The spinner guard branch is deliberately left untiered on all five pages: the Index tier adds no class, so an untiered spinner renders at exactly the width a tiered one would, and a marker on a transient branch would claim the tier for a state that is not the page. This differs from 35-05's Detail surfaces, where an untiered branch WOULD jump width"
  - "The pages do NOT import WIDTH_TIER_CLASS — the index entry is the empty string, so there is nothing to apply. The vocabulary is imported by the TESTS, where it powers the non-vacuity control"
  - "The no-cap assertion is scoped to the declaring element's className, never to the file: all five pages legitimately carry max-w-2xl on modal DialogContent, and a file-wide gate would red on correct code"
  - "No mounting harness was built for customers and shops; their coverage boundary is written down instead"
  - "docs/metrics.json and the prose docs were NOT regenerated here — plan 35-11 owns that loop, and five parallel plans each regenerating a shared manifest would conflict and each be wrong for the merged tree"

patterns-established:
  - "A guard asserting a count of ONE must be shown failing at TWO, not only at zero — the RED run only ever proves the zero direction"

requirements-completed: []
requirements-progressed: [UIX-08]

# Metrics
duration: 45min
completed: 2026-08-29
---

# Phase 35 Plan 03: The Index Tier, Part 1 Summary

**The five resource-index surfaces now state their width contract in the DOM instead of leaving
it to be inferred from an absence — including the orders table whose 900px of empty gutter at
2560 is the artefact this phase exists for.**

## Performance

- **Duration:** ~45 min
- **Tasks:** 3 of 3
- **Files modified:** 8 (0 created, 8 modified)
- **Commits:** 4

## What changed

### Tasks 1 and 2 — five in-place declarations (TDD)

One attribute on one element per page. The complete source diff for this plan is **five hunks,
one per file**, each replacing a bare `<div className="space-y-N">` with the same div carrying
`data-width-tier="index"` plus a prose comment. No wrapper node, no class, no import.

| Page | Root element | Declaration at | Rhythm class preserved |
|---|---|---|---|
| `app/dashboard/orders/page.tsx` | `OrdersPageInner`'s loaded return | line 525 | `space-y-6` |
| `app/dashboard/products/page.tsx` | `ProductsPage`'s loaded return | line 389 | `space-y-6` |
| `app/dashboard/page.tsx` | `DashboardPage`'s loaded return | line 262 | `space-y-8` |
| `app/dashboard/customers/page.tsx` | `CustomersPage`'s loaded return | line 240 | `space-y-6` |
| `app/dashboard/shops/page.tsx` | `ShopsPage`'s loaded return | line 279 | `space-y-6` |

The comments name the tier and its rationale **in prose**, never by restating the token, so plan
35-10's occurrence-counting gate is not satisfied by documentation. Each one tells the next reader
not to "tidy" the page by adding a cap.

The overview is tiered **Index rather than Detail** and the reason is recorded at the site: its
recent-orders table is the same six-column shape as the orders page, and showing one table at two
different widths on two pages is the half-shipped inconsistency this phase exists to remove.

### The branch map, keyed on guard conditions

Verified at each site rather than trusted from the plan's description — which is the rule that
produced the plan's own correction of an earlier false "measured" claim.

| Page | Branches, in source order | Tiered? |
|---|---|---|
| orders | `OrdersPage`'s `<Suspense fallback>` spinner → `OrdersPageInner`'s `if (loading)` spinner → default return | fallback NO, loading NO, default **YES** |
| products | `if (loading)` spinner → default return | loading NO, default **YES** |
| overview | `if (loading)` spinner → default return | loading NO, default **YES** |
| customers | `if (loading)` spinner → default return | loading NO, default **YES** |
| shops | `if (loading)` spinner → default return | loading NO, default **YES** |

No page has an error-branch early return — every error state on these five renders inline inside
the default return (which is why the `error-state.test.tsx` suites mount the same root). Confirmed
by an indentation-scoped scan: the only 4-space `return (` in each component is the loading guard;
everything deeper is inside a `.map()` callback.

**Why the spinner is deliberately NOT tiered here, and why that is not the B2 rule being ignored.**
The general finding is that every page-level render branch must carry the tier — and for the
Detail tier that is load-bearing, because an untiered branch renders at the full band and then
*jumps* to 1100px when the fetch resolves. The Index tier adds **no class**, so an untiered spinner
renders at precisely the width a tiered one would: there is no jump to hide. What the absence does
cost is the MARKER, and that is a real consequence rather than a free pass:

> A browser assertion that queries the tier selector before the fetch resolves finds nothing on
> these five pages. That is a timing fact, not a contract violation, and it is why plan 35-08 must
> **wait on the marker** rather than measure on network-idle.

Tiering the spinner was considered and rejected: a marker on a transient branch would claim the
tier for a state that is not the page.

## Verification — every criterion in both directions

### The TDD gates (fail direction by construction)

| Suite | RED | GREEN |
|---|---|---|
| orders + products `page.test.tsx` | rc=1, **6 failed / 15 passed** | rc=0, 5 suites / **28 tests** |
| overview `page.test.tsx` | rc=1, **3 failed / 14 passed** | rc=0, 4 suites / **25 tests** |

Real RED output, orders:

```
● Orders page — the Index width tier (phase 35) › declares the index tier once, on the loaded root element itself
    expect(received).toHaveLength(expected)
    Expected length: 1
    Received length: 0
    Received object: []
● … › adds no width cap of its own — Index is fluid to the shell band
    TypeError: Cannot read properties of undefined (reading 'className')
      163 |     expect(probe.className).toMatch(WIDTH_CAP)      <- CONTROL, passed
    > 165 |     expect(declared[0].className).not.toMatch(WIDTH_CAP)
```

The control on line 163 passing before line 165 throws is the point: the detector was already
proven live in the same run in which the evidence line failed.

### Opening clean arm (Tasks 1 and 2 committed BEFORE any arm ran)

```
npx jest <this plan's 9 suites, by exact path>
9 suites / 53 tests   rc=0
git diff --quiet HEAD -- <this plan's 8 files>   rc=0
```

### ARM A — a Detail cap on the Index root (plan-specified, the one that matters)

Applied as `className={`space-y-6 ${WIDTH_TIER_CLASS.detail}`}` with the vocabulary imported —
the *plausible* wrong edit, someone applying the tier map to an index page — rather than a raw
string literal.

```
BROKEN    ✓ declares the index tier once, on the loaded root element itself
          ✕ adds no width cap of its own — Index is fluid to the shell band
              expect(received).not.toMatch(expected)
              Expected pattern: not /(?:^|\s)max-w-/
              Received string:      "space-y-6 max-w-detail"
          ✓ keeps the vertical rhythm class the declaration was added beside
          1 failed, 6 passed   rc=1
RESTORED  root line present by content rc=0 | WIDTH_TIER_CLASS matches=[] rc=1
          worktree==HEAD rc=0 | 7/7 rc=0
```

Precisely isolated: only the no-cap assertion moved. **This is the arm that makes the Index tier's
uncapped claim measured rather than assumed** — before it, "no max-width class" had only ever been
observed passing.

### ARM B — a SECOND, nested tier declaration (added; not in the plan)

The RED run proved the count guard fires at **zero**. It had never been shown firing at **two**,
which is the direction that catches the double-cap defect.

```
BROKEN    an extra <div data-width-tier="index"> wrapped inside the root
          ✕ declares the index tier once, on the loaded root element itself
              Expected length: 1
              Received length: 2
              Received object: [<div class="space-y-6" data-width-tier="index">…, <div data-width-tier="index">…]
          1 failed, 6 passed   rc=1
RESTORED  declarations in file back to 1 (the arm made it 2) | worktree==HEAD rc=0
```

### ARM C — the declaration moved OFF the root onto a child (added)

`toBe(view.container.firstElementChild)` had no recorded fail direction: the RED run bailed at the
length assertion before reaching it.

```
BROKEN    the single declaration relocated to an inner div
          ✕ declares the index tier once, on the loaded root element itself
              expect(received).toBe(expected) // Object.is equality
              - Expected  - 4 / + Received  + 0
          ✕ keeps the vertical rhythm class the declaration was added beside
          ✓ adds no width cap of its own                      <- see boundary below
          2 failed, 5 passed   rc=1
RESTORED  worktree==HEAD rc=0 | root line present by content rc=0
```

**What ARM C exposed, and it is worth reading.** The no-cap case stayed GREEN while the tier sat on
the wrong element, because that case measures the **declaring** element and the child had no cap
either. So the no-cap assertion is a statement about whatever declares the tier — it is the
*root-identity* assertion, not the no-cap one, that pins the declaration to the page root. Both are
needed; neither alone is sufficient. Plan 35-08's browser spec inherits this: querying
`[data-width-tier="index"]` and measuring it proves the declared band's width, not that the
declared band is the page.

### ARM D — the wrong tier VALUE declared (added)

`toHaveAttribute(..., "index")` also had no fail direction — RED never got past the length check.

```
BROKEN    data-width-tier="detail" on the orders root
          ✕ declares the index tier once, on the loaded root element itself
              Expected the element to have attribute:
                data-width-tier="index"
              Received:
                data-width-tier="detail"
          1 failed, 6 passed   rc=1
RESTORED  worktree==HEAD rc=0
```

### ARM E — the type gate itself (added)

`npx tsc --noEmit` replaced a full production build in this plan's close, so it had to be shown
capable of failing, and its **scope** had to be measured rather than assumed.

```
BROKEN    `const __TSC_BREAK_ARM__: string = 42` planted in ONE source file and ONE test file
          app/dashboard/orders/__tests__/page.test.tsx(44,7): error TS2322: Type 'number' is not assignable to type 'string'.
          app/dashboard/orders/page.tsx(231,7):               error TS2322: Type 'number' is not assignable to type 'string'.
          rc=2
RESTORED  break token matches=[] rc=1 | worktree==HEAD rc=0 | tsc rc=0
```

Both files were named, so the type gate covers **test files as well as source** here
(`tsconfig.json` includes `**/*.tsx` with only `node_modules` excluded). Jest runs against
`tsconfig.build.json` and does not type-check, so this is not redundant with the suite.

### Closing clean arm

```
npx jest <this plan's 9 suites, by exact path>
9 suites / 53 tests   rc=0
npx tsc --noEmit -p tsconfig.json                          rc=0
git diff --quiet HEAD -- <this plan's 5 source files>      rc=0
data-width-tier="index" occurrences: 1 per page, all five  (content, not --stat)
npx eslint <this plan's 8 files>  rc=0, 0 errors / 4 pre-existing warnings
```

The closing arm is the only proof the four restores landed. Verified by **content** (a unique token
per arm) and by **blob identity** (`git diff --quiet`), never by `git diff --stat`.

### Content evidence with its own controls

```
EVIDENCE  data-width-tier="index" per page   -> 1, 1, 1, 1, 1   (all five)
CONTROL A data-width-tier="detail" in those files -> matches=[]  rc=1  (correctly absent)
CONTROL B the same pattern shape where it IS present:
          components/dashboard/dashboard-shell.tsx:88  data-width-tier="shell"  rc=0
```

Control B is what makes Control A's empty result evidence about the tree rather than about the
pattern.

### The A11Y-3 scroll region — untouched, and proven so

```
git diff --quiet HEAD -- products/__tests__/mobile-header-and-scroll-a11y.test.tsx   rc=0
PASS app/dashboard/products/__tests__/mobile-header-and-scroll-a11y.test.tsx
  ✓ the header row can wrap, with a gap between the wrapped lines
  ✓ the horizontally-scrolling table region is a keyboard-focusable, named landmark
```

The products diff is a single hunk at the root element; the `overflow-x-auto` region, its role, its
accessible name, its `tabIndex` and the header's `flex-wrap`/`gap-3` classes are all outside it.

## Deviations from Plan

### 1. [Rule 3 — Blocking] Four arms the plan did not specify (ARMs B, C, D, E)

- **Found during:** Task 3
- **Issue:** The plan specified one arm (the Detail cap). Four assertions had **no fail direction
  at all**: the nesting guard at a count of two, the root-identity assertion, the tier VALUE, and
  the type gate that replaced the build. The RED run only ever proves the zero/absent direction.
- **Fix:** ARMs B–E above, each with real output in both directions.
- **Why it was worth it:** ARM C changed what this plan claims — see the coverage boundary on the
  no-cap assertion's scope. ARM E measured the type gate's file scope instead of assuming it.

### 2. [Rule 3 — Blocking] `docs/metrics.json` and the prose docs deliberately NOT regenerated

- **Issue:** This plan adds **9 Jest blocks** across 3 existing files (0 new test files), which
  reds `scripts/docs-freshness.sh` and then `scripts/check-doc-metrics.sh`. 35-02's handover note
  says to regenerate.
- **Fix:** Not done here, on purpose. `docs/metrics.json`, `README.md`, `CLAUDE.md` and `AGENTS.md`
  are in **plan 35-11's** declared file set, and all five wave-3 plans add Jest blocks
  concurrently. Five parallel regenerations of one shared manifest would conflict, and each would
  record a count that is wrong for the merged tree — the manifest is only correct once, after the
  wave lands. 35-11 runs `scripts/docs-freshness.sh --write` then fixes the prose. **Never by
  arithmetic.**
- **This plan's contribution to the delta, measured:** `+9` jest blocks, `+0` jest files.

### 3. [Rule 3 — Blocking] `STATE.md` and `REQUIREMENTS.md` left for the orchestrator

- **Issue:** Wave 3's five plans are running as five agents in **one shared checkout on one
  branch**, not in separate worktrees — observed directly (a sibling's
  `app/dashboard/orders/[id]/__tests__/detail-tier.test.tsx` appeared mid-run, and
  `git log 0d14794b..HEAD` interleaves 35-03/04/05/06/07 commits). A named-path `git add` of a
  shared file in that situation sweeps whatever a sibling has uncommitted on disk into this plan's
  commit; this repository has already had 369 of another session's lines swept into main that way.
  UIX-08 is also 35-04's requirement, so both agents would read-modify-write the same row.
- **Fix:** Neither file was touched. The truthful requirement text is recorded below for the
  orchestrator to apply once, after the wave.
- **Every commit in this plan stages named paths only** — never `git add .` or `-A` — and each was
  checked to contain only this plan's files.

### 4. [Instrument defect, self-caught] The plan's own verify command is contaminated in a shared checkout

`npx jest app/dashboard` — the plan's Task 3 verify — reported `1 failed, 21 passed` with two
failures in `app/dashboard/webhooks/__tests__/delivery-log.test.tsx`, a file this plan does not
touch. A sibling plan was mid-edit on `app/dashboard/webhooks/[id]/page.tsx` at that moment.
Re-run alone once that edit settled: **7 passed, rc=0**. No conclusion was drawn from the
contaminated run; every result reported above is from a run scoped to **exact file paths**. A
directory-glob verify is not a safe instrument while siblings share the tree.

## Coverage boundaries, stated rather than implied

- **Customers and shops have NO per-PR test evidence for their declarations.** Neither has a
  mounted page test in this tree — only `error-state.test.tsx` (and an allergen-consent notice
  test on customers) — and this plan deliberately did not build a heavy mounting harness to assert
  one attribute. Their declarations are verified here **by content** (1 occurrence each, with the
  controls above), will be verified by plan **35-10**'s static contract gate, and are measured in
  a browser only by **35-08**'s spec — which is **covered by a spec that no current tree executes**
  (issue **#683**: the nightly lane is dark, and per-PR CI runs only `public-layout.spec.ts` +
  `public-a11y.spec.ts`). Not "covered nightly".
- **The same is true of the three tested pages' browser behaviour.** jsdom proves the attribute is
  APPLIED. It does not lay out or measure pixels, so nothing here proves any index band renders
  wider than 1400px in a browser. That claim belongs to 35-08 and is currently unexecuted.
- **The no-cap assertion measures the DECLARING element, not the file** (ARM C). This is
  deliberate and it matters for 35-10: **all five pages legitimately carry `max-w-2xl` on modal
  `DialogContent`** — `orders/page.tsx:782,958`, `products/page.tsx:641`, `customers/page.tsx:419`,
  `shops/page.tsx:430`. A file-wide "no `max-w-` in an index page" gate would red on correct code.
  The gate must be scoped to the declaring element's own `className`.
- **The pages do not import the vocabulary**, because `WIDTH_TIER_CLASS.index` is the empty string
  and there is nothing to apply. Consequence: if a future change gave `index` a non-empty class,
  these five pages would silently not pick it up. That is guarded upstream —
  `content-tier.test.tsx` asserts `index` maps to `""` and 35-02's ARM B proved that assertion
  fires — but it is a real coupling and it is written down rather than left implicit.
- **The tier marker is ABSENT during loading on all five pages** (see the branch map). A browser
  assertion must wait on the marker, not on network-idle.
- **Mobile:** the tier adds no class and no DOM node, so there is no declaration that could bind at
  any breakpoint. That is a structural property of an empty-string tier, not an observation — no
  mobile browser run was made here.
- **CLS/LCP: N/A.** All five surfaces are authenticated dashboard routes and sit outside every
  budget in `e2e/perf-budgets.ts`, which target public surfaces only.
- **SEO: N/A.** No public surface, metadata, route or markup semantics changed.
- **AI agent-readiness: N/A.** No API surface changed.
- **Web performance:** one attribute per page on an element that already existed. No new DOM node,
  no dependency, no image, no bundle growth beyond five attribute strings. A widened band exposes
  more of an already-rendered table; it renders no extra rows or columns.
- **This plan's close was TARGETED, not a full production build.** Targeted Jest by exact path
  plus `npx tsc --noEmit -p tsconfig.json`. The consolidated full `npx jest && npm run build` is
  **plan 35-08's**, once the whole wave has merged; plan **35-06** keeps a real `npm run build`
  because it is the one plan whose width VALUE changes and must read the emitted value out of the
  generated stylesheet. A per-plan build in a parallel wave proves one tree in isolation, never the
  wave. This is a narrowed arm, not a skipped one.

## Cited decisions

**ORCH-03 (orchestrator decision, 2026-08-29)** is the reason the Index tier carries an explicit
marker while adding no width class, and it is the decision every one of the five site comments
paraphrases. ORCH-01/02/04/05 concern public and marketing surfaces this plan does not touch.

## Known Stubs

None. No placeholder, no empty state, no TODO, no hardcoded empty value. Every element this plan
touched already existed and already rendered real data.

## Threat Flags

None. No endpoint, input, credential, data flow or dependency was added.

- **T-35-09** (information disclosure via widened index tables) — still `accept`, and still true:
  the column set is chosen by the component and gated by the API and Postgres RLS, identical at
  every width. No tenant boundary is expressed in CSS.
- **T-35-10** (the products scroll region) — `mitigate`, discharged: the region is untouched, its
  suite is green **unmodified** (`git diff --quiet` rc=0), and the axe `scrollable-region-focusable`
  rule is verified in a real browser by plan 35-12. The per-PR jsdom axe gate structurally cannot
  evaluate it — jsdom does not lay out, so nothing there can decide whether a region overflows.
- **T-35-11** (a stray max-width silently narrowing an index back to the defect) — `mitigate`,
  discharged by the explicit no-cap assertion **and ARM A's recorded fail direction**, which is the
  mitigation the register named.
- **T-35-SC** — nothing was installed, so the package-legitimacy gate correctly did not run rather
  than being skipped.

ASVS L2: V4 does not apply (no authorisation decision is made by a width, and a wider band cannot
reveal a row RLS withheld). V5 does not apply (the tier is a static literal, never an injected
value). V14: the tier is declared as an attribute and applied as a utility class, never
`style={{ maxWidth }}`, so the CSP's `'unsafe-inline'` allowance is not newly leaned on.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `6dd5ec2b` | test | assert the Index tier on orders and products before it exists (RED) |
| `4387cf80` | feat | declare the Index tier on orders and products (GREEN) |
| `4e444522` | test | assert the Index tier on the dashboard overview before it exists (RED) |
| `4c3d0e95` | feat | declare the Index tier on the overview, customers and shops (GREEN) |

Task 3 produced no commit of its own: every arm was applied and restored, verified by content and
blob identity against the commits above.

## TDD Gate Compliance

Both cycles complete and in order: `test(6dd5ec2b)` → `feat(4387cf80)`, then `test(4e444522)` →
`feat(4c3d0e95)`. Each RED gate was observed failing with recorded output before its GREEN commit —
6 substantive failures then 3 — and in both runs every pre-existing case stayed green, so the RED
is attributable to the new assertions and not to a broken harness. No REFACTOR commit was needed:
neither implementation required cleanup after going green.

## Requirement progress — recorded truthfully

- **UIX-08: in progress, 1 of 3 plans (35-03 done; 35-04 and 35-08 remain). NOT complete.** Five
  index surfaces now declare the tier and three of them assert it in jsdom with recorded fail
  directions. 35-04 covers the remaining index surfaces; 35-08 is the browser spec, and that spec
  is currently **executed by no tree** (#683). Suggested REQUIREMENTS.md row text, for the
  orchestrator to apply once after the wave: *"In progress (1/3 plans) — 35-03 done 2026-08-29:
  orders, products, the overview, customers and shops declare `data-width-tier="index"` in place
  with no width class; the uncapped claim is asserted on the declaring element and proven falsifiable
  (a Detail cap reds it). Coverage boundary: customers and shops have no mounted page test — their
  declarations rest on 35-10's static gate and on 35-08's spec, which no current tree executes."*
- **UIX-07: unchanged at 2 of 4 plans** (35-01, 35-02 done; 35-10 and 35-11 remain). This plan
  applies the contract; it does not advance the contract's own limbs.

## Self-Check: PASSED

All 8 claimed modified files exist on disk and are committed; all 4 claimed commit shas resolve.
Run with a control (`deadbee` correctly reported ABSENT), so the FOUND results are about this
repository rather than about a check incapable of failing.
