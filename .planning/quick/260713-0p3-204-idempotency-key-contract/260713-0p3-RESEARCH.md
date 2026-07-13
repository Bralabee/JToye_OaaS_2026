# Quick Task 260713-0p3: Uniform Idempotency-Key Contract (#204 [AI-2]) — Research

**Researched:** 2026-07-13
**Domain:** Spring Boot 3.5 multi-tenant idempotency; PostgreSQL RLS; SpringDoc OpenAPI
**Confidence:** HIGH (all audit claims file:line-verified against the live tree)

## Summary

This codebase already has **four independent idempotency islands** and **five duplicate-blocking
unique constraints**, but no uniform contract. The `Order.idempotencyKey` plumbing the issue points
at (`Order.java:62`, `OrderRepository.java:77`) is honored by **exactly one** write path — the public
storefront guest checkout — and it reads the key from the **request body**, not a header. The
authenticated dashboard order-create path (`OrderService.createOrder`, `OrderService.java:83-167`)
**silently ignores idempotency entirely**: a retried `POST /api/v1/orders` mints a duplicate order row.

The closest precedent to the desired contract already ships: **refunds** use a real
`Idempotency-Key` **header** (`RefundController.java:80`) with tenant-scoped stored-first dedup
(`RefundService.java:100-109`) and 201-replay. The issue's stated mirror target, **V47
`processed_order_events`** (composite-key, ENABLE+FORCE RLS, `INSERT … ON CONFLICT DO NOTHING`),
is the canonical shape to generalize.

**Primary recommendation:** Add a **generic `idempotency_keys` store (V50, ENABLE+FORCE RLS,
mirroring V47)** keyed `(tenant_id, endpoint, idempotency_key)` that captures `response_status` +
serialized `response_body` + `request_hash`. Gate it with an **`@Idempotent` marker annotation**
read by a `HandlerInterceptor` (adoption stays one-annotation-cheap), and advertise the header via a
**SpringDoc `OperationCustomizer`** keyed off that annotation. Make the concurrent race safe with
**reserve-first `INSERT … ON CONFLICT DO NOTHING`** (the house pattern, already used twice), returning
the stored response on 0-rows-inserted and **409 while the first request is still in-flight**. Slice 1
= orders-create + customers-create per AC.

## User Constraints (from issue #204)

No CONTEXT.md exists for this quick task. Constraints are the issue's fix direction + acceptance criteria:

### Locked (from the issue)
- Tenant-scoped dedup store keyed `(tenant_id, endpoint, key)` **mirroring the V47 semantic-key precedent**.
- Replay returns the **original response (200/201 with original body)**, never a duplicate row.
- Advertise the header in **OpenAPI**; regen snapshot via `./gradlew :core-java:updateOpenApiSnapshot`.
- **AC-1:** documented audit of existing coverage (this document is that audit).
- **AC-2:** same-key replay of order creation returns the original result with **zero duplicate rows**,
  proven by a Testcontainers test **including a CONCURRENT replay race**.
- **AC-3:** at least **customers + orders** create paths covered.
- **AC-4:** OpenAPI snapshot updated **same PR**; breaking-change gate green.

### Claude's Discretion
- Storage columns beyond the required key (request_hash, TTL strategy), opt-in mechanism
  (annotation vs allowlist), same-key-different-payload status code, concurrent-race primitive
  (ON CONFLICT re-read vs advisory lock).

## THE AUDIT (AC-1) — Existing Idempotency Coverage

### Per-endpoint idempotency truth table

