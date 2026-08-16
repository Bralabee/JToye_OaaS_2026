---
phase: 31-consumer-safety-and-legal-floor
plan: 01
subsystem: testing
tags: [accessibility, axe-core, jest-axe, playwright, radix-ui, wcag, supply-chain, typescript]

# Dependency graph
requires: []
provides:
  - "axe-core@4.13.0, @axe-core/playwright@4.13.0 and jest-axe@10.0.0 as exact-pinned devDependencies, with package-lock.json committed"
  - "components/ui/checkbox.tsx — the Radix checkbox primitive at the UI-SPEC's 24px with the house focus ring"
  - "__tests__/axe-instrument.test.tsx — a permanent three-arm falsification proving jest-axe can both fail and reach zero"
  - "types/jest-axe.d.ts — local type declarations replacing the rejected @types/jest-axe"
affects: [31-02, 31-03, 31-14, "every LGL-02 plan asserting zero axe violations"]

# Tech tracking
tech-stack:
  added: ["axe-core@4.13.0", "@axe-core/playwright@4.13.0", "jest-axe@10.0.0", "@radix-ui/react-checkbox@^1.3.11"]
  patterns:
    - "Instrument falsification as a permanent suite member, not a one-off break arm recorded in prose"
    - "Rule-id assertions (image-alt, button-name) rather than bare violation counts — measured to be the only arm that catches a repaired fixture"
    - "Non-vacuity control asserting the fixture mounted before any axe result is trusted"

key-files:
  created:
    - frontend/components/ui/checkbox.tsx
    - frontend/__tests__/axe-instrument.test.tsx
    - frontend/types/jest-axe.d.ts
  modified:
    - frontend/package.json
    - frontend/package-lock.json

key-decisions:
  - "@types/jest-axe REJECTED at the gate; a local ambient .d.ts replaces it, keeping axe-core 3.x out of the tree"
  - "The ambient .d.ts declares the WHOLE jest-axe module, not just the matcher, because jest-axe@10 ships no declarations at all"
  - "The .d.ts is a global script, not a module: `export {}` would convert `declare module` into an augmentation of an untyped package and stop resolving"
  - "docs/metrics.json deliberately NOT regenerated in this worktree — seven parallel plans would each write a different number to the same three keys"

patterns-established:
  - "Falsification arms are bracketed clean -> break -> clean, with every restore verified by git hash-object rather than git diff --stat"
  - "A build reporting rc=0 is only trusted after the same build is shown to fail on an injected type error"

requirements-completed: [LGL-02]

# Metrics
duration: 71min
completed: 2026-08-16
---

# Phase 31 Plan 01: Accessibility Instrument Bootstrap Summary

**The axe toolchain pinned behind a per-package human legitimacy gate, the Radix checkbox at 24px,
and a permanent three-arm jest-axe falsification whose break arm proved — empirically — that the
plan's insistence on asserting rule ids rather than violation counts was load-bearing.**

## Performance

- **Duration:** ~71 min (including the blocking human gate)
- **Completed:** 2026-08-16
- **Tasks:** 3 of 3
- **Files modified:** 5 (2 modified, 3 created)

## Accomplishments

- Three devDependencies installed at exact pins with the lockfile committed, plus the Radix checkbox
  as a runtime dependency — all four individually approved at the Task 1 gate.
- `@types/jest-axe` rejected and replaced with local declarations, keeping a third major of
  `axe-core` out of the tree.
- A permanent instrument test that reds the build if `jest-axe` ever stops being able to fail.
- Threat T-31-01-04 closed **by measurement**, not intent: `eslint-plugin-jsx-a11y` now dedupes onto
  the pinned `axe-core` instead of resolving its own copy.

## Task Commits

1. **Task 1: Package legitimacy gate** — `040c597a` (docs) — checkpoint record; no code
2. **Task 2: Install packages + resize the checkbox primitive** — `c67f9186` (feat)
3. **Task 3: Prove the jsdom instrument can fail** — `9b04b2df` (test)

## Files Created/Modified

- `frontend/package.json` — three exact-pinned axe devDependencies + `@radix-ui/react-checkbox`
- `frontend/package-lock.json` — committed alongside; CI runs `npm ci` and reads nothing else
- `frontend/components/ui/checkbox.tsx` — Radix primitive, 24px box, house focus ring
- `frontend/__tests__/axe-instrument.test.tsx` — the three-arm falsification
- `frontend/types/jest-axe.d.ts` — local `jest-axe` declarations (authorised deviation, below)

