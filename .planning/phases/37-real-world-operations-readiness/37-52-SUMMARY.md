---
phase: 37-real-world-operations-readiness
plan: 52
subsystem: ui
tags: [accessibility, accname, aria-labelledby, cdp, getFullAXTree, focus-management, live-region, cookie-notice, portal, scroll-padding, wcag-2.4.11, large-text, playwright, d-05, uxt-080, uxt-082, uxt-083, uxt-085]
status: complete

requires:
  - phase: 37-50
    provides: "the shop-card and basket 'Collection only' copy this plan's cards keep"
  - phase: 37-51
    provides: "the tracker half of UXT-080 and the a11y-tracking.spec.ts two-block shape (live + @stack-free)"
provides:
  - "/shop and landing shop-card links named by the shop (aria-labelledby = the card's name element), open state and distance as aria-describedby"
  - "Dish card: after Add focus moves to '+' (named 'Add one more {dish}'), after the last Remove focus returns to Add"
  - "One basket live region in the storefront header: '{Dish} added. {n} item(s) in basket.' / '{Dish} removed. Basket is empty.'; the basket link is no longer a live region and says its count once"
  - "lib/basket-announcement.ts: the one-dish diff the CartProvider broadcasts on jtoye:cart-updated, and the sentence; hydration and multi-line writes name no dish"
  - "BottomNoticeShell portals the cookie notice (and the dormant consent banner) to a host prepended to <body>, and sets scroll-padding-bottom to the band it covers while it shows"
  - "Basket page and drawer item names and the storefront <h1> wrap (break-words) instead of truncating"
  - "e2e/a11y-storefront.spec.ts: live block (run in 37-53) plus a stack-free instrument block"
affects: [37-53, phase-37-uat, public-a11y gate]

actuals:
  tokens: 25800
  tasks: 3
  commits: 9
plan_head_before: d9661e861655327eac4e3f503ee8a904c47d4ba9
plan_head_after: 5d7c3549a8252557ffaec1333d1eb71ad9554881

tech-stack:
  added: []
  patterns:
    - "Name a link that wraps an <article> with aria-labelledby on its heading: Chrome computes an EMPTY name for <a><article>…</a> (measured on the pre-plan build: name '' for every /shop card)"
    - "A basket announcement derived at the persistence point: the provider diffs the last persisted basket against the new one and broadcasts the single changed dish; the first persist (hydration) has no previous state, so a page load says nothing"
    - "Focus after a control swaps itself out: the pressed control records where focus goes next in a ref, and an effect on the quantity moves it once the replacement has rendered"
    - "A bottom notice first in DOM order without moving it on screen: portal into a host prepended to <body>; fixed positioning keeps visual position and zero CLS"
    - "scroll-padding-bottom = innerHeight - notice.top, re-measured by ResizeObserver, window resize, animation end and a MutationObserver on <html>'s style (write only on change, so the observer cannot loop on its own write)"
    - "Browser RED without the shared stack: the unmodified spec against two `next build`s (pre-plan via git archive + hard-linked node_modules, and this branch) served by `next start` over a stub core"

