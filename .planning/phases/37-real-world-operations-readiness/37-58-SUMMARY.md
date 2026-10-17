---
phase: 37-real-world-operations-readiness
plan: 58
subsystem: ui
tags: [tailwind, container-queries, finance, pagination, touch-targets, jest, g-37-6]

requires:
  - phase: 37-real-world-operations-readiness
    provides: "37-12 finance page and its mounted test (finance-scope.test.tsx); 37-54's container-query idiom (pending-invites-table, 60rem); 37-56's G376_BASE"
provides:
  - "finance stat grid and VAT grid as container-query grids (27/55rem and 26/52rem), test ids finance-stat-grid, finance-vat-grid, finance-amount"
  - "every finance figure tabular-nums with an overflow-wrap safety net"
  - "Pagination with container tiers (19/21/27rem), 44 px controls, exported paginationWindow"
  - "VAT helper owner sign-off (2026-10-16) recorded in products/page.tsx and 37-UI-SPEC.md"
  - "evidence/37-58-red-task1.json, evidence/37-58-red-task2.json, evidence/37-58-tw-emits.js (per-file Tailwind emission probe)"
affects: [37-59]

actuals:
  tokens: 9000
  tasks: 2
  commits: 4
plan_head_before: b3ac45fe5c7f6bef182789b079027b706ca8b157
plan_head_after: bfab66dd4a8b8f32299a1c769348b61383a49238

tech-stack:
  added: []
  patterns:
    - "A grid or control row that sits in a card chooses its layout from its OWN width: [container-type:inline-size] on the parent, [@container_(min-width:NNrem)]: variants on the children, thresholds derived from a declared worst-case content and written beside the code"
    - "Per-file Tailwind emission probe (content narrowed to one source file) when a test spells the same class literally"

key-files:
  created:
    - frontend/components/ui/__tests__/pagination.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-58-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-58-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-58-tw-emits.js
  modified:
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx
    - frontend/app/dashboard/products/page.tsx
    - frontend/components/ui/pagination.tsx
    - .planning/phases/37-real-world-operations-readiness/37-UI-SPEC.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "The built-CSS grep the plan named cannot fail once the RED test exists: Tailwind's content globs include __tests__/ under app/ and components/, so the test's literal class is emitted whatever the source does (measured both tasks). Kept the build check as the positive direction and added a per-file emission probe (evidence/37-58-tw-emits.js) as the falsifiable form"
  - "The plan's CSS path frontend/.next/static/css does not exist under Next 16/Turbopack; the stylesheet is .next/static/chunks/*.css (rg rc 2 on the planned path, recorded)"
  - "Thresholds kept at the plan's starting values (27/55, 26/52, 19/21/27rem); 37-59 measures and may raise each to the next whole rem"

patterns-established:
  - "Finance figure: data-testid finance-amount, tabular-nums, [overflow-wrap:anywhere]; size, weight and colour unchanged"

requirements-completed: [RWO-027, RWO-018, RWO-046]

coverage:
  - id: D1
    description: "Finance stat grid chooses columns from its own width (one, two from 27rem, four from 55rem); no viewport-keyed column token"
    requirement: RWO-027
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx#stat grid: columns from its own width (27rem two, 55rem four), no viewport-keyed column token"
        status: pass
      - kind: other
        ref: "node evidence/37-58-tw-emits.js frontend frontend/app/dashboard/finance/page.tsx '(min-width:27rem)' '(min-width:55rem)' (rc 1 RED, rc 0 GREEN)"
        status: pass
    human_judgment: false
  - id: D2
    description: "VAT breakdown grid from its card's width (26/52rem); tile text column min-w-0"
    requirement: RWO-027
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx#VAT grid: columns from its card's width (26rem two, 52rem four); each tile's text column can shrink"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every finance figure is the whole formatSignedPennies text up to the declared bound, tabular, with overflow-wrap"
    requirement: RWO-027
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx#amounts: every stat and VAT figure is the whole formatted text, tabular, with a wrap safety net"
        status: pass
    human_judgment: false
  - id: D4
    description: "Pagination tiers from its own width; every control 44 x 44 px; goods kept"
    requirement: RWO-018
    verification:
      - kind: unit
        ref: "frontend/components/ui/__tests__/pagination.test.tsx (9 tests)"
        status: pass
      - kind: other
        ref: "node evidence/37-58-tw-emits.js frontend frontend/components/ui/pagination.tsx '(min-width:19rem)' '(min-width:21rem)' '(min-width:27rem)' (rc 1 RED, rc 0 GREEN, rc 1 on the assembled-class arm)"
        status: pass
    human_judgment: false
  - id: D5
    description: "VAT helper sign-off recorded in source JSDoc and UI-SPEC"
    requirement: RWO-046
    verification:
      - kind: other
        ref: "rg -uu -c -F 'OWNER SIGN-OFF PENDING' frontend/app/dashboard/products/page.tsx (1 rc 0 RED, rc 1 GREEN)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Live geometry at 390/768/1024/1280 with real and worst-case figures; next page reachable at 390 px"
    verification: []
    human_judgment: true
    rationale: "jsdom has no layout; owned by 37-59 (rebuild all, freshness, Chrome measurements and the arm that forces the old layout back)"