## Task 1 — the four per-package verdicts, recorded verbatim

`slopcheck` could not be run: `pip install slopcheck` is refused by the `block-base-python.py` hook
and this project declares no `.conda-env`. It was not rerouted around, which is why the human gate
was the only legitimacy instrument available.

| # | Package | Verdict |
|---|---------|---------|
| 1 | `axe-core@4.13.0` | **APPROVED.** "Install at the exact pin as planned." |
| 2 | `@axe-core/playwright@4.13.0` | **APPROVED**, with the `prepare`-script assessment accepted as correct. |
| 3 | `jest-axe@10.0.0` | **APPROVED as planned.** The nested `axe-core@4.10.2` accepted knowingly. |
| 4 | `@radix-ui/react-checkbox@1.3.11` | **APPROVED** (maintainer set proven identical to four already-installed Radix packages). |
| 5 | `@types/jest-axe` | **REJECTED.** Not installed. Local ambient declaration instead. |

Three orchestrator findings settled points left open at the checkpoint: the Radix maintainer set is
byte-identical to that of `react-dialog`, `react-dropdown-menu`, `react-select` and `react-tabs`, so
it extends no new publisher trust; `jest-axe@10` ships **no** types of its own; and `@types/jest-axe`
does depend on `axe-core: ^3.5.5`.

### The `prepare` script, recorded so it is not re-litigated

`@axe-core/playwright@4.13.0` declares `prepare: "npx playwright install && npm run build"`.
RESEARCH's "no postinstall" column was accurate as far as it went — `preinstall`, `install` and
`postinstall` are all absent — but it did not cover `prepare`. This is **not** an install-time
execution hazard: npm runs `prepare` only for git/directory dependencies and inside a package's own
working tree, never when installing a published registry tarball.

## An instrument defect found before any of the above was trusted

The first pass at reading install-lifecycle scripts reported "no postinstall" for all four packages.
That was an artefact. `npm view <spec> --json` returns a **JSON array**, so `j.scripts` read
`undefined` and printed exactly what a genuinely clean package prints. The tell was that
`dist.integrity` also read `undefined`, and every published version has one:

```
rawlen=5257
first120="[\n  {\n    \"_id\": \"jest-axe@10.0.0\", ..."
type=array
keys=0
```

The replacement checker asserts `dist.integrity` as a positive control and exits 2 (VOID) without
it. Break arm run first:

```
$ echo '[{"name":"fake","version":"0.0.0","scripts":{}}]' | node pkgcheck.js
VOID: dist.integrity absent — parser is not reading a manifest
BREAK_ARM_rc=2
```

The broken parser would have missed the `@axe-core/playwright` `prepare` script entirely.

## Task 2 — the lockfile measurements the plan asked for

Read from `package-lock.json` directly, not from `npm ls`. A full sweep of **every** copy of both
packages returns exactly four entries:

```
node_modules/axe-core                                    4.13.0
node_modules/jest-axe/node_modules/axe-core              4.10.2
node_modules/jest-matcher-utils                          29.7.0
node_modules/jest-axe/node_modules/jest-matcher-utils    29.2.2
```

**Did `jest-axe@10` install cleanly? Yes** — no resolution error, no fallback to v11 considered.

**Did a second MAJOR of `jest-matcher-utils` appear? No.** v10 nests a second *copy* at 29.2.2, but
both are 29.x, matching the repo's `jest@^29.7.0`. v11 would have brought `30.4.1`. This is exactly
the outcome the v10-over-v11 pin was chosen for, and it is now a measurement rather than an
inference from declared dependencies (RESEARCH assumption A2 discharged).

**Bonus, measured:** no `axe-core` 3.x appears anywhere — which is precisely what rejecting
`@types/jest-axe` bought. And `eslint-plugin-jsx-a11y@6.10.2` declares `axe-core: ^4.10.0`, so it has
**deduped onto the top-level 4.13.0 pin** instead of resolving its own 4.11.2. Threat T-31-01-04
(rule drift under an unrelated eslint bump) is therefore closed by measurement.

### ⚠ Carry-forward for 31-02 and every later a11y plan