| Mutating endpoint | Idempotent today? | Mechanism (evidence) | On retry / duplicate |
|---|---|---|---|
| `POST /api/v1/orders` (dashboard/authenticated) | **NO** | `OrderService.createOrder` never touches `idempotencyKey`; `CreateOrderRequest` has no such field (`OrderService.java:83-167`) | **Duplicate order row minted** |
| `POST /public/shops/{slug}/orders` (storefront guest checkout) | **YES (partial)** | **request-BODY** `idempotencyKey` (`GuestOrderRequest.java:29`) → `findByTenantIdAndIdempotencyKey` read (`PublicStorefrontService.java:334`), write (`:385`); unique idx `idx_orders_idempotency` (`V24:7-9`) | Replay returns existing order; re-fetches Stripe client secret for payable DRAFT (`:346-370`) |
| `POST /sync/batch` (edge) | **YES (by construction)** | **UPSERT by natural key**, not the idempotency contract: `findByName`/`findBySku` → update-or-insert (`SyncService.java:86-109`). Handles shops+products only, **not orders** | Re-apply overwrites same row (idempotent) |
| `POST /api/v1/orders/{orderId}/refund` | **YES** | **HEADER** `Idempotency-Key` (`RefundController.java:80`) → tenant-scoped stored-first dedup (`RefundService.java:100-109`); `Refund` unique `(tenant_id, idempotency_key)` (`Refund.java:36-37, 59-60`) | Replay returns existing refund, **201** (`RefundController.java:84-89`) |
| `POST /api/v1/customers` | **NO (dup-blocked, not replay)** | `uq_customers_tenant_email` (`V9:15`) → `DataIntegrityViolationException` → **409** (`GlobalExceptionHandler.java:108-124`) | **409 CONFLICT**, NOT original 201+body |
| `PUT /api/v1/orders/{id}` + state transitions (`/submit`, `/confirm`, …) | **partial** | State-machine guard veto (#177) never revisits a state; `transitionOrder` (`OrderService.java:324-386`) | Repeat transition → `InvalidStateTransitionException` **400** |
| `POST /api/v1/products`, `/shops` | **NO (dup-blocked)** | unique `idx_products_tenant_sku`, `idx_shops_tenant_name` → 409 (`GlobalExceptionHandler.java:112-115`) | 409 CONFLICT |
| `POST /api/v1/onboarding/*` | **NO (dup-blocked)** | `uq_onboarding_tenant` → 409 (`GlobalExceptionHandler.java:116-117`) | 409 CONFLICT |
| Marketing (`shop_promotions`, `shop_announcements`) | **NO** | none | Duplicate rows |
| Stripe webhook (`/webhooks/stripe`) | **YES** | `processed_stripe_events` (`V35:28-31`), `INSERT … ON CONFLICT (event_id) DO NOTHING` (`PaymentService.java:201-207`) | 0 rows → skip side effects |

**Headline finding:** the `Order.idempotencyKey` column exists but is **write-live on only the storefront
path** and reads from the **body**. The dashboard create path — the exact one AC-2 tests — is
**not idempotent at all**. Refunds are the only header-based dedup, and it is entity-local, not generic.

### Precedent mechanisms to mirror

**V47 `processed_order_events` — THE model the issue names (`V47:28-42`):**
```sql
CREATE TABLE processed_order_events (
    tenant_id    UUID NOT NULL, order_id UUID NOT NULL, new_status VARCHAR(32) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, order_id, new_status));
ALTER TABLE processed_order_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_order_events FORCE ROW LEVEL SECURITY;
CREATE POLICY processed_order_events_tenant ON processed_order_events FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
```
- **No `_aud` mirror** — a dedup store is deliberately not Envers-audited. Follow this (do NOT add `orders_aud`-style mirror).
- Consumed via raw `JdbcTemplate` `INSERT … ON CONFLICT DO NOTHING` **inside** the listener `@Transactional`
  (`OrderStateChangeListener.java:95-103`); **0 rows ⇒ duplicate ⇒ skip**.
- **Critical RLS detail:** the listener sets `TenantContext` **and** the `app.current_tenant_id` GUC via
  `set_config` **before** the insert (`OrderStateChangeListener.java:83-90`) — because raw `JdbcTemplate`
  bypasses the JPA `TenantSetLocalAspect`. This is the #1 trap for a JdbcTemplate-based store.

**`processed_stripe_events` (`V35:28-31`)** — infra-scoped (`event_id TEXT PK`, **no tenant, no RLS**, runs
before TenantContext). Not the model here because idempotency keys ARE tenant data.

**Refund header-dedup (`RefundService.java:95-159`)** — the UX/contract precedent: optional header, server
generates a key when omitted, replay returns the mapped DTO, controller always 201.

### Duplicate-blocking constraints already in place (do not double-protect)
- `idx_orders_idempotency (tenant_id, idempotency_key) WHERE … NOT NULL` (`V24:7-9`)
- `uq_customers_tenant_email (tenant_id, email)` (`V9:15`)
- `uq_fin_tx_tenant_order` partial unique (V40 — one ledger row per order)
- `idx_products_tenant_sku`, `idx_shops_tenant_name`, `uq_onboarding_tenant`

## Recommended Implementation

### Storage — V50 `idempotency_keys` (mirror V47)

`V50__idempotency_keys.sql` (**V50 is the next free slot**; current head is V49. Note
`spring.flyway.out-of-order=true` is set project-wide but V50 is sequential so no interaction):

```sql
CREATE TABLE idempotency_keys (
    tenant_id       UUID         NOT NULL,
    endpoint        VARCHAR(128) NOT NULL,   -- logical op id, e.g. 'orders.create'
    idempotency_key VARCHAR(64)  NOT NULL,
    request_hash    CHAR(64),                -- SHA-256 of canonical request body
    response_status INT,                     -- NULL while first request in-flight
    response_body   TEXT,                    -- serialized DTO (JSON)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, endpoint, idempotency_key));
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_keys_tenant ON idempotency_keys FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
```
No `_aud` mirror. Also add `schema_version` bump → 50 in `docs/metrics.json` (docs-freshness gate).

### Concurrency — reserve-first, the house pattern

Mirror `OrderStateChangeListener.java:95-103` / `PaymentService.java:201-207`:

1. `INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash) VALUES (?,?,?,?) ON CONFLICT DO NOTHING`.
2. **1 row inserted** → first request: run the create in the same `@Transactional`, then `UPDATE` the row
   with `response_status` + `response_body`. (Row rolls back with the tx on failure = "processed at least
   once", identical to the V47/Stripe semantic — a failed create leaves no key so a genuine retry works.)
3. **0 rows inserted** → replay: re-read the row.
   - `response_status` present → return stored status + body (satisfies AC-2 replay).
   - `response_status` NULL (first request still in-flight — **the concurrent race AC-2 demands**) →
     **409** `ProblemDetail` "Idempotency-Key request in progress" + `Retry-After`. This is the honest,
     race-safe answer and matches Stripe's concurrent-request behavior.
   - `request_hash` mismatch → **422** "Idempotency-Key reused with a different payload".

**Alternative:** `pg_advisory_xact_lock(hashtext(tenant||endpoint||key))` to serialize concurrent same-key
requests so the second waits and reads the committed response (cleaner replay, no 409-in-flight), at the
cost of lock contention. Recommend the ON-CONFLICT+409 path first — it is the established house idiom and
needs no new locking primitive.

### Opt-in — `@Idempotent` annotation + HandlerInterceptor

- New `@Idempotent(endpoint = "orders.create")` marker on `OrderController.createOrder` and
  `CustomerController.create` (slice 1). Future endpoints adopt with one annotation.
- Register a `HandlerInterceptor` in `WebConfig.addInterceptors` (`WebConfig.java:68-94`) **after** the
  rate-limit + tenant-status interceptors, `HandlerMethod.hasMethodAnnotation(Idempotent.class)` gate.
- `preHandle`: if `@Idempotent` and header present, delegate to the store (replay short-circuit or reserve).
  Header value length ≤ 64, else 400.
- **Response capture:** prefer **storing the serialized DTO in the service/tx** (as Refund/Order already do
  — `RefundService.java:107`, `PublicStorefrontService.java:358`) over raw servlet-byte capture. If you must
  capture at the servlet layer, `ContentCachingResponseWrapper` needs a `OncePerRequestFilter` wrapper +
  `copyBodyToResponse()`, and you must exclude the SSE stream `GET /api/v1/orders/stream`
  (`OrderController.java:44-48`) and RFC 7807 error bodies. The DTO-in-tx approach sidesteps all of that and
  is more house-consistent — **recommended**.

### OpenAPI (AC-4) — OperationCustomizer keyed off `@Idempotent`

`[VERIFIED: Context7 /springdoc/springdoc-openapi]` A `@Bean OperationCustomizer` receives
`(Operation, HandlerMethod)` and can add a header parameter conditionally:

```java
@Bean
OperationCustomizer idempotencyKeyHeader() {
    return (operation, handlerMethod) -> {
        if (handlerMethod.hasMethodAnnotation(Idempotent.class)) {
            operation.addParametersItem(new Parameter()
                .in("header").name("Idempotency-Key").required(false)
                .description("Client-supplied key; same key replays the original response, never duplicates.")
                .schema(new StringSchema().maxLength(64)));
        }
        return operation;
    };
}
```
This advertises the header on **exactly** the operations that honor it. (Alternative: add
`@RequestHeader(value="Idempotency-Key", required=false)` params to each method — springdoc auto-adds them,
as already visible for `RefundController.java:80`. The OperationCustomizer keeps the header out of the
method signature and couples advertisement to the annotation — cleaner for uniform rollout.)

**Snapshot workflow (`ci-cd.yaml:213-264`, `build.gradle.kts:158-168`):** adding an optional header
parameter is **non-breaking** to oasdiff `[ASSUMED]`, but the `openapi-compat` gate **fails on ANY drift**
("non-breaking drift → FAIL telling the author to regenerate", `ci-cd.yaml:220-227`). So the required action
is unconditional: `./gradlew :core-java:updateOpenApiSnapshot` → commit `docs/api/openapi-snapshot.json` in
the same PR. The snapshot is generated by `OpenApiSnapshotTest` booting the full context against
Testcontainers Postgres.

## Common Pitfalls (codebase-specific)

1. **RLS GUC not set before store access (the #1 trap).** The store is FORCE RLS. If you touch it via raw
   `JdbcTemplate` (as the two precedents do), the `app.current_tenant_id` GUC is **not** auto-set — you must
   `SELECT set_config('app.current_tenant_id', ?, true)` first, exactly as `OrderStateChangeListener.java:83-90`.
   If you use a JPA repository the `TenantSetLocalAspect` sets it for you. `TenantContext` is available in
   interceptors because `JwtTenantFilter` (@Order 200, `JwtTenantFilter.java:61`) runs before interceptors —
   but ThreadLocal presence ≠ GUC set on the connection.
2. **Store write must join the create's transaction.** Reserve-then-fail must roll the key back (the V47
   "at least once" semantic). Do NOT use `REQUIRES_NEW` for the reservation, or a failed create leaves an
   orphan key that blocks the legitimate retry.
3. **No in-memory dedup — multi-replica.** `OrderStateChangeListener` javadoc confirms N replicas; dedup MUST
   be DB-backed (this store is). A `ConcurrentHashMap` would silently fail across pods.
4. **FORCE RLS + Hibernate traps** (memory: `ci_integration_test_gap`) — keep the store dumb: no `@Version`,
   no batching reliance, no session-cache assumptions. Prefer `JdbcTemplate` for the reserve/replay to stay
   TOCTOU-atomic (matches Phase 16.1-04 LOCKED decision, STATE.md:122).
5. **RFC 7807 shape.** Replay of an error is out of scope — only cache 2xx. Same-key-different-payload → 422
   `ProblemDetail` via `GlobalExceptionHandler` (`common/GlobalExceptionHandler.java`), consistent with the
   existing 409 duplicate shape (`:120-124`).
6. **Rate limiter double-count.** `RateLimitInterceptor` runs first (`WebConfig.java:71`); a replayed request
   still consumes a token. Acceptable; note it — do not try to refund the bucket.
7. **`/api/v1` prefix.** Order/Customer controllers are prefixed via `WebConfig` package matching
   (`WebConfig.java:43-52`). The `endpoint` store column should be the **logical** op id (`orders.create`),
   not the URL, so the key survives future versioning.
8. **TTL/cleanup.** `ScheduledCleanupService` exists (`config/ScheduledCleanupService.java`) — add a
   per-tenant pruning sweep (Stripe uses 24h; recommend 24-72h) mirroring V47's "deferred to scheduled-cleanup
   housekeeping" note. Not a launch blocker but document it.

## Validation Architecture (AC-2)

**Framework:** JUnit 5 + Testcontainers Postgres (real RLS). Command: `./gradlew :core-java:integrationTest`.

**Concurrent-replay test precedent to copy:** `ConcurrentStockDecrementIntegrationTest.java`
(`CountDownLatch` + `ExecutorService`, two threads released simultaneously — lines 24-39) and
`GuestCheckoutStockConvergenceIntegrationTest.java`. Both already prove concurrent-write convergence under
Testcontainers + the `SET LOCAL ROLE rls_test_role` NOSUPERUSER pattern (STATE.md:123) needed so RLS is
actually enforced in-test.

**Wave 0 test map:**

| AC | Behavior | Test |
|----|----------|------|
| AC-2 | same-key `POST /api/v1/orders` → 1 row, replay returns original body | `OrderIdempotencyIntegrationTest` (new) |
| AC-2 | **CONCURRENT** same-key race → exactly 1 order row, no 500 | new test, `CountDownLatch` 2-thread harness per `ConcurrentStockDecrementIntegrationTest` |
| AC-3 | same-key `POST /api/v1/customers` → replay returns original, no dup | `CustomerIdempotencyIntegrationTest` (new) |
| AC-4 | header advertised on annotated ops | `OpenApiSnapshotTest` snapshot diff (regen) |
| — | same-key + different payload → 422 | unit/integration |

Existing storefront idempotency unit coverage (`PublicStorefrontServiceTest.java:517-548`) shows the mock
pattern for `findByTenantIdAndIdempotencyKey`. Metrics baseline: `docs/metrics.json` = 1192 logical
invocations / schema V49 (CLAUDE.md's "1185" is stale vs metrics.json — metrics.json is the source of truth;
docs-freshness gate must be re-synced after new tests + V50).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Adding an optional header parameter is non-breaking to oasdiff | OpenAPI | Low — gate forces regen either way; snapshot commit covers both cases |
| A2 | Stripe returns an error (not replay) on same-key-different-payload; 24h TTL | Replay semantics / TTL | Low — 422 + 24-72h TTL is a defensible design choice regardless of Stripe's exact code |
| A3 | 409-while-in-flight is the right concurrent-race response vs advisory-lock-wait | Concurrency | Medium — both are valid; ON-CONFLICT+409 chosen to match house idiom. Confirm at plan time |

## Sources

**Primary (HIGH):**
- Codebase (all file:line refs above) — verified against working tree at HEAD `04b8418`.
- Context7 `/springdoc/springdoc-openapi` — `OperationCustomizer` global-header pattern (verified for SpringDoc 2.8.x).

**Secondary:**
- CLAUDE.md, STATE.md — V47/V35/V40 precedent context, Phase 16.1-04 LOCKED TOCTOU-INSERT decision, RLS test-role pattern.

## Metadata
- **Confidence:** Audit HIGH (file:line-verified); implementation HIGH (mirrors two shipped precedents); OpenAPI HIGH (Context7-verified).
- **Valid until:** 2026-08-12 (stable domain; re-verify if schema advances past V49 before implementation).
</content>
</invoke>
