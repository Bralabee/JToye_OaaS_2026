---
phase: 37-real-world-operations-readiness
plan: 30
subsystem: ui
tags: [bulk-reject, reject-dialog, cancel-reason, unverified-contact, contact-verified, order-cap, order-limit, rfc7807, stepper-limit, max-line-quantity, customer-token, httponly-proxy, retention-register, r-16, client-ip-digest, d-18, d-20, d-27, rwo-041, rwo-042, jest, tdd]

requires:
  - phase: 37-29
    provides: "POST /api/v1/orders/bulk-cancel {orderIds, reasonCode} -> {results[{orderId, outcome}]} with REJECTED | ALREADY_ACCEPTED | ALREADY_CANCELLED | NOT_FOUND, idempotent by construction; the four CancelReason codes; 400 bulk-cancel-too-large"
  - phase: 37-27
    provides: "OrderDto/OrderDetailDto.contactVerified (NON_NULL: absent = not recorded); the 429 order-cap-exceeded {axis EMAIL|PHONE|IP, cap} with no Retry-After; X-Customer-Token read on the guest-order POST"
  - phase: 37-28
    provides: "the IP axis and the client-IP digest's declared lifetime (jtoye.storefront.client-ip-digest.retention-hours 72, ClientIpDigestRetentionSweep) that R-16 publishes"
  - phase: 37-26
    provides: "PublicShopDto.maxLineQuantity and the 422 order-limit-exceeded {field quantity|units|lines|total, value, limit, productName}"
  - phase: 37-16
    provides: "the kitchen New lane and its single Reject, replaced here by the shared dialog"
provides:
  - "RejectOrdersDialog (components/dashboard/orders/reject-orders-dialog.tsx): ConfirmActionDialog shape + a REQUIRED reason Select over the four default reasons + a per-order result summary with 'Close summary' (no auto-close); every submit is POST /api/v1/orders/bulk-cancel, a batch of one included; the body says the reason stays on the order and isn't sent to the customer"
  - "Orders page bulk selection: a 24px Checkbox in a 44px cell on PENDING rows only (the column exists only while a PENDING row is on the page), a header checkbox over exactly those rows, the sticky safe-area-padded bar '{n} selected · Reject {n} orders · Clear selection', the re-read only after the summary closes"
  - "Kitchen New lane selection mode: 'Reject several orders' at >= 2 cards, a checkbox row per card ('Select order {number}'), the same sticky bar with 'Stop selecting', Accept untouched; the per-card Reject through the same dialog; a refused outcome re-reads the board on close (KdsNewLane.onRefresh)"
  - "UnverifiedContactBadge (components/dashboard/kitchen/unverified-contact-badge.tsx): bg-white border-dashed border-slate-500 text-slate-700, UserX, 'Unverified contact' + the explanatory title/sr text; rendered ONLY for contactVerified === false (F20), on New and grid kitchen cards, the Orders list Customer cell and the order detail; Order/OrderDetail.contactVerified typed"
  - "Checkout refusal copy: the cap 429 by axis (EMAIL/PHONE the customer sentence, IP the connection sentence, ', or pay by card' only when shop.acceptsCardPayments) and the limit 422 by field with every number from the problem body; branched on type before describeOrderError; exactly one POST, basket kept"
  - "The signed-in customer's order is posted to the same-origin proxy POST /api/customer-orders?shop={slug}, which forwards the body, the Idempotency-Key and any X-Forwarded-For chain and adds X-Customer-Token from the HttpOnly cookie (the /orders/mine shape); a guest's goes straight to core"
  - "Storefront steppers (dish card, dish modal, basket drawer) stop at PublicShop.maxLineQuantity, held on the cart context as lineLimit, with the 14px hint 'Max {n} per order' wired by aria-describedby; absent = no client limit"
  - "Retention row R-16 (the connection pseudonym on a cash order, up to 72 hours or until the order completes/cancels) on /legal/retention, in docs/retention-manifest.json (client_ip_digest_hours, enforced at application.yml retention-hours, consumer ClientIpDigestRetentionSweep) and in scripts/gates/claims.manifest; check-retention-enforcement PASS 15 rows / 7 comparisons, check-claims PASS 50"
affects: [37-31, 37-D-gate, kitchen-board, orders-page, storefront-checkout, legal-retention, k8s-operators]