**The two accessibility layers do NOT share one rule set.** `jest-axe@10` pins `axe-core` at
*exactly* `4.10.2`, so npm cannot dedupe it onto the top-level pin. The jsdom layer runs **4.10.2**
rules; `@axe-core/playwright` runs **4.13.0**. Accepted knowingly at the gate. A later plan asserting
"one shared rule set" would be false, and a rule present in 4.13.0 but not 4.10.2 will be enforced at
the E2E layer only.

## Approved deviation — one file outside `files_modified`

**`frontend/types/jest-axe.d.ts`** — authorised explicitly at the checkpoint when `@types/jest-axe`
was rejected. Recorded rather than silently absorbed, per that instruction.

Two refinements to the sketch given at the gate, both forced by the orchestrator's own Finding B
(jest-axe ships no types):

1. It declares the **whole `jest-axe` module**, not only the matcher. Confirmed independently on the
   installed tree: `node_modules/jest-axe/` contains `index.js`, `extend-expect.js`, `package.json`,
   `README.md`, `LICENSE.txt` — and no `.d.ts`.
2. It is a **global script, not a module**. The suggested `export {}` would have turned
   `declare module "jest-axe"` into a *module augmentation* of a package that has no types to
   augment, which does not resolve. For the same reason `namespace jest` is declared at top level
   rather than inside `declare global` (legal only in a module).

`interface Matchers<R, T = {}>` copies `@types/jest`'s own arity verbatim
(`node_modules/@types/jest/index.d.ts:801`); TypeScript requires identical type parameters across
merged declarations, so the unused `T` and the `{}` cannot be "cleaned up". Two eslint rules are
disabled on that one line with that reason stated inline.

## Verification — both directions, real output

Exit codes captured on their own statements. Every break arm was run **after** the relevant commit,
and every restore verified by content hash, never by `git diff --stat`.

### Task 2

| Assertion | Pre-change (fail direction) | Post-change |
|---|---|---|
| axe entries in `package.json` | `axe_hits_in_package_json=0` | `playwright_pin=1`, `axecore_pin=1`, `jestaxe=1` |
| `components/ui/checkbox.tsx` exists | `ls: cannot access … No such file` (`ls_rc=2`) | present |
| `h-6 w-6` present | 0 (file absent) | `h6w6=3` |
| `h-4 w-4` absent | n/a (file absent); **as generated by shadcn it was 2** | `h4w4=0` |
| imports `@radix-ui/react-checkbox` | 0 (file absent) | `radix_import=1` |

```
npm run lint    LINT_rc=0     (28 warnings, 0 errors — all pre-existing)
npm run build   BUILD_rc=0    "Running TypeScript ... Finished TypeScript in 9.5s"
```

**`BUILD_rc=0` is only evidence because the build was shown to fail.** `next.config.mjs` sets no
`ignoreBuildErrors`, and with a deliberate type error injected into `checkbox.tsx`:

```
BUILD_BREAK_rc=1
./components/ui/checkbox.tsx:37:7
Type error: Type 'string' is not assignable to type 'number'.
> 37 | const BREAK_ARM_PROBE: number = "not a number"
```

Restore verified by content: `git hash-object` = `4f762d0a1185716fa932254025ff7c9ff01eda00` =
`git rev-parse HEAD:frontend/components/ui/checkbox.tsx`.

**The ambient declaration was proven load-bearing.** With `types/jest-axe.d.ts` moved aside,
`npx tsc --noEmit` errors rose from 3 to 6, the new ones all in the instrument test:

```
__tests__/axe-instrument.test.tsx(38,41): error TS7016: Could not find a declaration file for
  module 'jest-axe'. '.../node_modules/jest-axe/index.js' implicitly has an 'any' type.
__tests__/axe-instrument.test.tsx(109,43): error TS7006: Parameter 'v' implicitly has an 'any' type.
__tests__/axe-instrument.test.tsx(131,23): error TS2339: Property 'toHaveNoViolations' does not
  exist on type 'JestMatchers<any>'.
```

Restored from git; `git hash-object` = `99893cb554244cee011ee06bcc81a9774aa5ec4c` = the committed
blob.

### Task 3 — the instrument falsification

Clean direction:

