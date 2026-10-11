---
phase: 37-real-world-operations-readiness
plan: 21
subsystem: ui
tags: [nextjs, react, dashboard, kitchen, pause, opening-hours, radix, jest, playwright, europe-london]

requires:
  - phase: 37-20
    provides: "PUT/DELETE /api/v1/shops/{id}/pause (STAFF, 20 | 40 minutes or untilReopened); ShopDto hoursSource, legacyOpeningHours, openingSchedule, ordersPausedUntil, ordersPausedIndefinitely (NON_NULL); CreateShopRequest.openingSchedule (absent keeps, {} closed every day, any save STRUCTURED); the D-25 ruling applied per day"
  - phase: 37-22
    provides: "lib/shop-availability.ts (the UK-time formatter reused here as formatUkTime); the customer side reading openNow"
  - phase: 37-18
    provides: "the kitchen header control row, the board-stopped overlay and its D-08 403 listener"
provides:
  - "PauseOrdersControl + useShopPause + readPauseView (kitchen header, UI-SPEC K3 vendor side): 20 / 40 minutes / Until I reopen, Resume orders now; state from the server's shop, never a client timer"
  - "PausedBanner below the feed banner: white with a 2px slate-900 border (L6), the shop's name, the K3 sentence with the server's time"
  - "OpeningScheduleEditor (UI-SPEC K4) replacing the shop dialog's seven free-text inputs; the dialog sends openingSchedule only when touched"
  - "HoursNoticeCard on the dashboard home; Hours not set / Check hours / Paused until HH:MM badges on the shops table; /dashboard/shops?edit={id}&focus=hours"
  - "lib/opening-schedule.ts: window/day wording, the needs-review days by the server's own rule, overlap spans"
  - "shops-api: fetchShop, pauseShopOrders, resumeShopOrders, PauseChoice; shop-availability: formatUkTime"
  - "Storefront /shop/[slug] Opening hours list read from openingSchedule (Rule 2 deviation)"
  - "Shop dialog saves keep the storefront slug (Rule 1 deviation)"
affects: [37-23, kitchen, dashboard-shops, dashboard-home, storefront]

actuals:
  tokens: 32018
  tasks: 3
  commits: 13
plan_head_before: 0ee80f55a26d5c64a774d8a53453192e2af832f4
plan_head_after: 059611155e8435ed7f0ff1d991c14ca1929b3251

tech-stack:
  added: []
  patterns:
    - "A vendor status claim reads the server's row and makes no claim before the first read lands (the control is disabled until then)"
    - "A stored end instant the server never clears is compared with a clock read in an effect or a loader (never in render), and confirmed by a re-read at that instant"
    - "A request field the editor did not touch is OMITTED (spread), not sent as undefined, so 'absent keeps' is a property of the object a test can assert"

key-files:
  created:
    - frontend/components/dashboard/kitchen/pause-orders-control.tsx
    - frontend/components/dashboard/kitchen/paused-banner.tsx
    - frontend/components/dashboard/shops/opening-schedule-editor.tsx
    - frontend/components/dashboard/hours-notice-card.tsx
    - frontend/lib/opening-schedule.ts
    - frontend/components/dashboard/kitchen/__tests__/pause-orders.test.tsx
    - frontend/components/dashboard/shops/__tests__/opening-schedule-editor.test.tsx
    - frontend/components/dashboard/__tests__/hours-notice-card.test.tsx
    - frontend/app/dashboard/__tests__/hours-notice-home.test.tsx
    - frontend/components/storefront/__tests__/shop-hours-list.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-red-task2-storefront-hours.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-red-task3.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-red-slug.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-red-pause-preread.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-break-arms.txt
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-live-probes.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-live.cjs
    - .planning/phases/37-real-world-operations-readiness/evidence/37-21-screens/
  modified:
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/app/dashboard/shops/page.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/lib/shops-api.ts
    - frontend/lib/shop-availability.ts
    - frontend/types/api.ts
    - .planning/phases/37-real-world-operations-readiness/37-UI-SPEC.md

