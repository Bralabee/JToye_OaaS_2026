---
phase: 17-vendor-order-detail-stripe-refund-flow
fixed_at: 2026-04-28T11:55:00Z
review_path: .planning/phases/17-vendor-order-detail-stripe-refund-flow/17-REVIEW.md
iteration: 1
findings_in_scope: 13
fixed: 9
skipped: 4
status: partial
---

# Phase 17: Code Review Fix Report

**Fixed at:** 2026-04-28T11:55:00Z
**Source review:** .planning/phases/17-vendor-order-detail-stripe-refund-flow/17-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 13 (4 BLOCKER + 9 WARNING; 5 INFO out of scope)
- Fixed: 9
- Skipped: 4

All four blockers were fixed. Five warnings were fixed (one — WR-06 — folded
into the WR-03 commit since it touched adjacent code). Four warnings were
skipped: WR-04 (performance, non-correctness), WR-08 (forward-compat enum
choice needs phase-level approval), and WR-09 (partial-refund semantics
require state-machine, UI, and CONTEXT-LOCKED transition changes that are
out of scope for a fix-bot).

## Fixed Issues

### BL-01: RefundController mounted at /orders/{id}/refund, not /api/v1/orders/{id}/refund

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundController.java`, `core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java`
**Commit:** 4247ff8
**Applied fix:** Changed `@RequestMapping("/orders")` → `@RequestMapping("/api/v1/orders")` directly on `RefundController`. This is preferable to adding `uk.jtoye.core.payment` to `WebConfig.configurePathMatch` because the latter would unintentionally rewrite `PaymentController`'s `/public/payments/webhook` mapping to `/api/v1/public/payments/webhook` and break the Stripe webhook endpoint. Updated `Location` header URI to match. Updated `RefundControllerIntegrationTest` URLs from `/orders/...` to `/api/v1/orders/...` so test routing reflects production. Note: the test still uses `@WebMvcTest` which doesn't load `WebConfig`, but the URLs now match what the frontend posts to.

### BL-02: Webhook metadata-less fallback bypasses TenantContext, so RLS rejects the lookup and update

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java`, `core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java`
**Commit:** e13d858
**Applied fix:** Removed the `findByStripeRefundId` fallback from `handleStripeRefundEvent`. When `refund_id` or `tenant_id` metadata is missing, the handler now logs a warning and returns immediately — the deliberate "ignore externally-issued refunds" posture per UC-5 LOCKED. No DB access happens without TenantContext set, so the RLS-bypass risk is eliminated by construction.

### BL-03: Outbox INSERT in webhook fallback path violates RLS

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java`, `core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java`
**Commit:** e13d858 (same commit as BL-02 — coordinated fix)
**Applied fix:** The metadata-less short-circuit (BL-02 fix) means `RefundEventPublisher.persist` is never invoked without TenantContext set, so the outbox INSERT can never violate `payment_event_outbox` RLS in the no-metadata path. Closes BL-03 transitively.

### BL-04: processed_stripe_events guard commits a dedup row even when the side-effect path no-ops on the metadata-less path

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java`, `core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java`
**Commit:** e13d858 (same commit as BL-02 — coordinated fix)
**Applied fix:** Two parts — (a) for the metadata-less path the dedup-row-stays-committed behaviour is now the deliberate documented posture per UC-5 LOCKED rather than a fragile fallback; (b) the metadata-bearing path already throws `ResourceNotFoundException` on missing-row at RefundService.java:281-282 (verified pre-existing), which correctly rolls back the dedup INSERT in `PaymentService.handleWebhookEvent`'s `@Transactional` and lets Stripe retry once the local row catches up. Updated webhook integration test `webhookRefundWithoutMetadata_findsByStripeRefundIdIfPresent` → `webhookRefundWithoutMetadata_ignoresEventEvenWhenLocalRowExists` to assert the new "ignore + dedup row stays committed + no outbox row" posture.

> **Requires human verification:** the metadata-less posture intentionally drops events that lack metadata, including any internally-issued refunds whose metadata path was broken at create time. If a future code change fails to set `putMetadata("refund_id", ...)` / `putMetadata("tenant_id", ...)` in `RefundService.createRefund` (line 181-183), the webhook lifecycle for those refunds will be silently dropped. The mitigation is the metadata-bearing path's stored-first idempotency (Refund row inserted BEFORE Stripe call) — but please confirm the fix-bot's reading of UC-5 LOCKED matches phase intent.

### WR-01: refunds_aud table is missing the `version` column

**Files modified:** `core-java/src/main/resources/db/migration/V37__refunds_aud_version_column.sql`
**Commit:** be28e09
**Applied fix:** Created forward-only `V37` migration adding `version BIGINT` to `refunds_aud`. V36 already shipped on the feature branch (commit 36d3239), so amending it would create a divergent state for any developer who had pulled and applied V36 locally — V37 is the safer pattern. Mirrors `orders_aud` (V4-V11) precedent of including every persistent column in the audit table.

### WR-02: RefundEventPublisher.persist() is `protected` and self-invoked — @Transactional is a no-op

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java`
**Commit:** 7eca10e
**Applied fix:** Removed misleading `@Transactional` annotation from `persist`, removed the unused `Transactional` import, and flipped `persist` from `protected` to `private` so future callers cannot reach it across the proxy boundary. Updated Javadoc to make the "joins caller's transaction" contract explicit and document why `@Transactional` here would be a no-op.

### WR-03: RefundService.applyStripeStatusToRefund overwrites the local `refund` parameter

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java`
**Commit:** efc7aa2
**Applied fix:** Bound the `refundRepository.save(refund)` result to a distinct local `Refund persisted = ...` and switched all downstream reads to `persisted`. Self-documents the "always read post-save state" contract and survives a future refactor that passes a detached entity.

