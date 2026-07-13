# Idempotency Contract

Issue #204 / AI-2. This document is the AC-1 audit of existing idempotency
coverage plus the reference for the uniform, tenant-scoped `Idempotency-Key`
HTTP header contract introduced in this slice.

## AC-1 — Existing coverage audit

Before this slice the codebase had four independent idempotency islands and
several duplicate-blocking unique constraints, but no uniform contract. The
authenticated dashboard order-create path silently minted a duplicate order on
retry.

| Mutating endpoint | Idempotent? | Mechanism | On retry / duplicate |
|---|---|---|---|
| `POST /api/v1/orders` (dashboard) | **YES (new, this slice)** | `Idempotency-Key` header → generic `idempotency_keys` store | Replays the original order; zero duplicate rows |
| `POST /api/v1/customers` | **YES (new, this slice)** | `Idempotency-Key` header → generic `idempotency_keys` store | Replays the original customer; no duplicate |
| `POST /public/shops/{slug}/orders` (guest checkout) | YES (partial) | request-**BODY** `idempotencyKey` → `orders.idempotency_key` unique idx (V24); `PublicStorefrontService` | Replays existing order; re-fetches Stripe client secret |
| `POST /api/v1/orders/{orderId}/refund` | YES | **HEADER** `Idempotency-Key` → entity-local `Refund (tenant_id, idempotency_key)` dedup (`RefundService`) | Replays existing refund, 201 |
| `POST /sync/batch` (edge) | YES (by construction) | UPSERT by natural key (`SyncService`), shops+products only | Re-apply overwrites the same row |
| `POST /webhooks/stripe` | YES | `processed_stripe_events` (V35) `INSERT … ON CONFLICT (event_id) DO NOTHING` | 0 rows → skip side effects |
| `POST /api/v1/products`, `/shops`, `/onboarding/*` | NO (dup-blocked) | unique constraints → 409 | 409 Conflict, not a replay |

Precedents mirrored: **V47 `processed_order_events`** (composite-key,
ENABLE+FORCE RLS, `INSERT … ON CONFLICT DO NOTHING`) for the store shape, and
the refund header-dedup UX for the client contract.

## The generic contract

### Request

Clients supply an optional `Idempotency-Key` request header (max 64 chars) on a
mutating endpoint that advertises it. Reusing the same key for the same request
body replays the original response and never repeats the side effect.

### Store — `idempotency_keys` (V50)

Tenant-scoped dedup store, keyed `(tenant_id, endpoint, idempotency_key)`:

| Column | Meaning |
|---|---|
| `tenant_id` | owning tenant (RLS scope) |
| `endpoint` | the **logical** op id (e.g. `orders.create`), **not** the URL, so the key survives future API versioning |
| `idempotency_key` | the client-supplied header value (≤ 64) |
| `request_hash` | SHA-256 hex of the canonical request body |
| `response_status` | **NULL while the first request is in-flight**, then the stored replay status |
| `response_body` | the serialized response DTO (JSON) |
| `created_at` | reservation timestamp |

The table is `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` with the
standard tenant policy (`current_setting('app.current_tenant_id')`). There is
**no `_aud` mirror** — a dedup store is deliberately not Envers-audited (V47
posture).

### RLS posture — load-bearing, not ceremonial

`response_body` stores serialized DTOs. For orders that DTO includes customer
PII (`customerName` / `customerEmail` / `customerPhone`), so a cross-tenant read
of this table would be a PII disclosure. FORCE RLS is therefore load-bearing.
Because the store is touched via raw `JdbcTemplate` (which bypasses the JPA
`TenantSetLocalAspect`), `IdempotencyService.execute` **also** issues an explicit
defensive `SELECT set_config('app.current_tenant_id', ?, true)` at the top of its
transaction, mirroring `OrderStateChangeListener`. Enforcement is proven under
the NOSUPERUSER `rls_test_role` downgrade by
`IdempotencyKeysRlsPolicyIntegrationTest` (the Testcontainers bootstrap role is a
SUPERUSER and bypasses even FORCE RLS, so only a downgraded-role test is real
proof).

### Semantics — reserve-first

`IdempotencyService.execute` runs the house `INSERT … ON CONFLICT DO NOTHING`
inside the create's transaction:

- **1 row inserted (first request):** run the work, then stamp
  `response_status` + serialized `response_body` onto the reserved row. Both the
  reservation and the create commit/roll back together, so a failed create rolls
  the key back and a genuine retry later succeeds ("processed at least once", the
  V47 semantic). **Do NOT use `REQUIRES_NEW`** for the reservation — that would
  orphan the key and block retries.
- **0 rows inserted (replay):** re-read the row.
  - `response_status` present and `request_hash` matches → return the stored
    status + deserialized body (the replay).
  - `response_status` **NULL** (first request still in-flight) → **409**
    `IdempotencyConflictException`. A later retry replays once the first commits.
  - stored `request_hash` differs → **422** `IdempotencyPayloadMismatchException`
    (same key reused with a different body).
- **Blank or > 64-char key** → 400 (`IllegalArgumentException`).

## Adopting the contract on a NEW endpoint

1. Annotate the controller handler with `@Idempotent(endpoint = "x.create")`.
   `IdempotencyHeaderCustomizer` (a springdoc `OperationCustomizer`) advertises
   the `Idempotency-Key` header in OpenAPI on exactly the annotated operations.
2. Add an optional header param and route the create through the service:

   ```java
   @Idempotent(endpoint = "x.create")
   public ResponseEntity<XDto> create(@Valid @RequestBody XReq req,
           @Parameter(hidden = true)
           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
       if (key == null || key.isBlank()) { /* existing 201 path */ }
       IdempotencyOutcome<XDto> outcome = idempotencyService.execute(
               "x.create", key, req, XDto.class, () -> xService.create(req));
       return ResponseEntity.status(outcome.status()).body(outcome.value());
   }
   ```

   The `@Parameter(hidden = true)` keeps springdoc from double-listing the header
   (the customizer supplies the rich, documented parameter).

### Limitation — 201 is hardcoded (this slice)

`IdempotencyService.execute` stamps `response_status = 201` on the first request
because every current adopter is a create. A future non-201 adopter must first
parameterize the stored status (pass the intended status into `execute` and store
it) — the replay path already echoes whatever status is stored, so only the
first-request stamp needs generalizing.

## Deliberate carve-outs (unchanged this slice)

- **Refunds** (`RefundController` / `RefundService`) keep their entity-local
  header dedup on the `Refund (tenant_id, idempotency_key)` unique constraint.
  Migration to the generic mechanism is a documented follow-up, not part of this
  slice.
- **Guest checkout** (`PublicStorefrontService`) keeps its request-**body**
  `idempotencyKey` on `orders.idempotency_key` (V24). It intentionally stays as
  is because the storefront replay also re-issues the Stripe client secret for a
  payable DRAFT — behavior the generic store does not model.

## edge-go compatibility

The edge gateway's Core client posts `POST /api/v1/orders` directly
(`edge-go/internal/core/orders.go` `CreateOrder`) **without** an `Idempotency-Key`
header. This is compatible today because the header is optional — an absent
header preserves the pre-existing (non-idempotent) create path. Threading a key
through the edge is a natural future adopter.

## Ops note — TTL / pruning

The store grows one row per keyed mutation. Pruning is deferred to the existing
scheduled-cleanup housekeeping (`ScheduledCleanupService`), mirroring V47's
ops-note posture (Stripe uses ~24h; 24–72h is a reasonable window). Not a launch
blocker.
