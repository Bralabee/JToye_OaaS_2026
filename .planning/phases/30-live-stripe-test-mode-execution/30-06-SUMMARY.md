---
phase: 30-live-stripe-test-mode-execution
plan: 06
subsystem: frontend
tags: [kitchen, orders, kds, rfc7807, payments, issue-958, ui-spec-s2, tdd]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-05: HTTP 409 problem+json, type https://jtoye.uk/errors/awaiting-payment, staff detail sentence"
provides:
  - "frontend/lib/awaiting-payment.ts: isAwaitingPayment (OrderService.isAwaitingUpfrontOrDepositPayment l.1102-1110 transcribed), AWAITING_PAYMENT_PROBLEM_TYPE"
  - "frontend/components/dashboard/orders/awaiting-payment-badge.tsx: AwaitingPaymentBadge, AWAITING_PAYMENT_COPY, AWAITING_PAYMENT_TITLE"
  - "Orders list: no Submit/Confirm for an awaiting-payment order; Cancel on every DRAFT"
  - "Kitchen grid: destructive toast 'Order not updated' with the server detail, or 'Couldn't {start preparing|mark ready|complete} this order. Check your connection and try again.'"
  - "New lane: an awaiting-payment Accept refusal shows the server detail in the card's alert"
  - "types/api.ts Order declares paymentStatus / paymentReference"
affects: [30-07, 30-08, 30-09, 30-32]

actuals:
  tokens: 21055     # chars/4 over the realized diff 6cec1c63..eaec2371 (84220 chars); code, tests and prose alone (evidence excluded): ~11750
  tasks: 2
  commits: 7        # git rev-list --count 6cec1c63..eaec2371, measured before the SUMMARY commit

plan_head_before: 6cec1c635187d6fd67e475f152fdd7ba19420f9f
plan_head_after: eaec2371615826e54eecba9d93618118e8cc61f7

tech-stack:
  added: []
  patterns:
    - "A client-side display predicate kept equal to its server method arm for arm by a test that cites the Java line per case"
    - "Action lists derived from the whole order (status + payment fields), not the status alone"
    - "A refused mutation's toast reads the RFC 7807 detail when the server answered and a named-action fallback when it did not (describeLoadError would otherwise surface 'Network Error')"

key-files:
  created:
    - frontend/lib/awaiting-payment.ts
    - frontend/lib/__tests__/awaiting-payment.test.ts
    - frontend/components/dashboard/orders/awaiting-payment-badge.tsx
    - frontend/components/dashboard/orders/__tests__/awaiting-payment-badge.test.tsx
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-06-red-task1.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-06-red-task2.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-06-task1-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-06-task2-arms.txt
  modified:
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/orders/__tests__/page.test.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/app/dashboard/kitchen/__tests__/page.test.tsx
    - frontend/components/dashboard/kitchen/kds-new-lane.tsx
    - frontend/components/dashboard/kitchen/__tests__/kds-new-lane.test.tsx
    - frontend/types/api.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/30-live-stripe-test-mode-execution/deferred-items.md

key-decisions:
  - "DRAFT gains Cancel in the Orders list (for every DRAFT, not only unpaid ones). The plan and UI-SPEC both say Cancel 'stays' on an awaiting DRAFT, but the list never offered Cancel on a DRAFT: hiding Submit alone would have left an unpaid DRAFT with no action, and D-02 makes the vendor's manual cancel the only way to clear one. The server allows DRAFT -> CANCELLED"
  - "An absent paymentStatus with a reference IS awaiting payment (the plan's must_have, PR #1018 review finding 8), overriding the UI-SPEC sentence 'renders nothing when paymentStatus is absent'. The Java method has no null special case, and the client must agree with the server"
  - "The badge sits in the cards' header row beside the fulfilment and window badges (UI-SPEC: 'beside the order number, where a cook looks first'), not in the customer-name row beside UnverifiedContactBadge as the plan's action text said; in the Orders list it is beside UnverifiedContactBadge as both documents say"
  - "The grid's fallback verb is a failureVerb field on each bumpAction, not derived from the label by lowercasing ('Start Preparing' -> 'start preparing' works, but the copy row is the contract and should not depend on label wording)"

