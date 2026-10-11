---
phase: 37-real-world-operations-readiness
plan: 22
subsystem: ui
tags: [nextjs, storefront, checkout, json-ld, schema-org, opening-hours, pause, ssr, spring-boot, dev-seeder, playwright]

requires:
  - phase: 37-20
    provides: "ShopAvailabilityPolicy and the server's answer on every public shop read (openNow, nextOpenAt, pausedUntil, pausedIndefinitely, openingSchedule, legacyAlwaysOpen, hoursSource); the typed 409 shop-closed; the pause endpoints"
provides:
  - "lib/shop-availability.ts: the one reading of the server's availability fields (open / paused / closed / unknown), K3 wording in Europe/London, the shop-closed problem reader and the race copy"
  - "ShopAvailabilityNotice (UI-SPEC K3 customer messaging) between the shop header and the menu, and at the top of checkout step 1"
  - "Server-driven Open now / Paused / Closed on the /shop/[slug] pill, and Open / Paused / Closed on the /shop cards and the landing near-you cards; no claim when openNow is absent"
  - "Checkout: Place order disabled (aria-describedby -> the notice) when the server says the shop is not taking orders at load; the typed shop-closed refusal renders the race copy in role=alert and keeps the basket"
  - "JSON-LD openingHoursSpecification from openingSchedule only; omitted for legacyAlwaysOpen and without a schedule; a pause never appears"
  - "lib/opening-hours.ts reduced to display helpers (isOpenNow and parseHoursRange removed)"
  - "DemoDataSeeder: every seeded shop declares an all-day STRUCTURED week, including demo shops V80 marked LEGACY_ALWAYS_OPEN; the e2e public-surface fixture carries the same declared schedule and openNow"
affects: [37-21, 37-23, storefront, checkout, seo, dev-seed, e2e]

actuals:
  tokens: 17186
  tasks: 3
  commits: 6
plan_head_before: b7c41e73a79f86bf1a10fc2b657f0174a716ff01
plan_head_after: cb8cf9d12b5eccf28e2adabcb01c727b80d9412f

tech-stack:
  added: []
  patterns:
    - "A status claim reads the same source as the action: every customer surface renders one reader of the server's availability fields; an absent field is no claim"
    - "Times the server sends are worded in Europe/London with Intl and an explicit timeZone, so a UTC container and the browser print the same UK time"
    - "Checkout disables an action only beside a permanent explanation, linked by aria-describedby (belowMinimum precedent), and refuses a forced submit in the handler as well"

key-files:
  created:
    - frontend/lib/shop-availability.ts
    - frontend/components/storefront/shop-availability-notice.tsx
    - frontend/components/storefront/__tests__/shop-availability-notice.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/shop-closed.test.tsx
    - core-java/src/test/java/uk/jtoye/core/dev/DemoDataSeederHoursIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-red-task3.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-red-task3-seeder.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-break-arms.txt
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-live-probes.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-22-screens/
  modified:
    - frontend/types/storefront.ts
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/lib/storefront-server.ts
    - frontend/lib/opening-hours.ts
    - frontend/lib/structured-data.ts
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/components/marketing/shop-card.tsx
    - frontend/app/page.tsx
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/e2e/helpers/public-surface.ts
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/test/java/uk/jtoye/core/storefront/ShopAvailabilityPolicyTest.java
    - frontend/lib/__tests__/storefront-server.test.ts
    - frontend/lib/__tests__/opening-hours.test.ts
    - frontend/lib/__tests__/structured-data.test.ts
    - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
    - frontend/components/marketing/__tests__/shop-card.test.tsx
    - "fixtures dropping the removed ShopDetail.isOpen: __tests__/shop/server-seeded-islands, __tests__/shop/product-card-allergens, __tests__/shop/product-card-quantity-a11y, components/storefront/__tests__/seller-block"

