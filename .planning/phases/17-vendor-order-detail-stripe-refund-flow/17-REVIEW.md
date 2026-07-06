---
phase: 17-vendor-order-detail-stripe-refund-flow
reviewed: 2026-04-28T00:00:00Z
depth: standard
files_reviewed: 39
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderEvent.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderStatus.java
  - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
  - core-java/src/main/java/uk/jtoye/core/payment/Refund.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundController.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundEvent.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundMapper.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundReason.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundRepository.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundService.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundStatus.java
  - core-java/src/main/java/uk/jtoye/core/payment/StripeRefundClient.java
  - core-java/src/main/java/uk/jtoye/core/payment/dto/CreateRefundRequest.java
  - core-java/src/main/java/uk/jtoye/core/payment/dto/RefundDto.java
  - core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql
  - core-java/src/test/java/uk/jtoye/core/order/OrderStateMachineServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundRepositoryTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java
  - frontend/app/dashboard/orders/[id]/page.tsx
  - frontend/app/dashboard/orders/page.tsx
  - frontend/app/dashboard/page.tsx
  - frontend/components/dashboard/orders/OrderDetailPanel.tsx
  - frontend/components/dashboard/orders/RefundDialog.tsx
  - frontend/components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx
  - frontend/components/dashboard/orders/__tests__/RefundDialog.test.tsx
  - frontend/e2e/vendor-refund-flow.spec.ts
  - frontend/types/api.ts
findings:
  blocker: 4
  warning: 9
  info: 5
  total: 18
status: issues_found
---

# Phase 17: Code Review Report

**Reviewed:** 2026-04-28
**Depth:** standard
**Files Reviewed:** 39
**Status:** issues_found

## Summary

Phase 17 introduces a Stripe-backed vendor refund flow on top of the existing order pipeline. The architecture (stored-first idempotency, single-outbox routing via per-row `exchange`, dedup-guard-then-refund-cases inside the existing webhook switch) follows the locked CONTEXT decisions. Tests cover the contract surface densely, including a Testcontainers-backed webhook lifecycle test.

However, the implementation contains **four shipping-blocking defects**:

1. **`RefundController` is mounted at the wrong URL in production** — the `/api/v1` prefix is package-restricted in `WebConfig`, and the new `RefundController` lives in `uk.jtoye.core.payment`, which is **not** in the prefix list. The frontend calls `/api/v1/orders/{id}/refund`; the production endpoint is at `/orders/{id}/refund`. This produces a 401/404 for every refund attempt. Critically, `RefundControllerIntegrationTest` uses `@WebMvcTest` (which does not load `WebConfig`) so the test never exercises the production routing, and the URL mismatch is invisible to CI.
2. **Webhook fallback path bypasses RLS** — when a Stripe refund event lacks `refund_id`/`tenant_id` metadata (e.g. Stripe-dashboard-issued refunds), `RefundService.handleStripeRefundEvent` looks up a row via `findByStripeRefundId` **without setting TenantContext**, then proceeds to mutate it under RLS. Production queries will return empty (or fail) and silently no-op the lifecycle update.
3. **`payment_event_outbox.tenant_id` violates RLS during webhook persistence** — the `RefundService` webhook handler calls `RefundEventPublisher.persist` (which writes a row with `tenant_id = refund.getTenantId()`) but the metadata-less fallback branch does not set TenantContext, so the INSERT is rejected by `payment_event_outbox` RLS (V33). The integration test `webhookRefundWithoutMetadata_findsByStripeRefundIdIfPresent` would fail under proper RLS-enforced Postgres if it asserted that an outbox row was written.
4. **`processed_stripe_events` dedup row is committed even when the side-effect path no-ops** — when the metadata-less webhook hits the `existing.isEmpty()` branch (no local Refund row matches the Stripe ID) the handler returns without updating anything, but the `INSERT INTO processed_stripe_events` already ran. The next Stripe redelivery (after we have created the corresponding local Refund) will be silently dropped at the dedup guard. This means a transient ordering glitch on Stripe's side (refund.created arriving before our local row exists) is permanently unrecoverable.