```
PASS __tests__/axe-instrument.test.tsx
  ✓ renders the broken fixture at all — the instrument can see the nodes (100 ms)
  ✓ BREAK ARM: reports violations on the broken fixture, naming image-alt and button-name (329 ms)
  ✓ CLEAN ARM: reaches zero violations on an accessible fixture (184 ms)
Tests: 3 passed, 3 total          JEST_rc=0
```

Fail direction, run as the plan specified (give the broken fixture's `img` a real `alt` and the
`button` a visible label):

```
FAIL __tests__/axe-instrument.test.tsx
  ✓ renders the broken fixture at all — the instrument can see the nodes (29 ms)
  ✕ BREAK ARM: reports violations on the broken fixture, naming image-alt and button-name (67 ms)
  ✓ CLEAN ARM: reaches zero violations on an accessible fixture (17 ms)

    expect(received).toContain(expected) // indexOf
    Expected value: "image-alt"
    Received array: ["link-name"]
Tests: 1 failed, 2 passed, 3 total    JEST_BREAK_rc=1
```

**This is the most important result in the plan.** Note *which* assertion caught the repair:
`expect(results.violations.length).toBeGreaterThan(0)` **still passed**, because `link-name` was
still firing. A count-only break arm would have stayed **green** while both rules the phase actually
depends on were silently lost. The plan's requirement to name `image-alt` and `button-name`
specifically is not defensive verbosity — it is the only thing that failed here.

Restore verified by content: `git hash-object` =
`f7bbe0480362856b1c49b143be426f1e8e32b4d6` = `git rev-parse HEAD:frontend/__tests__/axe-instrument.test.tsx`.
Closing clean run **last**, per the bracket rule: `FINAL_JEST_rc=0`, 3 passed.

## Findings that change what 31-14 must do

The must-have "the acknowledgement checkbox primitive renders a real native input, not a styled div"
was treated as a claim to measure, not to copy from Radix's documentation. A throwaway probe was
rendered, read, and deleted (tree confirmed clean afterwards). Both results are real:

**1. Standalone, there is NO native input.**

```
NATIVE_INPUT_FOUND: false
NATIVE_TAG: undefined
ROLE_CHECKBOX_PRESENT: true
BOX_CLASSES: grid place-content-center peer h-6 w-6 shrink-0 rounded-sm border border-primary
             shadow ring-offset-background focus-visible:outline-none focus-visible:ring-2
             focus-visible:ring-ring focus-visible:ring-offset-2 …
```

Radix renders a `<button role="checkbox">` and mounts its hidden `<input type="checkbox">` only when
the control is inside a `<form>`. So the must-have holds in its important half — this is a real
focusable control with correct checkbox semantics, **not** a styled div — but the *native input*
half is **conditional on being inside a form**. If 31-14 places the acknowledgement checkbox outside
a form, there is no native input and no submitted value. The `h-6 w-6` and the house focus ring are
confirmed present on the rendered element.

**2. Inside a form it currently CRASHES under jsdom.**

```
ReferenceError: ResizeObserver is not defined
  at node_modules/@radix-ui/react-use-size/src/use-size.tsx:12:30
```

The in-form BubbleInput path pulls `@radix-ui/react-use-size`, which needs `ResizeObserver`; jsdom
does not implement it, and `jest.setup.js` stubs `hasPointerCapture`, `setPointerCapture`,
`releasePointerCapture` and `scrollIntoView` for Radix but **not** `ResizeObserver` (measured: 0
occurrences in `jest.setup.js`).

**This is out of scope here and deliberately not fixed** — `jest.setup.js` is shared and six sibling
plans are running in parallel worktrees. It also does not need to be: the repo's established
convention is a per-file stub, already used by
`components/storefront/__tests__/cart-drawer.test.tsx:60`,
`app/dashboard/__tests__/page.test.tsx` and
`components/marketing/__tests__/competitive-teardown.test.tsx`:

```ts
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
// in beforeAll:
if (!window.ResizeObserver) { window.ResizeObserver = ResizeObserverStub }
```

31-14 should copy that stub into its own test file the moment it renders the checkbox in a form.

## Deviations from Plan

### Approved at the checkpoint

**1. [Authorised] Added `frontend/types/jest-axe.d.ts`, outside `files_modified`**
- **Reason:** `@types/jest-axe` rejected at the gate; explicitly authorised as the replacement.
- **Refinements:** declares the whole module (not just the matcher) and is a global script (not a
  module) — both forced by jest-axe shipping no declarations. Detailed above.
