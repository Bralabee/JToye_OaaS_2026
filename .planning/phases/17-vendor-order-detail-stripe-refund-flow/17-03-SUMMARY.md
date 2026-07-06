---
phase: 17-vendor-order-detail-stripe-refund-flow
plan: 03
subsystem: payments

tags: [stripe, refunds, webhook, mvc, problem-detail, mockmvc, testcontainers, idempotency]

requires:
  - phase: 17-01
    provides: "RefundService.createRefund + handleStripeRefundEvent seam, RefundRepository.findByStripeRefundId, RefundDto, RefundStatus, REFUND_REQUESTED state machine"
  - phase: 17-02
    provides: "RefundEventPublisher (publishRefundSucceeded/Failed/Updated) writing to payment_event_outbox with exchange='order.events'"
  - phase: 16.1-pre-prod-hardening
    provides: "processed_stripe_events idempotency log + RlsContractTest schema-walk; signed-event MockedStatic test pattern"
provides:
  - "RefundController exposing POST /orders/{id}/refund + GET /orders/{id}/refunds"
  - "Idempotency-Key header forwarded to RefundService for replay safety"
  - "GlobalExceptionHandler: StripeException -> 502 BAD_GATEWAY ProblemDetail with stripeCode property"
  - "OrderDetailDto extended with paymentStatus + paymentReference + paymentMethod + refunds list"
  - "OrderService.getOrderDetailById populates refunds via RefundService.findByOrderId"
  - "PaymentService.handleWebhookEvent: 3 refund.* cases + 1 charge.refunded no-op (UC-4 LOCKED), all AFTER the Phase 16.1 dedup INSERT (CORRECTION-2 LOCKED)"
  - "RefundService.handleStripeRefundEvent: metadata-driven row lookup with stripe_refund_id fallback, publishes outbox events, does NOT call state machine"
affects: [17-04-frontend-detail-refund]

tech-stack:
  added: ["RefundController @RestController", "Mockito MockedStatic + Webhook-stub testing pattern reused for refund webhooks"]
  patterns:
    - "Single-controller-base mirroring (RefundController @RequestMapping('/orders') matches OrderController so refund endpoints sit under /orders/{id}/...)"
    - "ProblemDetail handler with custom property (stripeCode) for vendor-debuggable Stripe failures"
    - "Webhook handler dispatches to a service method AFTER the dedup INSERT inside the same switch — re-delivery is observably idempotent without a second dedup table"
    - "Webhook handler updates Refund row only; it does NOT re-run the state machine (state machine ran at create time)"

key-files:
  created:
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundController.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java"
    - "core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java"
    - "core-java/src/main/java/uk/jtoye/core/order/OrderService.java"
    - "core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundService.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java"

key-decisions:
  - "RefundController always returns 201 Created on success — even on idempotent replay. Plan suggested 200-on-replay vs 201-on-first; the cleaner contract is 'the resource exists either way; Location header points to the same URI', and the frontend (Plan 17-04) treats 201 as success uniformly."
  - "Used @WebMvcTest + @Import(GlobalExceptionHandler.class) + @MockitoBean for RefundControllerIntegrationTest instead of full @SpringBootTest. Reasons: (a) the test exercises controller routing + GlobalExceptionHandler mapping, both of which @WebMvcTest loads; (b) full @SpringBootTest pulls in DatabaseConfigurationValidator which trips on Testcontainers' superuser 'test' unless @ActiveProfiles('test'); (c) PaymentControllerTest already established the @WebMvcTest pattern in this project. Net: 7 controller tests, ~0.4s wall clock, no DB."
  - "RefundController uses InvalidRequestException(message, param, requestId, code, statusCode, cause) constructor with code='amount_too_large' (the 4th arg) so handleStripeException's setProperty('stripeCode', ex.getCode()) actually fires. The first time around the test passed null for code, which silently skipped the property — now the assertion is meaningful."
  - "Webhook handler does NOT call the state machine. Plan acknowledged this guard explicitly; double-transitioning REFUNDED -> REFUNDED would throw InvalidStateTransitionException. handleStripeRefundEvent only mutates the Refund row's wire status + publishes the outbox event."

