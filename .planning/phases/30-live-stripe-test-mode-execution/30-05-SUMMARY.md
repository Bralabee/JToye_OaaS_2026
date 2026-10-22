---
phase: 30-live-stripe-test-mode-execution
plan: 05
subsystem: payments
tags: [orders, state-machine, stripe, webhook, rfc7807, openapi, issue-958, tdd]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-04: branch fix/958-awaiting-payment-gate cut from main 43d047fb"
provides:
  - "uk.jtoye.core.exception.AwaitingPaymentException(String orderNumber): the 30-UI-SPEC staff sentence, no issue number"
  - "Problem type https://jtoye.uk/errors/awaiting-payment: HTTP 409, title 'Awaiting payment' (GlobalExceptionHandler)"
  - "OrderService.transitionOrder refuses SUBMIT, CONFIRM and -> PREPARING when isAwaitingUpfrontOrDepositPayment (unchanged predicate), before any mutation"
  - "PaymentService.handlePaymentIntentSucceeded moves only a DRAFT to PENDING (and publishes only then); WARN event=payment_on_closed_order for CANCELLED, REFUNDED or voided"
  - "OpenAPI: submit/confirm/start-preparation 409 descriptions name awaiting-payment; snapshot regenerated"
  - "OrderAwaitingPaymentGateIntegrationTest (8 arms through the real API)"
affects: [30-06, 30-07, 30-08, 30-09, 30-32]

actuals:
  tokens: 132182    # chars/4 over the realized diff; ~114000 of it is the five RED evidence records, which embed the raw JUnit XML. Code, tests and prose alone: ~18200
  tasks: 3
  commits: 10       # git rev-list --count f6108ccc..6051907a, measured before the SUMMARY commit

plan_head_before: f6108ccc778baf2ed93073d331fa2aa186fdf0ee
plan_head_after: 6051907aa70f0dd5b0b4815a4a9043139b7c4992

tech-stack:
  added: []
  patterns:
    - "Payment gate at the single transition chokepoint, evaluated after the state machine judges legality and before any write (status, stock, outbox)"
    - "Status-preserving payment success: the webhook records money at any status and changes status only from DRAFT"
    - "A refusal type that is its own exception class, not a subclass of the generic invalid-transition one, so no existing catch swallows it"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/exception/AwaitingPaymentException.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderAwaitingPaymentGateIntegrationTest.java
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-red-task1.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-red-task2-unit.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-red-task2-it.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-red-task3-it.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-red-task3-unit.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-task2-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-05-task3-arms.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderController.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeWebhookIdempotencyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java
    - docs/api/openapi-snapshot.json
    - docs/architecture/API_REFERENCE.md
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "409 Conflict, not invalid-state-transition's 400: the request is well-formed and the transition legal; it conflicts with the order's payment state. Clients key on the type"
  - "The published 409 on submit/confirm/start-preparation now names the awaiting-payment cause (agent-readiness: the OpenAPI contract matches live responses); snapshot regenerated, 3 description lines"
  - "The CANCELLED/REFUNDED/voided WARN covers exactly the plan's set; COMPLETED and the open statuses record the payment silently"
  - "distinctEventIdsBothProcess (an existing test) asserted the defect: two payments on one order announcing DRAFT -> PENDING twice. It now asserts both payments are processed (publishSucceeded twice) and one state change"

patterns-established:
  - "A log assertion in a test profile that logs uk.jtoye at DEBUG must match the level, not only the message: a contains() on the text passed a debug downgrade"

requirements-completed: [PAY-04]

duration: 62min
completed: 2026-10-22