key-files:
  created:
    - frontend/lib/basket-announcement.ts
    - frontend/lib/__tests__/basket-announcement.test.ts
    - frontend/__tests__/shop/large-text-wrap.test.tsx
    - frontend/e2e/a11y-storefront.spec.ts
    - .planning/phases/37-real-world-operations-readiness/evidence/37-52-red-task1-cards.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-52-red-task1-focus.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-52-red-task2-notice.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-52-red-task2-wrap.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-52-runtime-proof/ (run.sh, stub-core.cjs, RED/GREEN and goods outputs)
  modified:
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/components/marketing/shop-card.tsx
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/components/storefront/storefront-nav.tsx
    - frontend/components/storefront/cart-provider.tsx
    - frontend/components/storefront/cart-drawer.tsx
    - frontend/app/shop/[slug]/cart/page.tsx
    - frontend/components/public/bottom-notice-shell.tsx
    - frontend/components/public/__tests__/cookie-notice.test.tsx
    - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
    - frontend/components/marketing/__tests__/shop-card.test.tsx
    - frontend/__tests__/shop/product-card-quantity-a11y.test.tsx
    - frontend/__tests__/shop/stepper-limit.test.tsx
    - frontend/e2e/public-a11y.spec.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "The basket announcement is derived in the CartProvider (diff of the last persisted basket), not dispatched by the dish card, so the dish modal, the drawer and the basket page are announced the same way and the old link-as-live-region's coverage of those surfaces is kept, now with the dish named"
  - "The basket link keeps its count as its NAME ('2 items in basket', badge aria-hidden) and stops being a live region; the new region sits AFTER it in DOM order (the #272 pluralisation checks read the first sr-only 'in basket' span) and carries explicit aria-live so Radix hideOthers still leaves the header announceable behind a dialog"
  - "Cookie notice order is GOV.UK's: notice, then skip link, then page. The public-a11y skip-link-first gate (31.1-28) is narrowed to 'the first link OUTSIDE the notice host is the skip link, and a host holds nothing but the notice' — the plan's DOM-order truth and the old gate could not both hold"
  - "UXT-080's basket half covers the drawer as well as the basket page (the same basket; the drawer is what a mouse opens); checkout's line items and the menu card titles were not touched (P2-GRA-24 says they already fit)"
  - "No rating is described on the cards: PublicShop has no rating field, and the landing card's header records why none may be invented"
  - "'+' falls back to the remove control as the focus target when '+' is disabled at a one-per-order line limit (focusing a disabled button drops focus)"

patterns-established:
  - "Soft assertions in a live a11y journey where one defect would hide the next, so a RED run reports every defect (focus AND announcement, h1 AND basket name, tab order AND overlap)"

requirements-completed: [RWO-082, RWO-083, RWO-085, RWO-080]

coverage:
  - id: D1
    description: "Every /shop card link (and the landing row's card) is named by its shop in Chrome's accessibility tree; open state and distance are its description"
    requirement: RWO-082
    verification:
      - kind: unit
        ref: "frontend/app/shop/__tests__/shop-discovery-client.test.tsx#each card link is named by its shop (UXT-082) (4) + components/marketing/__tests__/shop-card.test.tsx (3); RED_EVIDENCE_OK; break arms H, H2, I red"
        status: pass
      - kind: e2e
        ref: "e2e/a11y-storefront.spec.ts F2 via CDP Accessibility.getFullAXTree: pre-plan build name '' -> this build name = shop (evidence/37-52-runtime-proof)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Add moves focus to 'Add one more {dish}', the last Remove returns it to Add, and one region says '{Dish} added. {n} item(s) in basket.' with the count once; a page load says nothing"
    requirement: RWO-083
    verification:
      - kind: unit
        ref: "frontend/__tests__/shop/product-card-quantity-a11y.test.tsx (UXT-083, 5) + lib/__tests__/basket-announcement.test.ts (9); RED_EVIDENCE_OK; break arms A..G red, incl. the doubled '11 item in basket'"
        status: pass
      - kind: e2e
        ref: "e2e/a11y-storefront.spec.ts F3: pre-plan build focus BODY and recorded ['11 item in basket'] -> this build passes with the instrument arm"
        status: pass
    human_judgment: false
  - id: D3
    description: "The cookie notice is first in DOM and tab order and, on an iPhone 13 profile, covers none of the first 30 focused elements"
    requirement: RWO-085
    verification:
      - kind: unit
        ref: "frontend/components/public/__tests__/cookie-notice.test.tsx (UXT-085, 4; 26/26 suite); RED_EVIDENCE_OK; break arms J-inline, J2, K, L, L2 red"
        status: pass
      - kind: e2e
        ref: "e2e/a11y-storefront.spec.ts F5: pre-plan build 'Got it' not in 30 stops and 10 obscured stops -> this build Got it at stop 1, 0 obscured, walk scrolled"
        status: pass
    human_judgment: false
  - id: D4
    description: "At 360px with 130% text the storefront <h1> and the basket item names (page and drawer) wrap with no ellipsis and no sideways scroll"
    requirement: RWO-080
    verification:
      - kind: unit
        ref: "frontend/__tests__/shop/large-text-wrap.test.tsx (3); RED_EVIDENCE_OK; break arms M, N, O, O2 red"
        status: pass
      - kind: e2e
        ref: "e2e/a11y-storefront.spec.ts F6: pre-plan build h1 and basket name ellipsis/nowrap/overflow -> this build []"
        status: pass
    human_judgment: false
  - id: D5
    description: "The live a11y-storefront journey on the rebuilt shared stack (real seeded data, 37-53)"
    verification:
      - kind: e2e
        ref: "npx playwright test e2e/a11y-storefront.spec.ts --list (12 listed); --grep @stack-free 8/8; full spec 8/8 on this branch's build over a stub core"
        status: pass
    human_judgment: true
    rationale: "By plan the live RED (old images) and GREEN (rebuilt images) runs on the shared stack belong to 37-53; this code-only plan ran the same unmodified spec against its own builds over a stub core instead"

