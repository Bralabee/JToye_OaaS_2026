# Roadmap: J'Toye OaaS — Milestone v2.2 (Production Hardening + Vendor Order Operations)

Multi-tenant UK retail SaaS for food vendors — shops, products, orders, customers, marketing, kitchen fulfilment.

## Overview

Milestone v2.2 closes the 8 highest-priority P2 security/quality items from the 2026-04-16 deep-audit backlog and ships vendor-facing order detail + Stripe refund flow (Work Order E from the state-of-codebase doc). Three work streams:

1. **Security + Spring/Next.js hardening** (SEC-01..03) — tenant validation on guest tracking, CSP headers on the frontend, security response headers on Spring.
2. **Correctness + data access** (CQ-01..02) — fix stock race at order confirmation with optimistic lock; replace `findAll()`-then-reduce summaries with DB-side aggregation.
3. **K8s + API docs** (INF-01..02, DOC-01) — NetworkPolicies + Sealed Secrets + Go edge OpenAPI spec.
4. **Vendor order operations — Work Order E** (VOPS-01..03) — order detail view, Stripe refund API integration, refund state transition in the order state machine.

11 requirements across 5 categories. 6 phases, continuing phase numbering from 11. Estimated ~3 weeks.

## Milestones

- ✅ **v2.0 Tier 3 Enhancements** — Phases 1-8 (shipped 2026-04-10, PR #27)
- ✅ **v2.1 Post-Audit Hardening + Storefront Completion** — Phases 9-11 (shipped 2026-04-16, archived 2026-04-18)
- 🚧 **v2.2 Production Hardening + Vendor Order Operations** — Phases 12-17 (in progress, started 2026-04-18)
- 📋 **v2.3+** — unscoped; likely candidates: 6 remaining P2 HANDOFF items, Work Orders F/J/K, Postgres PITR

## Phases

<details>
<summary>✅ v2.0 Tier 3 Enhancements (Phases 1-8) — SHIPPED 2026-04-10</summary>

- [x] Phase 1: API Versioning — Backend (1/1 plans) — completed 2026-04-07
- [x] Phase 2: API Versioning — Edge & Frontend (1/1 plans) — completed 2026-04-08
- [x] Phase 3: Vendor Marketing Backend (2/2 plans) — completed 2026-04-08
- [x] Phase 4: Vendor Dashboard UI (1/1 plans) — completed 2026-04-08
- [x] Phase 5: KDS Security & WebSocket Foundation (1/1 plans) — completed 2026-04-08
- [x] Phase 6: KDS Event Pipeline (1/1 plans) — completed 2026-04-08
- [x] Phase 7: Kitchen Display UI (1/1 plans) — completed 2026-04-09
- [x] Phase 8: Test Coverage Closure (2/2 plans) — completed 2026-04-09

v2.0 shipped before `/gsd-complete-milestone` was adopted — no archive files. Source of truth: PR #27 (commit `955e641`).

</details>

<details>
<summary>✅ v2.1 Post-Audit Hardening + Storefront Completion (Phases 9-11) — SHIPPED 2026-04-16</summary>

- [x] Phase 9: Repository Secrets + Alerting (3/3 plans) — completed 2026-04-15
- [x] Phase 10: Storefront Marketing Render + Missing Customer Routes (3/3 plans) — completed 2026-04-16
- [x] Phase 11: STOMP Broker Relay for Horizontal Scale (3/3 plans) — completed 2026-04-16

Archived: `milestones/v2.1-ROADMAP.md` | `milestones/v2.1-REQUIREMENTS.md` | `milestones/v2.1-MILESTONE-AUDIT.md`

</details>

### 🚧 v2.2 Phases (in progress)

- [ ] **Phase 12: Spring Security Response Headers + Frontend CSP** - X-Frame-Options, HSTS (prod-only), X-Content-Type-Options, Referrer-Policy on Spring; CSP on Next.js (SEC-02, SEC-03)
- [ ] **Phase 13: Guest Tracking Tenant Validation** - Application-layer tenant check in guest/session paths, closes cross-tenant spoof via path slug (SEC-01)
- [ ] **Phase 14: Stock Race Fix + Summary Aggregation** - Move stock decrement into OrderStateMachine CONFIRM transition with optimistic lock; rewrite `getSummary()` to use DB-side `SUM/COUNT/GROUP BY` (CQ-01, CQ-02)
- [ ] **Phase 15: K8s NetworkPolicies + Sealed Secrets** - Pod-to-pod isolation policies + bitnami sealed-secrets controller with kubeseal conversion of the existing Secret manifests (INF-01, INF-02)
- [ ] **Phase 16: Go Edge OpenAPI** - swaggo-annotated Gin handlers, `/openapi.json`, Swagger UI at `/docs`, CI validation of spec (DOC-01)
- [ ] **Phase 17: Vendor Order Detail + Stripe Refund Flow** - `/dashboard/orders/[id]` detail view, `POST /api/v1/orders/{id}/refund` endpoint wired to Stripe, refund state transition in Order state machine with Flyway V34 migration and RabbitMQ `order.refunded` event (VOPS-01, VOPS-02, VOPS-03)

## Phase Details

### Phase 12: Spring Security Response Headers + Frontend CSP
**Goal**: Every HTTP response from both Spring Boot and Next.js carries the baseline browser-security headers that block clickjacking, MIME-type sniffing, weak referrers, and inline script/XSS vectors.
**Depends on**: Nothing (standalone — can ship first)
**Requirements**: SEC-02, SEC-03
**Success Criteria** (what must be TRUE):
  1. `GET /api/v1/shops` 200 response includes `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin` (verified by MockMvc assertion + curl against running dev stack)
  2. `Strict-Transport-Security` header present on Spring responses in `prod` profile only, absent in `dev` (profile-based `HttpSecurity.headers().hsts()` configuration)
  3. Next.js responses (homepage, `/shop/[slug]`, `/dashboard`) include a `Content-Security-Policy` header whose `default-src 'self'`, `script-src` allows the minimum set needed for NextAuth + Stripe, `frame-ancestors 'none'`
  4. Playwright e2e passes with no browser-console CSP violations across the full storefront + dashboard flow
  5. Header snapshot test committed to CI (fails if a header regresses)
**Plans**: 2 plans (1 complete, 1 pending)
  - [x] 12-01-PLAN.md — Spring Security response headers (SEC-03): HttpSecurity.headers() DSL with X-Frame-Options/X-Content-Type-Options/Referrer-Policy + profile-gated HSTS + MockMvc tests + Java-side header snapshot — **DONE 2026-04-18, see 12-01-SUMMARY.md (commits f428184, 68e903b, 953a25b, 09149c6)**
  - [ ] 12-02-PLAN.md — Next.js CSP (SEC-02): next.config.mjs Report-Only CSP with Stripe/Keycloak/API/WS allowlist + Jest unit + snapshot + Playwright spec + port 3100 reconcile + manual enforce-cutover gate
**UI hint**: no

### Phase 13: Guest Tracking Tenant Validation
**Goal**: Anonymous/session-based requests cannot slip across tenant boundaries by manipulating the URL slug or session cookie — application-layer rejects mismatches with 403 before any tenant-scoped data touches the response.
**Depends on**: Phase 12 (share security-test scaffolding; non-blocking but natural ordering)
**Requirements**: SEC-01
**Success Criteria** (what must be TRUE):
  1. A guest session established on tenant A cannot retrieve tenant B data via `/public/shops/{B-slug}/...` — rejected with 403 and structured audit log entry
  2. Legitimate browse flow on tenant A still passes (no regression in Playwright storefront e2e)
  3. Cross-tenant spoof attempt is covered by an integration test that seeds two tenants + attempts the spoof + asserts 403
  4. `GuestTrackingService` (or equivalent) has explicit unit tests for tenant-match, tenant-mismatch, and missing-tenant paths
**Plans**: 1 plan
**UI hint**: no

### Phase 14: Stock Race Fix + Summary Aggregation
**Goal**: The platform cannot oversell stock under concurrent order confirmations, and summary endpoints (`getSummary()` on FinancialTransactionService/OrderService) scale to 10k+ rows without loading the full table into memory.
**Depends on**: Nothing (backend-only, can run in parallel with 12/13)
**Requirements**: CQ-01, CQ-02
**Success Criteria** (what must be TRUE):
  1. Two concurrent `CONFIRM` events on the last-in-stock product: exactly one succeeds, the other throws `InsufficientStockException` (Testcontainers integration test exercises the real Postgres optimistic lock path)
  2. Stock decrement lives inside the `OrderStateMachine` CONFIRM transition, not in `OrderService.createOrder` (verified by code search + behavioral test)
  3. `getSummary()` returns the same output on a seeded 1k-row fixture as the previous `findAll()`+reduce implementation (golden-file comparison)
  4. `getSummary()` query plan uses an index (verified by `EXPLAIN ANALYZE` in the integration test)
  5. No regression in `OrderServiceTest` / `FinancialTransactionServiceTest` existing assertions
**Plans**: 2 plans
**UI hint**: no

### Phase 15: K8s NetworkPolicies + Sealed Secrets
**Goal**: Production cluster enforces pod-to-pod isolation so a compromised pod cannot pivot laterally, and secrets live in git as sealed ciphertext rather than base64-encoded plaintext.
**Depends on**: Nothing from earlier v2.2 phases (infra-only); requires bitnami-labs/sealed-secrets controller installed in the cluster before INF-02 ships
**Requirements**: INF-01, INF-02
**Success Criteria** (what must be TRUE):
  1. `k8s/base/networkpolicies/` directory contains policies enforcing: frontend↔core-java only, core-java↔(postgres, redis, rabbitmq, keycloak, minio, alertmanager), infra pods only accept from core-java, deny-all for all other combinations
  2. `kubectl --dry-run=server -k k8s/overlays/staging` applies cleanly; no policy references a non-existent pod label
  3. `k8s/base/secrets-template.yaml` replaced by `SealedSecret` manifests encrypted via `kubeseal` against the staging cluster public key; documentation for key rotation committed at `docs/runbooks/sealed-secrets.md`
  4. Dev/local docker-compose workflow unchanged (dev uses `.env` files, not k8s)
  5. CI validation: `kubeseal --dry-run` round-trips a plaintext Secret to SealedSecret and back without error
**Plans**: 2 plans
**UI hint**: no

### Phase 16: Go Edge OpenAPI
**Goal**: Every Go edge gateway route is documented in a machine-readable OpenAPI 3.0 spec served at `/openapi.json`, with an interactive Swagger UI at `/docs`, so downstream teams (frontend, mobile, integration partners) don't have to read Go source to learn the API surface.
**Depends on**: Nothing (edge-only)
**Requirements**: DOC-01
**Success Criteria** (what must be TRUE):
  1. `GET /openapi.json` returns a valid OpenAPI 3.0 document (validated by `openapi-spec-validator` in CI)
  2. Every existing Gin route in `cmd/edge/main.go` and `internal/` has a matching `@Summary`/`@Router`/`@Success`/`@Failure` swaggo annotation (line-count assertion: route count == annotation count)
  3. `GET /docs` renders Swagger UI with all routes browsable
  4. Go edge test suite grows to include a spec-freshness test (regenerate spec in CI, diff vs committed copy, fail on drift)
**Plans**: 1 plan
**UI hint**: no

### Phase 17: Vendor Order Detail + Stripe Refund Flow
**Goal**: Vendors can open any order from `/dashboard/orders`, see its full context (items, payment, transitions), and issue a full or partial Stripe refund with a structured reason — and the refund flows through Stripe, the database, the order state machine, and the RabbitMQ event bus consistently.
**Depends on**: Phase 14 (shares OrderStateMachine changes — STMP refund transition is added after CQ-01 lands to avoid merge conflicts)
**Requirements**: VOPS-01, VOPS-02, VOPS-03
**Success Criteria** (what must be TRUE):
  1. `/dashboard/orders/[id]` renders all order context: header (status, timestamps, state-transition timeline), customer block, item lines (product, qty, modifiers, price), payment block (Stripe payment intent, refund history)
  2. `POST /api/v1/orders/{id}/refund` with `{ amount_pennies, reason, note? }` creates a Stripe refund via `Refund.create`, persists a `Refund` entity via Flyway V34 migration, and publishes `order.refunded` to RabbitMQ
  3. `OrderStateMachine` accepts `REFUND_REQUESTED` event transitioning `CONFIRMED|PREPARING|READY|COMPLETED → REFUNDED`; second invocation on REFUNDED order is idempotent (no exception, no-op)
  4. Stripe webhook `charge.refunded` / `refund.updated` events update `Refund.status` in the database (webhook handler integration test with fixture payload)
  5. Playwright e2e: vendor logs in → navigates to `/dashboard/orders` → clicks row → lands on `/dashboard/orders/[id]` → clicks refund → enters partial amount → confirms → Stripe test-mode refund succeeds → UI updates to show `REFUNDED` state and refund history
**Plans**: 3 plans
**UI hint**: yes

## Progress

**Execution Order:**
v2.0 + v2.1: shipped (phases 1-11).
v2.2: Phase 12 first (broadest blast radius — all responses), then 13 + 14 can run in parallel (both backend, independent subsystems), then 15 + 16 can run in parallel (infra/edge, both standalone), Phase 17 depends on 14 (shares state machine surface).

Suggested wave layout:
- Wave 1: Phase 12
- Wave 2: Phases 13, 14 (parallel)
- Wave 3: Phases 15, 16 (parallel)
- Wave 4: Phase 17

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. API Versioning — Backend | v2.0 | 1/1 | Complete | 2026-04-07 |
| 2. API Versioning — Edge & Frontend | v2.0 | 1/1 | Complete | 2026-04-08 |
| 3. Vendor Marketing Backend | v2.0 | 2/2 | Complete | 2026-04-08 |
| 4. Vendor Dashboard UI | v2.0 | 1/1 | Complete | 2026-04-08 |
| 5. KDS Security & WebSocket Foundation | v2.0 | 1/1 | Complete | 2026-04-08 |
| 6. KDS Event Pipeline | v2.0 | 1/1 | Complete | 2026-04-08 |
| 7. Kitchen Display UI | v2.0 | 1/1 | Complete | 2026-04-09 |
| 8. Test Coverage Closure | v2.0 | 2/2 | Complete | 2026-04-09 |
| 9. Repository Secrets + Alerting | v2.1 | 3/3 | Complete | 2026-04-15 |
| 10. Storefront Marketing + Missing Customer Routes | v2.1 | 3/3 | Complete | 2026-04-16 |
| 11. STOMP Broker Relay for Horizontal Scale | v2.1 | 3/3 | Complete | 2026-04-16 |
| 12. Spring Security Response Headers + Frontend CSP | v2.2 | 0/2 | Not started | - |
| 13. Guest Tracking Tenant Validation | v2.2 | 0/1 | Not started | - |
| 14. Stock Race Fix + Summary Aggregation | v2.2 | 0/2 | Not started | - |
| 15. K8s NetworkPolicies + Sealed Secrets | v2.2 | 0/2 | Not started | - |
| 16. Go Edge OpenAPI | v2.2 | 0/1 | Not started | - |
| 17. Vendor Order Detail + Stripe Refund Flow | v2.2 | 0/3 | Not started | - |