key-decisions:
  - "A pause the server reports (pausedIndefinitely, or a pausedUntil it only sends while the pause is in force) reads Paused whatever openNow says; openNow ABSENT reads as unknown and renders no pill, no badge, no notice and disables nothing"
  - "Time wording: a paused shop says 'Back at HH:MM.' only when nextOpenAt is today in the UK, otherwise 'Opens {weekday} at {HH:MM}.' (a pause that outlasts today's hours must not read as today); 'Closed now' always names the weekday as the copy table says; no time -> 'Check back later.' The time is nextOpenAt (when orders actually come back), not pausedUntil"
  - "JSON-LD hours come only from openingSchedule; legacyAlwaysOpen and a payload without a schedule publish nothing (never a re-parse of the free text); a declared 00:00-00:00 window is 00:00-23:59; an overnight window keeps closes < opens on its opening day"
  - "hoursSource NEEDS_REVIEW (D-25, owner ruling applied per day by 37-20): JSON-LD publishes only the windows V80 read from the vendor's text; an unreadable day (open on the server) is not published at all, never as 24 hours. The storefront pill and checkout follow openNow, so they say open on such a day; search engines see it closed. Deliberate under-claim, recorded in deferred-items"
  - "ShopDetail.isOpen removed rather than redefined: the loader no longer computes an open flag, and the page reads shop.openNow on the server render and after a refetch alike"
  - "UI-SPEC open item 5 resolved: there is no revalidate window to tune; every storefront SSR fetch is cache: no-store (NO_STORE, lib/storefront-server.ts) and that is measured by a jest assertion on the fetch init of all six calls (break arm 1-B: revalidate 60 reds it)"
  - "The seeder declares the all-day week on every shop it writes (curated, archive, tenant-B probe) and upgrades a demo shop V80 marked LEGACY_ALWAYS_OPEN; it leaves STRUCTURED (a developer's saved hours), MIGRATED_FROM_TEXT and NEEDS_REVIEW alone. LEGACY_ALWAYS_OPEN is still written only by V80"
  - "The landing card gains an Open / Paused / Closed badge (it had none, deliberately, while the only source was the client parser); out of flow, top-left, so the card height and the landing CLS budget are unchanged"
  - "The e2e stub shop keeps openingHours null (what the server sends for a shop without text) and adds the declared schedule and the server's answer (openNow true), so the stub mirrors a seeded demo shop"

patterns-established:
  - "Customer availability: import readShopAvailability / headerPillLabel / cardBadgeLabel / availabilityNoticeCopy from lib/shop-availability.ts; never decide open/closed from hours in the client"
  - "Typed checkout refusals are recognised by problem-type suffix before describeOrderError (stale allergen, shop-closed)"

requirements-completed: []