# Actuals (#2632): chars/4 over the realized diff 1fba16a1..9e1d3d42 (30 files, 2611 insertions, 84 deletions).
# The plan estimated 75000 (raw 150000). The three RED evidence records (TAP output of 12/417/520 tests) are
# 76 % of the diff; the code, tests and docs alone are 30141.
actuals:
  tokens: 124073
  tasks: 3
  commits: 7
plan_head_before: 1fba16a184ab3f2cffd0a91aa96f5db07c506fb5
plan_head_after: 9e1d3d426ae17318c14929036bade422aee89a4b

tech-stack:
  added: []
  patterns:
    - "One destructive dialog for one and many: the single action is a batch of one through the batch endpoint, so the race ('someone accepted it a second ago') is classified by the server and READ in a summary instead of silently cancelling an accepted order"
    - "A dialog that lives inside the thing it removes defers the removal until its summary closes (rejectedIdsRef), or the card unmounts the summary before it can be read"
    - "A per-page selection held as ids and re-derived against the rows on screen, never pruned in an effect"
    - "A browser-unreadable credential (HttpOnly) rides a same-origin proxy that adds it server-side; the client chooses the URL by signed-in state and the error shape stays axios's, so every typed-error branch is untouched"
    - "A server-declared limit flows through the cart context (React state, never persisted) so three steppers in one provider stop at one number with one hint"
    - "A frontend RED needs a scaffold that exports the names and renders null, so the suite LOADS and fails on its planned assertions (a missing module is a load failure, which the classifier refuses)"

key-files:
  created:
    - frontend/components/dashboard/orders/reject-orders-dialog.tsx
    - frontend/components/dashboard/kitchen/unverified-contact-badge.tsx
    - frontend/components/dashboard/orders/__tests__/reject-orders-dialog.test.tsx
    - frontend/app/dashboard/orders/__tests__/bulk-reject.test.tsx
    - frontend/components/dashboard/kitchen/__tests__/kds-bulk-reject.test.tsx
    - frontend/components/dashboard/kitchen/__tests__/unverified-contact-badge.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/abuse-refusals.test.tsx
    - frontend/__tests__/shop/stepper-limit.test.tsx
    - frontend/app/legal/__tests__/retention-r16.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-30-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-30-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-30-red-task3.json
  modified:
    - frontend/app/dashboard/orders/page.tsx
    - frontend/components/dashboard/kitchen/kds-new-lane.tsx
    - frontend/components/dashboard/kitchen/__tests__/kds-new-lane.test.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/components/dashboard/orders/OrderDetailPanel.tsx
    - frontend/components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx
    - frontend/types/api.ts
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/api/customer-orders/route.ts
    - frontend/app/api/customer-orders/__tests__/route.test.ts
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/components/storefront/product-detail-modal.tsx
    - frontend/components/storefront/cart-drawer.tsx
    - frontend/components/storefront/cart-provider.tsx
    - frontend/types/storefront.ts
    - frontend/app/legal/retention/page.tsx
    - docs/retention-manifest.json
    - scripts/gates/claims.manifest

key-decisions:
  - "Every reject, one order or many, is POST /orders/bulk-cancel: the single cancel (optional {reasonCode}) would also cancel a CONFIRMED order, so a kitchen tapping Reject on a card a colleague accepted a second earlier would cancel an accepted order; the batch path classifies on the committed state and the dialog reads ALREADY_ACCEPTED, which is the race UI-SPEC D1's result copy exists for. The single cancel's body stays an API-caller feature"
  - "The result summary words the two outcomes the spec leaves unworded (ALREADY_CANCELLED -> '{k} had already been rejected.', NOT_FOUND -> '{j} couldn't be found.') in the same shape with zero counts omitted, so a batch is never summarised with orders unaccounted for; a DRAFT id (ALREADY_ACCEPTED, 37-29) is folded into 'already been accepted' and is unreachable from these PENDING-only surfaces"
  - "UI-SPEC D1 'empty' (unresolved, planner assumption): with no PENDING row on the page the selection column and the header checkbox are not rendered; the Orders bar renders only while >= 1 is selected; the kitchen bar renders throughout selection mode ('0 selected', Reject disabled) because 'Stop selecting' lives on it; selection mode ends when a batch's summary closes"
  - "The Orders page re-reads only after the summary closes: fetchData swaps the page for its spinner, which would unmount the dialog before the summary could be read"
  - "The kitchen's single-card dialog defers the card's removal to 'Close summary' (the card is the dialog's host); the lane's bulk dialog removes at once (it lives in the lane); either re-reads the board on close when any id was refused"
  - "The signed-in customer's order goes through POST /api/customer-orders?shop= (an absolute same-origin URL on publicApiClient, so axios's error shape and every typed-error branch are unchanged): the access token is an HttpOnly cookie the browser cannot read, so 'the same way /orders/mine does' is the only way to send it. Without the cookie the proxy places a guest order rather than 401, so a lapsed session degrades to exactly a guest order"
  - "The storefront line limit lives on the cart context (lineLimit/setLineLimit, React state only), published by the shop page from PublicShop.maxLineQuantity, so the card, the modal and the drawer (which has no shop of its own) stop at one number with one hint; the shop page optional-calls the setter so harnesses that stub useCart do not throw"
  - "The limit hint renders only AT the limit (aria-describedby set only then), so a below-limit stepper carries no idle sentence"
  - "R-16 is phrased as the ceiling for an order that never reaches a terminal state ('Up to 72 hours, or until the order is completed or cancelled if sooner'); the claims rule reads the integer before ' hours' off the named constant, the R-14/R-15 shape"

