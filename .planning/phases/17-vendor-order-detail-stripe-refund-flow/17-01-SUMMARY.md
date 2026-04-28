---
phase: 17-vendor-order-detail-stripe-refund-flow
plan: 01
subsystem: payments

tags: [stripe, refunds, flyway, jpa, hibernate-envers, spring-statemachine, idempotency, mapstruct, postgres-rls]

requires:
  - phase: 16.1-pre-prod-hardening
    provides: "canonical app.current_tenant_id GUC + processed_stripe_events idempotency log + RlsContractTest schema-walk"
  - phase: 11-checkout-payments
    provides: "PaymentService Stripe SDK init + payment_event_outbox + Order.paymentReference field"
provides:
  - "refunds + refunds_aud tables with ENABLE+FORCE RLS via canonical GUC"
  - "Refund JPA entity with stored-first idempotency contract (status=CREATING pre-Stripe)"
  - "RefundReason / RefundStatus enums (lowercase Stripe-wire format) + DTOs + MapStruct mapper"
  - "RefundService.createRefund implementing UC-1 LOCKED stored-first 8-step flow"
  - "StripeRefundClient wrapper seam (testable replacement for static Refund.create)"
  - "OrderEvent.REFUND_REQUESTED + OrderStatus.REFUNDED with 4 state-machine transitions"
  - "orders_status_check rewritten in V36 to include REFUNDED (V6 landmine fix)"
  - "payment_event_outbox.exchange column for per-row routing (consumed in 17-02)"
affects: [17-02-outbox-routing, 17-03-controller-webhook, 17-04-frontend-detail-refund]

tech-stack:
  added: ["StripeRefundClient seam", "REQUIRES_NEW transactional failure-persistence pattern"]
  patterns: ["stored-first idempotency (insert row pre-Stripe, reuse key on retry)", "lowercase enum names matching Stripe wire format", "service-level already-X short-circuit instead of state-machine self-loop"]

key-files:
  created:
    - "core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql"
    - "core-java/src/main/java/uk/jtoye/core/payment/Refund.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundReason.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundStatus.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundRepository.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundMapper.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundService.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/StripeRefundClient.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/dto/CreateRefundRequest.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/dto/RefundDto.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/RefundRepositoryTest.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/order/OrderStatus.java"
    - "core-java/src/main/java/uk/jtoye/core/order/OrderEvent.java"
    - "core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java"
    - "core-java/src/test/java/uk/jtoye/core/order/OrderStateMachineServiceTest.java"

key-decisions:
  - "Removed COMPLETED from .end() states — Spring Statemachine refuses transitions out of end states, blocking the COMPLETED → REFUNDED transition required by VOPS-03 (Rule 1 fix discovered during test)"
  - "Added @UniqueConstraint on the Refund entity's (tenant_id, idempotency_key) so the H2 test profile exercises the same dedup contract as the V36 SQL — Hibernate's create-drop generates schema solely from JPA, not Flyway SQL"
  - "Introduced StripeRefundClient as a thin component wrapping the static Refund.create call so unit tests mock without Mockito-inline; future enhancements (circuit breaker, retry, metrics) wrap here without touching RefundService"

patterns-established:
  - "Stored-first idempotency: persist Refund(status=CREATING) BEFORE the Stripe call, send the row's idempotency_key as Stripe's Idempotency-Key header. On retry the same row is reused and Stripe's 24h dedup window returns the original response. Two DB writes per refund (CREATING insert + status update post-Stripe) but eliminates double-refund risk."
  - "REQUIRES_NEW failure persistence: when a Stripe call fails inside an outer @Transactional method, mark the row as 'failed' via a brand-new transaction so the failure is durable for reconciliation even if the outer tx rolls back."
  - "Service-level already-X short-circuit: idempotency on already-REFUNDED orders lives in RefundService (returns existing Refund without calling Stripe or the state machine), NOT as a state-machine self-loop. The state machine remains fail-loud on illegal transitions."

requirements-completed: [VOPS-02, VOPS-03]

duration: 9min
completed: 2026-04-28
---

# Phase 17 Plan 01: Vendor Order Detail + Stripe Refund Flow — Backend Persistence Summary

**Stored-first idempotent refund persistence stack: V36 Flyway migration (refunds + refunds_aud + orders_status_check rewrite + payment_event_outbox.exchange), Refund JPA entity + DTOs + MapStruct mapper, RefundService implementing UC-1 LOCKED 8-step flow with REQUIRES_NEW failure handling, plus REFUND_REQUESTED state-machine extension with four transitions to REFUNDED.**

