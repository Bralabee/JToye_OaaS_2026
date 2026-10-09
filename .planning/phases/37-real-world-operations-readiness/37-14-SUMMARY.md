---
phase: 37-real-world-operations-readiness
plan: 14
subsystem: ui
tags: [vat, checkout, storefront, product-form, csv-import, copy-to-shop, uxt-046, uxt-047, uxt-026, nextjs, jest]

requires:
  - phase: 37-13
    provides: "POST /products/bulk/csv?shopId= (SHOP_MANAGER, per-row 'shop required'), POST /products/copy -> CopyProductsResult {created, skipped, failed, items[]}"
  - phase: 31.1
    provides: "trader_identity.vat_number (V71) exposed as SellerIdentityDto.vatNumber, absent when no registration is declared; SellerBlock's 'a failed fetch is not not-provided' rule"
  - phase: 37-12
    provides: "dialog shape for a mid-flight action (no close while pending), the TAP RED-evidence recipe"
provides:
  - "Checkout step 1, the card payment step and the cash confirmation render a VAT row only for a seller with a declared VAT number; otherwise the B7 note under Total"
  - "Product form 'VAT rate' Select (Standard / Reduced / Zero / Exempt), Standard pre-selected on create, sent as vatRate"
  - "products-api importCsv(file, shopId) and copyProducts(req)"
  - "Import page target: 'Importing into: {Shop}', a required 'Shop to import into' Select on All shops, no request without a target"
  - "CopyToShopDialog (components/dashboard/products/copy-to-shop-dialog.tsx), copyResultSentence, productCountCopy"
  - "types/api.ts: Product.vatRate, CreateProductRequest.vatRate, BulkImportResult, CopyProductsRequest/Result/ResultItem"
affects: [37-15-runtime-gate, 37-39-track-inventory, storefront-checkout, dashboard-products, product-import]

actuals:
  tokens: 22454
  tasks: 2
  commits: 4
plan_head_before: 88d5f5ddb7e265bfd7753988834065d76f71420f
plan_head_after: 1c8ee4043d549a187ebc32f766ce69379684428e

tech-stack:
  added: []
  patterns:
    - "A legal statement about a price (a VAT row) renders only on a declared fact; a failed fetch proves neither the fact nor its absence, so it renders neither the row nor the absence note"
    - "A write that must carry a target takes it as a REQUIRED argument (importCsv(file, shopId)), and the handler refuses without one; a disabled control is the visible gate, never the only one"
    - "An edit that did not learn a stored value sends nothing for it, so a PUT that preserves on absence keeps the real value rather than a guess"

key-files:
  created:
    - frontend/components/dashboard/products/copy-to-shop-dialog.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/vat-registration.test.tsx
    - frontend/app/dashboard/products/__tests__/product-vat-rate.test.tsx
    - frontend/app/dashboard/products/__tests__/import-copy.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-14-red-vat-display.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-14-red-import-copy.json
  modified:
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/products/import/page.tsx
    - frontend/lib/products-api.ts
    - frontend/types/api.ts
    - frontend/app/shop/[slug]/checkout/__tests__/vat-preview.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx

key-decisions:
  - "37-14: a VAT row (checkout step 1, payment step, cash confirmation) needs a DECLARED registration (shop.seller.vatNumber); a loaded shop without one gets the B7 note under Total; a shop that failed to load gets neither; the order page never prints VAT and gets no note (it has no totals block)"
  - "37-14: the product form sends vatRate STANDARD on a new product and the stored rate on an edit; an edit whose response carried no rate sends none, so the PUT keeps the stored rate rather than a guess"
  - "37-14: the CSV import target is the switcher's shop, or on All shops a required chosen shop; handleUpload refuses without a target (the disabled control is not the gate); importCsv takes shopId as a required argument"
  - "37-14: 'Copy to another shop…' is a row action (that product) and, with a shop selected, a header action for that shop's whole menu (productIds omitted); offered only where a source shop and another shop exist; '1 product' / 'its' for a single copy"