patterns-established:
  - "Bulk = batch of singles, outcomes not exceptions, read in a summary before the dialog closes"
  - "Stored-fact badges: render for the stored false only; absent is not recorded and renders nothing (F20)"
  - "Typed refusals are branched on `type` before any generic path, and the client holds none of the numbers"

requirements-completed: [RWO-041, RWO-042]

coverage:
  - id: D1
    description: "RejectOrdersDialog: plural/singular titles and labels, the four reasons, confirm disabled until a reason is chosen, the bulk-cancel POST with the chosen code, 'Working…' in flight, the per-order summary with zero counts omitted, no auto-close, 'Close summary', a failed request kept open with an inline alert"
    requirement: RWO-041
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/orders/__tests__/reject-orders-dialog.test.tsx (7 cases)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Orders page: checkboxes on PENDING rows only, header select-all over exactly those rows, the sticky bar, Clear selection, the dialog flow through 'Close summary' and the re-read, no column/bar with no PENDING rows, a checkbox tap never opens the order (router.push control)"
    requirement: RWO-041
    verification:
      - kind: automated_ui
        ref: "frontend/app/dashboard/orders/__tests__/bulk-reject.test.tsx (6 cases)"
        status: pass
      - kind: other
        ref: "break arm: showSelection forced true -> 'with no PENDING rows there is no header checkbox…' 1 failed / 4 passed; restore md5 cb2c10ff37da8e750c79e8ae5ff14610 before and after; closing clean 116/0"
        status: pass
    human_judgment: false
  - id: D3
    description: "Kitchen New lane: 'Reject several orders' at >= 2 cards, selection mode with a checkbox per card and the bar, Accept untouched, Stop selecting, the bulk POST and summary, the re-read after a refused outcome; the single Reject through the dialog with a required reason; the badge on New and grid cards"
    requirement: RWO-041
    verification:
      - kind: automated_ui
        ref: "frontend/components/dashboard/kitchen/__tests__/kds-bulk-reject.test.tsx (5 cases) + kds-new-lane.test.tsx 'Reject order (single, through RejectOrdersDialog)' (3 cases)"
        status: pass
    human_judgment: false
  - id: D4
    description: "UnverifiedContactBadge reads the stored fact: false renders it with the title and sr text, null/absent/true render nothing, on the badge, the Orders list, the detail and the kitchen cards (F20)"
    requirement: RWO-041
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/kitchen/__tests__/unverified-contact-badge.test.tsx (4) + OrderDetailPanel.test.tsx + bulk-reject.test.tsx badge cases"
        status: pass
      - kind: other
        ref: "F20 break arm: `if (contactVerified) return null` (derived from the absence) -> 5 failed / 41 passed across the four suites, exactly the null/absent cases on every surface; restore md5 ba6f16b449d54990107723db50fba547 before and after (equal to HEAD's file); closing clean 417/0"
        status: pass
    human_judgment: false
  - id: D5
    description: "Checkout: the cap 429 by axis with and without ', or pay by card', exactly one POST and the basket kept; the limit 422 for quantity (literal-free arm), total, units and lines from the body; the 60-character item renders in full in a slot that can wrap"
    requirement: RWO-042
    verification:
      - kind: automated_ui
        ref: "frontend/app/shop/[slug]/checkout/__tests__/abuse-refusals.test.tsx (10 cases)"
        status: pass
      - kind: other
        ref: "break arm: a second POST added in the cap branch -> the EMAIL and IP cases fail on toHaveBeenCalledTimes(1) (2 failed / 7 passed); restore md5 03f8c763231a0eeaa91d2bd575ee80e4; closing clean 558/0"
        status: pass
    human_judgment: false
  - id: D6
    description: "The signed-in customer's POST goes to /api/customer-orders?shop= with no token on the browser request; the guest's to /public/shops/{slug}/orders; the route forwards body, Idempotency-Key and the cookie's token, omits the token without the cookie, passes status/body/Retry-After through, refuses a malformed slug 400, maps a network failure to 502"
    requirement: RWO-041
    verification:
      - kind: automated_ui
        ref: "abuse-refusals.test.tsx 'carries the customer token' (2) + frontend/app/api/customer-orders/__tests__/route.test.ts POST (5)"
        status: pass
    human_judgment: false
  - id: D7
    description: "The dish card, dish modal and basket drawer steppers disable '+' at maxLineQuantity with 'Max {n} per order' wired by aria-describedby; absent -> no client limit"
    requirement: RWO-042
    verification:
      - kind: automated_ui
        ref: "frontend/__tests__/shop/stepper-limit.test.tsx (4 cases)"
        status: pass
      - kind: other
        ref: "break arm: `quantity >= (lineLimit ?? 10)` (a client literal) -> 'absent maxLineQuantity…' 1 failed / 3 passed; restore md5 38cc2ae5baccc862f37c6458f86e6372; closing clean 4/0"
        status: pass
    human_judgment: false
  - id: D8
    description: "R-16 published and gated: the manifest row, the flat key, the page row, check-retention-enforcement and check-claims"
    requirement: RWO-041
    verification:
      - kind: unit
        ref: "frontend/app/legal/__tests__/retention-r16.test.tsx (2) + components/legal/__tests__/retention-table.a11y.test.tsx (field-by-field, row count 15)"
        status: pass
      - kind: other
        ref: "scripts/check-retention-enforcement.sh PASS 15 rows / 7 comparisons ('OK R-16 72 hours == 72 hours at application.yml:retention-hours'); break arm period_value 48 -> FAIL '[R-16] published 48 hours … holds 72 hours'; scripts/check-claims.sh PASS 50; break arm flat key 48 -> FAIL 'claims 72, retention says 48'; restore md5 e885b0bb15432e175b2072547768c103; closing clean PASS/PASS"
        status: pass
    human_judgment: false
  - id: D9
    description: "The surfaces look right at 375px (the bar's safe-area padding, the badge beside a long name, the hint beside the stepper, the error slot wrapping)"
    requirement: RWO-042
    verification: []
    human_judgment: true
    rationale: "No browser proof in this plan: the compose stack still runs 6a825865's images (37-26..37-29 behaviour absent), so a live run could not show these refusals; proof is by jest render over the real pages with the network mocked. The 375px visual pass is 37-31's Playwright spec (the UI-SPEC long-text row's named backstop) and the 37-D gate's rebuilt stack."

