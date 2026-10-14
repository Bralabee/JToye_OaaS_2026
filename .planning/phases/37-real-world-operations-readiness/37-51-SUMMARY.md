---
phase: 37-real-world-operations-readiness
plan: 51
subsystem: ui
tags: [accessibility, live-region, aria-live, aria-current, route-announcer, generateMetadata, page-title, tracking, large-text, playwright, mutationobserver, d-05, uxt-080, uxt-081, uxt-084]
status: complete

requires:
  - phase: 37-37
    provides: "the order page's single role=status region and the C3 first-view message and title variant"
  - phase: 37-50
    provides: "the 37-G head this plan starts from (b68f0505, main merged back)"
provides:
  - "The order page and /track each have ONE role=status aria-live=polite region that announces 'Order update: {label}. {description}.' only when a tracking read returns a different status for the same order; the first read, an unchanged read and a failed read write nothing"
  - "aria-current='step' on the current tracker step (both trackers are now ordered lists labelled 'Order progress'); copy button named 'Copy order number' (kept)"
  - "Per-route layout.tsx generateMetadata: 'Basket · {Shop} — J'Toye', 'Checkout · {Shop} — J'Toye', 'Order {number} · {Shop} — J'Toye'; /track keeps 'Track your order — J'Toye'"
  - "lib/storefront-server.ts loadPublicShop: one cached read of /public/shops/{slug} for titles"
  - "lib/order-status-announcement.ts: one copy of the step words and the announcement for both tracking pages"
  - "Tracker labels wrap at 360px with root 130% (min-w-0 columns, hyphens + overflow-wrap) on /track and the order page"
  - "e2e/a11y-tracking.spec.ts: live journey (run in 37-53) plus a stack-free instrument block that runs on every run"
affects: [37-52, 37-53, phase-37-uat]

actuals:
  tokens: 21000
  tasks: 3
  commits: 6
plan_head_before: b68f05053e24a8abd2cee63aab9c89c197f161d8
plan_head_after: b0c01276db1faff498ce778a33744d48e796f0c1

tech-stack:
  added: []
  patterns:
    - "A change-only live region: keep the last status a read returned in a ref, write the region only when the same order comes back with a different status; an equal string is never rewritten, so the region's DOM does not mutate and nothing is read aloud"
    - "A jest MutationObserver on the region proves 'nothing announced' (zero mutation records), which a text-equality assertion cannot: a clear-and-rewrite of the same text is still an announcement"
    - "Per-step titles for client pages under a client layout: a server layout.tsx per route exporting generateMetadata, fed by a one-call cached loader, never notFound() from metadata"
    - "A test that must be RED before its module exists reports the missing module as an assertion (only 'Cannot find module' is mapped; any other import error rethrows), so RED is an assertion failure, not a suite load failure"

key-files:
  created:
    - frontend/app/shop/[slug]/cart/layout.tsx
    - frontend/app/shop/[slug]/checkout/layout.tsx
    - frontend/app/shop/[slug]/orders/[orderNumber]/layout.tsx
    - frontend/lib/order-status-announcement.ts
    - frontend/lib/storefront-step-title.ts
    - frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-live-region.test.tsx
    - frontend/app/__tests__/track-live-region.test.tsx
    - frontend/app/shop/__tests__/route-titles.test.ts
    - frontend/e2e/a11y-tracking.spec.ts
    - .planning/phases/37-real-world-operations-readiness/evidence/37-51-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-51-red-task2-track.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-51-red-task2-titles.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-51-runtime-proof/ (script + output)
  modified:
    - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
    - frontend/app/track/page.tsx
    - frontend/lib/storefront-server.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "Titles read the shop through a new one-call loader (loadPublicShop), not loadShopDetail: the full storefront load is four upstream calls on per-client rate-limited public endpoints, and every SSR call leaves from the same frontend address"
  - "A shop that cannot be read gives the step title without a name ('Basket — J'Toye'), never a guessed one, and the layouts never 404 by themselves; no robots key, so the indexing posture of the three steps is unchanged"
  - "F1 error (UI-SPEC unresolved, planner assumption): a failed poll announces nothing; the visible error text stays outside the region"
  - "On /track, looking an order up or switching to another order is not a change: it sets the baseline and clears a message about the previous order"
  - "The C3 first-view message stays the region's first message; a real change replaces it; there is never a second region"
  - "The live Playwright journey is @mobile-only (one real order per run); the instrument block runs on both projects"

patterns-established:
  - "Instrument arms on the real page: remove the region with page.evaluate, cause a real transition, assert nothing was recorded AND that the announcement assertion throws"

