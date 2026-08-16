---
phase: 31-consumer-safety-and-legal-floor
plan: 15
subsystem: ui
tags: [react, nextjs, kds, allergens, print-css, accessibility, wcag, jest]

requires:
  - phase: 31-10
    provides: "OrderDetailDto.allergenMask/allergenNames/allergenFlags and OrderItemDto.allergenMask/allergenNames — the write-time snapshot, with 'not recorded' distinguishable from 'nothing declared' on the wire"
  - phase: 31-04
    provides: "OrderAllergenAggregator — the advisory reconciliation flags this renders as CHECK: lines"
provides:
  - "OrderAllergenBanner — the order-level KDS banner: solid amber-800, complete declared set, never truncated"
  - "ItemAllergenBadge — the per-item badge: up to 3 names then +N"
  - "A THIRD on-screen and on-print state for 'allergens not recorded', distinct from both 'these allergens' and the silence that means 'none declared'"
  - "The KDS item list as a <ul> at 14px, one row per item, with the '{n} items' summary preserved"
  - "The allergen block on the printed kitchen ticket, carried in a heavy border and uppercase words with no colour dependency"
  - "A print-STYLESHEET test that reads globals.css and asserts the monochrome property directly"
affects: [31-18, kds, kitchen-display, print, qa-council]

tech-stack:
  added: []
  patterns:
    - "Three-state allergen rendering (null / [] / non-empty) applied identically on screen and on paper"
    - "Print-stylesheet assertions by parsing globals.css, because jsdom loads no stylesheet"
    - "DOM-position assertions via compareDocumentPosition + a data-testid container hook, not presence alone"

key-files:
  created:
    - frontend/components/dashboard/kitchen/order-allergen-banner.tsx
    - frontend/components/dashboard/kitchen/item-allergen-badge.tsx
    - frontend/components/dashboard/kitchen/__tests__/order-allergen-banner.test.tsx
    - frontend/app/dashboard/kitchen/__tests__/allergen-surfacing.test.tsx
  modified:
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/components/dashboard/kitchen/kitchen-ticket.tsx
    - frontend/app/globals.css
    - docs/metrics.json

key-decisions:
  - "NOT RECORDED gets its own third treatment rather than rendering nothing. Once 'no banner' MEANS 'the vendor declared none', a pre-V63 ticket rendering the same nothing is not silent — it makes the allergen-free claim on data that does not exist."
  - "The not-recorded strip states the FACT and gives NO instruction. What a kitchen should do with such a ticket is an operating decision, returned to the owner unanswered rather than invented here."
  - "The printed ticket carries the allergen block ABOVE the items, in a 2px border and uppercase words, with every colour value #000 or #fff — a thermal printer renders the screen's amber-800 fill as an indistinct grey box."
  - "Per-item allergens were added to the printed line as well as the block, because the must-have 'staff can tell which item carries which allergen' otherwise dies at the printer."
  - "The advisory flags render as their own CHECK: lines and are never merged into the declared set, on screen or on paper."
  - "The pre-existing colour-only age border was deliberately NOT touched and is NOT claimed as fixed."

patterns-established:
  - "Safety copy is written uppercase in the MARKUP, not only via a `uppercase` utility — a dropped class must not be able to soften a safety statement."
  - "A CSS-parsing test strips comments before locating an at-rule: indexOf matched `@media print` inside the file's own explanatory comment and extracted the wrong block."

requirements-completed: [LGL-03]

duration: 71 min
completed: 2026-08-16
---

# Phase 31 Plan 15: KDS Allergen Surfacing Summary

**An unmissable order-level allergen banner in the KDS card header plus a per-item badge on a restructured `<ul>` item list, carried onto the printed ticket in border-and-uppercase monochrome — and a third "not recorded" state so a pre-V63 ticket can never pass as allergen-free.**

## Performance

- **Duration:** 71 min
- **Started:** 2026-08-16T16:34Z
- **Completed:** 2026-08-16T17:45Z
- **Tasks:** 2 (both TDD)
- **Files modified:** 8 (4 created, 4 modified)