coverage:
  - id: D1
    description: "SUBMIT of an unpaid card DRAFT is refused 409 awaiting-payment with the UI-SPEC detail and no '#'; the order stays DRAFT and no outbox row is written"
    requirement: PAY-04
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/order/OrderAwaitingPaymentGateIntegrationTest.java#unpaidCardDraftSubmitIsRefused (RED 200 -> GREEN 409; break arm narrowed to PREPARING reds it)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java#testSubmitOrder_RefusesUnpaidCardDraft"
        status: pass
    human_judgment: false
  - id: D2
    description: "CONFIRM and START_PREP of an order awaiting payment are refused the same way; a refused confirm leaves stock exactly 5"
    requirement: PAY-04
    verification:
      - kind: integration
        ref: "OrderAwaitingPaymentGateIntegrationTest#unpaidPendingConfirmIsRefusedAndStockIsUntouched, #failedPaymentStartPrepIsRefused (RED 200 / 400 -> GREEN 409; capturedPendingConfirmMoves is the stock arm's positive control, 5 -> 3)"
        status: pass
      - kind: unit
        ref: "OrderServiceTest#testConfirmOrder_RefusesFailedLinkPayment, #testConfirmOrder_RefusesReferenceWithoutCapture, #testStartPreparation_BlocksUnpaidPaymentLinkOrder"
        status: pass
    human_judgment: false
  - id: D3
    description: "D-01: a vendor/REST/MCP order (NONE, no reference) submits, confirms and starts preparation exactly as before; CAPTURED, AUTHORIZED and a captured split deposit move; CANCEL of an unpaid card DRAFT stays allowed"
    requirement: PAY-04
    verification:
      - kind: integration
        ref: "OrderAwaitingPaymentGateIntegrationTest#vendorDraftSubmitStillMoves, #vendorPendingConfirmStillMoves, #vendorConfirmedStartPrepStillMoves, #capturedPendingConfirmMoves, #unpaidCardDraftCanStillBeCancelled (green on BOTH the pre-plan and the GREEN tree); OrderCreateSubmitIntegrationTest 5/5"
        status: pass
      - kind: unit
        ref: "OrderServiceTest#testVendorOrderIsNotPaymentGated, #testCapturedOrderMovesNormally, #testAuthorizedOrderConfirms, #testCapturedDepositWithBalanceDueStartsPreparation, #testCancelUnpaidCardDraftIsAllowed"
        status: pass
    human_judgment: false
  - id: D4
    description: "payment_intent.succeeded moves only a DRAFT (one DRAFT -> PENDING publish); on CONFIRMED or CANCELLED it records CAPTURED and the intent id and leaves status and outbox alone; CANCELLED logs WARN payment_on_closed_order"
    verification:
      - kind: integration
        ref: "StripeWebhookIdempotencyIntegrationTest#paymentOnConfirmedOrderKeepsItsStatus, #paymentOnCancelledOrderKeepsItsStatus, #paymentOnDraftOrderMovesItToPending, #distinctEventIdsBothProcess (RED: stored status PENDING -> GREEN; WARN arm reds on debug downgrade and on removal)"
        status: pass
      - kind: unit
        ref: "PaymentServiceTest#handlePaymentSucceeded_onConfirmedOrder_keepsStatus, #handlePaymentSucceeded_onCancelledOrder_keepsStatus"
        status: pass
    human_judgment: false
  - id: D5
    description: "The published API contract and the counts match the tree"
    verification:
      - kind: integration
        ref: "OpenApiSnapshotTest 1/1 (reds with the pre-plan snapshot)"
        status: pass
      - kind: other
        ref: "scripts/docs-freshness.sh && scripts/check-doc-metrics.sh rc 0 (rc 1 before --write and before the prose edit)"
        status: pass
    human_judgment: false
---

# Phase 30 Plan 05: Unpaid card and link orders cannot be submitted, accepted or prepared Summary

**The server now refuses SUBMIT, CONFIRM and START_PREP of an order still awaiting its card or link payment, with a typed 409 `awaiting-payment` problem and no write of any kind. The payment webhook moves only a DRAFT, so a late link payment no longer regresses an accepted order or re-sends "Received". This is issue #958's server half.**

## Performance

- **Duration:** 62 min (plus a 35 min full-suite run in the background)
- **Started:** 2026-10-22T18:40:16Z
- **Completed:** 2026-10-22T19:43Z
- **Tasks:** 3 of 3
- **Files modified:** 22 (13 code, test or doc files and 9 evidence files)

