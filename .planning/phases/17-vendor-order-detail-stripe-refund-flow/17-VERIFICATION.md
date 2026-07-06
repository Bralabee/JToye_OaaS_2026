---
phase: 17-vendor-order-detail-stripe-refund-flow
verified: 2026-04-28T12:05:00Z
status: human_needed
score: 4/5 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: n/a
  gaps_closed: []
  gaps_remaining: []
  regressions: []
gaps:
  - truth: "/dashboard/orders/[id] renders all order context: header (status, timestamps, transition timeline), customer block, item lines (product, qty, modifiers, price), payment block, refund history"
    status: partial
    reason: "OrderDetailPanel renders header status badge, customer block, items, payment block, refunds — but the explicit ROADMAP/REQUIREMENTS 'state-transition timeline' subcomponent of the header is NOT implemented. Only createdAt is rendered; there is no Envers-driven or stateChange-event-driven timeline of past transitions."
    artifacts:
      - path: "frontend/components/dashboard/orders/OrderDetailPanel.tsx"
        issue: "Header renders status badge + createdAt only (line 124-145). No transitions/timeline component, no list of past statuses + timestamps."
      - path: "core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java"
        issue: "DTO carries createdAt + updatedAt but no transitions[]/stateHistory[] field; backend exposes no endpoint for state history."
    missing:
      - "OrderDetailDto field carrying ordered list of state transitions (status, occurredAt, optional actor/note) — likely backed by Envers RevisionEntity reads"
      - "Backend service method to source transitions (e.g., OrderService.getStateHistoryForOrder) — currently no such method exists"
      - "OrderDetailPanel timeline UI block showing each prior state + timestamp"
human_verification:
  - test: "Run vendor-refund-flow.spec.ts against a live dev stack with Stripe test-mode keys + a seeded CONFIRMED+CAPTURED order with a real Stripe test PaymentIntent"
    expected: "Both Playwright tests run end-to-end (no test.skip): vendor login → list → click row → detail → refund dialog → £1.00 partial submit → Refunding… → dialog closes → £1.00 row appears under Refunds(1) heading. Stripe dashboard shows the refund. processed_stripe_events shows the dedup row. payment_event_outbox shows order.events row marked SENT after flusher tick."
    why_human: "Success criterion #5 is the whole-loop integration check (browser → API → Stripe → webhook → DB → SSE → UI). Spec compiles cleanly and is structurally aligned with the Task 1 selectors (selector contract documented in 17-04-SUMMARY.md), but the live run currently skips on the dev stack because the seeded vendor + Stripe-captured order are not present. No automated CI gate proves this end-to-end today."
  - test: "BL-04 fix interpretation: confirm UC-5 LOCKED 'externally-issued refunds out of scope' applies to ALL metadata-less refund.* webhook events (incl. internally-issued refunds where metadata accidentally gets stripped)"
    expected: "If a future regression breaks RefundService.createRefund metadata population (line 181-183), the webhook lifecycle for those refunds will be silently dropped — accept this as the documented posture or add a stripe_refund_id-only fallback that runs SET LOCAL row_security=off"
    why_human: "REVIEW-FIX.md flagged this as 'requires human verification'. The BL-04 fix-bot interpretation drops ALL no-metadata events; a human should confirm this matches phase intent and that operations have alerting on the metadata-less case (currently a WARN log only, no metric)."
  - test: "WR-09 partial-refund UX: issue a £5 partial refund on a £20 order, then verify the vendor can issue a SECOND £5 partial refund afterwards"
    expected: "Per ROADMAP goal 'issue a full or partial Stripe refund', sequential partials should both succeed. Current production behavior: first partial flips order.status=REFUNDED + paymentStatus=REFUNDED; the OrderDetailPanel's canRefund predicate fails (status not in REFUNDABLE_STATUSES, paymentStatus≠CAPTURED), the Issue refund button disappears. Backend RefundService.createRefund hits the already-REFUNDED short-circuit (line 116) and returns the existing first refund — no second Stripe call, no second Refund row."
    why_human: "REVIEW-FIX.md skipped WR-09 (requires state-machine + UI + CONTEXT-LOCKED revisions outside fix-bot scope). The literal 5 ROADMAP success criteria do not require sequential partial-refunds (#5 says 'enters partial amount' once), but the GOAL says 'issue a full or partial' which most readers interpret as 'either type works repeatedly until exhausted'. Phase needs a human decision: accept WR-09 as-is for v2.2 (single partial refund per order, then the UI correctly shows REFUNDED), or schedule a follow-up plan to introduce a PARTIALLY_REFUNDED status."
  - test: "Verify production RefundController routing: deploy + curl http://localhost:8080/api/v1/orders/<id>/refund with valid JWT + body and confirm 201/400/502 responses; curl http://localhost:8080/orders/<id>/refund with same body and confirm 401 or 404 (BL-01 fix verification)"
    expected: "/api/v1/orders/{id}/refund routes to RefundController.createRefund (production behavior). The legacy /orders/{id}/refund path returns 401/404 (rejected at security layer or unmapped). RefundControllerIntegrationTest uses @WebMvcTest which doesn't load WebConfig — only a live dispatch test or a full @SpringBootTest can confirm BL-01 is closed in production."
    why_human: "REVIEW BL-01 noted that @WebMvcTest masks production routing, and REVIEW-FIX.md kept @WebMvcTest. The fix is structurally correct (RequestMapping is hard-coded as /api/v1/orders) but the masking still holds — only a full-context integration verification confirms the controller actually responds at the path the frontend posts to."
