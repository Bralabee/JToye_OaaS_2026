---
phase: 37-real-world-operations-readiness
plan: 16
subsystem: ui
tags: [kitchen-display, kds, order-state, accept, reject, wcag-2.3.1, rfc7807, next.js, spring-boot]

requires:
  - phase: 37-15
    provides: "the compose stack rebuilt onto this branch (V79, strict scoping on); the 37-B baseline counts"
  - phase: 37-10
    provides: "getKitchenBoard excludes voided orders; a voided order refuses status changes with 400"
provides:
  - "OrderService.KITCHEN_STATUSES and lib/kitchen-orders-api.ts KITCHEN_STATUSES = [PENDING, CONFIRMED, PREPARING, READY], held equal by KitchenStatusesParityTest"
  - "KdsNewLane: the D-11 'New orders' lane with Accept (POST /orders/{id}/confirm) and Reject (POST /orders/{id}/cancel behind ConfirmActionDialog)"
  - "KdsFulfilmentBadge and KdsCustomerNote on every kitchen card (UXT-011)"
  - "CSS animation kds-new-flash: the 1 Hz New-card ring, static under reduced motion"
  - "Insufficient-stock problem members productName and available"
  - "kds-card-parts.tsx: elapsedText, orderLabel, KdsItemList shared by both card kinds"
affects: [37-17, 37-18, 37-23, 37-27, 37-29, 37-30, kitchen-display]

actuals:
  tokens: 40447
  tasks: 3
  commits: 8

plan_head_before: 2eb959cf41d71fab21ea1bf99a34d566a647cbd4
plan_head_after: da1985e0dbf71265416568b5b4bd299e9be1200a

tech-stack:
  added: []
  patterns:
    - "Server/client constant parity asserted by a Java test that parses the TypeScript literal and fails closed on an unparseable or empty list"
    - "A failed transition re-reads the order to tell 'someone else got there first' from a connection fault"
    - "Flash rules asserted from the CSS source: duration, steps, keyframe properties, the only-infinite-kds-animation sweep and the reduce block"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/order/KitchenStatusesParityTest.java
    - frontend/components/dashboard/kitchen/kds-new-lane.tsx
    - frontend/components/dashboard/kitchen/kds-fulfilment-badge.tsx
    - frontend/components/dashboard/kitchen/kds-customer-note.tsx
    - frontend/components/dashboard/kitchen/kds-card-parts.tsx
    - frontend/components/dashboard/kitchen/__tests__/kds-new-lane.test.tsx
    - frontend/components/dashboard/kitchen/__tests__/kds-card-additions.test.tsx
    - frontend/__tests__/kds-flash-css.test.ts
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderController.java
    - core-java/src/main/java/uk/jtoye/core/order/StockService.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/exception/InsufficientStockException.java
    - core-java/src/test/java/uk/jtoye/core/common/InsufficientStockExceptionHandlerTest.java
    - docs/api/openapi-snapshot.json
    - frontend/lib/kitchen-orders-api.ts
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/app/globals.css
    - frontend/e2e/kitchen-flow.spec.ts

key-decisions:
  - "Before the first board read the New lane's row reads 'Checking for new orders…', and 'No new orders waiting' only once a read has said so: the row exists from the first paint (no CLS), but an unchecked 'nothing waiting' would be the #556 untruth"
  - "Print all stays a print of the grid (accepted tickets); a New card keeps its own per-ticket print, so an unaccepted order never reaches the pass in a batch print"
  - "KdsFulfilmentBadge renders nothing for an absent fulfilment type instead of guessing (the printed ticket defaults to COLLECTION; a wrong badge sends food out the wrong door)"
  - "Any server refusal of Accept or Reject re-reads the order: CANCELLED -> already rejected, CONFIRMED/PREPARING/READY/COMPLETED -> already accepted, otherwise the generic copy; the stock copy needs both productName and available, else generic"
  - "The grid card's number row is flex-wrap with the status badge at ml-auto: the live desktop run measured the number squeezed to zero width in a non-wrapping row"
  - "Side effect, left for 37-17: with PENDING in KITCHEN_STATUSES the existing beep fires on DRAFT->PENDING (a new order) and no longer on PENDING->CONFIRMED"