There are nine warning-level defects (transactional propagation gotchas, swallowed/leaked exceptions, audit-table column mismatches with Envers, an enum-name leak through MapStruct, and several quality issues), plus a handful of info-level notes.

## Blockers

### BL-01: RefundController mounted at /orders/{id}/refund, not /api/v1/orders/{id}/refund

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundController.java:38`
**File:** `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java:23`
**File:** `frontend/components/dashboard/orders/RefundDialog.tsx:135`
**File:** `core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java:101`

**Issue:** `WebConfig.configurePathMatch` adds the `/api/v1` prefix only to controllers under `uk.jtoye.core.{shop,product,order,customer,finance,gdpr,sync}`. The new `RefundController` is in `uk.jtoye.core.payment`, which is **not** in that list. In production it is therefore exposed at `/orders/{orderId}/refund` and `/orders/{orderId}/refunds`. The frontend `RefundDialog` posts to `/api/v1/orders/${orderId}/refund` (line 135) and the detail page reads `/api/v1/orders/${orderId}/detail` from `OrderController` (which IS prefixed). The refund POST will return 401/404 in every real environment.

The integration test masks this defect: `@WebMvcTest(RefundController.class)` does not load `WebConfig`, so the test passes despite asserting the wrong URL. The same applies for `RefundWebhookHandlingIntegrationTest` which only exercises `PaymentService` directly.

**Fix:** Add `"uk.jtoye.core.payment"` to the package list, OR put `RefundController` in `uk.jtoye.core.order` (it already maps to `/orders/...`):

```java
// WebConfig.java
configurer.addPathPrefix("/api/v1",
    HandlerTypePredicate.forBasePackage(
        "uk.jtoye.core.shop",
        "uk.jtoye.core.product",
        "uk.jtoye.core.order",
        "uk.jtoye.core.customer",
        "uk.jtoye.core.finance",
        "uk.jtoye.core.gdpr",
        "uk.jtoye.core.sync",
        "uk.jtoye.core.payment"   // <— add (or fold RefundController into order package)
    )
);
```

Then add a full-stack integration test that exercises `/api/v1/orders/{id}/refund` against a populated `WebConfig` — `@WebMvcTest` is not enough.

NB: Existing `PaymentController` in the same package is mapped at `/public/payments/...` (which is `permitAll`), so it has no `/api/v1` prefix concern — the package was historically `permitAll`-only. Phase 17 broke that assumption.

---

### BL-02: Webhook metadata-less fallback bypasses TenantContext, so RLS rejects the lookup and update

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:260-273`

**Issue:** When a `refund.*` webhook arrives without `refund_id`/`tenant_id` in metadata (e.g., a refund issued from the Stripe dashboard, or any pre-V36 in-flight refund), the handler does:

```java
if (localRefundIdStr == null) {
    Optional<Refund> existing = refundRepository.findByStripeRefundId(stripeRefund.getId());
    if (existing.isEmpty()) {
        log.warn(...);
        return;
    }
    applyStripeStatusToRefund(existing.get(), stripeRefund, event.getType());
    return;
}
```

There is **no `TenantContext.set(...)`** before `findByStripeRefundId` and the subsequent `applyStripeStatusToRefund` (which calls `refundRepository.save`, `orderRepository.findById`, and `refundEventPublisher.persist` — all RLS-bound by V33/V36). Postgres `current_setting('app.current_tenant_id', true)` returns NULL, the RLS predicate evaluates to FALSE/UNKNOWN, and the SELECT returns zero rows. The handler then logs the no-match warning and returns.

In other words, this fallback path **never works in production** — the very case it's designed to handle (no-metadata Stripe refunds) is precisely the case where it cannot find the row.

