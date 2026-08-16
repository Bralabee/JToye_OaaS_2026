---
phase: 31-consumer-safety-and-legal-floor
plan: 02
subsystem: ui
tags: [accessibility, wcag, eslint, jsx-a11y, tailwind, contrast, jest, landmarks]

# Dependency graph
requires:
  - phase: 19-full-frontend-overhaul
    provides: "palette-discipline.test.ts — the static grep-gate shape (grepCount helper, the __tests__/ placement that stops a gate scanning its own literals, and the deliberate non-zero positive control)"
  - phase: 30-public-surface-brand
    provides: "contrast-tokens.test.ts + globals.css token moves (--primary orange-700, --trust emerald-700) — the recompute-from-source contrast idiom this plan extends to utility classes"
provides:
  - "frontend/__tests__/contrast-literals.test.ts — recompute-from-source AA gate over Tailwind text utilities on the D-09 declared consumer surfaces, measured against BOTH #ffffff and brand cream #FBF6F0"
  - "UNASSERTED_SITES — an enumerated, ratio-annotated ledger of 55 (file, utility) pairs already below AA, ready for 31-13's conformance statement to fix or declare"
  - "jsx-a11y enabled at error for 31 rules (was 6, all at warn) in frontend/eslint.config.mjs — a static a11y layer on every PR with zero new packages and zero new CI minutes"
  - "F-A: the delivery-threshold string is text-emerald-700 on /shop and /shop/[slug] — 4 declared-surface axe color-contrast nodes closed"
  - "F-B: three distinct storefront <nav> accessible names — the landmark-unique node closed"
  - "15 real keyboard/heading a11y defects fixed at source across 7 files (drop zones, card triggers, propagation-shield divs, CardTitle)"
affects: [31-13-conformance-statement, 31-18-docs-corrections, qa-council-a11y-audit]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Debt-ledger gate: an exemption register that cannot rot — a registered pair must still be BELOW AA and must still EXIST in the tree, so a fix cannot be silently absorbed and a fixed site loses its exemption"
    - "Stretched-trigger button (from #446) reused for drop zones that contain their own interactive children, where role=button on the wrapper would make those children presentational"
    - "Comment-stripping before a source regex, with a synthetic-input control proving the filter did not blind the extractor"

key-files:
  created:
    - frontend/__tests__/contrast-literals.test.ts
  modified:
    - frontend/eslint.config.mjs
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/components/storefront/storefront-nav.tsx
    - frontend/components/ui/card.tsx
    - frontend/components/ui/image-uploader.tsx
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/products/import/page.tsx
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "The plan's fail-direction arm for the lint layer CANNOT FAIL as literally written and is reported as such, not as satisfied: a placeholder-only input with no <label> measured rc=0 / 0 errors, because label-has-associated-control fires on <label> elements"
  - "control-has-associated-label stays OFF on measured evidence, not preference: with input un-ignored it produced 30 errors including a control that IS correctly named by <label htmlFor>, so it cannot tell a labelled control from an unlabelled one"
  - "The contrast sweep is scoped to the D-09 declared consumer surfaces, not the whole tree — a whole-tree blanket assertion is RED on a correct tree (170 non-compliant pairs, most on dark surfaces or icons) and the dashboard is outside the published conformance claim"
  - "No jsx-a11y rule was downgraded for any path, so there are NO D-12 exceptions to declare — all 15 findings were fixed at source"
  - "Two rule OPTIONS were widened with recorded reasons rather than rules dropped: label-has-associated-control depth 2->3, and no-noninteractive-tabindex roles += region"
  - "The plan's verify assertion `grep -cF FlatCompat eslint.config.mjs == 0` is an expected-0 that is 1 on the CORRECT tree (the file's own header forbids FlatCompat by name); replaced with a strictly stronger form asserting zero imports/invocations, control-armed"

patterns-established:
  - "Ledger anti-rot: two assertions (still-below-AA, still-present-in-tree) turn an exemption list from a hiding place into a forcing function"
  - "Every new static gate ships a positive control AND a control for any filter added to suppress a false positive"

requirements-completed: [LGL-02]

# Metrics
duration: 45min
completed: 2026-08-16
---

# Phase 31 Plan 02: Contrast Literals + jsx-a11y Layer Summary

**A contrast gate that can see Tailwind utility classes (recomputing every `text-<ramp>-<step>` from the palette against both shipped backgrounds), a jsx-a11y lint layer widened 6 -> 31 rules at error, and the 5 axe nodes plus 15 keyboard defects they exist to catch — all closed at source with no rule downgraded and no inline disable.**