duration: 34min
completed: 2026-10-12
status: complete
---

# Phase 37 Plan 30: Bulk reject, the unverified badge and the customer's cap and limit refusals Summary

**Vendors clear a flood of junk orders in one action from the Orders page or the kitchen's New lane through one `RejectOrdersDialog` — a required reason kept on the order and never sent to the customer, then a per-order summary read before the dialog closes, every submit a batch through 37-29's `/bulk-cancel` so an order a colleague accepted a second earlier reads "already been accepted" instead of being cancelled; every order surface shows "Unverified contact" from the stored `contactVerified: false` and nothing from absent; customers meet the cash-order cap and the order limits as honest, typed sentences with every number from the server's body and no retry; a signed-in customer's order carries the token through the same-origin proxy; the steppers stop at the shop's `maxLineQuantity`; and the retention register publishes R-16 for the client-IP pseudonym, gated to the 72.**

## Performance

- **Duration:** 34 min (plan start 2026-10-12T00:42:24Z; last code commit `9e1d3d42` at 01:15Z; gates, break arms and this SUMMARY after)
- **Started:** 2026-10-12T00:42:24Z
- **Completed:** 2026-10-12
- **Tasks:** 3 of 3, each RED (`test(37-30)`, RED_EVIDENCE_OK) then GREEN (`feat(37-30)`); one extra `test(37-30)` for the UI-SPEC long-text backstop; no REFACTOR commit was needed
- **Files modified:** 30 across the seven commits (2611 insertions, 84 deletions), plus this SUMMARY and deferred-items.md