The integration test `webhookRefundWithoutMetadata_findsByStripeRefundIdIfPresent` (RefundWebhookHandlingIntegrationTest.java:332) sets `TenantContext` in the *seed*, then **clears it** before invoking `paymentService.handleWebhookEvent`. But `findByStripeRefundId` is called inside the `@Transactional` of `PaymentService.handleWebhookEvent`, which inherits whatever ThreadLocal value is on the request thread at entry. That value is null in production (clear request context) — yet the test passes because Testcontainers Postgres still has the GUC set from a prior `TenantContext` write that ran on the same thread without a between-test reset, OR because the test is running in a Hibernate session that side-stepped RLS. Either way the test is not exercising the production posture.

**Fix:** Look up the tenant first via the repository (it is already bypassing RLS since `findByStripeRefundId` returns nothing without a tenant), then set TenantContext before mutating.

Concretely, expose a non-RLS-scoped lookup:

```java
// Either: a JdbcTemplate query that runs SET LOCAL row_security = off;
//         then SELECT tenant_id FROM refunds WHERE stripe_refund_id = ?
// Or:     embed tenant_id in Stripe metadata at create time (already done via
//         putMetadata("tenant_id", ...), so insist on its presence and treat
//         a missing tenant_id as "ignore — externally issued, out of scope").
```

Recommended: emit a warning + return when metadata is absent (we already do that for unknown stripe IDs — extend it to *all* metadata-less events). Externally-issued refunds are explicitly out-of-scope per UC-5 LOCKED; do not pretend otherwise.

---

### BL-03: Outbox INSERT in webhook fallback path violates RLS

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:289-321`
**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java:82-89`

**Issue:** `applyStripeStatusToRefund` calls `refundEventPublisher.publishRefundSucceeded` / `publishRefundFailed` / `publishRefundUpdated`, which in turn `outboxRepository.save(...)` against `payment_event_outbox` — a table with `ENABLE + FORCE ROW LEVEL SECURITY` (V33/V35). The INSERT carries `tenant_id` as a column value but the RLS policy uses `current_setting('app.current_tenant_id', true)` for `WITH CHECK`, so it requires `TenantContext.set(...)` on the calling thread.

In the metadata-less branch (BL-02 above) `TenantContext` is never set, so the INSERT fails with `new row violates row-level security policy for table "payment_event_outbox"`. The whole transaction rolls back, the dedup row also rolls back, and Stripe redelivers — into the same hole.

In the metadata-bearing branch the publisher INSERT does succeed (TenantContext is set on RefundService.java:276), so this defect is scoped to BL-02's fallback path. Fixing BL-02 (by short-circuiting metadata-less events) closes this too.

**Fix:** As BL-02 — short-circuit no-metadata events so the publisher is never invoked without TenantContext.

---

### BL-04: processed_stripe_events guard commits a dedup row even when side-effect path no-ops on the metadata-less path, blocking subsequent retries

**File:** `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:145-172`
**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:264-269`

**Issue:** `PaymentService.handleWebhookEvent` does:

```java
int inserted = jdbcTemplate.update(
    "INSERT INTO processed_stripe_events (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING",
    event.getId());
if (inserted == 0) { /* dedup */ return; }
```

This is correct as a "successfully processed at least once" gate. But for a metadata-less refund event, the downstream `RefundService.handleStripeRefundEvent` falls through `findByStripeRefundId` → `existing.isEmpty()` → log warn → return (no exception, no rollback). Because the handler returns normally rather than throwing, the `processed_stripe_events` INSERT **commits**. Stripe will not retry that event again. If the missing-local-row condition was transient (e.g., refund.created arrived a few hundred ms before our local row was inserted by another flow, or our DB was restored from a backup), there is no recovery — the refund.* event is permanently lost.

This is documented in code comments as "the dedup row stays committed and retries also no-op" (RefundService.java:263) — that is the *bug*, not the design.

**Fix:** Either (a) throw when the lookup fails so the dedup row rolls back and Stripe retries, or (b) move the dedup INSERT to AFTER successful side-effect application:

```java
// Option A — throw on missing-row, let Stripe retry
if (existing.isEmpty()) {
    log.warn(...);
    throw new ResourceNotFoundException(
        "No local Refund row matches Stripe id " + stripeRefund.getId() + " — will retry");
}