## Performance

- **Duration:** ~45 min
- **Started:** 2026-08-16T12:55Z (worktree reset to plan base `64d9f0ad`)
- **Completed:** 2026-08-16T12:40Z (UTC clock; commits stamped 13:15–13:30 +0100)
- **Tasks:** 2 (3 commits — Task 1 carries a second commit recording a measurement made during its fail-direction arm)
- **Files modified:** 12 (1 created)

## Accomplishments

- **F-A closed.** The delivery-threshold string is `text-emerald-700` (#047857) on both `/shop` and `/shop/[slug]` — 4 of the declared surfaces' 15 measured axe `color-contrast` nodes. Same step `--trust` already took; no new brand colour, no CSS variable touched, copy/weight/layout unchanged.
- **F-B closed.** Three storefront `<nav>` landmarks now carry three DIFFERENT accessible names — `Storefront` (header), `Storefront menu` (mobile sheet) and `Menu categories` (the sticky strip). The sheet was NOT in the plan and is added deliberately: it is in the accessibility tree at the same time as the header nav, which is exactly the `landmark-unique` condition.
- **A gate that can see what the token test cannot.** `contrast-literals.test.ts` scans the declared consumer surfaces, resolves each utility to a hex from `tailwindcss/colors` + `tailwind.config.ts`, and recomputes the WCAG ratio against **both** `#ffffff` and `#FBF6F0`.
- **jsx-a11y: 6 rules at warn -> 31 rules at error.** Zero new packages (`eslint-plugin-jsx-a11y@6.10.2` is already transitively installed), zero new CI minutes (`npm run lint` already runs unfiltered).
- **All 15 findings fixed, none suppressed.** Including two mouse-only file drop zones, a shared UI primitive whose heading rule could never pass, and three `onClick` propagation shields whose only job was to suppress an ancestor.

## Task Commits

1. **Task 1: Extend the jsx-a11y rule set** — `ca59e2c8` (feat)
2. **Task 1 (fail-direction record): why control-has-associated-label stays off** — `1ae673e2` (docs)
3. **Task 2: Tailwind-literal contrast gate + the nodes it covers** — `4c9513bb` (feat)

## Files Created/Modified

- `frontend/__tests__/contrast-literals.test.ts` — **created.** The utility-class contrast gate: scan, resolve, recompute, two backgrounds, positive control, VOID arm, debt ledger with two anti-rot assertions, plus the F-B landmark-name assertions.
- `frontend/eslint.config.mjs` — the 31-rule jsx-a11y block, scoped to the Next config's own file glob, with recorded reasons for two option widenings and for the one rule deliberately left off.
- `frontend/app/shop/shop-discovery-client.tsx` — 2 x `text-emerald-600` -> `-700`.
- `frontend/app/shop/[slug]/shop-detail-client.tsx` — 2 x `text-emerald-600` -> `-700`; `aria-label="Menu categories"` on the category strip; dead `onClick` removed from the menu `<article>` and the stopPropagation shield it required.
- `frontend/components/storefront/storefront-nav.tsx` — `aria-label` on the header nav and the mobile sheet nav.
- `frontend/components/ui/card.tsx` — `CardTitle` renders `{children}` explicitly.
- `frontend/components/ui/image-uploader.tsx` — picker click moved onto a stretched `<button>`; Remove control raised to `z-20` and given an accessible name; "Click to replace" veil rebound to `group-hover`.
- `frontend/app/dashboard/orders/page.tsx` — actions cell marked `data-row-actions`; the row handler declines to navigate for clicks inside it.
- `frontend/app/dashboard/products/import/page.tsx` — both CSV/photo drop zones are real `<button>`s.
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md` — 2807 -> 2818 invocations, 944 -> 955 Jest blocks, 99 -> 100 Jest files.

## The measurements the plan asked for

### jsx-a11y rule counts (`npx eslint --print-config app/page.tsx`)

| | keys present | enabled | at `error` |
|---|---|---|---|
| **Before** | 6 | 6 | **0** (all at `warn`, and `eslint .` does not fail on warnings) |
| **After** | 31 | 31 | **31** |

Before, verbatim: `alt-text`, `aria-props`, `aria-proptypes`, `aria-unsupported-elements`, `role-has-required-aria-props`, `role-supports-aria-props` — matching the research measurement exactly. **Delta +25 enabled rules**, against the plan's floor of +15. `grep -cF 'jsx-a11y/' eslint.config.mjs` = 33.

### Rules downgraded for `app/dashboard/**`

**None.** No rule was set to `warn` or removed for any path, so there are **no D-12 exceptions for 31-13 to publish from this plan**. The escape hatch the plan authorised was not needed: the widened set produced 15 errors across 7 files and all 15 were fixed at source. Two rule OPTIONS were widened, each with a written reason in the config:

| Rule | Change | Why |
|---|---|---|
| `label-has-associated-control` | `depth` 2 -> 3 | A browser computes a `<label>`'s accessible name from its entire subtree; `depth` is a search budget, not a standard. `WebhookCreateDialog`'s event picker is `<label><input/><span><span>{text}` — real, correct, invisible at depth 2. Proven still able to fire at depth 3 (break arm 1b below). |
| `no-noninteractive-tabindex` | `roles` += `region` | A horizontally scrolling container must be focusable or its content is keyboard-unreachable (WCAG 2.1.1). `dish-scroller.tsx` already does the correct thing (`role="region"` + `aria-label` + `tabIndex={0}` + focus ring); without this the rule reds the fix and rewards deleting it. |

## Break arms — BOTH directions, real output

Every arm was run **after** the work was committed, restored by `git hash-object` compared against `git rev-parse HEAD:<path>`, and the clean direction re-run **last**.

### Arm 1a — the plan's literal lint arm. IT CANNOT FAIL. Reported, not substituted.

Deleted the `sr-only <label htmlFor="shop-search">` from `app/shop/shop-discovery-client.tsx`, leaving an input whose only accessible name is its `placeholder` — the exact A11Y-13 defect class.

```
BREAK:  npm run lint  ->  rc=0
        28 problems (0 errors, 28 warnings)
CLEAN:  npm run lint  ->  rc=0
        28 problems (0 errors, 28 warnings)
```

Identical. `label-has-associated-control` fires on `<label>` elements; an input with no label at all is invisible to it. **The criterion as written is incapable of failing and is recorded as such rather than reported satisfied.**

### Arm 1b — the strictly stronger replacement, which DOES fail

Kept the `<label htmlFor>` and emptied its text, so the placeholder is the sole accessible name.

```
BREAK:  npm run lint  ->  rc=1
        app/shop/shop-discovery-client.tsx
          382:9  error  A form label must have accessible text  jsx-a11y/label-has-associated-control
        29 problems (1 error, 28 warnings)
CLEAN:  npm run lint  ->  rc=0
        28 problems (0 errors, 28 warnings)
RESTORE: git hash-object = 41e60702f00a7d6606275dc1f352474f24a4089d
         git rev-parse HEAD:<path> = 41e60702f00a7d6606275dc1f352474f24a4089d   (equal)
```

### Arm 1c — the rule that WOULD have caught arm 1a, rejected on measurement

`control-has-associated-label` is the only rule in the plugin aimed at that shape. Enabled at `error` with `input`/`textarea` removed from `ignoreElements`:

```
TOTAL ERRORS: 30
  …
  app/shop/shop-discovery-client.tsx:390 [jsx-a11y/control-has-associated-label]
  …
```

Line 390 is the search input that **is** correctly named by `<label htmlFor="shop-search">` eight lines above it. The rule does not follow an `htmlFor`/`id` association across siblings, so it cannot distinguish a labelled control from an unlabelled one. Enabling it would add 30 wrong verdicts, not one right one. Recorded in `eslint.config.mjs` as a named non-adoption with its measurement (commit `1ae673e2`) rather than as a silent `off`.

### Arm 2a — revert one `text-emerald-700` to `-600` on /shop

```
BREAK:  npx jest __tests__/contrast-literals.test.ts  ->  rc=1
        ✕ every unregistered text colour clears AA on BOTH shipped light surfaces
          + "app/shop/shop-discovery-client.tsx:163 uses text-emerald-600 (#059669)
             — 3.77 on white, 3.51 on cream (AA needs 4.5)"
        ✕ the delivery-threshold string is emerald-700 on both shop surfaces (F-A)
          + "text-emerald-600"
        Tests: 2 failed, 9 passed
CLEAN:  rc=0, 11 passed
RESTORE: git hash-object = 5a1d9f4ef09496c348fe0bc427979701c9153d00  (== HEAD blob)
```

Note the recomputed ratio is **3.77** on white, not the plan's `3.76`: the plan's own objective also says 3.77 and `globals.css` records 3.77 for the pre-fix `--trust`. The 3.76 in the plan's `<interfaces>` is the inconsistent figure; the number in this gate is recomputed from `#059669`, never transcribed.

### Arm 2b — F12 exactly: `text-amber-800` -> `text-amber-400` in a scanned component

`components/storefront/customer-signin-card.tsx:107` (chosen because that file has no registered `amber-400`, so the ledger cannot absorb the break).

```
BREAK:  rc=1
        ✕ every unregistered text colour clears AA on BOTH shipped light surfaces
          + "components/storefront/customer-signin-card.tsx:107 uses text-amber-400 (#fbbf24)
             — 1.67 on white, 1.55 on cream (AA needs 4.5)"
        Tests: 1 failed, 10 passed
CLEAN:  rc=0, 11 passed
RESTORE: git hash-object = 3335cb0066075480d3f290ed11980a548eb3dd4c  (== HEAD blob)
```

### Arm 2c — delete both nav `aria-label` values

```
BREAK:  rc=1
        ✕ gives every storefront nav an accessible name
            Received string: "components/storefront/storefront-nav.tsx <unnamed>"
        ✕ gives them DIFFERENT names — landmark-unique fires on ambiguity, not absence
            Expected: 3   Received: 2
        Tests: 2 failed, 9 passed
CLEAN:  rc=0, 11 passed
RESTORE: git hash-object = f19993b416e7f31c3f9e360f3be187d8c4720265  (storefront-nav, == HEAD blob)
         git hash-object = c583e2d0a2869c8c8d95361478e291c7b2bcd33b  (shop-detail-client, == HEAD blob)
```

The `Expected: 3 Received: 2` is the ambiguity arm doing its job: with both labels gone the two `null`s collapse to one distinct value — precisely the "two navs called the same thing" state.

### Arm 2d — T-31-02-03: `--primary` relitigated under WCAG pressure

Changed `AA_NORMAL` in `contrast-tokens.test.ts` from `4.5` to `3.5`, which is how a threshold gets quietly weakened.

```
BREAK:  npx jest __tests__/contrast-tokens.test.ts  ->  rc=0, 8 passed
        (the token test is GREEN on a weakened contract — it cannot police its own threshold)
        git diff --name-only -- frontend/__tests__/contrast-tokens.test.ts | wc -l  ->  1
CLEAN:  same command  ->  0
RESTORE: git hash-object = c13ea0475776e16216552b08752cfc657ff53abd  (== HEAD blob)
```

This is the arm worth keeping: the suite stayed 8/8 green while the contract was cut by a full point, and only the diff assertion saw it.

### Arm 3 — the plan's `FlatCompat == 0` verify is an expected-0 that is 1 on a CORRECT tree

```
grep -cF 'FlatCompat' frontend/eslint.config.mjs                       -> 1
git show <plan base>:frontend/eslint.config.mjs | grep -cF FlatCompat  -> 1   (already 1 before any change)
rg -uu -n 'FlatCompat' eslint.config.mjs
  11: * them directly. Do NOT wrap them with FlatCompat — that crashes with a
```

The single hit is the file's own header forbidding it by name — the "doc rule that must name the token it forbids" shape. Replaced with a strictly stronger form asserting zero **usage**, and control-armed so the empty result is a fact about the code:

```
rg -uu -c -e 'new FlatCompat' -e '@eslint/eslintrc' eslint.config.mjs   -> rc=1  out=''
CONTROL (same flags, one arm swapped for a token known present):
rg -uu -c -e 'new FlatCompat' -e 'eslint-config-next' eslint.config.mjs -> rc=0  out='4'
git ls-files '.eslintrc.json' 'frontend/.eslintrc.json'                 -> ''   (no legacy config anywhere)
```

### In-suite controls that ship with the gate (they run on every PR, not just today)

- **Positive control:** `SITES.size >= 150` (169 found), distinct files `>= 15` (30 found), and the scan must contain the specific pair this plan created. Without it a clean sweep is a statement about the grep.
- **VOID arm, demonstrated firing:** `resolve("text-notaramp-600")`, `resolve("text-emerald-999")` and `resolve("text-white")` all throw `VOID:`; the same function then resolves `text-emerald-700` to `#047857`, so the throws are not "this function always throws".
- **Extractor control:** the nav scanner strips comments so it does not fire on its own documentation — and a synthetic-input test proves it still sees `<nav>` and `<nav className="x">` as unnamed. A filter added to kill a false positive is the classic route to a false negative.

## Decisions Made

- **Scope of the contrast sweep: the D-09 declared consumer surfaces, not the whole tree.** Measured first: a whole-tree assertion is 170 non-compliant `(file, class)` pairs — overwhelmingly `text-slate-300/400` icon tints and text on dark marketing blocks — so the plan's literal "scan `app/` and `components/`" is RED on a correct tree. The declared surfaces are what the conformance statement will actually cover, and are the same boundary `eslint.config.mjs` records.
- **`UNASSERTED_SITES` is a debt ledger, not a certificate,** and the file says so. 55 pairs, seeded by measurement and **not individually inspected**. Some are certainly fine; some are certainly not (`text-emerald-600` is still open on three surfaces; `text-red-600` misses the cream arm by 0.01). Its job is to stop the set growing and to hand 31-13 an enumerated, ratio-annotated list.
- **Two anti-rot assertions keep the ledger from becoming a hiding place:** a registered pair must still be below AA (so a fix cannot be absorbed) and must still exist in the tree (so a fixed site loses its exemption). The second is what makes arm 2a decisive. It will force later plans that fix a listed site to delete its ledger line — that churn is the mechanism, not a side effect.
- **`--primary` was not touched, and the guard is a diff not a re-read.** `contrast-tokens.test.ts` is byte-identical (`git diff --name-only` = 0 lines, blob `c13ea047`) and still 8/8.

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 3 - Blocking] `node_modules` absent in the worktree**
- **Found during:** Task 1, before the first measurement
- **Issue:** A fresh GSD worktree has no `frontend/node_modules`, so `eslint`, `jest` and `next build` could not run at all.
- **Fix:** `npm ci --no-audit --no-fund` (819 packages, 9s). This installs the **committed lock tree** and adds no package; `package.json` and `package-lock.json` were verified untouched afterwards (`git status --short` empty), honouring 31-01's ownership of those two files.
- **Verification:** `git status --short` clean immediately after install.
- **Committed in:** nothing — `node_modules` is gitignored.

**2. [Rule 3 - Blocking] The new eslint rules block aborted ESLint config resolution**
- **Found during:** Task 1
- **Issue:** An unscoped `rules` object referencing `jsx-a11y/*` made ESLint exit **rc=2** with `A configuration object specifies rule "jsx-a11y/alt-text", but could not find plugin "jsx-a11y"` — it also applies to files the Next config's glob does not match (`.cjs`), where the plugin is not registered. Re-declaring the plugin was rejected as a flat-config redefinition hazard.
- **Fix:** `files:` on the new object mirrors the Next config object's own glob exactly (`**/*.{js,jsx,mjs,ts,tsx,mts,cts}`), verified by reading that config's structure at runtime.
- **Verification:** rc=2 -> rc=1 (15 real findings) -> rc=0 after the fixes.
- **Committed in:** `ca59e2c8`

**3. [Rule 2 - Missing critical] 15 real accessibility defects the widened rules found**
- **Found during:** Task 1
- **Issue:** Two file drop zones (`products/import`, `ui/image-uploader`) were mouse-only — not tabbable, not activatable by Enter/Space, announced as nothing. `CardTitle` spread its children so `heading-has-content` could never pass. Three `<div onClick={e => e.stopPropagation()}` shields existed solely to suppress an ancestor handler.
- **Fix:** Import drop zones became real `<button>`s. `image-uploader` got the stretched-trigger idiom (its nested Remove control forbids `role="button"` on the wrapper), with Remove raised to `z-20` + an accessible name and the hover veil rebound to `group-hover` so the "Click to replace" affordance still lights up. The `<article>` handler on the menu card was **dead** — the #446 stretched trigger already covers the card and stops propagation — so it and its shield were removed rather than papered over. The dashboard orders row now declines to navigate for clicks inside `[data-row-actions]`.
- **Verification:** `npm run lint` rc=0 with 0 errors and the same 28 pre-existing warnings; jest 100/100 suites incl. `components/ui/__tests__/image-uploader.test.tsx`; `next build` (the only TypeScript type-check) clean.
- **Committed in:** `ca59e2c8`

**4. [Rule 2 - Missing critical] The mobile sheet nav, not named in the plan**
- **Found during:** Task 2
- **Issue:** The plan names two `<nav>` landmarks. `storefront-nav.tsx` has a **third** — the mobile sheet at line ~197 — which is in the accessibility tree at the same time as the header nav whenever the sheet is open. That is the `landmark-unique` condition at the 375px viewport the phase cares about.
- **Fix:** `aria-label="Storefront menu"`, distinct from the other two.
- **Verification:** the "DIFFERENT names" assertion covers all three; arm 2c reds it.
- **Committed in:** `4c9513bb`

**5. [Rule 3 - Blocking] `check-doc-metrics` reds the moment `docs/metrics.json` is regenerated**
- **Found during:** Task 2
- **Issue:** The plan says to run `scripts/docs-freshness.sh --write` for the new test file. Doing so moves the manifest to 2818/955/100 and the **second** gate (`check-doc-metrics.sh`, which reads prose) then fails 10 ways against `README.md`, `CLAUDE.md` and `AGENTS.md`. Leaving either half alone leaves one CI gate red.
- **Fix:** updated the three prose figures alongside the manifest.
- **Verification:** `docs-freshness` rc=0 (2818); `check-doc-metrics` rc=0 (37/37 claims). Both were observed failing first — `docs-freshness` rc=1 before `--write`, `check-doc-metrics` rc=1 before the prose edit.
- **Committed in:** `4c9513bb`
- **⚠ Merge note:** `docs/metrics.json` + those three docs are the known cross-plan conflict surface. Any other wave-1 plan that adds tests will collide here; resolve by re-running `scripts/docs-freshness.sh --write` and re-fixing the prose, never by picking one side's numbers.

---

**Total deviations:** 5 auto-fixed (3 blocking, 2 missing-critical). No Rule 4 (architectural) situations arose.
**Impact on plan:** No scope creep. Deviations 1/2/5 were required to run the plan's own verifications at all; 3 is the plan's explicit "fix, do not disable" instruction applied to what the new layer found; 4 closes the same defect class at the viewport the plan's own two sites do not cover.

## Issues Encountered

- **The plan's own two lint criteria are unfalsifiable as written** (`label-has-associated-control` on a label-less input; `FlatCompat == 0`). Both were run, both recorded with real output in both directions, both replaced with strictly stronger forms rather than silently substituted. Detail in the break-arm section.
- **The plan's contrast-sweep design is RED on a correct tree** if taken literally (`app/` + `components/`, every ramp, both surfaces). Measured 170 non-compliant pairs before deciding; scope narrowed to the declared surfaces with the reason recorded in the test file itself.
- **First draft of the nav extractor fired on its own documentation.** `<nav\b[^>]*>` matched the word `<nav>` inside the comment explaining the `landmark-unique` rule. Fixed by stripping comments first — and then control-armed, because a filter added to kill a false positive is the standard route to a false negative.
- **`git status --short` verified clean after every restore, and each restore verified by content hash**, never by `git diff --stat`.

## Not done / handed on

- **`text-emerald-600` remains on three declared surfaces** — `app/page.tsx:313`, `app/shop/[slug]/checkout/page.tsx` (4 sites), `app/shop/[slug]/orders/[orderNumber]/page.tsx` (2 sites) and `components/marketing/operator-pitch.tsx:157`. They are outside this plan's `files_modified`, are in the ledger with their measured ratios, and belong to the rest of the phase's 15 declared-surface nodes.
- **`components/public/public-header.tsx` has two unlabelled `<nav>`s.** Not a `landmark-unique` violation today (they are never both in the tree, and `PublicHeader` is not rendered on `/shop` routes where `StorefrontNav` is), so they were left alone rather than changed on a hunch. Recorded here so 31-13 can decide.
- **No browser verification.** Every claim here is static — source, lint, jest, build. Nothing was rendered; the axe-node closures are argued from the colour maths and the DOM change, not measured in a browser. `/gsd:verify-work` (or 31-01's axe instrument) is where that lands.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- **31-13 (conformance statement)** has what it asked for: **no D-12 rule exceptions** from this plan (nothing was downgraded), and an enumerated, ratio-annotated ledger of 55 open `(file, utility)` pairs on declared surfaces in `UNASSERTED_SITES`.
- **Later plans that fix a ledgered site must delete its ledger line**, or the "no stale entry" assertion reds. That is the designed forcing function; it is documented in the test file's header.
- **Blocker for nobody.** `lint` rc=0, `jest` 100 suites / 955 tests, `next build` clean, `docs-freshness` + `check-doc-metrics` both rc=0.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