requirements-completed: [RWO-081, RWO-084, RWO-080]

coverage:
  - id: D1
    description: "Order page announces each status change once through its one status region; first load, unchanged and failed polls are silent; C3 message stays first"
    requirement: RWO-081
    verification:
      - kind: unit
        ref: "frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-live-region.test.tsx (10/10, RED 8/10 RED_EVIDENCE_OK, 6 break arms red)"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-51-runtime-proof/runtime-proof.out (real Chromium, next start of this branch, 17/17)"
        status: pass
    human_judgment: false
  - id: D2
    description: "/track result announces each status change once (guest lookup and the #458 signed-in path); aria-current on the current step on both trackers"
    requirement: RWO-081
    verification:
      - kind: unit
        ref: "frontend/app/__tests__/track-live-region.test.tsx (7/7, RED RED_EVIDENCE_OK, 6 break arms red)"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-51-runtime-proof/runtime-proof.out"
        status: pass
    human_judgment: false
  - id: D3
    description: "Basket, checkout and order routes have their own titles with the shop name; /track keeps its title"
    requirement: RWO-084
    verification:
      - kind: unit
        ref: "frontend/app/shop/__tests__/route-titles.test.ts (8/8, RED RED_EVIDENCE_OK, 4 break arms red)"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-51-runtime-proof/runtime-proof.out (server-rendered titles read by page.title())"
        status: pass
    human_judgment: false
  - id: D4
    description: "Tracker step labels wrap at 360px with root 130%: no horizontal overflow, no ellipsis"
    requirement: RWO-080
    verification:
      - kind: unit
        ref: "order-live-region.test.tsx + track-live-region.test.tsx structural arms (no truncate/nowrap/ellipsis; min-w-0)"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-51-runtime-proof/runtime-proof.out (overflow probe [] on both trackers; forced ellipsis flagged)"
        status: pass
    human_judgment: false
  - id: D5
    description: "e2e/a11y-tracking.spec.ts live journey on the rebuilt stack (real order, vendor confirm and start-preparation, titles, 360/130% probe, instrument arms on the real page)"
    verification:
      - kind: e2e
        ref: "npx playwright test e2e/a11y-tracking.spec.ts --list (9 listed); --grep @stack-free 8/8 with 4 break arms red"
        status: pass
    human_judgment: true
    rationale: "The live journey needs the rebuilt shared stack and the vendor password; by plan it is run (RED on old images, then GREEN) in 37-53, which this code-only plan may not touch"

duration: 19min
completed: 2026-10-14
---

# Phase 37 Plan 51: Tracking live regions, per-step titles and tracker wrapping Summary

**The order page and /track now tell a screen-reader customer "Order update: Confirmed. Shop accepted your order." exactly once when the order moves on, never on an unchanged or failed poll; basket, checkout and order each have their own server-rendered title with the shop name; and both trackers wrap at 360px with 130% text.**

## Performance

- **Duration:** about 19 min
- **Started:** 2026-10-14T19:18:40Z
- **Completed:** 2026-10-14T19:37:30Z
- **Tasks:** 3 of 3
- **Files:** 12 source and test files plus 4 doc/metrics files and the evidence files

## Accomplishments

- **F1 (UXT-081), order page.** The 37-37 region now also carries status changes. `fetchStatus` keeps the last status in a ref. The first read only sets that baseline. A read with a different status writes the announcement. A read with the same status, or a failed read, writes nothing. The tracker is an ordered list labelled "Order progress". The current step has `aria-current="step"` and the icon column is `aria-hidden`.
- **F1 on /track.** There is one region on the page from the first render. Every read that puts an order on screen goes through `showOrder`: the guest lookup, the signed-in list pick, the poll and "show recorded". `showOrder` announces only when the same order comes back with a different status. Looking up a different order clears the message and announces nothing. Both trackers use the same copy, from `lib/order-status-announcement.ts`.
- **F4 (UXT-084).** Three server `layout.tsx` files export `generateMetadata`. They build the title through `storefrontStepTitle`, which reads `loadPublicShop`: one cached request to `/public/shops/{slug}`. The order page still sets its C3 "confirmed" variant itself on the first view.
- **F6 (UXT-080), tracker part.** The /track tracker had no `truncate`. The real defect was that its five flex columns kept `min-width: auto`, so at 360px with 130% text the row could not shrink. The columns are now `min-w-0` and the labels use `hyphens-auto` and `overflow-wrap:anywhere`. Labels on the order page can now shrink and break the same way.
- **The browser spec.** `e2e/a11y-tracking.spec.ts` has two blocks. The live journey runs in 37-53. The stack-free instrument block runs on every run and passes 8/8 now.