## Accomplishments

- `OrderAllergenBanner` renders the **complete** declared set inside `CardHeader`, under the order number and above the customer name, on a solid `bg-amber-800` fill with an `AlertTriangle` and the literal word `ALLERGENS` at the contracted `text-xl font-semibold uppercase tracking-[0.08em]`. It never truncates and never animates.
- `ItemAllergenBadge` shows up to 3 names then `+N` on each item row, with a comment stating that the truncation is safe **only** because the banner above is not truncated — and that the dependency must not be inverted.
- The KDS item run moved from an inline comma-joined 12px `<span>` sequence to a `<ul>` at 14px, one `<li>` per item, quantity first. The `"{n} items"` summary above it is preserved.
- The printed ticket gained the allergen block above the items, plus per-line allergens, with print CSS carrying the warning in a 2px border and uppercase words only.
- **Three states are distinguished** on screen and on paper (see below), which is the part of this work most at risk of being "simplified" later.
- 35 new tests (15 + 20); full suite **1043 passed / 108 suites / 0 failures**, build compiles, lint 0 errors.

## The three states, and how each is distinguished

This is the answer to the question the plan's `<behavior>` posed but did not fully resolve ("renders no banner **and does not claim there are none**"). Rendering nothing for *not recorded* satisfies the first half and violates the second, because the design deliberately makes **absence itself a claim**.

| DTO state | Meaning | On screen | On the printed ticket |
|---|---|---|---|
| `allergenNames` non-empty | The vendor's declared set | Solid `bg-amber-800` banner, `AlertTriangle` + literal `ALLERGENS` at 20px/600 uppercase, the complete list at 16px/600, plus any `CHECK:` lines | `.kds-ticket__allergens` — 2px **solid** black border, uppercase, `ALLERGENS` + the list + `CHECK:` lines |
| `allergenNames === []` (mask 0) | The vendor declared none of the 14 | **Nothing at all.** Absence is the signal; a "no allergens" banner on every ticket trains staff to ignore the banner | **Nothing at all**, same reason |
| `allergenNames == null` | NOT RECORDED — the order or one of its lines predates V63 | Neutral **slate** strip: `border-2 border-slate-700 bg-white`, `HelpCircle` + literal `ALLERGENS NOT RECORDED` at the same 20px treatment, then "No allergen data was recorded for this order." | `.kds-ticket__allergens--unrecorded` — the same block with a **dashed** border and the words `ALLERGENS NOT RECORDED` |

Three properties make this safe rather than merely different:

1. **Not amber.** The platform is not claiming this order *has* allergens either. Amber is reserved for a declared set.
2. **Distinguished by WORDS, never by the colour or the border style alone** — the dashed border on paper is an aid, the uppercase words are the signal.
3. **No instruction is printed.** See the owner question below.

`allergenNames ?? []` and `allergenMask ?? 0` appear nowhere. The `null` branch is tested by `== null` so an absent field on a cached pre-31-10 response takes the same path.

**Banner fatigue was weighed and does not bite:** the KDS shows only ACTIVE tickets (CONFIRMED/PREPARING/READY), which are recent by definition, and every order written after V63 carries the snapshot. The not-recorded strip is a transient migration state on a handful of in-flight tickets, not a fixture on every card.

## The DOM-position assertion — what a future card refactor must not break

`page.tsx` carries `data-testid="kds-card-header"` on the `CardHeader`. The banner's contract is positional, and the assertion is:

```tsx
const header = title.closest("[data-testid='kds-card-header']")
const banner = within(header).getByTestId("kds-allergen-banner")          // contained in the header
title.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING  // after the order number
header.contains(customer) === false                                       // customer is outside the header
banner.compareDocumentPosition(customer) & Node.DOCUMENT_POSITION_FOLLOWING // ...and after the banner
```

Break arm (c) proved this does work a presence assertion would not: moving the banner below the customer name made the position test fail **while "renders the complete declared set on the card" still passed**.