coverage:
  - id: D1
    description: "The /shop/[slug] pill reads Open now / Paused / Closed from the server, never Open now while paused (F8); no pill when openNow is absent"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/components/storefront/__tests__/shop-availability-notice.test.tsx#F8: a paused shop whose hours are open now reads 'Paused', never 'Open now'"
        status: pass
      - kind: unit
        ref: "frontend/components/storefront/__tests__/shop-availability-notice.test.tsx#openNow absent (an older backend): no pill text and no notice"
        status: pass
      - kind: e2e
        ref: "evidence/37-22-live-probes.md section 5 (open / paused / resumed on the rebuilt runtime); screens shop-open-390.png, shop-paused-390.png"
        status: pass
    human_judgment: false
  - id: D2
    description: "ShopAvailabilityNotice between the header and the menu with the four K3 copies; no red; long names wrap at 360px / 130% root"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/components/storefront/__tests__/shop-availability-notice.test.tsx (8 notice cases)"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-22-live-probes.md section 5 backstop: 60-char name, 360px, root 130%, plus the nowrap instrument arm; notice-60char-360-root130.png"
        status: pass
    human_judgment: false
  - id: D3
    description: "/shop cards and landing cards read Open / Paused / Closed from the server; no badge without openNow"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/app/shop/__tests__/shop-discovery-client.test.tsx#reads Open / Paused / Closed from openNow and the pause, and shows no badge when openNow is absent"
        status: pass
      - kind: unit
        ref: "frontend/components/marketing/__tests__/shop-card.test.tsx#ShopCard availability badge (37-22) (5 cases)"
        status: pass
      - kind: e2e
        ref: "evidence/37-22-live-probes.md section 5 (/shop card and landing card, open / paused / resumed)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Checkout gating at load (disabled + aria-describedby) and the shop-closed race copy with the basket kept by content"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/app/shop/[slug]/checkout/__tests__/shop-closed.test.tsx (8 cases)"
        status: pass
      - kind: e2e
        ref: "evidence/37-22-live-probes.md section 5 (checkout disabled with aria-describedby on a paused shop; enabled when open); checkout-paused-390.png"
        status: pass
    human_judgment: false
  - id: D5
    description: "JSON-LD openingHoursSpecification from openingSchedule; omitted for legacy; pause never present (F9)"
    requirement: RWO-022
    verification:
      - kind: unit
        ref: "frontend/lib/__tests__/structured-data.test.ts#shopStructuredData: opening hours from the structured schedule (37-22) (9 cases)"
        status: pass
      - kind: e2e
        ref: "evidence/37-22-live-probes.md section 5 (7 x 00:00-23:59 on the declared demo shop, no 'paus' while paused)"
        status: pass
    human_judgment: false
  - id: D6
    description: "SSR fetch is no-store (measured)"
    verification:
      - kind: unit
        ref: "frontend/lib/__tests__/storefront-server.test.ts#fetches every storefront call with cache: no-store"
        status: pass
    human_judgment: false
  - id: D7
    description: "Every seeded shop declares an all-day STRUCTURED week; LEGACY demo shops are declared on the next run; developer-saved hours survive; the all-day window is open around the clock"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/dev/DemoDataSeederHoursIntegrationTest.java (3 cases)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/ShopAvailabilityPolicyTest.java#allDayWindow_openAtMidnightNoonAndLastMinuteEveryDay"
        status: pass
      - kind: other
        ref: "evidence/37-22-live-probes.md section 2 (V80 LEGACY_ALWAYS_OPEN=5, then 5 of 5 STRUCTURED all-day on the dev DB)"
        status: pass
    human_judgment: false
  - id: D8
    description: "WINDOWS #30 and #33 discharged on the rebuilt runtime"
    verification:
      - kind: other
        ref: "evidence/37-22-live-probes.md section 4 (probes 30-1..30-8, 33-0..33-13)"
        status: pass
    human_judgment: false

duration: 75min
completed: 2026-10-10
status: complete
---

# Phase 37 Plan 22: Customer surfaces read the server's availability Summary

**The storefront pill, the /shop and landing cards, the shop-page notice and checkout all read 37-20's `openNow` / `pausedUntil` / `pausedIndefinitely` / `nextOpenAt` through one reader (`lib/shop-availability.ts`). Checkout disables Place order beside the K3 notice and turns the typed `shop-closed` refusal into "Your basket is saved". JSON-LD hours come only from the structured schedule and are omitted for legacy shops. The SSR fetch is measured `no-store`. Every seeded shop declares an all-day STRUCTURED week. Proven live on a rebuilt runtime, which also closed WINDOWS #30 and #33.**

## Performance

- **Duration:** about 75 min
- **Started:** 2026-10-10T14:03:27Z
- **Completed:** 2026-10-10T15:18Z
- **Tasks:** 3 of 3, each RED (`test(37-22)`, RED_EVIDENCE_OK) then GREEN (`feat(37-22)`)
- **Files modified:** 30 in the code commits

## Accomplishments

- **Task 1 (tracer).** The `/shop/[slug]` header pill reads "Open now" / "Paused" / "Closed" from the server. There is no pill when `openNow` is absent. `ShopAvailabilityNotice` sits between the header and the menu.
  - `ShopDetail.isOpen` is gone, and `lib/storefront-server.ts` no longer imports `isOpenNow`.
  - Tracer gate (end-of-phase mode, automated verify only): the verify re-ran green (173/173), so expansion continued.
- **Task 2.**
  - The /shop cards read Open / Paused / Closed from the server.
  - The landing near-you cards gain the same badge. `near-you-row.tsx` needed no change: it renders `ShopCard` with the server's shops, located or not.
  - JSON-LD `openingHoursSpecification` is built from `openingSchedule` only.
  - `lib/opening-hours.ts` keeps only `DAY_ORDER`, `DAY_LABELS` and `SCHEMA_DAYS`.