key-decisions:
  - "Copy decision under the D-25 owner ruling (2026-10-10), made by the orchestrator, pending the owner's own wording: the editor still pre-fills an unreadable NEEDS_REVIEW day as Closed (a save makes it closed, D-15), but its amber line reads 'We couldn't read \"{original text}\". Customers can still order on this day at any time until you save your hours.'; the dashboard needs-review card says customers can order on those days at any time until the hours are saved. UI-SPEC rows 458/791/794 amended with '(D-25 ruling 2026-10-10)'. WINDOWS #36"
  - "The pause state is the server's shop: the PUT/DELETE response, then a re-read; re-read on the board's existing 60 s poll (KITCHEN_POLL_INTERVAL_MS) and at a timed pause's stated end. A past ordersPausedUntil is no pause, because the server keeps an ended pause's instant on the row"
  - "The pause control is disabled until the server's first read of the shop lands (found live: a fresh load of a paused shop showed an enabled 'Pause orders')"
  - "The menu stays open, every row disabled, while a write is in flight, then closes; a ref guards against two taps in one frame (T-37-55)"
  - "A pause 403 is shop-access-denied at the same STAFF gate as the board's own read, so 37-18's D-08 listener stops the board too; the K3 refusal copy renders under the control, behind the overlay. Recorded, not changed"
  - "The banner names the shop (a 14px line above the K3 sentence); the K3 copy table has no name, but the UI-SPEC long-text backstop measures a 60-character name in the banner"
  - "The unreadable days a needs-review notice names are the server's own rule (ShopAvailabilityPolicy.withUnreadableDaysOpen: schedule empty, text present and not 'closed'), gated on hoursSource; nothing decides from the text"
  - "The storefront Opening hours list renders openingSchedule for STRUCTURED / MIGRATED_FROM_TEXT; NEEDS_REVIEW keeps the vendor's text (its unreadable days are [] in the schedule but open on the server, so a schedule list would print Closed for a day customers can order on); legacy or no schedule shows the text; never both. This refines the orchestrator's 'fall back only for legacy or no schedule' by the 37-22 deferred note"
  - "The shop dialog sends the shop's current slug unless the name changed, because ShopService.updateShop regenerates name + random suffix whenever none is sent (every save moved /shop/{slug}). Server not changed (no Java); owner question recorded in deferred-items"
  - "The all-closed note is shown only when it is true: every day closed AND (touched, or not a legacy/needs-review shop), because an untouched legacy or needs-review shop still takes orders"
  - "Rebuild with build + up --no-deps --force-recreate frontend (37-17's measured form) instead of up -d --build frontend, which the project memory records retags core-java; core-java image and container were identical before and after"
  - "RWO-022 NOT marked complete: 37-23 also declares it and requirements.ready-ids reports it blocked for 37-21 (the orchestrator's 'you are the last declaring plan' did not hold)"

patterns-established:
  - "Kitchen pause: import useShopPause / PauseOrdersControl / readPauseView from components/dashboard/kitchen/pause-orders-control.tsx; format server instants with formatUkTime"
  - "Structured hours on the client: lib/opening-schedule.ts (formatWindow, formatDay, scheduleRows, unreadableDays); never decide open or closed from it"

requirements-completed: []

