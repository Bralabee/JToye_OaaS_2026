---
phase: 21-onboarding-blocker-ux
plan: 02
subsystem: api
tags: [spring-boot, rabbitmq, transactional-outbox, onboarding, rls, testcontainers, state-machine, java]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice
    provides: "GateChainRunner async recompute (re-establishes tenant GUC on the worker thread; leaves MANUAL_REVIEW parked in VERIFYING), VendorOnboardingGate rows (V43 RLS/FORCE)"
  - phase: 17-payments (V46 outbox reliability)
    provides: "Shared payment_event_outbox table (RLS V33 + V46 backoff/resurrection), PaymentEventOutboxFlusher per-tenant drain, 5-arg PaymentEventOutbox ctor (custom exchange), OrderEventPublisher producer shape"
provides:
  - "onboarding.events TopicExchange (unbound seam — no queue/binding until Phase 24 #205)"
  - "OnboardingStateChangeEvent fixed-shape record + OnboardingEventPublisher (transactional-outbox producer, mirrors OrderEventPublisher)"
  - "PaymentEventOutboxFlusher onboarding.events dispatch branch (deserializes OnboardingStateChangeEvent, never poison-casts to PaymentEvent)"
  - "GateChainRunner MANUAL_REVIEW stall emission (publishStall in the stays-VERIFYING park branch, at-least-once)"
  - "Unit proof the flusher does not poison the new exchange + Testcontainers proof the recompute writes a tenant-stamped onboarding.events row"
affects: [21-03-visibility-gate-resolve, 24-outbound-webhooks]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "New outbox exchange = ship (a) exchange bean + (b) producer + (c) flusher dispatch branch as ONE atomic unit so the shared flusher never poisons the new row (Pitfall 1)"
    - "Notification emitted from the @Async @Transactional recompute worker (tenant GUC already re-established) so the outbox INSERT joins the async tx, is tenant-stamped and RLS-safe"
    - "Fixed human-readable event reason (FhrsGate discipline) — raw provider text stays in WARN logs, never in the payload"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingStateChangeEvent.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEventPublisher.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingEventPublisherTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingStallOutboxIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/GateChainRunnerTest.java

key-decisions:
  - "Reuse the shared payment_event_outbox (not a new table) — zero-migration boundary held; the flusher dispatch branch lands in the SAME plan as the producer so no onboarding row is ever deserialized as PaymentEvent (Pitfall 1)"
  - "onboarding.events is an unbound TopicExchange this phase — a topic exchange with no binding discards cleanly; Phase 24 (#205) attaches the durable queue + @RabbitListener without touching the producer"
  - "Emit only when a mandatory gate is MANUAL_REVIEW (guard added inside the new stays-VERIFYING else) — a still-PENDING webhook-wait recompute is NOT a stall; at-least-once + idempotent consumer (A3), no already-emitted guard"
  - "EVENT_TYPE=ONBOARDING_STALLED, routingKey=onboarding.state.manual_review, event reason=\"One or more checks need a manual review\" (fixed, human-readable)"

patterns-established:
  - "Atomic new-outbox-exchange unit: exchange bean + event record + producer + flusher dispatch branch must ship together"
  - "Async-recompute notification: emit side-effect events from inside the tenant-re-established @Async worker so the outbox row is tenant-stamped without a new async path"

requirements-completed: []  # ONBD-03 spans 21-02 (this outbox seam) + 21-03 (reviewPending DTO derivation, admin review queue, gate-resolve). Only the notification seam ships here; NOT marked complete to avoid a false-complete signal (REQUIREMENTS traceability: ONBD-03 -> 21-02, 21-03).

# Metrics
duration: 25min
completed: 2026-07-14
---

# Phase 21 Plan 02: Manual-Review Stall Notification Seam (Transactional Outbox) Summary