- **Task 3.**
  - Checkout gating at load and the race copy.
  - The seeder declares the all-day week on every seeded shop, LEGACY demo shops included.
  - `ShopAvailabilityPolicyTest` adds the all-day case: 00:00, 12:00 and 23:59 UK time on every day of 19–25 Oct 2026, the autumn clock change included.
  - The e2e stub carries the declared schedule.
- **Runtime.** Both images were rebuilt and both containers recreated. Freshness was proven by content, both gates PASS, and V80 applied on the dev DB. WINDOWS #30 and #33 were probed live and marked fixed, and the storefront was proven live with Playwright (section 5 of the evidence).

**The SSR fetch is no-store.** This answers UI-SPEC open item 5. From `frontend/lib/storefront-server.ts`:

```ts
const NO_STORE: RequestInit = { cache: "no-store" }
```

Every call goes through `getJson`, which calls `fetch(`${coreBaseUrl()}${path}`, NO_STORE)`. This is measured, not read off the source: `storefront-server.test.ts` asserts `cache: "no-store"` and no `next.revalidate` on all six storefront fetches. Break arm 1-B, `{ next: { revalidate: 60 } }`, fails that test. There is no revalidate window to tune, so a cached page cannot say "Open now" for a paused shop. The routes are already `ƒ (Dynamic)` in the build output.

## Task Commits

1. **Task 1: the shop page says Paused or Closed because the server says so**
   - RED `1499c56b`: test(37-22), the shop page's open state must be the server's (F8)
   - GREEN `e745dc96`: feat(37-22)
2. **Task 2: cards and JSON-LD read the server's schedule, and the client stops deciding**
   - RED `02a488cf` (F9)
   - GREEN `962c0a8f`
3. **Task 3: checkout gating, the checkout race, and explicit hours for demo and E2E shops**
   - RED `6e74d75d`
   - GREEN `cb8cf9d1`

**Plan metadata:** the docs commits that follow this SUMMARY (SUMMARY, evidence, WINDOWS, deferred-items; then STATE and ROADMAP).

## TDD Gate Compliance

| Task | RED commit | Classifier | Target (semantic assessment in the record) | GREEN |
|---|---|---|---|---|
| 1 | `1499c56b` | RED_EVIDENCE_OK (`evidence/37-22-red-task1.json`, TAP) | F8: a paused shop with `{}` hours rendered the emerald "Open now" pill. 14 planned failures across 173 tests, no load fault | `e745dc96` |
| 2 | `02a488cf` | RED_EVIDENCE_OK (`evidence/37-22-red-task2.json`, TAP) | F9: `toBeUndefined()` received the two specs derived from the free text. 13 planned failures across 663 tests | `962c0a8f` |
| 3 | `6e74d75d` | RED_EVIDENCE_OK (`evidence/37-22-red-task3.json`, TAP; `evidence/37-22-red-task3-seeder.json`, JUnit) | The race alert showed the generic detail, not the K3 copy. Separately, a LEGACY demo shop stayed LEGACY after a seeder run, and the archive and tenant-B shops were unclassified | `cb8cf9d1` |

These tests passed in RED for a stated reason: each guards behaviour that already existed. Each has its own break arm:
- the no-store test (arm 1-B)
- "renders nothing when open" and "openNow absent" (arm 1-D)
- the pause-not-in-JSON-LD case (arm 2-C)
- the NEEDS_REVIEW JSON-LD case (arm 2-B). The old text parser happens to agree with it on that fixture.
- checkout "open" and "absent" (arm 3-D)
- the developer-hours IT (arm 3-J2)
- the policy all-day case (arm 3-J3). The behaviour has existed since 37-20.

## Measured figures (re-measured, not copied)