patterns-established:
  - "Display a regulated statement only on the declared fact; say plainly when it is not shown"

requirements-completed: [RWO-046, RWO-047, RWO-026]

coverage:
  - id: D1
    description: "UXT-047: for a seller with no VAT number, checkout step 1, the card payment step and the cash confirmation print no 'VAT (' row and no VAT figure, and show 'This seller hasn't declared a VAT registration, so no VAT is shown.' (14px slate-600) under Total; a shop with no seller details is treated the same"
    requirement: "RWO-047"
    verification:
      - kind: unit
        ref: "frontend/app/shop/[slug]/checkout/__tests__/vat-registration.test.tsx (5 unregistered arms): RED at cc9ec0df (RED_EVIDENCE_OK, evidence/37-14-red-vat-display.json, the cash confirmation read 'VAT (incl. 20%) £2.00' for a sole trader), GREEN at 77d1310c; break arm A (unconditional row) red on all 5, arm F (text-xs slate-500 note) red on the 3 note-position arms"
        status: pass
    human_judgment: false
  - id: D2
    description: "A VAT-registered seller keeps the VAT row with vatRateLabel and the figure on all three screens; the COR-6 rate arms (vat-preview) and the fee breakdown (checkout.test) keep their meaning with a registered seller fixture"
    requirement: "RWO-047"
    verification:
      - kind: unit
        ref: "vat-registration.test.tsx (3 registered arms); vat-preview.test.tsx 7/7 and checkout.test.tsx green; break arm G (fixture seller removed) red on all 7 vat-preview arms, proving the fixture change was load-bearing"
        status: pass
    human_judgment: false
  - id: D3
    description: "A shop that failed to load shows neither a VAT row nor the note; the order page prints no VAT though the tracking response carries vatAmountPennies"
    requirement: "RWO-047"
    verification:
      - kind: unit
        ref: "vat-registration.test.tsx failed-fetch arm (RED on the old page, which printed the row) and order-page arm; break arm B (note without a loaded shop) and arm C (a VAT row injected on the order page) each red on their own arm"
        status: pass
    human_judgment: false
  - id: D4
    description: "UXT-046: 'VAT rate' Select after Price with 'Standard rate (20%)', 'Reduced rate (5%)', 'Zero rate (0%)', 'Exempt'; Standard pre-selected on a new product and sent as STANDARD; choosing Zero sends ZERO; an edit shows and sends the stored/changed rate; an edit that learned no rate sends none; the helper copy is the control's description; stock tracking off still sends quantityInStock null"
    requirement: "RWO-046"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/products/__tests__/product-vat-rate.test.tsx (7 tests): 5 RED at cc9ec0df, GREEN at 77d1310c; arm D (vatRate ?? STANDARD) red on the no-rate arm, arm E (quantityInStock undefined) red on the stock arm"
        status: pass
    human_judgment: false
  - id: D5
    description: "The VAT helper copy is acceptable to the owner and does not read as tax advice"
    requirement: "RWO-046"
    verification: []
    human_judgment: true
    rationale: "UI-SPEC open item 1: owner sign-off on the copy is presented and recorded in 37-15."
  - id: D6
    description: "UXT-026 import: with a shop selected the page says 'Importing into: {Shop}' and posts bulk/csv?shopId={shop}; on All shops a required 'Shop to import into' Select, Import disabled beside the permanent hint 'Choose a shop to import into', no request without a target even when a file reaches the input; choosing a shop enables Import and posts that shopId; a 60-character name wraps"
    requirement: "RWO-026"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/products/__tests__/import-copy.test.tsx#UXT-026 import (5 tests): RED at 4ecc06a7 (RED_EVIDENCE_OK, evidence/37-14-red-import-copy.json, the old page posted /api/v1/products/bulk/csv with no shopId = WINDOWS #27), GREEN at 1c8ee404; arm H (ungated: no guard, button enabled) red on the hint and no-request arms; arm H2 (guard only removed) red on the no-request arm, sending bulk/csv?shopId=null; arm I (shopId dropped) red on both upload arms"
        status: pass
    human_judgment: false
  - id: D7
    description: "UXT-026 copy: row 'Copy {title} to another shop…' and header 'Copy to another shop…' (selected shop's whole menu) open a dialog whose target Select excludes the source; '{n} products will be copied with their allergens, ingredients and photos'; 'Copy {n} products' disabled until a target, 'Copying…' while in flight; body carries productIds for a row and none for the menu; created, skipped and failed reported as the server returns them with zero counts omitted, in a toast and inline; a refusal is said inline; w-full max-w-lg dialog with wrapping lines"
    requirement: "RWO-026"
    verification:
      - kind: unit
        ref: "import-copy.test.tsx#UXT-026 copy (8 tests): RED 8/8 at 4ecc06a7, GREEN at 1c8ee404; arms J (source offered), K (zero counts printed), L (productIds [] for the menu), M (no Copying… label), N (row copy for a shop-less product), O (truncate), P (generic error) each red only on their own arm"
        status: pass
    human_judgment: false
  - id: D8
    description: "Goods kept: every existing checkout, order page, products and dashboard suite; full jest, lint and build"
    verification:
      - kind: unit
        ref: "npx jest --ci: 205 suites, 2382 tests, 0 failed (2353 + 16 + 13)"
        status: pass
      - kind: other
        ref: "npm run lint: 0 errors, 32 warnings (the 37-12 baseline); npm run build rc=0 with TypeScript run, fresh BUILD_ID, the new copy present in .next (control string absent); tsc --noEmit fail direction TS2322 on an injected type error"
        status: pass
    human_judgment: false
  - id: D9
    description: "The rebuilt runtime shows all of the above against the 37-13 server (live browser)"
    verification: []
    human_judgment: true
    rationale: "The shared compose runtime predates 37-13 and is rebuilt at 37-15; a live run now would exercise old backend code (WINDOWS #28)."