patterns-established:
  - "New kitchen card parts live in components/dashboard/kitchen/, never in the page module (a Next page may only export route members)"

requirements-completed: [RWO-011, RWO-020]

coverage:
  - id: D1
    description: "PENDING is a kitchen status on the server and the client, held equal in content and order by KitchenStatusesParityTest"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/order/KitchenStatusesParityTest.java (2 tests): RED at 54c0a1ca (RED_EVIDENCE_OK, evidence/37-16-red-kitchen-statuses.json), GREEN at a6387764; arm A (PENDING dropped from the TS list) red on both tests, arm A2 (TS order swapped) red on the parity test only; clean 2/0/0"
        status: pass
    human_judgment: false
  - id: D2
    description: "The 'New orders' lane above the grid: PENDING orders oldest first, count badge above 0, a min-h-14 row from the first paint, Accept posts /confirm and moves the card to the grid as Confirmed, 'Accepting…' disables both buttons, one request per press burst, no auto-confirm"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/kitchen/__tests__/kds-new-lane.test.tsx#KDS New lane (5 tests): RED 5/5 at 54c0a1ca (RED_EVIDENCE_OK, evidence/37-16-red-new-lane.json), GREEN at a6387764; arm B (PENDING folded back into the grid) red on 3/5"
        status: pass
      - kind: e2e
        ref: "frontend/e2e/kitchen-flow.spec.ts#a storefront order appears in the New lane, and Accept moves it to the grid: mobile + desktop green on frontend image b2394b8e (evidence/37-16-e2e-kitchen-flow-after-fix.txt, 18/18); red on desktop before da1985e0 (evidence/37-16-e2e-kitchen-flow-before-fix.txt)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every kitchen card (New lane and grid) shows the fulfilment badge (Bike + DELIVERY / Store + COLLECTION, flex-shrink-0) beside a number that wraps whole, and a NOTE FROM CUSTOMER block after the customer name: 16px, pre-wrap, never clamped, text only, absent when empty (UXT-011)"
    requirement: RWO-011
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/kitchen/__tests__/kds-card-additions.test.tsx (7 tests): RED 6/6 at ebfcd32d (RED_EVIDENCE_OK, evidence/37-16-red-card-additions.json), GREEN at a9d5cf92; wrap arm RED at 90d07670 (RED_EVIDENCE_OK, evidence/37-16-red-number-row-wrap.json), GREEN at da1985e0; arms C (line-clamp-3) and E (slice 200) red on the full-length test, D (dangerouslySetInnerHTML) red on the inert-HTML test, F (icons swapped) and G (badge removed from the New card) red on 2"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-16-screens/37-16-kitchen-390.jpeg and 37-16-kitchen-1280.jpeg: real dev-tenant board on the rebuilt frontend, 20 New cards, zeroWidthNumbers 0 at both widths"
        status: pass
    human_judgment: false
  - id: D4
    description: "The New card's NEW strip (static) and the 1 Hz orange-700 ring (box-shadow only, static under prefers-reduced-motion); grid cards never flash"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "frontend/__tests__/kds-flash-css.test.ts (5 tests): RED 5/5 at 65662e60 (RED_EVIDENCE_OK, evidence/37-16-red-flash-css.json), GREEN at 194edd5c; arm H (200 ms, the plan's break arm) and J2 (ease-in-out, not stepped) red on the cycle test, I (background-color in the keyframes) red on ring-only, J (reduce rule removed) red on reduced motion; built CSS read: animation 1s step-end infinite, reduce rule emitted after it"
        status: pass
      - kind: unit
        ref: "kds-new-lane.test.tsx#the NEW strip and the 1 Hz ring (2 tests): the strip test RED at 65662e60; 'grid cards never flash' was vacuously green before the ring existed, its fail direction is arm K (kds-new-flash on the grid card), red"
        status: pass
    human_judgment: true
    rationale: "The ring is motion: jsdom applies no stylesheet and a screenshot cannot show a 1 Hz alternation. The source rule, the built CSS and the class placement are asserted; whether the flash reads as an alert without being distressing on a wall screen is a human look (phase UAT)."
  - id: D5
    description: "Accept failures say why in a role=alert on the card, which stays usable (stock copy from the problem members, already accepted/rejected on another screen via a re-read, generic copy otherwise); Reject asks 'Reject order {number}?' with 'Keep order' and posts /cancel"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "kds-new-lane.test.tsx#Accept says exactly why it failed (4) + Reject order (2): RED at 65662e60 (RED_EVIDENCE_OK, evidence/37-16-red-accept-outcomes.json; the network-copy test already held from Task 1 and is recorded as such), GREEN at 194edd5c; arm L (no re-read) red on both already-moved tests, M (stock branch disabled) red on the stock test, N (Reject posts without the dialog) red on both Reject tests"
        status: pass
    human_judgment: false
  - id: D6
    description: "The insufficient-stock problem (409, type unchanged) carries productName and available as RFC 7807 members, omitted on the retry-exhaustion path"
    verification:
      - kind: unit
        ref: "core-java InsufficientStockExceptionHandlerTest#insufficientStockCarriesProductNameAndAvailableAsProblemProperties, driven through the real StockService refusal: RED at 65662e60 (RED_EVIDENCE_OK, evidence/37-16-red-stock-properties.json), GREEN at 194edd5c; arm O (productName setProperty dropped) red; clean 2/0/0"
        status: pass
    human_judgment: false
  - id: D7
    description: "Runtime parity: core-java and frontend rebuilt and recreated onto this branch; the live API and bundle carry the change"
    verification:
      - kind: other
        ref: "scripts/check-runtime-freshness.sh rc=1 before (core-java, frontend image-not-rebuilt at 194edd5c), rc=0 after both rebuilds (4 running, 0 unverified); container image id = tag id (core-java 94e8e7b2, frontend b2394b8e); live /v3/api-docs kitchen description reads PENDING/CONFIRMED/PREPARING/READY; check-openapi-snapshot-fresh.sh PASS 0 differences across 120 paths; the running frontend bundle contains 'No new orders waiting', kds-new-flash and the flex-wrap row; seed-order-metric.sh PASS after the rebuild"
        status: pass
    human_judgment: false