duration: 33min
completed: 2026-10-14
---

# Phase 37 Plan 52: Storefront accessibility (names, focus, notice, large text) Summary

**Shop cards are now named by their shop in Chrome's accessibility tree. Pressing Add keeps the keyboard on the dish, and the basket says "Mango Lassi added. 1 item in basket." once. The cookie notice is the first thing a keyboard user reaches, and on a phone it no longer covers the focused element. Basket names and the shop name wrap at 360px with 130% text.**

## Performance

- **Duration:** about 33 min
- **Started:** 2026-10-14T19:39:40Z
- **Completed:** 2026-10-14T20:12:44Z
- **Tasks:** 3 of 3
- **Files:** 21 source, test and doc files, plus evidence

## Accomplishments

- **F2 (UXT-082).** Each card link on `/shop` and on the landing row now gets its name from the card's own name element through `aria-labelledby`. The open badge and the distance pill are its `aria-describedby`, and only when they are shown. The pre-plan build was measured through CDP: Chrome named every card link `""`.
- **F3 (UXT-083), focus.** Pressing "Add" on a dish card moves focus to the stepper's "+", which is now named "Add one more {dish}". Removing the last unit moves focus back to "Add". If "+" is disabled at a one-per-order limit, focus goes to the remove control instead. Only the card's own controls set the focus target, so the dish modal keeps its own focus.
- **F3, the announcement.** The basket link was the live region, and it held the badge "1" and the sr-only "1 item in basket" together, so a screen reader heard the count twice. On the old build the recorder captured "11 item in basket". The link now says its count once, in its name. One `role=status aria-live=polite` region after it says "{Dish} added. {n} item(s) in basket." or "{Dish} removed. Basket is empty." The CartProvider compares the new basket with the last one it saved and broadcasts the one dish that changed. A page load, a sign-out clear and the server's basket rewrite change no single dish, so they announce nothing.
- **F5 (UXT-085).** `BottomNoticeShell` renders the notice (and the dormant consent banner) through a portal into a host placed first in `<body>`. "Got it" is now the first tab stop; on the old build it was not within the first 30. The notice looks and sits exactly where it did. While it shows, `scroll-padding-bottom` equals the height of the band it covers, and the earlier value comes back when it goes. On an iPhone 13 profile none of the first 30 tab stops is covered. On the old build 10 of them were.
- **F6 (UXT-080), basket and header half.** `truncate` is replaced by `break-words` on the basket page item names, the drawer item names and the storefront `<h1>`.
- **The spec.** `e2e/a11y-storefront.spec.ts` has two blocks. The live block (F2, F3, F6 and an iPhone 13 F5) is customer-side only: it places no order and needs no vendor password. Each test has an instrument arm on the real page. The `@stack-free` block runs the four probes against fixtures, each with a positive control and a broken input.

## Task Commits

1. **Task 1 (tracer): named shop cards; Add/Remove focus and the announcement**
   - RED `fd11b187` (test)
   - GREEN `cceeee99` (feat)
   - Tracer gate: end-of-phase mode with an automated-only verify, so the verify was re-run before expanding: `npm --prefix frontend test -- app/shop/__tests__ __tests__/shop components/marketing` passed 28 suites, 344/344, rc 0.
2. **Task 2: the cookie notice never hides focus; large text wraps**
   - RED `0ca1cacd` (test)
   - GREEN `637e55c4` (feat)