duration: 16 min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 14: VAT only for VAT-registered sellers, a VAT rate choice, import into a chosen shop and copy to shop Summary

**A customer buying from a seller with no VAT number is no longer told the price includes "VAT (incl. 20%)". The checkout, the payment step and the cash confirmation now say plainly that no VAT is shown. Vendors can set each product's VAT rate in the form. A CSV import always names the shop it lands in, so it can no longer post shop-less. A menu can be copied to another shop from a row or from the header, and the dialog reports the server's created, skipped and failed counts.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-10-09T01:27:53Z
- **Completed:** 2026-10-09T01:44:00Z
- **Tasks:** 2 (Task 1 tracer, Task 2 auto, both tdd)
- **Files modified:** 13 (11 frontend source/test files, plus 2 RED evidence records)

## Accomplishments

- **UXT-047 is closed.** Before the fix, the cash confirmation for a sole trader read "Subtotal £12.00 · Delivery Free · VAT (incl. 20%) £2.00 · Total £12.00", and the RED run recorded exactly that. Now a VAT row needs `shop.seller.vatNumber`. Without one, the row is replaced by the B7 line under Total on all three screens.
- **No VAT claim is made on no evidence.** If the shop failed to load, the page shows neither the row nor the note. The order page has never printed VAT, and a test now pins that, with a break arm to show the test can fail.
- **UXT-046 is closed.** The product form has a "VAT rate" Select after Price, with Standard visibly pre-selected on a new product. The rate is sent on create and update. An edit whose response did not include the rate sends nothing, so the server keeps the stored rate.
- **UXT-026's UI half is closed, and so is WINDOWS #27.** The import page says "Importing into: {Shop}". On All shops it requires a target shop, and the upload handler refuses to run without one, however the file reaches the input. A menu can be copied to another shop from a row (that product) or from the header (the selected shop's whole menu).

## Task Commits

1. **Task 1: VAT shown only for a VAT-registered seller, and a VAT choice in the product form** (tracer, tdd)
   - `cc9ec0df` test(37-14): failing arms (RED 10/16, RED_EVIDENCE_OK)
   - `77d1310c` feat(37-14): VAT only for a VAT-registered seller, VAT rate Select (GREEN)
   - **Tracer gate.** `human_verify_mode` is unset (default end-of-phase) and the verify is automated-only. The `<verify>` was re-run after the break arms: 21 suites, 188 passed, rc=0. Expansion continued.