## Task Commits

1. **Task 1: OrderAllergenBanner and ItemAllergenBadge** — `4b381b49` (test, RED) → `20ec88bd` (feat, GREEN)
2. **Task 2: Mount on the card, restructure the item list, print it** — `cf9e4f39` (test, RED) → `6c14b5e9` (feat, GREEN)
3. **Generated metrics** — `863db33f` (chore)

No REFACTOR commits: neither GREEN pass left anything to clean up.

## Files Created/Modified

- `frontend/components/dashboard/kitchen/order-allergen-banner.tsx` — the three-state order-level banner
- `frontend/components/dashboard/kitchen/item-allergen-badge.tsx` — the per-item badge, 3 names then `+N`
- `frontend/components/dashboard/kitchen/__tests__/order-allergen-banner.test.tsx` — 15 tests
- `frontend/app/dashboard/kitchen/__tests__/allergen-surfacing.test.tsx` — 20 tests, incl. the print-stylesheet parser
- `frontend/app/dashboard/kitchen/page.tsx` — banner mounted in `CardHeader`; item run restructured to a `<ul>`; `data-testid="kds-card-header"` added
- `frontend/components/dashboard/kitchen/kitchen-ticket.tsx` — allergen block above the items, per-line allergens
- `frontend/app/globals.css` — `.kds-ticket__allergens{,--unrecorded,-check}` and `.kds-ticket__item-allergens` print rules; the allergen block joins the `break-inside: avoid` list
- `docs/metrics.json` — regenerated (`jest_blocks` 1008 → 1043, `jest_files` 106 → 108)

## Evidence — every criterion, both directions

Method: **commit → break → restore → verify by `git hash-object` → closing clean arm**. Every restore below was verified by content hash against the committed blob, never `git diff --stat`.

Clean hashes: `order-allergen-banner.tsx` `848daadd`, `item-allergen-badge.tsx` `02dead82`, `page.tsx` `3d58a429`, `kitchen-ticket.tsx` `6e29f5a6`, `globals.css` `dd3a2b51`.

### Baseline (measured in this worktree, at base `0d1834c2`)

```
Test Suites: 106 passed, 106 total
Tests:       1008 passed, 1008 total
```

Matches the brief exactly. `npm run build` rc=0, `npm run lint` 0 errors / 28 warnings.

### Task 1 arms

| # | Break | Clean output | Break output |
|---|---|---|---|
| RED | (components absent) | — | `Cannot find module '../order-allergen-banner'` — suite failed to run |
| a | Truncate the banner at 3 like the badge | `Tests: 15 passed` | `✕ lists EVERY declared allergen` → `Tests: 1 failed, 14 passed` |
| b | Remove the icon and the word `ALLERGENS`, keep the amber fill | `Tests: 15 passed` | `✕ says the word ALLERGENS…` + `✕ carries an icon ALONGSIDE the words` → `2 failed, 13 passed`. **`✓ wears the contracted treatment: solid amber-800 fill` still passed** — the colour-only regression is invisible to a colour assertion, which is why the text assertions exist |
| c | Render a "no allergens" banner instead of nothing | `Tests: 15 passed` | `✕ renders NOTHING AT ALL…` + `✕ tells NOT RECORDED apart from NOTHING DECLARED` → `2 failed, 13 passed` |
| d | Add `animate-pulse` | `Tests: 15 passed` | `✕ does not animate, flash or pulse` / `Received string: "animate-pulse"` → `1 failed, 14 passed`. Source grep `animate-` went `0` → `1` |
| e | Label to `text-lg font-bold` | greps `font-bold`→0, `text-lg`→0 | greps `font-bold`→**1**, `text-lg`→**1**; `✕ sizes the ALLERGENS label…` |
| **closing** | — | `Test Suites: 1 passed / Tests: 15 passed` | — |