duration: 9min
completed: 2026-10-16
status: complete
---

# Phase 37 Plan 58: Finance figures and Pagination fit their own box (G-37-6 defects 1 and 3, item 5) Summary

**The finance stat and VAT grids now choose their columns from their own width (container queries at 27/55rem and 26/52rem) with tabular, wrap-safe figures, so "£400.40" stays whole inside its card at 768 px. The shared Pagination now tiers its controls by its own width (19/21/27rem), and every control is 44 px. The VAT helper's owner sign-off is recorded.**

## Performance

- **Duration:** about 9 min
- **Started:** 2026-10-16T14:13:17Z
- **Completed:** 2026-10-16T14:22:12Z
- **Tasks:** 2 of 2
- **Files modified:** 10 (4 source/test modified, 1 test created, 2 planning docs modified, 3 evidence files created)

## Accomplishments

- Finance stat grid. The motion wrapper is `[container-type:inline-size]`. The grid is `grid grid-cols-1 gap-4 [@container_(min-width:27rem)]:grid-cols-2 [@container_(min-width:55rem)]:grid-cols-4`. `md:grid-cols-4` is gone. The derivation from the bound "−£999,999.99" is written beside it.
- VAT breakdown. Its CardContent is the container. The grid uses 26/52rem, and each tile's text column is `min-w-0 flex-1`.
- All 8 finance figures (4 stat, 4 VAT tile) carry `data-testid="finance-amount" tabular-nums [overflow-wrap:anywhere]`. Size, weight and colour are unchanged.
- The stale COVERAGE BOUNDARY note now names the mounted test (37-12) and 37-59's live proof.
- Pagination:
  - The root is `[container-type:inline-size] flex flex-wrap … gap-x-4 gap-y-2` and the nav is `min-w-0`.
  - Previous and next always show. First and last show from 19rem. "Page N of M" shows below 21rem. A three-page window shows from 21rem and the five-page window from 27rem.
  - Every control is `h-11` with `w-11` or `min-w-11`, plus `shrink-0`.
  - `paginationWindow(currentPage, totalPages, size)` is exported.
- The VAT helper JSDoc reads "Signed off by the owner on 2026-10-16 (UAT test 6 ruling: accepted as written)." The UI-SPEC copy row and open item 1 status line say the same.

## Task Commits

1. **Task 1 (tracer): finance figures stay whole; VAT helper sign-off.** RED `76672d6d` (test), GREEN `5d776c26` (feat). Tracer gate: `<verify>` re-run end-to-end (2 finance suites, 23 tests; build rc 0; stylesheet rg rc 0), so Task 2 went ahead.
2. **Task 2: Pagination fits any card at 44 px.** RED `be6bd434` (test), GREEN `bfab66dd` (feat).

## TDD: RED / GREEN evidence

| Task | RED record | Verdict | Target | What failed (semantic) |
|------|-----------|---------|--------|------------------------|
| 1 | evidence/37-58-red-task1.json | RED_EVIDENCE_OK | stat grid: columns from its own width (27rem two, 55rem four), no viewport-keyed column token | `viewportTokens(statGrid)` = `["md:grid-cols-4"]`. The VAT grid gave `["sm:grid-cols-2","lg:grid-cols-4"]`. The amounts test found no `finance-amount`. 3 failed, 20 passed, 23 total. The page mounted and the grid was found by structure, so this was not a setup fault. |
| 2 | evidence/37-58-red-task2.json | RED_EVIDENCE_OK | size: every control is at least 44 x 44 px … | "Go to first page" carried `h-8`/`w-8`. The root, tiers, status and windows tests failed on their declarations. The window-edges test failed with `paginationWindow is not a function`: that is the expected missing export, not the semantic RED. The 3 goods tests passed. 6 failed, 3 passed, 9 total. |