## Accomplishments

- **One gate at the one chokepoint.** `OrderService.transitionOrder` now refuses `SUBMIT`, `CONFIRM` and `-> PREPARING` when `isAwaitingUpfrontOrDepositPayment` is true. The predicate itself is unchanged. The check sits after `sendEvent` and the shop gate, and before `setStatus`, the stock decrement and the outbox write. A refusal therefore sends no "Received" or "Confirmed" email. The dashboard, the kitchen, REST and MCP all reach this code through the same method.
- **A typed refusal.** The 409 body is `application/problem+json` with type `https://jtoye.uk/errors/awaiting-payment` and title "Awaiting payment". The detail is word for word the UI-SPEC sentence: "Order {n} is awaiting payment. It can't be submitted, accepted or prepared until the customer's payment completes." The old "(#461)" text is gone.
- **D-01 holds.** A vendor, REST or MCP order (paymentStatus NONE, no reference) moves exactly as before. These arms go through the real API and were green on both the pre-plan tree and the GREEN tree. `CANCEL` is never gated (D-02).
- **The webhook no longer moves orders backwards.** `handlePaymentIntentSucceeded` records CAPTURED, the intent id, the payment-succeeded event and the ledger row at any status. It changes status, and publishes DRAFT -> PENDING, only from DRAFT. CANCELLED, REFUNDED and voided orders also log `WARN event=payment_on_closed_order order=… status=… intent=…`, with no PII, so the vendor can decide on a refund.
- **The contract says so.** The 409 response on submit, confirm and start-preparation now names the new cause, and the OpenAPI snapshot was regenerated (3 description lines). `API_REFERENCE.md` documents the problem.
- **MCP parity, no change needed.** `mcp-server/src/errors.ts` `toToolError` forwards any problem+json as `core {status} {title}: {detail}`, so an agent receives the typed detail verbatim. MCP `create_order` always sends `submit: true` on a NONE order (D-01), and there is no MCP confirm or start-prep tool.

## Task Commits

1. **Task 1 (tracer): submit refusal**
   - RED `4bd017f0` (test + `evidence/30-05-red-task1.json`)
   - GREEN `30634b95` (exception, handler, widened guard, OpenAPI, API_REFERENCE)
   - Tracer gate: the verify was re-run on the committed tree, rc 0, gate IT 3/3 and submit IT 5/5. Expansion followed.
2. **Task 2: confirm and start-prep, plus the paid, vendor and cancel arms**
   - Tests `3662acf0`
   - RED, break arm and GREEN evidence `48577794`
   - No production change was needed, as the plan predicted.
3. **Task 3: status-preserving webhook, and counts**
   - RED tests `d1283f06`, RED evidence `7e01d29f`
   - GREEN `791b7a16`
   - Counts `eed7ee02`
   - WARN assertion strengthened `39b3d63d`
   - Evidence `6051907a`

## TDD Gate Compliance

| Task | RED commit | RED verdict (`gsd_run check tdd-red-evidence`) | GREEN |
|------|-----------|-----------------------------------------------|-------|
| 1 | 4bd017f0 | RED_EVIDENCE_OK / target_test_failed: card arm l.150 expected 409, was 200. Setup passed; the vendor and cancel arms were green | 30634b95 |
| 2 | 3662acf0 | RED_EVIDENCE_OK for both records: unit 4/50 red (the old exception type, or nothing thrown); IT 3/8 red (200, 200, 400) | 30634b95 (already in place) |
| 3 | d1283f06 | RED_EVIDENCE_OK for both records: the stored row reads PENDING for CONFIRMED and CANCELLED; distinct events published twice | 791b7a16 |

Semantic assessment: every target ran and failed on its planned behavioural assertion. Each run's setup assertions passed before that point, and no run had a compile, fixture or zero-test fault. Task 2's RED could not be taken on the committed tree, because Task 1's GREEN already contained the whole gate. It was taken instead by temporarily restoring the pre-plan guard verbatim, marked `RED-ARM-30-05`. The file was then restored by cp and checked by sha256 against HEAD's blob.