patterns-established:
  - "Controller-level Idempotency-Key forwarding: @RequestHeader(value='Idempotency-Key', required=false) String → service. Service owns the dedup decision; controller is dumb. Mirrors the V24 pattern for orders."
  - "Custom ProblemDetail property: handler sets ex.getCode() as a {stripeCode} top-level property on the JSON ProblemDetail body, letting the vendor frontend display 'Error: amount_too_large' without hard-coding the message string."
  - "Refund webhook tests reuse the 16.1 MockedStatic Webhook.constructEvent stub plus a buildRefundEvent helper that mirrors PaymentServiceTest.buildSucceededEvent. Dedup re-delivery is verified by capturing updatedAt + outbox count before/after the second POST."

requirements-completed: [VOPS-02]

duration: 17min
completed: 2026-04-28
---

# Phase 17 Plan 03: Vendor Order Detail + Stripe Refund Flow — Controller + Webhook Surface Summary

**RefundController exposing POST /orders/{id}/refund and GET /orders/{id}/refunds (Idempotency-Key forwarding), GlobalExceptionHandler StripeException → 502 mapping, OrderDetailDto payment + refunds extension, PaymentService.handleWebhookEvent extended with 3 refund.* cases + 1 charge.refunded no-op (all AFTER the Phase 16.1 dedup INSERT), and RefundService.handleStripeRefundEvent dispatching to the outbox without re-running the state machine.**

## Performance

- **Duration:** ~17 min (worktree-resident execution)
- **Started:** 2026-04-28T09:40:32Z
- **Completed:** 2026-04-28T09:57:32Z
- **Tasks:** 2
- **Files created:** 3
- **Files modified:** 8

## Accomplishments

