# J'Toye OaaS — Current-State Architecture

**Version:** v2.3 · **Last verified:** 2026-08-19 against `main @ 53d7bd7d` (clean)
**Method:** two-round supervised codebase tour, every claim carrying a `file:line` citation or a
measured command output; counts taken with `rg -uu` (plain grep/rg on this machine honour
`.gitignore`).

> **Scope of this document.** This is the architecture **as it runs today**, in the canonical local
> Compose runtime. It is the companion to `docs/PRD.md`.
> `docs/architecture/SYSTEM_DESIGN_V2.md` is a **target-state / aspirational** design (k6 harnesses,
> OTel collectors, a scaling story) — useful for direction, but it describes things not deployed;
> where the two disagree, **this document is the current reality**. The 2026-04-18
> `.planning/codebase/ARCHITECTURE.md` is **stale** (it says Boot 3.4.2, Go 1.22, 33 migrations) and
> is superseded by this file.

---

## 1. System shape

Four deployable services, one database, a message broker, and supporting infrastructure. **Three of
the four services talk to Core directly — the "edge gateway" fronts almost nothing today.**

```
                    Browser (vendor + consumer)
                            │
              ┌─────────────┼──────────────────────────┐
              │ NEXT_PUBLIC_API_URL → core-java:9090    │  (frontend NEVER calls the edge)
              ▼                                          ▼
     ┌──────────────────┐                      ┌──────────────────┐
     │  frontend        │  SSR loaders ───────▶│                  │
     │  Next.js 16 /    │  (server-side fetch) │   core-java      │
     │  React 19 :3000  │                      │   Spring Boot    │
     └──────────────────┘   SSE (orders) ─────▶│   3.5.16 :9090   │
                            STOMP /ws (kitchen) │   JDK 21         │
     ┌──────────────────┐                      │                  │
     │  mcp-server      │  Bearer passthrough  │  ┌────────────┐  │
     │  TS/Express :9100│─────────────────────▶│  │ PostgreSQL │  │
     │  (5 AI tools)    │  (core is the RLS    │  │ 15 (RLS)   │  │
     └──────────────────┘   validator)         │  └────────────┘  │
                                               │  Redis · Rabbit  │
     ┌──────────────────┐                      │  MinIO · Keycloak│
     │  edge-go :8089   │  POST /sync/batch ──▶│  Ollama          │
     │  Gin/Go 1.26     │  (the ONE JWT route) │                  │
     │  + WhatsApp HMAC │  WhatsApp → orders ─▶│                  │
     └──────────────────┘                      └──────────────────┘
```

Key architectural fact, easy to get wrong: **the frontend and the mcp-server both call
`core-java:9090` directly.** The edge gateway's entire live surface is `/health`, `/ready`,
`/openapi.json` + `/docs`, an HMAC-signed WhatsApp webhook, and exactly **one** JWT-protected
business route — `POST /api/v1/sync/batch` — which has **no production caller** today
(`edge-go/cmd/edge/main.go:262-304`). "API gateway" describes ambition, not the current traffic
topology.

---

## 2. Core-Java — the real system

Spring Boot 3.5.16 on JDK 21. **367 Java source files across 26 domain packages** under
`uk.jtoye.core`. Largest: `onboarding` (35), `security` (30), `media` (28), `order` (28),
`notification` (25), `payment` (24). Vestigial: `controller` (1 — `SecurityHealthController`;
the real controllers live in domain packages), `ai` (2), `dev` (2). 29 files carry `@RestController`.

### 2.1 The multi-tenancy wall — four layers

Tenant isolation is enforced at four independent layers. A defect at any one is caught by the next.

