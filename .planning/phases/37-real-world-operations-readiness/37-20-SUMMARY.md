---
phase: 37-real-world-operations-readiness
plan: 20
subsystem: api
tags: [spring-boot, storefront, opening-hours, pause, europe-london, dst, rfc7807, openapi, testcontainers]

requires:
  - phase: 37-19
    provides: "V80 columns (opening_schedule, hours_source, orders_paused_until, orders_paused_indefinitely), OpeningSchedule/TimeWindow, HoursSource, the V80 backfill classes"
provides:
  - "ShopAvailabilityPolicy: the one open/paused/closed decision (evaluate, requireTakingOrders; Reason PAUSED | CLOSED_NOW | CLOSED_TODAY | NO_HOURS), UK wall-clock time, overnight carry, 7-day horizon, both clock changes"
  - "ShopClosedException -> 409 https://jtoye.uk/errors/shop-closed, code SHOP_CLOSED, reason, reopensAt, human detail"
  - "PUT /api/v1/shops/{id}/pause {minutes: 20|40} | {untilReopened: true} and DELETE /api/v1/shops/{id}/pause, require(shopId, STAFF), absolute writes"
  - "PublicShopDto: openNow, nextOpenAt, pausedUntil, pausedIndefinitely, openingSchedule, legacyAlwaysOpen, hoursSource"
  - "ShopDto (NON_NULL): hoursSource, legacyOpeningHours, openingSchedule, ordersPausedUntil, ordersPausedIndefinitely"
  - "CreateShopRequest.openingSchedule: save -> STRUCTURED, {} -> closed every day, absent -> keep; InvalidOpeningScheduleException -> 400 errors.openingSchedule.<day>"
affects: [37-21, 37-22, 37-23, storefront, kitchen, dashboard-shops]

actuals:
  tokens: 30183
  tasks: 3
  commits: 7
plan_head_before: 7b6173b150e9db09ee8a9f6a172714c0010dd45a
plan_head_after: 935bf0a6b12874324ebf8fe2d6ddca567d8fc06d

tech-stack:
  added: []
  patterns:
    - "One availability policy, called by the write gate and mapped onto every public read, so no client derives open/closed"
    - "Availability evaluated in UK wall-clock time from an injected Clock; unit cases on FIXED UTC clocks with UK expectations, so a UTC evaluation reds the BST cases"
    - "A refusal thrown before the idempotency reservation, asserted by the database (no order row, no reservation), never by status alone"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/storefront/ShopAvailabilityPolicy.java
    - core-java/src/main/java/uk/jtoye/core/exception/ShopClosedException.java
    - core-java/src/main/java/uk/jtoye/core/exception/InvalidOpeningScheduleException.java
    - core-java/src/main/java/uk/jtoye/core/shop/dto/PauseOrdersRequest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/ShopAvailabilityPolicyTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopDtoScheduleCacheRoundTripTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-20-red-pause.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-20-red-hours-unit.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-20-red-hours-it.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-20-red-dto-schedule.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopController.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopMapper.java
    - core-java/src/main/java/uk/jtoye/core/shop/dto/ShopDto.java
    - core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopReservedSlugAccountTest.java
    - "eight guest-order ITs (fixture shop hours_source): AllergyNoteAck, OrderAllergenSnapshot, CustomerRecordedAllergenSet, GuestCheckoutIdempotency, GuestCheckoutOnlinePayment, GuestCheckoutStockConvergence, GuestOrderAllergenAck, MoneyPathExecution"
    - docs/api/openapi-snapshot.json

