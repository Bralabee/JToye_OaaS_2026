# How J'Toye OaaS Works — End to End

**Last verified:** 2026-08-19 against `main @ 53d7bd7d`. Every mechanism below was read out of the
code during a two-round supervised tour. This document traces **what actually happens at runtime**,
request by request, so a new engineer can reason about the whole system rather than one file.

Companion docs: `docs/architecture/ARCHITECTURE.md` (structure), `docs/FAILURE_MODES.md` (hazards),
`docs/PRD.md` (product).

---

## 1. A request's life — where the security actually happens

Take an authenticated vendor request, `GET /api/v1/orders`, from the dashboard:

1. **Browser → core directly.** The dashboard's axios client uses `NEXT_PUBLIC_API_URL`, which points
   at `core-java:9090`. **It does not go through the edge.** The bearer token comes from the NextAuth
   session; an `X-Tenant-Id` header is attached as defence-in-depth (the server treats it as advisory).
2. **Spring Security** validates the JWT against the Keycloak `jtoye-dev` realm — signature, issuer
   (split-horizon: the JWKS host and the expected `iss` are configured separately), and audience.
3. **`TenantContextCleanupFilter`** (outermost, `@Order(HIGHEST_PRECEDENCE)`) wraps everything in a
   `try/finally` that clears the `TenantContext` ThreadLocal on the way out — on every path, in every
   profile. This is what prevents a pooled thread from leaking one tenant's context into the next
   request.
4. **`JwtTenantFilter`** (`@Order(200)`) reads the tenant claim (`tenant_id` → `tenantId` → `tid`) and
   sets `TenantContext`. **The JWT claim wins over any header.** A missing claim increments a
   `tenant.context.missing` counter that a Prometheus alert watches.
5. **The controller delegates to a `@Transactional` service.** As the transaction opens,
   `TenantSetLocalAspect` fires `SELECT set_config('app.current_tenant_id', ?, true)` — a
   *transaction-local* GUC — and re-pins it just-in-time before each repository call.
6. **Postgres RLS does the isolation.** Every policy is `tenant_id = current_tenant_id()`, where the
   helper reads that GUC. The app connects as `jtoye_runtime`, a non-owner role that **cannot bypass
   RLS**, so even a query that forgot to pin sees *zero* rows (fail-filtered), not another tenant's
   data. If the GUC is malformed, V51's hardening makes the helper return NULL → 0 rows rather than
   throwing `22P02`.
7. **Second wall for shop-scoped resources.** For anything gated per-shop, `ShopAccessService.require()`
   is the single decision funnel: `GROUP_ADMIN` is tenant-wide, others hold per-shop grants; a
   published *foreign* shop that slips through `findById` (because `shops_public_read` is deliberately
   cross-tenant) is caught by an explicit tenant comparison and answered as a non-disclosing 404.

**The takeaway:** the frontend and MCP server carry no authority. Isolation is RLS + `TenantContext` +
the GUC aspect, all inside the core. Two things defend each layer, so a single miss is contained.

---

## 2. How a guest places an order (the money path, and why it's COD)

`POST /public/shops/{slug}/orders` (unauthenticated, guest checkout):

1. The storefront collects items, a UK delivery address, and an **allergen acknowledgement** (a React
   checkbox — client-side only; the server does not enforce its presence).
2. `PublicStorefrontService.createGuestOrder` applies the minimum-order gate and delivery-fee rules,
   then reaches the decision point: **`if (paymentService.isConfigured())`**.
3. `isConfigured()` is just "is `stripe.api-key` non-blank?" — and it defaults **empty** on every
   stack. So the code takes the **cash-on-delivery else-branch**: status `PENDING`, `PaymentStatus.NONE`,
   `paymentMethod = "Unpaid"` (INT-9 / E-2 — fulfilment-neutral: the branch never consulted the
   fulfilment type, so the old `"Cash on Delivery"` label was wrong on every COLLECTION order),
   and the order event is published in-transaction via the
   outbox. A boot-time WARN records that payments are unconfigured.