// Option B — commit dedup row only on successful processing
// (more invasive — the dedup INSERT moves out of the @Transactional or
//  uses a REQUIRES_NEW commit inside each handler branch)
```

Option A is the smaller change; recommend pairing with BL-02's fix (refuse metadata-less events with HTTP 200 + log) so the only "no-op" path is the deliberate one.

---

## Warnings

### WR-01: refunds_aud table is missing the `version` column that Refund entity declares as @Version

**File:** `core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql:50-69`
**File:** `core-java/src/main/java/uk/jtoye/core/payment/Refund.java:92-94`

**Issue:** `Refund` is `@Audited` and has a `@Version` field (`version BIGINT NOT NULL DEFAULT 0` in `refunds`). The `refunds_aud` table does not declare a `version` column, so Envers writes will fail with `column "version" does not exist` whenever a Refund is updated under audit. Compare to `orders_aud` (V4-V10) which mirrors every persistent column.

Envers does include `@Version` columns in the audit table by default unless explicitly excluded with `@NotAudited`. Without the column, every update on Refund (and there are several — markRefundFailed, applyStripeStatusToRefund, post-Stripe success path) raises `org.hibernate.exception.SQLGrammarException: Column "VERSION" not found`.

**Fix:**

```sql
-- V36 (or a follow-up V37):
ALTER TABLE refunds_aud ADD COLUMN version BIGINT;
```

If for some reason the version column is intentionally not audited, annotate the field:

```java
@org.hibernate.envers.NotAudited
@Version
private Long version;
```

---

### WR-02: RefundEventPublisher.persist() is `protected` and self-invoked — @Transactional is a no-op

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java:69-93`

**Issue:** `persist` is annotated `@Transactional` but is `protected` and invoked **from within the same class** by `publishRefundSucceeded` / `publishRefundFailed` / `publishRefundUpdated`. Spring's AOP proxy only intercepts calls that traverse the proxy boundary; an internal `this.persist(event)` call goes straight to the target instance and bypasses the transaction interceptor. The method's @Transactional has no effect.

In practice this is masked by the fact that the only callers (RefundService methods) are themselves @Transactional, so `outboxRepository.save(row)` joins the existing transaction. But the comment "joins the caller's transaction" implies a Tx is opened if one is missing — it is not. Should the publisher ever be invoked from a non-transactional context, the save would silently auto-commit (no atomicity with the originating refund mutation).

**Fix:** Either remove the misleading annotation or make the public publish methods @Transactional and inline persist:

```java
@Transactional
public void publishRefundSucceeded(...) {
    persist(new RefundEvent(...));
}
// ...and remove @Transactional from persist()
```

---