## Task Commits

1. **Task 1 (tracer): the order page announces a change once**
   - RED `90957ce2` (test)
   - GREEN `a5d030db` (feat)
   - Tracer gate: end-of-phase mode with an automated-only verify, so the verify was re-run (`npm --prefix frontend test -- "app/shop/\[slug\]/orders"`: 3 suites, 35/35, rc 0) before expanding.
2. **Task 2: /track announces too, and each step has its own title**
   - RED `575da525` (test)
   - GREEN `2fcdd468` (feat)
3. **Task 3: the browser instruments** — `6baf9685` (test)
4. **Metrics** — `b0c01276` (docs): 6019 → 6049

## RED / GREEN evidence, both directions

### Task 1: `order-live-region.test.tsx`

**RED** on the pre-change page: 8 of 10 failed. Record: `evidence/37-51-red-task1.json`, verdict `RED_EVIDENCE_OK / target_test_failed`.
- The target ("PENDING then CONFIRMED") failed on its planned assertion: the region was empty after a real poll through the page's own 15 s callback.
- The two arms that passed are goods that already held before the change. The first load is silent, and the copy button was already named "Copy order number" (31.1-28). That criterion therefore cannot show this plan's change, and it is recorded as a kept good, not as evidence.

**GREEN:** 10/10. The `app/shop/[slug]/orders` suites pass 35/35, including order-confirmation and order-tracking-allergens.

**Break arms**, each run against the committed page. The page's sha `6a693902…` was checked before and after, and the closing clean run was 10/10.

| Arm | Break | Result |
|-----|-------|--------|
| A1 | Announce on every read, the first included | RED 2: first load not silent; C3 message replaced by an unchanged poll |
| A2 | Re-announce an unchanged poll (rewrite with a toggled zero-width space) | RED 2: unchanged poll mutates the region; C3 |
| B | A failed poll writes "Could not refresh…" into the region | RED 1: failed poll |
| C | No `aria-current` | RED 1 |
| D | C3 message wins over the update | RED 1: C3 arm |
| E | `truncate` on the label column | RED 1: UXT-080 arm |

### Task 2: `track-live-region.test.tsx` and `route-titles.test.ts`

**RED:** 15 of 15 failed. Both records return `RED_EVIDENCE_OK`: `evidence/37-51-red-task2-track.json` and `evidence/37-51-red-task2-titles.json`.
- /track had no element with `role="status"`. The lookup itself rendered, which proves the test harness works.
- None of the three routes exported `generateMetadata`. The test reports a missing module as an assertion (`typeof generateMetadata`: expected "function", received "undefined"). Any other import error is rethrown, not swallowed.

**GREEN:**
- Plan verify: `track-live-region` + `track` + `route-titles`, 28/28, rc 0.
- Acceptance: `public-marketing-seo-metadata`, `track-signed-in` and `sitemap-robots-consistency` pass 23/23.
- The 10-suite set around these changes passes 106/106, `storefront-server.test.ts` included.

**Break arms**, each run against the committed files. Three file shas were checked before and after, and the closing clean run was 15/15.

| Arm | Break | Result |
|-----|-------|--------|
| A1 | Announce on lookup | RED 2: lookup silent; #458 signed-in path |
| A2 | Re-announce an unchanged poll | RED 1 |
| B | Failed poll into the region | RED 1 |
| C | Region role removed | RED 5 |
| D | Columns without `min-w-0` | RED 1 |
| E | No `aria-current` | RED 1 |
| F | Title helper drops the shop name | RED 3 |
| G | Title helper uses the four-call `loadShopDetail` | RED 4 (incl. the one-read arm) |
| H | `notFound()`-style throw on 404 | RED 1 |
| I | `robots: { index: false }` on the cart layout | RED 1 |

### Task 3: `e2e/a11y-tracking.spec.ts`

- `--list`: 9 tests listed, rc 0. That is 1 live test (@mobile-only) and 4 stack-free tests on each of the two projects.
- `--grep @stack-free`: 8/8 passed, rc 0. No server is needed.

**Break arms on the instruments themselves**, mobile project. Spec sha `9c99f9a0…` was checked before and after, and the closing clean run was 8/8.

| Arm | Break | Result |
|-----|-------|--------|
| R1 | The recorder never records | RED: positive control |
| R2 | The recorder counts an unchanged rewrite | RED |
| R3 | The announcement assertion is a no-op | RED: the `toThrow` arm |
| R4 | The overflow probe is blind | RED |

