---
phase: 17-vendor-order-detail-stripe-refund-flow
plan: 02
subsystem: payments

tags: [outbox, rabbitmq, refunds, jpa, spring-amqp, jackson, multi-exchange-routing]

requires:
  - phase: 17-01
    provides: "V36 migration adds payment_event_outbox.exchange column with default 'payment.events'; refunds table + RefundService skeleton"
  - phase: 11-checkout-payments
    provides: "PaymentEventOutbox + PaymentEventOutboxFlusher + PaymentEventPublisher (V31 outbox pattern)"
provides:
  - "PaymentEventOutbox.exchange field + getter/setter + 5-arg constructor (maps V36 column)"
  - "PaymentEventOutboxFlusher.publishRow per-row exchange routing — order.events → RefundEvent, payment.events → PaymentEvent, NULL → fallback to payment.events"
  - "RefundEvent record + RefundEventType enum (REFUND_SUCCEEDED / REFUND_FAILED / REFUND_UPDATED)"
  - "RefundEventPublisher with publishRefundSucceeded/Failed/Updated mirroring PaymentEventPublisher"
affects: [17-03-controller-webhook, 17-04-frontend-detail-refund]

tech-stack:
  added: ["RefundEvent domain record", "per-row AMQP exchange routing in shared outbox"]
  patterns:
    - "single outbox table, per-row exchange column (UC-2 LOCKED) — avoids second order_event_outbox table while preserving payment.events flow"
    - "exchange-driven payload deserialization (order.events → RefundEvent, payment.events → PaymentEvent) keeps business code branch-free at the publisher layer"
    - "constant-driven destination (RabbitMQConfig.ORDER_EVENTS_EXCHANGE) keeps publisher tied to the queue declaration in RabbitMQConfig — no string-literal duplication"

key-files:
  created:
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundEvent.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java"

key-decisions:
  - "Defensive NULL-exchange fallback to payment.events with warn log — protects any pre-V36 in-flight row that managed to land before the V36 default column was applied. Cheaper than throwing and dead-lettering an otherwise-publishable row."
  - "Payload type chosen by exchange (not by routingKey prefix) — keeps the contract tight: ORDER_EVENTS_EXCHANGE → RefundEvent, anything else → PaymentEvent. Future event families add a new exchange and a new branch; routing-key sniffing would couple the flusher to naming conventions."
  - "RefundEventPublisher uses RabbitMQConfig.ORDER_EVENTS_EXCHANGE constant (not string literal) — passes structural grep gate and stays tied to the queue declaration in RabbitMQConfig.java line 19."

patterns-established:
  - "Per-row exchange routing on a shared outbox: V36 column + flusher dispatcher + per-publisher 5-arg constructor call. Future event families (e.g. shop.events, customer.events) plug in by adding a new exchange constant + a new flusher branch + a new publisher — no second outbox table required."

requirements-completed: [VOPS-02]

duration: ~6min
completed: 2026-04-28
---

# Phase 17 Plan 02: Outbox Per-Row Exchange Routing + RefundEventPublisher Summary

**Wires the V36 `payment_event_outbox.exchange` column into the JPA entity + the scheduled flusher, then adds a `RefundEventPublisher` that mirrors `PaymentEventPublisher` and writes outbox rows targeting `order.events` for `order.refunded` routing-key — closes UC-2 LOCKED (one outbox, exchange-per-row) without a second outbox table.**

## Performance

- **Duration:** ~6 min (worktree-resident execution)
- **Started:** 2026-04-28T09:30:21Z
- **Completed:** 2026-04-28T09:36:20Z
- **Tasks:** 2
- **Files created:** 3
- **Files modified:** 3
- **Lines changed:** +445 / -10

## Accomplishments

- **PaymentEventOutbox.exchange field** — added with default `"payment.events"` so existing payment-event rows preserve their behaviour. New 5-arg constructor `(tenantId, eventType, routingKey, payload, exchange)` is what `RefundEventPublisher` calls; the 4-arg constructor is unchanged so `PaymentEventPublisher` (and any other `payment.events` caller) continues compiling without modification. Getter/setter added for flusher reads + tests that null out the column.
- **PaymentEventOutboxFlusher.publishRow per-row routing** — replaced the hard-coded `RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE` argument in `convertAndSend` with `row.getExchange()`. Payload deserialization now picks the type by exchange: `ORDER_EVENTS_EXCHANGE` → `RefundEvent.class`, anything else → `PaymentEvent.class`. NULL exchange (defensive — any pre-V36 in-flight row that bypassed the column default) logs a warning and falls back to `payment.events` so the row still publishes instead of dead-lettering.
- **RefundEvent domain record** — 11 fields (`refundId, orderId, tenantId, orderNumber, stripeRefundId, amountPennies, currency, type, status, failureReason, occurredAt`) + nested `RefundEventType` enum (`REFUND_SUCCEEDED, REFUND_FAILED, REFUND_UPDATED`). Matches `PaymentEvent`'s shape conceptually but extends with refund-specific fields (refundId, stripeRefundId, status as wire string).
- **RefundEventPublisher** — `@Component` with 3 publish methods (`publishRefundSucceeded`, `publishRefundFailed`, `publishRefundUpdated`). Each persists exactly one `PaymentEventOutbox` row in the caller's transaction with `exchange=RabbitMQConfig.ORDER_EVENTS_EXCHANGE`, `routingKey="order.refunded"`, `eventType=type.name()`. ObjectMapper failures rethrow as `IllegalStateException` and never save a partial row.
- **Test count delta**: +3 in `PaymentEventOutboxFlusherTest` (payment-row routing, order-row routing + RefundEvent deserialization, NULL fallback), +4 in `RefundEventPublisherTest` (succeeded happy path with payload round-trip, failed with reason, updated, JsonProcessingException → IllegalStateException without save). Existing `PaymentEventPublisherTest` (3) and existing `PaymentEventOutboxFlusherTest` (4) all still green.
- **Full `:core-java:test` suite green at 397 tests, 0 failures, 0 errors, 0 skipped.** Was 390 in 17-01; +7 from this plan.