key-decisions:
  - "D-25 clarified 2026-10-10: NEEDS_REVIEW days stay open until the vendor saves structured hours; 37-20 must-have line 75 rewritten from 409 to 201"
  - "The ruling is applied PER DAY: a NEEDS_REVIEW day whose text V80 could not read is open all day (the pre-D-15 fail-open); the days V80 did read keep their windows, and a day written 'closed' or left out stays closed, which is what the server did before deploy (the ruling's own words: 'take orders exactly as before deploy'). A whole-shop 'NEEDS_REVIEW = open' reading would have opened days the vendor wrote as closed, which the pre-deploy server refused"
  - "A shop with no hours_source and no schedule (created after deploy, never saved hours) is CLOSED, NO_HOURS (D-25: D-15 applies to every shop created after deploy). The application never writes LEGACY_ALWAYS_OPEN; only V80 does"
  - "MIGRATED_FROM_TEXT is evaluated against its windows, like STRUCTURED (its text was fully readable and enforced before deploy)"
  - "A timed pause reopens at its end if the shop is open then, otherwise at the next window after it; an ended pause is simply no pause (no clean-up job)"
  - "The pause is gated at STAFF (the plan's must-have), not SHOP_MANAGER: the kitchen is who knows it is overwhelmed; resume is the same gate"
  - "The pause end is stored in whole seconds, so the PUT response equals every later read (PostgreSQL keeps microseconds)"
  - "validateShopIsOpen was deleted in Task 2, not Task 1: Task 1 added the pause gate in front of it so every task stayed green; the end state is the plan's"
  - "MCP gets no pause tool (operational kitchen control stays human-in-the-loop); vendor, REST and MCP orders are not blocked by a pause (a phone order is taken by a shop manager: vendor POST /orders needs SHOP_MANAGER, OrderService.java:141)"

patterns-established:
  - "Break arm on a skipped downstream task is a stale artifact: when a Gradle unit task fails, the integration task in the same invocation never runs and its old XML stays on disk; run the IT arm in its own invocation"

requirements-completed: []

coverage:
  - id: D1
    description: "A paused shop refuses storefront orders with a typed 409 shop-closed PAUSED before any write (no order, no reservation); resume lets the same key through; an ended pause refuses nothing"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#UXT-022: STAFF pauses 20 minutes -> a guest order is refused 409 shop-closed PAUSED with reopensAt and writes nothing; DELETE pause -> the SAME key is accepted 201"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#UXT-022: a shop whose pause columns are set refuses storefront orders (the storefront reads the pause, it does not only store it)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#UXT-022: 'until reopened' pauses indefinitely -> 409 PAUSED with no reopensAt; a repeat PUT and a repeat DELETE are absolute (same state)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The pause endpoint is gated (403 for ungranted and other-shop staff), takes only 20, 40 or until-reopened (typed 400 otherwise), and does not block vendor orders"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#T-37-51: a user with no grant on the shop cannot pause it (403 shop-access-denied); a STAFF grant on ANOTHER shop is not enough; nothing changes"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-15: only 20 or 40 minutes, or until reopened: 15 minutes, an empty body and an ambiguous body are typed 400s and change nothing"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#Recorded reason: a vendor POST /api/v1/orders (a phone order taken by staff) is NOT blocked by a pause -> 201"
        status: pass
    human_judgment: false
  - id: D3
    description: "Structured hours decide open and closed in UK time: windows, overnight carried past midnight, 7-day horizon, NO_HOURS, both 2026 clock changes; legacy shops per D-25 and the 2026-10-10 ruling"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopAvailabilityPolicyTest.java (25 cases, 5 nested classes)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-15: a STRUCTURED shop closed every day refuses a guest order 409 shop-closed NO_HOURS (was 201: no hours meant always open); CONTROL: 24-hour windows -> 201"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-25 (owner ruling 2026-10-10): a NEEDS_REVIEW shop's unreadable day stays OPEN until the vendor saves structured hours -> 201, as before deploy"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-25: a NEEDS_REVIEW shop's day written 'Closed' stays closed -> 409 shop-closed CLOSED_TODAY (was a 400 invalid-argument)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java (the four hours arms, fixed clock)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Every public shop read carries the server's answer (openNow, nextOpenAt, pausedUntil, pausedIndefinitely, openingSchedule, legacyAlwaysOpen, hoursSource)"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#theme 2: the public shop carries the policy's answer: paused -> openNow false with pausedUntil and nextOpenAt; resumed -> openNow true"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-25: the public shop says when hours are legacy (legacyAlwaysOpen, no schedule) or under review (hoursSource), and a closed STRUCTURED shop reads openNow false"
        status: pass
    human_judgment: false
  - id: D5
    description: "Vendors save structured hours (STRUCTURED; {} closed every day; absent keeps), invalid schedules are 400s naming the day, ShopDto exposes the hours and pause state, and a created shop is never LEGACY_ALWAYS_OPEN; the cached ShopDto round-trips"
    requirement: RWO-022
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-15: a vendor saving a schedule makes the shop STRUCTURED; {} saves closed every day; a save without the field keeps schedule and source"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#D-15: overlapping windows or more than 3 a day are a 400 naming the day, and nothing is saved"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopPauseIntegrationTest.java#ShopDto exposes hoursSource, the original text as legacyOpeningHours (for the NEEDS_REVIEW notice) and the pause fields; a new shop saved without hours is unclassified, never LEGACY_ALWAYS_OPEN"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/shop/ShopDtoScheduleCacheRoundTripTest.java (2)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java + ShopControllerIntegrationTest.java"
        status: pass
    human_judgment: false
  - id: D6
    description: "The delivered runtime: the compose core-java does not contain this plan (not rebuilt, by choice); live proof of the pause, the 409 and the new fields is owed at the next rebuild"
    requirement: RWO-022
    verification: []
    human_judgment: true
    rationale: "Proven in Testcontainers only; the running stack predates V80 and 37-20 (WINDOWS #30, #33). Runtime parity is a phase-end gate (check-runtime-freshness.sh, check-openapi-snapshot-fresh.sh)"