coverage:
  - id: D1
    description: "One-tap Pause orders in the kitchen header (20 / 40 minutes / Until I reopen, 44px rows, no confirm), the control and PausedBanner reading the server's pausedUntil in UK time, Resume orders / Resume orders now"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/kitchen/__tests__/pause-orders.test.tsx (16)"
        status: pass
      - kind: e2e
        ref: "evidence/37-21-live-probes.md 'live: kitchen' (23 PASS); screens kitchen-pause-menu-390.png, kitchen-paused-timed-390.png, kitchen-paused-indefinite-390.png"
        status: pass
    human_judgment: false
  - id: D2
    description: "A pause made or ended on another device reaches the board within the existing 60 s poll"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "pause-orders.test.tsx#a pause made on another device shows on the board's next 60 s re-read"
        status: pass
      - kind: e2e
        ref: "evidence/37-21-live-probes.md 'live: crossdevice' (60 s and 60 s); kitchen-crossdevice-b-1280.png"
        status: pass
    human_judgment: false
  - id: D3
    description: "Structured opening-hours editor in the shop dialog (UI-SPEC K4): validation, overnight hint, Copy Monday, all-closed note, legacy and needs-review pre-fill, 0-1-3 windows, untouched hours omitted"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/shops/__tests__/opening-schedule-editor.test.tsx (23)"
        status: pass
      - kind: e2e
        ref: "evidence/37-21-live-probes.md 'live: dialog' (save Monday 09:00-17:00 -> 200 STRUCTURED -> storefront list -> restored by md5) and 'live: backstops' (375px, 200-char quote)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Hours not set / Check hours / Paused until badges on the shops table; the dashboard-home hours notice card; the deep link to the hours fieldset"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/__tests__/hours-notice-card.test.tsx (7), frontend/app/dashboard/__tests__/hours-notice-home.test.tsx (3), opening-schedule-editor.test.tsx 'shops table notices' block (3)"
        status: pass
      - kind: e2e
        ref: "evidence/37-21-live-probes.md 'live: notices' (route-rewritten list) and 'live: kitchen' (real paused badge); home-hours-notice-390.png, dialog-from-notice-390.png, shops-table-badges.png, shops-row-paused.png"
        status: pass
    human_judgment: false
  - id: D5
    description: "Storefront Opening hours list from openingSchedule (Rule 2 deviation)"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/components/storefront/__tests__/shop-hours-list.test.tsx (8)"
        status: pass
      - kind: e2e
        ref: "evidence/37-21-live-probes.md 'live: dialog' (Monday 09:00–17:00, other days Open 24 hours); storefront-hours-after-save.png"
        status: pass
    human_judgment: false
  - id: D6
    description: "UAT rows owed to 37-23: the 403 refusal read by a revoked STAFF user, the in-flight state by hand, validation by hand, a real NEEDS_REVIEW / LEGACY shop"
    requirement: RWO-022
    verification: []
    human_judgment: true
    rationale: "Jest only or route-rewritten live (WINDOWS #37); the copy decision awaits the owner (WINDOWS #36)"

duration: 40min
completed: 2026-10-10
status: complete
---

# Phase 37 Plan 21: Kitchen pause, structured hours editor and hours notices Summary

**The kitchen pauses storefront orders in one tap (20 min, 40 min, until reopened) and shows the server's own end time in UK time. The shop dialog's seven free-text inputs are replaced by a structured editor that saves what the server enforces. Vendors whose shops are "open any time" by legacy, or whose hours could not be read, see notices on the dashboard home and badges on the shops table that read `hoursSource`. Proven in jest with break arms and live on a rebuilt frontend.**

## Performance

- **Duration:** about 40 min
- **Started:** 2026-10-10T15:20:14Z
- **Completed:** 2026-10-10T16:00Z
- **Tasks:** 3 of 3, each RED (`test(37-21)`, RED_EVIDENCE_OK) then GREEN (`feat(37-21)`), plus 2 found-defect RED/fix pairs and 1 test strengthening
- **Files:** 17 frontend files (+2492 / -58), no Java

## Accomplishments

- **Task 1 (tracer): Pause orders.**
  - `PauseOrdersControl` sits in the kitchen header after the shop selector, in the row that wraps (`flex-wrap`). It is an outline `h-11` button with a `PauseCircle` icon.
  - Its menu offers 20 minutes, 40 minutes and Until I reopen as 44px rows, with no confirm step. While paused it adds Resume orders now and reads "Paused until HH:MM" or "Paused".
  - `useShopPause` takes the state from the server's shop: the PUT/DELETE response, a re-read after it, the 60 s poll, and a re-read at the stated end.
  - The control is disabled until the server's first read lands, and everything is disabled while a write is in flight.
  - `PausedBanner` sits below the feed banner. It is white with a 2px slate-900 border and carries the shop's name and a primary Resume orders.
  - Tracer gate (end-of-phase mode, automated verify only): the verify re-ran green on `6ddc6e9e` (83/83), so expansion continued.