**Vacuity disclosed.** The plan's Task-1 criterion "the banner source contains NO `text-lg`, no `font-bold`" is a `grep -c == 0` that was **already 0 before any code existed** — a pass alone proves nothing. It is a *regression guard*, not evidence of this change. Arm (e) exists solely to show it is capable of firing, and it does. Recorded rather than reported as a satisfied check.

### Task 2 arms

| # | Break | Clean output | Break output |
|---|---|---|---|
| RED | (nothing mounted, no print block, no print CSS) | — | `Tests: 16 failed, 4 passed, 20 total`. The 4 that passed are preservation assertions that *should* hold pre-change; their fail direction is arms (b), (d), (e), (f) |
| a | **Remove the print block from `kitchen-ticket.tsx`** | `Tests: 50 passed` (both KDS suites) | `● carries the allergen block…`, `● prints ALLERGENS NOT RECORDED…`, `● reaches the sheet the printer actually receives` → `3 failed, 123 passed`. **Every screen-side test still passed** — exactly the blind spot this arm exists for |
| a2 | Swap the print border for `background: #92400e` (the screen amber) | `Tests: 20 passed` | `● carries the warning in a border and in uppercase` (`Expected pattern: /border:\s*\d/`) + `● uses NO colour but black and white` (`Expected value: "#92400e"`) → `2 failed, 18 passed`. The CSS half is falsifiable **independently of the markup** |
| b | Delete the `{itemSummary}` line | `Tests: 50 passed` | `● KEEPS the '{n} items' summary line` + `● shows NO banner … card is otherwise intact` → `2 failed, 48 passed` |
| c | Move the banner below the customer name | `Tests: 50 passed` | `● puts the banner inside CardHeader, AFTER the order number and BEFORE the customer name` / `Unable to find an element by: [data-testid="kds-allergen-banner"]` → `1 failed, 49 passed`, **while the presence-only test still passed** |
| d | Shrink the print control to `h-10 w-10` | `Tests: 50 passed` | `● keeps both 44px controls…` / `Expected length: 2, Received length: 1` → `1 failed, 49 passed` |
| e | Print the allergen block for a declared-none order | `Tests: 20 passed` | `● prints nothing at all about allergens when the vendor declared none` → `1 failed, 19 passed` |
| f | Drop the printed items list | `Tests: 29 passed` (incl. the pre-existing ticket suite) | `● KitchenTicket › lists every line item with its quantity first`, `● still prints everything it printed before`, `● names the allergen on the ITEM line too` → `3 failed, 26 passed` |
| g | Remove `.kds-ticket__allergens` from the `break-inside: avoid` list | `Tests: 20 passed` | `● never splits the allergen block across two pages` → `1 failed, 19 passed` |
| **closing** | — | `Test Suites: 108 passed / Tests: 1043 passed`; `✓ Compiled successfully`; lint `0 errors, 28 warnings` | — |

### Evidence that the print sheet actually carries the warning monochrome

A screenshot of the screen is not evidence about `@media print`, and jsdom loads no stylesheet, so the print claim is asserted in two independently-falsifiable halves:

1. **Markup** — `KitchenTicket` emits `.kds-ticket__allergens`, proven end-to-end through the real print path (click *Print ticket* → the block is found **inside `#kds-print-root`**). Fail direction: arm (a).
2. **Stylesheet** — `globals.css` is read as text, comments stripped, the `@media print` block located by brace counting and selected **by identity** (the one containing `#kds-print-root`, failing loudly if that is not exactly one). Inside it:
   - `.kds-ticket__allergens` declares `border: 2px solid #000` and `text-transform: uppercase`;
   - every hex in `.kds-ticket__allergens` and `.kds-ticket__item-allergens` is `#000`/`#fff` — asserted, not asserted-about;
   - no `transition`/`animation`;
   - the block is in the `break-inside: avoid` selector list.
   Fail directions: arms (a2) and (g).

The `.kds-ticket__allergens--unrecorded` variant changes only `border-style: dashed`, so the not-recorded state is distinguished on paper by **words first**, border second.

## Decisions Made