| Suite | Before (HEAD b7c41e73) | After | Notes |
|---|---|---|---|
| jest | 2516 tests / 213 suites (re-measured) | **2550 / 215** | +34. New: notice 14, shop-closed 8. Changed: storefront-server +2, structured-data +8, opening-hours 8 -> 4, shop-card +5, discovery +1 |
| core-java unit | 1682/0/0/1 (37-20) | **1683 / 0 failures / 0 errors / 1 skipped** | Fresh XML from `cleanTest test`: 196 files, the oldest stamped after the run started |
| core-java integration | 1068/0/0/1 (37-20) | **1071 / 0 / 0 / 1 skipped** | `cleanIntegrationTest integrationTest`, 26 m 57 s, 193 fresh files |
| `npm run build` | — | rc 0, TypeScript ran | Break arm 3-T: a type error in checkout gives rc 1 with TS2322 |
| `npm run lint` | — | **0 errors**, 31 warnings | One warning is in a plan file: `checkout/page.tsx` 'router' unused, in code this plan did not touch |
| `npx tsc --noEmit -p tsconfig.json` | 1 error (37-17's) | only that 1 error | `kds-sound.test.tsx(200,24)`, not this plan's file |
| `tsc -p tsconfig.e2e.json` | — | rc 0 | |
| docs block counter | — | `count-test-blocks.mjs --per-file` gives 116 over the 8 changed jest files, equal to the 116 executed | The 37-17 VOID is untouched |

Each acceptance check, both directions (full log in `evidence/37-22-break-arms.txt`):

- **Clean, arms, clean.** Every arm ran against a committed state and was restored by sha256. There are 21 `RESTORED-BY-CONTENT` lines and 0 `RESTORE-FAILED`. Each closing clean run was green: 173, 663, 109 jest; the Java policy run 26/26, not UP-TO-DATE; the seeder IT 3/3.
- **Task 2 acceptance `rg -uu -n 'isOpenNow\(' frontend/app frontend/components frontend/lib --glob '!**/__tests__/**'`.**
  - Pre-change tree: rc 0. At b7c41e73, 4 hits: shop-detail-client, shop-discovery-client, opening-hours and storefront-server.
  - Post-change: rc 1, no output.
  - Positive control: the same search for any mention returns rc 0 with comment and test hits.
- **F8 fail arm (1-A).** With the pill reverted to `isOpenNow`, 7 failures. The F8 test found the emerald "Open now" pill.
- **F9 fail arm (2-A).** Emitting a 24/7 spec for legacy shops fails F9 with 7 Monday..Sunday 00:00-23:59 nodes.
- **Basket kept, by content.**
  - Arm 3-B (`clearCart` on the race) failed on the missing alert, not on the storage assertion: the empty-basket view replaces the page.
  - Arm 3-B2 rewrites only the stored basket. It fails the by-content assertion: expected the stamped basket (`"owner":null`, quantity 2), received `{"items":[]}`.

## Files Created/Modified

- `frontend/lib/shop-availability.ts`: reads the server's fields. It holds the pill and badge labels, the K3 notice copy, the UK-time wording, `readShopClosedProblem` and `shopClosedRaceCopy`.
- `frontend/components/storefront/shop-availability-notice.tsx`: the K3 block. Classes `rounded-xl border border-slate-300 bg-white p-4`, a Clock icon and no red. It wraps with `overflow-wrap:anywhere`.
- `frontend/app/shop/[slug]/shop-detail-client.tsx`: the server-driven pill (`data-testid="shop-availability-pill"`) and the notice placement.
- `frontend/lib/storefront-server.ts`: no client open flag; the NO_STORE rationale.
- `frontend/lib/structured-data.ts`: hours from the schedule.
- `frontend/lib/opening-hours.ts`: display helpers only.
- `frontend/app/shop/shop-discovery-client.tsx` and `frontend/components/marketing/shop-card.tsx`: server-driven badges (`data-testid="shop-availability-badge"`).
- `frontend/app/page.tsx`: comment only, on the openness claim.
- `frontend/app/shop/[slug]/checkout/page.tsx`: the K3 gate, the forced-submit guard and the race branch.
- `frontend/e2e/helpers/public-surface.ts`: the declared all-day fixture.
- `core-java/.../dev/DemoDataSeeder.java`: `applySeededHours` on all three write paths, and the LEGACY upgrade.
- Tests: listed in key-files.

## Decisions Made

See key-decisions in the frontmatter. The NEEDS_REVIEW handling and the time-wording rule are the two the owner may want to look at.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] A new `frontend/lib/shop-availability.ts` beside the listed files**
- **Found during:** Task 1
- **Issue:** Five surfaces need one reading of the server fields (page, cards, landing card, checkout, notice). `lib/opening-hours.ts` must not decide, so it was the wrong home.
- **Fix:** One new lib module, tested through the listed notice, card, discovery and checkout tests.
- **Committed in:** `1499c56b` (stub), `e745dc96`, `cb8cf9d1`