## Accomplishments

- **Task 1 (tracer).** `RejectOrdersDialog` + the Orders page selection. **RED** (`5491e2ce`; `37-30-red-task1.json` 12 ran / 11 failed, RED_EVIDENCE_OK — the dialog cases on a scaffold rendering null, the page cases on "no checkbox 'Select order ORD-p1'"; the one green, "no PENDING rows -> no header checkbox", holds on the pre-plan tree by construction and was recorded, then broken at GREEN time: column forced unconditional -> 1 failed / 4 passed). **GREEN** (`e6ae60f0`): 116/0 over `components/dashboard/orders app/dashboard/orders`. **Tracer gate** (interactive, `end-of-phase`, automated-only `<verify>`): the verify re-run on the committed tree, 116/0 — expanded.
- **Task 2.** `UnverifiedContactBadge`, the lane's selection mode, the shared dialog for the single Reject, the badge on four surfaces, `KdsNewLane.onRefresh`. **RED** (`cdc0f3f1`; 417 ran / 10 failed, RED_EVIDENCE_OK). **GREEN** (`e4e3d3d9`): 417/0 over the kitchen and orders directories. The GREEN was first committed with one case still red (I committed before reading the run — a process slip, recorded in Issues); the fix was a real defect, not the test: the single-card dialog lives inside the card, so removing the order on `onRejected` unmounted the summary before it could be read; the removal now waits for "Close summary". The commit was amended (my own, unpushed) and the closing clean run is 417/0 on `e4e3d3d9`. **F20 break arm**: the badge derived from the absence of a confirmation turned the null/absent cases red on every surface (5 failed / 41 passed); restore md5-verified.
- **Task 3.** The checkout copy, the proxy route, the steppers, R-16. **RED** (`b0c35fb4`; 520 ran / 18 failed, RED_EVIDENCE_OK — the cap 429 read the rate-limiter's "wait a moment" copy, the 422s showed the server's detail string, the route exported no POST, no stepper stopped, no R-16). **GREEN** (`a3a7a5b9`): 558/0 over the plan's pattern + `components/legal` + the route tests; `npm run build` rc 0 ("Compiled successfully"); `check-retention-enforcement` PASS 15 / 7 comparisons; `check-claims` PASS 50. Three break arms (a client literal for the limit; an auto-retry in the cap branch; the manifest's period and flat key drifted to 48) each red in the named place, each restore md5-verified, closing clean PASS/PASS/558/0. `9e1d3d42` adds the long-text backstop (a 60-character item in full, no truncation class on the slot).
- **Regression** on `9e1d3d42`: full jest **229 suites / 2672 / 0** (2 snapshots); `npx tsc --noEmit -p tsconfig.json` (the CI gate that includes test files) rc 0; `npm run lint` 0 errors / 31 warnings (per-file lint of every touched file shows only two pre-existing warnings: `orders/page.tsx` `watch()` and `checkout/page.tsx` `const router` at 297 on the pre-plan tree); gitleaks over `1fba16a1..HEAD` **no leaks** (7 commits, no allowlist change); `check-doc-metrics` PASS 37/37; `check-doc-citations` PASS 29/0 (7 uncheckable incl. the pre-existing `terminal-states.yaml` VOID, rc 0).
- **Task 1 acceptance** (`rg -uu -n -i 'fake' frontend/app/shop frontend/components/storefront`): 12 lines before, 12 after, the line-number-insensitive diff empty (all 12 are `useFakeTimers` in tests); the positive control finds the vendor-facing reason in the dialog file. No customer surface writes "Fake".

## Task Commits

1. **Task 1: Reject several orders from the Orders page with a kept reason** — `5491e2ce` (test, RED), `e6ae60f0` (feat, GREEN)
2. **Task 2: Bulk and single reject in the kitchen, and the unverified badge everywhere** — `cdc0f3f1` (test, RED), `e4e3d3d9` (feat, GREEN; amended once, see above)
3. **Task 3: Customer-facing cap and limit refusals, the stepper limit, the customer token, R-16** — `b0c35fb4` (test, RED), `a3a7a5b9` (feat, GREEN), `9e1d3d42` (test, the long-text backstop)