**When an onboarding recompute stalls in `VERIFYING` because a mandatory gate is `MANUAL_REVIEW`, a tenant-stamped `onboarding.events` row is written to the shared V46 transactional outbox — landing the exchange bean, `OnboardingStateChangeEvent` record, `OnboardingEventPublisher`, the flusher dispatch branch, and the `GateChainRunner` emission together so the hard-coded two-arm flusher never poison-casts the new row (RESEARCH Pitfall 1). Zero Flyway migrations; the state machine is untouched (the application parks exactly as before — only an outbox emission is added).**

## Performance

- **Duration:** ~25 min
- **Tasks:** 2 (both `type=auto`)
- **Commits:** 2 task commits (`5135475`, `0988147`) + this metadata commit
- **Files:** 4 created, 5 modified

## Accomplishments

- **The atomic Pitfall-1 unit (Task 1):** `ONBOARDING_EVENTS_EXCHANGE = "onboarding.events"` + an unbound `onboardingEventsExchange()` `TopicExchange` bean (no queue/binding); `OnboardingStateChangeEvent(onboardingId, tenantId, shopId, status, reason, occurredAt)`; `OnboardingEventPublisher.publishStall(...)` (mirrors `OrderEventPublisher` — `@Component`, NOT `@Transactional`, 5-arg `PaymentEventOutbox` ctor with the new exchange, serialization-failure → poisoned FAILED placeholder, never propagates); and a `PaymentEventOutboxFlusher.publishRow` `else if (ONBOARDING_EVENTS_EXCHANGE.equals(exchange))` branch positioned **before** the final `PaymentEvent` poison-sink `else`.
- **The emission (Task 2):** `GateChainRunner` gained a **new** `else { ... }` clause after `else if (anyFailed)` (previously a comment-only park). Inside it, when ≥1 mandatory gate is `MANUAL_REVIEW`, it calls `publishStall(onboardingId, tenantId, onboarding.getShopId(), VERIFYING, "One or more checks need a manual review")`. Because this runs on the `@Async @Transactional` worker with the tenant GUC already re-established, the outbox INSERT joins that transaction and is tenant-stamped/RLS-safe. No emission on the `GATES_PASSED`/`GATE_FAILED` branches, and none when a gate is merely still `PENDING` (webhook wait, not a review).
- **Proofs:** a flusher unit test asserts an `onboarding.events` row deserializes to `OnboardingStateChangeEvent` (not `PaymentEvent`) and is marked **SENT, not poison-FAILED**; a Testcontainers test drives the **real async recompute** and asserts a tenant-stamped `onboarding.events` outbox row with the correct `event_type`/`routing_key`/payload and **zero leakage** to an unrelated tenant.

## Task Commits

1. **Task 1 — onboarding.events exchange + event record + publisher + flusher dispatch branch**
   - `5135475` — feat(21-02): onboarding.events exchange + stall event + publisher + flusher dispatch
2. **Task 2 — emit the stall event from GateChainRunner's park branch + unit + Testcontainers proof**
   - `0988147` — feat(21-02): emit MANUAL_REVIEW stall event from GateChainRunner park branch

## Files Created/Modified

