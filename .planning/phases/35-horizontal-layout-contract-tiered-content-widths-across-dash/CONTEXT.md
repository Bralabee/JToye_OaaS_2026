# Phase 35 — Context

Gathered 2026-08-29, after the QA-council run `20260829-122447` merged as PR #685.
Origin: owner feedback — *"not enough of the real estate is being utilised… it feels
narrow and confined to the middle… being able to utilise at least two thirds of any
screen's width for the web version is more mature and professional"* — with the explicit
instruction not to take that on faith but to check it against reputable industry examples.

## 1. Measured baseline (evidence, not inference)

Measured in a real browser against the post-#685 runtime (runtime-freshness gate: 4/4
FRESH, 0 unverified — so the numbers describe the merged code, not a stale image).
Instrument: a temporary Playwright probe measuring `main` and the widest `max-width`-
constrained band inside it, at three viewports. Probe deleted after the run.

| Surface | Content band | @1440 | @1920 | @2560 |
|---|---|---|---|---|
| `/` landing | 1152px (`max-w-6xl`) | 80.0% | **60.0%** | **45.0%** |
| `/shop` directory | 1280px (`max-w-7xl`) | 88.9% | 66.7% | **50.0%** |
| `/dashboard*` | 1400px (`container`) | 82.2% | 72.9% | **54.7%** |

Dashboard `main` itself is 82–90% of viewport (the sidebar takes 256px); the **inner
band** is what caps at 1400px. At 1440 the dashboard genuinely uses all available space —
the problem only appears at 1920 and above.

Visual confirmation captured at 2560: the six-column orders table (Order ID, Customer,
Status, Total, Created, Actions) is confined to 1400px with roughly **900px of empty
gutter** to its right. That is the single most persuasive artefact — a data-dense surface
starved of width while the screen sits empty.

## 2. Root cause

`frontend/tailwind.config.ts`:

```
container: { center: true, padding: "2rem", screens: { "2xl": "1400px" } }
```

This is the **stock shadcn/ui scaffold block, shipped verbatim**. It is applied once, at
`frontend/components/dashboard/dashboard-shell.tsx:55`:

```
<div className="container mx-auto p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8 dark:text-slate-100">
```

`rg -uu` over `app/` + `components/` for a declared width standard in `docs/` returns
nothing: **no document in this repo states a content-width standard.** The 1400px is
inherited, not chosen. That is the finding — not "1400 is the wrong number".

Width-cap idioms in the tree: `container` 1 file (the dashboard shell), `max-w-7xl`
9 files, `max-w-6xl` 2 files, plus `max-w-[68ch]` 6 uses for prose (correct, leave alone).

## 3. Industry evidence

Gathered by a research agent that **self-corrected mid-task** — it struck one claimed
value (`Square main 928px`) as unverifiable and re-measured. Values below are the
post-correction set. Where a number could not be verified it is marked so.