The live journey's own instrument arms run on the real pages in 37-53:
- a wrong expected title is refused;
- a forced ellipsis is flagged;
- with the region removed, a real mark-ready records nothing and the announcement assertion throws.

### Supplementary: a real browser against this branch's build

This was not in the plan. It is `evidence/37-51-runtime-proof/`, and it does not touch the shared stack:
- This branch's own `next build` was served by `next start` on :3051.
- The Next server's metadata read went to a stub core on :9351.
- The browser's API calls were answered by page routes.
- Polls were driven by `page.clock.fastForward(15000)`.
- The build was rebuilt from the committed tree first. It has no app source newer than `BUILD_ID`. "Order progress", "data-tracker-step" and "Order update: " are each found in 6 built chunks, and a negative control string is found in none (rc 1).

Result: **17/17 checks passed.**
- Basket, checkout and order titles carry the shop name, and the /track title is kept.
- Each page has exactly one polite status region.
- CONFIRMED was announced on the order page and PREPARING on /track. An unchanged poll was not re-announced on either page.
- `aria-current` moved with the status.
- Both trackers at 360px with 130% text: overflow probe `[]`.
- Instrument with the region removed: the stepper reached Ready and nothing was recorded.
- Instrument with a forced ellipsis: flagged as `["Ready ellipsis","Ready 48>10"]`.

The fail-closed check also caught a bug in the scratch recorder. Its first two runs recorded the /track announcement twice, because the order page's observer survived the client-side navigation. The first fix assigned the return value of `.observe()` to the handle, so `disconnect()` never ran. With the observer assigned and then observed separately, /track records exactly one announcement. The committed spec does not have this bug: `installAnnouncementRecorder` disconnects the previous observer through a correctly assigned handle.

## Test counts

| Check | Result |
|-------|--------|
| jest, full | 2899/2899 passed, 246 suites, rc 0 (was 2874/243; +25 tests, +3 suites) |
| `npm run build` | rc 0, "Running TypeScript … Finished". Run cold at Task 2, then again from the committed tree before the runtime proof |
| `npx tsc --noEmit -p .` | rc 0 |
| `npm run lint` | rc 0: 0 errors, 29 warnings. The only warnings in changed files are two that were already there on the order page (`searchParams`, `isPending` unused) |
| `check-e2e-typecheck` | PASS, 47 files |
| `check-e2e-baseurl-contract` | PASS |
| `check-test-count-oracle playwright` | PASS, 167/41 |
| `docs-freshness` | rc 1 before `--write`, rc 0 after |
| `check-doc-metrics` | RED (12 stale claims) before the prose edit, PASS 37/37 after |
| `check-ssr-coverage-contract`, `check-layout-width-contract`, `check-playwright-mobile-contract`, `check-test-block-counter`, `check-claims`, `check-doc-citations`, `check-no-measured-placeholders` | rc 0 |
| Playwright live journey | **Not run.** It needs the shared stack rebuilt from this branch plus the vendor password; the plan assigns its RED (old images) and GREEN runs to 37-53 |

## Files Created/Modified

**Created**
- `frontend/lib/order-status-announcement.ts`: `ORDER_STATUS_COPY` and `statusUpdateAnnouncement`, one copy for both tracking pages.
- `frontend/lib/storefront-step-title.ts`: "{step} · {Shop} — J'Toye", or a nameless step title when the shop cannot be read.
- `frontend/app/shop/[slug]/cart/layout.tsx`, `checkout/layout.tsx`, `orders/[orderNumber]/layout.tsx`: `generateMetadata`.
- The three jest suites and the Playwright spec listed above.