1. **Database (RLS).** Across V1–V63: **40 `ENABLE`, 48 `FORCE`, 93 `CREATE POLICY`** statements.
   The canonical predicate is `tenant_id = current_tenant_id()` via a safe plpgsql helper. V51
   hardened that helper so a malformed GUC **fail-filters** (returns NULL → 0 rows) rather than
   raising `22P02`, and routed the last 10 raw-cast policies through it;
   `RlsContractTest.noPolicyUsesRawTenantGucCast` sweeps `pg_policy` to prevent reintroduction.
   Exactly **6 tables are RLS-exempt** (`RlsContractTest.EXEMPT_TABLES:95-162`), each with a written,
   sentinel-guarded justification: `flyway_schema_history`, `processed_stripe_events` (the webhook
   handler runs *before* `TenantContext` is set), `tenants` (the registry IS the identity source),
   `revinfo` (global Envers metadata; per-tenant filtering lives in the `_aud` children),
   `postcode_centroid` (public reference data), `dsar_request` (RLS would return 0 rows to the very
   worker that must read it — "a dead table is indistinguishable from an empty one").
2. **DB role (Phase 28).** The app connects as a **non-owner, NOSUPERUSER/NOBYPASSRLS DML role
   `jtoye_runtime`** (verified live: 5 pool connections, no `jtoye_app` session); `jtoye_app` remains
   owner/migrator with the Flyway credential decoupled via `DB_MIGRATION_USER`. A boot-time
   `DatabaseConfigurationValidator` **refuses to start** if the connected role owns any public
   relation. This removes the "a future table missing FORCE would be owner-readable" failure mode —
   a fourth layer that no older document describes.
3. **JPA/service.** `TenantContext` is a `ThreadLocal<UUID>`. `TenantSetLocalAspect` pins
   `set_config('app.current_tenant_id', ?, true)` `@Before` every `@Transactional` boundary **and**
   just-in-time before every repository/`JdbcTemplate` call, resetting to DEFAULT when context is
   absent. Off-request-thread code (workers, listeners) must pin manually — 19 files do.
   `MissingTenantContextException` is thrown rather than proceeding unpinned.
4. **HTTP.** `TenantContextCleanupFilter` (`@Order(HIGHEST_PRECEDENCE)`) clears the ThreadLocal in a
   `finally` on every path in every profile — the real prod clearer. `JwtTenantFilter` (`@Order(200)`)
   maps the JWT claim (`tenant_id` → `tenantId` → `tid`) to `TenantContext`; **the JWT wins over any
   header**. The dev-only `X-Tenant-Id` header path is `@Profile({"dev","local","test"})` — inert in
   prod, and Phase 28 proved the *served* OpenAPI no longer advertises it.

**Second wall — application-layer shop scoping (Phase 23).** Within a tenant, `ShopAccessService` is
"the single in-tenant authorization seam." `shop_staff` (V52/V57) carries per-shop grants;
`GROUP_ADMIN` is tenant-wide. JIT lazy-provisioning auto-creates a tenant-wide admin for the caller's
own subject; a `strict-scoping` switch (**default OFF**) de-honours JIT admins while keeping OPERATOR
and realm admins, retaining the oldest JIT admin as a logged bootstrap so no tenant can lock itself
out. **FC-1**, a shipped cross-tenant write BOLA, was fixed by making the `GROUP_ADMIN` early-return
compare the target shop's tenant to `TenantContext` explicitly (necessary because the
`shops_public_read` policy lets a *published foreign* shop through `findById`); the answer is a
non-disclosing 404.

### 2.2 Async / event architecture

- **Exactly two AMQP outboxes.**
  - `payment_event_outbox` is shared by **four publishers across three exchanges** — payment.events, order.events (both order and refund events), and onboarding.events (`OrderEventPublisher` writes order events into the *payment* table). Drained by
    `PaymentEventOutboxFlusher` (`@Scheduled` 5 s flush / 300 s resurrect, per-tenant tx,
    `FOR UPDATE SKIP LOCKED`, exponential backoff, `MAX_ATTEMPTS → FAILED` resurrectable,
    `JsonProcessingException → poison` terminal). **The dispatch trap lives here:** `publishRow`
    deserialises by a **closed set** of exchanges; anything not in the set falls into a PaymentEvent
    poison sink. Branch ordering is load-bearing.
  - `media_event_outbox` (V58) is a **dedicated clone precisely to sidestep that trap** — one event
    type, no dispatch branch. Drained by `MediaEventOutboxFlusher`.
  - `webhook_delivery` (V56) is outbox-shaped but per-`(subscription, event)` delivery state, drained
    by `WebhookDeliveryWorker` (`FOR UPDATE SKIP LOCKED`, SSRF re-validation per attempt, HMAC-signs
    stored bytes, consecutive failures → `AUTO_PAUSED`).
