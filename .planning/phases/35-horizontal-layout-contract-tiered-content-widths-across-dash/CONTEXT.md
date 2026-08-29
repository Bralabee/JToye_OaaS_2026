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

## 4b. Decisions

**Provenance matters here and was initially got wrong.** These five were resolved by the
**orchestrator** during planning, not ratified by the owner. The owner was told they had
been resolved and delegated the phase autonomously ("complete the entire plan, implement
and verify phases autonomously"), which is authority to decide — it is **not** the owner
having chosen each option. The first draft of the plans cited them as "user decision
D-0N" and instructed the executor to write that phrase into a source comment; that is a
false attribution and is corrected here. Cite these as **ORCH-0N (orchestrator decision,
2026-08-29)**, never as a user decision.

Three of them (ORCH-01/02/03) resolve what `RESEARCH.md` §Open Questions left open. That
section is **not** self-resolving — read it together with this block.

| ID | Decision | Rationale | Reversibility |
|---|---|---|---|
| **ORCH-01** | Public `/shop` stays **Marketing (1280px)**, unchanged. The Index tier means the DASHBOARD routes only. | `/shop` is a customer-facing card browse surface, not a dense table. Going fluid turns a zero-file change into ~3 files and forces SEO + CLS re-measurement on a public, indexed surface. | **Owner-visible if wrong.** One-line difference; flag at the 35-13 gate for an explicit look rather than burying it. |
| **ORCH-02** | `/` gains a **desktop-viewport CLS arm**. | Every existing CLS instrument measures at 375px, where a desktop-only `max-width` provably cannot bind — so without a desktop arm the CLS check on this change is vacuous. | Additive test only. |
| **ORCH-03** | Index tier carries an explicit **`data-width-tier="index"` marker**, no max-width class. | "Uncapped" implemented as an absence is a contract no assertion can distinguish from a forgotten cap. | Additive attribute. |
| **ORCH-04** | `/` moves to the **Marketing tier (1280px)**. | Its content bands are `max-w-6xl` (1152px) inside header/footer chrome at `max-w-7xl` (1280px) — content is inset 128px from its own frame. Source: PATTERNS F-2. | Visual; covered by the CLS arm and the 35-13 gate. |
| **ORCH-05** | `app/shop/[slug]/loading.tsx` skeleton **aligned to the content it replaces**. | Skeleton is `max-w-7xl` (1280px), content is `max-w-4xl` (896px) — a 384px narrowing on hydration. Pre-existing defect, same class as the phase, cheap in place. Source: PATTERNS A-12. | Strict improvement. |
| **ORCH-06** | `components/legal/policy-page.tsx:112` moves to the **Marketing tier**, covering the four `/legal/*` policy routes. | **Raised by 35-06 as D-35-06-a, mid-execution.** Fixing `/` (ORCH-04) left this file at `max-w-6xl` (1152px) inside public chrome that now declares 1280 — so the policy pages inherited *exactly* the content-inset-from-its-own-frame defect the phase just removed from the landing page. Shipping that asymmetry is the "inconsistent half" the Incremental Betterment doctrine names a defect. **Verified safe before deciding**: line 112 is the outer BAND; the reading measure is independently held at `max-w-[68ch]` on lines 124/151/156, so widening the band does not widen prose — the ceiling-not-target rule holds. Routes affected: `/legal/privacy`, `/legal/cookies`, `/legal/retention`, `/legal/accessibility`. | One line + marker; same pattern as ORCH-04. |

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
- **THE NIGHTLY LANE IS DARK — "covered nightly" is currently FALSE.** Issue **#683** is
  OPEN: *"Nightly E2E is failing — the full-suite lane is dark… this lane is the project's
  only full-suite E2E instrument, so while it is red no merge has full-suite evidence on
  any tree."* The per-PR browser gate runs **only** `public-layout.spec.ts` +
  `public-a11y.spec.ts` (`.github/workflows/ci-cd.yaml:531`). Therefore: **Marketing-tier**
  assertions can be per-PR and genuinely blocking; **Shell / Index / Detail** assertions
  are nightly-only, and the nightly is not running. The honest statement is *"covered by a
  spec that no current tree executes"*, **not** *"covered nightly"*. Any doc, spec header
  or CI comment this phase writes must say the former. The phase's own rule — "an unstated
  boundary reads as covered" — applies to the phase itself. Same root cause as **#686**
  (the skip-budget gate is wired only into that dark lane).
- **Skip-budget precondition.** `scripts/gates/e2e-skip-budget.conf` is `MAX_SKIPS 6` and
  fails on any *undeclared* skip title. New dashboard specs skip via
  `skipWithoutVendorPassword()` when the credential is absent (`e2e/vendor-credentials.ts`
  defaults to `""` deliberately). Either state the precondition (`E2E_VENDOR_PASSWORD`, or
  source the stack `.env` for `KC_SEED_USER_PASSWORD`) or declare the skips in the conf —
  do not let the run silently exceed budget. Note #686 already records the budget at 7/6.

## 6. Verification approach

- A committed Playwright width-contract spec asserting **measured** band width per tier at
  1440/1920/2560, importing the declared constants (so the contract and the assertion
  cannot drift). Must be shown failing against the pre-change tree.
- Re-measure CLS/LCP on `/` and `/shop` against the recorded baselines in `perf-budgets.ts`.
- axe clean on the widened dashboard surfaces (the #685 jest-axe gate plus the nightly spec).
- Visual capture at 1920 and 2560 for the orders table, before and after.
- Full Jest + Playwright suites green; `docs/metrics.json` regenerated via
  `scripts/docs-freshness.sh --write` if test counts move (never hand-edited).

## 7. Owner gate — decisions recorded 2026-08-30

The 35-13 blocking checkpoint was put to the owner by the orchestrator with the
before/after captures at `frontend/e2e-artifacts/35-12/` and the measured arithmetic.
**These three ARE owner decisions** — unlike ORCH-01..06, which were orchestrator
decisions taken under delegated autonomy. The distinction is recorded deliberately,
because an earlier draft of these plans attributed orchestrator choices to the owner.

| Question | Owner decision | Consequence |
|---|---|---|
| Detail tier narrows live surfaces (1120→1100 at 1440; 1600→1100 at 1920/2560) | **Accepted 1100px** | The peer-matched reading column stands (Linear 1136, Square 1016, Lightspeed 1100). The displaced-goods ledger in `35-05-SUMMARY.md` is the accepted cost, not an oversight. |
| ORCH-01: public `/shop` stays Marketing 1280 rather than fluid | **Confirmed 1280px** | The one owner-visible judgement in the phase is now ratified rather than assumed. No SEO/CLS re-measurement needed on a public indexed surface. |
| Close-out | **Run 35-13, then open a PR** | Merge left to the owner after CI. |

Measured at the gate, from the running container (not a local `next start`):
served CSS carries `1700px`/`1100px`/`1280px`, **zero** `.container{` rules, tier
attributes present; `check-runtime-freshness.sh` rc=0, 4/4 FRESH. The orders index at
2560 moved from a ~1336px band to ~1636px — matching `min(2304, 1700) − 64px` exactly.