## Performance

- **Duration:** ~9 min (worktree-resident execution)
- **Started:** 2026-04-28T09:15:00Z
- **Completed:** 2026-04-28T09:24:00Z
- **Tasks:** 2
- **Files created:** 12
- **Files modified:** 4
- **Lines changed:** +1,546 / -5

## Accomplishments

- **V36 migration** (`V36__refunds_and_outbox_exchange.sql`) bundles four atomic concerns: refunds + refunds_aud tables with ENABLE+FORCE RLS via canonical `app.current_tenant_id` GUC, the orders_status_check rewrite (V6 landmine — previously rejected REFUNDED at the DB layer), and the `payment_event_outbox.exchange` column with default `'payment.events'` for the per-row routing 17-02 will consume.
- **Refund entity stack** (entity + repo + reason/status enums + DTOs + mapper) with stored-first persistence shape: `idempotency_key VARCHAR(64) NOT NULL`, unique on `(tenant_id, idempotency_key)`, `@Audited` Envers tracking, `@Version` optimistic-lock so concurrent webhook updates from Stripe (`refund.created` then `refund.updated`) don't last-writer-wins.
- **RefundService** with the 8-step flow LOCKED in 17-CONTEXT.md: X-Idempotency-Key replay (no Stripe call), already-REFUNDED short-circuit (no Stripe call, no state machine), refundable-status guard, server-side amount validation, CREATING insert BEFORE Stripe, Stripe call with stored key, post-Stripe row update, state-machine transition + order save. Failures during the Stripe call route through `markRefundFailed` in a `REQUIRES_NEW` tx so the failed status is durable for reconciliation.
- **StripeRefundClient** thin component wrapping `Refund.create(...)` so unit tests mock without bytecode rewriting. RefundServiceTest's 11 unit tests cover happy path (snapshot at saveAndFlush proves CREATING ordering), X-Idempotency-Key replay, already-REFUNDED short-circuit, data-drift IllegalState, DRAFT/no-payment-ref rejection, amount overflow, null-amount-equals-full-remaining, StripeException-marks-failed, and missing TenantContext.
- **State-machine extension**: `OrderEvent.REFUND_REQUESTED`, `OrderStatus.REFUNDED`, four `.withExternal()` transitions (`CONFIRMED|PREPARING|READY|COMPLETED → REFUNDED`), `.end(REFUNDED)`. Idempotent already-REFUNDED handling lives in RefundService, not the state machine.
- **Test count delta**: +6 in OrderStateMachineServiceTest, +6 in RefundRepositoryTest, +11 in RefundServiceTest. Full :core-java:test suite green at 390 tests, 0 failures.

## Task Commits

1. **Task 1: V36 migration + Refund persistence stack + state-machine extension** — `36d3239` (feat)
2. **Task 2: RefundService with stored-first idempotency + Stripe seam** — `962a692` (feat)

## Files Created

- `core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql` — refunds + refunds_aud + orders CHECK rewrite + outbox exchange column
- `core-java/src/main/java/uk/jtoye/core/payment/Refund.java` — JPA entity with `@UniqueConstraint(tenant_id, idempotency_key)` + `@Audited` + `@Version`
- `core-java/src/main/java/uk/jtoye/core/payment/RefundReason.java` — enum (DUPLICATE / FRAUDULENT / REQUESTED_BY_CUSTOMER) with `toStripeReason` helper
- `core-java/src/main/java/uk/jtoye/core/payment/RefundStatus.java` — enum with lowercase Stripe-wire names + `CREATING` sentinel
- `core-java/src/main/java/uk/jtoye/core/payment/RefundRepository.java` — JpaRepository with idempotency-key lookup, order-history ordering, Stripe-id inverse lookup, and `sumLiveAmountByOrderId`
- `core-java/src/main/java/uk/jtoye/core/payment/RefundMapper.java` — MapStruct entity → DTO mapping
- `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java` — 8-step flow + `markRefundFailed` REQUIRES_NEW + read-only `findByOrderId`
- `core-java/src/main/java/uk/jtoye/core/payment/StripeRefundClient.java` — thin seam over static `Refund.create(...)` for testability
- `core-java/src/main/java/uk/jtoye/core/payment/dto/CreateRefundRequest.java` — Java record with Jakarta validation
- `core-java/src/main/java/uk/jtoye/core/payment/dto/RefundDto.java` — read-only DTO record
- `core-java/src/test/java/uk/jtoye/core/payment/RefundRepositoryTest.java` — 6 `@DataJpaTest` methods (H2 / Postgres mode)
- `core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java` — 11 Mockito unit tests covering the 8-step flow