## Task Commits

1. **Task 1: PaymentEventOutbox.exchange field + Flusher per-row routing + RefundEvent record** — `952ebb1` (feat)
2. **Task 2: RefundEventPublisher + 4 unit tests** — `a7e5dbd` (feat)

## Files Created

- `core-java/src/main/java/uk/jtoye/core/payment/RefundEvent.java` — 11-field record + `RefundEventType` enum
- `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java` — 3 publish methods + `@Transactional protected persist(...)` mirroring `PaymentEventPublisher`
- `core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java` — 4 Mockito tests (round-trip, failed, updated, mapper-throws)

## Files Modified

- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java` — added `exchange` field (NOT NULL default `"payment.events"`), getter/setter, 5-arg constructor
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java` — `publishRow` reads `row.getExchange()`, dispatches deserialization by exchange, NULL fallback warning
- `core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java` — 3 new tests for routing matrix; helper methods `paymentRow()` / `refundRow()` build typed payloads

## Decisions Made

1. **NULL-exchange defensive fallback** — A pre-V36 in-flight row could theoretically have `exchange=NULL` if it landed in the seconds before V36's `ALTER TABLE ... NOT NULL DEFAULT` applied (or if a backfill race occurred). The flusher logs a warning and routes to `payment.events`. This is cheaper than throwing and dead-lettering a row that is otherwise publishable; the warn log surfaces the condition for ops investigation. The 4-arg `PaymentEventOutbox` constructor still sets the field initializer to `"payment.events"`, so any new row created via Java code is never null.
2. **Payload type by exchange, not routing key** — `routing_key` is a string the publisher chose; `exchange` is a constant from `RabbitMQConfig`. Switching on the exchange constant ties payload-deserialization decisions to the queue topology declared in `RabbitMQConfig.java`, not to a naming convention that could drift. Adding a new event family is a one-branch change in the flusher rather than a regex update.
3. **`RabbitMQConfig.ORDER_EVENTS_EXCHANGE` constant in publisher, not literal string** — passes the structural grep gate (literal `"order.events"` count = 0) and keeps the publisher tied to the queue declaration in `RabbitMQConfig.java:19`. If the exchange name changes, both sides update together; if a hard-coded literal had been used, only the publisher would have updated and the flusher's `equals` would silently miss the route.
4. **`RefundEvent.java` co-shipped in Task 1's commit** — The plan formally puts `RefundEvent` in Task 2, but `PaymentEventOutboxFlusher.publishRow` imports `RefundEvent` to do the type-driven deserialization. To keep each task individually compilable + reviewable, `RefundEvent.java` lives in Task 1's commit (it's a 30-line record needed for the flusher to compile in isolation) and Task 2's commit adds only the publisher + tests.

## Deviations from Plan

None — plan executed exactly as written. The four structural grep gates and full `:core-java:test` suite all pass first-time; no auto-fixes (Rules 1-3) and no architectural escalations (Rule 4) were needed.

The only minor judgement call was **co-shipping `RefundEvent.java` in Task 1's commit** (the plan placed it in Task 2). The plan acknowledges this in the inline note: *"`RefundEvent` is created in Task 2 of this plan — the flusher import will resolve once both tasks compile together."* I chose to keep each task's commit individually compilable rather than ship a Task-1 commit that fails to compile alone, which is a cleaner audit trail. The functional outcome is identical: by end-of-plan all files exist and all tests pass.

## Issues Encountered

- **JDK 25 default on local toolchain** — initial `./gradlew` invocation would have failed because `JAVA_HOME` defaults to JDK 25 (Gradle 8.10 incompatible per CLAUDE.md). All Gradle invocations were prefixed with `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64` per the GSD `<test_environment>` directive. The `java { toolchain { languageVersion.set(21) } }` block in `build.gradle.kts` resolves the toolchain correctly under JDK 21.
- **`build/` directory is `build-local/`** — the project's Gradle build dir is named `build-local`, not `build`. Test result XML lives at `core-java/build-local/test-results/test/`. This was a one-time recheck; doesn't affect the plan but worth noting for any future `find` commands targeting Gradle reports.

## User Setup Required

None — this plan is pure backend persistence + scheduled flusher logic. No new endpoints, no new schema (V36 was applied by Plan 17-01), no new RabbitMQ exchanges (`ORDER_EVENTS_EXCHANGE` already declared in `RabbitMQConfig.java:19` since v2.0). Plan 17-03 will introduce the controller and the webhook handler that *call* `RefundEventPublisher`; that plan owns the user-facing surface.

## Next Phase Readiness

- **17-03** unblocked end-to-end: `RefundEventPublisher` is the publisher seam Plan 17-03's webhook handler will call from `PaymentService.handleRefundEvent`. The contract is now stable — three methods (succeeded/failed/updated), each persisting one outbox row to `order.events` exchange. The flusher is already V36-aware and will pick those rows up on the next 5s tick.
- **17-04** unblocked from a contract perspective: the AMQP wire-format that the frontend's SSE service ultimately consumes (via `OrderSseService`) flows: `RefundService` → `RefundEventPublisher` (this plan) → outbox → flusher → RabbitMQ `order.events` exchange → `OrderSseService` → SSE → browser. The new `order.refunded` routing key fits cleanly into the existing SSE broadcast machinery without further changes.

## Threat Flags

None found. The threat register in the plan (`T-17-08` exchange tampering, `T-17-09` cross-tenant routing, `T-17-10` repudiation/lost event) is fully mitigated by the implementation:

- T-17-08 (Tampering): `exchange` column accepts only the constants `RabbitMQConfig.ORDER_EVENTS_EXCHANGE` or `RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE` from publisher code paths — no user input flows through. The VARCHAR(128) NOT NULL DEFAULT in V36 caps any direct DB write at 128 chars and rejects null.
- T-17-09 (Information Disclosure): `RefundEvent.tenantId` is set from the caller's already-validated `Refund.tenantId`; `PaymentEventOutboxFlusher` iterates per-tenant via `TenantContext.set` so RLS on `payment_event_outbox` filters each tenant's rows independently. No code path lets one tenant's refund row publish under another tenant's exchange.
- T-17-10 (Repudiation / lost event): row stays `PENDING` until publish succeeds; up to `MAX_ATTEMPTS=5` retries before flipping `FAILED`; `payment.outbox.dead_letter` Micrometer counter increments on terminal failure. Same lifecycle for refund rows as for payment rows — the existing alerting infrastructure covers both.

No new security-relevant surface introduced beyond what the plan's threat model already accounts for.

## TDD Gate Compliance

Both tasks were marked `tdd="true"` but as `type="execute"` (not a `type: tdd` plan), strict RED-then-GREEN commit separation was not required. Both task commits include tests alongside production code:

- **Task 1 (`952ebb1`)**: 3 new test methods in `PaymentEventOutboxFlusherTest` were authored in the same commit as the flusher change. The new tests would have failed against the pre-modification flusher (which hard-coded `PAYMENT_EVENTS_EXCHANGE`); they pass against the post-modification flusher.
- **Task 2 (`a7e5dbd`)**: 4 new test methods in `RefundEventPublisherTest` were authored in the same commit as the publisher. All are exercise the publisher's three publish paths plus the failure path.

Full `:core-java:test` suite passes 397/0/0 with no skipped tests.

## Self-Check: PASSED

**Files (created):**
- `core-java/src/main/java/uk/jtoye/core/payment/RefundEvent.java` — FOUND
- `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java` — FOUND
- `core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java` — FOUND

**Files (modified):**
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java` — modified (exchange field + 5-arg ctor)
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java` — modified (per-row routing)
- `core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java` — modified (+3 tests)

**Commits:**
- `952ebb1` (Task 1) — FOUND
- `a7e5dbd` (Task 2) — FOUND

**Structural verify gates:**
- `grep "row.getExchange()"` in flusher: 1 (≥1) — PASS
- `grep "PAYMENT_EVENTS_EXCHANGE,"` in flusher: 0 (=0) — PASS
- `grep "ORDER_EVENTS_EXCHANGE"` in publisher: 1 (≥1) — PASS
- `grep '"order.events"'` literal in publisher: 0 (=0) — PASS

**Test verification:**
- `:core-java:test --tests "*PaymentEventOutboxFlusherTest"` — green
- `:core-java:test --tests "*RefundEventPublisherTest"` — green
- Full `:core-java:test` suite — 397/0/0/0 (tests/failures/errors/skipped)

---
*Phase: 17-vendor-order-detail-stripe-refund-flow*
*Plan: 02*
*Completed: 2026-04-28*