### WR-05: RefundEventPublisher persist's IllegalStateException kills the entire RefundService transaction

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java`, `core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java`
**Commit:** d070deb
**Applied fix:** Caught `JsonProcessingException` in `persist` and persist a `FAILED` placeholder row to `payment_event_outbox` with a `last_error` set + a stub JSON payload built via `String.format` (so this branch cannot itself JSON-fail). The method returns normally so the caller's `@Transactional` commits — including the `processed_stripe_events` dedup row — and Stripe does not retry into the same serialization failure forever. The flusher already handles `FAILED` rows by skipping them and incrementing the dead-letter counter. Updated `RefundEventPublisherTest`'s `persist_objectMapperThrows_*` test to assert the new contract (no exception, FAILED placeholder persisted, last_error set). Removed the now-unused `assertThrows` and `Mockito.never` imports.

> **Requires human verification:** the WR-05 fix changes a load-bearing contract — the placeholder payload format is now `{"error":"serialization_failed","refundId":"...","orderId":"..."}` rather than the actual `RefundEvent` JSON. Downstream consumers reading `payment_event_outbox` for forensic purposes will see this structurally-different payload only on the rare serialization-failure path. Please confirm operators' alerting on `payment.outbox.dead_letter` metric is in place — the placeholder row is stored as `FAILED` so it'll show up there.

### WR-06: orderRepository.findById in webhook handler bypasses TenantContext when tenant metadata is absent

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java`
**Commit:** efc7aa2 (folded into WR-03 commit since adjacent code)
**Applied fix:** Added a warn-log when `orderRepository.findById(refund.getOrderId())` returns empty in `applyStripeStatusToRefund`, so operators can distinguish a genuinely-deleted order from a tenant-context bug. The original concern that this code runs without TenantContext on the metadata-less path is closed by the BL-02 fix (metadata-less events are short-circuited before this code runs).

### WR-07: RefundDialog crypto.randomUUID fallback uses Math.random

**Files modified:** `frontend/components/dashboard/orders/RefundDialog.tsx`
**Commit:** d4368fd
**Applied fix:** Replaced the `Math.random` + `Date.now` fallback with a 3-tier cryptographically-secure waterfall: (1) `crypto.randomUUID` for modern HTTPS contexts, (2) `crypto.getRandomValues` for older browsers (RFC 4122 v4 hand-rolled from 16 secure bytes), (3) explicit `throw` if neither is available — secure random is mandatory for refund idempotency keys. The existing `RefundDialog` test stubs `globalThis.crypto.randomUUID` so the unit test contract is unchanged.

## Skipped Issues

### WR-04: PaymentEventOutboxFlusher iterates EVERY tenant on EVERY tick

**File:** `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java:71-93`
**Reason:** skipped: not a correctness bug (review explicitly notes "review charter excludes performance" and classifies this as warning-level only because it has correctness implications under load at 100+ tenants). The recommended fix (operator role bypassing RLS, or 60s tenant-list cache) is non-trivial and changes the security posture of the flusher; this needs phase-level discussion rather than a fix-bot decision. The current shape is "functionally correct, security takes priority over throughput" per the review.
**Original issue:** Iterates all tenants every 5s producing T queries / 5s even when no events are pending. As tenants grow this becomes a hot loop.

### WR-08: OrderMapper exposes RefundReason enum directly via JSON

**File:** `core-java/src/main/java/uk/jtoye/core/payment/dto/RefundDto.java:24`
**Reason:** skipped: forward-compat decision flagged as "not a current bug" by the review. Choosing between (a) collapsing the parallel enum and using `RefundCreateParams.Reason` directly or (b) wrapping with explicit `@JsonValue` mapping is a design choice with breaking-change implications for any in-flight mobile clients reading `RefundDto.reason`. Needs phase-level approval rather than a fix-bot decision.
**Original issue:** Wire format leaks Stripe enum names (`DUPLICATE`, `FRAUDULENT`, `REQUESTED_BY_CUSTOMER`) directly to clients despite the enum's Javadoc claiming "API surface is independent of the Stripe SDK enum".

### WR-09: RefundService.order.setPaymentStatus(REFUNDED) is set unconditionally even on partial refunds

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:201-206`
**Reason:** skipped: the fix requires state-machine changes (no transition on partial-refund OR a new `PARTIALLY_REFUNDED` status), frontend `OrderDetailPanel.canRefund` rewrite, AND revisiting the Phase 17 CONTEXT-LOCKED guidance "REFUND_REQUESTED → REFUNDED is the only transition for refunds". A fix-bot cannot unilaterally revisit a LOCKED CONTEXT decision. This is the largest skipped finding; it materially affects the partial-refund UX (vendors cannot issue a second partial refund because the "Issue refund" button disappears after the first one). Recommend logging this for Phase 18 / a CONTEXT correction in the next phase.
**Original issue:** After a partial refund, order.status moves to REFUNDED + paymentStatus REFUNDED, the UI gates `canRefund` on `paymentStatus === "CAPTURED"`, so the "Issue refund" button disappears. Vendors cannot issue a second partial refund despite `RefundRepository.sumLiveAmountByOrderId` supporting it.

### IN-01..IN-05: Info-level findings

**Reason:** skipped: out of scope for this iteration. `fix_scope: critical_warning` per orchestrator config means INFO findings (IN-01 dead Dialog code, IN-02 OrderService→RefundService coupling, IN-03 V36 ALTER TABLE concurrency window, IN-04 RefundDialog double-submit guard, IN-05 e2e spec test.skip) are deferred. Not counted in `findings_in_scope` total.

---

_Fixed: 2026-04-28T11:55:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