**Plan metadata:** this SUMMARY and `deferred-items.md` in the docs commit that follows. STATE.md, ROADMAP.md and REQUIREMENTS.md are the orchestrator's.

`commits: 7` is `git rev-list --count 1fba16a1..9e1d3d42` from the persisted ledger `.git/gsd-plan-head-before-37-30`; `gsd_run check evaluation-scope --plan 37-30 --commits-only` -> `status: resolved`, the same 7 commits.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `5491e2ce` test(37-30), RED_EVIDENCE_OK (`37-30-red-task1.json` 12 / 11) | `e6ae60f0` feat(37-30) | none needed |
| 2 | `cdc0f3f1` test(37-30), RED_EVIDENCE_OK (`37-30-red-task2.json` 417 / 10) | `e4e3d3d9` feat(37-30) | none needed |
| 3 | `b0c35fb4` test(37-30), RED_EVIDENCE_OK (`37-30-red-task3.json` 520 / 18) | `a3a7a5b9` feat(37-30) | none needed |

Evidence format: TAP (flat full names) from a scratchpad jest reporter, the 37-16 precedent (jest is not a TAP producer and `jest-junit` is not installed). Semantic assessment for all three: the target executed against the real page with only the network, session, socket, toast, Stripe and the Radix Select mocked, and failed on its planned assertion for the intended reason (no selection on the Orders page; no selection mode in the lane; the cap 429 rendered as the rate-limiter's copy); every pre-existing suite in the same directories stayed green in the same run; no load, syntax, fixture or zero-discovery fault. Each RED commit carries a scaffold component exporting the names and rendering null so the new suites load; the cases that were green by construction on the RED tree are named in each record with their GREEN-time break arm.

## Files Created/Modified

- `components/dashboard/orders/reject-orders-dialog.tsx`: the dialog, the four reasons, the copy helpers (`rejectTitleCopy`, `rejectConfirmCopy`, `rejectBodyCopy`, `rejectResultCopy`, `describeRejectFailure`), one `/bulk-cancel` POST per submit
- `app/dashboard/orders/page.tsx`: `filteredOrders`/`pendingOnPage`/`showSelection`, the Checkbox column and header, the sticky bar, the dialog, the badge in the Customer cell, `pb-28` while the bar shows
- `components/dashboard/kitchen/unverified-contact-badge.tsx`: the badge; `kds-new-lane.tsx`: selection mode, the bar, the bulk dialog, the single-card dialog with deferred removal, the badge, `onRefresh`; `app/dashboard/kitchen/page.tsx`: `onRefresh={fetchOrders}`, the badge on grid cards; `OrderDetailPanel.tsx`: the badge beside the name; `types/api.ts`: `contactVerified`
- `app/shop/[slug]/checkout/page.tsx`: `readOrderCapProblem`/`orderCapCopy`/`readOrderLimitProblem`/`orderLimitCopy`, `customerSignedIn`, the proxy URL, the two branches before `describeOrderError`
- `app/api/customer-orders/route.ts`: `POST` (slug-validated, body + Idempotency-Key + X-Forwarded-For forwarded, X-Customer-Token from the cookie, status/body/Retry-After through, 502 on a network failure)
- `components/storefront/cart-provider.tsx`: `lineLimit`/`setLineLimit`; `shop-detail-client.tsx`: publishes `shop.maxLineQuantity`, the card stepper's disable + hint; `product-detail-modal.tsx`: `lineLimit` prop, `lineLimitHintCopy`; `cart-drawer.tsx`: the drawer stepper; `types/storefront.ts`: `maxLineQuantity`
- `app/legal/retention/page.tsx`: `PERIOD_CONNECTION_PSEUDONYM`, row R-16, the header comment (seven gated periods); `docs/retention-manifest.json`: `client_ip_digest_hours: 72` + row R-16; `scripts/gates/claims.manifest`: the R-16 rule
- Tests: the nine new/extended suites in `key-files`; `evidence/37-30-red-task{1,2,3}.json`

## Decisions Made

See `key-decisions`. Two depart from the plan's letter: every reject is a `/bulk-cancel` batch (the dispatch mentions the single cancel's optional body; the race argument is in the first decision and in the dialog's header), and the hint renders only at the limit rather than beside every stepper.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] A same-origin proxy POST for the signed-in order (file not in the plan's list)**
- **Found during:** Task 3
- **Issue:** the plan says "send the customer token the same way /orders/mine does"; the access token is an HttpOnly cookie the browser cannot read, so the only way is the server-side proxy `/orders/mine` uses. `app/api/customer-orders/route.ts` is not in the plan's file list
- **Fix:** `POST /api/customer-orders?shop=` beside the existing GET, with its five route tests; the checkout picks the URL by signed-in state
- **Verification:** D6; `npm run build` rc 0
- **Committed in:** `a3a7a5b9`

