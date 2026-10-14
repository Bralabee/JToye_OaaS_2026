---
phase: 37-real-world-operations-readiness
plan: 49
subsystem: ui
tags: [nextjs, react, react-hook-form, zod, radix-checkbox, jest, storefront, checkout, dashboard]

requires:
  - phase: 37-48
    provides: "ShopDto/CreateShopRequest deliveryFeePennies, freeDeliveryThresholdPennies (explicit null clears), offersDelivery, offersCollection; PublicShopDto flags; 422 fulfilment-not-offered {fulfilmentType, offered[]}; 409 shop-slug-taken; the edit dialog's G2 web-address row"
provides:
  - "Shop dialog: Delivery fee (£), Free delivery over (£) with the empty-threshold hint, and the 'How customers get their order' fieldset (neither ticked refused with a role=alert, no request)"
  - "Delivery Info placeholder 'e.g. Delivery within 3 miles' (the free text kept)"
  - "Checkout: one offered type renders a static row and orders as that type; the 422 refusal renders the UI-SPEC copy from its typed members and adopts offered[]"
  - "Storefront header: 'Collection only' / 'Delivery only' chip in a flex-wrap status row; a collection-only shop no longer advertises 'Free delivery'"
  - "Create dialog: a preview of the generated web address (/shop/{base}-…), mirroring ShopService.generateSlug"
  - "lib/fulfilment-offered.ts: one reading of the flags (absent = offered), the refusal reader and the contract copy"
affects: [37-50, phase-37-uat, phase-39-slug-aliases]

actuals:
  tokens: 30847
  tasks: 2
  commits: 5

plan_head_before: 9a3cf989690ec6cb357ad92f637d6d261903f3a7
plan_head_after: f236319e834725ad26549e799c2aff1e030aec15

tech-stack:
  added: []
  patterns:
    - "Customer surfaces read offered fulfilment types through lib/fulfilment-offered.ts only; an absent flag means offered"
    - "A typed refusal's copy is built from its typed members, never by echoing `detail`"
    - "Live form values for display are read with useWatch, not watch(), so the React Compiler does not skip the component"

key-files:
  created:
    - frontend/lib/fulfilment-offered.ts
    - frontend/app/dashboard/shops/__tests__/shop-fulfilment-form.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/fulfilment-offered.test.tsx
    - frontend/app/shop/__tests__/shop-fulfilment-chip.test.tsx
  modified:
    - frontend/app/dashboard/shops/page.tsx
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/types/api.ts
    - frontend/types/storefront.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-49: the shop form ALWAYS sends freeDeliveryThresholdPennies, so an emptied field reaches 37-48 as null and clears the threshold; an empty fee is sent as 0 (no fee), like the minimum order"
  - "37-49: an absent offersDelivery/offersCollection reads as offered on every surface (old-backend tolerance; the behaviour before the flags existed)"
  - "37-49: a 422 fulfilment-not-offered makes checkout adopt the server's offered[]; with one type left the control becomes its static row and the order switches to it, so the next tap continues"
  - "37-49: the create-dialog preview shows the server's generated base plus an ellipsis for the random 8-character code, never an invented code"
  - "37-49: a collection-only shop's details bar shows no delivery price at all (it fell through to 'Free delivery')"

patterns-established:
  - "Offered-type reading: onlyOfferedType(shop) returns the single type or null (both, or a payload claiming neither, which the server's CHECK makes unstorable)"

requirements-completed: [RWO-023, RWO-028]

coverage:
  - id: D1
    description: "Shop form: fee and threshold inputs (pennies 299/3000; an emptied threshold sent as explicit null; fee with no threshold valid) and the corrected Delivery Info placeholder"
    requirement: RWO-023
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/shops/__tests__/shop-fulfilment-form.test.tsx#the shop form's delivery fields (UI-SPEC G1, UXT-023)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Shop form: 'How customers get their order' fieldset, both ticked by default, stored flags pre-filled, neither ticked refused with a role=alert and zero requests"
    requirement: RWO-023
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/shops/__tests__/shop-fulfilment-form.test.tsx#'How customers get their order' (UI-SPEC G1, zero-one-many)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Checkout: offered-only control (static row for one type, segmented control for two), the order sent as the only type, the fee preview under and at the threshold, and the 422 refusal copy"
    requirement: RWO-023
    verification:
      - kind: unit
        ref: "frontend/app/shop/[slug]/checkout/__tests__/fulfilment-offered.test.tsx"
        status: pass
    human_judgment: false
  - id: D4
    description: "Storefront header 'Collection only' / 'Delivery only' chip in a flex-wrap row; no delivery price for a collection-only shop"
    requirement: RWO-023
    verification:
      - kind: unit
        ref: "frontend/app/shop/__tests__/shop-fulfilment-chip.test.tsx"
        status: pass
    human_judgment: false
  - id: D5
    description: "Web address: create-dialog preview; the edit dialog sends a slug only when edited; the long address breaks anywhere with the link below sm"
    requirement: RWO-028
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/shops/__tests__/shop-fulfilment-form.test.tsx#the web address (UI-SPEC G2, UXT-028)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Visual fit at 375/768/1280 (chip wrap, web-address row, create preview) and the live end-to-end path on rebuilt images"
    verification: []
    human_judgment: true
    rationale: "jest asserts the DOM and classes (flex-wrap, break-all, flex-col below sm), not pixels; the compose stack runs images behind this branch and this plan did not rebuild it. Owed to the 37-50 gate (WINDOWS #45)."

