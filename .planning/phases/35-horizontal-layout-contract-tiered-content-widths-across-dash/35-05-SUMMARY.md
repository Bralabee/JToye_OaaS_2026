---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 05
subsystem: ui
tags: [layout, design-tokens, react, jest, dashboard, branch-parity, incremental-betterment]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 02
    provides: "WIDTH_TIER_CLASS (the tier -> class map) and the documented IN-PLACE application shape"
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 01
    provides: "DETAIL_MAX_PX = 1100 and the generated max-w-detail utility"
provides:
  - "the Detail tier applied to three dashboard reading/form surfaces, on EVERY page-level render branch (7 elements across 3 files)"
  - "the branch-parity instrument: the only assertion in the phase that renders a loading or error branch at all"
  - "a browser-measured displaced-goods ledger for the three narrowed surfaces at 1440/1920/2560"
  - "the enumerated ceiling-not-target exceptions ledger (7 surfaces, each confirmed untouched and untiered)"
  - "the ban on describe.each in this repo's Jest suites, with the gate that makes it load-bearing"
affects: [35-08, 35-10, 35-11, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "branch-parity: alternate render branches are asserted against EACH OTHER, never each against a literal, because the defect is the difference"
    - "page-level BRANCH vs nested SUB-COMPONENT: branches each carry the tier, children must not (a cap inside a cap)"
    - "tier-is-a-ceiling: a surface already narrower for a stated reason keeps its measure and is recorded as an exception"
    - "no describe.each: scripts/count-test-blocks.mjs VOIDs on it, which is worse than drift"

key-files:
  created:
    - frontend/app/dashboard/orders/[id]/__tests__/detail-tier.test.tsx
  modified:
    - frontend/app/dashboard/orders/[id]/page.tsx
    - frontend/app/dashboard/onboarding/page.tsx
    - frontend/app/dashboard/onboarding/__tests__/page.test.tsx
    - frontend/app/dashboard/products/import/page.tsx

key-decisions:
  - "The narrowing is the intended product change, not a side effect: three live surfaces lose 236px at 1920+ and 20px at 1440, measured in a browser against the pre-phase tree, and justified against the 1016-1136px peer cluster"
  - "EVERY page-level render branch carries the tier (7 elements, not 3), because an untiered branch would render at the Shell content box and the page would jump as the request settled"
  - "The five nested sub-components are deliberately left untiered and that is asserted structurally in both directions"
  - "docs/metrics.json is NOT regenerated here — it is plan 35-11's declared file; but the VOID this plan introduced in that gate WAS fixed here, because a gate that cannot answer is worse than one reporting drift"

patterns-established:
  - "A render-branch driver proves WHICH branch it got before returning it; a driver that silently returned the wrong branch makes every assertion downstream vacuous"
  - "The measurement harness is built from the REAL emitted stylesheets of both trees (generated from each tree's own committed tailwind config) and cross-checked against the shipped .css, so the numbers describe the product rather than a mock-up"

requirements-completed: []
requirements-progressed: [UIX-09]

# Metrics
duration: 95min
completed: 2026-08-29
---

# Phase 35 Plan 05: The Detail Tier Summary

**The dashboard's three reading-and-form surfaces now cap at 1100px on every one of their render branches — which makes them 236px narrower at 1920 and above than they are on main today, deliberately, and 500px narrower than they would have been had this plan not run.**

## Performance

- **Duration:** ~95 min
- **Tasks:** 3 of 3
- **Files:** 5 (1 created, 4 modified) — exactly the declared `files_modified` set, verified per commit
- **Commits:** 6

## What changed

Seven elements across three page files, each an in-place edit on a root element that already existed — no wrapper node anywhere, so nothing in the pages' motion, CLS or bounding-box behaviour has a new element to notice.

| File | Branch (located by GUARD CONDITION, not line) | Element |
|---|---|---|
| `app/dashboard/orders/[id]/page.tsx` | `if (loading)` | spinner root |
| | `if (error)` | back-button + `role="alert"` root |
| | the unguarded return | loaded root |
| `app/dashboard/onboarding/page.tsx` | `if (loading)` | spinner root |
| | `if (!onboarding)` | the "Take your shop live" CREATE FORM |
| | the unguarded return | loaded state-machine root |
| `app/dashboard/products/import/page.tsx` | the only return of `ImportProductsPage` | page root |

`if (!order) return null` in the order detail page renders no element, so there is nothing to tier; it is left alone.

Each element gained `data-width-tier="detail"`, `mx-auto`, and `WIDTH_TIER_CLASS.detail`. **No tier-class literal was written anywhere** — measured, with a control:

```
rg -uu -n 'max-w-(shell|detail|marketing)' <all five of this plan's files>   matches=[]  rc=1
CONTROL  same pattern over components/layout/content-tier.tsx                3 hits      rc=0
CONTROL  WIDTH_TIER_CLASS references in the three source files              4 / 4 / 2
```

`cn()` was measured rather than assumed inert, on every class list this plan introduced:

```
mx-auto max-w-detail flex h-full items-center justify-center p-12   7 in / 7 out   dropped: []
mx-auto max-w-detail space-y-4 p-6                                  4 in / 4 out   dropped: []
mx-auto max-w-detail flex h-full items-center justify-center        6 in / 6 out   dropped: []
mx-auto max-w-detail space-y-6                                      3 in / 3 out   dropped: []
```

---

## (i) THE DISPLACED-GOODS LEDGER — the three surfaces this plan NARROWS

**This is most of what the plan does, and the doctrine requires it be enumerated rather than implied.**

### These numbers are MEASURED, not derived

The plan supplied a table. That table is derived arithmetic, and a derived number recorded as a measurement is precisely the defect the falsifiability contract exists to stop — so the before values were measured instead, in a real browser (headless Chromium via the repo's own Playwright install).

Method, and why it is a measurement of the product rather than of a mock-up:

- The DOM chain is the real one, read off `components/dashboard/dashboard-shell.tsx` and `components/dashboard/sidebar.tsx`: `div.flex.h-screen.overflow-hidden > (sidebar `hidden md:flex h-full w-64`) + `main.flex-1.overflow-y-auto` > (mobile top bar) + band > surface`.
- The CSS for each tree state was **generated from that tree's own committed `tailwind.config.ts`** by the repo's own Tailwind CLI — main's via `git show origin/main:frontend/tailwind.config.ts`, the branch's via the working config. Nothing was hand-transcribed.
- The branch CSS was cross-checked against the **shipped** stylesheet before being trusted:

```
generated branch.css     .max-w-detail{max-width:1100px}   .max-w-shell{max-width:1700px}
.next/static/chunks/…css .max-w-detail{max-width:1100px}   .max-w-shell{max-width:1700px}   rc=0
main.css (from main's config)  .container{width:100%;…padding:2rem}
                               @media (min-width:1400px){.container{max-width:1400px}}
branch.css  .container            matches=[]  rc=1   (corePlugins.container:false — correct)
```

### The measured table (surface element width, CSS px)

| Surface | Viewport | **A — main today** | **B — this branch without 35-05** | **C — after 35-05** | Δ vs A | Δ vs B |
|---|---|---|---|---|---|---|
| order detail | 1440 | **1120** | 1120 | **1100** | **−20** | −20 |
| order detail | 1920 | **1336** | 1600 | **1100** | **−236** | −500 |
| order detail | 2560 | **1336** | 1636 | **1100** | **−236** | −536 |
| onboarding | 1440 | 1120 | 1120 | 1100 | −20 | −20 |
| onboarding | 1920 | 1336 | 1600 | 1100 | −236 | −500 |
| onboarding | 2560 | 1336 | 1636 | 1100 | −236 | −536 |
| import wizard | 1440 | 1120 | 1120 | 1100 | −20 | −20 |
| import wizard | 1920 | 1336 | 1600 | 1100 | −236 | −500 |
| import wizard | 2560 | 1336 | 1636 | 1100 | −236 | −536 |

Supporting measurements at every row: sidebar 256 at all three viewports; `main` 1184 / 1664 / 2304; band 1184 / 1400 / 1400 in state A and 1184 / 1664 / 1700 in state B/C; band content box 1120 / 1336 / 1336 in A and 1120 / 1600 / 1636 in B/C.

**The measurement AGREES with the plan's derived table for column A and for the deltas.** Column B is new information the plan gave only as "~1636": at 1920 the untiered branch value is **1600**, not 1636, because the 1700 Shell cap does not bind until roughly 1956px. So the jump ARM C protects against is **500px at 1920** and 536px at 2560, not "~500" at both.

### The justification, which has to carry that 236px

These are reading and form surfaces, not data grids. CONTEXT.md §3's third independent finding: detail and reading columns cluster tightly at **1016–1136px** across the closest measurable peers — Linear's detail ladder tops out at 1136, Square's content ladder at 1016, Lightspeed's content column is 1100 — and prose-measure guidance (45–75 characters a line) is why. A 1336px order detail is already past that cluster; 1100 sits inside it, level with Lightspeed and 36px under Linear.

The order detail is a labelled key–value read plus a line-item list. Onboarding is a sequential gate form. The import wizard is a tabbed upload form. None gains from width.

And the choice was never "1336 or 1100". Column B is the measured answer to what these surfaces would have shipped as **without** this plan: 1600 at 1920 and 1636 at 2560 — 264 and 300px **wider** than main, and further from the peer cluster in the wrong direction. The real range is "1636 or 1100", and 1100 is the defensible end of it.

### FLAGGED FOR THE 35-13 GATE — the −20 at 1440

**Do not describe 1440 as unchanged.** 1440 is the laptop width at which the owner was told nothing would move, and the order detail, onboarding and import wizard each lose **20px** there (1120 → 1100). It is small, it is real, and it is surfaced here rather than left to be tripped over.

If the owner rejects the narrowing at the gate, the remedy is `DETAIL_MAX_PX` in `frontend/lib/layout-widths.ts` — one number, one place. **Do not respond by untiering the surfaces**, which would silently hand them the 1600–1636 Shell band instead.

### Goods NOT displaced, confirmed preserved

Every branch's existing rhythm and padding classes, asserted by name in jsdom and proven falsifiable (ARM A's sibling assertions stayed green while the utility assertion RED-ed): loading `flex h-full items-center justify-center p-12` (orders) and `flex h-full items-center justify-center` (onboarding); error/loaded `space-y-4 p-6`; create-form/loaded `space-y-6`. Centring is newly ADDED, so the narrower band sits mid-band rather than hugging the left edge.

---

## (ii) THE EXCEPTIONS LEDGER — a tier is a ceiling, not a target

Every surface below is already narrower than the Detail tier for a stated ergonomic reason. Each was examined and deliberately left alone. **None carries a tier attribute**, because an attribute would claim a tier the surface has not been assigned. Read off the tree, not from the plan:

| Surface | Cap | px | Why it keeps its measure | tier attr |
|---|---|---|---|---|
| `app/dashboard/payments/connect/connect-outcome.tsx` | `max-w-2xl` ×1 | 672 | Stripe Connect return/refresh outcome — three paragraphs, shared by both routes | 0 |
| `app/shop/[slug]/cart/page.tsx` | `max-w-2xl` ×2 | 672 | a linear checkout step | 0 |
| `app/shop/[slug]/checkout/page.tsx` | `max-w-2xl` ×4 | 672 | address, payment, confirm | 0 |
| `app/shop/[slug]/orders/[orderNumber]/page.tsx` | `max-w-lg` ×4 | 512 | a customer receipt | 0 |
| `app/shop/orders/orders-client.tsx` | `max-w-lg` ×3 | 512 | a phone-first history list | 0 |
| `app/track/page.tsx` | `max-w-lg` ×1 | 512 | a two-field lookup form | 0 |
| `app/unsubscribe/unsubscribe-content.tsx` | `max-w-lg` ×1 | 512 | a one-click opt-out panel | 0 |
| every Radix dialog | `max-w-2xl` etc. | — | portal-rendered (`DialogPortal`, `components/ui/dialog.tsx:11,34,50`), so outside the page container — the page tier cannot reach them, and their width is a modal-ergonomics decision | n/a |
| table-cell truncation clamps, empty-state copy measures | — | — | character-measure decisions, not layout-contract ones | n/a |

The Stripe Connect panel was **measured, not assumed**: 672px at 1440, 1920 and 2560, in state A, state B **and** state C. It does not follow the band and it did not move when the tier landed — which is the ceiling-not-target rule demonstrated rather than asserted.

Confirmed untouched: `git show --stat` over all six of this plan's commits lists **exactly** the five declared files and nothing else.

---

## Verification — every criterion in both directions

### The TDD gates (fail direction by construction)

| Suite | RED | GREEN |
|---|---|---|
| `orders/[id]/__tests__/detail-tier.test.tsx` | rc=1, **12 failed / 14 passed** | rc=0, 26/26 (20/20 after the describe.each restructure) |
| `onboarding/__tests__/page.test.tsx` | rc=1, **12 failed / 40 passed**, the 12 being 3 branches × 4 assertions | rc=0, 41/41 (38/38 after the restructure) |

**A finding worth reading in both RED runs:** the two branch-parity cases PASSED. They are not vacuous — they were passing because all three branches were equally untiered, which is a correct answer to "do the branches agree". Parity cannot catch "no tier anywhere"; the per-branch assertions do. It is ARM C, which tiers some branches and not others, that proves parity is live. This is why the plan required both kinds of assertion and not just the parity one.

### ARM A — the detail utility removed from the onboarding LOADED root, tier attribute kept

```
BROKEN    className={cn("mx-auto", "space-y-6")}
  ✕ the LOADED branch › carries the detail max-width utility from the tier vocabulary
  ✕ the LOADED branch › carries exactly ONE max-width class
  ✕ the LOADING branch renders the same declaration as the loaded branch
  ✕ the CREATE-FORM branch renders the same declaration as the loaded branch
  4 failed, 34 passed   rc=1
  — and `the LOADED branch › declares the detail width tier` stayed GREEN, which is
    the whole point of the arm: the assertion measures the CLASS, not the attribute
RESTORED  WIDTH_TIER_CLASS.detail references back to 3 (content) | git diff --quiet rc=0 (identity)
          38/38   rc=0
```

### ARM B — a SECOND max-width class on the same root (the double-cap defect)

```
BROKEN    cn("mx-auto", WIDTH_TIER_CLASS.detail, WIDTH_TIER_CLASS.shell, "space-y-6")
  ✕ the LOADED branch › carries exactly ONE max-width class
        - Expected  - 0
        + Received  + 1
          Array [ "max-w-detail",
        +          "max-w-shell",  ]
  3 failed, 35 passed   rc=1
  — `carries the detail max-width utility` stayed GREEN, which is why the two are
    separate assertions; and twMerge kept BOTH classes, so the defect survives the
    joiner exactly as 35-02 predicted for tier keys
RESTORED  WIDTH_TIER_CLASS.shell occurrences back to 0 | git diff --quiet rc=0 | 38/38 rc=0
```

### ARM C — the branch-parity arm: onboarding's `if (!onboarding)` CREATE FORM left untiered

This is the defect the plan was one instruction away from shipping.

```
BROKEN    <div className="space-y-6">            (attribute AND class removed)
  ✕ the CREATE-FORM branch › declares the detail width tier
  ✕ the CREATE-FORM branch › carries the detail max-width utility from the tier vocabulary
  ✕ the CREATE-FORM branch › centres inside the wider Shell band
  ✕ the CREATE-FORM branch › carries exactly ONE max-width class
  ✕ the CREATE-FORM branch renders the same declaration as the loaded branch
        - Expected  - 4        + Received  + 2
          Object { -  "maxWidthClasses": Array [ "max-w-detail" ],  -  "tier": "detail",
                   +  "maxWidthClasses": Array [],                  +  "tier": null }
  5 failed, 33 passed   rc=1
```

**The failure NAMES the branch** — "the CREATE-FORM branch renders the same declaration as the loaded branch" — and the LOADING branch's parity case stayed GREEN throughout, so the output isolates which branch diverged rather than reporting "some branch disagrees". That distinction is what makes it actionable.

```
RESTORED  by CONTENT: 3 tier attributes, 3 class references  |  git diff --quiet rc=0  |  38/38 rc=0
```

### ARM D — added beyond the plan: the order detail ERROR branch left untiered

The plan's three arms all act on onboarding. The order detail page's own parity assertions had **no recorded fail direction**, and the ERROR branch is the one no other arm touches. Falsifiability is a standing criterion, so:

```
BROKEN    the error return reverted to <div className="space-y-4 p-6">
  ✕ the ERROR branch › declares the detail width tier
  ✕ the ERROR branch › carries the detail max-width utility from the tier vocabulary
  ✕ the ERROR branch › centres inside the wider Shell band
  ✕ the ERROR branch › carries exactly ONE max-width class
  ✕ the ERROR branch renders the same declaration as the loaded branch
  5 failed, 15 passed   rc=1
  — the LOADING branch's parity case stayed GREEN
RESTORED  3 tier attributes | git diff --quiet rc=0 | 20/20 rc=0
```

The arm script carried its own guard: if the string replacement had not applied it printed `ARM DID NOT APPLY — refusing to report a result` and exited 2, so a no-op arm could not have been recorded as a pass.

### The sub-component containment check — nested caps, in three directions

The claim "the five nested sub-components are NOT tiered" needed to be checkable rather than asserted.

```
EVIDENCE  onboarding/page.tsx        2 named sub-components, earliest at line 855; tier lines 433 453 613   rc=0
          products/import/page.tsx   3 named sub-components, earliest at line 107; tier lines 51            rc=0
FAIL ARM  a scratchpad copy with data-width-tier planted inside CsvImportTab
          VIOLATION: …:150 declares a tier at/after sub-component territory (starts line 107)   rc=1
VOID ARM  a sub-component name that does not exist
          VOID: none of [NoSuchComponent] found …                                               rc=2
```

**An instrument defect, self-caught and recorded.** The first version of this check anchored on "the first top-level `function `", which in `onboarding/page.tsx` is the helper `httpStatus()` at line 168 — defined *above* the page component. It reported **three violations on a correct tree**: the expected-0-that-is-actually-1 vacuity shape, caught only because the result was read rather than glanced at. The check now names the sub-components explicitly.

The same run also surfaced `app/dashboard/products/page.tsx:389` carrying a tier — that is plan 35-03/35-04's in-flight work in this shared checkout, not this plan's, and it is untouched here.

### The measurement instrument's own fail direction

```
BROKEN   the surface's cap swapped for the Marketing one (1280px)
         1440:1120   1920:1280   2560:1280
RESTORED the Detail cap
         1440:1100   1920:1100   2560:1100
```

The harness reports the VALUE of whichever cap is applied, so the 1100s are a measurement rather than a constant. Three further internal controls: states A, B and C produce three different numbers at 1920 (1336 / 1600 / 1100), so the harness distinguishes the shadcn container from the Shell tier from the Detail tier; and the Stripe panel stays 672 in all three, so it is not simply reporting "whatever the band does".

**A second self-caught instrument defect, worth recording because it reproduces a documented failure mode.** The first run of this arm reported 1120/1600/1636 for `max-w-marketing` — i.e. *no cap at all* — because that class was not in the harness's scanned content, so Tailwind never generated the rule. That is exactly the silent failure `content-tier.tsx`'s docblock warns about: the class is in the markup, the build is clean, and the element renders uncapped. The arm was re-run after regenerating the CSS with the class in scope.

### Closing arms

```
CLOSING CLEAN ARM   all five files == HEAD                      git diff --quiet rc=0
                    npx jest orders/[id] + onboarding/page.test  58 passed / 58   rc=0
TYPE CHECK          npx tsc --noEmit -p tsconfig.json                             rc=0
LINT (scoped)       npx eslint <all five files>        0 errors, 4 warnings, all pre-existing
                    npx eslint <the new test file>                                rc=0, no output
BRANCH vs BASE      scripts/check-branch-behind-base.sh   38 ahead / 0 behind origin/main   rc=0
INFORMATIONAL       npx jest app/dashboard/orders app/dashboard/onboarding app/dashboard/products
                    8 suites / 109 tests   rc=0
```

**The close is TARGETED, and that is a narrowing rather than a skip.** No `npm run build` here: five plans run this wave, each build would only ever have proved one plan's tree in isolation, and the build that answers "does the merged wave compile" is **plan 35-08's**, which depends on all five. Plan **35-06** keeps a real build because its width VALUE changes and it must read the emitted value out of the generated stylesheet. The type-check replaces what the build would have caught here, because Jest does not type-check frontend TypeScript in this repo.

---

## Deviations from Plan

### 1. [Rule 3 — Blocking] `describe.each` put an existing repo gate into VOID

- **Found during:** Task 3's closing checks.
- **Issue:** Both of this plan's suites originally used `describe.each`. `scripts/docs-freshness.sh` exited **rc=2 (VOID)** — not drift — with `count-test-blocks.mjs` naming the file and line: *"describe.each multiplies every block inside it; this counter cannot resolve that statically. Treat this as UNVERIFIED, not as a pass."* That is the correct behaviour for the counter, but it left the **source half of the docs-metrics loop unable to answer at all**, which is a worse state than a wrong number and would have landed on plan 35-11, whose declared file `docs/metrics.json` is.
- **Fix:** Blocks rewritten one per branch. `it.each` over an **array literal** is supported by that counter but a **variable** table is not, so the preserved-class ledgers became single blocks asserting with `arrayContaining`, which still names every class by hand and still prints the missing one on failure. The reason is written at both sites so the next reader does not reintroduce it.
- **Result:** `docs-freshness.sh` rc=2 → **rc=1** (drift: `jest_blocks` 1394 recorded vs 1498 computed).
- **Commit:** `4bdd1711`
- **Deliberately NOT done:** regenerating `docs/metrics.json`. It is plan **35-11's** declared file, the drift is the combined contribution of all five parallel wave-3 plans, and any number written now would be wrong within minutes and would collide with another executor in this shared checkout.

### 2. [Rule 3 — Blocking] The verification command had to be narrowed

- **Issue:** All five wave-3 plans commit to `feature/35-horizontal-layout-contract` in one shared checkout. `npx jest app/dashboard/onboarding` also matches `app/dashboard/onboarding/approvals/__tests__/page.test.tsx` — plan **35-04's** file, which was mid-RED at the time. Reporting that as this plan's result would have been a false attribution in either direction.
- **Fix:** The authoritative close runs this plan's own two suite paths. The plan's broader command was also run, for information, and is now green (8 suites / 109 tests) since 35-04 landed its implementation.

### 3. [Falsifiability — standing criterion] A fourth arm the plan did not specify

ARM D. The plan's three arms all act on onboarding, leaving the order detail page's parity assertions with no recorded fail direction and the ERROR branch untouched by any arm. Recorded in both directions above.

### 4. [Instrument defect, self-caught ×2]

Both recorded in full under Verification: the containment check that fired on a correct tree because it anchored on the wrong function, and the measurement arm that read "uncapped" because the class it was testing had never been generated. Neither produced a conclusion; both were re-run after the instrument was fixed.

### 5. [Blocked command honoured, not rerouted]

A `python3` one-liner for the fail-arm file surgery was blocked by `block-base-python` (no conda env declared for this repo). The block is the answer: the work was redone in `node`, which is this project's own runtime, rather than routed around the guard.

---

## Coverage boundaries, stated rather than implied

- **Nothing in this plan measures a rendered width in the running application.** jsdom cannot measure widths; it proves the class and the attribute are APPLIED and that the branches AGREE. The browser measurements above are of a harness built from the real DOM chain and the real emitted CSS — strong evidence about the CSS semantics, and explicitly **not** a measurement of the deployed dashboard.
- **The import wizard has no mounted test.** It has one page-level branch, so it has no parity claim to make, and the plan directed that no harness be built for a single attribute. Its coverage is: the static gate in **35-10**, and the browser arm in **35-08** — whose dashboard half belongs to a spec that **no current tree executes** (issue **#683** records the nightly lane as dark; the per-PR browser gate runs only `public-layout.spec.ts` + `public-a11y.spec.ts`). On a pull request, **nothing** exercises it. That is the honest statement; "covered nightly" would be false.
- **The same applies to the order detail and onboarding surfaces in a browser.** Their jsdom coverage is real and runs per-PR; their *rendered width* is covered by a spec no current tree executes.
- **The parity assertions cannot catch a globally-untiered page.** Shown, not assumed: both RED runs had them passing. They are paired with per-branch assertions for exactly that reason.
- **ARM F from 35-02 is inherited.** These suites assert against `WIDTH_TIER_CLASS.detail`, so they prove APPLICATION, never the value. If the vocabulary itself said the wrong thing, only `content-tier.test.tsx`'s derivation assertion would catch it.
- **Mobile is untested here and structurally unchanged.** `max-w-detail` is emitted with no media query, so it cannot bind against a narrow parent; below `lg` the surfaces are the same markup with a cap that never applies. The browser arm for mobile belongs to 35-08.
- **`docs/metrics.json` is left in drift** (see deviation 1). Plan 35-11 owns both halves of that loop.

## Cross-Cutting Quality Contracts

- **Web performance: N/A with reason.** No route in this plan owns a recorded budget in `e2e/perf-budgets.ts` (all of which target public surfaces); these are three authenticated dashboard routes. The change is one attribute and two classes per element, no new DOM node, no new dependency, no image, no bundle growth beyond the attribute strings. A narrower cap renders the same content in a narrower column — it adds no bytes.
- **SEO: N/A.** All three surfaces are authenticated dashboard routes, exempt by the standing rule. No metadata, route or markup semantics changed.
- **AI agent-readiness: N/A.** No API surface, no endpoint, no error contract, no credential changed.
- **Security:** threat model below.
- **Falsifiable evidence + runtime parity:** four arms plus the instrument's own arm, all in both directions with real output; the displaced-goods before values measured against the pre-change tree rather than copied from the plan's table. **Runtime parity is NOT claimed** — no container was rebuilt and no running stack was exercised by this plan; that belongs to the phase's closing plans.

## Threat model outcomes

- **T-35-16** (Detail surfaces inheriting the Shell band) — mitigated, and the risk is confirmed bidirectional by measurement: without the tier these surfaces render at 1600/1636 (264–300px **wider** than main); with it they render at 1100 (20–236px **narrower**). The narrowing direction is enumerated in the ledger above with its arithmetic and flagged for the 35-13 gate; the cap is the measured 1016–1136 peer cluster rather than an invented number; "exactly one max-width class" is asserted per root and **proven by ARM B**.
- **T-35-16b** (untiered alternate render branches) — mitigated by tiering all seven page-level branch roots, by parity assertions phrased branch-vs-branch, and **proven by ARM C (create form) and ARM D (error branch)**, each naming the divergent branch. The measured size of the jump this prevents: **500px at 1920, 536px at 2560**.
- **T-35-17** (double max-width class) — mitigated, **proven by ARM B**, including the observation that twMerge keeps both classes so the defect survives the joiner.
- **T-35-18** (already-narrower surfaces widened by a mechanical tier) — mitigated by the ceiling-not-target rule, the nine-row exceptions ledger, and the Stripe panel measured at 672 in all three tree states.
- **T-35-19** (order detail content) — still `accept`: the fields are chosen by the component and gated by the API and Postgres RLS, identical at every width.
- **T-35-SC** — nothing was installed, so the package-legitimacy gate correctly did not run rather than being skipped.

ASVS L2 V4 does not apply (no authorisation decision is made by a width) and V5 does not apply (the tier is a compile-time member of a closed union, never a runtime string). V14: the tier is applied as a utility class, never `style={{ maxWidth }}`, so the CSP's `'unsafe-inline'` allowance is not newly leaned on.

## Cited decisions

**ORCH-03 (orchestrator decision, 2026-08-29)** is why every tiered element carries `data-width-tier` as well as the class: a tier implemented purely as a class is a contract no assertion can distinguish from a forgotten cap. This plan's parity instrument reads that attribute, so ORCH-03 is load-bearing here rather than decorative. ORCH-01/02/04/05 concern public and marketing surfaces this plan does not touch.

## Known Stubs

None. No placeholder, no empty state, no TODO, no hardcoded empty value. Every element this plan touched already rendered real content and still does.

## Threat Flags

None. This plan adds no endpoint, no input, no credential, no data flow and no dependency.

## Requirement progress — recorded truthfully

- **UIX-09: in progress, not complete.** The Detail tier now exists on three dashboard surfaces and every one of their render branches. The requirement's remaining limbs — the browser-measured contract spec (35-08), the scattered-literal gate (35-10) and the written standard (35-11) — are not this plan's.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `45abc0d0` | test | assert the Detail tier on EVERY order-detail render branch (RED) |
| `58a5e887` | feat | the Detail tier on all three order-detail branches (GREEN) |
| `5f9e39b4` | test | assert the Detail tier on all three onboarding branches (RED) |
| `2686ca6d` | feat | the Detail tier on the onboarding form and the import wizard (GREEN) |
| `4bdd1711` | test | drop describe.each so the block counter can still answer |
| `ab002ca8` | style | drop the unused screen import from the detail-tier suite |

Every commit message was written through a quoted heredoc and read back with `git log -1 --format=%B`; none was passed as an interpolating double-quoted string.

## TDD Gate Compliance

Both cycles complete and in order: `test(45abc0d0)` → `feat(58a5e887)`, then `test(5f9e39b4)` → `feat(2686ca6d)`. Each RED gate was observed failing with recorded output before its GREEN commit — 12 substantive assertion failures each time, with the instrument controls and the preserved-class ledgers already passing, which is what proves the RED is about the tier rather than about a broken harness. No RED passed unexpectedly. The two later commits are a gate fix and a lint fix, neither adding behaviour.

## Self-Check: PASSED

All 5 claimed files exist on disk; all 6 claimed commit shas resolve, run with a control (`deadbee` correctly ABSENT) so the FOUND results are about this repository rather than about a check incapable of failing. Tier-element counts re-read from the tree at close: 3 / 3 / 1 = the 7 branches the plan requires.