1. **A third rendering state for "not recorded"** — see the table above. This is a Rule-2 addition beyond the plan's two enumerated banner states and is the most consequential decision in this plan.
2. **No operating instruction anywhere in the not-recorded copy** — the components state the fact and stop.
3. **Per-item allergens on the printed line**, not just the order-level block.
4. **The declared-empty-but-flagged case renders the banner** with "None declared" plus the `CHECK:` line, rather than being swallowed by the empty-set branch. Reachable and exactly what D-03 exists to raise.
5. **Icons are `aria-hidden="true"`** — the words carry the meaning; announcing "triangle" adds nothing.
6. **Safety labels are uppercase in the markup**, not only via the `uppercase` utility.
7. **The age border was not touched.** It is AGE, not allergens; it remains a recorded out-of-scope observation for a later phase and is **not** claimed as remediated. `/dashboard/kitchen` is outside D-09's axe surface list and was not added to it.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — Missing Critical] "Not recorded" rendered nothing, which is itself the allergen-free claim**
- **Found during:** Task 1
- **Issue:** The plan's `<behavior>` says a not-recorded order "renders no banner **and does not claim there are none**". Those two clauses conflict under this design: the same plan makes rendering nothing the *signal* for "the vendor declared none", so a not-recorded ticket rendering nothing makes precisely the claim the clause forbids — on data that does not exist. Two states cannot encode three.
- **Fix:** A third, deliberately non-amber treatment on screen (`kds-allergen-unrecorded`, slate outline, `HelpCircle`, literal `ALLERGENS NOT RECORDED`) and on paper (`.kds-ticket__allergens--unrecorded`, dashed border, same words). No instruction, no colour-only distinction.
- **Files:** `order-allergen-banner.tsx`, `kitchen-ticket.tsx`, `globals.css`
- **Verification:** "tells NOT RECORDED apart from NOTHING DECLARED — three states, not two" asserts **both** directions in one test, so a collapse either way fails. Break arm (c) confirmed it fires.
- **Committed in:** `20ec88bd`, `6c14b5e9`

**2. [Rule 2 — Missing Critical] Per-item allergens on the printed ticket**
- **Found during:** Task 2
- **Issue:** The plan's must-have "staff can tell which item carries which allergen" was satisfied on screen by the badge and lost entirely at the printer — the printed ticket would have carried only the order-level aggregate, which is the exact "tells kitchen staff nothing actionable" the objective rejects.
- **Fix:** `.kds-ticket__item-allergens` under each dish name, inside the name column so the quantity keeps its 10mm gutter; uppercase, bold, monochrome.
- **Files:** `kitchen-ticket.tsx`, `globals.css`
- **Verification:** "names the allergen on the ITEM line too" asserts presence on the line that has it and **absence** on the line that does not; break arm (f) fired it.
- **Committed in:** `6c14b5e9`

**3. [Rule 1 — Bug, in my own instrument] The CSS parser matched `@media print` inside a comment**
- **Found during:** Task 2
- **Issue:** The first print-stylesheet test located the at-rule with `css.indexOf("@media print")` and matched the phrase inside `globals.css`'s own explanatory comment at line 156 — 25 lines above the real at-rule. Brace counting from there extracted `display: none;`, and all five stylesheet tests failed for a reason with nothing to do with the CSS. Same shape as a doc rule firing on its own definition.
- **Fix:** Strip `/* … */` first; enumerate **all** `@media print` blocks; select the one containing `#kds-print-root` and throw unless exactly one matches.
- **Files:** `app/dashboard/kitchen/__tests__/allergen-surfacing.test.tsx`
- **Verification:** The tests then passed on the real tree and still fail on arms (a2) and (g) — so the fix did not make them vacuous.
- **Committed in:** `6c14b5e9`