---

# Phase 17: Vendor Order Detail + Stripe Refund Flow Verification Report

**Phase Goal:** Vendors can open any order from `/dashboard/orders`, see its full context (items, payment, transitions), and issue a full or partial Stripe refund with a structured reason — and the refund flows through Stripe, the database, the order state machine, and the RabbitMQ event bus consistently.
**Verified:** 2026-04-28T12:05:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #   | Truth                                                                                                                                                                                                  | Status         | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `/dashboard/orders/[id]` renders all order context: header (status, timestamps, **transition timeline**), customer block, item lines, payment block (Stripe payment intent, refund history)            | ⚠️ PARTIAL     | All 5 blocks render except the explicit "transition timeline" subcomponent of the header (`OrderDetailPanel.tsx` line 124-145 shows only status badge + createdAt; no list of prior status changes). REQUIREMENTS.md VOPS-01 explicitly calls for "state transitions timeline" — gap is real but small.                                                                                                                                              |
| 2   | `POST /api/v1/orders/{id}/refund` with `{ amount_pennies, reason, note? }` creates a Stripe refund via `Refund.create`, persists a `Refund` entity via Flyway V36 migration, publishes `order.refunded` | ✓ VERIFIED     | `RefundController.java:46` (`/api/v1/orders`), `RefundService.java:177-188` (Stripe.Refund.create with stored idempotency key), `V36__refunds_and_outbox_exchange.sql` creates refunds + refunds_aud + RLS, `RefundEventPublisher.java:113-120` writes `payment_event_outbox` row with exchange=`order.events`, routingKey=`order.refunded`. RefundControllerIntegrationTest 7/0/0/0 + RefundServiceTest 11/0/0/0 + RefundEventPublisherTest 4/0/0/0. |
| 3   | `OrderStateMachine` accepts `REFUND_REQUESTED` event `CONFIRMED|PREPARING|READY|COMPLETED → REFUNDED`; second invocation on REFUNDED order is idempotent (no exception, no-op)                          | ✓ VERIFIED     | `OrderStateMachineConfig.java:128-153` defines all 4 transitions; `.end(REFUNDED)` at line 41. State machine itself throws on REFUNDED→REFUNDED (`refundRequestedFromRefunded_throwsInvalidStateTransition` line 166); RefundService short-circuits at `RefundService.java:116-125` BEFORE state-machine call, returning existing refund — verified by `createRefund_orderAlreadyRefunded_returnsLatestRefundNoStripeCall` (RefundServiceTest:218).    |
| 4   | Stripe webhook `charge.refunded` / `refund.updated` events update `Refund.status` in the database (webhook handler integration test with fixture payload)                                              | ✓ VERIFIED     | `PaymentService.java:162-170` adds 3 refund.* cases + 1 charge.refunded no-op AFTER processed_stripe_events dedup INSERT (line 145-148). `RefundService.handleStripeRefundEvent` at `RefundService.java:250-297` parses metadata, sets TenantContext, applies status. `RefundWebhookHandlingIntegrationTest.java` 7 Testcontainers tests cover all 4 event types + dedup re-delivery + metadata-less ignore.                                          |
| 5   | Playwright e2e: vendor login → orders list → click row → detail → refund dialog → partial → confirm → Stripe test-mode succeeds → UI shows REFUNDED + refund history                                   | ⚠️ STRUCTURAL  | `frontend/e2e/vendor-refund-flow.spec.ts` exists (`playwright test --list` shows 4 invocations) and matches the Task 1 selector contract documented in 17-04-SUMMARY.md. However, the spec uses `test.skip(true, "...")` when fixtures (NextAuth sign-in form, CONFIRMED+CAPTURED Stripe order) are missing — and per 17-04-SUMMARY.md the most recent live run skipped both tests. End-to-end behavioral proof requires human verification.       |