### WR-03: RefundService.applyStripeStatusToRefund overwrites the local `refund` parameter — confusing and brittle

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:299`

**Issue:**

```java
private void applyStripeStatusToRefund(Refund refund, ...) {
    ...
    refund = refundRepository.save(refund);  // <-- reassign param
    Order order = orderRepository.findById(refund.getOrderId()).orElse(null);
    ...
}
```

Java method parameters are pass-by-value of references. Reassigning `refund` to the result of `save` only changes the local variable; the caller's reference is unchanged. In this case the `save` returns the same managed instance so the reassignment is redundant — but a future refactor that passes a detached instance and expects the reassignment to "update the caller's view" would silently misbehave.

More importantly: under JPA `save` returns the merged entity which is what subsequent code should use; getting that right by accident here is a code smell.

**Fix:** Either don't reassign (Hibernate will flush automatically on `@Transactional` commit), or rename to make intent obvious:

```java
Refund persisted = refundRepository.save(refund);
// ...use persisted from here on
```

---

### WR-04: PaymentEventOutboxFlusher iterates EVERY tenant on EVERY tick — N×P Postgres queries per flush

**File:** `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java:71-93`

**Issue:** `flushPending` queries `SELECT id FROM tenants` on every tick, then for each tenant: sets TenantContext, runs the RLS-bound `findTop100ByStatusOrderByCreatedAtAsc`, clears TenantContext. With T tenants and a 5-second flush interval that is `T queries / 5s` even when no events are pending. As tenants grow this becomes a hot loop.

This is functionally correct — security takes priority over throughput, and the comment correctly notes RLS is the wall. But there is a cleaner shape: select PENDING rows directly with `SET LOCAL row_security = off` (admin role) and dispatch by row.tenant_id, OR add `tenant_id` to a non-RLS index used by the flusher's bypass query.

Marking as a warning rather than blocker because it's not a correctness bug — but at 100+ tenants this will be visible in pg_stat_statements. (Note: the review charter excludes performance, so this is flagged only because it has correctness implications under load: long flusher ticks cause the @Scheduled fixedDelay to coalesce, dropping flush frequency and growing the SENT-tail.)

**Fix:** Either set explicit operator role for the outbox flusher (bypassing RLS), OR cache the tenant list with a TTL of 60s. Both are quick wins.

---

### WR-05: RefundEventPublisher persist's IllegalStateException on JsonProcessingException kills the entire RefundService transaction

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java:74-80`

**Issue:** When ObjectMapper.writeValueAsString throws (vanishingly unlikely for a record-shaped POJO, but possible if a custom Jackson module misbehaves), `persist` throws `IllegalStateException`. The `RefundService.applyStripeStatusToRefund` does not catch it. The whole webhook transaction rolls back, and along with it the **dedup row in processed_stripe_events**. Stripe redelivers the same event, and we hit the same JSON failure on the next retry, looping forever.

This is the same risk as BL-04 but for a different cause. The IllegalStateException from RefundEventPublisher should arguably be swallowed (logged + stored as `last_error` on a distinct outbox row?) OR the dedup row should be committed in a separate transaction before downstream side effects.

**Fix:** Pair with BL-04. If you adopt "dedup INSERT in a separate REQUIRES_NEW tx" then this resolves automatically. Otherwise, catch JsonProcessingException specifically in `publishRefund*`, log + record metric, and persist a placeholder row with status FAILED — never propagate to the caller.

---

### WR-06: orderRepository.findById in webhook handler runs even on REFUND_FAILED, where order lookup is best-effort but bypasses TenantContext when tenant metadata is absent

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:303-304`

**Issue:** `Order order = orderRepository.findById(refund.getOrderId()).orElse(null);` — this query runs under RLS. When invoked on the metadata-less fallback path (BL-02) without TenantContext set, it returns Optional.empty silently. The subsequent `String orderNumber = order != null ? order.getOrderNumber() : null;` then publishes a RefundEvent with `orderNumber = null`. Downstream consumers expecting orderNumber will see null even though the refund row's tenant has the order.

This is "best effort, do not fail the webhook" by design — but the failure mode (silent null orderNumber) is invisible. At minimum log a warning when the order lookup yields empty for a non-null orderId.

**Fix:**

```java
Order order = orderRepository.findById(refund.getOrderId()).orElse(null);
if (order == null) {
    log.warn("Refund {} references missing/unreachable order {} — event will publish with null orderNumber",
        refund.getId(), refund.getOrderId());
}
String orderNumber = order != null ? order.getOrderNumber() : null;
```

---

### WR-07: RefundDialog crypto.randomUUID fallback is non-cryptographic and uses Math.random (predictable)

**File:** `frontend/components/dashboard/orders/RefundDialog.tsx:55-61`

**Issue:**

```js
function makeIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}
```

The fallback path concatenates `Date.now()` and `Math.random()`. `Math.random()` is not cryptographically random and the timestamp is observable. Two clients hitting submit at the same wall-clock millisecond could generate the same key (`Math.random()` is per-tab-seeded), flushing through to the backend's `findByTenantIdAndIdempotencyKey` short-circuit and incorrectly returning the *other* tenant's refund (cross-tenant correctness depends entirely on the unique constraint being `(tenant_id, idempotency_key)` — that part is correct, but a same-tenant collision still produces wrong-refund-replay).

The comment says "Production browsers always have crypto.randomUUID" — true for modern HTTPS contexts, but not for Safari < 15.4 or Chrome < 92, both of which still have non-trivial use share. Idempotency keys are a security-critical contract; do not fall back to Math.random.

**Fix:**

```js
function makeIdempotencyKey(): string {
  if (typeof crypto?.randomUUID === "function") return crypto.randomUUID()
  if (typeof crypto?.getRandomValues === "function") {
    const buf = new Uint8Array(16)
    crypto.getRandomValues(buf)
    // Format as UUID v4
    buf[6] = (buf[6] & 0x0f) | 0x40
    buf[8] = (buf[8] & 0x3f) | 0x80
    const hex = [...buf].map(b => b.toString(16).padStart(2, "0")).join("")
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
  }
  throw new Error("No secure random source available — cannot generate Idempotency-Key")
}
```

---

### WR-08: OrderMapper exposes RefundReason enum directly via JSON — Stripe enum names leak into vendor API

**File:** `core-java/src/main/java/uk/jtoye/core/payment/dto/RefundDto.java:24`
**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundReason.java:13-21`