## Falsification (both directions, real output in the evidence files)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| Gate arms (submit, confirm, start-prep) | pre-plan guard: 200, 200, 400; condition narrowed to PREPARING: the submit and confirm arms red (unit 3, IT 2), start-prep green | 8/8, 50/50 |
| Stock arm (stays 5) | positive control `capturedPendingConfirmMoves` reads 5 -> 3 on both trees, so the read can see a change | 5 before, 5 after |
| Webhook status arms | plan-base PaymentService: stored status PENDING | CONFIRMED / CANCELLED preserved |
| WARN `payment_on_closed_order` | **first break arm did NOT fail**: a downgrade to `log.debug` stayed green because the test profile logs `uk.jtoye` at DEBUG. The assertion was strengthened to match the level. After that, both the debug downgrade and `if (false)` red the CANCELLED arm | 6/6 |
| OpenApiSnapshotTest | pre-plan snapshot: FAILED "no longer matches the reviewed snapshot" | 1/1 |
| Counts | docs-freshness rc 1 (6541 vs 6562); check-doc-metrics rc 1 (10 FAIL lines) | both rc 0, 37/37 |
| Regression sweep | n/a (a suite-wide sweep has no single break) | full core-java suite at 791b7a16: unit 1947 tests in 221 classes, integration 1369 tests in 226 classes, 0 failures, 0 errors, 1 skipped each, rc 0 |

Every break was restored by cp from a scratch copy. Each restore was verified by sha256 against the HEAD blob, a marker count of 0 and `git diff --quiet`, and a clean run followed.

## Files Created/Modified