duration: 56min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 16: Kitchen New Lane and Card Additions Summary

**Storefront orders now reach the kitchen. They wait in a "New orders" lane, where the kitchen accepts them with the existing `/confirm` or rejects them with `/cancel` after a confirm step. Every kitchen card now shows the customer's note and DELIVERY or COLLECTION, and a failed accept says exactly why. A Java test parses the TypeScript constant so the server and client status lists stay equal.**

## Performance

- **Duration:** 56 min (start 2026-10-09T09:42:23Z, end 2026-10-09T10:38:08Z; the core-java integration suite alone took 29 min)
- **Tasks:** 3 of 3 (Task 1 is a tracer: re-verified end-to-end, then expanded)
- **Files:** 27 changed (8 created source/test files, 11 modified, plus evidence)

## Accomplishments

- **D-11, UXT-020.** PENDING is now a kitchen status on both sides. A storefront order arrives over the existing DRAFT->PENDING frame or the next read, and it lands in the lane, oldest first. "Accept order" moves it to the grid as Confirmed. Nothing confirms on its own.
- **UXT-011, P0.** The cook now sees the customer's note ("severe peanut allergy" in the persona repro) and how the order leaves, on every card. The note is never clamped and renders as text only.
- **WCAG 2.3.1.** On a New card only the ring flashes, at 1 Hz, and it stops under reduced motion. The strip, the allergen banner and the text never flash.
- **Failure copy.** A stock refusal names the product and how many are left, using the new problem members rather than the prose. A refusal caused by another screen says so. Anything else says to check the connection and try again.
- **Goods kept.** The allergen banner, per-item badges, allergy-note block, Print all and per-ticket print, the big bump buttons, the ember glow, the #536 board reserve and the one-shop notice are all unchanged in position and behaviour. Every existing kitchen jest suite stays green: page, allergen-surfacing, allergy-note-board, kds-board-scope and kitchen-ticket.