**2. [Rule 1 - Bug] Fixtures carried the removed `ShopDetail.isOpen`**
- **Found during:** Task 1
- **Issue:** `tsc` reported TS2353 in `product-card-allergens` and `product-card-quantity-a11y`. `seller-block` carried the same literal untyped. `server-seeded-islands` asserted the old flag.
- **Fix:** Removed the field from the fixtures. The verdict test now reads `openNow`.
- **Committed in:** `1499c56b`, `e745dc96`

**3. [Rule 2 - Missing verification] `DemoDataSeederHoursIntegrationTest` (a new IT file)**
- **Found during:** Task 3
- **Issue:** The plan's only Java test was the policy case, which cannot see the seeder.
- **Fix:** A 3-method dev-profile IT: every seeded shop is declared, a LEGACY demo shop is declared on the next run, and saved hours survive.
- **Committed in:** `6e74d75d`

**4. [Rule 2 - Missing critical] The seeder covered only the curated path, and was create-only**
- **Found during:** Task 3 RED
- **Issue:** The archive and tenant-B shops were unclassified. On the shared dev DB all five shops would have stayed LEGACY_ALWAYS_OPEN after V80: measured live, V80 classified 5 LEGACY. That contradicts the must-have.
- **Fix:** `applySeededHours` on all three paths. LEGACY is upgraded; other sources are left alone.
- **Committed in:** `cb8cf9d1`

**5. [Rule 1 - Bug] Palette gate red on an issue reference in a test comment**
- **Found during:** Task 2
- **Issue:** `#544` in the new shop-card test comment matched the gate's raw-hex grep.
- **Fix:** Re-spelled as "issue 544".
- **Committed in:** `962c0a8f`

**6. [Rule 2 - Missing critical] The checkout handler refuses a forced submit**
- **Found during:** Task 3
- **Issue:** A disabled button does not stop `form.submit()` or a scripted submit.
- **Fix:** `if (notTakingOrders) return` in `handleCreateOrder`, with its own test and arm 3-E.
- **Committed in:** `cb8cf9d1`

**7. [Rule 3 - Instrument] The scratch TAP reporter split a test title containing a control character**
- **Found during:** Task 2 RED
- **Issue:** The classifier rejected the record (`invalid_record`: `safe-return-to.test.ts` has a literal control character in a title).
- **Fix:** The reporter escapes control characters and `#`. RED was re-run on the unchanged tree, and the record says so.

---

**Total deviations:** 7 auto-fixed: 2 Rule 1, 3 Rule 2, 2 Rule 3.
**Impact on plan:** No scope creep. The seeder, the handler guard and the IT were needed to make the must-haves true and provable.

## Issues Encountered

- **Wrong phrasing in the live script.** The "until reopened" Playwright run printed one FAIL. That was the timed-pause assertion ("Back at HH:MM") meeting an indefinite pause. The text it found was the correct K3 copy, "Check back later.". It is kept as the `-indefinite` evidence, and the timed run was re-taken (ALL PASS).
- **Transient document width.** One live measure of the no-space 60-character variant read `document.scrollWidth` 364 (and 361 on a re-run) at 360px. A dedicated re-measure listed no element past the viewport in three states, and the notice's right edge was 339 every time. Recorded as transient, not as an overflow.
- **Hidden duplicate tree.** The React streaming buffer briefly holds a hidden duplicate of the tree, so the live backstop scopes to `#main` (memory: streaming buffer duplicates DOM).
- **Gates not run before the rebuild.** `check-runtime-freshness.sh` and `check-openapi-snapshot-fresh.sh` were not run on the old images. Their fail direction is the pre-rebuild content check here (no V80, no `ShopAvailabilityPolicy` in the old jar) plus 37-20's recorded +274-line snapshot report.

## Runtime parity (CLAUDE.md contract b)

- **Rebuilt:** `docker compose -f docker-compose.full-stack.yml up -d --build frontend core-java`, rc 0.
- **frontend:**
  - Container `c5e633c4` became `8945c830`, StartedAt 15:00:48Z. Image `809cf75c` became `5d359e69`.
  - The old bundle had no notice copy; positive control "Open now" was present.
  - The new bundle has both the notice copy and the race copy.
