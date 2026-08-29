# Phase 35: Horizontal Layout Contract — Research (the HOW)

**Researched:** 2026-08-29
**Domain:** Tailwind CSS 3.4 theme authoring · CSS layout/CLS · Playwright measurement contracts
**Confidence:** HIGH (every load-bearing claim below is either measured on this tree or cited)

> **Scope note.** `CONTEXT.md` owns the WHAT — the measured baseline, the root cause, and the
> industry width evidence. **None of that is redone here.** This document is the HOW: which
> Tailwind mechanism, which CSS risks, which test shape. Where this research *contradicts a
> premise in the brief*, it says so and shows the measurement.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

The four-tier contract, §4 of CONTEXT.md, copied verbatim:

| Tier | Target | Applies to | Peer |
|---|---|---|---|
| **Shell** | ~1700px | dashboard chrome | Stripe 1690, Square 1680–1720 |
| **Index** | fluid to shell | products, orders, customers, shops | Polaris full-width, Carbon `--full-width` |
| **Detail** | ~1100px | order detail, settings, forms | Linear 1136, Square 1016, Lightspeed 1100 |
| **Marketing** | ~1280px | landing, for-operators, business-model-guide | Stripe marketing 1264 |
| **Prose** | 68ch — unchanged | body copy | already correct in-tree |

> Declared in **one config module**, following the existing `frontend/e2e/perf-budgets.ts`
> convention (a declared budget other code and tests import), never scattered literals.

Constraints and known hazards, §5, copied verbatim:

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

### Claude's Discretion

CONTEXT.md declares no explicit discretion block. Everything not fixed by the tier table
above is discretionary — specifically: the Tailwind mechanism, the module's file path and
name, the component/helper shape, the spec file layout, and whether a static drift gate is
added. This document recommends each.

### Deferred Ideas (OUT OF SCOPE)

CONTEXT.md declares no deferred block. Out of scope by direct implication of §4/§5:
prose measure (68ch — "unchanged", "already correct in-tree"), any mobile (<`lg`) change,
and fixing the pre-existing `/` CLS 0.1793 debt (CONTEXT explicitly frames it as a control
arm, not a target; `perf-budgets.ts` says fixing it "is outside 33-03's file set and is its
own scoped work").
</user_constraints>

---

## Summary

The current 1400px cap is produced by Tailwind's `container` **core plugin**, and that plugin
is structurally incapable of expressing a four-tier system for two measured reasons: its
selector `.container` is hardcoded (one class for the whole app), and `theme.container.screens`
forces each tier's max-width to **equal** the breakpoint that activates it. Both were confirmed
by reading the installed plugin source and by generating the CSS. The correct mechanism is
`theme.extend.maxWidth` with named semantic keys, sourced from one TypeScript constants module
that `tailwind.config.ts` imports by **relative** path (the `@/` alias does **not** resolve in
the Tailwind config — measured, it throws).

The single most useful structural finding is that **a `max-width` cap is self-gating and needs
no responsive variant at all.** `max-width: 1700px` on the dashboard shell emits *no media
query*; it simply does not bind until the parent offers more than 1700px, which cannot happen
below roughly a 1956px viewport. So "mobile is byte-identical" stops being a promise you test
for and becomes a property of the emitted CSS you can diff. This matters directly for the CLS
constraint: `perf-budgets.ts`'s CLS arm runs at **375px throttled mobile**, and a rule that
emits no media query and does not bind at 375px cannot move that number. Per web.dev, a
max-width change is a *size* change, not a start-position change, and size changes contribute
zero to CLS unless they displace other elements.

Three premises in the brief needed correcting against measurement, and each correction
simplifies the work. (1) **Tailwind 3.4 does *not* support `@container` natively** — container
queries landed in v4; 3.4.19's `corePlugins.js` contains no `container-type` and the
`@tailwindcss/container-queries` plugin is not installed. The question "container queries or
overkill" resolves to "unavailable", so it is moot. (2) The axe `scrollable-region-focusable`
rule is **runtime-geometric, not structural** — its matcher requires `scrollWidth > clientWidth + 13`
— which means the blocking per-PR jsdom gate is *structurally unable* to evaluate it, and the
A11Y-3 protection can only be verified in a real browser. (3) The Marketing tier is **already
1280px** on two of its three target surfaces (`max-w-7xl` is exactly 1280px); only `/` at
`max-w-6xl` (1152px) actually moves.

**Primary recommendation:** Declare the four numeric widths in `frontend/lib/layout-widths.ts`
(pure constants, no imports). Import it relatively into `tailwind.config.ts` to generate
`max-w-shell` / `max-w-detail` / `max-w-marketing` via `theme.extend.maxWidth`. Apply them as
**unconditional** utilities — zero `xl:`/`2xl:` variants. Import the same module relatively into
a new `e2e/layout-width-contract.spec.ts` (exact precedent: `e2e/public-a11y.spec.ts:75` already
imports `../lib/cart-identity`). Assert `band.width === Math.min(parent.clientWidth, TIER)`,
never a bare constant.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Declaring the four numeric widths | Shared TS module (`lib/`) | — | Must be readable by the Tailwind config (build), the app (runtime) and the Playwright spec (test). Only a plain TS module is reachable by all three. |
| Turning numbers into CSS utilities | Build config (`tailwind.config.ts`) | — | Tailwind's theme is the only place that turns a value into a scanned, purgeable utility class. |
| Applying a tier to a surface | Frontend Server (RSC/layout) | Browser/Client | The dashboard shell is a client component; the marketing shells are server components. Both apply a static class — no tier decision is ever made at runtime. |
| Keeping tables sane inside a fluid tier | Browser (CSS layout) | — | `table-layout` and `min-width` are pure CSS; no JS, no server involvement. |
| Enforcing the contract | Test (Playwright) + CI gate | Build (`tsc`) | Only a real browser can measure a computed band. `check-e2e-typecheck.sh` catches the drift where the spec stops compiling. |
| Preventing scattered literals | CI gate (`scripts/check-*.sh`) | — | A prose rule does not survive; per CLAUDE.md, the fix is a script that fails loudly. |

**No tier reassignment is required by this phase.** Nothing moves between browser/server/API —
this is a CSS-only change with a test contract on top.

---

## Project Constraints (from CLAUDE.md)

Directives the planner must verify compliance against:

| Directive | Source | Bearing on this phase |
|---|---|---|
| All new code requires tests; counts are the SSOT in `docs/metrics.json`, enforced by **two** gates | Constraints | A new spec bumps `playwright_specs` 26→27 and `playwright_blocks`. Regenerate with `scripts/docs-freshness.sh --write` — **never** hand-edit (`trap_docs_freshness_block_counter`). |
| Always rebuild ALL containers after code changes before E2E | Constraints / Proof Standard §2, §4 | A CSS-only change still ships in a rebuilt frontend image. `scripts/check-runtime-freshness.sh` will VOID otherwise. |
| Compose is the canonical local E2E runtime | Constraints | Dashboard tiers need the full stack; only the public tiers are stack-free. |
| Web performance is a standing acceptance criterion for any phase touching a user-facing page | Cross-Cutting Quality Contracts | Measure against `perf-budgets.ts`, throttled mobile, never localhost-unthrottled. |
| SEO applies to public/unauthenticated surfaces | Cross-Cutting Quality Contracts | `/`, `/for-operators`, `/business-model-guide`, `/shop` are in scope. A width change alters no metadata — record **N/A with the reason**, do not silently drop it. |
| AI agent-readiness applies to API surfaces | Cross-Cutting Quality Contracts | No API surface changes. Record **N/A**. |
| Falsifiable evidence: every criterion shown FAILING before trusted | Cross-Cutting Quality Contracts (a) | See "Falsifiability Playbook" below — pre-change build is the control arm. |
| Runtime parity: the delivered runtime must match the branch | Cross-Cutting Quality Contracts (b) | `check-runtime-freshness.sh` + `check-branch-behind-base.sh`, both fail-closed at exit 2. |
| Incremental Betterment: enumerate displaced goods; regression by omission is a defect | Incremental Betterment Doctrine | Every surface that loses `max-w-6xl`/`max-w-7xl`/`container` must have its displaced good named and preserved. |
| Every static `check-*.sh` must be referenced by a workflow, or carry a `gate-enforcement.conf` entry — **default-deny** | `scripts/check-gate-enforcement.sh` | If the plan adds a drift gate, wiring it into CI is **mandatory in the same commit**, or CI reds. |
| No emojis | Global | — |
| Work on a feature branch, never main | Git Policy | — |

---

## Standard Stack

**No new packages. Nothing is installed by this phase.** Everything needed is already present.

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `tailwindcss` | **3.4.19** (installed; `package.json` says `^3.4.1`) | `theme.extend.maxWidth` is the mechanism | Already the project's styling layer. [VERIFIED: `require('tailwindcss/package.json').version`] |
| `jiti` | **1.21.7** (transitive dep of tailwindcss) | Lets `tailwind.config.ts` be TypeScript and import other TS modules | Bundled by Tailwind itself; no install needed. [VERIFIED: `tailwindcss/package.json` dependencies] |
| `@playwright/test` | 1.62.1 | Measures the computed band in a real browser | The repo's only real-browser instrument. |
| `jest` + `next/jest` | 29.7.0 | Structural (class-presence) assertions, stack-free | Already the blocking per-PR unit gate. |
| `axe-core` | **4.13.0** | `scrollable-region-focusable` verification | Already installed, used by both the jest-axe gate and the nightly Playwright sweep. [VERIFIED: `require('axe-core/package.json').version`] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `theme.extend.maxWidth` | `theme.container.screens` | **Rejected — structurally impossible.** See "Why the container plugin cannot do this" below. |
| `theme.extend.maxWidth` | CSS custom properties + `max-w-[var(--shell)]` | Rejected: the value then lives in CSS, unreadable by the Playwright spec, so the contract and the assertion *can* drift — the exact failure the "one config module" rule exists to prevent. |
| `theme.extend.maxWidth` | `@container` queries | **Rejected — unavailable in v3.4.** [VERIFIED: measured, see below] |
| Unconditional utilities | `xl:` / `2xl:` responsive variants | Rejected: strictly more CSS for identical rendering, and it forfeits the "no media query emitted" proof. See §3. |
| New `@tailwindcss/container-queries` install | — | Rejected: adds a dependency to solve a problem the cap does not have (see §1). |

## Package Legitimacy Audit

**Not applicable — this phase installs no external packages.** Every dependency named above is
already in `frontend/package-lock.json` and already runs in CI. No `slopcheck` run is required
because no new package name enters the tree. If the plan later proposes an install (the only
candidate would be `@tailwindcss/container-queries`, which this research recommends **against**),
the Package Legitimacy Gate must be run at that point.

---

## 1. Tailwind 3.4 container strategy — recommendation and evidence

### 1a. Why the `container` core plugin cannot express a multi-tier system

Read from the installed plugin at `frontend/node_modules/tailwindcss/lib/corePlugins.js`
[VERIFIED: source read on this tree]:

```js
let screens = normalizeScreens(theme("container.screens", theme("screens")))
let minWidths = extractMinWidths(screens)
let atRules = Array.from(new Set(minWidths.slice().sort(...))).map((minWidth) => ({
    [`@media (min-width: ${minWidth})`]: {
      ".container": { "max-width": minWidth, ...generatePaddingFor(minWidth) }
    }
}))
```

Two disqualifying properties, both visible in that snippet:

1. **The selector `.container` is a hardcoded string literal.** There is exactly one container
   class per Tailwind build. Four tiers need four classes.
2. **`max-width` is assigned the breakpoint's own `minWidth`.** The cap *is* the breakpoint.
   You cannot say "cap at 1700px, starting from 1280px" — asking for a 1700px cap forces the
   media query to `min-width: 1700px`.

Confirmed by generating the CSS from the repo's exact config block [VERIFIED: PostCSS run,
2026-08-29]:

```
/* ARM 1 — the current shadcn block, verbatim from frontend/tailwind.config.ts */
.container { width:100%; margin-right:auto; margin-left:auto; padding-right:2rem; padding-left:2rem }
@media (min-width: 1400px) { .container { max-width: 1400px } }
```

Note what this proves about **today's** behaviour, which the plan should state rather than
assume: the current config emits exactly **one** media query. Below 1400px `.container` is
fully fluid. The 1400px cap only ever engages at viewports ≥1400px — which is precisely why
CONTEXT.md's baseline shows the dashboard using all available space at 1440 and only starving
at 1920+.

The control arm (same config with the `screens` key removed) emits five media queries, one per
stock breakpoint, each capping at its own breakpoint value — confirming that `container.screens`
*replaces* rather than extends, and confirming the cap==breakpoint coupling.

### 1b. Container queries: not available on this version

The brief states "Tailwind 3.4 supports `@container` queries natively". **This is false for
v3.4** and the correction is measured, not argued:

| Probe | Result |
|---|---|
| `rg -uu 'containerQueries\|container-type\|@container' node_modules/tailwindcss/src/corePlugins.js` | rc=1 (no match) |
| **Control**, same file, same pattern shape: `rg -uu -c 'aspectRatio' …` | rc=0, 1 match — proves the probe *can* match |
| `ls node_modules/@tailwindcss/container-queries` | No such file or directory |
| `rg -uu 'container-queries' package.json` | rc=1 (not a declared dependency) |

[VERIFIED: measured on this tree with a fail-direction control, 2026-08-29]

Official position: container queries are built into **v4.0**; v3.x requires the
`@tailwindcss/container-queries` plugin. [CITED: github.com/tailwindlabs/tailwindcss-container-queries]

**Recommendation: do not introduce container queries.** Beyond availability, they are the wrong
tool here. A container query asks "how wide is my parent?" so a *component* can restyle itself.
This phase asks "how wide may this band grow?", which is a plain cap. A cap needs no query
because CSS `max-width` already only binds when the parent is wider (§3). Adding a plugin,
a `container-type` (which creates a containment context and can affect layout), and a new
variant syntax to express a clamp would be overkill by any reading.

### 1c. Recommended mechanism

**`theme.extend.maxWidth` with named semantic keys, values imported from one TS module.**

Verified working end-to-end [VERIFIED: PostCSS + jiti run on this tree]:

```
/* ARM 3 — theme.extend.maxWidth: { shell:'1700px', detail:'1100px', marketing:'1280px' } */
.max-w-shell     { max-width: 1700px }
@media (min-width: 1280px) { .xl\:max-w-detail    { max-width: 1100px } }
@media (min-width: 1536px) { .\32xl\:max-w-marketing { max-width: 1280px } }
```

Named keys compose with variants normally (shown above), and — critically for §3 — the
*unconditional* form emits **no media query at all**.

This is also what the Tailwind docs prescribe for exactly this case: *"You can add custom named
sizes by extending the `theme.maxWidth` section"*, and *"values defined in `theme.maxWidth` take
precedence over values defined in `theme.spacing`"*. [CITED: v3.tailwindcss.com/docs/max-width]

**Trade-offs, honestly:**

| Approach | Type-safe values shared with tests | Emits one class per tier | Needs a plugin | Verdict |
|---|---|---|---|---|
| `theme.extend.maxWidth` from a TS module | Yes | Yes | No | **Recommended** |
| `theme.container.screens` | No | No (one `.container`) | No | Impossible |
| CSS vars + `max-w-[var(--x)]` | No (value lives in CSS) | Yes | No | Drift risk |
| Component class layer (`@layer components`) | No | Yes | No | Same drift risk as CSS vars, plus a cascade-order hazard against utilities |
| `@container` queries | n/a | n/a | **Yes** | Unavailable on 3.4 |

### 1d. The two plumbing facts that will otherwise cost a debugging session

**(i) `tailwind.config.ts` cannot resolve the `@/` alias.** Tailwind loads the TS config through
jiti, which does not read `tsconfig.compilerOptions.paths`. Measured, both directions
[VERIFIED: 2026-08-29]:

```
import { LAYOUT_WIDTHS } from "./lib/layout-widths"   ->  rc=0, utilities emitted
import { LAYOUT_WIDTHS } from "@/lib/layout-widths"   ->  rc=1, "Cannot find module '@/lib/layout-widths'"
```

**Use a relative import in the Tailwind config.** This is a build-time hard failure, not a
silent one — but it will look like a Tailwind bug if you have not seen it.

**(ii) `lib/` is outside the `content` globs, so class-name *strings* living there are silently
dropped.** The config scans only `./pages/**`, `./components/**`, `./app/**`. Measured with both
arms [VERIFIED: 2026-08-29]:

```
class name declared in lib/       -> emitted in CSS? false
class name declared in components/ -> emitted in CSS? true
```

This is the dangerous one, because it fails **silently**: the class is never generated, the
element gets no cap, and everything still builds and renders — just at the wrong width. Two safe
shapes:

- Keep the **numeric values** in `lib/layout-widths.ts` (never a class-name string), and put the
  **class-name literals** in a component under `components/` (scanned). *Recommended* — it keeps
  the concerns cleanly split and needs no config change.
- Or add `./lib/**/*.{ts,tsx}` to `content`. Cheap and additive, but the resulting CSS-size delta
  should be **measured, not assumed**, before it is adopted.

---

## 2. Avoiding CLS when changing container widths

### 2a. What actually counts as a shift

> *"Note that layout shifts only occur when existing elements change their start position. If a
> new element is added to the DOM or an existing element changes size, it doesn't count as a
> layout shift — as long as the change doesn't cause other visible elements to change their
> start position."* [CITED: web.dev/articles/cls]

So a max-width change contributes to CLS **only indirectly**, and only if it displaces something.
A statically wider container that is wider from the first paint is not a shift at all — CLS
measures *change between frames during the session*, not "is this layout different from
yesterday's build".

### 2b. The structural argument that this phase cannot regress the measured CLS budget

`perf-budgets.ts`'s CLS arm and the recorded `LANDING_CLS_KNOWN_BASELINE = 0.1793` are both
measured **at a 375px viewport with 4x CPU throttling** (`perf-budgets.ts` docblock, and
`landing-webperf.spec.ts` / `webhooks-webperf.spec.ts:179` pin `viewport: {width: 375}`).

If every tier is expressed as an **unconditional `max-width`** whose value exceeds any mobile
viewport, then at 375px:

- the emitted rule carries **no media query**, so it is present but
- `max-width: 1100px` (the narrowest tier) never binds against a ~343px parent.

The computed geometry at 375px is therefore *identical*, and this is checkable by diffing the
generated CSS rather than by hoping. That is a much stronger position than "we tested it and it
looked the same".

### 2c. The four real mechanisms, and which are live here

| # | Mechanism | Live on this tree? | Mitigation |
|---|---|---|---|
| 1 | **Responsive grid re-columning** — a wider container newly satisfies an `xl:grid-cols-*`, changing row count and total height; if the grid hydrates late, that height change moves everything below it. | **NO — measured absent.** `2xl:` appears in **zero** `.tsx` files under `app/` + `components/`; `xl:` appears in exactly **one** (`app/dashboard/kitchen/page.tsx`, 2 uses). Control: `lg:` matches 17 files with the identical pattern shape, so the empty result is about the tree, not the pattern. The dashboard stat grid tops out at `lg:grid-cols-4`. | None needed. Cards get wider; the column count cannot change. **If the plan adds any `xl:grid-cols-*`, this mitigation is void and the risk becomes live.** |
| 2 | **Font-swap reflow** — the fallback→web-font swap re-wraps text; at a different container width the wrap point differs, so the height delta differs. | Partially mitigated already. `app/layout.tsx` uses `next/font/google` `Work_Sans`, and next/font's `adjustFontFallback` defaults to true, emitting a `size-adjust`ed local fallback (`@font-face { src: local("Arial") }` + override metrics). [VERIFIED: `next/dist/compiled/@next/font/dist/google/loader.js:66,156`] | Nothing to add. Do not disable `adjustFontFallback`. |
| 3 | **Images without reserved boxes** — an image in a now-wider box that has no intrinsic dimensions reserves nothing and pushes content when it decodes. | Guarded. `SafeImage` forwards `width`/`height` to the `<img>` (Phase 24 D-07), and `public-layout.spec.ts` already runs a generic `aspect-ratio` conformance sweep plus a `naturalWidth > 0` broken-image check across every element on the page. | Reuse the existing sweep; do not write a new one. Any newly-widened surface must keep passing `aspectViolations()`. |
| 4 | **Late-hydrating client islands** — the recorded 0.1793 shift on `/` fires at ~1516ms with all sources in the hero island. | Live, but **pre-existing and out of scope** (CONTEXT §5; `perf-budgets.ts` explicitly scopes the fix elsewhere). | Use it as the **control arm**, exactly as CONTEXT directs: assert the no-regression form against `LANDING_CLS_KNOWN_BASELINE ± LANDING_CLS_TOLERANCE`. Do **not** raise `CLS_BUDGET`. |

### 2d. The gap worth naming

`/` is the one surface whose tier value actually changes (1152 → 1280, §5 below). That change is
invisible at 375px and therefore **invisible to every CLS instrument the repo currently owns**.
A desktop-viewport CLS regression on `/` would go unmeasured.

Two honest options for the planner, in preference order:

1. Add a **desktop-viewport CLS arm** for `/` in the new spec, recording its own baseline the
   same way `perf-budgets.ts` records the mobile one (measure first, declare the number, never
   invent it). This is the falsifiable option.
2. Accept the gap and **write it down** as a known-unmeasured dimension. Acceptable only if
   stated explicitly — an unmeasured dimension that nobody records reads as a passing one.

---

## 3. Responsive-tier authoring — the idiom, and why no variant is needed

### 3a. The recommendation: zero responsive variants

**A `max-width` cap is self-gating.** CSS `max-width` clamps only when the used width would
otherwise exceed it. A cap larger than any small-screen parent is inert on small screens — with
no `@media` rule, no variant, and nothing to get wrong.

Concretely, for the dashboard shell inside `main` (viewport − 256px sidebar, `w-64` = 16rem =
256px [VERIFIED: `components/dashboard/sidebar.tsx:65`]):

| Viewport | `main` width | `max-w-shell` (1700px) binds? |
|---|---|---|
| 390 (mobile, sidebar hidden) | 390 | No |
| 1024 | 768 | No |
| 1440 | 1184 | No |
| 1920 | 1664 | No |
| **~1956** | **1700** | **First viewport at which it binds** |
| 2560 | 2304 | Yes → band 1700 |

`max-w-detail` (1100px) and `max-w-marketing` (1280px) are likewise inert far above any mobile
viewport.

So the answer to "how should tiers behave below the large breakpoints" is: **they need no
authored behaviour, because they have none.** The emitted CSS is a single unconditional
declaration (ARM 3 above proves `.max-w-shell { max-width: 1700px }` with no media query), and
"mobile is byte-identical" becomes a diffable property rather than a claim.

### 3b. If a variant is nevertheless wanted, this is the idiom

The Tailwind idiom is **mobile-first min-width variants** — an unprefixed utility applies at all
sizes and a `xl:`/`2xl:` prefix *adds* a rule above that breakpoint (`xl` = 1280px, `2xl` = 1536px;
generated media queries visible in ARM 3). Small screens are untouched because the prefixed rule
sits inside a `min-width` query they never match.