- **Task 2: Structured hours editor.**
  - `OpeningScheduleEditor` replaces the free-text inputs.
  - Validation mirrors the server: a missing time, overlapping windows, and an overnight window running into the next day. Errors use role=alert, aria-invalid, aria-describedby, and focus the first.
  - Also: the overnight hint, Copy Monday, the all-closed note, the legacy info line, the needs-review quote, 0/1/3 windows and named remove buttons.
  - The dialog sends `openingSchedule` only when the editor was touched.
- **Task 3: Notices.**
  - `HoursNoticeCard` on the dashboard home, built from the shops list the page already loads. It shows nothing while the list is pending or after a failed load.
  - The shops table gains Hours not set, Check hours and Paused until HH:MM / Paused badges.
  - `/dashboard/shops?edit={id}&focus=hours` opens that shop's dialog focused on the Opening hours fieldset. A shop that is not on the loaded page is read by id.
- **Deviations delivered:**
  - The storefront Opening hours list now reads the schedule.
  - A dialog save keeps the shop's storefront slug.

**Refresh cadence (asked by the orchestrator):** the banner follows the server on the board's existing 60 s liveness poll (`KITCHEN_POLL_INTERVAL_MS`). The board also re-reads after each pause or resume and at a timed pause's stated end. Measured live: a second device showed a pause made on the first device after 60 s, and cleared after a resume after 60 s.

## Task Commits

1. **Task 1: Pause orders from the kitchen header** — RED `56bed872`, GREEN `6ddc6e9e`
2. **Task 2: The structured opening-hours editor** — RED `11ef3402`, GREEN `3b5bfa68`
   - Deviation, storefront hours list: RED `3e10fecc`, GREEN `f2557079`
3. **Task 3: Hours not set / Check hours notices** — RED `69a9ccf3`, GREEN `013eb954`
- Found while preparing the live run (slug): RED `a834abcb`, fix `d5e1490f`
- Found in the live run (claim before read): RED `69ecdc8b`, fix `7b80f302`
- Test strengthening after arm 1-H stayed green: `05961115`

**Plan metadata:** the docs commit that follows this SUMMARY (SUMMARY, evidence, WINDOWS, deferred-items, UI-SPEC, STATE, ROADMAP).

## TDD Gate Compliance

| Gate | RED | Classifier | Target (semantic assessment in the record) | GREEN |
|---|---|---|---|---|
| Task 1 | `56bed872` | RED_EVIDENCE_OK (`37-21-red-task1.json`, TAP) | No pause control in the kitchen header (14 of 14 new tests red, 69 existing green) | `6ddc6e9e` |
| Task 2 | `11ef3402` | RED_EVIDENCE_OK (`37-21-red-task2.json`) | No Opening hours fieldset. 17 of 19 red, including 7 old free-text inputs where 0 are required | `3b5bfa68` |
| Deviation (storefront list) | `3e10fecc` | RED_EVIDENCE_OK (`37-21-red-task2-storefront-hours.json`) | Monday read "whenever" (the old text) instead of 09:00–17:00. 4 of 8 red; 4 guards pass by design | `f2557079` |
| Task 3 | `69a9ccf3` | RED_EVIDENCE_OK (`37-21-red-task3.json`) | No "Hours not set" badge. 8 of 13 red; 5 "no card" guards pass by design | `013eb954` |
| Slug (Rule 1) | `a834abcb` | RED_EVIDENCE_OK (`37-21-red-slug.json`) | The PUT body had no slug. 1 of 3 red; rename and create pass by design | `d5e1490f` (fix) |
| Claim before read (Rule 1) | `69ecdc8b` | RED_EVIDENCE_OK (`37-21-red-pause-preread.json`) | The control was enabled before the read | `7b80f302` (fix) |