## Files Modified

- `core-java/src/main/java/uk/jtoye/core/order/OrderStatus.java` — added `REFUNDED`
- `core-java/src/main/java/uk/jtoye/core/order/OrderEvent.java` — added `REFUND_REQUESTED`
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java` — added 4 transitions, `.end(REFUNDED)`, removed `.end(COMPLETED)` (see Deviations)
- `core-java/src/test/java/uk/jtoye/core/order/OrderStateMachineServiceTest.java` — 6 new `@Test` methods covering REFUND_REQUESTED happy paths and rejections

## Decisions Made

1. **Removed `COMPLETED` from `.end()` states.** Spring Statemachine treats `.end()` states as terminal and rejects every event sent to a machine started in that state, regardless of `.withExternal()` definitions. Without this fix, `COMPLETED → REFUNDED` (one of the four required transitions) failed with `InvalidStateTransitionException`. CANCELLED and REFUNDED remain terminal end states. Documented inline in `OrderStateMachineConfig.configure(StateMachineStateConfigurer)`.
2. **`@UniqueConstraint` on the Refund entity table.** The V36 SQL declares `CONSTRAINT refunds_idem_unique UNIQUE (tenant_id, idempotency_key)` but `application-test.yml` runs Flyway-disabled with Hibernate `ddl-auto: create-drop` — so the test schema is generated from JPA only. Adding the entity-level annotation makes the unique-constraint test pass against H2 AND keeps the Flyway-managed Postgres schema authoritative in production.
3. **`StripeRefundClient` seam component.** The Stripe SDK's `Refund.create(...)` is static; mocking statics requires Mockito-inline (heavy toolchain dependency). A 4-line `@Component` wrapper costs nothing and provides a hook point for future cross-cutting (circuit breaker, retry, metrics).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Removed `.end(OrderStatus.COMPLETED)` from state-machine config**
- **Found during:** Task 1 (state-machine test for `COMPLETED → REFUNDED`)
- **Issue:** With `COMPLETED` declared as `.end()`, Spring Statemachine reset to that state and refused all subsequent events. The `refundRequestedFromCompleted_transitionsToRefunded` test failed with `InvalidStateTransitionException`. Plan 17-CONTEXT explicitly required `COMPLETED → REFUNDED` as one of the four valid transitions (research §7.3 noted "Adding REFUNDED as an .end() state… means COMPLETED→REFUNDED requires a non-end→end transition which Spring StateMachine supports" — but this is only true if the SOURCE is not also `.end()`).
- **Fix:** Removed `.end(OrderStatus.COMPLETED)`. Documented the change inline so future work understands COMPLETED is a non-terminal "fulfilled" status now (refunds may still be issued post-completion). The existing test `testInvalidTransitions` asserts `COMPLETED + SUBMIT throws` — still passes because no transition rule maps COMPLETED + SUBMIT, regardless of end-state status.
- **Files modified:** `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java:35`
- **Verification:** Full `:core-java:test` suite green (390 tests, 0 failures). All 4 REFUND_REQUESTED happy paths + the 2 rejection paths pass.
- **Committed in:** `36d3239` (Task 1)

**2. [Rule 2 - Missing Critical] Added `@UniqueConstraint` annotation to Refund entity**
- **Found during:** Task 1 (RefundRepositoryTest's `uniqueIdempotencyKey_rejectsDuplicate` failed)
- **Issue:** The plan placed the unique constraint solely in V36 SQL, but `application-test.yml` runs `ddl-auto: create-drop` with Flyway disabled — Hibernate generates the test schema from entity annotations only. Without `@UniqueConstraint`, H2 had no unique index and the duplicate-insert test passed instead of throwing. This is a correctness gap: the entity contract didn't match the SQL contract.
- **Fix:** Added `uniqueConstraints = @UniqueConstraint(name = "refunds_idem_unique", columnNames = {"tenant_id", "idempotency_key"})` to `@Table` on `Refund`. Now both the H2 test schema and the Postgres production schema enforce the same dedup contract.
- **Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/Refund.java:36-43`
- **Verification:** RefundRepositoryTest `uniqueIdempotencyKey_rejectsDuplicate` now throws `DataIntegrityViolationException` as expected.
- **Committed in:** `36d3239` (Task 1)