patterns-established:
  - "When a negative scan is evidence (no other bare catch near apiClient.post), run it first on a copy known to contain the shape"

requirements-completed: [PAY-04]

duration: 11min from the recorded start (19:47:56Z) to 19:58:44Z; context reading before the start was not timed
completed: 2026-10-22

coverage:
  - id: D1
    description: "isAwaitingPayment equals the server predicate arm for arm, including the null/undefined-status arms"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "frontend/lib/__tests__/awaiting-payment.test.ts (13 cases, each citing OrderService.java l.1104-1109; RED 8 failing on the stub -> GREEN; break arms: the absent-status short-circuit reds the 2 null-status arms, a truthy reference check reds the empty-reference arm)"
        status: pass
    human_judgment: false
  - id: D2
    description: "AwaitingPaymentBadge renders only for an awaiting order, with Hourglass, amber border-2, title and sr-only sentence"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/orders/__tests__/awaiting-payment-badge.test.tsx (5 cases)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Orders list: an awaiting DRAFT or PENDING row shows the badge, offers no Submit or Confirm, and keeps Cancel; a vendor DRAFT keeps Submit and has no badge"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/orders/__tests__/page.test.tsx 'Orders list: an order awaiting payment' (3 cases; RED -> GREEN; break arm without the filter reds both awaiting arms)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Kitchen grid: a refused bump toasts 'Order not updated' with the server detail verbatim, or the named-action fallback with no response; the board re-reads; Start Preparing stays enabled; an unpaid card carries the badge in its header"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/kitchen/__tests__/page.test.tsx 'KitchenPage grid: a refused bump says why' (6 cases; RED showed 'Failed to start preparing. Reverting.'; arms: old title, no own fallback, no re-read each red their target)"
        status: pass
    human_judgment: false
  - id: D5
    description: "New lane: an awaiting-payment Accept refusal shows the server detail in the card's alert; a blank detail falls through; the stock / moved-elsewhere / generic branches are unchanged; Accept stays enabled; the card carries the badge"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/kitchen/__tests__/kds-new-lane.test.tsx 'KDS New card: an unpaid order' (4 cases; RED showed 'Check your connection'; arms: branch disabled, no trim) and the pre-existing refusal arms"
        status: pass
    human_judgment: false
  - id: D6
    description: "The badge and the toast look right on a real KDS and Orders page at mobile and wall-screen sizes"
    requirement: PAY-04
    human_judgment: true
    rationale: "No browser run in this plan (the plan's verify is Jest + lint + build + counts). Placement is asserted structurally (inside kds-card-header / the customer cell), not visually"
---

# Phase 30 Plan 06: Staff see the refusal reason and the "Awaiting payment" marker Summary

**Staff can now see which orders are waiting for the customer's money, and are told why one will not move. An "Awaiting payment" badge (Hourglass, heavy amber border) marks such orders on the Orders list and on both kinds of kitchen card. The Orders list stops offering Submit and Confirm for them. The kitchen shows the server's own refusal sentence: in a destructive toast on the grid, and in the card's alert in the New lane.**

## Performance

- **Duration:** 11 min from the recorded start. The context reading done before that was not timed.
- **Started:** 2026-10-22T19:47:56Z
- **Completed:** 2026-10-22T19:58:44Z
- **Tasks:** 2 of 2 (Task 1 is a tracer)
- **Files modified:** 19 in the plan's commits (11 code and test files, 4 count and doc files, 4 evidence files), plus deferred-items.md in the metadata commit

## Accomplishments

- **One rule, the server's.** `isAwaitingPayment` transcribes `OrderService.isAwaitingUpfrontOrDepositPayment` (l.1102-1110):
  - PENDING or FAILED gives true.
  - Otherwise the result is true when the reference is `!= null` and the status is neither CAPTURED nor AUTHORIZED.
  - An absent status gets no special case.
  - The test cites the Java line on every case.
  - A vendor order (NONE, no reference) is never flagged (D-01).