## Task Commits

1. **Task 1 (tracer): PENDING reaches the New lane and Accept confirms it.** RED `54c0a1ca`, GREEN `a6387764`.
2. **Task 2: the customer's note and the fulfilment type on every card.** RED `ebfcd32d`, GREEN `a9d5cf92`.
3. **Task 3: the NEW strip, the 1 Hz ring, and Accept/Reject outcomes.** RED `65662e60`, GREEN `194edd5c`.
4. **Fix found by the live desktop run: the number row wraps.** RED `90d07670`, fix `da1985e0`.

The plan metadata commit follows this SUMMARY.

## TDD Gate Compliance

| Task | RED (evidence, classifier) | GREEN | REFACTOR |
|------|----------------------------|-------|----------|
| 1 | `54c0a1ca`: JUnit 1/2 (`37-16-red-kitchen-statuses.json`) and TAP 5/5 (`37-16-red-new-lane.json`), both RED_EVIDENCE_OK | `a6387764` | not needed |
| 2 | `ebfcd32d`: TAP 6/6 (`37-16-red-card-additions.json`), RED_EVIDENCE_OK | `a9d5cf92` | not needed |
| 3 | `65662e60`: TAP 12/18 (`37-16-red-flash-css.json`, `37-16-red-accept-outcomes.json`) and JUnit 1/2 (`37-16-red-stock-properties.json`), all RED_EVIDENCE_OK | `194edd5c` | not needed |
| fix | `90d07670`: TAP 1/7 (`37-16-red-number-row-wrap.json`), RED_EVIDENCE_OK | `da1985e0` (fix) | not needed |

**Semantic assessment (RED).** In every record the named target ran and failed on its planned assertion. The failures were:

- the server list lacks PENDING;
- there is no "New orders" region;
- the New card has no note block;
- there is no `.kds-new-flash` rule;
- a typed 409 got the generic copy;
- `$.productName` is absent although type and title matched;
- the number row is not `flex-wrap`.

Two Task 3 lane tests were already green on the RED tree, and that is recorded rather than hidden. The network copy shipped in Task 1. "Grid cards never flash" cannot fail before a flash exists, so its fail direction is break arm K.

## Verification

**Tracer `<verify>`, re-run after commit.**

- `KitchenStatusesParityTest`: 2/0/0.
- `OrderServiceTest`: 38/0/0. Fresh XML, tests > 0.
- `npm --prefix frontend test -- components/dashboard/kitchen app/dashboard/kitchen`: 161 passed.
- ⚡ Tracer verified end-to-end — expanding.

**Plan `<verification>`.**

- The parity test and the lane, card and flash suites went RED then GREEN, with the break arms above.
- `npm run build` is green.
- `kitchen-flow.spec.ts` is updated. It was also run here: see below.

**Success criteria.** UXT-011's repro (note and fulfilment hidden on the kitchen screen) failed before and passes after: `kds-card-additions` RED 6/6 then GREEN. UXT-020's lane half failed before and passes after: `kds-new-lane` RED 5/5 then GREEN, and the e2e passes on both projects.

**Acceptance criteria, both directions.**

