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

- [🟡] **Phase 12: Spring Security Response Headers + Frontend CSP** - X-Frame-Options, HSTS (prod-only), X-Content-Type-Options, Referrer-Policy on Spring; CSP on Next.js (SEC-02, SEC-03) — 12-01 DONE, 12-02 operationally complete (Tasks 01-06 shipped; Task 07 manual cutover gate pending human verification)
- [🟡] **Phase 13: Guest Tracking Tenant Validation** - Application-layer tenant check in guest/session paths, closes cross-tenant spoof via path slug (SEC-01) — 13-01 DONE 2026-04-18, ready for PR
- [x] **Phase 14: Stock Race Fix + Summary Aggregation** - Move stock decrement into OrderStateMachine CONFIRM transition with optimistic lock; rewrite `getSummary()` to use DB-side `SUM/COUNT/GROUP BY` (CQ-01, CQ-02) — **DONE 2026-04-19, both plans shipped on feature/phase-14-stock-race-summary-aggregation, ready for PR**
- [🟡] **Phase 15: K8s NetworkPolicies + Sealed Secrets** - Pod-to-pod isolation policies + bitnami sealed-secrets runbook + batch `kubeseal` conversion script (INF-01, INF-02) — **DRAFTING COMPLETE 2026-04-18 on `feature/phase-15-k8s-networkpolicies-sealed-secrets` (6 commits). 6 NetworkPolicy manifests + offline validator + runbook + `seal-secrets.sh` + `secrets-template.yaml` legacy flag. Cluster-admin rollout pending — 4-step checklist in 15-01-SUMMARY.md. Actual layout: `k8s/staging/` + `k8s/production/` (not `k8s/overlays/*`).**
- [x] **Phase 16: Go Edge OpenAPI** - swaggo-annotated Gin handlers, `/openapi.json`, Swagger UI at `/docs`, CI validation of spec (DOC-01) — **DONE 2026-04-19 on `feature/phase-16-go-edge-openapi` (5 commits: aa6e292, 1d95bb3, 36a29fc, 197243b + metadata). 4 business routes documented (/health, /ready, /api/v1/sync/batch, /api/v1/webhooks/whatsapp), 7 response-type definitions, BearerAuth security scheme. CI installs `swag@v1.16.3`, runs `TestOpenAPISpec_Fresh` (regenerate-and-diff), + `@seriousme/openapi-schema-validator validate-api` (spec validity). Swagger 2.0 (not OpenAPI 3.0) — explicit tradeoff documented in 16-01-SUMMARY.md; v2.3 upgrade to swag v2 (OpenAPI 3.1) once stable.**
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
**Plans**: 2 plans (2 operationally complete; 1 manual gate pending)
  - [x] 12-01-PLAN.md — Spring Security response headers (SEC-03): HttpSecurity.headers() DSL with X-Frame-Options/X-Content-Type-Options/Referrer-Policy + profile-gated HSTS + MockMvc tests + Java-side header snapshot — **DONE 2026-04-18, see 12-01-SUMMARY.md (commits f428184, 68e903b, 953a25b, 09149c6)**
  - [🟡] 12-02-PLAN.md — Next.js CSP (SEC-02): next.config.mjs Report-Only CSP with Stripe/Keycloak/API/WS allowlist + Jest unit + snapshot + Playwright spec + port 3100 reconcile + manual enforce-cutover gate — **Tasks 01-06 DONE 2026-04-18 (commits 9163143, 0a19c4c, fddbc4e, 445f169, 30d94ee, 8baf065); Task 07 manual human-verify gate pending ≥1-week staging observation; see 12-02-SUMMARY.md**
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
  - [x] 13-01-PLAN.md — Service-layer tenant-match gate in PublicStorefrontService + ReviewService (resolvePublicShopForSlug helper, TenantAccessDeniedException → 403, CrossTenantSpoofIntegrationTest on Testcontainers Postgres, 4 unit tests on helper) — **DONE 2026-04-18, see 13-01-SUMMARY.md (commits 1f0b9aa, 1e7f357, e978939, 9c5309b, 300cae2)**
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
**Plans**: 2 plans (both complete)
  - [x] 14-01-PLAN.md — CQ-01 Stock Race Fix: V34 migration adding `@Version` to `products`, new `InsufficientStockException` → 409 via GlobalExceptionHandler, `StockService.decrementForOrder` with `@Retryable(ObjectOptimisticLockingFailureException.class, maxAttempts=3, backoff=50ms)` + `@Recover`, `OrderService.transitionOrder` rewire (save-after-decrement ordering fix), `adjustStockInBatch` + silent `Math.max(0,…)` clamp deleted, Testcontainers concurrent two-thread race test — **DONE 2026-04-19, see 14-01-SUMMARY.md (commits ec89443, c062f3a, ad02c98, fe27915, 20ebf24, c77fbdd, 98176a5)**
  - [x] 14-02-PLAN.md — CQ-02 getSummary DB Aggregation: 2 JPQL constructor-target queries (`FinancialAggregateRow` scalar + `FinancialVatRow` GROUP BY vatRate) with `COALESCE(SUM(CASE WHEN…), 0L)`, `ORDER BY ft.vatRate` + Java `Comparator` defense-in-depth, golden-file parity test (1k rows), EXPLAIN ANALYZE Index Scan assertion (10k rows + enable_seqscan=off), Hibernate `getPrepareStatementCount() == 2` pin, cross-tenant isolation test (raw-SQL disjointness + reflection-based no-explicit-tenant-WHERE — superuser RLS bypass environmental caveat documented) — **DONE 2026-04-19, see 14-02-SUMMARY.md (commits 635cc22, 06964ac, 83fa33a)**
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
**Plans**: 1 plan (consolidated — both requirements ship in one atomic DRAFT-ONLY plan)
  - [🟡] 15-01-PLAN.md — K8s NetworkPolicies (default-deny + 5 tier allow-lists, offline YAML + label-reference validator, live `kubectl --dry-run=server` documented for cluster admin) + Sealed Secrets runbook (`docs/runbooks/sealed-secrets.md`) + batch conversion script (`k8s/scripts/seal-secrets.sh`) + `secrets-template.yaml` legacy-flag header — **DRAFTING COMPLETE 2026-04-18, see 15-01-SUMMARY.md (commits 69710e7, 1ec1187, 5ac74b2, a3755b5, f59a0fb + metadata commit). Cluster-admin rollout pending: operator install, public-key export, plaintext→SealedSecret conversion, `kubectl apply -k` + functional verification — all 4 steps enumerated in SUMMARY.md.**
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
  - [x] 16-01-PLAN.md — swaggo annotations on 4 Gin handlers + generated Swagger 2.0 spec at `edge-go/docs/` + `/openapi.json` + Swagger UI at `/docs` + in-process freshness test + CI validation via `@seriousme/openapi-schema-validator` — **DONE 2026-04-19, see 16-01-SUMMARY.md. Swagger 2.0 vs OpenAPI 3.0 tradeoff: `swaggo/swag v1` emits 2.0; npm validator accepts both; v2.3 upgrade path to `swag v2` (OpenAPI 3.1) when stable. Pinned swaggo deps at `swag v1.16.3 / gin-swagger v1.6.0 / files v1.0.1` to keep edge-go on Go 1.22 (CLAUDE.md constraint).**