- **RefundController** — `POST /orders/{id}/refund` + `GET /orders/{id}/refunds` mirror `OrderController`'s `@RequestMapping("/orders")` base, so the refund endpoints sit under `/orders/{id}/...`. The optional `Idempotency-Key` header is parsed and forwarded straight to `RefundService.createRefund` — the service owns the dedup decision, the controller is dumb. POST always returns `201 Created` with a `Location: /orders/{id}/refunds/{refundId}` header, even on idempotent replay (clean contract: the resource exists either way).
- **GlobalExceptionHandler** — new `@ExceptionHandler(StripeException.class)` returns `502 Bad Gateway` ProblemDetail with `title="Payment Provider Error"`, `type=https://jtoye.uk/errors/payment-provider`, and a custom `stripeCode` property surfaced from `ex.getCode()`. Body never leaks Stripe's internal stack — full trace logged at WARN, not returned (T-17-14).
- **OrderDetailDto + OrderMapper + OrderService** — `OrderDetailDto` gains `paymentStatus`, `paymentReference`, `paymentMethod`, and `refunds: List<RefundDto>`. The MapStruct `toDetailDto` maps the three payment fields directly; `refunds` is `@Mapping(target = "refunds", ignore = true)` and populated by `OrderService.getOrderDetailById` post-mapping via the new `RefundService` constructor dependency. No circular dependency at startup — `RefundService` depends on `OrderRepository` (not `OrderService`).
- **PaymentService.handleWebhookEvent** — added 3 refund cases (`refund.created`, `refund.updated`, `refund.failed`) dispatching to `refundService.handleStripeRefundEvent`, plus 1 `charge.refunded` no-op with debug log (UC-4 LOCKED — Stripe's 2024-10-28 unified-events changelog deprecated `charge.refunded` in favour of `refund.*`). All four new cases sit AFTER the existing Phase 16.1 `INSERT INTO processed_stripe_events ... ON CONFLICT DO NOTHING` guard inside the same switch — re-delivery short-circuits at the dedup row (CORRECTION-2 LOCKED). PaymentService now constructor-injects `RefundService`.
- **RefundService.handleStripeRefundEvent** — webhook handler that locates the local `Refund` row by `metadata.refund_id` (set by us at create time), falls back to `findByStripeRefundId` for dashboard-issued refunds with no metadata, and no-ops with a WARN log when neither matches. Sets `stripeRefundId` + `status` + `failureReason`, persists, and publishes the appropriate `RefundEventPublisher` method (`publishRefundFailed` for `failed` status or `refund.failed` event type, `publishRefundSucceeded` for `succeeded`, `publishRefundUpdated` for everything else). Does NOT call the state machine — the order-status transition only fires on initial create.
- **Test count delta**: +7 in `RefundControllerIntegrationTest` (`@WebMvcTest`, ~0.4s, no DB), +7 in `RefundWebhookHandlingIntegrationTest` (Testcontainers Postgres, exercises full V36 + Phase 16.1 dedup contract). Default `:core-java:test` suite green at **404 / 0 / 0 / 0** (was 397 in 17-02; +7 from this plan's `RefundControllerIntegrationTest` since the Testcontainers-tagged suite is excluded by default). Testcontainers run (`-PincludeIntegration`): `RefundWebhookHandlingIntegrationTest` 7/0/0/0 + `StripeWebhookIdempotencyIntegrationTest` 3/0/0/0 — Phase 16.1 dedup contract preserved.

## Task Commits

1. **Task 1: RefundController + OrderDetailDto payment block + StripeException 502 mapping** — `4658000` (feat)
2. **Task 2: PaymentService refund.* cases + RefundService.handleStripeRefundEvent + RefundWebhookHandlingIntegrationTest** — `9d311e7` (feat)

## Files Created

- `core-java/src/main/java/uk/jtoye/core/payment/RefundController.java` — POST `/orders/{id}/refund` + GET `/orders/{id}/refunds`, `Idempotency-Key` header forwarding, 201 Created with Location header
- `core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java` — 7 `@WebMvcTest` MockMvc tests (happy path, no-Idempotency-Key, replay, amount-exceeds-total 400, DRAFT-order 400, StripeException 502, GET listing)
- `core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java` — 7 Testcontainers Postgres tests (refund.created, refund.failed, pending→succeeded sequence, dedup re-delivery, charge.refunded no-op, metadata-absent fallback to stripe_refund_id, metadata-absent unknown-id graceful no-op)

## Files Modified

- `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` — `@ExceptionHandler(StripeException.class)` → 502 ProblemDetail with `stripeCode` custom property
- `core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java` — explicit `@Mapping` for paymentStatus + paymentReference + paymentMethod, `@Mapping(target = "refunds", ignore = true)` for service-layer population
- `core-java/src/main/java/uk/jtoye/core/order/OrderService.java` — `RefundService` constructor dependency, `getOrderDetailById` populates `dto.setRefunds(refundService.findByOrderId(orderId))` post-mapping
- `core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java` — 4 new nullable fields (paymentStatus, paymentReference, paymentMethod, refunds) + getters/setters
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java` — `RefundService` constructor dependency; switch gains `refund.created/updated/failed → refundService.handleStripeRefundEvent` + `charge.refunded → log.debug` no-op; all sit AFTER the Phase 16.1 dedup INSERT
- `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java` — `RefundEventPublisher` constructor dependency; new `handleStripeRefundEvent(Event)` + private `applyStripeStatusToRefund(Refund, Refund, String)` helper
- `core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java` — constructor signature updated for the new `RefundService` mock
- `core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java` — constructor signature updated for the new `RefundEventPublisher` mock

## Decisions Made

1. **`@WebMvcTest` instead of `@SpringBootTest` for `RefundControllerIntegrationTest`.** The plan's recommended `@SpringBootTest @AutoConfigureMockMvc` would have pulled in `DatabaseConfigurationValidator`, which fails to find a non-superuser when running with `Testcontainers` Postgres' default `test` user. `@WebMvcTest(RefundController.class) @Import(GlobalExceptionHandler.class) @MockitoBean RefundService` exercises both controller routing AND the `StripeException → 502` ProblemDetail mapping — exactly what the plan asks for — without booting the full context. The test runs in ~0.4s without Docker.
2. **Always return `201 Created` on POST refund**, even on idempotent replay. The plan offered "200 on replay vs 201 on first" — but on the wire there's no observable difference between "fresh" and "replay" from the controller layer (RefundService transparently handles both), and the resource exists either way. A uniform `201 + Location` is the cleaner contract; the frontend (17-04) treats `201` as success.
3. **`InvalidRequestException` constructor argument order in tests.** The Stripe SDK 28.2.0 signature is `(message, param, requestId, code, statusCode, cause)`. The plan's example used `("amount too high", null, null, null, 400, null)` which sets `code=null` — and `handleStripeException` only sets the `stripeCode` property when `ex.getCode() != null`. To make the assertion `jsonPath("$.stripeCode").value("amount_too_large")` meaningful, the test passes `code="amount_too_large"` (4th arg). Without this fix the test would have asserted on a property that the production handler had skipped.
4. **`charge.refunded` no-op uses `log.debug` not `log.info`.** Per UC-4 LOCKED, this event is fully redundant with `refund.*`. A debug log keeps the receipt trail without spamming production INFO logs every time Stripe double-fires the legacy event.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] PaymentServiceTest constructor signature update**
- **Found during:** Task 2 compile step
- **Issue:** Adding `RefundService` to `PaymentService`'s constructor broke `PaymentServiceTest.setUp()` (which constructs `new PaymentService(...)` directly with positional args). Compilation failed.
- **Fix:** Added `@Mock private RefundService refundService;` and passed it as the 7th arg to `new PaymentService(...)`. The test does not exercise refund flows, so the mock just needs to exist — no stubbing required.
- **Files modified:** `core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java`
- **Verification:** Full payment-package suite green (52/0/0/0 default + 10/0/0/0 testcontainers).
- **Committed in:** `9d311e7` (Task 2)

**2. [Rule 3 - Blocking] RefundServiceTest constructor signature update**
- **Found during:** Task 2 compile step
- **Issue:** Adding `RefundEventPublisher` to `RefundService`'s constructor broke `RefundServiceTest.setUp()` for the same reason. Compilation failed.
- **Fix:** Added `@Mock private RefundEventPublisher refundEventPublisher;` and passed it as the 6th arg.
- **Files modified:** `core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java`
- **Verification:** RefundServiceTest 11/0/0/0 — none of the existing 11 tests exercise `handleStripeRefundEvent`, so the mock just needs to exist.
- **Committed in:** `9d311e7` (Task 2)

---

**Total deviations:** 2 auto-fixed (Rule 3 — both blocking compile errors triggered by constructor-signature changes the plan explicitly requested). No scope creep. No architectural changes.

The plan was unusually clean — no Rule 1 bugs, no Rule 2 missing-critical, no Rule 4 escalations. The two Rule 3 fixes are mechanical follow-ons of the plan's own `<action>` steps (the plan tells you to add a constructor parameter; the existing tests must be updated in lock-step).

## Issues Encountered

- **Pre-existing `OrderControllerIntegrationTest` ApplicationContext failure under `-PincludeIntegration`.** This `@SpringBootTest @Testcontainers` test does NOT set `@ActiveProfiles("test")`, so `DatabaseConfigurationValidator` (annotated `@Profile("!test")`) runs and rejects the Testcontainers `test` superuser. **Confirmed pre-existing** by `git log --oneline -5 -- OrderControllerIntegrationTest.java` — last meaningful changes in PR #33 (audit phase 2) and earlier; not caused by Plan 17-03 changes. Out of scope per the executor's `<deviation_rules>` SCOPE BOUNDARY rule. Logged here for the record; remediation is a separate ticket (add `@ActiveProfiles("test")` to that test, or change the Testcontainers user away from the default `test`).
- **JDK 25 default on local toolchain.** Same as 17-01/17-02 — all Gradle invocations prefixed with `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64` per the GSD `<test_environment>` directive.
- **`build/` directory is `build-local/`.** Gradle test reports live at `core-java/build-local/test-results/test/` — repeated noting from prior plans for any future commands targeting Gradle reports.

## User Setup Required

None for this plan. **Webhook subscription configuration is required at deploy time:** the Stripe dashboard must subscribe to `refund.created`, `refund.updated`, and `refund.failed` events (and optionally `charge.refunded`, which we ignore). This is captured in 17-CONTEXT UC-4 LOCKED and will be documented in the project-level USER-SETUP when Phase 17 closes; it is NOT new in 17-03 — the requirement was inherited from UC-4.

## Next Phase Readiness

- **17-04 (frontend)** unblocked end-to-end:
  - **HTTP contract:** `POST /orders/{id}/refund` returns `201 Created` with `Location: /orders/{id}/refunds/{refundId}` and a `RefundDto` body. `Idempotency-Key` header is recommended (frontend should generate `crypto.randomUUID()` per submit).
  - **Error contract:** validation/state-transition errors return `400 ProblemDetail`; Stripe errors return `502 ProblemDetail` with a `stripeCode` property the frontend can surface.
  - **Detail contract:** `GET /orders/{id}/detail` now returns `paymentStatus`, `paymentReference`, `paymentMethod`, and `refunds[]` — the frontend (17-04) reads these directly to render the Payment block + Refund history. The TypeScript `OrderDetail` interface needs the matching fields.
  - **SSE flow:** Refund-induced order transitions (`REFUND_REQUESTED → REFUNDED`) still fire `OrderEventPublisher.publishStateChange`, so the existing `OrderSseService` broadcast still drives the live update. The frontend re-fetches the detail when SSE notifies a state change — no separate refund SSE channel required.

## Threat Flags

None new in this plan. All surface introduced (`POST /orders/{id}/refund`, the `refund.*` webhook switch cases, the `OrderDetailDto` payment+refunds extension) is fully covered by the plan's `<threat_model>` register (T-17-11 through T-17-16). Specifically:

- **T-17-12 (Tampering — body-modified refund POST):** `@Valid` on `CreateRefundRequest` (positivity, reason enum) + server-side `amountPennies > 0 AND <= remaining` in RefundService (already in 17-01) + Stripe-Idempotency-Key replay returns existing row, can't be used to bypass amount check — verified by `RefundControllerIntegrationTest.postRefund_amountExceedsTotal_returns400ProblemDetail`.
- **T-17-14 (Information Disclosure — StripeException leaks Stripe internals):** the new `handleStripeException` ProblemDetail surfaces only `ex.getMessage()` and `stripeCode` — not the stack trace. `log.warn` keeps the trace server-side — verified by `RefundControllerIntegrationTest.postRefund_stripeThrows_returns502ProblemDetail`.
- **T-17-16 (Business logic — webhook delivery without metadata):** `handleStripeRefundEvent` falls back to `findByStripeRefundId`; logs WARN and returns gracefully if neither metadata nor stripe_refund_id matches a local row — verified by `RefundWebhookHandlingIntegrationTest.webhookRefundWithoutMetadataAndUnknownStripeId_logsWarningWithoutCrashing` and `webhookRefundWithoutMetadata_findsByStripeRefundIdIfPresent`.

## TDD Gate Compliance

This plan's tasks were marked `tdd="true"` but the plan is `type="execute"` (not a `type: tdd` plan), so strict RED-then-GREEN commit separation was not required. Both task commits include tests alongside production code, and all behaviors specified under the plan's `<behavior>` blocks are covered by green tests. The full default `:core-java:test` suite passes 404/0/0/0 with no skipped tests. The Testcontainers integration suite (`-PincludeIntegration`) for the payment package is 10/0/0/0 (`RefundWebhookHandlingIntegrationTest` 7 + `StripeWebhookIdempotencyIntegrationTest` 3).

## Self-Check: PASSED

**Files (created):**
- `core-java/src/main/java/uk/jtoye/core/payment/RefundController.java` — FOUND
- `core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java` — FOUND
- `core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java` — FOUND

**Files (modified):**
- `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` — modified (StripeException handler, +18 lines)
- `core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java` — modified (4 new @Mapping annotations on toDetailDto)
- `core-java/src/main/java/uk/jtoye/core/order/OrderService.java` — modified (RefundService dep + getOrderDetailById refunds population)
- `core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java` — modified (4 new fields + accessors)
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java` — modified (RefundService dep + 4 new switch cases)
- `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java` — modified (RefundEventPublisher dep + handleStripeRefundEvent + applyStripeStatusToRefund)
- `core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java` — modified (constructor signature)
- `core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java` — modified (constructor signature)

**Commits:**
- `4658000` (Task 1) — FOUND in `git log --oneline`
- `9d311e7` (Task 2) — FOUND in `git log --oneline`

**Structural verify gates:**
- `grep -v comments core-java/src/main/java/uk/jtoye/core/payment/RefundController.java | grep -c "Idempotency-Key"` = 2 (≥1) — PASS
- `grep -v comments core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java | grep -c "StripeException"` = 3 (≥1) — PASS
- `grep -v comments core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java | grep -c "BAD_GATEWAY"` = 1 (≥1) — PASS
- `grep -v comments core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java | grep -c "refund\.created"` = 1 (≥1) — PASS
- `grep -v comments core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java | grep -c "charge\.refunded"` = 2 (≥1) — PASS
- `awk 'INSERT INTO processed_stripe_events comes BEFORE switch(event.getType())'` — OK_DEDUP_BEFORE_SWITCH — PASS

**Test verification:**
- `:core-java:test --tests "*RefundControllerIntegrationTest"` — 7/0/0/0
- `:core-java:test --tests "*RefundWebhookHandlingIntegrationTest" -PincludeIntegration` — 7/0/0/0
- `:core-java:test --tests "*StripeWebhookIdempotencyIntegrationTest" -PincludeIntegration` — 3/0/0/0 (Phase 16.1 dedup contract preserved)
- Full `:core-java:test` (default tag-exclude) — 404/0/0/0

---
*Phase: 17-vendor-order-detail-stripe-refund-flow*
*Plan: 03*
*Completed: 2026-04-28*