- Parity break arm: dropping PENDING from the TS list turned both tests red.
- The lane, card-additions and flash suites went RED then GREEN, with the full-length and inert-HTML arms each turning red under their own break.
- The 200 ms flash arm was red.
- Each Accept outcome is asserted by exact copy.
- `contrast-tokens.test.ts` passes unchanged. No CSS variable moved, and `globals.css` adds only `.kds-new-flash`, its keyframes and a reduce block.

**Every break arm was restored and checked by content.** Each restore was verified by sha256 against a scratch backup, with a clean run afterwards. `git status` was clean after every arm set.

**Full suites, final tree.**

- **core-java unit:** 1624 tests, 0 failures, 0 errors, 1 skipped. Baseline was 1621, plus 2 parity tests and 1 stock test.
- **core-java integration:** 1006 tests, 0 failures, 0 errors, 1 skipped. This includes `OpenApiSnapshotTest`, green against the edited snapshot.
- **frontend jest:** 2408 tests passed in 208 suites. `docs/metrics.json` says 2383; the 25 new tests are 13 lane, 7 card-additions and 5 flash. `docs/metrics.json` drift belongs to the sub-theme gate plan.
- **frontend lint:** 0 errors, 31 warnings. Baseline was 32; an unused local was removed from the kitchen page.
- **frontend build:** rc 0. "Finished TypeScript" ran, and the built chunk and CSS contain the new code.

**Live run on the rebuilt stack.** The orchestrator allowed it. The plan scheduled this run for 37-23, but it was run here and found a defect.

- **Before the fix:** `kitchen-flow.spec.ts` failed 4 tests on desktop and passed on mobile. The card's order-number `h3` resolved but was "hidden".
- **After `da1985e0`:** 18 of 18 passed on both projects, against frontend image `b2394b8e`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The order number was squeezed to zero width on desktop cards**

- **Found during:** the post-task live run of `kitchen-flow.spec.ts` (desktop project, 1280px, four columns).
- **Issue:** In the non-wrapping header row, `min-w-0` let flex shrink the number to 0px between the DELIVERY badge and the status badge. `truncate`'s overflow:hidden then hid it. The UI-SPEC long-text row says this must never happen, and jsdom could not see it.
- **Fix:** The number row is `flex-wrap` on both card kinds, and the status badge uses `ml-auto`. A jest arm now pins the structural cause.
- **Files modified:** `frontend/app/dashboard/kitchen/page.tsx`, `frontend/components/dashboard/kitchen/kds-new-lane.tsx`, `kds-card-additions.test.tsx`.
- **Commits:** `90d07670` (RED), `da1985e0`.

**2. [Rule 1 - Bug] The New-lane e2e stub kept serving PENDING after /confirm**

- **Found during:** the same run.
- **Issue:** The socket's reconnect re-read the board, and the stub returned PENDING again. That put the accepted card back in the lane and failed a correct page.
- **Fix:** The stub is stateful (CONFIRMED once /confirm has answered), as the server is.
- **Files modified:** `frontend/e2e/kitchen-flow.spec.ts`.
- **Commit:** `da1985e0`.

**3. [Rule 1 - Bug] The kitchen endpoint's OpenAPI description became false**

- **Found during:** Task 1.
- **Issue:** It said "active (CONFIRMED/PREPARING/READY)".
- **Fix:** The `@Operation` description and the committed `docs/api/openapi-snapshot.json` now name PENDING. `OpenApiSnapshotTest` is green, and the live snapshot gate passes with 0 differences across 120 paths.
- **Commit:** `a6387764`.

**4. [Rule 3 - Blocking] The stock members needed a carrier**

- **Found during:** Task 3.
- **Issue:** `InsufficientStockException` had no fields, and it is not in the plan's file list.
- **Fix:** Added nullable `productName` and `available`, with the message-only constructor kept. `StockService` passes `product.getTitle()` and the current count.
- **Commit:** `194edd5c`.