GREEN: finance 23 of 23. Pagination plus finance plus orders: 7 suites, 59 tests. The JSON listing shows `components/ui/__tests__/pagination.test.tsx` passed with 9 tests.

## Acceptance criteria, both directions

- **Built-CSS rg.**
  - The plan's path `frontend/.next/static/css` does not exist on Next 16/Turbopack: rc 2 on every needle. The stylesheet is `frontend/.next/static/chunks/*.css`.
  - Build before any 37-58 test existed (14:14Z): `19rem` rc 1, `21rem` rc 1, `60rem` control rc 0, `23rem` rc 0. The finance thresholds were already rc 0 at that point because the Task 1 RED test was in the working tree.
  - After Task 1 GREEN: `55rem`, `27rem`, `26rem` and `52rem` rc 0, `60rem` rc 0. The CSS chunk is byte-identical to the RED build (same content-hash name `2g9nvzwxiewam.css`, 100310 bytes). The test file alone had already put every finance rule into production CSS.
  - On the Task 2 RED commit (source unchanged): `19rem`, `21rem` and `27rem` all rc 0. So a built-CSS grep cannot fail once the RED test exists. See Deviations.
  - Final build after the arms were restored: every needle rc 0 (`evidence: 58-final-build.rg` in the session scratchpad; build rc 0).
- **Per-file emission probe** (`evidence/37-58-tw-emits.js`: the project config with `content` narrowed to one file):
  - finance page RED: 27/55/26/52rem MISSING, rc 1. GREEN: all EMITTED, rc 0.
  - pagination RED: 19/21/27rem MISSING, rc 1. GREEN: 19/21/27rem, `.min-w-11`, `.h-11` and `container-type: inline-size` EMITTED, rc 0.
  - Positive control: `pending-invites-table.tsx` `(min-width:60rem)` EMITTED, rc 0.
  - VOID arm: a missing source file gives rc 2.
  - The test file alone gives `(min-width:55rem)` EMITTED, rc 0 (the confound, measured directly).
  - A first needle spelled `container-type:inline-size` reported MISSING because the CSS reads `container-type: inline-size`. That was an instrument miss, corrected and re-run, and it is recorded here because it shows the probe can fail.
- **VAT JSDoc.** `rg -uu -c -F 'OWNER SIGN-OFF PENDING'`: RED tree `1` rc 0, GREEN `''` rc 1. `Signed off by the owner on 2026-10-16`: RED rc 1, GREEN `1` rc 0.
- **UI-SPEC L8.** `git diff --quiet origin/main -- frontend/app/globals.css frontend/__tests__/contrast-tokens.test.ts` gives rc 0. The fail-direction control (`finance/page.tsx` against main) gives rc 1.
- **Fail arm, Task 1** (md:grid-cols-4 put back on the stat grid): 1 failed, 22 passed. The stat-grid test named `+ "md:grid-cols-4"`. Restored, sha256 `df1bf2d6…` before = after = HEAD blob. Closing clean run: 23 of 23.
- **Fail arm 1, Task 2** (first/last class assembled as `"…[@container_(min-width:19rem)]:" + "inline-flex"`):
  - jest 9 of 9 green, which confirms jsdom cannot see emission.
  - Per-file probe: MISSING, rc 1.
  - Full build of the arm: rc 0, and the built-CSS rg for `19rem` was **rc 0**. The plan expected rc 1; the test file's literal kept the rule in the stylesheet.
  - Restored, sha256 `f7cd61fa…` before = after = HEAD blob, and rebuilt (rc 0, every needle rc 0).
- **Fail arm 2, Task 2** (previous/next reduced to `w-11`, so `h-11` and `shrink-0` dropped): only the size test went red, `"name": "Go to previous page"`, `h8: true`. Restored, sha256 `f7cd61fa…`. Closing clean run: 9 of 9.
- **Full gate.**
  - jest: 253 suites, 3011 tests.
  - `npx tsc --noEmit` rc 0, `npm run lint` rc 0 (0 errors, 29 warnings), `npm run build` rc 0.
  - No lint warning is in a touched file: the grep over the warning file list returned rc 1, while the control `playwright.config` in the same list counted 1.