**4. [Rule 3 — Blocking] Worktree branched from `main`, not the phase base**
- **Found during:** setup
- **Issue:** `HEAD` was `bb2ae65d` (`main`), so `.planning/phases/31-…/` did not exist and wave-1/2 work was absent (known issue #2015).
- **Fix:** Asserted HEAD is on the per-agent branch `worktree-agent-a2a4fa633934dc4ee` **first**, then `git reset --hard 0d1834c2` on a clean tree.
- **Verification:** `git rev-parse HEAD` → `0d1834c2`; baseline suite then reproduced the brief's numbers exactly (106/1008).
- **Committed in:** n/a (no content change)

---

**Total deviations:** 4 auto-fixed (2 missing-critical, 1 bug, 1 blocking).
**Impact on plan:** No scope creep — every addition is inside `files_modified`. Deviations 1 and 2 are the difference between a KDS that states what it knows and one that quietly overstates it.

## Issues Encountered

- The plan's forbidden-token greps (`text-lg`, `font-bold`, `animate-` == 0) are regression guards that were already satisfied before any code existed. Recorded as vacuous-on-a-clean-tree and proven capable of firing by arms (d) and (e) rather than reported as passing checks.
- 4 of the 20 Task-2 tests passed at RED. That is correct — they are preservation assertions about goods that already existed — and their fail directions were run separately as arms (b), (d), (e) and (f) rather than being left unfalsified.

## Owner question — returned unanswered

**A ticket whose allergen data is "not recorded" now says so on screen and on the printed ticket. It does not say what to do about it, and that is deliberate.**

Two decisions belong to the owner and were not invented here:

1. **May a "not recorded" order be prepared and served at all**, or must it be stopped? The platform can state that it cannot state the allergen set; it cannot decide the food-safety policy that follows.
2. **What is the kitchen instructed to do** when the platform cannot state the allergen set — check the product record, ring the customer, escalate to the vendor? Any of these is an operating procedure, and printing one would make the platform the author of a food-safety instruction it cannot stand behind.

The copy is therefore purely factual: `ALLERGENS NOT RECORDED` / "No allergen data was recorded for this order." Whatever the owner decides, the wording change is confined to two literals — one in `order-allergen-banner.tsx`, one in `kitchen-ticket.tsx`.

A related, narrower question if the answer to (1) is "no": nothing in this plan *blocks* the bump action on such a ticket, and adding a block would be a workflow change well outside a rendering plan.

## Merge-gate items for the orchestrator

- **`docs/metrics.json` regenerated** to `jest_blocks: 1043`, `jest_files: 108`, `total_logical_invocations: 2986` — **worktree-local and wrong once siblings merge.** Re-run `scripts/docs-freshness.sh --write` on the merged tree, then reconcile the prose counts in `README.md`, `CLAUDE.md` and `AGENTS.md` (`scripts/check-doc-metrics.sh`). No prose file was touched here, by instruction.
- **`frontend/app/globals.css`** — the edit is confined to the `@media print` block (one selector added to an existing `break-inside` list, one new rule group before `.kds-ticket__notes`). No sibling wave-3 plan touches this file.
- **`frontend/types/api.ts` was NOT edited**, as instructed.
- **`frontend/app/dashboard/kitchen/__tests__/page.test.tsx`** was **not** modified; its 30 tests still pass unchanged.
- Not verified here and out of scope for a worktree: Playwright/E2E, and a real-browser `emulateMedia({ media: "print" })` PDF check of the ticket. The stylesheet assertions are the strongest evidence obtainable without a browser; a browser-level print check would be a strictly stronger successor and belongs with the phase's E2E pass.

## Next Phase Readiness

- S4 is complete and independent of the other wave-3 surfaces. 31-14 (checkout, S3) renders the same server snapshot from the same DTO, so the two surfaces agree by construction rather than by coincidence.
- 31-18 must **not** add `/dashboard/kitchen` to its declared axe surface list; the a11y properties here are build-time quality, not gate scope.

## Self-Check: PASSED

All five claimed files exist on disk; all six commits (`4b381b49`, `20ec88bd`, `cf9e4f39`, `6c14b5e9`, `863db33f`, `33d7b534`) resolve in `git log 0d1834c2..HEAD`; working tree clean. `frontend/types/api.ts` untouched, confirmed by its absence from the diff.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