**5. [Rule 1 - Test harness] The handler test's mapper lacked Boot's `ProblemDetailJacksonMixin`**

- **Found during:** Task 3 GREEN.
- **Issue:** Without the mixin, the test serialised extension members nested under `properties`, which is not the shape the running app emits.
- **Fix:** The mixin is registered, as `ProblemDetailAuthenticationEntryPointTest` already does. The RED stands, because the members were absent in either shape. Arm O re-proved the fail direction with the mixin in place.
- **Commit:** `194edd5c`.

**6. [Structure] `elapsedText` and the item list moved to `kds-card-parts.tsx`**

- **Issue:** A Next page module cannot export them, and the New card needs them.
- **Fix:** Moved them with the same markup and test ids.
- **Commit:** `a6387764`.

**Total deviations:** 6 auto-fixed (4 Rule 1, 1 Rule 3, 1 structural). **Impact:** all are within the plan's surfaces, and none widens scope. Deviation 1 is a P0-adjacent readability defect that would have shipped: every jsdom gate was green.

## Issues Encountered

- **The dev tenant's lane holds 20 stale PENDING orders.** Many are `seed-order-metric.sh` probes up to 39 days old, and each one flashes. This is D-11 working as specified, but it matters for 37-17's repeating alert and for any screenshot or persona pass. It is recorded in `deferred-items.md` with a suggestion. Nothing was filed: it breaks no gate, loses no data, and is a dev-data observation.
- **Pre-existing:** the 20px "ALLERGENS" label of `OrderAllergenBanner` touches its box edge on a 4-column desktop card. The banner is untouched by this plan (L6). Logged in `deferred-items.md` as measure-before-filing.

## Known Stubs

None. The Reject button rendered in Task 1 without its dialog was wired in Task 3 (`194edd5c`) of this same plan.

## Threat Flags

None. No new endpoint, auth path or schema change.

- **T-37-42 (stored XSS through notes) is mitigated.** Notes render as a React text node, and the inert-HTML arm proves it: `dangerouslySetInnerHTML` turned it red.
- **T-37-43 is unchanged.** Accept and Reject use the existing `/confirm` and `/cancel`, which keep STAFF-on-shop plus `SCOPE_orders:write`.
- **The new problem members** name a product and a count already in the prose `detail` sent to the same caller.
- **No package was installed (T-37-SC).**

## Next Phase Readiness

- **For 37-17 (repeating alert):** the lane's PENDING set is available as `newOrders` in `page.tsx`. The flash already runs whether or not sound is muted. Replace the per-beep `playBeep`; it now fires on DRAFT->PENDING.
- **For 37-23 (sub-theme gate):** the runtime is fresh at `da1985e0` for core-java and frontend, and `kitchen-flow.spec.ts` is green live on both projects. Re-run it there anyway after any later rebuild.
- **For 37-27 to 37-30 (reasons and bulk reject):** `KdsNewCard`'s Reject is the single-order path to extend. The D1 body copy still omits the reason sentence.
- **Requirements:** RWO-011 is also declared by 37-23, and RWO-020 by 37-17 and 37-23. `requirements.ready-ids` returned 0/2, so neither is marked complete here.

## Self-Check: PASSED

- FOUND: every `key-files.created` path, and `evidence/37-16-red-{kitchen-statuses,new-lane,card-additions,flash-css,accept-outcomes,stock-properties,number-row-wrap}.json`, `evidence/37-16-e2e-kitchen-flow-{before,after}-fix.txt` and `evidence/37-16-screens/` (2 JPEGs).
- FOUND (ancestors of HEAD): `54c0a1ca`, `a6387764`, `ebfcd32d`, `a9d5cf92`, `65662e60`, `194edd5c`, `90d07670`, `da1985e0`.
- MEASURED: `git rev-list --count 2eb959cf..da1985e0` = 8.