duration: 10min
completed: 2026-10-14
status: complete
---

# Phase 37 Plan 49: Delivery fees, offered fulfilment types and the shop web address in the UI Summary

**Vendors set the delivery fee, the free-delivery threshold and whether they deliver or collect in the shop form. Customers see "Collection only" on the shop page and get only the offered option at checkout. The create dialog previews the web address the shop will get.**

## Performance

- **Duration:** 10 min (clock: 2026-10-14T11:40:15Z to 11:50:39Z)
- **Tasks:** 2 (Task 1 tracer, Task 2 auto; both TDD)
- **Files modified:** 17 (13 code, tests and types; 4 evidence records)

## Accomplishments
- **Shop form (UI-SPEC G1):**
  - "Delivery fee (£)" and "Free delivery over (£)": number, step 0.01, min 0, h-11, with the hint "Leave empty if you don't offer free delivery." linked by `aria-describedby`.
  - The fieldset "How customers get their order": Delivery and Collection, as 24px checkboxes in 44px rows.
  - Unticking both shows "Choose at least one: delivery or collection." in a `role="alert"`, and nothing is sent.
  - The threshold key is always sent, so an emptied field clears it (the 37-48 explicit-null contract).
- **Delivery Info** keeps its text. Its placeholder is now "e.g. Delivery within 3 miles".
- **Checkout:**
  - A shop that takes one type gets the static row "Collection only. This shop doesn't deliver." (or the delivery version), and the order is sent as that type.
  - The fee preview follows the declared fee and threshold. The threshold is inclusive: £20.00 shows £2.99 and £30.00 shows Free.
  - A 422 `fulfilment-not-offered` renders "This shop doesn't offer {x}. Choose {y} to continue." built from `fulfilmentType` and `offered[]`. The control then shows only what the server named.
- **Storefront header:**
  - A "Collection only" / "Delivery only" chip (Store / Bike icon) sits beside the open state and the rating.
  - The status row now wraps (planner assumption for the 375px overflow row).
  - A collection-only shop no longer shows "Free delivery" in its details bar.
- **Create dialog (UI-SPEC G2):** "Web address" previews `/shop/{base}-…` in 14px monospace with `break-all`. It mirrors `ShopService.generateSlug`. A hint says that a short code is added at the end. The 37-48 edit row was verified and left unchanged.

## Task Commits

1. **Task 1: A collection-only shop is set in the form and shown as such at checkout** (tracer)
   - `a60a8f45` test (RED): 21 tests, 17 failed, 4 regression arms green
   - `bef53d24` feat (GREEN): 21/21; the verify command gave 19 suites, 204 tests
2. **Task 2: The storefront chip and a web address that changes only on purpose**
   - `56040c05` test (RED): 25 tests, 5 failed
   - `77356207` feat (GREEN): 25/25; shops, storefront and checkout suites 55 suites, 615 tests; build rc 0
3. **Counts:** `f236319e` docs. jest 2801/240 became 2834/243; total 5945 became 5978.

**Plan metadata:** the commit containing this SUMMARY (docs).

## TDD Gate Compliance

Each task has a `test(37-49)` RED commit before its `feat(37-49)` GREEN commit. No REFACTOR commit was needed.

Every RED record returned `RED_EVIDENCE_OK` / `target_test_failed` from `gsd_run check tdd-red-evidence`. Each record also passed `gitleaks dir … -c .gitleaks.toml` with rc 0. A positive control showed gitleaks can fire: rc 1 on a high-entropy `secret_token`.