- **Committed in:** `c67f9186`

### Deliberate omission

**2. [Scope] `docs/metrics.json` NOT regenerated in this worktree**
- **Plan text:** Task 3's `<done>` says to run `scripts/docs-freshness.sh --write`.
- **Why omitted:** the plan's own `<verification>` states `docs/metrics.json` is a generated artefact
  deliberately absent from every plan's `files_modified`, and seven plans are running in parallel.
  Each would regenerate from its own worktree, see only its own additions, and write a different
  value to the same three keys — a guaranteed conflict whose winner is wrong.
- **Measured, not computed by arithmetic.** Read-only run: `DOCSFRESH_rc=1`, drift =
  `jest_blocks 944 -> 947`, `jest_files 99 -> 100`, `total_logical_invocations 2807 -> 2810`.
  `--write` then produced `WRITE_rc=0` with a diff touching exactly those three keys and nothing
  else, confirming the delta is entirely this plan's 3 `it()` blocks in 1 new file.
- `docs/metrics.json` was then **restored** and verified by content:
  `git hash-object` = `5e7636988c44f84d7f3db51e14032b7a21cd4e99` = the committed blob.
- **ACTION FOR THE ORCHESTRATOR:** run `scripts/docs-freshness.sh --write` **once** after the wave
  merges, then reconcile the prose counts in `CLAUDE.md`, `AGENTS.md` and `README.md` — the second
  gate (`scripts/check-doc-metrics.sh`) reads those and they are shared files no parallel agent
  should touch.

---

**Total deviations:** 1 authorised addition, 1 deliberate scope omission. No auto-fixes were needed.
**Impact:** none on scope. The omission is a sequencing decision, with the exact delta measured and
handed over.

## Issues Encountered

1. **Worktree base behind the plan base.** HEAD was `bb2ae65d`, an ancestor of the required
   `64d9f0ad`. Corrected with the sanctioned setup-time `git reset --hard`; tree was clean, nothing
   discarded.
2. **Vacuous package-metadata parser** (the `npm view --json` array). Caught by its own positive
   control before any verdict was formed.
3. **Lint error in the new `.d.ts`** — `@typescript-eslint/no-empty-object-type` on the `{}` copied
   from `@types/jest`. Not "fixed", because changing it breaks interface merging; scoped
   `eslint-disable-next-line` with the reason recorded inline. `npm run lint` then `rc=0`.
4. **Three pre-existing `npx tsc --noEmit` errors** in `__tests__/shop/server-seeded-islands.test.tsx`,
   `components/dashboard/__tests__/dashboard-shell.test.tsx` and `lib/__tests__/structured-data.test.ts`.
   None in this plan's files (verified: grep for this plan's paths against the tsc log returns rc=1);
   the Next build excludes them from its production typecheck, so `npm run build` is green. Left
   alone as out of scope.

## Threat Flags

None. No network endpoint, auth path, file-access pattern or schema change was introduced. The two
supply-chain threats this plan carried (`T-31-01-SC`, `T-31-01-04`) were both mitigated and the
mitigation measured.

## Next Phase Readiness

Ready. Downstream LGL-02 plans have their toolchain, and the jsdom layer's zero is now worth
something because zero has been shown to be reachable *and* avoidable.

Three things a later plan must not re-derive:
- the jsdom/Playwright rule-set split (4.10.2 vs 4.13.0);
- the checkbox's native input existing only inside a `<form>`, and that path needing a local
  `ResizeObserver` stub;
- `docs/metrics.json` still owing a single post-merge regeneration (+3 blocks, +1 file, 2807 → 2810).

## Self-Check: PASSED

- All four created files present on disk.
- All four commits present in `64d9f0ad..HEAD`: `040c597a`, `c67f9186`, `9b04b2df`, `04e9b9e0`.
- `git status --short` empty — nothing uncommitted; every break-arm restore landed.
- `git diff --diff-filter=D 64d9f0ad..HEAD` empty — no file was deleted by any commit.
- Files changed by the whole plan are exactly the six intended: the five in scope plus this SUMMARY.
  **`STATE.md` and `ROADMAP.md` are untouched**, as required for a parallel worktree agent.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