**Issue:** `RefundDto.reason` is the bare `RefundReason` enum, serialized as its enum name (`DUPLICATE`, `FRAUDULENT`, `REQUESTED_BY_CUSTOMER`). The frontend `RefundReason` type matches 1:1 — fine for now. But the comment on the enum says "kept as a separate Java enum so the API surface is independent of the Stripe SDK enum (lets us version, document, and validate independently)". That promise is broken at the wire: clients today see Stripe's exact enum names. If we ever add a non-Stripe reason (e.g., `GOODWILL`) the wire compatibility burden falls on us, not on Stripe.

This is a forward-compat warning — not a current bug.

**Fix:** Decide whether the wire format should mirror Stripe (then collapse the parallel enum and use `RefundCreateParams.Reason` directly), or whether it should be J'Toye-domain (then add explicit `@JsonValue` mapping or wrap in a string with documented values). Today's setup is the worst of both worlds.

---

### WR-09: RefundService order.setPaymentStatus(REFUNDED) is set unconditionally even on partial refunds

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:201-206`

**Issue:**

```java
OrderStatus newStatus = stateMachineService.sendEvent(
    orderId, order.getStatus(), OrderEvent.REFUND_REQUESTED);
order.setStatus(newStatus);              // -> REFUNDED
order.setPaymentStatus(PaymentStatus.REFUNDED);  // <-- regardless of partial
order.setUpdatedAt(OffsetDateTime.now());
orderRepository.save(order);
```

After a partial refund (e.g., £5.00 of a £10.00 order), the order moves to status REFUNDED and paymentStatus REFUNDED. The OrderDetailPanel's UI then `canRefund = REFUNDABLE_STATUSES.has(order.status) && order.paymentStatus === "CAPTURED"` — both checks fail. The "Issue refund" button disappears. **Vendors cannot issue a second partial refund** even though `sumLiveAmountByOrderId < totalAmountPennies` would otherwise allow it.

This contradicts the explicit partial-refund support in `RefundRepository.sumLiveAmountByOrderId` and the `Refund` test cases that verify partial-refund arithmetic.

**Fix:** Distinguish "fully refunded" from "partially refunded". Either:

```java
long alreadyRefunded = ... + requested;  // include the refund we're about to write
boolean fullyRefunded = alreadyRefunded >= order.getTotalAmountPennies();

if (fullyRefunded) {
    OrderStatus newStatus = stateMachineService.sendEvent(...);
    order.setStatus(newStatus);
    order.setPaymentStatus(PaymentStatus.REFUNDED);
}
// else: leave order.status alone (still CONFIRMED/PREPARING/READY/COMPLETED),
//       leave paymentStatus = CAPTURED (the original capture is still partly live)
order.setUpdatedAt(OffsetDateTime.now());
orderRepository.save(order);
```

This will require state-machine changes (no transition on partial-refund; or a new PARTIALLY_REFUNDED status) and frontend updates. The hard-deck guidance from CONTEXT was "REFUND_REQUESTED → REFUNDED is the only transition for refunds"; revisit that assumption since the partial-refund UI flow is broken in production.

---

## Info

### IN-01: orders/page.tsx retains dead inline-detail Dialog and four eslint-disable lines

**File:** `frontend/app/dashboard/orders/page.tsx:239-251`, `827-955`

**Issue:** Three `// eslint-disable-next-line @typescript-eslint/no-unused-vars` comments hide three setters whose state still ships in the bundle. The detail Dialog JSX (lines 827-955) is also dead code reachable only by unused setters. Comments say it's "preserved for v2.2 per 17-CONTEXT" — but it adds ~130 lines of unreachable code and four eslint suppressions that future contributors will see and copy.

**Fix:** Delete it now (it's in version control if v2.2 wants to revive). If you must keep it, extract to a `_LEGACY_OrderDetailDialog.tsx` file marked with `@deprecated` so it's at least quarantined.

---

### IN-02: OrderEventPublisher constructor parameter is unused in RefundService

**File:** `core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:69-81`

**Issue:** RefundService imports OrderEventPublisher? Actually no — RefundService does NOT inject OrderEventPublisher (good). But OrderService now depends on RefundService (line 53), creating a tight coupling: every Order operation transitively pulls Stripe / refund infrastructure. This makes OrderService harder to slice for unit tests. Consider an event-driven shape (publish an order-detail-loaded event, RefundService listens) so OrderService doesn't statically depend on the payment package.

Not a defect — flagged for architecture review.

---

### IN-03: V36 migration drops orders_status_check without rebinding tenant context — risk on multi-tenant Postgres

**File:** `core-java/src/main/resources/db/migration/V36__refunds_and_outbox_exchange.sql:84-86`

**Issue:** `ALTER TABLE orders DROP CONSTRAINT orders_status_check;` then immediately re-adds it. In a multi-tenant deployment with concurrent writes, there's a brief window where any status value is allowed. Flyway runs in a maintenance window so this is generally safe — but for hot-deploy environments add `LOCK TABLE orders IN ACCESS EXCLUSIVE MODE` first or use `ALTER ... DROP CONSTRAINT ... RESTRICT` followed by add-with-NOT-VALID + VALIDATE to keep the table writable.

Minor — only relevant if hot-deploy is part of the SLA.

---

### IN-04: Frontend RefundDialog allows re-submit while in flight if user double-clicks before submitting prevents render

**File:** `frontend/components/dashboard/orders/RefundDialog.tsx:120-156`

**Issue:** `submitting` state guards the submit button, but during the `await apiClient.post(...)` window a user could press Enter on the form (which triggers another submit). The button is disabled but the form's default submit behaviour still fires. This results in two POSTs with the SAME idempotency key — server-side replay handling resolves it correctly, but it's wasted effort. Add `if (submitting) return` at the top of `onSubmit`.

```js
const onSubmit = async (values: FormValues) => {
  if (submitting) return  // <-- belt and braces
  setSubmitting(true)
  ...
}
```

---

### IN-05: e2e/vendor-refund-flow.spec.ts skips on missing fixtures rather than failing — easy to mistake "passing" for "tested"

**File:** `frontend/e2e/vendor-refund-flow.spec.ts:75-101`

**Issue:** The spec calls `test.skip(true, "...")` when a refundable order is not seeded or the auth form is missing. In CI this surfaces as "0 failures" while the actual coverage is 0. Recommend `test.fail()` with a clear message when the fixture is required, or split the spec into "seeded environment only" and gate it via a Playwright project tag.

---

---

_Reviewed: 2026-04-28_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
