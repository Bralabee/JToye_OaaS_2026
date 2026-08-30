---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 07
subsystem: ui
tags: [layout, design-tokens, marketing, storefront, react, jest, hydration, cls]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 02
    provides: "WIDTH_TIER_CLASS — the tier -> class map, and the documented IN-PLACE application shape"
provides:
  - "the Marketing tier DECLARED on eleven marketing band sites (operator pitch 3, business-model guide 4, competitive teardown 4)"
  - "the Marketing tier DECLARED on four /shop band sites (header rail, directory, its Suspense skeleton, the route skeleton)"
  - "ORCH-05 discharged: the /shop/[slug] skeleton aligned to the content it replaces — the 384px hydration narrowing is gone"
  - "ORCH-01's reasoning AND its orchestrator provenance recorded in the tree at the directory band"
  - "an OWED arm handed to plan 35-10: the skeleton-parity fail direction, with the measurement that no instrument on this tree can catch it"
affects: [35-08, 35-10, 35-11, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "band-count-per-component: the NUMBER of declared bands is asserted, because a band left on the old token renders identically at today's value"
    - "two-assertion coverage: a full revert is caught ONLY by the count, a half-swap ONLY by the token-absence case — measured, neither alone covers both"

key-files:
  created: []
  modified:
    - frontend/components/marketing/operator-pitch.tsx
    - frontend/components/marketing/business-model-guide.tsx
    - frontend/components/marketing/competitive-teardown.tsx
    - frontend/components/marketing/__tests__/operator-pitch.test.tsx
    - frontend/components/marketing/__tests__/business-model-guide.test.tsx
    - frontend/components/marketing/__tests__/competitive-teardown.test.tsx
    - frontend/app/shop/layout.tsx
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
    - frontend/app/shop/loading.tsx
    - frontend/app/shop/[slug]/loading.tsx

key-decisions:
  - "ORCH-01 is written into the tree as an ORCHESTRATOR decision of 2026-08-29 and explicitly NOT as a user decision — the one citation in this phase that outlives the planning archive"
  - "The /shop/[slug] skeleton gets NO tier attribute: the surface has not been ASSIGNED a tier (A-11 deferred), and an attribute would claim an assignment nobody made"
  - "The storefront layout's main element stays uncapped — /shop is a separate tree and PolicyPage nests in it"
  - "ARM B recorded UNVERIFIABLE-TODAY with its instrument named, never as a pass"

requirements-completed: []
requirements-progressed: [UIX-07]

# Metrics
duration: 12min
completed: 2026-08-29
---

# Phase 35 Plan 07: Marketing Tier Part 2 + the Storefront Skeleton-Parity Fix Summary

**Fifteen band sites that were already the right width now say so, and the one place where a skeleton and its content disagreed by 384px no longer does — a page that visibly narrowed on hydration.**

## Performance

- **Duration:** ~12 min
- **Tasks:** 4 of 4
- **Files modified:** 11 (0 created)
- **Commits:** 5
- **Jest blocks added:** 20 (the `components/marketing` + `app/shop` scope moved 126 -> 146)

## What changed

### Job one — declaration (Tasks 1 and 2)

Fifteen band elements across seven files swap the stock scale token for
`WIDTH_TIER_CLASS.marketing` and declare `data-width-tier="marketing"` beside it, **in
place** on the element that already existed. **No band changes its rendered width.** The
tier class is never written as a literal, so plan 35-10's single-occurrence property still
reads one module.

| Surface | Bands | Per-PR browser cover? |
|---|---|---|
| `components/marketing/operator-pitch.tsx` | 3 | yes — `/for-operators` |
| `components/marketing/business-model-guide.tsx` | 4 | yes — `/business-model-guide` |
| `components/marketing/competitive-teardown.tsx` | 4 | yes — `/competitive` |
| `app/shop/layout.tsx` (header rail) | 1 | yes — `/shop` |
| `app/shop/shop-discovery-client.tsx` (directory + its Suspense skeleton) | 2 | yes — `/shop` |
| `app/shop/loading.tsx` (route skeleton) | 1 | route covered; the skeleton itself is transient |
| `app/shop/[slug]/loading.tsx` (**width fix**, no tier) | — | **NO** — see Coverage boundaries |

Everything the plan told me to leave alone was left alone: the headline and paragraph
measures in the marketing components, and the sub-heading and search-input clamps in the
discovery client, are typographic measures and controls (PATTERNS 1c), orthogonal to the
page tier. Each is asserted by name rather than assumed.

### Job two — ORCH-05, the hydration jump (Task 3)

`app/shop/[slug]/loading.tsx` rendered its skeleton band at 1280 while the content that
replaces it renders at 896. The page **narrowed by 384px the moment real content arrived**
— a latent layout-shift contributor on a route with **no recorded CLS budget** (recorded as
an absence; none was invented here). The skeleton is aligned DOWN to the content. Nothing
else in the file changed.

It deliberately gets **no tier attribute**. `/shop/[slug]` has not been *assigned* a tier —
A-11 is deferred, a tier is a ceiling rather than a target, and 896 is within prose-measure
territory for a scannable list of dish rows. An attribute would claim an assignment nobody
made.

Measured on the tree, before and after:

```
BEFORE  app/shop/[slug]/loading.tsx            4xl=0   7xl=1     <-- the sole outlier
        app/shop/[slug]/shop-detail-client.tsx 4xl=10  7xl=0
        app/shop/[slug]/not-found.tsx          4xl=1   7xl=0
AFTER   app/shop/[slug]/loading.tsx            4xl=1   7xl=0
        (other two unchanged)
```

**A correction to PATTERNS.** PATTERNS 1c records `shop-detail-client.tsx` as
`max-w-4xl` **x9** while listing ten line numbers. The measured count is **10**. Recorded
so 35-10's gate is not built against a wrong figure.

## ORCH-01 — the deliberate non-change, attributed correctly

Public `/shop` **keeps** the Marketing width. The reasoning is written at the directory
band, with its three reasons (the Index tier is scoped to the *dashboard* resources; a
fluid card grid would render wider than the header rail directly above it, inverting on the
storefront the very misalignment this phase fixes on the landing page; and it is an SEO- and
CLS-measured indexed route where the least-cost correct change is none).

It is cited as **ORCH-01 (orchestrator decision, 2026-08-29, CONTEXT.md section 4b)** and
the comment states explicitly that it is **not** a user decision. Proven by content, with a
control so the search is about the tree rather than about the pattern:

```
EVIDENCE  rg 'ORCH-01 \(orchestrator decision' app/shop/shop-discovery-client.tsx
          -> :390  rc=0
EVIDENCE  rg -i 'user decision|D-01' over all 6 shipped source files in the set
          -> ONE hit, and it is the NEGATION:
             ":407  during planning. It is NOT a user decision: the owner delegated..."
CONTROL   the same pattern over CONTEXT.md -> 2 hits  (the search shape works)
```

## Verification — every criterion in both directions

### The TDD gates (fail direction by construction)

| Suite | RED | GREEN |
|---|---|---|
| three marketing suites | rc=1, **11 failed / 27 passed** | rc=0, **77/77** |
| `shop-discovery-client.test.tsx` | rc=1, **5 failed / 16 passed** | rc=0, **24/24** |

The 27 and 16 that passed in the RED runs are the pre-existing cases **plus the
measure-clamp and control-width controls** — which is the point: those controls pass
*before* the change, proving the instrument works on the tree as it stands, so the failures
are about the missing declaration rather than about a broken query.

### A vacuity defect caught in my own new test, before it was trusted

The header-rail case **passed against the unmodified layout**. `StorefrontLayout` also
renders `PublicFooter`, whose rail already declares the same tier (plan 35-06), so a
container-wide query for `[data-width-tier="marketing"]` was satisfied by the **footer**
while the header rail carried nothing. The pass was about the wrong element.

Fixed by scoping the query to the `<header>`; re-run RED went **4 failed -> 5 failed**. The
reason is recorded at the site so the scoping is not later "simplified" away. This is the
only reason the header-rail assertion is evidence rather than decoration.

### Opening clean arm (before any arm ran; Tasks 1-3 committed first)

```
git diff --quiet -- <my 11 files>                        rc=0
npx jest components/marketing app/shop   14 suites / 146 tests   rc=0
```

### ARM A — a band reverted to the stock token (plan-specified)

```
BROKEN    business-model-guide footer band -> the stock token, declaration removed
          ● BusinessModelGuide › declares the Marketing tier on every one of its bands
              Expected length: 4
              Received length: 3
          1 failed, 11 passed   rc=1
RESTORED  declarations back to 4 | stock-token matches=[] rc=1
          worktree==HEAD rc=0 | 12/12 rc=0
```

Precisely isolated: only the count assertion moved.

### ARM C — the HALF-swap (added; the plan did not specify it)

ARM A reverts a band completely, which the **count** catches. That left the *other*
assertion — "carries the tier class INSTEAD of the stock token, **never beside it**" — with
no recorded fail direction, even though the half-swap is T-35-24's actual failure mode: a
band carrying both renders identically today and diverges only when the tier value moves.

```
BROKEN    operator-pitch body band: the stock token added BESIDE the tier class
          ● OperatorPitch › carries the tier class INSTEAD of the stock token, never beside it
              Expected: false
              Received: true
          1 failed, 13 passed   rc=1
RESTORED  stock-token matches=[] rc=1 | declarations 3
          worktree==HEAD rc=0
```

**The finding worth reading.** ARM A reds *only* the count; ARM C reds *only* the
token-absence case. The count case stayed green under ARM C (the band is still declared),
and the token case stayed green under ARM A (it loops over *declared* bands, and the
reverted one no longer is). **Neither assertion alone catches both failures** — they are
complements, not belt-and-braces, and dropping either would leave a real regression class
uncovered.

### ARM B — OWED. Recorded UNVERIFIABLE-TODAY, not as a pass

The plan's second arm is the skeleton-parity claim. Run in the break direction, in full:

```
BROKEN    app/shop/[slug]/loading.tsx band set back to the 1280 value
          (break confirmed by content: ':35  <div className="mx-auto max-w-7xl ...')
          npx jest app/shop components/marketing
          -> 14 suites / 146 tests   rc=0     <-- NOTHING CAUGHT IT
```

And the absence is repo-wide, each half with a control so the empty result is evidence
about the tree rather than about my pattern:

```
EVIDENCE  any test/spec referencing the shop-detail skeleton
          rg 'slug./loading|ShopDetailLoading' over tests+specs -> matches=[] rc=1
CONTROL   the SAME search shape finds the SIBLING route skeleton's instrument
          rg 'ShopBrowseLoading' -> 3 hits in shop-discovery-client.test.tsx  rc=0
EVIDENCE  any e2e spec asserting a band width on /shop/[slug]
          rg 'max-w-4xl|max-w-7xl|widthTier|data-width-tier' frontend/e2e/ -> matches=[] rc=1
CONTROL   the e2e directory is non-empty and searchable -> 30 files contain a test block
RESTORED  band back to the 896 value | stock-token matches=[] rc=1
          worktree==HEAD rc=0 | family agrees 1/10/1
```

**Status: OWED, not satisfied.** The instrument is
**`scripts/check-layout-width-contract.sh` gate G-4**, driven by
**`docs/architecture/layout-tiers.tsv`**, both created by **plan 35-10**, whose own Task 3
already carries "ARM G-4 — set the shop detail skeleton band back to the wider value. Must
RED naming the files" and whose success criteria already say "Plan 35-07's owed
skeleton-parity arm explicitly discharged". **This plan does not report ARM B as passing
and does not claim the parity is enforced.** Today it is enforced by a comment, and a
comment cannot fail a build — which is precisely why 35-10 exists.

I deliberately did **not** build a competing instrument here: 35-10 owns G-4, and a second
parity checker landing from a parallel plan would be duplication in a wave whose safety
rests on disjoint file sets.

### Closing clean arm — the only proof the restores landed

```
git diff --quiet -- <my 11 files>                        rc=0   (all three restores landed)
npx jest components/marketing app/shop   14 suites / 146 tests   rc=0
```

Verified by **content** (unique token per file) *and* by **blob identity**, never by
`git diff --stat`.

### Closing gates

| Check | Result |
|---|---|
| `npx jest components/marketing app/shop` | **14 suites / 146 tests**, rc=0 |
| `npx tsc --noEmit -p tsconfig.json` | **rc=0** |
| `npx eslint` over all 11 touched files | **rc=0**, no output |
| `scripts/docs-freshness.sh` | **rc=2 (VOID)** — see Deviations 2 |

**This plan's close was TARGETED, by design.** No `npm run build` here. Five plans run in
parallel in this wave on one branch, so a build from any one of them proves only that
plan's tree, never the wave. The consolidated full `npx jest && npm run build` is **plan
35-08's**, once the wave has merged; plan **35-06** keeps a real build because it is the one
plan whose width *value* changes and it must read the emitted value out of the generated
stylesheet. The type-check is **not** optional and replaces what the build would have
caught: this repo has recorded that frontend TypeScript errors are invisible to Jest.

## Deviations from Plan

### 1. [Rule 2 - Missing critical assertion] ARM C, and the vacuity fix in my own test

- **Found during:** Task 4, and Task 2's RED run.
- **Issue:** (a) The plan specified two arms; the "never beside it" assertion — the
  mitigation the threat register names for T-35-24 — had **no fail direction at all**.
  (b) My own header-rail case passed against the unmodified tree.
- **Fix:** ARM C added and recorded in both directions; the header-rail query scoped to the
  `<header>` with the reason recorded at the site.
- **Why it mattered:** ARM C changed what this plan can claim — it established that the
  count and token assertions catch *disjoint* failures.

### 2. [Out of scope — logged, NOT fixed] `docs-freshness.sh` is VOID, and it is not mine

`scripts/docs-freshness.sh` exits **2 (VOID)**, not 1 (drift):

```
VOID: frontend/app/dashboard/onboarding/__tests__/page.test.tsx:652:
      describe.each multiplies every block inside it; this counter cannot resolve
      that statically.  Treat this as UNVERIFIED, not as a pass.
```

Introduced by commit `5f9e39b4`, *test(35-05)*, in **plan 35-05's** file set. The counter is
failing **closed**, exactly as designed. The consequence for this summary is stated
precisely: my Jest-block drift is **unmeasured**, which is *not* the same as "no drift" — I
added 20 blocks and say so numerically instead.

Not fixed here: the file belongs to another plan running concurrently in the same tree, and
`docs/metrics.json` + README/CLAUDE/AGENTS are all in **plan 35-11's** declared set.
Logged to `deferred-items.md`.

**35-11 needs this:** its Task 2 enumerates the block-adding plans as "35-01, 35-02, 35-05,
35-08 and 35-09". That list is **incomplete** — **35-07 adds 20 Jest blocks**, and **35-06
adds 17** (its own measurement, appended to `deferred-items.md` after mine; it reproduced
this VOID independently and attributed it to the same commit by content, not by inference).
Regeneration must use `scripts/docs-freshness.sh --write`, never arithmetic, and cannot run
at all until the VOID is cleared.

### 3. [Recorded, no action] The wave shares one branch and one working tree

All five wave-3 plans commit to `feature/35-horizontal-layout-contract` in the same
checkout; other agents had uncommitted edits throughout this run. Consequences honoured:
every `git add` named my paths explicitly (never `-A`, never `.`), no `git clean` /
`git stash` / blanket restore was used, and every "clean" assertion is **scoped to my 11
files** — the tree as a whole was never clean and a global `git diff --quiet` would have
been meaningless here.

## Coverage boundaries, stated rather than implied

- **The per-PR browser gate covers five of my six surfaces — but not the one that changed
  width.** `ci-cd.yaml:531` runs `public-layout.spec.ts` + `public-a11y.spec.ts`, whose
  `PUBLIC_ROUTES` includes `/`, `/shop`, `/shop?q=grill`, `/for-operators`,
  `/business-model-guide` and `/competitive`. **`/shop/[slug]` is absent, deliberately**:
  the spec records at :119-123 that `/shop/test-kitchen` *used to be in this list* and was
  removed because #537 made the route a server component that 404s without a real backend.
  So the ORCH-05 skeleton fix — the only rendered-width change in this plan — has **no
  per-PR browser cover**, which is exactly why the owed ARM B matters.
- **Even on the covered routes, that gate does not assert band width.** It asserts
  fixed-ratio boxes, image rendering and no horizontal overflow. A green run there is not
  evidence that a band renders at 1280. The width-contract spec is **plan 35-08's**.
- **Say "covered by a spec that no current tree executes", never "covered nightly."** #683
  is open and the full-suite lane is dark. Nothing in this summary claims nightly cover.
- **These suites test APPLICATION, never the VALUE** — 35-02's ARM F property, inherited.
  Every case asserts against `WIDTH_TIER_CLASS.marketing`, so a wrong value in the
  vocabulary would leave all 20 of my blocks green. Only `content-tier.test.tsx`'s
  derivation assertion guards the value.
- **jsdom is not a browser.** These prove the class is applied and the attribute declared.
  They do not prove a rendered band width, and this plan does not claim one.
- **The Suspense-fallback band is asserted at source level, not in the DOM** — nothing
  suspends under jsdom. It is paired with the DOM case on the sibling band, and it is a
  *count* (exactly 2) with a control, so a half-done file reds.
- **CLS not measured here.** The skeleton fix removes a layout-shift contributor on a route
  with **no recorded CLS budget**; per the plan, none was invented. ORCH-02's desktop CLS
  arm belongs to 35-09 and concerns `/`.
- **SEO: N/A with reason.** Public indexable surfaces, but no metadata, structured data,
  canonical, robots directive or crawlable link changed, and no width changed on any
  indexed route.
- **AI agent-readiness: N/A.** No API surface changed.
- **Web performance:** class swaps and one attribute per existing element. **No DOM node
  added** anywhere, so nothing in the scroll-reveal, motion or bounding-box behaviour has a
  new element to notice. No dependency, no image, no bundle growth beyond the attribute
  strings.

## N/A surfaces — written down, because an unstated boundary reads as covered

- **`components/public/public-shell.tsx` — N/A-container, and it carries an ACTIVE TRAP.**
  Its `<main id="main">` is deliberately width-free so its children own their bands.
  **Do NOT add a cap here.** Two independent reasons: `/shop` is a separate layout tree, and
  `PolicyPage` nests inside this one, so a cap would silently narrow surfaces this phase
  never examined. PATTERNS B-12 records the concrete consequence —
  `e2e/unsubscribe-flow.spec.ts` asserts no horizontal overflow at 375px on `/unsubscribe`,
  which is `PublicShell` + `max-w-lg`, and is "only at risk if `PublicShell` gains a cap".
  **No cap was added.** The same rule was honoured for `app/shop/layout.tsx`'s own `<main>`,
  which this plan asserts stays uncapped and untiered.
- **`app/shop/[slug]/not-found.tsx` — N/A, and a PARITY PARTNER.** Line 30 renders at the
  same 896 as `shop-detail-client.tsx`; confirmed on the tree (`4xl=1, 7xl=0`). No tier
  attribute, same reason as the skeleton. It is the **third** member of the `/shop/[slug]`
  family, and a parity check covering two of three would be worse than none because it
  reads as covering the route.

**Handoff to 35-10, checked rather than assumed:** 35-10's plan **already** carries both
entries (`:156` public-shell N/A-container with the do-not-cap reason, `:160` not-found as a
family member, `:162-163` the three-file parity set, `:254` the third-member arm). My
contribution is **confirmation on the tree**, not new information — stated that way rather
than claiming to have supplied it.

## Both storefront route pairs' parity status

- **`/shop` pair — CONSISTENT, and now asserted.** `app/shop/loading.tsx` and the discovery
  client were already equal at the Marketing width; Task 2 kept them so and added a case
  that asserts the skeleton and the content declare the **same** band width, including a
  guard that the shared value is a real class rather than two matching absences.
- **`/shop/[slug]` family — FIXED, but NOT yet asserted.** Three files, all at 896. The
  disagreement is gone; the mechanical assertion is **owed to 35-10** (ARM B above).

Stated for both because a parity claim covering one of two route pairs would be worse than
none — it reads as covering both.

## A-11 — deferred, with its reasoning, not omitted

`/shop/[slug]` keeps 896 and is **not** widened to the Detail tier. ORCH-01 scopes the
public storefront to "unchanged"; a tier is a **ceiling, not a target**, so a surface
already narrower for a stated reason keeps its measure; 896 is within prose-measure
territory and a menu row is a scannable line rather than a grid; the surface is absent from
CONTEXT's measured baseline table; and it is the highest-churn edit available in the phase
(**10** sites in one file, not the 9 PATTERNS records). Recorded at the site as a deferral
with the reason, so it reads as a decision rather than an oversight.

## Known Stubs

None. No placeholder, empty state, mock data source or TODO ships in this plan.

## Threat Flags

None. No endpoint, input, credential, data flow or dependency is added.

- **T-35-24** (half-completed token swap) — mitigated by per-component count assertions
  **and** the token-absence assertions, **proven by ARMs A and C**, which between them
  established that both are required.
- **T-35-25** (shop-detail hydration narrowing) — the width is **fixed**; the mechanical
  parity assertion is **owed to 35-10** and is recorded as owed, not as delivered.
- **T-35-26** (storefront rail vs directory content) — mitigated by declaring the same tier
  on both and recording ORCH-01's reasoning at the site.
- **T-35-27** (public marketing markup) — still `accept`: no metadata, structured data,
  credential or tenant value touched; no width changed on any indexed route.
- **T-35-SC** — nothing was installed, so the Package Legitimacy Gate correctly did not run
  rather than being skipped.

ASVS L2 V14: every tier is applied as a utility class, never `style={{ maxWidth }}`, so the
CSP's `'unsafe-inline'` allowance is not newly leaned on.

## Requirement progress — recorded truthfully

**UIX-07: in progress, not complete.** This plan declares the Marketing tier on fifteen band
sites and removes one real hydration defect. The scattered-literal gate (35-10), the
width-contract browser spec (35-08) and the documented standard (35-11) all remain, and the
enforcement of this plan's own parity fix is one of the things 35-10 still owes.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `3ba9e6ff` | test | assert the Marketing tier on the three marketing surfaces (RED) |
| `8c290c82` | feat | declare the Marketing tier on the three marketing surfaces (GREEN) |
| `f8c0b499` | test | assert the Marketing tier across the /shop route family (RED) |
| `4ba6e143` | feat | declare the Marketing tier across the /shop route family (GREEN) |
| `13e94c46` | fix | align the shop detail skeleton to the content it replaces (ORCH-05) |

Every message was passed via a quoted heredoc, never an interpolating `-m` string, and read
back with `git log -1 --format=%B` — backticks inside a double-quoted message execute and
are silently dropped from the stored text.

## TDD Gate Compliance

Both cycles complete and in order: `test(3ba9e6ff)` -> `feat(8c290c82)`, then
`test(f8c0b499)` -> `feat(4ba6e143)`. Each RED gate was observed failing with recorded
output before its GREEN commit (11 and 5 substantive assertion failures respectively), with
the non-vacuity controls already passing in both RED runs. No REFACTOR commit was needed.

Task 3 (`13e94c46`) carries **no test of its own** — deliberate, and the plan says so: its
assertion is 35-10's G-4 gate. It is committed as `fix` rather than `feat` because it
repairs a pre-existing defect, and it is the one change in this plan whose correctness rests
on an instrument that does not exist yet. That is recorded as owed rather than glossed.

## Self-Check: PASSED

All 12 claimed files exist on disk; all 6 claimed commit shas resolve (the five of this plan
plus `5f9e39b4`, the 35-05 commit this summary attributes the docs VOID to). Run with
controls in both directions — a non-existent path correctly reported MISSING and `deadbee`
correctly reported MISSING — so the FOUND results are about this repository rather than
about a check incapable of failing.