- **The badge.** `AwaitingPaymentBadge` follows the `UnverifiedContactBadge` pattern: `Badge variant="outline"`, `border-2 border-amber-700 bg-white text-amber-800`, and a Hourglass glyph that is `aria-hidden`. It carries `data-testid="awaiting-payment"`, plus the UI-SPEC explanation as both `title` and an sr-only sentence.
- **Orders list.**
  - `getAvailableTransitions` now takes the whole order. It drops `submit` and `confirm` when the order is awaiting payment, in the table rows and in the detail panel.
  - Cancel now appears on every DRAFT (see Deviations).
  - The badge sits beside `UnverifiedContactBadge`.
- **Kitchen grid.** The bare `catch {}` became `catch (error)`.
  - The toast is now titled "Order not updated" and is still the destructive variant.
  - When the server answered, the description is the RFC 7807 `detail` (via `describeLoadError`).
  - When there is no response, it reads "Couldn't {start preparing | mark ready | complete} this order. Check your connection and try again."
  - `fetchOrders()` still runs, so the optimistic move reverts.
- **New lane.** `describeAcceptFailure` has a new first branch: an `awaiting-payment` type with a non-blank `detail` returns that detail. The stock, moved-elsewhere and generic branches are unchanged.
- **Buttons stay enabled.** Accept and Start Preparing stay enabled on an unpaid card (T-30-23). The badge warns before the tap, and the server's refusal explains after it.

## Task Commits

1. **Task 1 RED** `25f6f6d6`: test(30-06): failing tests for the predicate, the badge and the orders list
2. **Task 1 GREEN** `a593e02b`: feat(30-06): "Awaiting payment" in the orders list, no Submit/Confirm for it
3. **Task 1 arms** `eda63be7`: test(30-06): record the Task 1 break arms
4. **Task 2 RED** `8fde2ffa`: test(30-06): failing tests for the kitchen refusal reason and the badge
5. **Task 2 GREEN** `109f3de3`: feat(30-06): the kitchen shows why an order did not move, and marks unpaid orders
6. **Task 2 arms** `015d1de5`: test(30-06): record the Task 2 break arms, the build fail direction and the bare-catch survey
7. **Counts** `eaec2371`: docs(30-06): test counts 6562 -> 6593

No REFACTOR commit was needed.

## TDD Gate Compliance

### RED, Task 1 (`evidence/30-06-red-task1.json`, classifier `RED_EVIDENCE_OK`)
- **Target:** "Orders list: an order awaiting payment > an awaiting-payment DRAFT shows the badge, offers no Submit, and keeps Cancel".
- **Result:** 12 of the 28 tests failed, each on its planned assertion.
  - 8 predicate arms and the problem-type constant, run against stub exports that return false or empty. The stubs were committed with the tests so the suites load: without them, a module-not-found would be a load failure, which is INVALID_RED.
  - 2 badge-renders arms.
  - 2 orders-list arms (`Unable to find an element by: [data-testid="awaiting-payment"]`).
- **Passing arms:** the 16 that pass are the negative cases, which are true of the stub by construction, plus the existing page tests.
- **Semantic assessment:** assertion-level RED. Every target executed and there was no load fault.

### RED, Task 2 (`evidence/30-06-red-task2.json`, `RED_EVIDENCE_OK`)
- **Target:** the grid's 409 arm. The toast received `title: "Error updating order"`, `description: "Failed to start preparing. Reverting."`. That is the defect itself.
- **Result:** 7 of the 60 tests failed:
  - the grid 409 arm;
  - the grid network-fallback arm, three times (start preparing, mark ready, complete);
  - the grid badge arm;
  - the New lane 409 arm, whose alert read "Couldn't accept this order. Check your connection and try again.";
  - the New lane badge arm.

### GREEN
- Task 1: 28 of 28 tests pass.
- Task 2: 60 of 60 tests pass. That run includes the unchanged `unverified-contact-badge` suite.