**2. [Rule 3 - Blocking] The line limit on the cart context (cart-provider.tsx, types/storefront.ts not in the list)**
- **Found during:** Task 3
- **Issue:** the basket drawer has no shop and no slug-scoped shop read; `PublicShop` had no `maxLineQuantity` type
- **Fix:** `lineLimit`/`setLineLimit` on `CartContextValue` (React state), published by the shop page; the type added
- **Committed in:** `a3a7a5b9`

**3. [Rule 3 - Blocking] `Order`/`OrderDetail.contactVerified` typed (types/api.ts not in the list)**
- **Found during:** Task 2 RED (the `tsc` gate includes test files)
- **Committed in:** `cdc0f3f1`

**4. [Rule 1 - Bug] The single-card dialog's summary vanished with the card**
- **Found during:** Task 2 GREEN
- **Issue:** `onRejected` removed the order at once; the dialog is the card's child, so the summary unmounted before it could be read (UI-SPEC: it does not auto-close)
- **Fix:** `rejectedIdsRef` — the card leaves on "Close summary"
- **Verification:** `kds-new-lane.test.tsx` "asks first … Close summary leaves the lane"; closing clean 417/0
- **Committed in:** `e4e3d3d9` (amended)

**5. [Rule 2 - Missing critical] `KdsNewLane.onRefresh` (kitchen page wiring)**
- **Found during:** Task 2
- **Issue:** after a refused outcome (ALREADY_ACCEPTED) the lane would keep showing an order that is CONFIRMED elsewhere until the next poll
- **Fix:** the lane asks the page to re-read when any id was refused; the page passes `fetchOrders`
- **Committed in:** `e4e3d3d9`

### Plan wording that did not hold (recorded, not relabelled)
- **"the optional {reasonCode} body on the single cancel"** (dispatch essentials): not used by the UI — see the first decision. The body remains the API's.
- **"the drawer stepper stops at maxLineQuantity"** holds only once the shop page has published the limit in this provider; a deep link straight to `/checkout` leaves `lineLimit` null (the server's 422 is the gate, the UI-SPEC's declared other branch).
- **Visual proof**: by jest render, not by screenshot (D9). The frontend-craft-sequence's proof stage is deferred to 37-31 with the stale runtime as the reason.

**Total deviations:** 5 auto-fixed (3 blocking, 1 bug, 1 missing critical), 3 plan-wording records. **Impact:** no scope creep; each fix was required to do what the plan asked on a browser that cannot read the token, to type what the tests read, or to keep the summary readable.

## Issues Encountered