Every guard that passed in RED has its own break arm on the GREEN tree. The one exception is the dashboard-home "pending" case. It is held structurally: the home page renders its loading spinner and not the card while the list is in flight, so arm 3-B (a card for a null list) reds the failed-load case and the unit case, but cannot reach the pending case.

## Acceptance criteria and break arms (both directions)

Full log: `evidence/37-21-break-arms.txt`. Every arm ran on a committed state:

- **30 arms:** 29 jest and 1 build.
- **Restores:** every one was checked by sha256 (`RESTORED-BY-CONTENT` 29, `RESTORE-FAILED` 0, `VOID` 0, plus the build arm restored by content).
- **Clean runs:** every clean run before and after was green.

| Arm | Break | Fail direction (measured) |
|---|---|---|
| 1-A | **the plan's fail arm:** a client timer (now + 20) instead of the server field | 5 of 14 red; the target test found "18:40" where the server said 18:41, and 'Until I reopen' read "Paused until 18:40" |
| 1-B | the UK time formatter set to UTC | 4 red (17:41 instead of 18:41) |
| 1-C | the menu items not disabled in flight | the backstop test red (`aria-disabled` missing) |
| 1-D | a past ordersPausedUntil read as paused | 2 red ("Paused until 18:00" on an ended pause) |
| 1-E | the header row without `flex-wrap` | the header test red |
| 1-F | the banner `bg-slate-900` (L6) | the L6 test red |
| 1-G | no 60 s re-read | the other-device test red |
| 1-H | no re-read at the stated end | **stayed green**: the 60 s poll also re-reads inside the 21 minutes the test advanced. The test could not fail, so a new test was added (`05961115`) with the end falling between two polls. Arm 1-H2 now reds it |
| P-A | control enabled before the first read | the claim-before-read test red |
| 2-A / 2-B / 2-C | the overlap check, the next-day spill check, or the missing-time check removed | 1 / 1 / 2 red |
| 2-D | an untouched editor sends `{}` | the legacy-keeps and other-fields tests red (a legacy shop would silently close) |
| 2-E | the old "This day shows as closed." sentence | the NEEDS_REVIEW test red |
| 2-G | no focus on error | 2 red |
| 2-H | a 4th window offered | the zero-one-many test red |
| 2S-A..E | list from text / NEEDS_REVIEW from schedule / both lists / a list with no hours / legacy text dropped | 4 / 1 / 3 / 1 / 1 red |
| S-A | slug sent on a rename too | the rename test red |
| **3-A** | **the plan's fail arm:** the card computed by parsing `legacyOpeningHours`, ignoring `hoursSource` | 3 of 7 red, including both "source and text disagree" fixtures |
| 3-B | a card rendered for a null list | 2 red (the null unit case and the home failed-load case) |
| 3-C / 3-D / 3-E / 3-F | a badge from text / an ended pause badged / focus ignored on the deep link / the old card sentence | 1 red each |
| B-T | a type error in a committed file | `npm run build` rc 1 (TS2322); clean build rc 0 |

## Measured figures (re-measured)