## Fail directions (all on committed GREEN trees; restore by `cp`, verified by sha256 against the HEAD blob; closing clean run green)

| Arm | Break | Result |
|-----|-------|--------|
| A (review finding 8) | `if (status == null) return false` reintroduced | 2 red: null-status + reference, undefined-status + reference |
| C | `!!order.paymentReference` (empty string treated as unset) | 1 red: NONE + "" reference |
| B | Orders list stops filtering submit/confirm | 2 red: awaiting DRAFT, awaiting PENDING |
| D | toast title back to "Error updating order" | 2 red: grid 409, grid network |
| E | no own fallback (describeLoadError for a response-less error gives "Network Error") | 3 red: the three fallback arms |
| F | `fetchOrders()` removed from the catch | 2 red: both re-read assertions |
| G | New lane awaiting-payment branch disabled | 1 red: New lane 409 |
| H | New lane branch without `.trim()` | 1 red: blank-detail fall-through |
| Build | `const fallback: number = …` | `npm run build` rc 1 (TS2322 at page.tsx 638); restored, rc 0 |

- **Plan criterion:** `rg -uu -c 'Error updating order' frontend/app/dashboard/kitchen/page.tsx; echo rc=$?` prints rc=1. The control is the same command against the pre-GREEN copy (8fde2ffa), which counts 1.
- **Build content check:**
  - "Order not updated" is present in the kitchen client and SSR chunks under `frontend/.next` (rc 0).
  - "Error updating order" is absent from the build output (rc 1).

## Verification

- Plan verify 1, Task 1 suites: 3 suites, 28 tests, rc 0.
- Plan verify 2, Task 2 suites: 3 suites, 60 tests, rc 0.
- Plan verify 3, `npm run lint && docs-freshness.sh && check-doc-metrics.sh`: rc 0.
  - Lint: 0 errors and 28 warnings. Neither warning in a touched file is new: `ordersPayload` unused at kitchen test l.132, and react-hook-form `watch()` in the orders page.
  - Both count gates were shown red first. Freshness was rc 1 before `--write`. The prose gate was rc 1 with 10 FAIL lines across README, CLAUDE.md and AGENTS.md before the prose edit.
- Full Jest: 3207 of 3207 tests and 273 of 273 suites pass, rc 0. Both counts equal `docs/metrics.json`.
- `npm run build`: rc 0. "Compiled successfully" and "Finished TypeScript". Full-program `tsc --noEmit` also exits 0.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing functionality] Cancel added to DRAFT in the Orders list**
- **Found during:** Task 1, reading `getAvailableTransitions`.
- **Issue:** The plan and UI-SPEC say Cancel "stays" on an awaiting DRAFT, but DRAFT only ever offered Submit. Hiding Submit would have left an unpaid DRAFT with no action. D-02 (no sweeper) makes a vendor's manual cancel the only way to clear an abandoned card DRAFT.
- **Fix:** DRAFT now offers Submit and Cancel, the same Cancel entry PENDING and CONFIRMED have. The server allows DRAFT -> CANCELLED (`OrderStateMachineConfig`), and 30-05's `unpaidCardDraftCanStillBeCancelled` proves it through the API.
- **Scope:** This applies to every DRAFT, not only unpaid ones. A vendor DRAFT can now be cancelled from the list too. That is additive and is what the server already permits.
- **Commit:** a593e02b

**2. [Rule 3 - Blocking] `Order` (list) type gained `paymentStatus` / `paymentReference`**
- **Issue:** `frontend/types/api.ts`'s `Order` lacked both fields. The backend `OrderDto` has always serialised them (`OrderMapper` l.80-81), so the Orders list could not pass them to the predicate.
- **Fix:** Two optional, nullable fields. The file is not in the plan's `files_modified`.
- **Commit:** a593e02b