| Product | Surface | Width behaviour | Value | Confidence |
|---|---|---|---|---|
| Stripe Dashboard | app shell | fixed cap | **1690px** (`--Chrome-maxWidth`) | measured |
| Square (docs shell) | app shell | fixed cap | **1720px** | measured |
| Square (design site) | body | fixed cap | 1680px | measured |
| IBM Carbon | max grid | fixed + opt-out | 1584px, plus `--full-width` | documented |
| Vercel | content | fixed cap | 1400px | measured |
| GitHub Primer | page / content | fixed cap | 1280 / 1012px | documented |
| GitLab | page | 1280 fixed **with fluid option** | 1296 / 1006px in CSS | documented |
| Shopify Polaris | resource index | **full-width page pattern** | n/a (`none`) | documented |
| Shopify Polaris | default page | fixed | 998 / 662px | documented |
| Lightspeed | app shell | **fluid, uncapped** | — | measured |
| Lightspeed | content column | fixed | 1100px (`.vd-g-row`), nested escape `max-width:none` | measured |
| Linear | detail content ladder | stepped fixed | 448/688/880/**1136px** | measured |
| Square design | content ladder | stepped fixed | 276→…→**1016px** | measured |
| Stripe marketing | marketing page | fixed cap | 1264px | measured |
| Shopify Dawn | storefront | configurable | 1000–1600, default 1200 | documented |

**The convergent cluster.** Three independent products sit within 40px of each other at
**1680–1720px** for the application shell: Stripe's actual dashboard (1690), Square's docs
shell (1720), Square's design site body (1680). That band is **88% of 1920 and ~66% of
2560** — i.e. it lands exactly on the owner's "at least two thirds" instinct. The instinct
is not a preference; it reproduces the measured industry ceiling for a fixed cap.

**The second, independent finding.** Data-dense *resource index* surfaces are the one case
where the documented advice is to abandon the cap entirely: Polaris prescribes a
full-width page for "lists of data that have many columns"; Carbon ships `--full-width`;
GitLab offers a fluid preference; Lightspeed's shell is uncapped. So the mature pattern is
**not one width** — it is a cap for chrome plus an opt-out for tables.

**The third.** Reading/detail columns cluster tightly at **1016–1136px** (Square, Linear),
and prose measure guidance (45–75 characters) is why. Widening a settings form or an order
detail to 1700px would be a regression, not an improvement.

**Explicitly NOT verified — do not cite as closest-domain evidence.** Square's *Market*
design system has no public spec (site, GitHub repo and npm package all 404) and the Square
Dashboard is Cloudflare-challenged. Toast yields no number from any route (Buffet is a
client-rendered SPA, the npm scope is empty against a working control, the GitHub org is
migration stubs, the back-office 403s). Every Square and Toast row above is a **substitute
surface** (docs/design site), not the POS back-office itself. The closest true peers we
could measure are Stripe, Lightspeed, GitLab and Shopify.

## 4. The contract to build

| Tier | Target | Applies to | Peer |
|---|---|---|---|
| **Shell** | ~1700px | dashboard chrome | Stripe 1690, Square 1680–1720 |
| **Index** | fluid to shell | products, orders, customers, shops | Polaris full-width, Carbon `--full-width` |
| **Detail** | ~1100px | order detail, settings, forms | Linear 1136, Square 1016, Lightspeed 1100 |
| **Marketing** | ~1280px | landing, for-operators, business-model-guide | Stripe marketing 1264 |
| **Prose** | 68ch — unchanged | body copy | already correct in-tree |

Declared in **one config module**, following the existing `frontend/e2e/perf-budgets.ts`
convention (a declared budget other code and tests import), never scattered literals.
This satisfies the global config rule and gives the visual tests something to assert
against rather than duplicating magic numbers.

## 5. Constraints and known hazards

- **CLS control.** `/` already measures **CLS 0.1793** against a `CLS_BUDGET` of 0.1
  (`frontend/e2e/perf-budgets.ts:35,49-56`) — pre-existing debt, documented, not a
  regression. The landing change must be measured against that recorded baseline as a
  control arm. "It still breaches the budget" is expected; "it got worse" is the failure.
- **Mobile must not move.** This is a large-screen change. Every tier's behaviour below
  the `lg` breakpoint should be byte-identical. The `mobile` Playwright project is the
  guard, and a viewport is not a device — check `matchMedia` before trusting a mobile pass.
- **A11Y-3 regression risk.** #685 made the dashboard `overflow-x-auto` region
  keyboard-focusable (`tabindex=0` + name). Widening the index tier changes when that
  region overflows; the axe `scrollable-region-focusable` rule must stay clean and the
  region must keep its accessible name.
- **No body horizontal scroll at 390px** — `public-layout.spec.ts` and the council's FE-1
  fix both assert this. Widening must not reintroduce it.
- **Incremental Betterment.** These are live user-visible surfaces. Enumerate each
  displaced good and preserve or better it; prefer additive changes (a wider tier that
  existing content flows into) over re-layouts.
- **Falsifiability.** Every acceptance criterion is run against a deliberately broken
  input first and both directions recorded. A width assertion that has only ever passed
  may be incapable of failing — the cheapest control is the pre-change build.

## 6. Verification approach

- A committed Playwright width-contract spec asserting **measured** band width per tier at
  1440/1920/2560, importing the declared constants (so the contract and the assertion
  cannot drift). Must be shown failing against the pre-change tree.
- Re-measure CLS/LCP on `/` and `/shop` against the recorded baselines in `perf-budgets.ts`.
- axe clean on the widened dashboard surfaces (the #685 jest-axe gate plus the nightly spec).
- Visual capture at 1920 and 2560 for the orders table, before and after.
- Full Jest + Playwright suites green; `docs/metrics.json` regenerated via
  `scripts/docs-freshness.sh --write` if test counts move (never hand-edited).