**3. [Rule 1 - Bug] Snapshot-style verification in happy-path RefundServiceTest**
- **Found during:** Task 2 (`createRefund_happyPath_persistsCreatingThenSucceededAndTransitionsOrder` failed: expected CREATING but was succeeded)
- **Issue:** The plan's recommended `ArgumentCaptor<Refund>` pattern captures the same JPA entity reference passed to `saveAndFlush(...)` — but the production code legitimately mutates that same instance in-place (sets `stripeRefundId`, updates status to `succeeded`) BEFORE the test reads `creatingCap.getValue().getStatus()`. So the captor read the post-mutation state.
- **Fix:** Replaced the captor for the CREATING insert with `AtomicReference<RefundStatus>` snapshots taken inside the `thenAnswer(...)` lambda. This records the entity state at the *moment* `saveAndFlush` is called, before any subsequent mutation. The captor pattern is retained for the post-Stripe `save(...)` call, where mutation has already settled. The fix is purely test-side.
- **Files modified:** `core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java:118-180`
- **Verification:** All 11 RefundServiceTest cases green; the snapshot proves the CREATING-before-Stripe ordering required by UC-1 LOCKED.
- **Committed in:** `962a692` (Task 2)

---

**Total deviations:** 3 auto-fixed (2 Rule 1 bugs, 1 Rule 2 missing-critical)
**Impact on plan:** All three were correctness gaps that would have shipped a broken state machine, a non-deduped test schema, or a self-contradictory test. None expanded scope; each closes a hole the plan implied but did not specify.

## Issues Encountered

- **JDK 25 default on local toolchain.** Initial `./gradlew` invocations failed because `JAVA_HOME` pointed to JDK 25 (Gradle 8.10 incompatible per CLAUDE.md). Resolved by setting `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64` for all subsequent Gradle invocations. The build's `java { toolchain { languageVersion.set(21) } }` block resolves the toolchain correctly under JDK 21.
- **Refund namespace collision.** `com.stripe.model.Refund` and `uk.jtoye.core.payment.Refund` collided in the test imports. Resolved by importing only the entity and qualifying `com.stripe.model.Refund` inline in the helper method that constructs a Stripe response stub.

## User Setup Required

None — this plan is pure backend persistence + business logic. The vendor-facing controller (Plan 17-03) and frontend (Plan 17-04) introduce the user surface; webhook subscription configuration is required at deploy time (`refund.created`, `refund.updated`, `refund.failed`) per UC-4 LOCKED but is captured in 17-03's USER-SETUP.

## Next Phase Readiness

- **17-02** unblocked: V36 added `payment_event_outbox.exchange` with default `'payment.events'`. `PaymentEventOutboxFlusher` per-row routing + `RefundEventPublisher` can build directly on the new column. No further DB schema work needed.
- **17-03** unblocked: `RefundService.createRefund` is the controller's sole dependency for the create path; `findByOrderId` covers the GET. Webhook handler will call `findByStripeRefundId` (already on the repository) and `markRefundFailed` (already public).
- **17-04** unblocked from a contract perspective: `RefundDto` is the wire shape; `OrderStatus.REFUNDED` is the new TypeScript union member.

## TDD Gate Compliance

This plan's tasks were marked `tdd="true"` but as `type="execute"` (not a `type: tdd` plan), strict RED-then-GREEN commits were not required. Both task commits include tests alongside production code, and all behaviors specified under `<behavior>` are covered. The `:core-java:test` suite passes 390/390 with no skipped tests.

## Self-Check: PASSED

**Files (created):**
- `core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/Refund.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/RefundReason.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/RefundStatus.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/RefundRepository.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/RefundMapper.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/StripeRefundClient.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/dto/CreateRefundRequest.java` ✔ FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/dto/RefundDto.java` ✔ FOUND
- `core-java/src/test/java/uk/jtoye/core/payment/RefundRepositoryTest.java` ✔ FOUND
- `core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java` ✔ FOUND

**Commits:**
- `36d3239` (Task 1) ✔ FOUND
- `962a692` (Task 2) ✔ FOUND

---
*Phase: 17-vendor-order-detail-stripe-refund-flow*
*Plan: 01*
*Completed: 2026-04-28*