2. **Task 2: Import into a chosen shop and copy a menu between shops** (tdd)
   - `4ecc06a7` test(37-14): failing arms (RED 13/13, RED_EVIDENCE_OK)
   - `1c8ee404` feat(37-14): import target and CopyToShopDialog (GREEN)

No REFACTOR commits were needed. There are 4 commits in `88d5f5dd..1c8ee404`, all from this plan.

**Plan metadata:** the docs(37-14) commit that adds this file.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `cc9ec0df` (10/16, TAP, `evidence/37-14-red-vat-display.json`, RED_EVIDENCE_OK) | `77d1310c` | not needed |
| 2 | `4ecc06a7` (13/13, TAP, `evidence/37-14-red-import-copy.json`, RED_EVIDENCE_OK) | `1c8ee404` | not needed |

### Task 1 RED

- **Target:** "the cash confirmation for a seller with no VAT number prints no VAT row though the server sent a VAT amount".
- **Actual:** the page body contained "VAT (incl. 20%)£2.00".
- **Semantic assessment:** the target ran and failed on its planned assertion, and that failure is the UXT-047 defect itself.
- **The other 9 reds** are all the planned behaviour being absent:
  - no B7 note (×2);
  - a VAT row even though the shop fetch failed;
  - the same row on the payment step;
  - no VAT rate Select, and no `vatRate` in the request (×5).
- **The 6 passing tests:**
  - Four are goods that must hold both before and after the change: the three VAT-registered arms and the stock-tracking arm.
  - Two are absence assertions that pass trivially on the old pages: the order page, and "no `vatRate` when the response omitted it". Break arms C and D are their fail direction.

### Task 2 RED

- **First attempt, rewritten before recording.** The first version of the no-request arm failed while waiting for the new Select, which is a fixture-shaped failure. It was rewritten to wait for the upload control instead.
- **Target:** "on All shops, no request is sent without a target, even when a file reaches the input".
- **Actual:** `[["/api/v1/products/bulk/csv", FormData, {multipart}]]`. The old page posted with no `shopId`, which is WINDOWS #27 reproduced.
- **The other 12 reds** are missing controls: the target line and Select, and the row and header copy actions.

## Acceptance criteria, both directions

| Criterion | Pass | Fail |
|-----------|------|------|
| T1: `vat-registration.test.tsx` red on the pre-change page (it shows "VAT (incl. 20%)") | GREEN 16/16 at `77d1310c` | RED 10/16 at `cc9ec0df`; the body read "…DeliveryFreeVAT (incl. 20%)£2.00Total£12.00" on all three screens |
| T1 fail arm: the unconditional render must fail it | as above | Arm A (`sellerVatRegistered = true`): 5 red, every unregistered arm including the failed fetch |
| T1: every existing products and checkout suite green | `<verify>` 21 suites, 188 passed, rc=0 (run twice: at GREEN and after the arms) | `<verify>` rc=1 on the RED tree; arm G (fixture seller removed) red on 7 vat-preview arms |
| T1 `<fails_when>` "Tests: 0 total" | 188 total | n/a; the count is printed in every run |
| T2: the All-shops arm asserts no request is sent without a target, red against an ungated dialog | GREEN 13/13 at `1c8ee404` | Arm H (no guard, button enabled): no-request and hint arms red. Arm H2 (guard removed, button still disabled): the no-request arm is red with `bulk/csv?shopId=null` sent. **The handler guard holds on its own.** |
| T2 `<verify>` products jest | 10 suites, 92 passed, rc=0 (GREEN and closing run) | RED run rc=1 |
| T2 `<verify>` build | `npm run build` rc=0; TypeScript ran (6.6s); BUILD_ID 02:42:50 BST; the new copy found in `.next` (3 files each for 4 strings; control string rc=1) | `tsc --noEmit` with an injected `number = "string"`: rc=2, TS2322 |
| UXT-046 and UXT-047 repros fail before, pass after (success criterion) | both green at HEAD | both red at the RED commits (see the TDD table) |