| Record | Target | Semantic assessment |
|---|---|---|
| `37-49-red-task1.json` | collection-only checkout | The static row was absent: the page rendered both options for every shop and defaulted to DELIVERY. The 4 green arms (both offered, no flags, fee preview under and at the threshold) prove the harness renders and prices |
| `37-49-red-task1b.json` | neither ticked: alert and zero requests | There was no "How customers get their order" group to drive. The dialog itself rendered: the placeholder arm failed on the old "Free delivery over £30" |
| `37-49-red-task2.json` | "Collection only" chip | No chip element existed. The details-bar arm failed because a collection-only shop showed "Free delivery" (the defect). The both-offered and preserved-fee arms passed |
| `37-49-red-task2b.json` | create-dialog preview | There was no preview after a name was typed. Its "nothing before a name" assertion passed, as expected of an absent feature. The G2 edit-row arms passed because 37-48 built that row |

## Fail directions (break arms, each restored by `cp` and verified by sha256 plus `git diff --quiet`)

| Arm | Break | Result |
|---|---|---|
| A (plan acceptance) | the neither-offered guard disabled in `submitForm` | rc 1: "Expected number of calls: 0, Received: 1" |
| B | threshold key omitted when empty | rc 1: the explicit-null arm failed |
| C | checkout ignores the flags (`onlyOfferedType(null)`) | rc 1: "Unable to find … Collection only. This shop doesn't deliver." |
| D | refusal copy echoes `detail` instead of the typed members | rc 1: the collection sentence was not found |
| E | static row shown but the order type not switched on load | rc 1: the collection-only arm failed |
| F (plan acceptance) | the dialog always sends the slug | rc 1: "Expected path: not "slug"", received `rosies-kitchen-019835a3` |
| G | chip disabled | rc 1: no `shop-fulfilment-chip` |
| H | status row without `flex-wrap` | rc 1: class assertion failed |
| I | details bar advertises delivery for a collection-only shop again | rc 1 |
| J | preview generator keeps `&` (drift from `generateSlug`) | rc 1: text content mismatch |
| build | `const asked: number = …` in `lib/fulfilment-offered.ts` | `npm run build` rc 1: TS2322, "Failed to type check"; restored, rebuild rc 0 |
| docs gates | the tree before `--write` | `docs-freshness` rc 1; `check-doc-metrics` 10 FAILs; both rc 0 after |
| pin guard | run from `/tmp` | rc 1 FATAL (actual-capture) |

**Closing clean run (committed tree):**
- full jest: 243 suites, 2834 tests, rc 0. This equals `docs/metrics.json` (243 files, 2834 blocks).
- `npm run lint`: rc 0 with 0 errors and 29 warnings. None of the warnings are in a file this plan touched. That absence grep was shown able to match.
- `npm run build`: rc 0, and "Finished TypeScript" appears in the output.

## Files Created/Modified
- `frontend/lib/fulfilment-offered.ts`: `onlyOfferedType`, `readFulfilmentNotOfferedProblem`, `fulfilmentNotOfferedCopy`, and the chip and single-option copy.
- `frontend/app/dashboard/shops/page.tsx`:
  - the fee, threshold and offered fields, their validation and the submit body;
  - the Delivery Info placeholder;
  - the create preview (`generatedSlugBase`, read through `useWatch`).