**3. [Rule 3 - Blocking] Stale `frontend/node_modules` restored with `npm --prefix frontend ci`**
- **Issue:** The installed packages did not match the lockfile: tailwindcss 3.4.19 vs 4.3.3, next 16.3.6 vs 16.4.2, lucide-react 1.48.0 vs 1.59.0. `tsc` reported 5 errors in an untouched test, and a build would not have tested what CI builds. This is the open deferred item from 30-01.
- **Fix:** The plan's precondition names `npm ci` as the remedy.
  - Before running it I confirmed no process was using the checkout.
  - `package.json` and `package-lock.json` are unchanged, and no package was added.
  - After the restore, `tsc` exits 0 and the full Jest run is green (it had 15 failures in 3 suites before).
  - The deferred item is marked resolved.
- **Note:** Task 1's RED ran on the stale tree. Jest itself was the same version (30.5.2), and the RED failures are assertion failures in plain DOM and logic tests.

**4. [Placement] The badge sits in the cards' header row, not beside `UnverifiedContactBadge`**
- **Why:** UI-SPEC § S2 puts it "in the card's header row beside the order number, where a cook looks first", and "on grid cards (beside the fulfilment badge)". The plan's action text said "next to UnverifiedContactBadge", which is in the customer row.
- **Choice:** I followed the UI-SPEC, which is also the plan's long-text truth: "badges … sit in the existing flex-wrap header rows". On the Orders list the badge is beside `UnverifiedContactBadge`, as both documents say.

**5. [Spec conflict] An absent status with a reference renders the badge**
- **Conflict:** UI-SPEC says the badge "renders nothing when `paymentStatus` is absent". The plan's must_have, which comes from PR #1018 review finding 8 and is the later decision, says an absent status with a reference is awaiting payment, as in Java.
- **Choice:** I followed the plan. An absent status WITHOUT a reference still renders nothing.

**6. [Approach] RED stubs**
- To get assertion-level RED for modules that did not exist yet, the RED commit included stub exports: `isAwaitingPayment` returning false, a badge returning null, and empty constants. GREEN replaced them.

**Total deviations:** 3 auto-fixed (1 Rule 2, 2 Rule 3), plus 3 documentation-level choices. **Impact:** All of them are within the plan's goal. #1 adds one button on DRAFT rows. Every server rule is unchanged.

## Pitfall 8 survey (recorded, not changed)

I looked for other bare `catch {}` blocks after `apiClient.post` in the dashboard, outside the tests (`evidence/30-06-task2-arms.txt`).

- **Control:** the scan run on the pre-30-06 kitchen page finds the l.623 catch, so it can see the shape.
- **Real run:** 95 files and 27 bare-catch lines. None is within 8 lines of a post. Widening the window to 25 lines finds one: `frontend/app/dashboard/products/import/page.tsx:337` (bulk image scan, "Could not process images").
- **Disposition:** That surface is out of scope. It is recorded in `deferred-items.md` with its route and is not filed, because it does not pass the admission test.

## Threat model

- **T-30-23:** The server stays authoritative. The kitchen keeps its buttons enabled, and the list hides Submit/Confirm for UX only.
- **T-30-24:** The server detail renders as a React text node. Nothing uses `dangerouslySetInnerHTML`.
- **T-30-25:** The 409 arms assert that the detail is shown verbatim, in the toast and in the alert.
- **T-30-SC:** `npm ci` installed the lockfile's own versions. No package was added or bumped.

No new threat surface.

## Known Stubs

None. The RED stubs were replaced at GREEN (a593e02b).

## Next Phase Readiness

Ready for 30-07. Not done here, and not asked for: a browser check of the badge and toast (coverage D6, human judgment). There was no merge, no deploy and no staging change.

## Self-Check: PASSED

- Created files exist:
  - `frontend/lib/awaiting-payment.ts`
  - `frontend/lib/__tests__/awaiting-payment.test.ts`
  - `frontend/components/dashboard/orders/awaiting-payment-badge.tsx`
  - `frontend/components/dashboard/orders/__tests__/awaiting-payment-badge.test.tsx`
  - the four evidence files
- All 7 commits are ancestors of HEAD: 25f6f6d6, a593e02b, eda63be7, 8fde2ffa, 109f3de3, 015d1de5, eaec2371.
- Both RED records reclassify `RED_EVIDENCE_OK`.