- `config/RabbitMQConfig.java` — MOD: added `ONBOARDING_EVENTS_EXCHANGE` constant + `onboardingEventsExchange()` `TopicExchange` bean (documented unbound seam — NO `Queue`/`Binding` referencing it).
- `onboarding/OnboardingStateChangeEvent.java` — NEW: fixed-shape serializable record; `reason` is a fixed human-readable string (ASVS V7).
- `onboarding/OnboardingEventPublisher.java` — NEW: transactional-outbox producer cloned from `OrderEventPublisher` (`@Component`, NOT `@Transactional`; `EVENT_TYPE="ONBOARDING_STALLED"`, `MANUAL_REVIEW_ROUTING_KEY="onboarding.state.manual_review"`; 5-arg ctor with `ONBOARDING_EVENTS_EXCHANGE`; poison-placeholder-on-serialization-failure).
- `payment/PaymentEventOutboxFlusher.java` — MOD: added the `onboarding.events` dispatch branch before the `PaymentEvent` else + import; poison/backoff/dead-letter handling untouched (already covers the new type).
- `onboarding/GateChainRunner.java` — MOD: constructor now injects `OnboardingEventPublisher`; new `else` park clause emits the stall (guarded on `MANUAL_REVIEW`).
- `payment/PaymentEventOutboxFlusherTest.java` — MOD: +1 test (`publishRow` with `exchange=onboarding.events` → `OnboardingStateChangeEvent`, SENT, no poison).
- `onboarding/OnboardingEventPublisherTest.java` — NEW: 2 tests (row shape/5-arg ctor/tenant stamp/round-trip; serialization-failure poison placeholder does not propagate).
- `onboarding/GateChainRunnerTest.java` — MOD: publisher added as a constructor mock; +2 tests (stall emitted on MANUAL_REVIEW park; NOT emitted while still PENDING) + `never()` publishStall on the GATES_PASSED/GATE_FAILED branches.
- `onboarding/OnboardingStallOutboxIntegrationTest.java` — NEW: 1 Testcontainers test (real async recompute writes a tenant-stamped `onboarding.events` row; no cross-tenant leakage).

**Test delta:** +6 Java `@Test` methods (2 `OnboardingEventPublisherTest`, +1 `PaymentEventOutboxFlusherTest`, +2 `GateChainRunnerTest`, +1 `OnboardingStallOutboxIntegrationTest`). `docs/metrics.json` was deliberately **not** reconciled here — the `scripts/docs-freshness.sh --write` reconciliation is plan 21-05's closing task (phase guardrail).

## Verification

