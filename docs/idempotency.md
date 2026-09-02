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
| `POST /api/v1/public/shops/{slug}/orders` (guest checkout; `/public/...` alias) | **YES (full, QA 20260902 Cluster E)** | **HEADER** `Idempotency-Key` OR request-**BODY** `idempotencyKey` (body authoritative) → generic `idempotency_keys` store, endpoint `storefront.orders.create`, via `executeWithoutStoringResponse` (**no `response_body`**; `orders.idempotency_key` V24 unique idx kept as backstop) | Replays existing order and re-fetches a LIVE Stripe client secret; different body → 422; concurrent in-flight → 409 |
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

#### MCP write tools mandate the key (Phase 25 [AI-02], D-05)

Core keeps the header **optional** (`required=false`) — the dashboard still passes it
optionally and edge-go omits it (see *edge-go compatibility* below). But the `create_order`
and `create_customer` MCP write tools (#204) make `idempotencyKey` a **required tool input**
(Zod `z.string().min(1).max(64)`, matching the 1..64 store bound) and **always** forward it as
the `Idempotency-Key` header. The tools therefore have **no non-idempotent path** — an AI agent
cannot use them to mint a silent duplicate, and the tool description instructs the agent to
reuse the same key when retrying. This makes AC-1's "a replayed call returns the original
result, not a duplicate" a structural property of the tool, not a hope. No core change (D-06):
the 409 (in-flight) / 422 (same-key different-body) RFC 7807 responses core already emits flow
through the MCP `toToolError` sanitizer unchanged.

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

### Credential-bearing responses — `executeWithoutStoringResponse`

`IdempotencyService.execute` persists `serialize(result)` unconditionally. That is
unusable when the response carries a credential: the guest checkout's
`GuestOrderConfirmation.clientSecret` is a Stripe PaymentIntent client secret, a
browser-presentable payment credential that must not be archived at rest — and
would be stale anyway, because the storefront deliberately re-fetches a live one
on replay (WR-02). QA council 20260902-134741 (adjudication A3) therefore added
the `persistResponse=false` variant:

```java
IdempotencyOutcome<XDto> outcome = idempotencyService.executeWithoutStoringResponse(
        "storefront.orders.create", key, request,
        () -> place(request),          // the work, run exactly once per key
        () -> rederive(key));          // the replay: re-read the system of record
```

Same reservation, same request hash, same 409 / 422 semantics; `response_body`
stays NULL and `response_status` is stamped so a completed reservation is never
mistaken for an in-flight one. Two rules for adopters:

- **Namespace the endpoint** away from every body-storing adopter (the guest
  path uses `storefront.orders.create`, not `orders.create`): the table is
  shared, and a NULL body under a storing endpoint is undeserialisable on replay.
- **The replay supplier reads the system of record**, never the store — that is
  the whole point of the variant.

The completion `UPDATE` asserts it stamped exactly one row: under FORCE RLS a
lost tenant GUC would otherwise make it match zero rows silently, leaving the
reservation "in-flight" forever (every retry a 409) while the create had
committed. `GuestCheckoutIdempotencyIntegrationTest` and
`IdempotencyServiceUnstoredResponseIntegrationTest` (which includes the
falsifying control: the storing `execute` on the same table DOES write the body)
prove the contract against real Postgres.

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
- **Guest checkout** (`PublicStorefrontService`) — carve-out CLOSED by QA council
  20260902-134741 Cluster E (API-3 / API-4 / INT-15). It now reserves through the
  generic store's credential-safe variant (see *Credential-bearing responses*
  above); the request-**body** `idempotencyKey` is retained as the authoritative
  legacy source alongside the header, and `orders.idempotency_key` (V24) stays as
  the in-work lookup for keys placed before the store existed and as the unique
  backstop. The storefront (`checkout/page.tsx`) sends BOTH the header and the
  body field, with the key bound to the basket signature so a changed basket mints
  a new key instead of tripping the 422.

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