duration: 67min
completed: 2026-10-10
status: complete
---

# Phase 37 Plan 20: Pause and structured-hours enforcement Summary

**A vendor can now pause storefront orders in one call (20 min, 40 min or until reopened), and a single `ShopAvailabilityPolicy` decides in UK time, from structured hours, whether a shop takes an order. The checkout refuses with a typed 409 `shop-closed` before any write. Every public shop read carries the server's answer. The free-text fail-open is gone, except where the owner's 2026-10-10 D-25 ruling keeps an unreadable legacy day open until its vendor saves hours.**

## Performance

- **Duration:** 67 min
- **Started:** 2026-10-10T12:13:02Z
- **Completed:** 2026-10-10T13:20Z
- **Tasks:** 3 of 3, each RED then GREEN (no REFACTOR commit; nothing needed cleanup)
- **Files:** 29 changed outside `.planning` (+2230 / -119), plus 4 RED evidence records

## Accomplishments

- **One decision.** `ShopAvailabilityPolicy.evaluate(shop)` returns `openNow`, a `reason`, `nextOpenAt`, `pausedUntil` and `pausedIndefinitely`. It is evaluated in Europe/London wall-clock time from the injected `Clock`. It uses today's windows plus yesterday's overnight window, searches the next 7 days for the next opening, and does not throw on either clock change. Rules by `hours_source`:
  - `LEGACY_ALWAYS_OPEN`: open (D-25).
  - `NEEDS_REVIEW`: an unreadable day is open all day (owner ruling). Read days and days written closed are enforced as before deploy.
  - `STRUCTURED`, `MIGRATED_FROM_TEXT` and unclassified shops: open only inside a window. Empty or no windows is `NO_HOURS` (D-15).
  - A pause wins over all of these.
- **The checkout gate.** `createGuestOrder` calls `requireTakingOrders` where `validateShopIsOpen` was (which is now deleted), before the idempotency reservation. A refusal is 409 `https://jtoye.uk/errors/shop-closed` with `code` `SHOP_CLOSED`, `reason` and `reopensAt`. The detail is human wording, e.g. "Mama Put isn't taking orders (closed right now). Reopens on Tuesday at 09:00.". Vendor, REST and MCP orders do not call the gate.
- **The pause endpoints.** `PUT /api/v1/shops/{id}/pause` takes `{minutes: 20|40}` or `{untilReopened: true}`; `DELETE` resumes. Both are gated `require(shopId, STAFF)` and are absolute writes (a retry cannot stack pauses). Any other choice is a typed 400 `errors.oneValidChoice`. Each write evicts the cached ShopDto and is audited.
- **The server's answer on every read.** `PublicShopDto` gains `openNow`, `nextOpenAt`, `pausedUntil`, `pausedIndefinitely`, `openingSchedule`, `legacyAlwaysOpen` and `hoursSource` (the NEEDS_REVIEW state, per the ruling). These are computed per request and never cached.
- **Vendors save structured hours.** `CreateShopRequest.openingSchedule`:
  - A save makes the hours `STRUCTURED`. `{}` saves closed every day. An absent field keeps the stored schedule and source.
  - An unknown day, a missing time, an overlap, or more than 3 windows on a day is a 400 with `errors.openingSchedule.<day>`, refused before any field is copied.
  - `ShopDto` exposes `hoursSource`, `legacyOpeningHours` (the original text), `openingSchedule` and the pause fields. These are `NON_NULL`, so the Phase 38 golden is untouched.
  - The mapper never maps the schedule, its source or the pause from a request.