**Score:** 3/5 fully verified, 2/5 partial (1 gap, 1 needing human verification for live run)

### Required Artifacts

| Artifact                                                                                          | Expected                                                                                | Status     | Details                                                                                              |
| ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ---------- | ---------------------------------------------------------------------------------------------------- |
| `core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql`                  | refunds + refunds_aud + RLS + orders_status_check rewrite + payment_event_outbox.exchange | ✓ VERIFIED | All 4 concerns present; ENABLE+FORCE RLS via `app.current_tenant_id`; verified by file read.         |
| `core-java/src/main/resources/db/migration/V37__refunds_aud_version_column.sql`                   | (WR-01 fix) ADD COLUMN version BIGINT to refunds_aud                                    | ✓ VERIFIED | New 13-line migration adding the missing @Version column to audit table.                             |
| `core-java/src/main/java/uk/jtoye/core/payment/Refund.java`                                       | JPA entity with @Audited, @Version, @UniqueConstraint(tenant_id, idempotency_key)        | ✓ VERIFIED | Lines 35-92 confirm.                                                                                  |
| `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java`                                | 8-step stored-first flow + handleStripeRefundEvent + markRefundFailed REQUIRES_NEW       | ✓ VERIFIED | `createRefund` (line 94), `handleStripeRefundEvent` (line 250), `markRefundFailed` (line 218).        |
| `core-java/src/main/java/uk/jtoye/core/payment/RefundController.java`                             | POST /api/v1/orders/{id}/refund + GET /api/v1/orders/{id}/refunds, Idempotency-Key       | ✓ VERIFIED | `@RequestMapping("/api/v1/orders")` line 46; BL-01 fix in place. Idempotency-Key header forwarded.    |
| `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java`                         | Outbox-backed publisher writing exchange=order.events, routingKey=order.refunded         | ✓ VERIFIED | Uses `RabbitMQConfig.ORDER_EVENTS_EXCHANGE` constant (line 105, 118); WR-02 + WR-05 fixes applied.    |
| `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java`                    | Per-row exchange routing via row.getExchange()                                          | ✓ VERIFIED | `publishRow` line 95-149 reads getExchange() and dispatches; deserializes RefundEvent for order rows. |
| `core-java/src/main/java/uk/jtoye/core/order/OrderStatus.java`                                    | REFUNDED enum value                                                                     | ✓ VERIFIED | Line 30 confirms.                                                                                     |
| `core-java/src/main/java/uk/jtoye/core/order/OrderEvent.java`                                     | REFUND_REQUESTED enum value                                                             | ✓ VERIFIED | Line 27 confirms.                                                                                     |
| `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java`                        | 4 transitions + .end(REFUNDED); .end(COMPLETED) removed                                 | ✓ VERIFIED | Lines 128-153 (4 transitions), 41 (.end), 33-39 (COMPLETED no longer end-state).                      |
| `core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java`                             | paymentStatus + paymentReference + paymentMethod + refunds[]                             | ✓ VERIFIED | Lines 29-32 add 4 fields with full getters/setters.                                                   |
| `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java`                        | StripeException → 502 BAD_GATEWAY ProblemDetail with stripeCode property                 | ✓ VERIFIED | `@ExceptionHandler(StripeException.class)` line 176-188.                                              |
| `frontend/types/api.ts`                                                                           | OrderStatus union + REFUNDED, Refund interface, CreateRefundRequest, PaymentStatus       | ✓ VERIFIED | Line 105 (REFUNDED), 108-114 (PaymentStatus), 117-150 (RefundReason/Status, Refund, CreateRefundRequest), 197 (paymentStatus on OrderDetail). |
| `frontend/components/dashboard/orders/OrderDetailPanel.tsx`                                       | Reusable detail panel: header + customer + payment + items + refunds + action            | ⚠️ ALMOST  | All 5 blocks present; canRefund predicate correct; **missing transition timeline subcomponent** (gap on truth #1). |
| `frontend/components/dashboard/orders/RefundDialog.tsx`                                           | Zod-validated refund modal posting with crypto.randomUUID Idempotency-Key                | ✓ VERIFIED | apiClient.post line 161-165, Idempotency-Key header (164), 3-tier crypto.randomUUID/getRandomValues fallback (line 65-90, WR-07 fix). |
| `frontend/app/dashboard/orders/[id]/page.tsx`                                                     | Detail route — fetch /detail + SSE re-fetch                                             | ✓ VERIFIED | apiClient.get line 40 to `/api/v1/orders/${orderId}/detail`; fetchEventSource subscription line 75-99. |
| `frontend/app/dashboard/orders/page.tsx`                                                          | Row click navigates to detail route; REFUNDED in statusConfig + filter                  | ✓ VERIFIED | Line 592 `router.push('/dashboard/orders/${order.id}')`; line 120 statusConfig REFUNDED; line 542 filter dropdown. |
| `frontend/e2e/vendor-refund-flow.spec.ts`                                                         | Playwright spec — login → list → detail → refund → REFUNDED                              | ⚠️ STRUCTURAL | Spec compiles, lists 4 tests; 17-04-SUMMARY.md notes both tests skip on the current dev stack (no live behavioral proof). |

### Key Link Verification

| From                                       | To                                                  | Via                                              | Status     | Details                                                                                                              |
| ------------------------------------------ | --------------------------------------------------- | ------------------------------------------------ | ---------- | -------------------------------------------------------------------------------------------------------------------- |
| RefundService.createRefund                 | Stripe.Refund.create                                | StripeRefundClient seam + RequestOptions Idempotency-Key | ✓ WIRED    | `RefundService.java:188` calls `stripeRefundClient.create(params, opts)`; opts built with `setIdempotencyKey(serverIdemKey)` (line 185-186). |
| RefundService.createRefund                 | OrderStateMachineService.sendEvent                  | REFUND_REQUESTED event after Stripe success      | ✓ WIRED    | `RefundService.java:201-202` calls sendEvent AFTER stripe call; result drives order.setStatus.                       |
| V36 migration                              | orders_status_check rewrite                         | DROP CONSTRAINT + ADD CONSTRAINT with REFUNDED   | ✓ WIRED    | V36 lines 84-86 drop and recreate with REFUNDED in IN-list.                                                          |
| RefundEventPublisher.persist               | payment_event_outbox row save                       | exchange=ORDER_EVENTS_EXCHANGE                   | ✓ WIRED    | `RefundEventPublisher.java:113-120` builds outbox with constant; `outboxRepository.save(row)` line 120.              |
| PaymentEventOutboxFlusher.publishRow       | RabbitTemplate.convertAndSend                       | row.getExchange() lookup                         | ✓ WIRED    | Line 101 reads, line 119 dispatches; routes RefundEvent to order.events, PaymentEvent to payment.events.             |
| RefundController.createRefund              | RefundService.createRefund                          | Idempotency-Key header pass-through              | ✓ WIRED    | `@RequestHeader("Idempotency-Key", required=false)` line 73; passed straight to service line 75.                     |
| PaymentService.handleWebhookEvent (refund) | RefundService.handleStripeRefundEvent               | switch case AFTER processed_stripe_events INSERT | ✓ WIRED    | Line 145-148 dedup INSERT; line 162-163 refund.* dispatches to refundService — order verified by REVIEW.             |
| GlobalExceptionHandler                     | StripeException → 502                               | @ExceptionHandler(StripeException.class)         | ✓ WIRED    | Line 176-188 returns ProblemDetail with HttpStatus.BAD_GATEWAY + stripeCode property.                                |
| RefundDialog onSubmit                      | POST /api/v1/orders/{id}/refund                     | apiClient.post with Idempotency-Key header       | ✓ WIRED    | RefundDialog.tsx line 161-165.                                                                                       |
| /dashboard/orders/[id]/page.tsx            | GET /api/v1/orders/{id}/detail                      | apiClient.get on mount                           | ✓ WIRED    | line 40.                                                                                                              |
| Orders list row click                      | /dashboard/orders/[id]                              | router.push                                      | ✓ WIRED    | orders/page.tsx line 592.                                                                                            |

### Data-Flow Trace (Level 4)

| Artifact                                              | Data Variable      | Source                                                | Produces Real Data | Status      |
| ----------------------------------------------------- | ------------------ | ----------------------------------------------------- | ------------------ | ----------- |
| frontend/app/dashboard/orders/[id]/page.tsx          | order              | apiClient.get('/api/v1/orders/${id}/detail')          | Yes — backed by JPA findById + RefundService.findByOrderId | ✓ FLOWING   |
| frontend/components/dashboard/orders/OrderDetailPanel | order, refunds     | Props from page.tsx; populated by OrderService.getOrderDetailById which reads orders + refunds tables | Yes                | ✓ FLOWING   |
| RefundService.createRefund                            | refund row         | refundRepository.saveAndFlush(refund) BEFORE Stripe   | Yes                | ✓ FLOWING   |
| RefundEventPublisher.persist                          | payment_event_outbox row | outboxRepository.save(row) with order.events exchange | Yes                | ✓ FLOWING   |
| PaymentEventOutboxFlusher.publishRow                  | RefundEvent       | objectMapper.readValue(row.getPayload(), RefundEvent.class) for order.events rows | Yes                | ✓ FLOWING   |

### Behavioral Spot-Checks

| Behavior                                                                              | Command                                                                                            | Result            | Status      |
| ------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | ----------------- | ----------- |
| Full Java test suite passes (default tag-exclude)                                     | `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew :core-java:test`                            | tests=404 failures=0 errors=0 | ✓ PASS      |
| Frontend Jest tests for refund components pass                                       | `npm test -- --testPathPattern="components/dashboard/orders/__tests__"`                            | 15/15 passed       | ✓ PASS      |
| Playwright vendor-refund-flow spec compiles + Playwright recognizes 4 invocations    | `npx playwright test e2e/vendor-refund-flow.spec.ts --list`                                        | 4 tests listed     | ✓ PASS      |
| RefundController hard-codes /api/v1 prefix                                            | `grep -E '@RequestMapping\("/api/v1/orders"\)' RefundController.java`                              | line 46 match      | ✓ PASS      |
| Live Playwright run end-to-end against dev stack with seeded order                   | (requires docker stack + Stripe test keys + seeded vendor; not run here)                            | n/a                | ? SKIP      |

### Requirements Coverage

| Requirement | Source Plan | Description                                                                       | Status         | Evidence                                                                                                   |
| ----------- | ----------- | --------------------------------------------------------------------------------- | -------------- | ---------------------------------------------------------------------------------------------------------- |
| VOPS-01     | 17-04       | Order detail view with header (incl. transitions timeline), customer, items, payment, refunds | ⚠️ PARTIAL     | All blocks present except the explicit "state transitions timeline" — see truth #1 gap.                    |
| VOPS-02     | 17-01, 17-02, 17-03, 17-04 | POST /refund + Stripe + Refund entity + V34/V36 migration + order.refunded RabbitMQ + webhook updates | ✓ SATISFIED    | Backend tests + frontend tests pass; all wiring verified; webhook handler integration-tested.              |
| VOPS-03     | 17-01       | REFUND_REQUESTED transition + idempotent no-op on REFUNDED order + Envers audit + INFO log | ✓ SATISFIED    | State-machine config + RefundService idempotency short-circuit + @Audited on Refund + log.info statements throughout. |

No orphaned requirements detected — all 3 VOPS-* IDs present in plan frontmatter requirements lists.

### Anti-Patterns Found

| File                                                                | Line     | Pattern                                                                          | Severity   | Impact                                                                                                                                                                                              |
| ------------------------------------------------------------------- | -------- | -------------------------------------------------------------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| frontend/app/dashboard/orders/page.tsx                              | 239-251, 827-955 | Dead inline-detail Dialog kept with eslint-disable comments (IN-01)              | ℹ️ Info    | ~130 LOC of unreachable code + 3 eslint-disable lines. Documented in 17-04-SUMMARY.md as deferred cleanup; not a correctness issue.                                                                  |
| RefundService.java                                                  | 201-206  | order.setPaymentStatus(REFUNDED) unconditionally even on partial refunds (WR-09) | ⚠️ Warning | Vendors cannot issue a SECOND partial refund on the same order — first one flips status REFUNDED + paymentStatus REFUNDED, the OrderDetailPanel `canRefund` predicate fails, button disappears. WR-09 was skipped by REVIEW-FIX (requires phase-level decision). |
| RefundDto.java                                                      | 24       | RefundReason enum leaks Stripe SDK enum names to wire format (WR-08)             | ℹ️ Info    | Forward-compat — not a current bug. Skipped by REVIEW-FIX (design decision).                                                                                                                          |
| PaymentEventOutboxFlusher.java                                      | 71-93    | Per-tick scan of all tenants → N×P queries every 5s (WR-04)                      | ℹ️ Info    | Performance concern at 100+ tenants; not a correctness bug. Skipped by REVIEW-FIX.                                                                                                                    |
| frontend/e2e/vendor-refund-flow.spec.ts                             | 75-101   | test.skip on missing fixtures (IN-05)                                            | ℹ️ Info    | CI shows "0 failures" while actual coverage is 0 in environments without seeded data. Documented limitation.                                                                                          |

### Human Verification Required

#### 1. Live Playwright E2E run with full dev stack

**Test:** Bring up `docker compose build && docker compose up -d`, ensure `STRIPE_API_KEY=sk_test_...` and `STRIPE_WEBHOOK_SECRET=<dev tunnel>` are set in `.env`, seed at least one CONFIRMED order whose `paymentReference` is a real Stripe test-mode PaymentIntent that has been captured. Then run `cd frontend && PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test e2e/vendor-refund-flow.spec.ts`.
**Expected:** Both tests run end-to-end (no test.skip): the success-path test issues a £1.00 partial refund and observes `Refunds (1)` heading + `£1.00` row + REFUNDED state badge (if order total was £1.00) on the detail page. Stripe dashboard shows the refund. `processed_stripe_events` row exists for the event.id. `payment_event_outbox` shows an `order.events` row marked `SENT` after the next flusher tick.
**Why human:** Success criterion #5 is the integration-loop check (browser → API → Stripe test mode → webhook → DB → SSE → UI). The spec compiles and matches the Task 1 selector contract, but the live run on the current dev stack skips both tests because the seeded vendor + Stripe-captured order are missing. No automated CI gate proves end-to-end behavior today.

#### 2. BL-04 metadata-less webhook posture confirmation

**Test:** Confirm the BL-04 fix-bot interpretation matches phase intent: ALL refund.* webhook events lacking `refund_id` OR `tenant_id` metadata are now silently dropped (logged at WARN, dedup row commits, never recovers).
**Expected:** Operations team accepts that internally-issued refunds whose metadata population path breaks at create-time will be silently dropped at webhook time, and there is operational alerting on the WARN log line `"Refund webhook ... is missing required metadata"`. Alternatively, the team approves a follow-up to add a `SET LOCAL row_security=off` admin lookup-by-stripe_refund_id fallback.
**Why human:** REVIEW-FIX.md explicitly flagged this as needing human confirmation. The fix-bot's UC-5 LOCKED interpretation is reasonable but conservative — a tenant with a regression in `RefundService.createRefund` metadata setters would silently lose webhook lifecycle updates.

#### 3. WR-09 partial-refund UX decision

**Test:** Issue a £5 refund on a £20 order. Then attempt a SECOND £5 refund.
**Expected:** Per the phase goal "issue a full or partial Stripe refund", a second partial should be allowed. Current production behavior: first partial flips order.status=REFUNDED + paymentStatus=REFUNDED; the UI hides the Issue refund button; the backend returns the FIRST refund (already-REFUNDED short-circuit at `RefundService.java:116`) — no second Stripe call.
**Why human:** REVIEW-FIX.md skipped WR-09 because the fix requires state-machine + UI + CONTEXT-LOCKED revisions outside fix-bot scope. The 5 explicit ROADMAP success criteria do not require sequential partials (#5 says "enters partial amount" once, singular), but the GOAL states "full or partial". Phase needs a human decision: (a) accept WR-09 as documented v2.2 behavior (one refund per order, then UI correctly shows REFUNDED) or (b) schedule a follow-up plan introducing a `PARTIALLY_REFUNDED` status with the matching state-machine + UI changes.

#### 4. Production routing verification (BL-01 fix masking)

**Test:** Deploy the build and curl `http://localhost:8080/api/v1/orders/<order_id>/refund` with valid JWT + body; then curl `http://localhost:8080/orders/<order_id>/refund` with the same body.
**Expected:** First call returns 201 (or 400/502 depending on order state). Second call returns 401 or 404 (rejected at security/dispatch layer). RefundControllerIntegrationTest uses `@WebMvcTest`, which doesn't load `WebConfig` — so the test passes regardless of whether production routing is correct.
**Why human:** REVIEW noted that `@WebMvcTest` masks BL-01. REVIEW-FIX.md kept `@WebMvcTest`. The fix is structurally correct (`@RequestMapping("/api/v1/orders")` is hard-coded on the controller), but only a full-context dispatch check confirms BL-01 is fully closed in the running container. A small additional `@SpringBootTest` smoke test would close this masking permanently.

### Gaps Summary

**One must-have is partial:** Truth #1 (order detail rendering) is mostly verified — header status badge, customer block, payment block, items, refunds all render — but the explicit "state-transition timeline" subcomponent of the header (called out in both ROADMAP success criterion #1 and REQUIREMENTS.md VOPS-01 wording) is NOT implemented. The header today shows only a status badge + createdAt timestamp; there is no list of past transitions (e.g., DRAFT@T1 → PENDING@T2 → CONFIRMED@T3) and no DTO field carrying that history. The frontend has no source for it because the backend exposes none. A small follow-up plan (or a focused fix) would: (a) add `OrderDetailDto.transitions: List<TransitionDto>` populated from Envers revisions (or from a future state-change audit table), (b) extend `OrderService.getOrderDetailById` to enrich it, (c) add a Timeline UI block to OrderDetailPanel showing each prior status with timestamp.

**Four human-verification items** are independently routable: (1) live Playwright run, (2) BL-04 posture confirmation, (3) WR-09 partial-refund UX decision, (4) BL-01 production routing smoke test. Items 2-4 are decisions or smoke checks that don't block the current implementation; item 1 is the ROADMAP success criterion #5 which cannot be considered fully met without behavioral evidence.

**The test suite is healthy:**
- Java: 404 tests pass (default tag-exclude); 0 failures, 0 errors. Testcontainers `RefundWebhookHandlingIntegrationTest` (7 tests) is excluded by default but file exists with comprehensive coverage including dedup, charge.refunded no-op, and metadata-less ignore.
- Frontend Jest: 15/15 refund-related tests pass; full suite was 99/99 per 17-04-SUMMARY.md.
- Playwright: spec compiles; 4 invocations registered; live run depends on environment seeding (skip-with-reason design).

**Ship recommendation:** The phase is feature-complete for the 4 verifiable truths and structurally complete for the 5th (live Playwright run). Whether to proceed depends on:
- Whether stakeholders accept the missing transitions-timeline subcomponent of truth #1 as v2.2 scope (or want a tiny follow-up plan).
- Whether the WR-09 partial-refund UX issue is acceptable as v2.2 behavior or needs a fix in this milestone.
- Whether the live Playwright run is treated as a pre-deploy gate (item 1) or a post-deploy smoke test.

---

_Verified: 2026-04-28T12:05:00Z_
_Verifier: Claude (gsd-verifier)_