Run from the repo root (`core-java` is a Gradle subproject; the wrapper is at the root — the plan's `cd core-java && ./gradlew` form does not apply here, per the environment correction). Testcontainers spins its own Postgres 15, independent of the compose stack.

- `./gradlew :core-java:test --tests "*PaymentEventOutboxFlusher*" --tests "*OnboardingEventPublisher*"` → BUILD SUCCESSFUL.
  - `OnboardingEventPublisherTest: tests=2 failures=0 errors=0`
  - `PaymentEventOutboxFlusherTest: tests=15 failures=0 errors=0` — incl. `publishRow with exchange=onboarding.events deserializes OnboardingStateChangeEvent (not PaymentEvent) and marks SENT — no poison (Pitfall 1)  OK`
- `./gradlew :core-java:test --tests "*GateChainRunnerTest*"` → `GateChainRunnerTest: tests=12 failures=0 errors=0` — incl. `runAndRecompute emits a stall event when a mandatory gate is MANUAL_REVIEW (stays VERIFYING)  OK` and `... does NOT emit a stall when a mandatory gate is still PENDING  OK`.
- `./gradlew :core-java:integrationTest --tests "*OnboardingStallOutboxIntegrationTest*"` → `OnboardingStallOutboxIntegrationTest: tests=1 failures=0 errors=0` — `manualReviewStall_writesTenantStampedOnboardingEventsOutboxRow()  OK (0.235s)`.
- Regression sweep — `./gradlew :core-java:test :core-java:integrationTest --tests "*Onboarding*" --tests "*PaymentEventOutboxFlusher*" --tests "*GateChainRunner*"` → **BUILD SUCCESSFUL in 5m 19s**; aggregate across the verification set: **18 test classes, 93 tests, 0 failures, 0 errors** (the new `OnboardingEventPublisher` `@Component` constructor param wires cleanly into every onboarding Spring context).

Guardrail / acceptance greps:
- Flusher onboarding branch (`ONBOARDING_EVENTS_EXCHANGE.equals(exchange)`, line 271) precedes the final `readValue(row.getPayload(), PaymentEvent.class)` (line 274). ✔
- `publishStall(` (line 224) is inside the new `} else {` (line 204) after `else if (anyFailed)` (line 202) — NOT in the allPassed/anyFailed branches. ✔
- `RabbitMQConfig` has `onboarding.events` + a `TopicExchange` bean and **NO** `Binding`/`Queue` referencing it. ✔
- **Zero** new files under `core-java/src/main/resources/db/migration/`. ✔

## Decisions Made

- **Shared outbox, not a new table.** Reusing `payment_event_outbox` keeps the zero-migration boundary; the cost is the flusher's hard-coded dispatch, mitigated by shipping the dispatch branch in the same plan as the producer (Pitfall 1). An onboarding row is therefore never deserialized as a `PaymentEvent`.
- **Unbound topic exchange.** No `Queue`/`Binding` this phase — a topic exchange drops unmatched messages cleanly, so nothing dead-letters while the consumer side is absent. Phase 24 (#205) attaches the subscription.
- **Emit only on MANUAL_REVIEW.** The new `else` covers both still-PENDING and MANUAL_REVIEW parks; a guard (`anyManualReview`) restricts emission to the human-review stall — matching truth #1 ("parked … because a mandatory gate is MANUAL_REVIEW"). At-least-once with an idempotent downstream consumer (A3); no already-emitted guard.
- **State machine untouched (Incremental Betterment).** The emission writes only an outbox row; it does not write `status`/`Shop.published` and does not change SM behaviour. The application parks in `VERIFYING` exactly as before.

## Deviations from Plan

None — plan executed exactly as written (both tasks, in order).

_(Execution note, not a plan deviation: the plan's `<verify>` blocks use `cd core-java && ./gradlew …`, which fails in this repo — there is no `core-java/gradlew`. Every command was run as `./gradlew :core-java:<task>` from the repo root, per the environment correction supplied with the task. The build output directory is `core-java/build-local/` in this environment.)_

## Known Stubs

None that block the plan's goal. The `onboarding.events` exchange is deliberately **unbound** (no consumer) this phase — that is the D-01 seam, not a stub: the event is written and durably queued in the outbox; Phase 24 (#205) delivers it. The producer, flusher dispatch, and tenant-stamp are fully wired and proven.

## Threat Flags

None — no new security surface beyond the plan's `<threat_model>`. The stall INSERT stamps `tenant_id` from the re-established async `TenantContext` and is written through the RLS-scoped `payment_event_outbox` (T-21-02-01, proven by the tenant-scoped integration count + zero-leakage assertion); the flusher dispatch branch prevents poison on the new exchange (T-21-02-04); the event `reason` is a fixed human-readable string, no provider internals (T-21-02-03).

## Issues Encountered

None. Both tasks passed verification on first implementation with no auto-fix cycles. No auth gates, no checkpoints, no architectural (Rule 4) decisions.

## Next Phase Readiness

- The notification seam is live for **21-03** (manual-review visibility + gate-resolve): the same `GateChainRunner.runAndRecompute` park path that gate-resolve triggers will now emit the stall event; 21-03 adds the DTO-derived `reviewPending`, the admin review queue, and `POST /onboarding/admin/{id}/gates/{gateType}/resolve`.
- **Phase 24 (#205 outbound webhooks)** consumes this event: it attaches a durable queue + binding + `@RabbitListener` to `onboarding.events` and delivers `OnboardingStateChangeEvent` — no producer change required.
- **ONBD-03 remains OPEN** in REQUIREMENTS traceability pending 21-03 (visibility + gate-resolve) and 21-04 (frontend in-review copy/back-off).

## Self-Check: PASSED

- Files verified present: `OnboardingStateChangeEvent.java`, `OnboardingEventPublisher.java`, `OnboardingEventPublisherTest.java`, `OnboardingStallOutboxIntegrationTest.java` (created); `RabbitMQConfig.java`, `PaymentEventOutboxFlusher.java`, `GateChainRunner.java`, `PaymentEventOutboxFlusherTest.java`, `GateChainRunnerTest.java` (modified) — all FOUND.
- Commits verified in `git log`: `5135475`, `0988147` — both FOUND.

---
*Phase: 21-onboarding-blocker-ux*
*Completed: 2026-07-14*