3. **Task 3: browser instruments**
   - `d1faee6e` (test): the spec
   - `b52d4e65` (test): soft assertions so a RED run reports every defect, plus the runtime proof
4. **The skip-link gate:** `f3e5b246` (test), deviation 2
5. **Metrics:** `17eb3e81` (docs), 6049 → 6085
6. **Deferred item and layout goods evidence:** `5d7c3549` (docs)

## RED / GREEN evidence, both directions

### Task 1

**RED** (`evidence/37-52-red-task1-cards.json`, `-focus.json`, both `RED_EVIDENCE_OK / target_test_failed`): 70 tests ran, 12 failed and 58 passed.

- **Cards (7 tests).** Every one failed on the missing wiring. The heading had no id, and the link's computed name was the concatenated card text. The marketing card read "Peckham Jollof Co. logo Open 0.7 miles away Peckham Jollof Co. Jollof · West African · Halal £2.99 delivery · min £15.00".
- **Focus (5 tests).** The target failed on focus: `activeElement` was `<body>`. Four sibling tests failed at "Add one more Mango Lassi" not found, which is the naming half of F3. That means the doubled-count assertion was not reached in RED. Break arm A shows it red separately.

**GREEN:** the plan verify passed 28 suites, 344/344. The closing clean run over the four touched suites passed 79/79.

**Break arms**, each run against the committed tree. Every restore was checked by `git hash-object`.

| Arm | Break | Result |
|-----|-------|--------|
| A | `aria-live` back on the basket link | RED 4. Recorded `["11 item in basket", "Mango Lassi added. 1 item in basket."]`: the old doubled count |
| A2 | Badge not `aria-hidden` | RED 1: the link's name becomes "2 2 items…" |
| B | No focus target after Add | RED 2 |
| C | No focus target after the last Remove | RED 1 |
| D | Old "+" label | RED 5 |
| E | Provider broadcasts no edit | RED 3 |
| E2 | Provider treats hydration as an edit | RED 1: the page-load control |
| F | Multi-line write names its first dish | RED 1 |
| G | Old "items" plural | RED 3 |
| H | Directory card without `aria-labelledby` | RED 4 |
| H2 | Dangling `aria-describedby` | RED 1 |
| I | Marketing card without `aria-labelledby` | RED 3 |

### Task 2

**RED** (`evidence/37-52-red-task2-notice.json`, `-wrap.json`, both `RED_EVIDENCE_OK`): 29 tests ran, 6 failed and 23 passed.

- **DOM order.** Failing as planned: `compareDocumentPosition(main) & FOLLOWING` was 0, so the notice came after `main`.
- **Scroll padding.** Expected `132px`, received `""`. Its control (nothing set before the notice exists) passed first.
- **Consent banner.** Failed the same way.
- **Large text (3 tests).** Each heading was found by role and full name, then failed on its `truncate` class.
- **Storage-key test.** This one passed in RED, as it should: it is a kept good, not part of this change.

**GREEN:**
- Plan verify: `components/public app/shop` and `components/public app/shop app/legal lib consent hooks bottom-chrome client-storage-keys components/legal` passed 46 suites, 515/515.
- `npm run build` passed with rc 0. The output was confirmed by content: `data-bottom-notice-host` and "Add one more" are in `.next/static`.

**Break arms**, each restore checked by hash:

| Arm | Break | Result |
|-----|-------|--------|
| J-inline | Render without the portal | RED 3: DOM order ×2 and the axe host lookup |
| J2 | Host appended instead of prepended | RED 2 |
| K | No padding written | RED 2 |
| L | Padding not restored on dismissal | RED 1 |
| L2 | Height-only measure that ignores the bottom offset | RED 2 |
| M, N, O | `truncate` back on the basket page, the drawer and the h1 | RED 1 each |
| O2 | h1 without `break-words` | RED 1 |

The first J arm returned nothing at all and turned 24 tests red. It was too broad to be useful, so it was replaced by J-inline.

### Task 3 and the browser RED/GREEN