- `exception/AwaitingPaymentException.java`: the typed refusal (new).
- `common/GlobalExceptionHandler.java`: 409 handler.
- `order/OrderService.java`: the widened guard.
- `order/OrderController.java`: the 409 descriptions and the #448 comment.
- `payment/PaymentService.java`: DRAFT-only status change, plus the closed-order WARN.
- `OrderAwaitingPaymentGateIntegrationTest.java`: new, 8 arms.
- `OrderServiceTest.java`: +8 arms. The start-prep refusal now checks the exact detail and the absence of '#'.
- `StripeWebhookIdempotencyIntegrationTest.java`: +3 arms, and `distinctEventIdsBothProcess` corrected.
- `PaymentServiceTest.java`: +2 arms.
- `docs/api/openapi-snapshot.json` and `docs/architecture/API_REFERENCE.md`: the contract.
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`: counts 6541 -> 6562.

## Decisions Made

See `key-decisions` in the frontmatter. In short:
- 409 with a stable type.
- The published 409 names the new cause.
- The WARN covers exactly CANCELLED, REFUNDED and voided.
- An existing test that encoded the double-publish defect was corrected rather than kept.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The existing start-prep unit test was updated in Task 1's GREEN commit, not in Task 2's**
- **Found during:** Task 1 GREEN
- **Issue:** The new exception type makes `testStartPreparation_BlocksUnpaidPaymentLinkOrder` (`assertThrows(InvalidStateTransitionException)`) fail. Leaving it for Task 2 would have committed a red tree.
- **Fix:** 30634b95 changes it to expect `AwaitingPaymentException`. Task 2 (3662acf0) adds the exact-detail and no-'#' assertions and the new arms.
- **Verification:** OrderServiceTest 42/42 at 30634b95 and 50/50 at 3662acf0. Task 2's RED was taken by temporarily restoring the pre-plan guard (above).

**2. [Rule 2 - Missing critical functionality] The published OpenAPI 409 descriptions and API_REFERENCE name awaiting-payment**
- **Found during:** Task 1 GREEN
- **Issue:** The new 409 would otherwise be absent from the spec agents read (agent-readiness contract). The plan anticipated a snapshot move only in Task 3's verify.
- **Fix:** `@ApiResponse(409)` on submit, confirm and start-preparation; `./gradlew :core-java:updateOpenApiSnapshot` (3 lines); an API_REFERENCE section.
- **Files modified:** OrderController.java, openapi-snapshot.json, API_REFERENCE.md
- **Commit:** 30634b95

**3. [Rule 1 - Bug] An existing test asserted the double-publish defect**
- **Found during:** Task 3 RED
- **Issue:** `distinctEventIdsBothProcess` expected two DRAFT -> PENDING publishes for two payments on one order. The second publish is exactly the re-sent "Received" this plan removes.
- **Fix:** It now asserts `publishSucceeded` twice (both processed) and `publishStateChange` once.
- **Commit:** d1283f06

**4. [Rule 1 - Bug, in a test] The WARN assertion could not fail under a level downgrade**
- **Found during:** the Task 3 break arm
- **Issue:** `contains(message)` passed with `log.debug`, because the test profile enables DEBUG for `uk.jtoye`.
- **Fix:** `containsPattern("WARN\\s+\\S*PaymentService - event=…")`.
- **Verification:** reds on the downgrade and on removal; green restored.
- **Commit:** 39b3d63d

**5. [Rule 1 - Test quality] Lenient `save` stubs on the three new SUBMIT/CONFIRM unit refusals**
- **Found during:** Task 2 RED
- **Issue:** The first RED run failed on a downstream NullPointerException from the unstubbed save, not on "nothing was thrown".
- **Fix:** Lenient stubs, amended into 3662acf0 before the recorded RED, so a missing refusal reads as such.

**Also added beyond the plan's arms:**
- An explicit DRAFT webhook arm.
- D-01 confirm and start-prep IT arms.
- A NONE-plus-reference unit arm.
- A full core-java suite run, because a widened guard can break existing tests (memory: scope-gate regression).

**Total deviations:** 5 auto-fixed (2 Rule 1 test bugs, 1 Rule 1 defect-encoding test, 1 Rule 2 contract, 1 Rule 3 blocking).
**Impact:** None on scope; each makes a claim provable or keeps a commit green.

## Issues Encountered

- `block-base-python` refuses the base interpreter. The RED-record builder ran under `conda activate jtoye-ops`.
- The JUnit adapter of `tdd-red-evidence` names a testcase by its `@DisplayName`, so `Class#method` targets returned `no_target_test_failure`. Targets now use the display name, with the class passed separately.

## Known Stubs

None.

## Threat Flags

None. There is no new endpoint, auth path or schema change. The refusal sits after `shopAccessService.require(STAFF)` (T-30-21), and the WARN line carries no PII. The CANCELLED arm asserts that the customer email is absent from the output.

## Flagged assumptions (carried from the plan, for the owner or verifier)

- A payment on an order the vendor already cancelled leaves it CANCELLED, with `paymentStatus` CAPTURED and the WARN line. No automatic refund is made.
- A FAILED payment keeps the order awaiting payment, and the gate keeps refusing it.
- A captured split DEPOSIT releases the gate (`testCapturedDepositWithBalanceDueStartsPreparation`). Making an unpaid balance block production would be a new rule.
- D-01 gap: vendor NONE orders are not payment-gated in Phase 30. This is recorded for plan 30-32.

## Next Phase Readiness

Ready for 30-06, which continues PR 2 on `fix/958-awaiting-payment-gate`. Nothing is pushed, merged or deployed, and staging is untouched.

## Self-Check: PASSED

- FOUND: AwaitingPaymentException.java, OrderAwaitingPaymentGateIntegrationTest.java and all 7 evidence files (checked with `[ -f ]` before the write)
- FOUND (ancestors of HEAD): 4bd017f0, 30634b95, 3662acf0, 48577794, d1283f06, 7e01d29f, 791b7a16, eed7ee02, 39b3d63d, 6051907a
- Plan verification re-run at 39b3d63d: rc 0 (50 + 26 unit, 8 + 5 + 6 + 1 integration); counts rc 0