Two facts make this purely additive here if the plan chooses it: `2xl:` is used **nowhere** in
the tree and `xl:` in one file (§2c #1), so no existing rule can be displaced.

**But prefer 3a.** Variants here would produce byte-for-byte identical rendering while adding
media queries, a second thing to keep in sync, and — the real cost — they would forfeit the
"no media query emitted" proof that makes the mobile-safety claim cheap to verify.

### 3c. The one substitution to check when replacing `container`

Swapping `container` for `mx-auto max-w-shell` at `dashboard-shell.tsx:55` drops three
declarations. Each is accounted for [VERIFIED: CSS generation from the repo's own class string]:

| Dropped | Replacement / why it does not matter |
|---|---|
| `margin-left/right: auto` | Already duplicated by the `mx-auto` on the same element. |
| `padding-left/right: 2rem` | **Already dead.** `.container` is a *components*-layer rule; `p-4` / `sm:p-8` are *utilities*, emitted later at equal specificity, so the utility wins at every width. Generated CSS confirms the ordering: `.container{…padding:2rem}` then `.p-4{padding:1rem}` then `@media(min-width:640px){.sm\:p-8{padding:2rem}}`. |
| `width: 100%` | `main` is `flex-1 overflow-y-auto` but its own `display` is `block`, so children are block-level and fill by default; with `box-sizing: border-box` (Tailwind preflight) the two are equivalent, and auto margins centre correctly under both once the cap binds. Empirically corroborated: every marketing surface already uses bare `mx-auto max-w-7xl` with no `w-full` and centres correctly. |

`container` is used at **exactly one site** (CONTEXT §2), so this analysis is exhaustive rather
than a sample.

### 3d. A validated arithmetic model (use it to predict, then falsify)

Band width = `min(parent content width, TIER)`. Against CONTEXT's three measured cells with
today's 1400px cap:

| Viewport | Model | CONTEXT measured | Match |
|---|---|---|---|
| 1440 | min(1440−256, 1400) = 1184 → 82.2% | 82.2% | ✓ |
| 1920 | min(1664, 1400) = 1400 → 72.9% | 72.9% | ✓ |
| 2560 | min(2304, 1400) = 1400 → 54.7% | 54.7% | ✓ |

Three for three. Predicted post-change, Shell = 1700:

| Viewport | Band | % of viewport | vs today |
|---|---|---|---|
| 1440 | 1184 | 82.2% | **unchanged — this is a falsifiable must-not-move** |
| 1920 | **1664 (fluid — no cap binds)** | 86.7% | +264px |
| 2560 | 1700 (capped) | 66.4% | +300px |

Note the 1920 row: the shell is **fluid** there, not capped. An assertion written as
`expect(width).toBe(1700)` would fail at 1920 on a perfectly correct build. This is the single
most likely way to ship a wrong test — see §5.

---

## 4. Table / index surfaces

### 4a. The nesting is a double scroll container, and this changes the a11y analysis

`components/ui/table.tsx` renders its own wrapper [VERIFIED: source read]:

```tsx
<div className="relative w-full overflow-auto">
  <table className={cn("w-full caption-bottom text-sm", className)} … />
</div>
```

So the products/orders markup is:

```
div.overflow-x-auto[tabindex=0][role=region][aria-label]   <- OUTER, the #685 A11Y-3 fix
  └ div.relative.w-full.overflow-auto                      <- shadcn's own wrapper
      └ table.w-full                                       <- table-layout: auto
```

The **inner** wrapper is the element that actually scrolls: it is `w-full`, so it never exceeds
the outer's content box, and its `overflow: auto` clips the table. The outer's `scrollWidth`
therefore equals its `clientWidth` and it never scrolls. The existing test's own comment already
notices the double wrapper (`mobile-header-and-scroll-a11y.test.tsx`: *"the `Table` component
itself wraps `<table>` in its own `overflow-auto` div, so this is one level further out than
`table.parentElement`"*), but the geometric consequence has not been written down.

### 4b. What axe actually checks — read from axe-core 4.13.0, not inferred

```js
function scrollableRegionFocusableMatches(node, virtualNode) {
  return get_scroll_default(node, 13) !== void 0 && _isComboboxPopup(virtualNode) === false
      && isNonEmptyElementOutsideViewableRect(virtualNode);
}
function getScroll(elm, buffer = 0) {
  var overflowX = elm.scrollWidth  > elm.clientWidth  + buffer;
  var overflowY = elm.scrollHeight > elm.clientHeight + buffer;
  if (!(overflowX || overflowY)) return;                       // <- no overflow: rule does not apply
  …
}
```
Rule wiring: `{ id:'scrollable-region-focusable', … any: ['focusable-content','focusable-element'] }`
[VERIFIED: `node_modules/axe-core/axe.js`, all three excerpts read on this tree]

Three consequences the plan must act on:

1. **The rule is runtime-geometric, not structural.** A widely-repeated summary (including the
   answer Deque's rule page yields to a summarising fetch) calls it a markup check. It is not:
   it requires real overflow of more than a **13px buffer**, plus a descendant rect outside the
   element's bounding rect.
2. **The blocking per-PR jsdom gate cannot evaluate it.** In jsdom every `scrollWidth` and
   `clientWidth` is 0, so `getScroll` returns `undefined` and the rule never applies. The
   `__tests__/dashboard-a11y-axe.test.tsx` gate is *structurally blind* to this rule — it has
   been passing vacuously for it. **Only the real-browser nightly sweep can verify A11Y-3.**
3. **The rule passes via `any: [focusable-content, focusable-element]`.** The products and orders
   tables carry per-row action `<Button>`s, so the inner scroller satisfies `focusable-content`
   independently of the outer region's `tabindex`. Tables with **no** focusable cells (finance,
   dashboard overview, customers, shops) depend on there being no overflow at all.

**Net direction of risk: widening makes this strictly better, never worse.** A wider band means
less overflow at large viewports, so fewer elements can match the rule. At 390px the caps are
inert (§3a), so nothing changes there either. The correct posture is therefore *verify, do not
fear* — but verify **in a browser**, because the gate that runs on every PR cannot answer.

### 4c. Keeping columns sane as width grows

The table is `w-full` with the CSS default `table-layout: auto`. As the band widens, the browser
redistributes the extra space across columns in proportion to content demand — long text columns
(Customer, Title) absorb most of it, short ones (Status, Price) grow little. For variable-length
vendor data that is the right default, and it is what every peer in CONTEXT's industry table does
for resource indexes.

| Control | Effect | Recommendation |
|---|---|---|
| `table-layout: auto` (current) | Content-driven column widths; a wide column absorbs slack | **Keep.** It degrades gracefully at every width. |
| `table-layout: fixed` | Columns sized only by the first row / explicit widths; ignores content | **Avoid** unless a `<colgroup>` with explicit widths is authored. Without one it truncates unpredictably and produces a *worse* result as width grows. |
| `min-w-[Npx]` on the `<table>` | Guarantees the table stops shrinking at N and scrolls instead of crushing columns | **Add where a table crushes at 390px.** In-tree precedent: `business-model-guide.tsx:227` uses `min-w-[640px]`. Note the interaction: raising `min-w` *increases* overflow at 390px, so any new value must be re-checked against the 390px assertion. |
| `max-w-[Nch]` + `truncate` on a cell | Caps one runaway column so the others get the slack | Use per-column, only where a single column visibly monopolises the new width. Requires a `title`/accessible full value so truncation does not hide data. |
| Removing the outer `overflow-x-auto` region | — | **Do not.** It carries the `role="region"` + `aria-label` that gives screen-reader users a named landmark, and `mobile-header-and-scroll-a11y.test.tsx` asserts `tabIndex="0"` on it. That is a *displaced good* under the Incremental Betterment doctrine. |

### 4d. The 390px no-horizontal-scroll assertion

Unaffected by construction — the caps are inert at 390px (§3a) and no `min-w` changes unless the
plan adds one. But it must still be **run as the control**, not reasoned about: `public-layout.spec.ts:330`
and `dashboard-mobile.spec.ts:380` already own this, and `public-layout.spec.ts:319` documents the
non-vacuity guard that must precede it (*"a missing table has a `scrollWidth` of 0"* — an empty
page passes the fit trivially).

---

## 5. Testing a width contract

### 5a. Which instrument, and why

| Instrument | Measures | Use it for |
|---|---|---|
| `el.getBoundingClientRect().width` (or Playwright `boundingBox()`) | The **effective** rendered width, sub-pixel, after every constraint resolves | **The primary assertion.** This is the only thing that answers "is the band actually this wide". |
| `getComputedStyle(el).maxWidth` | The **declared** cap only | A secondary assertion that the right class landed. It will happily report `1700px` on an element a parent has squeezed to 400px — so it must never be the only check. |
| `matchMedia` | Which media queries the runtime believes are active | Not needed for the caps (they emit no media query, §3a). Needed for the **mobile-project sanity check** — the repo's own `mobile-instrument-contract.spec.ts` exists because *"a viewport is not a device"*, and CONTEXT §5 restates it. |

**Assert `min(parent.clientWidth, TIER)`, never a bare constant.** From §3d, the shell is *fluid*
at 1920 (1664px) and *capped* at 2560 (1700px). A constant assertion is wrong at one of those two
viewports. Reading the parent's `clientWidth` also disposes of scrollbar-width guesswork
(`main` is `overflow-y-auto`, so a classic scrollbar eats real width; `clientWidth` already
excludes it, whereas `viewport − 256` does not).

```ts
const { band, avail } = await page.evaluate(() => {
  const el = document.querySelector<HTMLElement>("[data-width-tier='shell']")!
  return { band: el.getBoundingClientRect().width, avail: el.parentElement!.clientWidth }
})
expect(band).toBeCloseTo(Math.min(avail, SHELL_MAX_PX), 0)
```

A `data-width-tier` attribute (rather than selecting on the class name) keeps the spec from
breaking on an unrelated class edit and makes the tier explicit in the DOM.

### 5b. Sharing constants so the contract and the assertion cannot drift

**This is already solved in-tree and the precedent is exact.** `e2e/public-a11y.spec.ts:75`:

```ts
import { CART_KEY_PREFIX } from "../lib/cart-identity"
```

A Playwright spec importing an app `lib/` module by **relative** path. It works, and it is in the
spec that the per-PR CI browser gate runs.

Verified that this composes with the type-check gate [VERIFIED: `npx tsc --noEmit -p tsconfig.e2e.json --listFiles`, 2026-08-29]:

```
tsc rc=0
e2e files in program        : 30
frontend/lib files in program: 1   -> /home/sanmi/.../frontend/lib/cart-identity.ts
```

`tsconfig.e2e.json` narrows `include` to `e2e/**/*.ts`, but TypeScript follows imports into the
program regardless — so `lib/layout-widths.ts` will be type-checked by
`scripts/check-e2e-typecheck.sh` too. That is a *feature*: a malformed constants module reds the
gate. It also imposes a requirement — **the module must be pure constants with no imports** (no
React, no `next/*`, no DOM-only types), so it stays valid in the Tailwind (jiti/Node), Next
(webpack) and Playwright (esbuild) loaders alike.

Three consumers, one file:

```
frontend/lib/layout-widths.ts        <- the single declaration
   ├── tailwind.config.ts            (relative import — the @/ alias FAILS here, §1d(i))
   ├── components/…                  (relative or @/ — both work in the Next build)
   └── e2e/layout-width-contract.spec.ts  (relative — matches the cart-identity precedent)
```

Follow `perf-budgets.ts`'s house style: export **numbers** (`SHELL_MAX_PX = 1700`) with the
`px` string derived (`` `${SHELL_MAX_PX}px` ``), so the spec can do arithmetic and Tailwind gets
its unit, from one literal. And carry the same kind of docblock — `perf-budgets.ts` is the model
because it explains *why each number is defensible*, which is what stops the next person raising
it to go green.

### 5c. Reaching 1920 and 2560

`playwright.config.ts` defines only two projects: `mobile` (390×844, `isMobile`, `hasTouch`) and
`desktop` (1440×900). Neither is 1920 or 2560.

- Use `test.use({ viewport: { width: 1920, height: 1080 } })` **inside a `describe`**. Documented
  (*"test.use({ viewport: { width: 1600, height: 1200 } }) within a test describe block applies to
  all tests in that scope"* [CITED: playwright.dev/docs/emulation]) and already used in-tree at
  `webhooks-flow.spec.ts:157` and `dashboard-interface-corrections.spec.ts:105`.
- Or `page.setViewportSize()` mid-test, as `public-layout.spec.ts:330` does. Prefer `test.use` —
  it applies before the first navigation, so there is no resize-driven re-render to wait out.
- **Tag the wide describes `@desktop-only`.** The `mobile` project's `grepInvert: /@desktop-only/`
  then never *enumerates* them. The config's own comment explains why this matters: a runtime
  `test.skip` would put a permanent "not applicable here" entry in the skip count, and
  `scripts/check-e2e-skip-budget.sh` enforces a ceiling on that count.

### 5d. Where each tier can be asserted (this splits the plan)

The per-PR CI browser gate runs exactly two specs, stack-free:
`npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts` [VERIFIED: `ci-cd.yaml:531`].
Everything else runs only in the nightly full-stack lane.

| Tier | Surface | Auth needed | Where it can be asserted |
|---|---|---|---|
| Marketing | `/`, `/for-operators`, `/business-model-guide` | No | **Per-PR gate** (public API stubbed) — highest value, fastest feedback |
| Marketing-ish | `/shop` | No | Per-PR gate, with the caveat in `public-layout.spec.ts`'s header (server-rendered, only partly stubbed) |
| Shell / Index / Detail | `/dashboard*` | Yes (Keycloak) | **Nightly only** |

To avoid the dashboard tiers being effectively untested on PRs, add a **stack-free
config-drift check**: a Jest test that runs the Tailwind config through PostCSS and asserts the
emitted `.max-w-shell` value equals `SHELL_MAX_PX` from the module. It cannot prove the class was
*applied*, but it does prove the config and the constant have not diverged, on every PR, in
milliseconds. Pair it with a jsdom class-presence assertion on `DashboardShell` (there is already
a `components/dashboard/__tests__/dashboard-shell.test.tsx`).

### 5e. Falsifiability playbook

Per CLAUDE.md, each criterion must be shown FAILING before it is trusted, with both directions
recorded. Concrete break-arms for this phase:

| Criterion | Break arm that must red it | Why this arm and not another |
|---|---|---|
| Shell band = min(avail, 1700) @2560 | Set `SHELL_MAX_PX = 1400` in the module | Exercises the whole chain — module → config → CSS → DOM — in one edit. A hand-edited class would test less. |
| Shell band @1440 **unchanged** at 1184 | Set `SHELL_MAX_PX = 900` | Proves the "must not move" assertion can move. |
| Detail band = min(avail, 1100) | Remove the tier class from the detail surface | Proves the assertion is measuring the element, not the declaration. |
| No horizontal scroll @390 | Add `min-w-[900px]` to a table | Proves the check is not vacuous over an empty page — chain it after `public-layout.spec.ts`'s existing non-vacuity control. |
| CSS/config drift check | Change the config value without the module | The exact drift the gate exists to catch. |
| Mobile byte-identical | Diff generated CSS pre/post, filtered to rules with no media query and to `max-width` queries below 1024px | The strongest available form: an empty diff is a *property*, not a sample. |

Per the "Break-Arm Revert Eats Fixes" trap: **commit before running the arms**, and verify each
restore **by content** (a unique token or hash), never `git diff --stat`. Run the sequence
**clean → arms → clean again**; the closing clean pass is the only evidence the restores landed.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---|---|---|---|
| Turning tier numbers into CSS | A hand-written `@layer components` block, or inline `style={{maxWidth}}` | `theme.extend.maxWidth` | Inline styles bypass Tailwind's scanner and purge entirely, and rely on `style-src 'unsafe-inline'` (present at `lib/security-headers.ts:93`, but leaning on it moves the app the wrong way). A hand-written layer re-introduces the cascade-order question §3c answers for free. |
| Sharing constants between config, app and test | Duplicating the number in three files, or parsing `tailwind.config.ts` from the spec | One TS module imported relatively by all three | The precedent already exists and is proven green: `public-a11y.spec.ts` → `lib/cart-identity`. |
| Measuring an element's effective width | A bespoke `offsetWidth` + scrollbar-subtraction helper | `getBoundingClientRect().width` against `parentElement.clientWidth` | `clientWidth` already excludes the scrollbar; hand-rolled scrollbar arithmetic is how a spec becomes environment-dependent. |
| Detecting layout shift | A custom MutationObserver / rAF differ | The existing `landing-webperf.spec.ts` `PerformanceObserver('layout-shift')` harness | It already handles `hadRecentInput` correctly and its limitations are documented at length in `perf-budgets.ts`. |
| Checking image/aspect regressions after widening | New per-surface image assertions | `public-layout.spec.ts`'s `aspectViolations()` + `brokenImages()` | Generic, computed-style based, already covers every element on the page — present and future. |
| Verifying `scrollable-region-focusable` | A jsdom/jest-axe assertion | The real-browser nightly sweep | Proven above: the rule is geometric and **cannot fire in jsdom**. A jsdom assertion here is a vacuous pass by construction. |
| Enforcing "no scattered literals" | A note in a doc | A `scripts/check-*.sh` grepping `app/` + `components/` for raw `max-w-[0-9]`/`max-w-\[\d+px\]` | CLAUDE.md: *"when a recurring failure is found, the fix is a script that fails loudly — not a firmer instruction."* Must be wired into CI in the same commit (`check-gate-enforcement.sh` is default-deny). |

**Key insight:** almost every capability this phase needs already exists in the repo, built and
debugged by an earlier phase. The failure mode to guard against is not "no tool for this" — it is
writing a *second, weaker* copy of a tool that is already here, which is exactly what
`public-shell.tsx`'s docblock warns about (*"a fourth variant would be the drift, not the fix"*).

---

## Runtime State Inventory

This is a refactor (class/config substitution), so the inventory applies. Four of five categories
are empty and say so explicitly.

| Category | Items Found | Action Required |
|---|---|---|
| Stored data | **None** — no width value is persisted anywhere. Verified: the caps exist only as Tailwind class literals in `.tsx` and one config block; no DB column, no Redis key, no localStorage entry references a layout width. | None |
| Live service config | **None** — no external service (n8n, Datadog, Cloudflare) holds a layout width. | None |
| OS-registered state | **None** — no scheduled task or registered service references layout widths. | None |
| Secrets / env vars | **None** — no `NEXT_PUBLIC_*` or SOPS key carries a width. Deliberately keep it that way: a build-time-inlined `NEXT_PUBLIC_*` width would be unreadable by the Playwright spec and would defeat the single-source rule. | None |
| **Build artifacts** | **LIVE.** Tailwind output is baked into `.next/` at build time and then into the frontend Docker image. A CSS-only source change is invisible until both are rebuilt — `docker compose start` does not rebuild (CLAUDE.md Proof Standard §2/§4, and `trap_stale_containers_after_phase`). | **Rebuild the frontend image, then verify parity** with `scripts/check-runtime-freshness.sh` (per-service; any built service missing or not `running` VOIDs the run at exit 2). Prove by content — read the generated CSS out of the running artifact, not by a 200 response. |

**The canonical question — after every file in the repo is updated, what still has the old value?**
Only the built CSS bundle, in `.next/` and in the frontend image. Nothing else.

---

## Common Pitfalls

### Pitfall 1: The `@/` alias silently looks like a Tailwind bug
**What goes wrong:** `import { … } from "@/lib/layout-widths"` in `tailwind.config.ts` throws
`Cannot find module`. **Why:** jiti does not read `tsconfig.compilerOptions.paths`.
**Avoid:** relative import in the config only. **Warning sign:** the build fails at PostCSS
init, before any component is compiled — the stack trace points at Tailwind, not at your import.
[VERIFIED: measured, both directions]

### Pitfall 2: A class name living in `lib/` is never generated — silently
**What goes wrong:** the utility is absent from the CSS, the element gets no cap, everything
builds and renders at the wrong width. **Why:** `content` globs cover `pages/`, `components/`,
`app/` — not `lib/`. **Avoid:** keep class-name *strings* in `components/`; keep only *numbers*
in `lib/`. **Warning sign:** `getComputedStyle(el).maxWidth === "none"` while the class is
present in `className`. [VERIFIED: measured, both arms]

### Pitfall 3: Asserting a constant where the tier is fluid
**What goes wrong:** `expect(width).toBe(1700)` reds at 1920 on a correct build, because `main`
is only 1664px there. **Avoid:** `Math.min(parent.clientWidth, TIER)`. **Warning sign:** the
spec passes at 2560 and fails at 1920 — the opposite of what a too-narrow cap would do.

### Pitfall 4: Trusting the jsdom axe gate for A11Y-3
**What goes wrong:** `scrollable-region-focusable` returns clean from jest-axe no matter what,
because `getScroll` needs `scrollWidth > clientWidth + 13` and jsdom reports 0 for both.
**Avoid:** verify in the nightly browser sweep. **Warning sign:** a green jest-axe run cited as
evidence about a scroll region. [VERIFIED: axe-core 4.13.0 source]

### Pitfall 5: Reading a stale runtime
**What goes wrong:** the browser is served CSS from a container built before the change; the
spec measures 1400 and the change looks broken (or, worse, an unchanged number looks correct).
**Avoid:** rebuild all containers, then `check-runtime-freshness.sh`. **Warning sign:** measured
values exactly matching CONTEXT's *pre-change* baseline table.

### Pitfall 6: `min-w` on a table cutting both ways
**What goes wrong:** adding `min-w-[900px]` to stop column crush at large widths reintroduces
horizontal scroll at 390px. **Avoid:** re-run the 390px assertion after any `min-w` change; the
two constraints pull in opposite directions and only the mobile arm can tell you.

### Pitfall 7: Regression by omission on a widened surface
**What goes wrong:** a centred narrow layout that read as deliberate becomes a stretched band
with a lonely heading — green tests, worse product. **Avoid:** the Incremental Betterment
enumeration is a *deliverable*, not a formality: for each surface, name the displaced good and
say how it is preserved. **Warning sign:** the visual capture at 2560 shows content hugging the
left edge of a now-wider band.

### Pitfall 8: Doc-metric drift
**What goes wrong:** a new spec bumps `playwright_specs` (26→27) and `playwright_blocks`, and
both `docs-freshness.sh` and `check-doc-metrics.sh` red. **Avoid:** regenerate with
`scripts/docs-freshness.sh --write`; never hand-edit and never compute arithmetically
(`trap_docs_freshness_block_counter`).

---

## Code Examples

### The declaration module (shape, not final copy)

```ts
// frontend/lib/layout-widths.ts
//
// THE single declaration of the horizontal layout contract. Imported by
// tailwind.config.ts (build), by the tier component (runtime) and by
// e2e/layout-width-contract.spec.ts (test), so the contract and the assertion
// cannot drift. Same convention as e2e/perf-budgets.ts.
//
// PURE CONSTANTS, NO IMPORTS. This file is loaded by three different loaders —
// jiti (Tailwind), webpack (Next) and esbuild (Playwright). Anything importing
// React, next/* or DOM-only types breaks at least one of them.
//
// Numbers, not strings: the spec does arithmetic (min(avail, TIER)) and Tailwind
// needs a unit. Deriving the px string here keeps one literal.

export const SHELL_MAX_PX = 1700      // dashboard chrome
export const DETAIL_MAX_PX = 1100     // order detail, settings, forms
export const MARKETING_MAX_PX = 1280  // landing, for-operators, business-model-guide

export const LAYOUT_WIDTHS = {
  shell: `${SHELL_MAX_PX}px`,
  detail: `${DETAIL_MAX_PX}px`,
  marketing: `${MARKETING_MAX_PX}px`,
} as const
```

### Wiring it into Tailwind

```ts
// frontend/tailwind.config.ts
// RELATIVE, not "@/lib/…": jiti does not read tsconfig paths (measured — it throws).
import { LAYOUT_WIDTHS } from "./lib/layout-widths"

theme: {
  // theme.container is deliberately REMOVED — the plugin's selector is a hardcoded
  // ".container" and its max-width is forced to equal the activating breakpoint, so
  // it cannot express more than one tier. See 35-RESEARCH.md §1a.
  extend: {
    maxWidth: { ...LAYOUT_WIDTHS },   // -> max-w-shell / max-w-detail / max-w-marketing
  },
}
```

Emitted CSS, verified by generating it [VERIFIED: PostCSS run on this tree]:

```css
.max-w-shell { max-width: 1700px }   /* no @media — inert until the parent exceeds 1700px */
```

### Applying a tier

```tsx
// components/layout/content-tier.tsx  (under components/ so Tailwind SCANS the literals)
const TIER_CLASS = {
  shell: "max-w-shell",
  detail: "max-w-detail",
  marketing: "max-w-marketing",
} as const

export function ContentTier({ tier, className, children }: {
  tier: keyof typeof TIER_CLASS; className?: string; children: React.ReactNode
}) {
  // data-width-tier is the spec's selector: stable across unrelated class edits.
  return (
    <div data-width-tier={tier} className={cn("mx-auto", TIER_CLASS[tier], className)}>
      {children}
    </div>
  )
}
```

### The spec assertion

```ts
// frontend/e2e/layout-width-contract.spec.ts
// Relative import — same shape as e2e/public-a11y.spec.ts:75's `../lib/cart-identity`,
// which is already resolved into the tsconfig.e2e.json program and green.
import { SHELL_MAX_PX } from "../lib/layout-widths"

test.describe("Shell tier @desktop-only", () => {
  test.use({ viewport: { width: 2560, height: 1440 } })

  test("the dashboard band is min(available, SHELL_MAX_PX)", async ({ page }) => {
    await page.goto("/dashboard")
    const m = await page.evaluate(() => {
      const el = document.querySelector<HTMLElement>("[data-width-tier='shell']")
      if (!el) return null                       // null, never 0 — an absent band must
      return {                                   // VOID the test, not pass it trivially
        band: el.getBoundingClientRect().width,
        avail: el.parentElement!.clientWidth,    // excludes the scrollbar; viewport-256 does not
        declared: getComputedStyle(el).maxWidth, // secondary: proves the class landed
      }
    })
    expect(m, "no [data-width-tier=shell] on /dashboard — vacuous pass guard").not.toBeNull()
    expect(m!.declared).toBe(`${SHELL_MAX_PX}px`)
    expect(m!.band).toBeCloseTo(Math.min(m!.avail, SHELL_MAX_PX), 0)
  })
})
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|---|---|---|---|
| `theme.container` + `screens` override (the shadcn scaffold) | Semantic `maxWidth` keys, or v4's `@container` where available | shadcn's own newer scaffolds dropped the `container` block; Tailwind v4 removed `theme.container` config entirely | The 1400px block in this repo is a **v3-era shadcn scaffold artefact**, which is precisely CONTEXT.md's finding that it was inherited, not chosen. |
| Media-query-only responsive layout | Container queries (`@container`) | **Tailwind v4.0** (plugin for v3.2/3.3) [CITED: tailwindlabs/tailwindcss-container-queries] | Not available here (3.4.19, plugin absent — measured). Relevant only as a **v4 migration note**, not as an option for this phase. |
| One width for the whole app | Tiered widths + a full-width opt-out for resource indexes | Polaris / Carbon / GitLab, per CONTEXT §3 | Already the basis of the locked contract. |

**Deprecated / outdated for this phase:**
- `theme.container` — keep it only if some other surface still uses `.container`. CONTEXT §2 says
  it is used in exactly one file, so it can be removed outright. Removing it is the honest move:
  leaving a dead 1400px block invites the next person to re-apply it.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| A1 | Removing `theme.container` breaks nothing, because `container` is used in exactly one file (taken from CONTEXT §2's count, not independently re-measured here) | §1c, State of the Art | LOW — a stray `.container` would lose its cap and go fluid. **Cheap to falsify:** `rg -uu '\bcontainer\b' app components --glob '*.tsx'` before deleting the block, treating comments and Testing-Library `container` variables as noise. |
| A2 | Widening cannot make `scrollable-region-focusable` findings worse | §4b | LOW — the reasoning (less overflow ⇒ fewer matches) is sound and follows from the axe source, but it is reasoned, not run. **Falsify by running the nightly browser sweep** on the widened surfaces. |
| A3 | The public `/shop` directory belongs to the Marketing tier | §5d, Open Q1 | MEDIUM — `/shop` is a data-dense *index*, and CONTEXT's Index tier names "shops", which could mean either `/dashboard/shops` or `/shop`. Getting it wrong applies the wrong tier to a customer-facing page. **Needs a user decision** (Open Question 1). |
| A4 | `parentElement.clientWidth` is the right "available width" denominator on every tier | §5a | LOW — holds where the tier element's parent is the constraining block. If a tier is nested inside another capped element the denominator changes. **Falsify by printing both values** on first run and eyeballing them against §3d's table. |
| A5 | A jsdom class-presence assertion plus a config-drift check is adequate per-PR cover for the dashboard tiers | §5d | MEDIUM — neither proves the band renders at the right width; only the nightly does. This is a deliberate, statable coverage boundary, not an oversight — but it must be **written down**, not assumed. |
| A6 | The `p-4`/`sm:p-8` utilities win over `.container`'s `padding: 2rem` at every width | §3c | LOW — the generated CSS shows the ordering directly, and equal-specificity later-wins is unambiguous. Falsify by measuring the element's computed `padding-left` at 390 and 1440 before and after. |

---

## Open Questions

1. **Does the public `/shop` directory take the Marketing tier (1280) or the Index tier (fluid to shell)?**
   - *What we know:* `/shop` is currently `max-w-7xl` = 1280px, which already equals the Marketing
     value. CONTEXT's Index row names "products, orders, customers, shops" alongside three
     unambiguous dashboard routes, and its Marketing row names only "landing, for-operators,
     business-model-guide".
   - *What's unclear:* whether "shops" means `/dashboard/shops` or the public `/shop`. CONTEXT's
     baseline table measures `/shop` separately and flags it at 50% of 2560, which reads like it
     is meant to move.
   - *Recommendation:* treat "shops" as `/dashboard/shops` (it sits in a list of dashboard
     resource indexes) and leave public `/shop` on Marketing — which means **no change**, since
     `max-w-7xl` is already 1280. **Confirm with the user**; it is a one-line difference in the
     plan and a visible difference to customers.

2. **Should the pre-existing `/` CLS debt gain a desktop-viewport arm?**
   - *What we know:* `/` is the only surface whose value actually changes (1152→1280), and every
     CLS instrument in the repo measures at 375px, where that change is invisible.
   - *What's unclear:* whether adding a desktop CLS baseline is in scope for a layout phase.
   - *Recommendation:* add it. It is ~20 lines reusing `landing-webperf.spec.ts`'s existing
     observer, and without it the phase's most-changed surface has an unmeasured CLS dimension.
     If it is declined, record the gap explicitly (§2d option 2).

3. **Does the Index tier need any class at all?**
   - *What we know:* "fluid to shell" means no cap beyond the shell's. If the shell caps at 1700
     and index pages add nothing, index == shell automatically.
   - *What's unclear:* whether the contract wants an explicit `data-width-tier="index"` marker
     anyway, so the spec can assert the tier *by name* rather than inferring it from absence.
   - *Recommendation:* emit the marker with **no** max-width class. It costs one attribute, makes
     the tier assertable and self-documenting, and — importantly — makes "index is uncapped" a
     falsifiable statement rather than the absence of one.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|---|---|---|---|---|
| Node.js | build + tests | ✓ | (repo pins 24 in CI) | — |
| `tailwindcss` | tier utility generation | ✓ | 3.4.19 | — |
| `jiti` | TS Tailwind config + TS imports in it | ✓ | 1.21.7 (transitive) | — |
| `postcss` | CSS-generation drift check | ✓ | (Tailwind dep) | — |
| `@playwright/test` | width measurement | ✓ | 1.62.1 | — |
| Playwright chromium browser | real-browser arms | ✓ | installed by CI step; assumed local | `npx playwright install chromium` |
| `axe-core` | a11y verification | ✓ | 4.13.0 | — |
| `jest` + `next/jest` | stack-free structural checks | ✓ | 29.7.0 | — |
| Docker Compose full stack | dashboard-tier E2E | not probed in this session | — | Public-tier assertions run stack-free; dashboard tiers degrade to the nightly lane |
| `@tailwindcss/container-queries` | (would be needed for `@container`) | ✗ | — | **Not needed — this research recommends against container queries entirely (§1b)** |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** the container-queries plugin, and its fallback is "do not
use container queries", which is the recommendation on independent grounds.

---

## Validation Architecture

### Test Framework

| Property | Value |
|---|---|
| Framework (browser) | Playwright 1.62.1 — `frontend/playwright.config.ts` |
| Framework (unit) | Jest 29.7.0 via `next/jest` — `frontend/jest.config.js` |
| Quick run command | `cd frontend && npx jest <pattern>` |
| Browser quick run | `cd frontend && npx playwright test e2e/layout-width-contract.spec.ts --project=desktop` |
| Full suite | `cd frontend && npm test` and `npx playwright test` |
| Type-check gate | `scripts/check-e2e-typecheck.sh` (0 = clean, 1 = type error, **2 = VOID**) |

### Phase Requirements → Test Map

CONTEXT.md carries no `REQ-` identifiers, so the contract rows are mapped directly.

| Contract row | Behaviour | Test type | Automated command | File exists? |
|---|---|---|---|---|
| Shell 1700 | `/dashboard` band = min(avail, 1700) @1440/1920/2560 | e2e (nightly, auth) | `npx playwright test e2e/layout-width-contract.spec.ts` | ❌ Wave 0 |
| Shell 1700 | config value == `SHELL_MAX_PX` | unit (stack-free, per-PR) | `npx jest layout-widths` | ❌ Wave 0 |
| Shell 1700 | `DashboardShell` renders `data-width-tier="shell"` | unit (jsdom) | `npx jest dashboard-shell` | ⚠️ extend `components/dashboard/__tests__/dashboard-shell.test.tsx` |
| Index fluid | index band is uncapped below the shell cap | e2e (nightly) | same spec | ❌ Wave 0 |
| Detail 1100 | detail band = min(avail, 1100) | e2e (nightly) | same spec | ❌ Wave 0 |
| Marketing 1280 | `/`, `/for-operators`, `/business-model-guide` band = min(vw, 1280) | e2e (**per-PR**, stack-free) | `npx playwright test e2e/layout-width-contract.spec.ts --project=desktop` | ❌ Wave 0 |
| Prose 68ch unchanged | `max-w-[68ch]` sites untouched | unit | `npx jest policy-page.a11y` | ✅ `components/legal/__tests__/policy-page.a11y.test.tsx:141` |
| Mobile byte-identical | no horizontal scroll @390; band geometry unchanged | e2e (mobile project) | `npx playwright test --project=mobile` | ✅ `public-layout.spec.ts`, `dashboard-mobile.spec.ts` — **reuse, do not duplicate** |
| Mobile byte-identical | generated CSS delta below `lg` is empty | unit (PostCSS diff) | `npx jest layout-widths-css` | ❌ Wave 0 |
| CLS no-regression on `/` | CLS ≤ `LANDING_CLS_KNOWN_BASELINE + TOLERANCE` | e2e (throttled mobile) | `npx playwright test e2e/landing-webperf.spec.ts` | ✅ exists — re-run as the control arm |
| A11Y-3 preserved | `scrollable-region-focusable` clean; region keeps its name | e2e (**nightly, real browser only**) | `npx playwright test e2e/dashboard-a11y-nightly.spec.ts` | ✅ exists — **the jsdom gate cannot answer this (§4b)** |
| No scattered literals | no raw `max-w-[<digits>px]` in `app/`+`components/` | static gate | `scripts/check-<name>.sh` | ❌ Wave 0 (+ **must** be wired into `ci-cd.yaml` in the same commit) |

### Sampling Rate

- **Per task commit:** `npx jest <touched pattern>` + `scripts/check-e2e-typecheck.sh`
- **Per wave merge:** `npm test` (full Jest, incl. the coverage floor) + `npx playwright test --project=desktop`
- **Phase gate:** full Jest + full Playwright (both projects) green; `check-runtime-freshness.sh`
  and `check-branch-behind-base.sh` clean (both exit-2-on-VOID); `docs/metrics.json` regenerated
  via `scripts/docs-freshness.sh --write`; then `/gsd:verify-work`.

### Wave 0 Gaps

- [ ] `frontend/lib/layout-widths.ts` — the single declaration (pure constants, no imports)
- [ ] `frontend/components/layout/content-tier.tsx` — tier component + scanned class literals
- [ ] `frontend/e2e/layout-width-contract.spec.ts` — measured band per tier @1440/1920/2560, `@desktop-only`
- [ ] `frontend/lib/__tests__/layout-widths-css.test.ts` — PostCSS drift check + the below-`lg` empty-diff proof
- [ ] Extend `components/dashboard/__tests__/dashboard-shell.test.tsx` with the tier marker assertion
- [ ] `scripts/check-*.sh` scattered-literal gate **+ its `ci-cd.yaml` wiring** (default-deny gate enforcement)
- [ ] No framework install needed — all runners already present

---

## Security Domain

`security_enforcement: true`, `security_asvs_level: 2`. This phase changes CSS class names and one
Tailwind theme block. It adds no endpoint, no input, no credential and no data flow.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---|---|---|
| V2 Authentication | **No** | Unchanged. The dashboard tiers render inside `app/dashboard/layout.tsx`, whose server-side `auth()` + `redirect()` is untouched. |
| V3 Session Management | **No** | No session handling touched. |
| V4 Access Control | **No** | No authorisation decision is made by a width. Widening a band does not reveal a row RLS would have withheld — the tenant wall is enforced in Postgres and the API, not in CSS. |
| V5 Input Validation | **No** | No user input reaches the tier system; tier is a compile-time literal from a closed union (`keyof typeof TIER_CLASS`), never a runtime string. **If the plan makes tier dynamic from any external value, this flips to yes.** |
| V6 Cryptography | **No** | None involved. |
| V14 Configuration | **Yes (advisory)** | The CSP at `lib/security-headers.ts:93` is `style-src 'self' 'unsafe-inline'`. Using Tailwind utilities keeps widths in the external stylesheet; using `style={{maxWidth}}` would newly depend on `'unsafe-inline'`. Prefer utilities — §"Don't Hand-Roll". |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---|---|---|
| CSS-injected class names from user data | Tampering | Not applicable — tier is a closed union resolved at compile time. Keep it that way. |
| Inline-style reliance weakening CSP posture | Tampering | Use generated utilities, never `style={{…}}`. |
| Layout-driven information disclosure (a wider table revealing more columns) | Information Disclosure | Not applicable — the columns rendered are chosen by the component and gated by the API/RLS, and are identical at every width. Widening changes only how much of an already-rendered table is visible without scrolling. |
| Supply-chain risk from a new dependency | Tampering | **Avoided by construction** — this phase installs nothing (see Package Legitimacy Audit). |

**Threat-model summary:** no new trust boundary, no new input, no new dependency. The only
security-adjacent decision is "utilities, not inline styles", and it is already the recommended
approach on other grounds.

---

## Sources

### Primary (HIGH confidence — measured on this tree, 2026-08-29)
- `frontend/node_modules/tailwindcss/lib/corePlugins.js` — container plugin implementation
  (hardcoded `.container` selector; `max-width` = breakpoint `minWidth`)
- `frontend/node_modules/tailwindcss/stubs/config.full.js` — default `maxWidth` scale
  (`6xl` = 72rem = 1152px, `7xl` = 80rem = 1280px)
- PostCSS + Tailwind CSS-generation runs (3 arms): current config, no-`screens` control, named
  `maxWidth` keys
- PostCSS run proving cascade order (`.container` padding vs `.p-4` / `.sm:p-8`)
- jiti resolution probe, both directions: relative import succeeds, `@/` alias throws
- `content`-glob probe, both arms: class name in `lib/` **not** emitted, in `components/` emitted
- `rg` probes with fail-direction controls: `container-type`/`@container` absent from 3.4.19
  (control: `aspectRatio` present); `2xl:` absent from all `.tsx` (control: `lg:` in 17 files)
- `frontend/node_modules/axe-core/axe.js` — `scrollableRegionFocusableMatches`, `getScroll`
  (`+13` buffer), and the rule's `any: ['focusable-content','focusable-element']` wiring
- `npx tsc --noEmit -p tsconfig.e2e.json --listFiles` — rc=0, 30 e2e files, `lib/cart-identity.ts`
  already resolved into the program
- `frontend/e2e/public-a11y.spec.ts:75` — the spec→`lib/` relative-import precedent
- `frontend/components/ui/table.tsx` — the nested `overflow-auto` wrapper
- `frontend/playwright.config.ts`, `frontend/jest.config.js`, `.github/workflows/ci-cd.yaml:531`
- `frontend/e2e/perf-budgets.ts`, `frontend/components/dashboard/dashboard-shell.tsx:55`,
  `frontend/components/dashboard/sidebar.tsx:65`
- `frontend/node_modules/next/dist/compiled/@next/font/dist/google/loader.js` — `adjustFontFallback`

### Secondary (HIGH — official documentation)
- https://web.dev/articles/cls — what counts as a layout shift; size vs start-position
- https://v3.tailwindcss.com/docs/max-width — extending `theme.maxWidth`; precedence over `theme.spacing`
- https://playwright.dev/docs/test-typescript — tsconfig `paths` support; the supported-options list
- https://playwright.dev/docs/emulation — `test.use({ viewport })` in a describe; `page.setViewportSize`
- https://github.com/tailwindlabs/tailwindcss-container-queries — plugin required for v3.x; built in from v4.0

### Tertiary (LOW — noted and then superseded)
- https://v3.tailwindcss.com/docs/container — did **not** document the `screens` key; superseded by
  reading the plugin source
- https://dequeuniversity.com/rules/axe/4.10/scrollable-region-focusable — a summarising fetch of
  this page reported the rule as *structural*. **That is wrong**, and the axe-core source above is
  the correction. Recorded here because the mistake is easy to repeat.

---

## Metadata

**Confidence breakdown:**
- Tailwind mechanism (§1): **HIGH** — plugin source read and CSS generated for three arms; the
  negative claim about container queries carries a fail-direction control.
- CLS analysis (§2): **HIGH** for the mechanism (web.dev) and for the "no `xl:`/`2xl:` grids"
  measurement; **MEDIUM** for "cannot regress" — structurally sound but the desktop-viewport arm
  does not yet exist (Open Question 2).
- Responsive authoring (§3): **HIGH** — the self-gating property follows from the emitted CSS,
  and the arithmetic model reproduces all three of CONTEXT's measured cells exactly.
- Tables and a11y (§4): **HIGH** for the axe mechanics (source read); **MEDIUM** for "widening
  cannot make it worse" (reasoned, marked A2, falsifiable by the nightly sweep).
- Testing (§5): **HIGH** — the shared-import pattern is not merely possible but already green
  in-tree, confirmed through the actual type-check gate.

**Research date:** 2026-08-29
**Valid until:** ~2026-09-28 (30 days). Tailwind 3.4 is a stable, effectively frozen line; the
one event that would invalidate §1 and §3 wholesale is a **Tailwind v4 migration**, which removes
`theme.container` configuration entirely and makes `@container` native.