- **Fresh volumes keep working.** On a fresh volume, `DemoDataSeeder` gives the curated demo shops all-day STRUCTURED hours (create-only), so the demo storefront and the nightly e2e still take orders. Without this, the seeder would have created them unclassified and closed.

## Task Commits

1. **Task 1 (tracer): a paused shop refuses storefront orders with a typed reason**
   - RED `d44576d5`. GREEN `af1fb959`.
   - ⚡ Tracer verified end-to-end, then expanded. The tracer `<verify>` was re-run on the committed tree after the break arms: 6/6.
2. **Task 2: structured hours decide open and closed, including legacy, overnight and DST**
   - RED `a2320ed4`. GREEN `37d9bcbc`.
3. **Task 3: the storefront DTO carries the server's answer, and vendors save structured hours**
   - RED `813ecba8`. Fix `24d22a2c` (see Deviations). GREEN `935bf0a6`.

## TDD Gate Compliance

| Task | RED | GREEN | Evidence record | Classifier |
|------|-----|-------|-----------------|------------|
| 1 | `d44576d5` | `af1fb959` | `evidence/37-20-red-pause.json` | RED_EVIDENCE_OK |
| 2 (unit) | `a2320ed4` | `37d9bcbc` | `evidence/37-20-red-hours-unit.json` | RED_EVIDENCE_OK |
| 2 (IT) | `a2320ed4` | `37d9bcbc` | `evidence/37-20-red-hours-it.json` | RED_EVIDENCE_OK |
| 3 | `813ecba8` | `935bf0a6` | `evidence/37-20-red-dto-schedule.json` | RED_EVIDENCE_OK |

How each RED was judged:

- **Task 1:** 6 of 6 tests red.
  - Target: a shop paused in the database took a guest order (201).
  - The five endpoint arms got the router's 404 for `/pause`.
  - The fixture reached the controller in every arm.
- **Task 2, unit:** policy 16 of 25 red, storefront 3 of 44 red, all on assertions.
  - Target: Mon 18:00 reported open, `reason` null.
  - The cases that passed in RED pass for a stated reason: open-inside-window, LEGACY, the NEEDS_REVIEW unreadable day, and the Task 1 pause cases.
- **Task 2, IT:** 2 of 9 red.
  - Target: a STRUCTURED all-closed shop took the order (201).
  - The NEEDS_REVIEW closed-day arm got the old 400.
  - The NEEDS_REVIEW unreadable-day arm passed in RED by design: the ruling made it a guard for behaviour that already exists. Its fail direction is arm B below.
- **Task 3:** 5 of 14 red.
  - Target: a schedule save left the shop LEGACY_ALWAYS_OPEN.
  - The other 4: no DTO fields, and an overlap accepted with 200.
  - The 9 earlier arms stayed green.

## Acceptance criteria and break arms (both directions recorded)

Every arm ran clean, then broken, then clean. Each restore was checked by sha256 and `git diff --quiet HEAD`. Every arm ran on a committed tree.