- **9 `@RabbitListener` sites across 8 consumer classes**; **11 `@Scheduled` sites**. Notable: the
  order state-change listener dedupes on the V47 `processed_order_events` semantic key; the SSE
  fan-out listener consumes a per-replica **exclusive auto-delete AnonymousQueue** (restarts leak
  nothing); `MediaPendingReaper` (10 min) may only flip PENDING→FAILED *with dispatch evidence* and
  holds no `StorageService` (it structurally cannot delete bytes); `MediaQuarantineRetentionSweep`
  (72 h horizon) is the sole byte-reclamation path; `DsarFanoutWorker` (5 min) iterates tenants,
  pinning the GUC per tenant. The `onboarding.events` exchange **has no DLX** — unroutable onboarding
  messages vanish at the broker; a counter is the only visibility.
- **State machines.** Orders: `DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED`; CANCEL fires from any
  non-terminal state (DRAFT/PENDING/CONFIRMED/PREPARING/READY); a terminal `REFUNDED` is reachable
  from CONFIRMED/PREPARING/READY/COMPLETED. Stock is
  decremented **exactly once** at `CONFIRMED`. `COMPLETED` is deliberately not an `.end()` state
  because Spring StateMachine refuses transitions out of end states — refund-driven transitions depend
  on this. Onboarding: the gate-chain state machine is the sole writer of `Shop.published`.
- **Real-time.** SSE (`OrderSseService`, per-emit grant re-check, broker fan-out so N replicas all
  broadcast) and STOMP (`/topic/{feature}.{tenantId}` one-dot-segment grammar; `TenantChannelInterceptor`
  enforces tenant + per-shop grant at SUBSCRIBE; relay vs in-memory by `stomp.broker.mode`).

### 2.3 Money path (built, dormant by default)