- **A commit before reading the run.** Task 2's GREEN was committed while one case was still red (the jest result was printed but not gated). Caught on the very next read, fixed as deviation 4, the commit amended (my own, unpushed) and the verify re-run clean on the amended head. Recorded so the orchestrator knows the first `e4e3d3d9`-less history never left this checkout.
- **This machine's older Select mock in `kds-new-lane.test.tsx`** has no placeholder option, so a native select already displays the first reason and `fireEvent.change` to that same value fires no `onChange`; the single-reject case picks a different code (the dialog's own state starts empty either way — the disabled confirm is the proof).
- **The signed-in proxy and the IP axis (37-28).** Core reads `X-Forwarded-For` only from a peer in `jtoye.security.trusted-proxies` (`TRUSTED_PROXY_CIDRS`, empty by default), and a Next 16 route handler cannot read its own socket peer, so a signed-in order placed through the proxy reaches core from the frontend container's address: every signed-in customer's cash orders at a shop share ONE IP digest, and the per-IP cap (5 open) could refuse legitimate signed-in customers in production. The route forwards the chain it receives (an ingress that sets it, with the frontend's range declared trusted, restores the client address). Recorded in deferred-items with the recommended fix (the IP axis should not count an order whose contact is verified — a server change in `CashOrderCaps`, or the operator's trusted-proxy declaration); surfaced for the owner / 37-31 / the 37-D gate.
- `scripts/docs-freshness.sh` rc 1 (`jest_blocks` 2627 -> 2672, `jest_files` 222 -> 229, `total_logical_invocations` 5626; the Java counts are 37-24..37-29's); `check-doc-metrics` PASS 37/37 (no count moved by hand); owed to the 37-D PR docs step (deferred-items).
- `scripts/check-openapi-snapshot-fresh.sh` FAIL rc 1 (the stack runs `6a825865`'s images, Up 7 hours); expected until the 37-D gate rebuilds.
- `npm run lint` 31 warnings project-wide, 0 errors; none introduced (per-file runs on every touched file show only the two pre-existing ones).

## Known Stubs
None. The dialog, the badge, the copy, the proxy, the steppers and R-16 are wired to their data.

## Threat Flags

None new beyond the plan's register. **T-37-76** (customer token to the wrong origin) is mitigated: the token never reaches browser JS; the proxy sends it only to `${coreBaseUrl()}/public/shops/{slug}/orders` with a slug validated by pattern (a traversal slug is 400 before any call, tested); **T-37-77** (auto-retrying the cap) is mitigated by type-branching before the 429 path and the exactly-one-POST arm with its break arm; **T-37-SC** held — nothing installed (`package.json`, `package-lock.json` untouched). New surface, recorded: `POST /api/customer-orders` is an unauthenticated same-origin endpoint that forwards a JSON body to core's public order endpoint — the same surface the browser already has directly, now also reachable server-side; it forwards only the body, the Idempotency-Key, the token and the X-Forwarded-For chain, and nothing it receives is logged. Residual, by design: the IP-axis consequence in Issues.

## User Setup Required

None for this plan. The IP-axis note in deferred-items names an operator decision (`TRUSTED_PROXY_CIDRS`) that is not required for the frontend to work.

## Next Phase Readiness

- **37-31 (E2E):** the vendor flow is Orders page -> tick PENDING rows -> "Reject {n} orders" -> reason -> "Reject {n} orders" -> read the summary -> "Close summary"; the kitchen flow is "Reject several orders" -> tick -> the same dialog; a single card's Reject is the same dialog with "Reject order {number}?". The badge needs a storefront order placed without a signed-in customer (`contactVerified: false`); the cap copy needs four cash orders with the same email at one shop; the stepper limit reads the shop's `maxLineQuantity`; the 375px visual pass (the bar's safe-area padding, the long item name in the error slot) is this plan's D9. The `getByText("Unverified contact")` target is the badge's own span.
- **37-D gate:** rebuild carries 37-26..37-30; `check-openapi-snapshot-fresh` stays FAIL until then; live probes in deferred-items. Decide the IP-axis note before the first signed-in cash orders go through the proxy in a shared environment.
- **Requirements:** RWO-041 and RWO-042 are declared by this plan (RWO-041 also by 37-27/37-28/37-29); the orchestrator's `requirements.ready-ids` gate decides readiness.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-12*

## Self-Check: PASSED

- FOUND: `reject-orders-dialog.tsx` (contains `Reason (kept on the order)` x1, `bulk-cancel` x4), `unverified-contact-badge.tsx` (contains `Unverified contact` x1), the nine test files, `evidence/37-30-red-task1.json`, `-task2.json`, `-task3.json`, this SUMMARY
- FOUND commits (ancestors of HEAD): `5491e2ce`, `e6ae60f0`, `cdc0f3f1`, `e4e3d3d9`, `b0c35fb4`, `a3a7a5b9`, `9e1d3d42`; `git rev-list --count 1fba16a1..9e1d3d42` = 7; `gsd_run check evaluation-scope --plan 37-30 --commits-only` -> `status: resolved`, 7 commits
- TDD gate: 3 `test(37-30)` RED commits each preceding its `feat(37-30)` GREEN (+1 backstop test commit after); 0 `refactor(37-30)`
- The committed badge's md5 `ba6f16b449d54990107723db50fba547` equals the F20 break arm's restore
- Verify commands re-run on the final tree: Task 1 116/0, Task 2 417/0, Task 3 558/0 + build rc 0; full jest 2672/0; tsc rc 0