**Modified**
- `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx`: change-only region, `<ol>` tracker with `aria-current`, labels that wrap, shared step copy.
- `frontend/app/track/page.tsx`: the region, `showOrder`, `<ol>` tracker with `aria-current`, `min-w-0` columns and labels that wrap.
- `frontend/lib/storefront-server.ts`: `loadPublicShop`.
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`: counts 6019 → 6049.

## Decisions Made

See key-decisions in the frontmatter. The two with consequences:
- **One upstream read per title, not four.** Every SSR call leaves from the frontend's single address, so it counts against one rate-limit bucket.
- **A failed poll is silent (F1 error assumption).** The order page's existing visible error only appears when no order has loaded yet. It is not in the region either way.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] Titles read the shop with one request, not `loadShopDetail`**
- **Found during:** Task 2.
- **Issue:** The plan's interface note suggests `loadShopDetail`. That function makes four public calls (shop, menu, reviews, announcements) for a title. They would run on every basket, checkout and order request, against public endpoints that are rate-limited per client, and every SSR call leaves from one address.
- **Fix:** `loadPublicShop` is a cached single read. The test pins it to one upstream call; break arm G is red with the four-call loader.
- **Files:** `frontend/lib/storefront-server.ts`, `frontend/lib/storefront-step-title.ts`.
- **Commit:** `2fcdd468`

**2. [Rule 2] Two shared modules not listed in files_modified**
- **Found during:** Task 2.
- **Issue:** The announcement copy would otherwise be duplicated across two pages and could drift. The three layouts would each duplicate the title logic.
- **Fix:** Added `lib/order-status-announcement.ts` and `lib/storefront-step-title.ts`. The order page's `STEPS` now spread the shared copy. The rendered words are byte-identical, and every existing order and track test still passes.
- **Commit:** `2fcdd468`

**3. [Criterion replaced] "Remove truncate from tracker labels" could not fail**
- **Found during:** Task 1.
- **Issue:** Neither tracker had `truncate` before this plan, so a "no truncate" check was already true on the old tree and could not fail.
- **Fix:** The actual /track defect was the flex columns' `min-width: auto` and is fixed as described above. The stronger checks kept are: the jest `min-w-0` arm (break arm D red), and the real-browser probe at 360px with 130% text (`scrollWidth <= clientWidth`, computed `text-overflow`), which flags a forced ellipsis. The original no-truncate jest check is kept alongside (break arm E red).

**4. [Scope, additive] The spec gained a stack-free instrument block, and the live test is @mobile-only**
- **Found during:** Task 3.
- **Why:** The plan's instrument arms live inside a live-only test, so they would prove nothing until 37-53. The stack-free block re-proves on every run that each probe can fail. One real order per run is enough for the live test, and it cancels its order afterwards.

**5. [Evidence, additive] The real-browser runtime proof above**
- **Why:** This is a code-only plan, but the jsdom tests cannot show that Chromium sees exactly one mutation. The proof does not touch the shared stack.

**Total deviations:** 2 auto-fixed (Rule 2), 1 criterion replaced with a stronger one, 2 additive.
**Impact on plan:** No scope creep beyond the tracking surfaces. Every addition reduces risk (rate limits, copy drift, instruments that cannot fail).

## Issues Encountered

- **`check-terminal-states.sh` VOIDs (rc 2) on this machine.** The global python PATH shim refuses its interpreter. Neither the script nor `docs/ops/terminal-states.yaml` is touched by this plan, and its last change is a1237bdc (2026-10-13). This is an environment issue and out of scope.
- **`scripts/check-render-invariants.sh` does not exist at that path.** It matched a CI grep and was not run. Not caused by this plan.
- **One of my own commands killed its own shell.** A cleanup `pkill -f` matched the command line that ran it (the trap the user CLAUDE.md warns about). The next-server it was meant to stop was then found by port and killed by PID. Every later run stopped the server by captured or port-resolved PID, and :3051 had no listener at the end.

## Known Stubs

None.

## Items for the orchestrator

- **Requirements.** RWO-080, RWO-081 and RWO-084 are copied verbatim into requirements-completed. 37-52 and/or 37-53 also declare all three. RWO-081 and RWO-084 need 37-53's live run, and RWO-080 also needs 37-52 (basket and shop header). Leave them unticked until the last declaring plan has its SUMMARY.
- **For 37-53.** Run `npx playwright test e2e/a11y-tracking.spec.ts` with the stack's `.env` sourced (vendor password, `KEYCLOAK_CLIENT_SECRET`): first on the old images (RED: no region, root titles), then after the rebuild (GREEN). The live test is @mobile-only, so it runs on the mobile project. The new layouts add one upstream GET of `/public/shops/{slug}` per basket, checkout and order request, so watch the public rate-limit bucket in the full nightly run.
- **No owner questions.** The one UI-SPEC gap (F1 error) was decided as the planner's assumption: a failed poll is silent.

## Next Phase Readiness

Ready for 37-52 (the F2/F3/F5/F6 basket and shop-header work). The shared copy module and the per-route layouts are in place. 37-53 owns the stack rebuild and the live runs.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-14*

## Self-Check: PASSED

- All 9 created source and test files exist on disk.
- All 6 task commits are ancestors of HEAD: `90957ce2`, `a5d030db`, `575da525`, `2fcdd468`, `6baf9685`, `b0c01276`.
- The plan ledger `gsd-plan-head-before-37-51` = `b68f0505`; `git rev-list --count b68f0505..b0c01276` = 6.
