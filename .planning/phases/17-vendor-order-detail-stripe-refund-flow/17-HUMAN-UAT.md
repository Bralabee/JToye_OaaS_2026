---
status: partial
phase: 17-vendor-order-detail-stripe-refund-flow
source: [17-VERIFICATION.md]
started: 2026-04-28T12:10:00Z
updated: 2026-04-28T12:10:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Live Playwright e2e on dev stack
expected: vendor-refund-flow.spec.ts runs end-to-end against a dev stack with Stripe test-mode keys + a seeded CONFIRMED+CAPTURED order. Both tests pass without test.skip — vendor login → list → click row → detail → refund dialog → £1.00 partial submit → Refunding… → dialog closes → £1.00 row appears under Refunds(1). Stripe dashboard shows the refund. processed_stripe_events shows the dedup row. payment_event_outbox shows order.events row marked SENT after flusher tick.
result: [pending]

### 2. BL-04 metadata-less webhook posture
expected: Confirm UC-5 LOCKED interpretation — current behavior drops ALL metadata-less refund.* webhook events (incl. internally-issued refunds where metadata accidentally got stripped). Either accept this posture and add operator alerting on the WARN log, or schedule a follow-up to add a stripe_refund_id-only fallback that runs SET LOCAL row_security=off.
result: [pending]

### 3. WR-09 partial-refund UX decision
expected: Issue a £5 partial refund on a £20 order, then verify the vendor can issue a SECOND £5 partial refund afterwards. Current production behavior: first partial flips order.status=REFUNDED + paymentStatus=REFUNDED, the OrderDetailPanel canRefund predicate fails, the Issue refund button disappears, and a second attempt would hit the already-REFUNDED short-circuit. Phase needs a human decision: accept WR-09 as-is for v2.2 (single partial refund per order), or schedule a follow-up plan to introduce PARTIALLY_REFUNDED status.
result: [pending]

### 4. BL-01 production routing smoke test
expected: Deploy + curl http://localhost:8080/api/v1/orders/<id>/refund with valid JWT + body and confirm 201/400/502 responses; curl http://localhost:8080/orders/<id>/refund with same body and confirm 401 or 404. RefundControllerIntegrationTest uses @WebMvcTest which doesn't load WebConfig — only a live dispatch confirms BL-01 fix is wired in production.
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps
