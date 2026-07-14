# Phase 17: Vendor Order Detail + Stripe Refund Flow - Context

**Gathered:** 2026-04-28
**Status:** Ready for planning
**Source:** `17-RESEARCH.md` (2026-04-18, HIGH confidence) + post-Phase-16.1 corrections (this file).

<domain>
## Phase Boundary

Three deliverables (VOPS-01..03):

1. **VOPS-01** — `/dashboard/orders/[id]` detail page (header, customer, line items, payment, refund history, action panel).
2. **VOPS-02** — `POST /api/v1/orders/{id}/refund` → Stripe `Refund.create` → persist `Refund` entity → publish `order.refunded` via RabbitMQ. Webhook updates `Refund.status` on `refund.created` / `refund.updated` / `refund.failed`.
3. **VOPS-03** — `REFUND_REQUESTED` state-machine transition `CONFIRMED|PREPARING|READY|COMPLETED → REFUNDED`; idempotent no-op on already-REFUNDED orders.

Out of scope (deferred to v2.3+):
- Vendor RBAC for refund action (UC-5 below — codebase has zero RBAC today; adding it is a separate cross-cutting workstream).
- "Fully refunded" vs "partially refunded" UI distinction beyond a single `REFUNDED` order status (any refund flips the flag; per-refund detail lives on `Refund` rows).
- Refund-reason enum constraint (free-text + suggested values is acceptable for v2.2).

</domain>

<decisions>
## Implementation Decisions

### 5 User-confirmation locks (from RESEARCH §13/§USER CONFIRMATION REQUIRED)

All 5 locked to the research's recommended defaults — each is the only safe option per the technical rationale; alternatives are flagged in research as either unsafe (UC-1, UC-3) or scope creep (UC-5).

- **UC-1 LOCKED — Idempotency key strategy: stored-first server-generated UUID.**
  Persist a `Refund` row with a server-generated `idempotency_key VARCHAR(64)` and `status='CREATING'` BEFORE the Stripe call. Pass that key as Stripe's `Idempotency-Key` header. On retry (client OR Stripe-network failure), the same row is re-used and the same key is sent — Stripe replies with the original response within its 24h window. Two DB writes per refund (CREATING insert + status update post-Stripe), but eliminates double-refund risk. Optional client-supplied `X-Idempotency-Key` header for endpoint-level dedup (rejects duplicate POSTs with 409).

- **UC-2 LOCKED — Outbox routing: reuse `payment_event_outbox` + add `exchange VARCHAR(128) NOT NULL DEFAULT 'payment.events'` column.**
  V36 adds the column; `PaymentEventOutboxFlusher` reads it and routes per-row. `order.refunded` rows write `exchange='order.events'`. Single outbox table > separate `order_event_outbox` (less code, less duplication).

- **UC-3 LOCKED — `RefundStatus` enum: lowercase to match Stripe wire format.**
  Values: `succeeded`, `failed`, `pending`, `requires_action`, `canceled` + uppercase sentinel `CREATING` for pre-Stripe state. No JPA `@Converter` needed (enum names = wire strings). Style deviation from Java PascalCase is justified by Stripe-wrapper semantics — the enum IS the API surface.

- **UC-4 LOCKED — Webhook subscription: `refund.created` / `refund.updated` / `refund.failed` exclusively; no-op `charge.refunded` case.**
  Per Stripe's 2024-10-28 changelog recommendation (refund-specific events superseded `charge.refunded`). The `charge.refunded` case stays in the switch as a documented `log.debug("ignored — see refund.* events")` to prevent "unhandled event" warnings without double-processing.

- **UC-5 LOCKED — Vendor RBAC: defer to v2.3.**
  Codebase has zero RBAC today (verified by grep for `hasRole`/`hasAuthority`/`@PreAuthorize` = 0 matches). Adding role checks for refund alone would create a one-off cross-cutting precedent. JWT-authenticated + tenant-scoped RLS is the existing security model; refund action inherits it. This is a known scope deferral — flag in PR description.

### Phase 16.1 corrections (post-research)

Phase 16.1 (merged 2026-04-28 in PR #56) shipped between this research being written and Phase 17 starting. Three corrections to the research:

- **CORRECTION-1 LOCKED — Migration slot is V36, NOT V35.** Phase 16.1 took V35 (`V35__rls_idempotency_force_rls.sql`). All research references to "V35" must read as "V36" in plans. The new file name is `V36__refunds_and_outbox_exchange.sql`.

- **CORRECTION-2 LOCKED — Reuse the Phase 16.1 idempotency guard for refund webhooks.** `PaymentService.handleWebhookEvent` now starts with a TOCTOU-safe `INSERT INTO processed_stripe_events (event_id) VALUES (?) ON CONFLICT DO NOTHING` (Phase 16.1 AUDIT-W0-03). The new `refund.*` cases sit AFTER that dedup, inside the existing switch. No new dedup table, no new dedup pattern. The existing `StripeWebhookIdempotencyIntegrationTest` already covers the dedup itself; Phase 17 adds `RefundWebhookHandlingIntegrationTest` covering the per-event handler logic.

- **CORRECTION-3 LOCKED — All new RLS policies use canonical `app.current_tenant_id` GUC.** Phase 16.1 AUDIT-W0-04 standardised this. Any policy in V36 (e.g., `refunds_tenant_policy`) MUST use `current_setting('app.current_tenant_id', true)::UUID` — never the legacy `app.tenant_id`. `RlsContractTest` (Phase 16.1) will fail the build if `refunds` lacks ENABLE+FORCE RLS or any policy references the legacy GUC.

### Refund entity shape (V36)

- **LOCKED**: `refunds(id UUID PK, tenant_id UUID, order_id UUID FK→orders, payment_intent_id VARCHAR(255), stripe_refund_id VARCHAR(255) NULLABLE, idempotency_key VARCHAR(64) NOT NULL, amount_pennies BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, reason VARCHAR(255), reason_note TEXT, status VARCHAR(32) NOT NULL, requested_by UUID, requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), version BIGINT NOT NULL DEFAULT 0)`. Unique on `(tenant_id, idempotency_key)` mirroring V24 orders.
- **LOCKED**: ENABLE + FORCE ROW LEVEL SECURITY. Single policy `refunds_tenant_policy FOR ALL USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID) WITH CHECK (...)`.
- **LOCKED**: Envers audit table `refunds_aud` follows V11 pattern (audit RLS already standardised).

### State-machine extension (VOPS-03)

- **LOCKED**: New `OrderEvent.REFUND_REQUESTED`. New `OrderStatus.REFUNDED`.
- **LOCKED**: V6 `orders_status_check` constraint MUST be DROP+CREATE in V36 to add `REFUNDED` to the IN-list (research §2.3 flagged this as a landmine).
- **LOCKED**: Transitions `CONFIRMED → REFUNDED`, `PREPARING → REFUNDED`, `READY → REFUNDED`, `COMPLETED → REFUNDED` via `REFUND_REQUESTED` event. `REFUNDED` is an `.end()` state.
- **LOCKED**: Idempotent no-op when target order already `REFUNDED` — implement as a guard in `RefundService.refund()` BEFORE invoking the state machine (return existing `Refund` row, do not throw, do not call Stripe again). The state machine itself remains fail-loud on illegal transitions.

### Frontend (VOPS-01)

- **LOCKED**: `/dashboard/orders/[id]/page.tsx` (server component, mirrors `dashboard/layout.tsx` auth guard).
- **LOCKED**: Extract the existing detail-Dialog content from `frontend/app/dashboard/orders/page.tsx` lines ~796-923 into a reusable `OrderDetailPanel` component used both inline (route page) and modal (list page → eventually deprecated, but keep modal for v2.2).
- **LOCKED**: Refund action panel uses Radix Dialog primitives already proven in the orders list page. NOT a new design system. Per `feedback_design_direction.md` memory: this is feature work within the existing orange/emerald/slate palette — NOT a redesign. No serif type, no editorial layout. Add nothing speculative.
- **LOCKED**: Optimistic update on refund submit — disable button + show "Refunding…" state. Real refund status arrives via SSE from `OrderSseService` (already broadcasts state changes per Phase 16.1).
- **LOCKED**: `OrderStatus` TypeScript union extends with `"REFUNDED"`. `OrderDetail` interface adds `payment` + `refunds[]` fields.

### Webhook handler (VOPS-02)

- **LOCKED**: New cases in `PaymentService.handleWebhookEvent` switch (AFTER Phase 16.1's `processed_stripe_events` dedup):
  - `case "refund.created" -> handleRefundEvent(event, RefundEventType.CREATED)`
  - `case "refund.updated" -> handleRefundEvent(event, RefundEventType.UPDATED)`
  - `case "refund.failed" -> handleRefundEvent(event, RefundEventType.FAILED)`
  - `case "charge.refunded" -> log.debug("ignored — see refund.* per Stripe 2024-10-28 changelog")` (no-op, prevents unhandled-event warnings)
- **LOCKED**: `handleRefundEvent` extracts `refund.metadata.refund_id` (our internal `Refund.id`), updates `Refund.status` + `stripe_refund_id`, publishes `order.refunded` to outbox. Wraps in `@Transactional`.
- **LOCKED**: `RefundWebhookHandlingIntegrationTest` (Testcontainers) covers each event type + dedup behaviour (re-delivery short-circuits at the existing Phase 16.1 guard).

### Plan split

- **LOCKED**: 4 plans (research recommended 3; +1 for the V36 outbox-exchange retrofit which is a small but distinct concern):
  - **17-01** — V36 migration + Refund entity + RefundRepository + RefundService (with stored-first idempotency) + state-machine extension (REFUND_REQUESTED, REFUNDED, V6 CHECK constraint rewrite). Unit tests. Wave 1.
  - **17-02** — `payment_event_outbox.exchange` column + `PaymentEventOutboxFlusher` per-row routing + `RefundEventPublisher` (mirrors `PaymentEventPublisher` pattern). Wave 1 (independent of 17-01 — touches outbox, not Refund).
  - **17-03** — Webhook handler extension (refund.* cases) + `RefundController` (`POST /api/v1/orders/{id}/refund`) + `RefundWebhookHandlingIntegrationTest` (Testcontainers). Wave 2 (depends on 17-01 + 17-02).
  - **17-04** — Frontend `/dashboard/orders/[id]/page.tsx` + `OrderDetailPanel` extraction + refund Dialog + `OrderStatus` type extension + Playwright E2E (vendor → list → click row → click refund → confirm → Stripe test-mode success → UI shows REFUNDED). Wave 3 (depends on 17-03 backend).

### Claude's Discretion

- Exact Refund column ordering / index strategy beyond the unique constraint.
- DTO/mapper structure (follow `OrderMapper` MapStruct pattern).
- Whether to add a `Refund.cancelable` predicate (probably not — Stripe's lifecycle is the source of truth).
- Frontend component file naming under `frontend/components/dashboard/orders/`.
- Playwright test fixture data shape.

</decisions>

<canonical_refs>
## Canonical References

### Phase 17 research (the "how")
- `.planning/phases/17-vendor-order-detail-stripe-refund-flow/17-RESEARCH.md` — full design, file:line evidence for every claim. Read sections 3, 4, 6, 7, 8, 10 in detail before planning.

### Phase 16.1 dependencies (must reuse)
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:142-148` — TOCTOU-safe `INSERT ... ON CONFLICT` dedup. Refund webhook cases sit AFTER this guard, inside the existing switch.
- `core-java/src/main/resources/db/migration/V35__rls_idempotency_force_rls.sql` — defines `processed_stripe_events`. Refund webhooks reuse this table, no new dedup table.
- `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` — schema-walk drift guard. Will fail the build if `refunds` lacks ENABLE+FORCE RLS.
- `.planning/phases/16.1-pre-prod-hardening/16.1-CONTEXT.md` — for the canonical `app.current_tenant_id` GUC pattern.

### Existing patterns to copy
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java` — outbox-backed publish; model for `RefundEventPublisher`.
- `core-java/src/main/resources/db/migration/V31__payment_event_outbox.sql` — outbox schema template.
- `core-java/src/main/resources/db/migration/V24__order_idempotency_key.sql` — `(tenant_id, idempotency_key)` unique pattern for the new `refunds.idempotency_key`.
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java:38-113` — transition DSL extension point.
- `core-java/src/main/java/uk/jtoye/core/order/OrderService.java:309-363` — canonical `transitionOrder(orderId, event)` invocation pattern.
- `frontend/app/dashboard/orders/page.tsx:796-923` — existing detail Dialog content to extract.
- `frontend/lib/api-client.ts:20-121` — axios pattern (Bearer + X-Tenant-Id + retry + 401 refresh).

### Project standards
- `CLAUDE.md` — JDK 21, Gradle 8.10, Spring Boot 3.4.2, multi-tenant RLS, Testcontainers for integration tests.
- `feedback_design_direction.md` (memory) — no UI redesigns, no editorial type. Phase 17 frontend uses existing design system.

</canonical_refs>

<specifics>
## Specific Ideas

- The webhook signature verification in `PaymentService.handleWebhookEvent` happens BEFORE the dedup INSERT. Refund event cases sit inside the same switch — no new entry point to secure.
- `Refund.idempotency_key` MUST be unique per `(tenant_id, idempotency_key)`. Server-generated key uses `UUID.randomUUID().toString()` (32 hex chars without hyphens) so it fits comfortably in `VARCHAR(64)`.
- Refund metadata for Stripe: `metadata = Map.of("refund_id", refund.id().toString(), "tenant_id", refund.tenantId().toString(), "order_id", refund.orderId().toString())`. The webhook handler reads `refund.id` from metadata to look up the local row (NOT by `stripe_refund_id` — that's NULL pre-Stripe).
- Optimistic-lock annotation `@Version Long version` is required on `Refund` to handle concurrent webhook updates from Stripe (e.g., `refund.created` then immediate `refund.updated`).
- Frontend SSE: the existing `OrderSseService` (Phase 16.1 hardened) broadcasts on every state change. The refund flow's `transitionOrder(REFUND_REQUESTED)` triggers a broadcast — frontend re-fetches detail when SSE event arrives. Don't poll.

</specifics>

<deferred>
## Deferred Ideas

- Multi-step refund UX (partial → confirm → review → submit). v2.2 ships single-step modal.
- Per-line-item refund (refund a specific item rather than amount). Stripe supports this via `refund_application_fee` semantics — not in scope.
- Refund email receipts (Stripe sends them automatically when `refund.email_receipt: true`; we don't override). Custom-templated email would need the SMTP layer + template engine — separate phase.
- Refund analytics dashboard (refund rate, top reasons, avg time-to-refund). v2.3 reporting workstream.
- Vendor RBAC (UC-5 deferred).
- `Refund.cancelable` admin action — Stripe allows refund cancellation only in `pending` state and only via Connect. Not in scope.

</deferred>

---

*Phase: 17-vendor-order-detail-stripe-refund-flow*
*Context gathered: 2026-04-28 — derived from 17-RESEARCH.md (LOCKED defaults on all 5 UC items per autonomous-mode user instruction) + Phase 16.1 corrections (V35 → V36, reuse processed_stripe_events, use canonical GUC).*