**Bracketing.** Both arm sets ran clean, then arms, then clean, against committed code. Restores were checked by sha256:

- **Task 1:** 4 files, starting at `adbc3ef9…` (checkout).
- **Task 2:** 4 files, starting at `dc87111c…` (products-api).

Both sets came back with `RESTORED_BY_HASH` and 0 `BREAK-` tokens (`rg -uu`, rc=1). Each arm turned red only the test it targets (lists in coverage D1–D7).

## Verification

- **Plan `<verification>`:** the checkout, order page and products jest suites are green with fail arms, and `npm run build` is green.
- **Full frontend gates** (after the last task):
  - `npm run lint`: rc=0, **0 errors, 32 warnings**. That is the 37-12 baseline. The warnings in touched files are on pre-existing lines: `router` unused in checkout, and `toast` unused plus two `<img>` in the import page.
  - `npx jest --ci`: **205 suites, 2382 tests, 0 failed**. That is the 37-12 baseline of 2353 plus 16 + 13 new tests. 37-13 touched no frontend file, so the delta reconciles exactly.
  - `npm run build`: rc=0.
- **T-37-SC:** `git diff 88d5f5dd..HEAD -- frontend/package.json frontend/package-lock.json` is 0 bytes. The same range on `types/api.ts` is 2361 bytes, so the check can see a change.

## Decisions Made

