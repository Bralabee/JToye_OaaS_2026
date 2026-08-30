---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 01
subsystem: ui
tags: [tailwind, css, layout, design-tokens, postcss, jest, contract-test]

# Dependency graph
requires:
  - phase: 34-rendering-test-truthfulness
    provides: "the Jest coverage floor and scoped build type-check (tsconfig.build.json) this plan's new suites run under"
provides:
  - "frontend/lib/layout-widths.ts — THE single declaration of the four-tier horizontal layout contract"
  - "max-w-shell / max-w-detail / max-w-marketing Tailwind utilities, generated from that module, each with NO media query"
  - "retirement of the stock shadcn container utility: no class in the tree can produce the inherited 1400px cap"
  - "a stack-free per-PR drift gate that reads the contract back out of the emitted stylesheet"
  - "the WidthTier vocabulary, including the index tier that deliberately declares no width"
affects: [35-02, 35-03, 35-04, 35-05, 35-06, 35-07, 35-08, 35-09, 35-10, 35-11, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "config-reads-repo-module: tailwind.config.ts imports a repo-local TS module (a first for this codebase), by RELATIVE specifier because jiti does not read tsconfig paths"
    - "declared-constant shared across build, runtime and test, following the lib/cart-identity.ts precedent"
    - "PostCSS-AST contract test: structural questions about emitted CSS answered by the tree, not by a regex"

key-files:
  created:
    - frontend/lib/layout-widths.ts
    - frontend/lib/__tests__/layout-widths.test.ts
    - frontend/lib/__tests__/layout-widths-css.test.ts
  modified:
    - frontend/tailwind.config.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "Tier numbers live in lib/ and tier CLASS LITERALS do not, because Tailwind's content globs exclude lib/ and a class string there is silently never generated"
  - "theme.container deleted AND corePlugins.container disabled — measured: deleting the theme block alone makes the plugin emit five default media queries, strictly worse than the single one the tree had"
  - "Tier utilities are unconditional, with zero responsive variants, so mobile-identity is a diffable property of the stylesheet rather than a browser observation"
  - "The index tier is in the WidthTier union but has no entry in LAYOUT_WIDTHS; the asymmetry is the contract"
  - "The justification check was rescoped from whole-file to per-export docblock after its fail arm did not fire"

patterns-established:
  - "Fail-arm-first on documentation checks too: a prose assertion that greps the whole file is satisfied by coincidental prose elsewhere"
  - "Break-arm restores verified by CONTENT and by blob identity (git diff --quiet), never by git diff --stat"
  - "Contract tests carry a non-vacuity control that must PASS while the contract arms red"

requirements-completed: [UIX-07]

# Metrics
duration: 55min
completed: 2026-08-29
---

# Phase 35 Plan 01: Declared Layout Contract Summary

**The four content widths now exist exactly once, in a module the build, the app and the tests all read, and the inherited shadcn 1400px cap can no longer be produced by any class in the tree.**

## Performance

- **Duration:** ~55 min
- **Tasks:** 3 of 3
- **Files modified:** 8 (3 created, 5 modified)
- **Commits:** 6

## Accomplishments

### Task 1 — the declaration module (TDD)

`frontend/lib/layout-widths.ts` exports `SHELL_MAX_PX` (1700), `DETAIL_MAX_PX` (1100),
`MARKETING_MAX_PX` (1280), the derived `LAYOUT_WIDTHS` px-string view, and the `WidthTier`
union. Each number carries the peer measurement that justifies it in its own docblock —
Stripe's dashboard at 1690, Linear's detail ladder at 1136, Stripe marketing at 1264 —
because the defect this phase exists to fix is not the value 1400 but that nobody could say
where it came from.

The index tier is in the union and deliberately absent from `LAYOUT_WIDTHS`. The superseded
1400 is recorded in the header rather than deleted, following the `LANDING_CLS_KNOWN_BASELINE`
precedent, so plan 35-12 has something to A/B against.

### Task 2 — utilities generated, container retired (TDD)

`tailwind.config.ts` now imports the module by **relative** specifier, spreads it into
`theme.extend.maxWidth`, has no `theme.container` block, and sets `corePlugins.container:
false`. Both halves of the retirement are guarded independently, because they fail
differently.

### Task 3 — the arms

Nine break arms run, all recorded below with real output in both directions.

## Verification — every criterion in both directions

Tasks 1 and 2 were committed **before** any arm ran, so no restore could eat a fix.

### The TDD gates themselves (fail direction by construction)

| Suite | RED | GREEN |
|---|---|---|
| `layout-widths.test.ts` | rc=1, `Cannot find module '../layout-widths'` | rc=0, 12/12 |
| `layout-widths-css.test.ts` | rc=1, **10 failed / 1 passed** | rc=0, 11/11 |

The one CSS test that passed in the RED run is the vacuity control, which is the point: it
proves the PostCSS run is real before any contract assertion is trusted.

### ARM A — the build proof (jiti resolution)

`npm run build` **rc=0**. A `tsc` type-check cannot answer this: jiti resolution happens at
PostCSS init, which tsc never reaches.

Proven by content in the shipped artifact (`.next/static/chunks/2y-_392jhqod7.css`, 96591 bytes):

```
EVIDENCE  rg 'max-width:1400px|\.container\{'  -> matches=[]  rc=1
CONTROL   rg 'max-width:'                      -> count=21    rc=0
IDENTITY  rg '58 11 13' (oxblood)              -> count=9     rc=0
```

The control is load-bearing — without it the empty result would be a statement about the
instrument, not the stylesheet.

### ARM B — the `@/` alias fail arm

```
BROKEN    import { LAYOUT_WIDTHS } from "@/lib/layout-widths";
          Warning: Module not found: Can't resolve '@/lib/layout-widths'
          Error: Cannot find module '@/lib/layout-widths'
          rc=1
RESTORED  relative form count=1 rc=0 | alias form count=[] rc=1 | worktree==HEAD rc=0
          npm run build rc=0
```

### ARM C — CSS drift (config value diverging from the module)

```
BROKEN    maxWidth: { ...LAYOUT_WIDTHS, shell: "1400px" }
          ✕ emits the shell tier at the value declared in the module
              Expected "1700px" / Received "1400px"
          ✕ feeds theme.extend.maxWidth from the declaration module
          2 failed, 9 passed   rc=1
RESTORED  hardcoded literal absent rc=1 | worktree==HEAD rc=0 | 11/11 rc=0
```

Precisely isolated: only the shell arm and its structural twin moved.

### ARM D — the container fail arm

```
BROKEN    corePlugins: { container: true }
          ✕ emits no container rule even when the class name is in the content
            + ".container [unwrapped]"
            + ".container [@media (min-width: 640px)]"
            + ".container [@media (min-width: 768px)]"
            + ".container [@media (min-width: 1024px)]"
            + ".container [@media (min-width: 1280px)]"
            + ".container [@media (min-width: 1536px)]"
          2 failed, 9 passed   rc=1
```

This independently reproduces the plan's non-negotiable constraint: with the theme block
deleted but the plugin on, Tailwind emits **five** media queries instead of the single 1400px
one the tree had. Confirmed at the shipped-artifact level too — a build under this arm put six
container caps in the stylesheet, where the correct tree returns rc=1:

```
.container{width:100%}  .container{max-width:640px}  .container{max-width:768px}
.container{max-width:1024px}  .container{max-width:1280px}  .container{max-width:1536px}
```

`RESTORED` 11/11 rc=0, and the closing build's CSS content hash returned to
`2y-_392jhqod7.css` — different from this arm's `1x71fawq80pc-.css`, which is an identity
proof that the restore landed.

### ARMs E–I — added beyond the plan (falsifiability is a standing criterion)

The plan specified four arms, none covering Task 1's assertions, whose only fail direction
would otherwise have been the weak "the module is missing".

| Arm | Broken input | Result |
|---|---|---|
| **E** purity | added `import { cn } from "@/lib/utils"` | 1 failed / 11 passed — and the printed `Received string` shows the docblock stripped to whitespace with only the real import left, proving the check is not satisfied by its own prose |
| **F** index key | added `index: "1600px"` to `LAYOUT_WIDTHS` | 1 failed / 11 passed, diff names the stray key |
| **G** derivation | hand-typed `shell: "1690px"` | 1 failed / 11 passed, `Expected "1700px" / Received "1690px"` — the *plausible* drift, since 1690 is Stripe's real value |
| **H** justification | deleted the shell peer citation | **DID NOT FIRE — 12/12 green.** See Deviations |
| **I** superseded value | removed all 3 header mentions of 1400 | 1 failed / 14 passed |

### Closing clean run

| Check | Result |
|---|---|
| Both new suites | **26/26**, rc=0 |
| Full Jest suite | **139 suites / 1360 tests**, rc=0 — no existing test regressed |
| `npm run build` | rc=0 |
| Shipped CSS: container rules | `[]` rc=1, control `.mx-auto{...}` rc=0 |
| `npm run lint` | rc=0, 0 errors; no finding in any new file (control: `use-toast.ts` still reported) |
| `scripts/check-e2e-typecheck.sh` | rc=0, 30 e2e files clean |
| `scripts/check-gate-enforcement.sh` | rc=0, 39 gates |
| `scripts/docs-freshness.sh` | rc=0 |
| `scripts/check-doc-metrics.sh` | rc=0, 37/37 claims |
| `scripts/check-branch-behind-base.sh` | rc=0, 9 ahead / **0 behind** |
| `npx tsc --noEmit -p tsconfig.json` | rc=0 |

The `tsc` pass is itself a two-directional proof: the test's `@ts-expect-error` on
`const notATier: WidthTier = "prose"` would red as an *unused directive* if `"prose"` were
ever admitted to the union.

## Deviations from Plan

### 1. [Rule 1 - Bug] The justification check was vacuous, found only by running ARM H

- **Found during:** Task 3, ARM H
- **Issue:** The check asserted each tier's peer measurement is recorded, but searched the
  **whole file**. Deleting `"Stripe's own dashboard at 1690"` from the shell docblock left the
  suite **12/12 green**, because the module header uses the same figure in an unrelated
  sentence about what a drifting spec would measure. It could fail in principle — deleting
  every mention would red it — but it was not testing what it claimed.
- **Fix:** Rescoped to read the docblock immediately preceding each export, with its own
  non-vacuity control (the shell block must exceed 100 chars, must NOT contain the detail or
  marketing peers, and an unknown export must yield `""`) — because the failure mode that made
  the first draft pass is exactly "the scope is secretly the whole file". The superseded-value
  assertion was scoped to the module header for the same reason.
- **Re-run against the same broken input:** 1 failed / 14 passed, failure printing only the
  shell docblock.
- **Commit:** `04b9cadf`

### 2. [Rule 3 - Blocking] Two instrument defects in my own CSS test, caught before it was trusted

- **Found during:** Task 2 RED
- **Issue:** (a) `expect(value, "message")` is the **Playwright** idiom; Jest's `expect` takes
  one argument and threw `Expect takes at most one argument` on every assertion, producing 11
  failures that were all the instrument. (b) A non-vacuity threshold of `emitted.length > 50`
  was **invented rather than measured**; the true count is 45, so the vacuity control was
  itself a false red.
- **Fix:** Diagnostics now travel in the compared value. The vacuity control asserts two
  signals measured on this tree — the brand colour, which exists only in this repo's theme,
  and a preflight rule. Grounded with an out-of-band jiti+PostCSS probe rather than another
  guess.
- **Commit:** folded into `e26279b4`

### 3. [Rule 3 - Blocking] Doc-metric gates, not in the plan's file set

- **Issue:** Both halves of the docs loop red on the new Jest blocks (RESEARCH pitfall 8).
  `docs-freshness.sh` rc=1, then `check-doc-metrics.sh` rc=1 on README/CLAUDE/AGENTS.
- **Fix:** Regenerated via `scripts/docs-freshness.sh --write` (never hand-edited, never
  arithmetic); prose claims updated to match. `jest_blocks` 1334→1361, `jest_files` 137→139,
  total 3318→3345. Note the block count moves by 27 while the suite gains 26 tests: the counter
  reads literal `it(`/`test(` tokens, and one `it.each` over three tiers is one token and three
  runs.
- **Commit:** `a7e5c2d1`

### 4. [Blind-search artefact, self-caught] An empty variable widened a search to the whole repo

While first checking the built CSS I used an unset `$CSS`, so `rg` searched the current
directory instead of the artifact and returned container rules from `node_modules` and docs.
Caught immediately because the "css files:" line printed empty. Re-run with an explicit file
and a `[ -z ]` guard that exits 2 (VOID) rather than passing on nothing. No conclusion was
drawn from the bad run.

## Known Stubs

None. This plan ships no placeholder, no empty state and no TODO.

## Interim state this plan deliberately leaves (resolved by 35-02)

`components/dashboard/dashboard-shell.tsx:55` still carries the `container` class, which now
generates nothing — so on this commit the dashboard band is **fluid** rather than capped at
1700. That is expected: plan 35-02 (wave 2, `depends_on: ["35-01"]`) owns that file and
replaces the class with the Shell tier. It is recorded here rather than left implicit because
an unstated intermediate state reads as a defect. The phase branch is not merged until the
wave completes. Verified this is the **only** such site: a class-context search over `app/` +
`components/` returns exactly 1 hit (control: the same pattern shape for `mx-auto` matches many
files, so the count is about the tree, not the pattern) — confirming RESEARCH assumption A1.

## Coverage boundaries, stated rather than implied

- **The dashboard tiers are not measured in a browser by anything on this tree.** Per-PR CI
  runs only `public-layout.spec.ts` + `public-a11y.spec.ts`. Shell/Index/Detail assertions land
  in the nightly lane, and **issue #683 records that lane as dark**. The honest phrasing is
  *"covered by a spec that no current tree executes"*, never *"covered nightly"*. This plan
  ships no Playwright spec (35-08 does); what it does ship is the stack-free per-PR drift gate,
  which proves the config and the constant have not diverged but **cannot** prove a class was
  applied to a surface.
- **Mobile identity is proven structurally, not by a browser.** Every tier rule is emitted with
  no media query, so the narrowest cap (1100px) is present at 390px and cannot bind against a
  390px parent. That is a property of the stylesheet; the browser arm belongs to later plans.
- **CLS/LCP not measured here.** No surface renders any new class yet, so there is nothing to
  measure. ORCH-02's desktop CLS arm belongs to 35-09.
- **SEO: N/A.** No metadata, route or markup changes.
- **AI agent-readiness: N/A.** No API surface changes.

## Cited decisions

ORCH-03 (orchestrator decision, 2026-08-29) is the reason `index` appears in `WidthTier` while
declaring no width. ORCH-01/02/04/05 do not bear on this plan's file set.

## Threat Flags

None. This plan adds no endpoint, no input, no credential, no data flow and no dependency.
T-35-01 (a class-name string in `lib/` silently never generated) is mitigated by holding only
numbers there, asserted by the purity test and proven by ARM E. T-35-02 (config/module drift)
is mitigated by the PostCSS test and proven by ARM C. T-35-03 (an unresolvable config import)
is mitigated by ARMs A/B. T-35-SC: nothing was installed, so the Package Legitimacy Gate did
not run — correctly not applicable rather than skipped.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `ea5d0451` | test | the layout-widths contract, asserted before it exists (RED) |
| `f84dac83` | feat | declare the four-tier horizontal layout contract in one module (GREEN) |
| `e26279b4` | test | read the width contract out of the emitted stylesheet (RED) |
| `8753789c` | feat | generate the tier utilities and retire the shadcn container (GREEN) |
| `04b9cadf` | fix | scope the justification check to each number's own docblock |
| `a7e5c2d1` | docs | regenerate test metrics for the two new Jest suites |

## TDD Gate Compliance

Both cycles complete and in order: `test(ea5d0451)` → `feat(f84dac83)`, then
`test(e26279b4)` → `feat(8753789c)`. Each RED gate was observed failing with recorded output
before its GREEN commit. No REFACTOR commit was needed.

## Next

Plan 35-02 (wave 2) builds `components/layout/content-tier.tsx` — the single parameterised
tier wrapper where the class literals live, since Tailwind cannot see them in `lib/` — and
applies the Shell tier to the dashboard shell, closing the interim state noted above.

## Self-Check: PASSED

All 9 claimed files exist on disk; all 6 claimed commit shas resolve. Verified with a control
(`deadbee` correctly absent), so the FOUND results are about the repository rather than about a
check that cannot fail.
