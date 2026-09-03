# J'Toye OaaS — Essential Architecture

**The one read for a new engineer.** Everything here is measured against `main @ 53d7bd7d`
(2026-08-19). For depth see `docs/architecture/ARCHITECTURE.md`; for the runtime walkthrough,
`docs/HOW_IT_WORKS.md`; for the hazards, `docs/FAILURE_MODES.md`.

---

## The one-paragraph model

A multi-tenant UK SaaS for owner-led food vendors. A **Spring Boot core** (JDK 21, `:9090`) holds all
the business logic, the data, and — crucially — **all the security**. A **Next.js frontend** (`:3000`)
and an **MCP server** for AI agents (`:9100`) both call the core **directly**. A **Go edge gateway**
(`:8089`) exists but fronts almost nothing — one JWT route with no live caller, plus a WhatsApp
webhook. **PostgreSQL 15 with Row-Level Security is the tenant boundary**; everything else defends it.

## The five things you must not get wrong

1. **The core is the only security boundary that matters.** The MCP server forwards Bearer tokens
   without validating them; the edge validates JWTs but does no tenant authorization. RLS in Postgres,
   plus `TenantContext` + a GUC-pinning aspect in the core, is what actually isolates tenants. Break
   nothing in `TenantSetLocalAspect` or the RLS policies without understanding the whole wall.

2. **Tenancy is four layers deep.** (a) RLS policies (`tenant_id = current_tenant_id()`); (b) the app
   connects as a non-owner `jtoye_runtime` role that cannot bypass RLS and a boot validator refuses to
   start otherwise; (c) `TenantContext` ThreadLocal pinned to a transaction-local GUC before every
   query; (d) `JwtTenantFilter` sets it from the JWT claim (which overrides any header). A fifth
   *application-layer* wall — `shop_staff` scoping via `ShopAccessService` — sits inside a tenant.

3. **Two identity planes, never conflate them.** Vendors/staff → Keycloak realm `jtoye-dev`, header
   `Authorization`. Customers → realm `jtoye-customers`, header `X-Customer-Token`, verified by a
   *separate* `CustomerJwtVerifier`.

4. **The money path is fully built but dormant.** With no `STRIPE_API_KEY` (the default everywhere),
   checkout silently takes a **cash-on-delivery** branch. No real payment has ever been taken. Don't
   assume the Stripe path works end-to-end — it has never executed.

5. **Async goes through two Postgres outboxes, not the broker directly.** Durability is Postgres's
   (transactional outbox); RabbitMQ only fans out. `payment_event_outbox` has a **closed-set dispatch
   trap** — a new event type routed through it poison-fails unless you extend `publishRow` too. A
   dedicated `media_event_outbox` exists precisely to avoid that trap.

## The runtime in one diagram

```
Browser ──▶ frontend :3000 ──(SSR + SSE + STOMP)──▶ core-java :9090 ──▶ Postgres 15 (RLS)
AI agent ──▶ mcp-server :9100 ──(Bearer passthrough)──▶ core ──▶ Redis · RabbitMQ · MinIO · Keycloak
Meta   ──▶ edge-go :8089 ──(WhatsApp HMAC → orders; /sync/batch, no caller)──▶ core
```

## Where things live

| You want to change… | Go to |
|---|---|
| A REST endpoint, service, or entity | `core-java/src/main/java/uk/jtoye/core/<domain>/` |
| The tenancy/RLS wall | `security/TenantContext.java`, `security/TenantSetLocalAspect.java`, `db/migration/`, `security/RlsContractTest.java` |
| A Flyway migration | `core-java/src/main/resources/db/migration/` (head V63) |
| Vendor dashboard / storefront UI | `frontend/app/dashboard/`, `frontend/app/shop/` |
| Server-side rendering of a public page | `frontend/lib/storefront-server.ts` |
| Edge routing / JWT / WhatsApp | `edge-go/cmd/edge/main.go`, `edge-go/internal/` |
| An MCP tool | `mcp-server/src/tools/` |
| Config (never hardcode) | `application*.yml` `${VAR:default}` — zero `System.getenv` in core |
| Alerts / monitoring | `infra/monitoring/prometheus/alerts.yml` |
| A verification gate | `scripts/check-*.sh` (37 gates) |

## The runtime you actually run

**Compose is canonical for local dev + E2E** (`docker-compose.full-stack.yml`, 16 containers total).
**Kubernetes is the staging/prod target** — but neither deploy job is armed today, so k8s exists only
as manifests. **Locally, run Compose XOR minikube, never both** (they share the dev DB). Rebuild ALL
images after code changes before E2E — `docker compose start` does not rebuild.

Ports: frontend **3000**, core-java **9090**, edge-go **8089** (→ container 8080), mcp **9100**,
Postgres **5433**, Keycloak **8085**, Grafana **3002**.

## The stack (fixed — do not migrate without a decision)

Spring Boot 3.5.16 · JDK 21 (JDK 25 breaks Gradle 8.10) · Next.js 16.2.12 / React 19 · Go 1.27 /
Gin · PostgreSQL 15 · Redis 7 · RabbitMQ 4.3.4 · Keycloak 24.0.5 · MinIO · Ollama.

## The culture, in one line

**A check is not trusted until it has been observed failing.** Gates exit 0 (clean) / 1 (violation) /
2 (VOID — could not evaluate, never a pass). Every gate names the real incident it exists to catch.
Measure before you quote; `rg -uu` (plain grep here honours `.gitignore` and will lie to you).