**UI hint**: no

### Phase 16.1: Pre-prod Hardening — Wave 0 Council Audit Fixes (INSERTED)

**Goal**: Close the 5 confirmed pre-prod blockers from the 2026-04-27 council audit before any production rollout >1 tenant or real payments. Eliminates 3 cross-tenant data-integrity bugs, adds Stripe webhook idempotency, and forces RLS on tables where superuser/owner bypass would defeat tenant isolation.
**Depends on**: Phase 16
**Requirements** (must land before Phase 17 Stripe refund work):
  1. **`OrderSseService` cross-tenant leak fix** — capture `TenantContext.get()` at `subscribe()`, filter `broadcast()` by event tenant. Regression test: `OrderSseServiceTenantIsolationTest` — two SSE subscriptions from different tenants, tenant A's transition NOT seen by tenant B.
     - File: `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:17,29-40`
  2. **Customer-orders IDOR mitigation** — make `verify` parameter mandatory on `/public/orders`; reject 400 without it. Test: `curl '/public/orders?email=victim@example.com'` → 400 not 200.
     - File: `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104`
  3. **Stripe webhook idempotency** — V35 migration adds `processed_stripe_events(event_id PRIMARY KEY, processed_at TIMESTAMPTZ)`. Guard `handleWebhookEvent` with TOCTOU-safe `INSERT ... ON CONFLICT DO NOTHING` at top. Test: same `event.id` POSTed twice → exactly one `financial_transactions` row.
     - File: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:113-132`
  4. **`reviews_tenant_write` RLS rewrite** — V35 migration: drop `app.tenant_id` reference (use `app.current_tenant_id`), drop the `customer_email` OR-clause, require `EXISTS (SELECT 1 FROM orders WHERE id=order_id AND customer_email=app.customer_email)`. Test: spam-review attempt with arbitrary `tenant_id` → INSERT rejected.
     - File: original policy in `db/migration/V27__customer_reviews.sql:31-36`; replacement migration V35.
  5. **`FORCE ROW LEVEL SECURITY`** on `reviews`, `shop_promotions`, `shop_announcements`, and all 6 `_aud` audit tables (V35 migration). Test: `SELECT relforcerowsecurity FROM pg_class WHERE relname IN (...)` → all true.
**Plans:** 6 plans

Plans:
- [ ] 16.1-01-PLAN.md — V35 Flyway migration: processed_stripe_events table + reviews_tenant_write rewrite + FORCE RLS on 9 tables (AUDIT-W0-03/04/05) [Wave 1]
- [ ] 16.1-02-PLAN.md — OrderSseService per-tenant emitter routing + OrderSseServiceTenantIsolationTest (AUDIT-W0-01) [Wave 1]
- [ ] 16.1-03-PLAN.md — Mandatory `verify` param on /public/orders + PublicStorefrontControllerIdorTest (AUDIT-W0-02) [Wave 1]
- [ ] 16.1-04-PLAN.md — PaymentService.handleWebhookEvent TOCTOU-safe idempotency guard + StripeWebhookIdempotencyIntegrationTest (AUDIT-W0-03) [Wave 2; depends on 16.1-01]
- [ ] 16.1-05-PLAN.md — RlsContractTest + ReviewsRlsPolicyIntegrationTest (AUDIT-W0-04/05) [Wave 2; depends on 16.1-01]
- [ ] 16.1-06-PLAN.md — REQUIREMENTS/CHANGELOG/STATE/ROADMAP closure: register AUDIT-W0-01..05, mark phase complete [Wave 3; depends on 01-05]

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
| 13. Guest Tracking Tenant Validation | v2.2 | 1/1 | Complete (ready for PR) | 2026-04-18 |
| 14. Stock Race Fix + Summary Aggregation | v2.2 | 0/2 | Not started | - |
| 15. K8s NetworkPolicies + Sealed Secrets | v2.2 | 1/1 | Drafting complete; cluster rollout pending | 2026-04-18 |
| 16. Go Edge OpenAPI | v2.2 | 1/1 | Complete (ready for PR) | 2026-04-19 |
| 17. Vendor Order Detail + Stripe Refund Flow | v2.2 | 0/3 | Not started | - |