| # | Break | Fail direction (measured) | Pass direction |
|---|---|---|---|
| 1-A | `requireTakingOrders` call commented out | 3 of 6 red, each `HTTP 201 {orderNumber...}` where 409 was expected (pause20, indefinite, DB-paused) | 6/6 |
| 1-B | `require(shopId, STAFF)` removed from `pauseOrders` | the T-37-51 arm red: `HTTP 200 {ShopDto}` where 403 was expected | 6/6 (also the tracer re-run) |
| 2-A | `UK_ZONE = UTC` (the plan's own arm) | 7 of 25 red: the two clock-change cases, Mon 09:30 BST, Mon 16:30/17:30 BST, the overnight case, the pause end after closing time, and `closedThrows` | 25/25 |
| 2-D | yesterday's overnight carry removed | 1 of 25 red (Fri 23:30 / Sat 01:30) | 25/25 |
| **2-B** | **the owner's fail direction: NEEDS_REVIEW treated as STRUCTURED** | **IT: the D-25 arm red with `HTTP 409 ... isn't taking orders (closed today). Reopens on Sunday at 00:00.`.** Unit: both NEEDS_REVIEW unreadable-day cases red | 14/14, 25/25 |
| 2-C | the guest-order fixture shop left unclassified (`hours_source` NULL) | GuestOrderAllergenAckIntegrationTest 9 of 10 red with `409 ... (no opening hours are set)`. This proves D-15 closes an unclassified shop, and that the fixture change was load-bearing | 10/10 |
| 3-E | `createShop` writes LEGACY_ALWAYS_OPEN when no hours are sent | the writer check FAILs (rc 1) and names the line. IT: the ShopDto arm red, `expected: null but was: "LEGACY_ALWAYS_OPEN"` | writer check PASS, 14/14 |
| 3-F | a save does not set STRUCTURED | 2 of 14 red (the vendor-save arm and the ShopDto arm) | 14/14 |
| 3-H | `setOpenNow(true)` always | 2 of 14 red (the paused public read and the closed STRUCTURED read) | 14/14 |
| 3-G | cache allowlist without `java.util.` | ShopDtoScheduleCacheRoundTripTest 2/2 red: `Could not resolve type id 'java.util.LinkedHashMap'` | 2/2 |

**One arm was measured on a stale artifact, then re-run.** The first run of 2-B passed the unit task and the IT task in one Gradle invocation. The unit task failed, so Gradle never ran the IT task, and the three IT XMLs it read were the GREEN run's, left on disk. The arm was re-run alone, and the 409 above is from a fresh XML (timestamp 12:32:30Z, one file in the directory). Arm 1-B's first run, and arm 3-E's first run, did not apply their edit (wrong `node` path, then an anchor mismatch). They were caught by the `edit rc` line, the clean runs were recorded as such, and both arms were re-run.

**The Task 3 acceptance grep, as written, cannot pass on a correct tree.**
- The grep was `rg -uu -n 'LEGACY_ALWAYS_OPEN' core-java/src/main/java | rg -v 'HoursSource.java|ShopAvailabilityPolicy.java'` prints nothing.
- It counts MENTIONS, not writers. On the pre-plan tree `7b6173b1` it already printed 3 lines: 37-19's `OpeningSchedule.fromLegacy` and the `Shop.java` Javadoc.
- On the final tree it prints 8, all of them readers, Javadoc or comments.
- It was replaced by a writer check, which is strictly stronger. Every `setHoursSource(` in main code that is not a pure copy (`x.setHoursSource(y.getHoursSource())`) must write `HoursSource.STRUCTURED`. No migration other than V80 may name LEGACY_ALWAYS_OPEN. The check is VOID (exit 2) when it finds no writer.
- Result: PASS on the final tree (two writers, `ShopService.applySchedule` and `DemoDataSeeder.applySeededHours`), and FAIL in arm 3-E.

**OpenAPI.**
- The regenerated snapshot is purely additive: `git diff --numstat` reports 274 added, 0 removed.
- `scripts/openapi-gate.sh` exits 0 on the new snapshot and 1 on the old one.
- `oasdiff` is not installed here, so its breaking/non-breaking classification was not run. It is additive by count.

## Plan-level verification

1. ShopPauseIntegrationTest, ShopAvailabilityPolicyTest and PublicStorefrontServiceTest each went RED then GREEN, with break arms (above).
2. ShopControllerIntegrationTest 6/6 and OpenApiSnapshotTest 1/1 are green, both in the Task 3 run after the regeneration and in the full suite.
3. Full core-java suites at `935bf0a6`, both fresh `clean*` runs:
   - Unit: **1682 tests / 0 failures / 0 errors / 1 skipped** (196 XML files), against a baseline of 1655 (+25 ShopAvailabilityPolicyTest, +2 ShopDtoScheduleCacheRoundTripTest). BUILD SUCCESSFUL in 1m 16s.
   - Integration: **1068 / 0 / 0 / 1 skipped** (192 XML files), against a baseline of 1054 (+14 ShopPauseIntegrationTest). BUILD SUCCESSFUL in 26m 31s.

Success criterion (UXT-022's server half): before this plan there was no pause, and an unclassified or closed shop took an order (201). After it, both are refused 409. Shown by the RED records and the GREEN runs above.

## Decisions Made

See `key-decisions`. In short:

- **The D-25 ruling is applied per day.** An unreadable NEEDS_REVIEW day stays open. A day the vendor wrote as closed stays closed, as it did before deploy.
- **An unclassified shop is closed (NO_HOURS).** Only V80 writes LEGACY_ALWAYS_OPEN.
- **The pause is gated at STAFF.** Pause writes are absolute and stored in whole seconds.
- **A timed pause reopens at its end, or at the next window after it.**

## Deviations from Plan

### Owner ruling applied (binding override)

**D-25 clarified 2026-10-10.** The must-have at PLAN line 75 ("NEEDS_REVIEW day -> 409 CLOSED_TODAY") was rewritten to 201. The arm was kept as the D-25 regression guard in the other direction. Its fail direction, NEEDS_REVIEW treated as STRUCTURED, reds with 409 (arm 2-B). `PublicShopDto` and `ShopDto` carry `hoursSource`, so the vendor notice still shows.

**How the ruling was read.** The ruling's bullet reads "NEEDS_REVIEW -> open". Its stated intent is "take orders exactly as before deploy". These differ on days the vendor wrote as `closed`: the pre-deploy server refused orders on those days. The per-day reading honours the intent and only widens what was already open. If the owner meant the whole shop open, the change is one line in `ShopAvailabilityPolicy.withUnreadableDaysOpen`, and the unit case `needsReview_readDaysAsBefore` would need to flip.

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Eight guest-order ITs seeded shops that D-15 now closes**
- **Found during:** Task 2
- **Issue:** These ITs seed shops by SQL with no hours and place real storefront orders. After D-15 such a shop is unclassified and closed.
- **Fix:** Each fixture INSERT now carries `hours_source = 'LEGACY_ALWAYS_OPEN'`, which is what V80 makes of an hours-less pre-existing shop. Stale comments naming `validateShopIsOpen` were updated.
- **Proof the change was needed:** arm 2-C.
- **Commit:** `37d9bcbc`

**2. [Rule 2 - Missing critical / regression by omission] Demo storefronts on a fresh volume**
- **Found during:** Task 2
- **Issue:** `DemoDataSeeder` creates the curated shops after V80 on a fresh volume (the nightly e2e's `down -v`). They would have been unclassified, so closed, so every order-placing journey would fail. The research had flagged the demo and e2e shops; the plan's file list missed the seeder.
- **Fix:** Create-only all-day STRUCTURED hours. Existing dev rows are untouched (V80 classified them).
- **Check:** `scripts/seed-e2e-fixtures.sh` creates no shops (searched, rc 1), so the seeder covers e2e.
- **Commit:** `37d9bcbc`

**3. [Rule 1 - Bug] ShopReservedSlugAccountTest red since Task 1**
- **Found during:** Task 3
- **Issue:** Task 1 added `Clock` to the `ShopService` constructor. This test builds the service in an `ApplicationContextRunner` without a Clock bean, so all 4 tests had been red since `af1fb959`. The Task 1 run covered ShopServiceTest, ShopServiceGeocodeTest and PublicStorefrontServiceTest, not this file.
- **Fix:** A Clock bean in the runner.
- **Commit:** `24d22a2c`

**4. [Rule 1 - Bug] The pause response disagreed with the stored pause end**
- **Found during:** Task 3 GREEN
- **Issue:** The response carried nanoseconds; PostgreSQL keeps microseconds.
- **Fix:** Truncated to whole seconds at the write.
- **Commit:** `935bf0a6`

**5. [Rule 1 - Bug] OpenAPI described `minutes` with string enum values on an integer**
- **Found during:** Task 3
- **Issue:** `allowableValues` rendered as the string enum `["20","40"]` on an integer field, a schema no integer satisfies.
- **Fix:** Removed `allowableValues`. The rule stays in the description and in the typed 400.
- **Commit:** `935bf0a6`

**6. [Rule 2 - Missing verification] ShopDto cache round-trip**
- **Found during:** Task 3
- **Issue:** ShopDto is the Redis "shops" value, and a read failure there is swallowed silently. The new `TimeWindow` lists, serialized through `@JsonValue`, needed proof.
- **Fix:** `ShopDtoScheduleCacheRoundTripTest` round-trips both runtime list shapes (JDK immutable lists of 0 to 3 windows, and ArrayLists) through the production serializer. Arm 3-G makes it red.
- **Commit:** `935bf0a6`

**7. [Sequencing] `validateShopIsOpen` deleted in Task 2, not Task 1**
- Task 1 put the pause gate in front of it, so each task stayed green. The end state is the plan's.
- OpenApiSnapshotTest was red on the intermediate commits `af1fb959` to `813ecba8` (the pause paths existed before the snapshot did). It is green from `935bf0a6`.

**Total deviations:** 1 binding owner override, 6 auto-fixed (1 Rule 3, 2 Rule 2, 3 Rule 1) and 1 sequencing note. **Impact:** none widens what the plan allows. #1 and #2 keep pre-existing behaviour (tests and the demo storefront) under D-15. #3 to #6 are correctness fixes found by running more than the plan's verify set.

## Issues Encountered

- My scratch edit applier mis-parsed an edit with an empty replacement and swallowed the blocks after it. It was caught at compile time, the two files were restored (one from HEAD, one rebuilt), and the applier now counts blocks against headers. No commit carried the damage.
- `python3` is refused by the machine guard (no conda env for this project). Edits used a Node applier instead.

## Known Stubs

None. Every new field is wired to the policy or the entity.

## Threat Flags

None outside the plan's threat model. The new surface is the pause endpoints (T-37-51, mitigated, 403 arms) and the shop-closed refusal (T-37-52, mitigated, enforced before the reservation). T-37-53 (an indefinite pause abused to close a shop) is accepted: only STAFF can pause, and resume is one call.

## Runtime / stack

**The stack was not touched.** core-java was not rebuilt. The running image predates V80, the customer gate and this plan (WINDOWS #30). WINDOWS #33 records the live proof owed at the next rebuild. The dev tenant's stale PENDING orders were left alone.

## Ledger (WINDOWS)

- **#32 (deviation):** the D-25 ruling's accepted consumer-safety risk. An unreadable legacy day keeps taking orders until its vendor reviews it.
- **#33 (unrun-verify):** the live proof of the pause, the 409 and the new fields on the rebuilt runtime.

## Next Phase Readiness

- **37-21:** the interfaces it names exist as specified. Two notes:
  - The NEEDS_REVIEW copy "This day shows as closed" is untrue under the ruling: the server keeps those days open. The editor may still pre-fill them as Closed. See deferred-items "From 37-20".
  - `PauseOrdersRequest` accepts `{minutes: 20|40}` and `{untilReopened: true}` exactly.
- **37-22:** must render `PublicShopDto.openNow` (and `nextOpenAt`, `pausedUntil`), not re-parse `openingHours`. Otherwise the storefront shows Closed while checkout accepts the order.
- **RWO-022 is not marked complete.** `requirements.ready-ids` returned 0/1, because 37-21 also declares it.
- **The docs-metrics gate VOIDs** on a 37-17 jest table, a new cause. See deferred-items. Regenerate before the phase PR.

## Self-Check: PASSED

- Files: all 11 created files exist (checked with `[ -f ]`).
- Commits: `d44576d5`, `af1fb959`, `a2320ed4`, `37d9bcbc`, `813ecba8`, `24d22a2c`, `935bf0a6` are all ancestors of HEAD. `git rev-list --count 7b6173b1..HEAD` = 7. `check evaluation-scope --plan 37-20 --commits-only` resolved them.
- Gate commits: 3 `test(37-20)` and 3 `feat(37-20)`, in RED-then-GREEN order.
- Artifacts:
  - `ShopAvailabilityPolicy.java` contains `LEGACY_ALWAYS_OPEN` (3 hits).
  - `ShopPauseIntegrationTest.java` contains `shop-closed` (4).
  - `PublicStorefrontService.java` calls `requireTakingOrders` before the reservation (1).
- Acceptance criteria and plan verification were re-run as recorded above.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-10*