- **The order page gets no note.** It has never printed VAT, and it has no totals block, only the total in the order-number card. A note saying "no VAT is shown" there would imply the page would show VAT for a registered seller, which it does not. The B7 rule ("a VAT row only with a declared registration") holds there by construction, and a test plus break arm C pins it.
- **The VAT rate on an edit.** A product whose response carried no `vatRate` shows the placeholder "Keep the current rate" and sends nothing. Sending STANDARD there could silently re-rate a zero-rated product. Break arm D proves the test catches that.
- **Bulk copy is the selected shop's whole menu**, sent with `productIds` omitted. The table has no row selection, and adding one was not in the contract. The header action appears only when a shop is selected (that shop is the source), when another shop exists, and when the menu is not empty. Its count comes from the unsearched list, so a search on screen never changes what the copy sends.
- **Singular copy.** For n=1 the copy reads "1 product … its allergens", and "skipped because {Shop} already has it". The UI-SPEC writes "{n} products"; this is the same sentence read correctly.
- **The import page fetches the shop names itself** (`fetchAllMyShops`), as the products page does. The names are labels only, because the server gates on the id. The page also works when rendered outside the dashboard shell's provider.
- **A refused import or copy shows the server's typed detail** (403 shop-access-denied) instead of the old generic "Could not process CSV file".

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The Bulk Import "dialog" is a page: `frontend/app/dashboard/products/import/page.tsx`** (not in the plan's file list)
- **Found during:** Task 2.
- **Issue:** The plan placed the import target in the Bulk Import dialog in `products/page.tsx`. That page only links to `/dashboard/products/import`, where the CSV tab posts the file. WINDOWS #27 already named the import page.
- **Fix:** The target line, Select, hint and guard went into the import page's CSV tab.
- **Committed in:** `1c8ee404`

**2. [Rule 3 - Blocking] `frontend/types/api.ts` and `frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx`** (not in the plan's file list)
- **`types/api.ts`:** `Product` and `CreateProductRequest` had no `vatRate`, and there were no types for the import or copy responses.
- **`checkout.test.tsx`:** its fee-breakdown arm asserts the "VAT (incl. 20%)" row, so its fixture now declares a VAT number, the same change as in vat-preview. With that change its assertion keeps its meaning.
- **Committed in:** `cc9ec0df`, `77d1310c`, `1c8ee404`

**3. [Plan file not changed] `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx`**
- **Issue:** The plan lists this file for gating, but it renders no VAT row, so there was nothing to gate (see Decisions).
- **Coverage:** The order-page arm in `vat-registration.test.tsx` covers it, and break arm C is its fail direction.

**4. [Rule 1 - Instrument] The first Task 2 RED form of the no-request arm**
- **Issue:** It waited for the new Select, so on the old page it failed on a missing control rather than on the request. That would not have shown the defect.
- **Fix:** It was rewritten, before the RED record, to wait for the upload control. On the old page it then failed on the shop-less POST itself. A first rewrite also waited for a shops GET that the old page never makes. That wait was removed and was not counted.

---

**Total deviations:** 4 (1 Rule 1 instrument, 2 Rule 3, 1 plan file needing no change). **Impact:** none on scope. Every extra file serves B6 or B7.

## Goods displaced (Incremental Betterment)

- **The "VAT (incl. 20%)" row for an unregistered seller is removed.** That is strictly better, because the row was false. A registered seller keeps the row unchanged, including the COR-6 rate label, and arm G shows the rate arms still bite.
- **The import drop zone used to accept a file at any time.** On All shops it now waits for a target shop. The posted file used to create nothing anyway ("shop required" since 37-13), so nothing that worked was lost.
- **The products header can now hold three buttons.** Its row is now `flex-wrap`, so at 375px the buttons wrap instead of clipping (the FEB-2 rule).
- **The stock tracking toggle is unchanged.** A test arm now pins "tracking off sends `quantityInStock: null`" ahead of the 37-39 change.

## Issues Encountered

- **No live browser run (WINDOWS #28).** The compose runtime predates 37-13, so a live run would exercise the old backend. Live proof is owed to 37-15.
- **The VAT helper copy needs owner sign-off** (UI-SPEC open item 1). It ships as written and goes to the owner in 37-15.
- **The photo (AI) import is unchanged.** It is still Group-admin only and creates unassigned drafts by design (37-13 §3-FLAG #1). This plan's import target applies to the CSV tab only.
- **`docs/metrics.json` drift from the +29 jest blocks** is Phase 37 deferred item 1. It is handled at the phase PR, not here.

## Known Stubs

None. The only "placeholder" strings added are Radix `SelectValue` prompts ("Choose a shop", "Keep the current rate").

## Threat Flags

None beyond the plan's register:
- **T-37-38 (mitigated):** the VAT row is gated on `vatNumber`, with fail arms A and B.
- **T-37-39 (mitigated):** the target is shown explicitly, the request carries `shopId`, the handler refuses without one (arms H and H2), and the server gates it (37-13).
- **T-37-SC:** no package was installed (0-byte diff).

## User Setup Required

None.

## Next Phase Readiness

- **37-15:**
  - Rebuild the frontend and core-java.
  - In a live browser, prove:
    - a sole-trader shop's checkout and cash confirmation show the B7 note and no VAT;
    - a VAT-registered shop shows its row;
    - a new product saves with Standard and a Zero choice persists;
    - an All-shops import requires a target and lands its rows on that shop's storefront;
    - a copy reports created and skipped counts, and a repeat copy reports all skipped.
  - Present the VAT helper copy for owner sign-off.
- **Requirements:** RWO-046, RWO-047 and RWO-026 are listed as this plan's contribution. `requirements.ready-ids` reports 0/3 ready (shared with a later plan), so their REQUIREMENTS.md checkboxes stay open.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*

## Self-Check: PASSED

- FOUND: copy-to-shop-dialog.tsx (contains "Copy to"), vat-registration.test.tsx (contains "hasn't declared a VAT registration"), product-vat-rate.test.tsx, import-copy.test.tsx, evidence/37-14-red-vat-display.json, evidence/37-14-red-import-copy.json
- FOUND commits (ancestors of HEAD): cc9ec0df, 77d1310c, 4ecc06a7, 1c8ee404
- Commit count measured from the ledger: `git rev-list --count 88d5f5dd..1c8ee404` = 4
- Key link: `checkout/page.tsx` gates the VAT rows on `shop?.seller?.vatNumber`