- **`--list`:** 12 tests in 1 file, rc 0: 4 live tests on mobile and 4 `@stack-free` tests on each project.
- **`--grep @stack-free`:** 8/8. The first run failed 3 times because of fixture geometry: the button was not under the notice, and the mobile project lays out at 980px without a viewport meta tag. The fixtures were fixed; no assertions were changed.
- **The unmodified spec against two builds** served by `next start` over `evidence/37-52-runtime-proof/stub-core.cjs`. Nothing touched the shared stack. Neither build's static chunks contain `localhost:9090`, and the stub log shows the browser calls came from :3052 and :3053.
  - **Pre-plan build (d9661e86):** 4 failed. Chrome named the cards `""`. Focus after Add was `BODY`. The recorder captured `["11 item in basket"]`. The h1 and the basket name showed ellipsis, nowrap and overflow (h1 262 > 214; basket name 573 > 49; document 362 > 360). "Got it" was not within 30 stops, and 10 stops were covered by the notice.
  - **This branch:** 8/8, including every instrument arm. The arms are the stripped `aria-labelledby` (Chrome's name is no longer the shop, and the assertion throws), removed live regions (nothing recorded, and the assertion throws), the notice forced over the focused element (overlap reported), and a forced ellipsis (reported).

### Doc gates

- `check-doc-metrics.sh` had 15 FAIL claims with rc 1 before the update, and 37/37 PASS with rc 0 after it.
- `docs-freshness.sh` reported drift ("Fix by running … --write") before the update, and rc 0 after.

## Files Created/Modified

- `frontend/lib/basket-announcement.ts`: the one-dish diff and the basket sentence.
- `frontend/components/storefront/cart-provider.tsx`: broadcasts `edit` on `jtoye:cart-updated`.
- `frontend/components/storefront/storefront-nav.tsx`: the basket link is no longer a live region; the new status region follows it.
- `frontend/app/shop/[slug]/shop-detail-client.tsx`: focus after Add and Remove, the "Add one more" label, and the h1 wraps.
- `frontend/app/shop/shop-discovery-client.tsx` and `frontend/components/marketing/shop-card.tsx`: `aria-labelledby` and `aria-describedby` on the card links.
- `frontend/components/public/bottom-notice-shell.tsx`: the portal host first in `<body>`, and the scroll padding.
- `frontend/app/shop/[slug]/cart/page.tsx` and `frontend/components/storefront/cart-drawer.tsx`: item names wrap.
- `frontend/e2e/a11y-storefront.spec.ts`: new spec.
- `frontend/e2e/public-a11y.spec.ts`: the skip-link check narrowed (deviation 2).
- Tests: `shop-discovery-client`, `shop-card`, `product-card-quantity-a11y`, `stepper-limit`, `cookie-notice`, plus the new `large-text-wrap` and `basket-announcement`.

## Decisions Made

See `key-decisions` in the frontmatter. The one the owner should see is the notice and skip-link order (deviation 2).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] The announcement is derived in the CartProvider, and the drawer's item names wrap too.**
- **Found during:** Task 1 and Task 2.
- **Issue:** The old link-as-live-region was the only thing announcing a count, on every surface: the dish card, the modal, the drawer and the basket page. Moving the announcement into the dish card alone would have left the other three silent, which is a regression by omission.
- **Fix:** The provider diffs the basket and every surface is announced through it. `cart-provider.tsx` and the new `lib/basket-announcement.ts` were not in the plan's file list. The drawer shows the same basket as the basket page, so its item names wrap too (`cart-drawer.tsx`).
- **Commits:** `cceeee99`, `637e55c4`

**2. [Rule 3 - Blocking] The public-a11y skip-link-first gate conflicted with the plan's DOM-order truth.**
- **Found during:** the goods run (verification).
- **Issue:** `scanSurface` asserts that `document.querySelector("a[href]")` is the skip link. With the notice first, that link became "Cookie policy" on every public surface: 14 of 16 tests failed on this branch's build, on 7 surfaces across 2 projects.
- **Fix:** The gate now requires the first link OUTSIDE the notice host to be the skip link, and requires every host to hold nothing but the notice. This is the GOV.UK cookie-banner order. Three break arms all went red: a link prepended to body, a link smuggled into the host, and the host attribute removed. The restore was checked by sha.
- **Not taken:** placing the host after the skip link. The skip link lives inside per-route layouts, so a foreign host there would be removed when the layout unmounts.
- **Files:** `frontend/e2e/public-a11y.spec.ts`
- **Commit:** `f3e5b246`