| Check | Before (0ee80f55) | After |
|---|---|---|
| jest | 2550 / 215 (re-measured at dispatch) | **2607 tests / 220 suites**, all pass (+57: pause-orders 16, opening-schedule-editor 23, shop-hours-list 8, hours-notice-card 7, hours-notice-home 3) |
| docs block counter | — | `count-test-blocks.mjs --family jest --per-file` over the 5 new files gives 57, equal to the 57 executed. Control: the 37-17 file still VOIDs (rc 2), untouched |
| `npm run build` | — | rc 0, TypeScript ran (break arm B-T: rc 1) |
| `npm run lint` | 31 warnings (37-22) | **0 errors**, 31 warnings, none in this plan's files (per-file eslint rc 0) |
| `tsc -p tsconfig.json` | 1 error (37-17's `kds-sound.test.tsx(200,24)`) | the same single error |
| `tsc -p tsconfig.e2e.json` | — | rc 0 |
| core-java | 1683/0/0/1, 1071/0/0/1 | **not run: no Java changed** |

## Runtime parity (CLAUDE.md contract b)

- **Before the rebuild:**
  - The frontend container was `8945c830`, image `5d359e69`.
  - `Until I reopen` and `Copy Monday to every day` were absent from `/app/.next` (rc 1). The positive control "Open now" was present (rc 0).
  - `check-runtime-freshness.sh` failed: `FAIL: 1 of 4 running built service(s) do not match the source tree`.
- **Rebuilds:** four, each `docker compose -f docker-compose.full-stack.yml build frontend` then `up -d --no-deps --force-recreate frontend`. They followed the two found defects and the last test commit.
- **core-java untouched:** image `9d3b9884` and container `2128456c` are identical before and after. It was not restarted, so the alert metrics were not reset and `seed-order-metric.sh` was not needed.
- **Final:**
  - Container `040c64a6`, StartedAt 15:55:44Z, image `9a532285`, LastTagTime 15:55:42Z.
  - All four tokens are present in `/app/.next` (rc 0), and an absent-token negative control gives rc 1.
  - `check-runtime-freshness.sh` rc 0, verbatim: `frontend FRESH image tagged 2026-10-10 15:55:42 UTC >= newest build-input commit 05961115 (2026-10-10 15:53:47 UTC)` and `PASS: 4 running built service(s) match the source tree (0 unverified).`

## Live proof (Playwright, `evidence/37-21-live-probes.md`, 13 screenshots in `evidence/37-21-screens/`)

- **Kitchen, Brixton Village Grill, 390px: 23 PASS.**
  - The control is 44px; the menu rows are 44px; the page does not scroll sideways.
  - PUT `{minutes: 20}` returned 200 with `ordersPausedUntil` 17:09:58+01:00. The control then read "Paused until 17:09", and the banner showed the K3 sentence with that time, on a white background.
  - The public shop agreed: `openNow` false, `pausedUntil` the same instant.
  - The shops table row showed "Paused until 17:09".
  - Resume orders sent DELETE (200). The banner went, and the public shop reopened.
  - Until I reopen showed the indefinite sentence; Resume orders now sent DELETE (200).
- **Cross-device:** a second browser context showed the pause after 60 s and cleared it 60 s after the resume.
- **Long name:** a 60-character name at 360px and root 130% (20.8px). The name wraps (`scrollWidth` 273 = `clientWidth` 273), the banner's right edge is 339 within 360, and the page does not scroll sideways.
- **Dialog, Mama Ade's Kitchen:**
  - The editor was pre-filled with 00:00–00:00 on all 7 days.
  - Monday was set to 09:00–17:00. The save returned 200 STRUCTURED, and the request carried slug `mama-ades-kitchen`, which the server kept.
  - The storefront list then read Monday "09:00–17:00" and every other day "Open 24 hours".
  - **Restored** to the all-day week. Proven by content: the `opening_schedule` md5 is `cfd9a7b6…` before and after, with the same slug and coordinates.
  - At the end no shop is paused, and all 5 schedules are identical by md5.
- **Backstops at 375px:**
  - The two time inputs sit side by side at 132.5px each.
  - The dialog does not overflow (`scrollWidth` 373 = `clientWidth` 373).
  - The 200-character needs-review quote wraps: the line's right edge is 350, inside the dialog's 375.
- **Notices** (shops list rewritten in the browser; nothing written):
  - The home card showed the legacy line and the needs-review line under the ruling.
  - Set opening hours opened that shop's dialog with `document.activeElement` on `opening-hours`, and the URL parameters were dropped.
  - The table showed the Hours not set and Check hours badges.

## Files Created/Modified

- `frontend/components/dashboard/kitchen/pause-orders-control.tsx`: `useShopPause`, `readPauseView`, `pauseControlLabel` and `PauseOrdersControl`.
- `frontend/components/dashboard/kitchen/paused-banner.tsx`: the K3 banner.
- `frontend/app/dashboard/kitchen/page.tsx`: mounts the control after the selector and the banner after the feed banner.
- `frontend/components/dashboard/shops/opening-schedule-editor.tsx`: the K4 editor and `HOURS_COPY`.
- `frontend/app/dashboard/shops/page.tsx`:
  - the editor in the dialog, with `openingSchedule` sent only when touched;
  - the badges and the deep link;
  - the slug kept on save.
- `frontend/components/dashboard/hours-notice-card.tsx` and `frontend/app/dashboard/page.tsx`: the home notice.
- `frontend/lib/opening-schedule.ts`: structured-hours wording and the needs-review days.
- `frontend/lib/shops-api.ts`: `fetchShop`, `pauseShopOrders`, `resumeShopOrders`.
- `frontend/lib/shop-availability.ts`: `formatUkTime`.
- `frontend/types/api.ts`: the ShopDto and CreateShopRequest fields.
- `frontend/app/shop/[slug]/shop-detail-client.tsx`: `hoursListRows` (deviation).
- `37-UI-SPEC.md`: copy rows 458, 791 and 794 amended under the ruling.

## Decisions Made

See `key-decisions`. For the owner, in order:
1. **Wording of the unreadable-day lines** (WINDOWS #36). The orchestrator's text is in place pending your words.
2. **Should a rename move a shop's storefront address at all?** The server still regenerates the slug when a request carries none (deferred-items "From 37-21").
3. **The pause-403 overlay interplay.** A 403 on pause stops the kitchen board; the K3 refusal copy sits behind the overlay.

## Deviations from Plan

### Owner ruling applied (orchestrator copy decision)

**D-25 ruling 2026-10-10.** The must-have and UI-SPEC sentence "This day shows as closed." is untrue: the server keeps those days open. Changes:
- The editor still pre-fills the day as Closed.
- The editor's amber line and the dashboard needs-review card now say customers can still order on those days until the hours are saved.
- Every test asserts the new sentence, and arm 2-E / 3-F prove the old one reds them.
- UI-SPEC rows amended. WINDOWS #36 (deviation, close by the owner's wording).

### Auto-fixed Issues

**1. [Rule 2 - Regression by omission] The storefront Opening hours list showed the old free text**
- **Found during:** Task 2, from deferred-items "From 37-22".
- **Issue:** A STRUCTURED save leaves the old text on the row, and the list printed it beside a pill driven by the new hours.
- **Fix:** `hoursListRows` renders `openingSchedule`.
  - NEEDS_REVIEW keeps the text, because its unreadable days are open on the server.
  - A legacy shop, or one with no schedule, shows its text.
  - The list never renders both.
- **Files:** `frontend/app/shop/[slug]/shop-detail-client.tsx` (added to the plan's file list), plus a new `components/storefront/__tests__/shop-hours-list.test.tsx`.
- **Commits:** `3e10fecc`, `f2557079`.

**2. [Rule 1 - Bug] Every shop dialog save gave the shop a new storefront address**
- **Found during:** preparing the live proof, by reading `ShopService.updateShop`.
- **Issue:** the server regenerates name + a random suffix whenever no slug is sent, and the dialog sent none. Each new hours save would have broken every link to the shop.
- **Fix:** the update sends the current slug unless the name changed. A rename keeps the server's behaviour.
- **Commits:** `a834abcb`, `d5e1490f`.

**3. [Rule 1 - Bug] The pause control claimed "not paused" before the server's read**
- **Found during:** the live run, on a shop left paused by an earlier failed run. The control read "Pause orders", enabled, until the first shop read landed.
- **Fix:** `loaded` from the hook; the control stays disabled until then.
- **Commits:** `69ecdc8b`, `7b80f302`.

**4. [Rule 3 - Instrument] Test fixes before GREEN**
- The in-flight test queried the banner's Resume button while Radix's modal menu hid the page from the accessibility tree. It now passes `hidden: true`. Committed with GREEN `6ddc6e9e` and recorded in the message.
- The RED records were taken after length fixes to two fixture strings (60 and 200 characters, measured with node).

**5. [Instrument] Arm 1-H could not fail the stated-end test**
- The 60 s poll masked it.
- A discriminating test was added (`05961115`), and arm 1-H2 reds it.

**6. [Runtime command] Rebuild command**
- I used `build frontend` + `up -d --no-deps --force-recreate frontend` instead of the orchestrator's `up -d --build frontend`. The project memory and 37-17 record that the latter retags core-java.
- Container recreation and core-java's unchanged image are both recorded.

---

**Total deviations:** 1 owner-ruling copy decision, 2 Rule 1 fixes, 1 Rule 2 delivery, 2 instrument corrections, 1 command substitution.
**Impact on plan:** no scope creep beyond the storefront list (ordered by the orchestrator) and the slug (needed for the new save to be safe). Both are tested and proven live.

## Issues Encountered

- **Two failed live runs** (details in the live-probes notes):
  - The menu rows were measured during Radix's open animation (42.05px is 0.955 × 44).
  - The shop name was read from the board header, which includes "Showing tickets for ". This was a script bug; the product was right.
  - The second failed run left Brixton Village Grill paused until 17:07. The next run resumed it, and the closing DB check shows no shop paused.
- `python3` was not used. Edits went through the 37-22 node applier (`subst.cjs`, exact single-occurrence anchors).

## Known Stubs

None. The three RED stubs were replaced in their GREEN commits (`6ddc6e9e`, `3b5bfa68`, `013eb954`).

## Threat Flags

None outside the plan's threat model:
- **T-37-54** (banner showing a pause the server does not hold) is mitigated: server fields only, a re-read at the end, the control disabled before the first read, arms 1-A, 1-D, 1-H2 and P-A.
- **T-37-55** (conflicting double tap) is mitigated: a ref guard and disabled items, arm 1-C.
- **T-37-SC:** no package installed.
- **New request:** `GET /api/v1/shops/{id}` from the kitchen once a minute. It is the same read-scoped endpoint and grant as the shops page.

## Ledger (WINDOWS)

- **#36 (deviation):** the D-25 copy decision, pending the owner's wording.
- **#37 (unrun-verify):** proven in jest only, or only through a route-rewritten list:
  - the kitchen 403 copy, the in-flight state, the stated-end hide and editor validation;
  - the home pending and failed-load states;
  - real NEEDS_REVIEW and LEGACY shops;
  - the slug fix's live fail direction.

## Next Phase Readiness

- **37-23 UAT:**
  - WINDOWS #35 and #37 rows.
  - Pause from the kitchen while a customer is on checkout.
  - Tap pause twice fast.
  - Provoke the editor's validation by hand.
  - Read the 403 as a revoked STAFF user.
  - Judge the seven "Closes after midnight (next day)" hints under the all-day demo hours.
- **RWO-022 stays open.** 37-23 declares it too: `requirements.ready-ids .../37-21-PLAN.md RWO-022` reports `blocked`. Every implementation truth across 37-19, 37-20, 37-22 and 37-21 is met.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-10*

## Self-Check: PASSED

- Files: all 19 created files listed in key-files exist on disk (`[ -f ]`), and the screens directory holds 13 PNGs.
- Commits: all 13 plan commits are ancestors of HEAD (`git merge-base --is-ancestor`); control: an unknown sha reports MISSING.
- `commits: 13` is measured: `git rev-list --count 0ee80f55..05961115`.
- RED records: all six classify RED_EVIDENCE_OK (re-run at self-check).
- Gate commits: 6 `test(37-21)` RED commits, each before its `feat(37-21)` / `fix(37-21)`, plus 1 test-strengthening commit.
- Break-arms log: 30 restores by content (29 jest arms + the build arm), 0 restore failures, 0 VOID.
- Final tree:
  - jest 2607/220;
  - build rc 0;
  - lint 0 errors;
  - `check-runtime-freshness.sh` PASS 4/4 on frontend `040c64a6` (image tagged after 05961115).