4. **If a key were present**, the order is `saveAndFlush`-ed *before* the Stripe intent is created (the
   order UUID must exist for the `order_id` metadata — omitting this was the #538 defect), MARKETPLACE
   orders route as **destination charges** with an application fee, and the order's event is published
   later at **webhook** time instead.
5. **The V63 allergen snapshot** is written onto each `order_item` at this moment — the vendor's
   declared mask + an advisory reconciliation flag — so a later product edit cannot rewrite what the
   customer was shown. Historic rows stay NULL ("NOT RECORDED"), deliberately un-backfilled.

**No automated run has ever taken a card payment or issued a refund.** The nightly E2E confirms
checkout via the COD branch; the refund test is a declared skip pending Stripe keys.

---

## 3. How a state change propagates (orders → kitchen, and outbox durability)

When an order changes state (e.g. staff marks it READY):

1. The service writes the state change **and an outbox row in the same transaction**. Durability is
   Postgres's, not the broker's — a RabbitMQ outage cannot lose the event.
2. `PaymentEventOutboxFlusher` (every 5 s) claims due rows with `FOR UPDATE SKIP LOCKED`, per tenant,
   and publishes them to RabbitMQ. On failure it backs off exponentially; `MAX_ATTEMPTS` → `FAILED`
   (resurrectable every 300 s), a corrupt payload → `poison` (terminal).
   - **The dispatch trap:** `publishRow` deserialises by a *closed set* of exchanges. A new event
     family routed through this shared outbox that isn't in the set is deserialised as a PaymentEvent,
     throws, and poison-fails permanently. `media_event_outbox` (V58) is a dedicated clone that exists
     *only* to avoid this.
3. A `@RabbitListener` consumes the event. The order state-change listener dedupes on the V47
   `processed_order_events` semantic key (`(tenant_id, order_id, new_status)`) — the consumer is
   at-least-once, so this makes it effectively-once.
4. **Two realtime fan-outs reach the UI:**
   - **SSE** (`/dashboard/orders`): the event hits a per-replica **exclusive auto-delete** AnonymousQueue,
     and `OrderSseService` broadcasts to that replica's subscribers — **re-checking the subscriber's
     current shop grant before every emit** (a revoked grant stops the stream, bounded by a 5-minute
     cache TTL across replicas).
   - **STOMP** (`/dashboard/kitchen`): the kitchen board subscribes to `/topic/orders.{tenantId}`;
     `TenantChannelInterceptor` enforces the tenant + per-shop grant at SUBSCRIBE. A poll fallback
     covers STOMP unavailability.

---

## 4. How media upload works (copy-on-write + safe pipeline)

1. The vendor UI pre-flights the image (dimension rules, canvas re-encode under a 5 MB cap), attaches
   an Idempotency-Key, and `POST`s; the server returns **202 Accepted** with a PENDING `media_asset`
   row and an outbox row (dedicated `media_event_outbox`).
2. `MediaProcessingWorker` (`@RabbitListener`, its own container factory) pins the tenant GUC **first**,
   magic-byte-sniffs jpeg/png/webp, guards against decompression bombs, strips EXIF, transcodes to a
   **WebP derivative + thumbnail**, and stores **only the validated derivative** (never raw bytes),
   moving the asset PENDING→ACTIVE (or FAILED) under a `@Version` optimistic lock.
3. Two schedulers guard durability: `MediaPendingReaper` (10 min) may only flip PENDING→FAILED *with
   dispatch evidence* and structurally cannot delete bytes (it holds no `StorageService`);
   `MediaQuarantineRetentionSweep` (72 h horizon) is the sole byte-reclamation path — so a broker
   outage longer than the flusher's ~20-minute backoff no longer destroys the source (the V60 fix).
4. The UI review queue surfaces PENDING (processing), ACTIVE (WebP with dims), FAILED (reason +
   re-upload), and flagged-ACTIVE (Keep-or-Replace).

CoW model: `media_asset` is sha256-deduped per tenant; the physical MinIO object is deleted only at
ref-count 0. Objects are anonymously readable **by key** (deliberate) but the bucket is **not**
anonymously listable (fixed, #626 — verified live: unauthenticated ListObjects → 403).

---

## 5. How an AI agent uses the platform (MCP)

1. An agent POSTs to `mcp-server:9100/mcp` with a Bearer token. The MCP server requires the token to be
   non-empty but **never validates it** — it builds a fresh `McpServer` per request, closing over that
   Bearer, and forwards to a **fixed** `CORE_BASE_URL` (SSRF guard) with a 10 s timeout.
2. **The core is the sole validator and RLS boundary.** A read-scoped agent token calling a mutating
   tool gets a 403 from core's `@PreAuthorize("hasAuthority('SCOPE_orders:write')")`; a tenant-A token
   reading a tenant-B UUID resolves 404/empty via RLS.
3. Mutating tools (`create_order`, `create_customer`) require an **idempotency key** as a tool
   argument, split out to the `Idempotency-Key` header and backed by core's V50 FORCE-RLS store —
   replay returns the original response, an in-flight race returns 409, same-key/different-body returns
   422.
4. `read_orders` exposes the V63 allergen aggregate only on its `orderId` **detail** call, never on the
   list calls (an N+1 avoidance mirrored from the core DTOs).

---

## 6. How onboarding gates a shop live

The vendor onboarding **state machine is the sole writer of `Shop.published`**. Three gates run today —
`BUSINESS_VERIFIED` (Companies House API), `FOOD_HYGIENE_RATING` (FSA FHRS/FHIS API), and
`ALLERGEN_DATA_COMPLETE` — each behind a Resilience4j circuit breaker. Five further gates are
enum-declared placeholders. Phase 21 added per-gate remediation blocks, correctable data, a WITHDRAW
exit, and vendor-visible "in review" states. `MANUAL_REVIEW` currently reaches **no actor** (there is
no cross-tenant operator identity by design) — an open product decision (#453).

---

## 7. How GDPR requests are executed without a human ever holding cross-tenant read

1. An anonymous subject lodges a DSAR from the public internet. Intake (`dsar_request`, V62) stores a
   **one-way hash** of the email, a digest-only verification token, and returns a **constant opaque
   202** carrying nothing that could confirm which vendors hold the address (anti-enumeration).
2. `dsar_request` is deliberately **not tenant-scoped** and RLS-exempt — there is no JWT, no
   `TenantContext`, so an RLS predicate would return 0 rows to the worker that must read it.
3. `DsarFanoutWorker` (every 5 minutes) reads verified requests and **iterates every tenant, pinning
   the GUC per tenant** — the cross-tenant reach is the *loop*, not a privileged identity. It executes
   verified erasures via `GdprService` (Article-17: anonymise live rows + scrub the `_aud` audit
   history via the V42 UPDATE policies + write a PII-free `erasure_records` row).
4. This is how a single "data subject request" desk is reconciled with a platform that has refused a
   cross-tenant human operator: intake is a request, execution is background, no person ever reads
   across tenants.

---

## 8. How the project proves things to itself (and what it can't)

Verification is a first-class subsystem, not an afterthought.

- **Test estate (3185 logical invocations, `docs/metrics.json`):** 1713 Java `@Test` methods / 270 files
  (113 of those files are Testcontainers-tagged and run against a **real Postgres** — this is where RLS is actually exercised,
  since H2 has no RLS); 1230 Jest; 113 Playwright / 22 specs; 81 Go; 48 MCP vitest. Results land in
  `build-local`, **not** `build/` (which is stale — a known trap).
- **37 gate scripts**, three-state exit: **0 clean / 1 violation / 2 VOID** (VOID = "could not
  evaluate" and is *never* a pass). Each gate names the real incident it exists to catch, and the
  culture rule is that **a check is not trusted until it has been observed failing**. Two docs-freshness
  gates keep the prose counts honest against the tree; a third oracle checks the manifest against the
  actual test runners.
- **A green PR does NOT prove:** any authenticated flow works (that's nightly-only); the integration
  suite ran (it's path-filtered and reports SUCCESS when skipped); the running runtime matches the
  branch (runtime-freshness is deliberately not in CI); alerts can fire; or that money moves.
- **Live caveat (2026-08-19):** the **nightly full-stack E2E has failed for 9 consecutive nights**
  since 2026-08-11 — the compose stack never becomes healthy, so Playwright never runs — and **nothing
  alerts on it**. This means merges since 2026-08-10 (including Phase 31, 18 plans) landed with **zero
  full-suite E2E evidence**. See `docs/FAILURE_MODES.md` §7.

---

## 9. How you run it locally

```bash
# Bring up the canonical Compose runtime (dev + E2E)
docker compose -f docker-compose.full-stack.yml up -d --build

# Hybrid ALTERNATIVE, not the same thing: scripts/start-dev.sh runs verify-env.sh against the
# repo-root .env, then `cd infra && docker compose up -d` (Postgres + Keycloak only, reading
# infra/.env), then bootRun and `npm run dev` as HOST processes. Never run it alongside the
# line above — they collide on host ports 5433 and 8085. Teardown: scripts/stop-dev.sh
#   scripts/start-dev.sh

# After ANY code change, rebuild the affected images BEFORE E2E — `start` does not rebuild:
docker compose -f docker-compose.full-stack.yml up -d --build core-java frontend

# Prove the runtime matches the tree (from the MAIN checkout, not a worktree):
bash scripts/check-runtime-freshness.sh     # expect rc=0, "N/N services match"

# Run the gate sweep (expect 35 pass + 2 documented states):
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1 || echo "rc=$? $(basename "$g")"
done
# check-alert-metrics rc=1 after a core-java rebuild is EXPECTED (counter dies on restart):
bash scripts/seed-order-metric.sh
```

Compose XOR minikube locally — never both (shared dev DB). Ports: frontend 3000, core 9090,
edge 8089, mcp 9100, Postgres 5433, Keycloak 8085, Grafana 3002.