- **core-java:**
  - Container `b5085080` became `2128456c`, StartedAt 15:00:27Z. Image `94e8e7b2` became `9d3b9884`.
  - The old jar had neither `ShopAvailabilityPolicy` nor `V80__` (rc 1); positive control `V79__` was present.
  - The new jar has `ShopAvailabilityPolicy*.class` and `db/migration/V80__shop_opening_schedule_pause.sql`.
- **V80 on the dev DB:** `80|shop opening schedule pause|t`. Notice: `V80: classified shops: LEGACY_ALWAYS_OPEN=5, MIGRATED_FROM_TEXT=0, NEEDS_REVIEW=0`. Ten seconds later the seeder made all 5 STRUCTURED with the all-day week (5 of 5 by jsonb equality; a control query counts 0).
- **Gate output, verbatim:**
  - `scripts/check-runtime-freshness.sh`: rc 0, `PASS: 4 running built service(s) match the source tree (0 unverified).` Lines include `core-java FRESH image tagged 2026-10-10 15:00:18 UTC >= newest build-input commit cb8cf9d1` and `frontend FRESH image tagged 2026-10-10 15:00:06 UTC >= ... cb8cf9d1`.
  - `scripts/check-openapi-snapshot-fresh.sh`: rc 0, `A-1 ... PASS — 0 differences across 121 declared path(s).` and `RESULT: PASS — the running service emits everything the committed contract claims.`
  - `scripts/seed-order-metric.sh`: rc 0, `placed : HTTP 201 ORD-00000000-20261010-8E4955C0`, `PASS: the NoOrdersCreated counter now exists — the rule can fire.`
- **WINDOWS #30 and #33:** marked fixed with the probe summary. Probes, fail directions included, are in `evidence/37-22-live-probes.md` §4:
  - an ungranted user gets 403 on customers; the admin control gets 200
  - STAFF gets 200, and the same token string gets 403 after the revoke
  - an ungranted pause is refused 403
  - STAFF pause gives `openNow false`; a guest order is then refused 409 `shop-closed` `PAUSED` with 0 reservations and 0 orders
  - after resume the same key is accepted 201, with 1 reservation and 1 order
  - an indefinite pause gives `pausedIndefinitely true`
- **WINDOWS #35 (new, unrun-verify):** the checkout race UI and the NEEDS_REVIEW JSON-LD rule are proven in jest only.
- **Left on the dev tenant:** two PENDING probe orders, `ORD-…-8E4955C0` and `ORD-…-AE0346A5`, and the probe user's `user_directory` row. The Keycloak user was deleted (204) and no grant remains.

## Threat Flags

None new. T-37-56 is mitigated (JSON-LD omitted for legacy, F9 and arm 2-A). T-37-57 is mitigated (no-store measured, server fields read). T-37-SC: no package installed.

## Known Stubs

None. The RED stubs from `1499c56b` were replaced in `e745dc96`.

## User Setup Required

None.

## Next Phase Readiness

- **37-21 (wave 16):** read `deferred-items.md` "From 37-22" before building the hours editor. It covers:
  - the storefront "Opening hours" list still shows the free text, which goes stale once structured hours are saved
  - the NEEDS_REVIEW copy
  - the JSON-LD under-claim on unreadable days
- **37-23 UAT:** pause from the kitchen while a customer is on checkout (WINDOWS #35).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-10*

## Self-Check: PASSED

- All 11 created files listed in key-files exist on disk (`[ -f ]`), and the screens directory holds 19 PNGs.
- All 6 task commits are ancestors of HEAD (`git merge-base --is-ancestor`). Control: an unknown sha reports MISSING.
- `commits: 6` is measured as `git rev-list --count b7c41e73..cb8cf9d1`.
- Task acceptance re-run on the final tree, with closing clean runs:
  - Task 1 verify: 173/173.
  - Task 2 verify: 663/663, and the `isOpenNow(` rg gives rc 1.
  - Task 3 verify: checkout 109/109, build rc 0, ShopAvailabilityPolicyTest 26/26 (not UP-TO-DATE).
  - Full jest: 2550/215. Unit: 1683/0/0/1. IT: 1071/0/0/1.