## Test counts (docs/metrics.json is 37-59's)

- +3 jest blocks in `finance-scope.test.tsx` and +9 in the new `components/ui/__tests__/pagination.test.tsx`, so +12 blocks and +1 file.
- `scripts/docs-freshness.sh` rc 1 (drift, expected). It reports `jest_blocks` 3011 and `jest_files` 253 against the metrics file's 2967 and 250. That drift includes 37-56's additions. Not regenerated here.

## Goods displaced and kept

- **Replaced (stat row).** From 1024 to 1279 px the stat row is two by two instead of four across, because four across gives 164 px cards, under the ~205 px the bound needs. Four across is kept at 1280 and wider, and one column at 390.
- **Replaced (VAT tiles).** One column at 768 and two at 1024, instead of two and four.
- **Replaced (Pagination).**
  - Controls grow from 32 to 44 px on all seven callers.
  - Between 21rem and 27rem the window shows three pages instead of five.
  - Below 21rem "Page N of M" replaces the page numbers.
- **Kept:**
  - Finance: card titles, icons, colours, the transaction count line, the motion entrance, the scope banner, export, reversal and voided rows, the ledger table and its scroll region, and the Admin-access and Choose-a-shop states. All 20 pre-existing finance tests are green.
  - Pagination: "Showing x-y of z", the nav name, every accessible name, aria-current, disabled rules, the five-page window from 27rem, and null for one page. The goods tests passed on the unchanged component and still pass.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Instrument] The built-CSS path in the plan does not exist**
- **Found during:** Task 1 RED build.
- **Issue:** `rg -uu -l -F '(min-width:55rem)' frontend/.next/static/css` returned rc 2 (IO error, no such directory). Next 16/Turbopack writes the stylesheet to `.next/static/chunks/*.css`, so the criterion as written could never reach rc 0.
- **Fix:** every built-CSS probe uses `rg -uu -l -F -g '*.css' NEEDLE frontend/.next/static/chunks`.

**2. [Rule 1 - Instrument] The built-CSS fail direction is unfalsifiable once the RED test exists**
- **Found during:** Task 1 RED build, confirmed on the Task 2 RED commit and in Task 2 fail arm 1.
- **Issue:** Tailwind `content` includes `app/**` and `components/**`, and so includes `__tests__/`. Each RED test spells the new class literally, so the production stylesheet carries it whatever the source does. Measured three ways:
  - finance thresholds rc 0 before GREEN;
  - 19/21/27rem rc 0 on the Task 2 RED commit;
  - the assembled-class arm kept 19rem at rc 0.
- **Fix:** kept the build check as the positive direction (rc 0 after GREEN, build rc 0). Recorded the true RED direction on the build taken before the Task 2 test existed (19rem and 21rem rc 1). Added `evidence/37-58-tw-emits.js`, a per-file Tailwind emission probe that fails on RED (rc 1), on the assembled-class arm (rc 1) and VOIDs on a missing file (rc 2). Tailwind config is unchanged; the option is logged in `deferred-items.md`.
- **Files modified:** `.planning/phases/37-real-world-operations-readiness/evidence/37-58-tw-emits.js` (new), `deferred-items.md`.

---

**Total deviations:** 2 auto-fixed, both instrument corrections. No change to source scope or thresholds.

## Issues Encountered

- `docs-freshness.sh` drift is expected; 37-59 owns `docs/metrics.json`.

## Next Phase Readiness

- 37-59 can measure the max-content of "−£999,999.99" at text-2xl and text-lg in Chrome. It should raise a threshold to the next whole rem only if the measurement needs it.
- 37-59 can measure the Pagination root width on the orders and finance pages at 390/768, and click "Go to next page" at 390 px.
- If 37-59 changes a threshold, both the source literal and the test literal must change. The per-file probe (`evidence/37-58-tw-emits.js`) is the check that the source emits it.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-16*

## Self-Check: PASSED

All 4 created files present; all 4 task commits (76672d6d, 5d776c26, be6bd434, bfab66dd) are ancestors of HEAD; control sha deadbee0 reported missing.