**3. [Rule 3 - Blocking] Existing tests followed the "+" rename and the portal.**
- `stepper-limit.test.tsx` (5 queries) and the A11Y-7 test now use "Add one more {dish}".
- Two container-based cookie-notice tests (the shared-shell comparison and the axe scan) now read the portal host. The axe scan keeps its non-vacuity control.
- **Commits:** `cceeee99`, `637e55c4`

**4. [Rule 1 - Test robustness] Soft assertions in the live spec.**
- Without them the first failure hid the rest, and on the pre-plan build the F6 journey failed while waiting for F3's new label. It now waits for the remove control, whose name did not change.
- **Commit:** `b52d4e65`

**Total deviations:** 4 auto-fixed (1 missing critical, 2 blocking, 1 test robustness).
**Impact:** No scope creep beyond keeping existing goods intact. The gate change is narrow and has falsification arms.

## Goods kept (Incremental Betterment Doctrine)

All goods runs used the stub with short dish names.

- **P2-MAR-P1** (the dish modal stays a textbook modal): `storefront-dish-modal-a11y.spec.ts` dialog contract and #272 pluralisation pass 16/16 on both builds.
- **Public axe gate:** /, /legal/cookies, /shop, a storefront, the dish modal, checkout and cart, plus both axe instrument arms, pass 18/18 on both builds.
- **Cookie notice has no layout cost:** `cookie-notice-layout.spec.ts` passes 2/2 on this branch's build, so the notice still occupies no layout space.
- **Priya's skip link and focus rings:** unchanged. The skip link is still first after the notice (gate above), and is first outright once the notice is dismissed.
- **P2-GRA-24** (checkout and menu fit at large text): checkout lines and menu card titles were not touched.
- **No storage key added:** asserted by the cookie-notice suite.

## Issues Encountered

- Run with a 62-character dish name, the goods specs fail on the pre-plan build too:
  - `storefront-dish-modal-a11y` compares 60-character slices of the focused element's text.
  - axe reports `scrollable-region-focusable` on the desktop dish modal.
  - Both are logged in `deferred-items.md` as open and not caused by this plan.
- The final build used the normal environment, so `.next` no longer has the stub URL baked in. The pre-plan tree was removed from the scratchpad.

## Known Stubs

None.

## Next Phase Readiness

- **37-53:** run `e2e/a11y-storefront.spec.ts` live: RED on the current images, then GREEN after the rebuild. It needs only the stack, not the vendor password.
- **Owner-visible:** the notice and skip-link order. The notice now comes before the skip link, and the public-a11y gate was narrowed to allow exactly that.

## TDD Gate Compliance

- Task 1: RED `fd11b187` (test) → GREEN `cceeee99` (feat)
- Task 2: RED `0ca1cacd` (test) → GREEN `637e55c4` (feat)
- Task 3 is not a TDD task (the spec itself)
- No REFACTOR commits

## Self-Check: PASSED

- Created files exist: `frontend/lib/basket-announcement.ts`, `frontend/lib/__tests__/basket-announcement.test.ts`, `frontend/__tests__/shop/large-text-wrap.test.tsx`, `frontend/e2e/a11y-storefront.spec.ts`, the four RED records, `evidence/37-52-runtime-proof/run.sh`.
- Commits on this branch: `fd11b187`, `cceeee99`, `0ca1cacd`, `637e55c4`, `d1faee6e`, `b52d4e65`, `17eb3e81`, `f3e5b246`, `5d7c3549` (`git rev-list --count d9661e86..5d7c3549` = 9).
- Final checks:
  - full jest: 248 suites, 2927/2927, rc 0
  - `npm run build`: rc 0
  - `tsc --noEmit`: rc 0
  - `npm run lint`: rc 0, with 0 errors and 29 warnings, none in a line this plan added; the `cart/page.tsx` `removeItem` warning predates it
  - `check-doc-metrics`: rc 0
  - `docs-freshness`: rc 0