`PublicStorefrontService.createGuestOrder` decides at `if (paymentService.isConfigured())`
(`:847`): with a Stripe key it saves the order *before* creating the intent (the order UUID must
exist for `order_id` metadata — the #538 defect), routes MARKETPLACE orders as **destination charges**
with an application fee of `platform-fee-bps` (default 0); **without** a key it takes the
**cash-on-delivery** branch (`:903-908`, `PaymentStatus.NONE`, `paymentMethod="Cash on Delivery"`).
`isConfigured()` is simply "`stripe.api-key` non-blank", which defaults empty everywhere — so **every
out-of-the-box runtime silently takes COD**, and a `@PostConstruct` WARN is logged at boot. Webhooks
(`/public/payments/webhook`) verify the Stripe signature, dedupe on `processed_stripe_events` *before*
`TenantContext` is set (that table is RLS-exempt for exactly this reason), then dispatch. HTTP
idempotency (V50) requires `TenantContext` and reserves-first on `(tenant_id, endpoint,
idempotency_key)` under FORCE RLS.

### 2.4 Config layer

**Zero `System.getenv` in main source** — all env injection flows through YAML `${VAR:default}`
placeholders across profiles (`application.yml` + `-dev/-local/-prod/-staging/-test`). 20 top-level namespaces; 11 typed `@ConfigurationProperties` beans (each individually enabled — no
`@ConfigurationPropertiesScan`). `spring.flyway.out-of-order: true` is set in base/staging/prod
(a reserved slot was filled after later migrations shipped).

### 2.5 Two identity planes

Do not conflate them:
- **Vendor / staff:** Keycloak realm `jtoye-dev`, OAuth2 resource server, `Authorization: Bearer`,
  split-horizon issuer (JWKS host vs expected `iss` decoupled, `#87`), audience always enforced.
- **Customer:** a **separate** verification plane — `CustomerJwtVerifier` validates
  `jtoye-customers`-realm tokens on the `X-Customer-Token` header (never `Authorization`), with a
  configurable `require-verified-email` gate. An architecture that omits this omits an entire attack
  surface.

---

## 3. Frontend — Next.js 16 / React 19

**38 pages, 20 layouts, 7 API routes.** Three surfaces (see PRD §3). Server-rendering via
`lib/storefront-server.ts` (three-valued `ok | notfound | defer` loader) covers `/`, `/shop`,
`/shop/[slug]`, `/shop/orders`, and `sitemap.ts`; all 18 dashboard pages are client-fetch-on-mount
(defensible — authenticated, not crawlable). Root layout is `force-dynamic` app-wide so every page
carries a per-request CSP nonce (built in `middleware.ts` — which explicitly does **not** gate auth;
`app/dashboard/layout.tsx` does, server-side, before HTML streams).

**Notable current facts:**
- **`next/image` has zero importers.** Every image ships through a plain `<img>` (`SafeImage` /
  `AssetImage`). Consequence: `images.remotePatterns` is inert config today and a latent staging/prod
  trap on any future adoption (the S3 `eu-west-2` hostname it would need is not whitelisted). CLS is
  handled by forwarded width/height; payload by the server-side WebP derivative pipeline.
- **The edge is never called from app code** (only one E2E spec hits `:8089` directly). Realtime is
  SSE (orders) + STOMP (kitchen, with a poll fallback) — two transports, token-per-connect.
- **Error-boundary root gap:** there is no `app/global-error.tsx` and no root `not-found.tsx`, so an
  exception in the root layout falls through to Next's unbranded default, outside the CSP nonce and
  brand chrome.
- Currency is GBP-only, formatted ad hoc (`formatPrice` re-declared per component; `"GBP"` hardcoded)
  — a deliberate UK-only constraint, but a drift risk with no shared money module.

---

## 4. Edge-Go and MCP-Server

**edge-go** (Gin, Go 1.26; 23 files): JWT validation is solid and fail-closed (RSA-only, split-horizon
issuer, audience always enforced with a `core-api` default, mandatory tenant claim); an unknown-`kid`
triggers a **concurrent** (not serialised) JWKS refetch per request — a real amplification vector
bounded only by the 20-rps limiter. The rate limiter is a **process-wide** DoS valve (not per-tenant —
that is Core's Bucket4j), and it sits **in front of `/health`**, so a 429 storm can starve the Docker
HEALTHCHECK into a container restart. One `sony/gobreaker` breaker is shared by all edge→core calls
and **counts 4xx as failure**, so one client's bad requests can open it for everyone; there is **no
fallback** — breaker-open returns 502. The WhatsApp webhook is HMAC-verified and fail-closed
(unconfigured → 503) but **provisioned in no environment**, and its order-create path sends **no
Idempotency-Key** — so a Meta retry would double-create (Core's header is `required = false`; a
key-less request bypasses the V50 store entirely). It also logs customer phone numbers in plaintext.

**mcp-server** (TypeScript/Express, `:9100`): a deliberately trust-nothing forwarder. It builds a
fresh `McpServer` **per request**, requires a non-empty Bearer but **never validates it** (Core is the
sole validator and RLS boundary), fixes `CORE_BASE_URL` (SSRF guard), times out at 10 s, and logs no
PII. Five tools: `list_shops` / `list_products` / `read_orders` (read) and `create_order` /
`create_customer` (mutating, both with a **required** idempotency key split to the `Idempotency-Key`
header). The V63 allergen aggregate is exposed only on `read_orders`' `orderId` **detail** call, never
the list calls (an N+1 avoidance). It has **no k8s manifest** (does not deploy to k8s) and no graceful
shutdown.

---

## 5. Data & schema

PostgreSQL 15, RLS-enforced, audited via Hibernate Envers. **63 Flyway migrations (V1–V63);** schema
head V63 matches the live dev DB. **11 entities are `@Audited`** with a matching set of 11 `_aud`
mirror tables (exact 1:1 parity). The migration story is legible from the SQL headers — RLS cast
safety (V39/V51), GDPR erasure (V42), outbox reliability (V46), HTTP idempotency (V50), the CoW media
model (V53/V58/V59/V60), postcode locality (V61), DSAR intake (V62), and the write-time allergen
snapshot (V63).

---

## 6. Runtime & deploy topology

**Two layers, both kept.**
- **Compose (canonical local dev + E2E).** `docker-compose.full-stack.yml` (11 app containers) +
  `infra/monitoring/docker-compose.monitoring.yml` (5 containers) = **16 running**. Infrastructure
  ports bind to `127.0.0.1`; the application tier (core/edge/frontend/mcp) binds to `0.0.0.0` by
  design. Stateful set: `postgres_data, redis_data, rabbitmq_data, keycloak_data, minio_data,
  ollama_data` (+ monitoring volumes).
- **Kubernetes (staging/prod deploy target).** `k8s/base` + `staging`/`production`/`local` overlays,
  Sealed Secrets, 6 NetworkPolicies (default-deny + tier allow-lists). **Neither deploy job is
  currently reachable** — `DEPLOY_STAGING_ENABLED` (staging) and `DEPLOY_ENABLED` (production) are
  both unset, so staging/production exist only as manifests + reviewed goldens. The local minikube
  rehearsal cluster is **not provisioned** right now. Locally it is Compose **XOR** minikube (shared
  dev DB), enforced by `scripts/k8s-local-up.sh`, not just documented.

**Monitoring:** Prometheus (8 targets up), 19 live alert rules + 3 dormant, Alertmanager → Mailhog,
Grafana, postgres/redis exporters. Runtime-parity machinery (`check-runtime-freshness.sh` on image
`.Metadata.LastTagTime` + image-ID; `check-branch-behind-base.sh`) exists because `docker compose
start` starts existing containers with old image IDs — an HTTP 200 and a green suite are identical
whether the running code is current or months stale.

**Verification is a signature feature.** 37 gate scripts, three-state exit (**0 clean / 1 violation /
2 VOID**, where VOID is never a pass), fail-direction-first (every gate names the incident it exists
to catch), default-deny CI wiring. See `docs/FAILURE_MODES.md` §7 and `docs/HOW_IT_WORKS.md` §8 for
what the gates do and do not prove.

---

## 7. Where the older docs disagree with reality

| Document | Stale claim | Reality (measured 2026-08-19) |
|---|---|---|
| `.planning/codebase/ARCHITECTURE.md` (2026-04-18) | Boot 3.4.2, Go 1.22, 33 migrations | Boot 3.5.16, Go 1.26, 63 migrations — predates Phases 18–33 entirely |
| `docs/architecture/SYSTEM_DESIGN_V2.md` | k6/OTel/scaling story | **Target-state**, not deployed — treat as direction |
| `docs/architecture/SECURITY_ARCHITECTURE.md` (v0.7.2, 2026-01-06) | `jtoye`/`jtoye_app` role model; `tablesWithRls: 5` | `jtoye_app` owner + `jtoye_runtime` runtime; ~40 RLS tables; no mention of shop_staff, scopes, SystemPrincipal, or the customer realm |
| `edge-go/README.md` | `JWKS_URL`/`JWT_ISSUER` vars; Go 1.21+ | Those vars are read by no code; real vars are `KC_ISSUER_URI`/`JWT_EXPECTED_ISSUER`; Go 1.26 |
| `docs/CHANGELOG.md` | `jtoye.co.uk` "never registered" | Registered 2026-07-27, parked at Namecheap to 2031 (RDAP) |