- `frontend/app/shop/[slug]/checkout/page.tsx`: the static single-option row, the switch to the only type on load, and the 422 branch.
- `frontend/app/shop/[slug]/shop-detail-client.tsx`: the chip, the flex-wrap status row, and no delivery price for a collection-only shop.
- `frontend/types/api.ts` / `types/storefront.ts`: the 37-48 fields (optional for old-backend tolerance).
- Tests: `shop-fulfilment-form.test.tsx` (18), `fulfilment-offered.test.tsx` (8), `shop-fulfilment-chip.test.tsx` (7).
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`: counts.

## Decisions Made
See `key-decisions`. Defaults this plan set where the UI-SPEC set none, each recorded so the owner can override:
- **Amount validation copy:** "Enter an amount of £0.00 or more." The server refuses a negative amount with `@Min(0)`.
- **Create preview hint:** "A short code is added to the end when the shop is created, so no two shops share an address." The server appends a random 8-character code that the browser cannot know, so the preview shows an ellipsis instead of inventing one.
- **Chip style:**
  - It is derived from the header's existing white-on-image chip (the Back link: `rounded-full bg-black/30 backdrop-blur-sm text-white`).
  - It is put on the phase's scale: `px-2 py-1`, 14px/600, a 16px icon, and `whitespace-nowrap`.
- **Create preview label:** it uses the same `text-sm font-medium` `<p>` as 37-48's edit-dialog "Web address" label, so the two dialogs match. That class mirrors the `Label` primitive.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `types/api.ts` and a shared `lib/fulfilment-offered.ts` were outside the plan's file list**
- **Found during:** Task 1
- **Issue:** The dialog types `Shop` / `CreateShopRequest` from `types/api.ts`, which had none of the four 37-48 fields, so tsc would refuse the submit body. Also, the chip and the checkout must read the flags by one rule.
- **Fix:**
  - Added the optional fields to `types/api.ts`.
  - Put the one reading of the flags, the 422 reader and the contract copy in `lib/fulfilment-offered.ts`.
- **Committed in:** bef53d24

**2. [Rule 1 - Bug] A collection-only shop advertised "Free delivery"**
- **Found during:** Task 2
- **Issue:** The storefront details bar falls through to "Free delivery" whenever the fee is 0 or null. For a shop that does not deliver at all, that claims a delivery nobody can order. It sits right beside the new "Collection only" chip.
- **Fix:** No delivery price line is shown when the only offered type is COLLECTION. Delivering shops keep their fee and threshold line, which a control arm asserts.
- **Verification:** RED arm, break arm I.
- **Committed in:** 56040c05 (test), 77356207

**3. [Rule 1 - Bug] `watch("name")` made the React Compiler skip the whole shops page**
- **Found during:** Task 2 lint
- **Issue:** eslint `react-hooks/incompatible-library` warned that compilation was skipped.
- **Fix:** Switched to `useWatch({ control, name: "name" })`. Lint on the file is clean, and the base file was also clean, so no pre-existing warning was hidden.
- **Committed in:** 77356207

**4. [Rule 3 - Blocking] Test counts and their prose**
- **Found during:** verification
- **Issue:** `docs-freshness` rc 1 (jest 2801 to 2834) and `check-doc-metrics` gave 10 FAILs.
- **Fix:** Regenerated with `--write` and updated the counts in README, CLAUDE.md and AGENTS.md.
- **Committed in:** f236319e

---

**Total deviations:** 4 auto-fixed (2 bugs, 2 blocking).
**Impact on plan:** Each was needed for a correct or buildable result on the surfaces the plan names. There was no new dependency and no API change.

## Preserved goods (Incremental Betterment Doctrine)
- **Shops that offer both types:** they keep the checkout segmented control and its Delivery default. A flag-less payload behaves exactly as before.
- **Delivering shops:** they keep "Delivery £x / Free over £y" in the details bar.
- **Free-text Delivery Info:** it is kept as vendor data. Only its placeholder changed.
- **The 37-48 G2 edit row:** unchanged, and its existing 9 arms in `opening-schedule-editor.test.tsx` stay green.
- **Checkout's other typed refusals** (shop-closed, cap, limit, basket-changed, stale allergens): untouched. Their suites are green.

## Issues Encountered
- None blocking. jest RED evidence used the same minimal TAP reporter as 37-46/37-48, copied into this session's scratchpad.

## Known Stubs
None. The stub-marker scan of the added lines in this plan's diff found nothing (rc 1), and the pattern was shown able to match.

## Threat Flags
None. There is no new endpoint and no new trust boundary. T-37-117 is mitigated: the fee preview follows the declared fee and threshold, the threshold is inclusive (arms at £20 and £30), and a collection-only shop no longer advertises a delivery price. T-37-SC: no package was installed.

## User Setup Required
None.

## Next Phase Readiness — what the 37-50 gate must prove live (WINDOWS #45)
- **Rebuild ALL images first.** The compose stack runs images behind this branch: 37-48 core-java and this plan's frontend.
- **Live path:** in the dashboard, set a shop to collection-only with a £2.99 fee and a £30 threshold, then save.
  - The shop page shows "Collection only".
  - Checkout shows the static row and no address form.
  - The order is placed as COLLECTION.
- **Fee preview:** on a delivering shop, the preview at £20 and at £30 equals the server's charged fee (P2-REG-17).
- **Threshold clear:** empty the "Free delivery over" field and save. The threshold is cleared (read it back), not kept.
- **Refusal race:** untick Delivery while a customer's checkout is open. Their Place order meets the 422 refusal copy, and the control switches to collection.
- **Web address:** edit a shop without touching the address and the slug is unchanged. The create dialog preview matches the created shop's slug prefix.
- **Pixels at 375/768/1280:** the chip wraps under the open state and rating in the header; the long-slug "Web address" row and the create preview do not overflow the dialog. This plan asserted classes only.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-14*

## Self-Check: PASSED

- All 5 key created files exist.
- All 6 plan commits (a60a8f45, bef53d24, 56040c05, 77356207, f236319e, fea42b2e) are ancestors of HEAD.
- There are 2 `test(37-49)` and 2 `feat(37-49)` commits, and each RED precedes its GREEN.
- No commit message carries an attribution line. The one `generated with` hit is "regenerated with scripts/docs-freshness.sh".
- The working tree is clean.
