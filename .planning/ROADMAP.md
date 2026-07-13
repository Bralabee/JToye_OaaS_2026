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
- ✅ **v2.2 Production Hardening + Vendor Order Operations** — Phases 12-18 (finished 2026-07-11, PR #176)
- 🚧 **v2.3 Experience Overhaul + P2 Scale-out** — Phase 19+ (started 2026-07-11); backlog candidates: P2 #92-#94, 6 remaining P2 HANDOFF items, Work Orders F/J/K, Postgres PITR

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
- [x] **Phase 16.1: Pre-prod Hardening — Wave 0 Council Audit Fixes** — DONE 2026-04-28 on `feature/phase-16.1-pre-prod-hardening` (V35 migration + 19 new Java tests; ready for PR). Closes AUDIT-W0-01..05: OrderSseService cross-tenant SSE leak, /public/orders IDOR, Stripe webhook idempotency, reviews_tenant_write RLS rewrite, FORCE RLS on 9 tables.
- [x] **Phase 17: Vendor Order Detail + Stripe Refund Flow** - `/dashboard/orders/[id]` detail view, `POST /api/v1/orders/{id}/refund` endpoint wired to Stripe (stored-first idempotency), `REFUND_REQUESTED` state-machine transition, `refunds` table via Flyway V36 migration, refund webhook handlers reusing the Phase 16.1 dedup guard, and `order.refunded` published to RabbitMQ via the shared payment_event_outbox (UC-2 LOCKED `exchange` column added in V36) (VOPS-01, VOPS-02, VOPS-03) — 4 plans drafted 2026-04-27 (completed 2026-04-28)
- [x] **Phase 18: Vendor Onboarding — First Slice (MVP)** — `vendor_onboarding` state machine + gate chain (Flyway V43): auto-verify business (Companies House) + food-hygiene rating (FSA FHRS, `min-rating=2`) at signup, gate go-live on allergen completeness, state machine as sole writer of `Shop.published`. Seeded from `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md`. — planning via `/gsd plan-phase 18` (MVP mode) (completed 2026-07-11)

### 🚧 v2.3 Phases (in progress)

- [x] **Phase 19: Full-Frontend Experience Overhaul** — **DONE 2026-07-11** (9/9 plans, 4 waves, on `feature/19-ui-overhaul`). Closed the 15-item remediation backlog from the full-frontend UI audit (18-UI-REVIEW.md, whole-app 42/72): public landing page + information architecture (killed the `/` blind redirect, connected the 3 surfaces, de-orphaned every route), responsive dashboard shell, real product names in kitchen/orders, checkout delivery address + fee transparency (V45 migration — V44 stays reserved for #96), per-shop menus, and comparator-grade polish (Deliveroo/Just Eat storefront bar; Square/Toast dashboard bar). Registered UIX-01..06. Palette kept orange/emerald/slate (editorial redesign explicitly rejected), mobile-first; test invocations grew 921 → 988 (no regression), schema V43 → V45, docs-freshness green. Backlog #14 (error boundary) documented LEAVE-AS-IS.
- [ ] **Phase 20: AI-1 MCP Server (Read-Only Slice)** [MVP] — EPIC #209 Wave 2, issue #203. New TypeScript `mcp-server/` (official `@modelcontextprotocol/sdk`) exposing read-only tenant-scoped tools (list shops/products, read orders) over the existing core REST API; auth reuses #206 client-credentials + `catalog:read`/`orders:read` scopes (RLS is the boundary). Cross-tenant → empty/403 (RLS-proven test); RFC 7807 tool errors; live E2E on dev stack; README. Mutating tools + #205 webhooks deferred. — **NOT STARTED**

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

### Phase 16.1: Pre-prod Hardening — Wave 0 Council Audit Fixes — DONE 2026-04-28

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

**Plans:** 6 plans (all complete)

Plans:

- [x] 16.1-01-PLAN.md — V35 Flyway migration: processed_stripe_events table + reviews_tenant_write rewrite + FORCE RLS on 9 tables (AUDIT-W0-03/04/05) [Wave 1]
- [x] 16.1-02-PLAN.md — OrderSseService per-tenant emitter routing + OrderSseServiceTenantIsolationTest (AUDIT-W0-01) [Wave 1]
- [x] 16.1-03-PLAN.md — Mandatory `verify` param on /public/orders + PublicStorefrontControllerIdorTest (AUDIT-W0-02) [Wave 1]
- [x] 16.1-04-PLAN.md — PaymentService.handleWebhookEvent TOCTOU-safe idempotency guard + StripeWebhookIdempotencyIntegrationTest (AUDIT-W0-03) [Wave 2; depends on 16.1-01]
- [x] 16.1-05-PLAN.md — RlsContractTest + ReviewsRlsPolicyIntegrationTest (AUDIT-W0-04/05) [Wave 2; depends on 16.1-01]
- [x] 16.1-06-PLAN.md — REQUIREMENTS/CHANGELOG/STATE/ROADMAP closure: register AUDIT-W0-01..05, mark phase complete [Wave 3; depends on 01-05]

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

**Plans**: 4 plans

Plans:

- [x] 17-01-PLAN.md — V36 migration (refunds + refunds_aud + orders CHECK rewrite + payment_event_outbox.exchange) + Refund entity stack + RefundService stored-first idempotency + state-machine extension (REFUND_REQUESTED, REFUNDED, .end()) + unit tests [Wave 1]
- [x] 17-02-PLAN.md — PaymentEventOutbox.exchange field + Flusher per-row routing + RefundEvent record + RefundEventPublisher [Wave 1; consumes V36 column from 17-01]
- [x] 17-03-PLAN.md — RefundController (POST /orders/{id}/refund + Idempotency-Key + GET /orders/{id}/refunds) + PaymentService webhook refund.* cases (after Phase 16.1 dedup) + OrderDetailDto extension + GlobalExceptionHandler StripeException→502 + RefundWebhookHandlingIntegrationTest (Testcontainers) [Wave 2; depends on 17-01 + 17-02]
- [x] 17-04-PLAN.md — Frontend /dashboard/orders/[id] route + OrderDetailPanel extraction + RefundDialog (Zod + Idempotency-Key) + OrderStatus REFUNDED type extension + Jest unit tests + Playwright vendor-refund-flow E2E (port 3100) [Wave 3; depends on 17-03]

**UI hint**: yes

### Phase 18: Vendor Onboarding — First Slice

**Goal:** As a food vendor, I want to auto-verify my business and hygiene rating at signup, so that my shop goes live without manual review.
**Mode:** mvp
**Requirements**: VOB-01, VOB-02, VOB-03, VOB-04, VOB-05 (new capability; minted during Phase 18 planning — registered in REQUIREMENTS.md by Plan 18-06; scope seeded from `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md`)
**Success Criteria** (what must be TRUE):

  1. A tenant-scoped `vendor_onboarding` aggregate with a state machine (DRAFT → VERIFYING → ACTION_REQUIRED/PENDING_APPROVAL → APPROVED → LIVE) persists under RLS via Flyway V43, mirroring the Order state-machine pattern; the state machine is the sole writer of `Shop.published`.
  2. On submit, the `BUSINESS_VERIFIED` (Companies House) and `FOOD_HYGIENE_RATING` (FSA FHRS, threshold `min-rating=2`, header `x-api-version: 2`) gates run automatically, recording pass/fail + evidence; no/ambiguous FHRS match → `MANUAL_REVIEW` (never hard-fail).
  3. The `ALLERGEN_DATA_COMPLETE` gate blocks `GO_LIVE` until every product carries required allergen data (V41 fields).
  4. FHRS threshold and API base URLs are injected via config (`onboarding.*`, `${ENV:default}`), never literals.
  5. Tests added (state-machine transitions, RLS Testcontainers, gate evaluators) and `docs/metrics.json` bumped so the `docs-freshness` gate stays green.

**Plans**: 6 plans (MVP vertical slices; planned 2026-07-10)

Plans:

- [x] 18-01-PLAN.md — Persistence foundation: Flyway V43 (vendor_onboarding + _gate + _aud, FORCE RLS), enums, audited entities/repos, OnboardingProperties + config [Wave 1]
- [x] 18-02-PLAN.md — Submit slice: onboarding state machine (sole writer of Shop.published) + VendorOnboardingService + gate-chain registry/runner + create/submit/status API [Wave 2]
- [x] 18-03-PLAN.md — FOOD_HYGIENE_RATING gate: FhrsClient (x-api-version:2 + circuit breaker) + FhrsGate (min-rating=2, MANUAL_REVIEW fallback) [Wave 3]
- [x] 18-04-PLAN.md — BUSINESS_VERIFIED gate: CompaniesHouseClient (HTTP Basic + circuit breaker) + CompaniesHouseGate (active->PASSED, sole trader->WAIVED) [Wave 3]
- [x] 18-05-PLAN.md — ALLERGEN_DATA_COMPLETE gate (V41 fields) + POST /onboarding/go-live + Shop.published sole-writer hardening [Wave 3]
- [x] 18-06-PLAN.md — Cross-gate end-to-end proof + docs/metrics.json reconcile (docs-freshness) + REQUIREMENTS/ROADMAP/CHANGELOG closure [Wave 4]

### Phase 19: Full-Frontend Experience Overhaul

**Goal:** Every visitor — customer, prospective vendor, or operator — lands on a coherent product: a real front door routes them to their surface, every page is reachable through navigation, every flow is complete and comparator-grade (Deliveroo/Just Eat for storefront, Square/Toast for dashboard), on mobile first.
**Depends on**: Phase 18 (onboarding UI is the quality reference — replicate its pattern, do not regress it)
**Requirements**: UIX-01..UIX-06 (minted from `phases/18-vendor-onboarding-first-slice/18-UI-REVIEW.md` remediation backlog — register in REQUIREMENTS.md during planning)
**Success Criteria** (what must be TRUE):

  1. `/` renders a public landing page (no blind redirect) routing the 3 personas: order food → shop directory, run your food business → `/for-operators`, sign in → dashboard; shared public header/footer connects `/`, `/for-operators`, `/business-model-guide`, `/track`, `/shop` — zero orphan routes (every route ≥1 inbound nav link, verified by a link-graph test).
  2. All 11 dashboard routes usable at 390px (sidebar collapses to drawer/bottom nav); Playwright mobile viewport spec passes.
  3. Kitchen display and order detail show real product names on live orders — `OrderItem` snapshot populated at order creation; "Unknown Product" never renders for a product that exists.
  4. Checkout collects a delivery address (persisted via V45 — V44 stays reserved for #96) and shows the fee breakdown (subtotal + delivery + VAT) BEFORE payment; Playwright checkout e2e updated.
  5. Each shop renders its own menu: seeded/live products assigned `shop_id`, `ProductRepository` `IS NULL` fallback behaviour resolved deliberately (kept only if product-decision says tenant-wide items are a feature — then rendered as such, not duplicated).
  6. All 15 audit backlog items closed or explicitly deferred with reason; existing 921 logical test invocations stay green; palette stays orange/emerald/slate (no editorial/serif redesign).

**Plans**: 9 plans (4 waves) — planned 2026-07-11 (UI-SPEC + RESEARCH + PATTERNS + VALIDATION → 9 executable plans)

Plans:
**Wave 1**

- [x] 19-01-PLAN.md — Backend order-creation completeness: V45 fulfilment/address (+orders_aud mirror) + productName snapshot fix + backfill + GDPR address scrub + audited-write proof (UIX-03, UIX-04) [Wave 1]
- [x] 19-02-PLAN.md — Per-shop menus: dev-profile DemoDataSeeder (realistic UK data, shop_id assigned) + ProductRepository scoping (drop IS NULL bleed) + Testcontainers isolation (UIX-05) [Wave 1]
- [x] 19-03-PLAN.md — Public shell + persona landing page + sheet primitive + IA cross-links + static link-graph orphan guard (UIX-01) [Wave 1]

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 19-04-PLAN.md — Dashboard responsive shell: exported navigation + mobile bottom tab bar (4 + More sheet) + Playwright mobile spec (UIX-02) [Wave 2]
- [x] 19-05-PLAN.md — Marketing token re-skin (operator-pitch + business-model-guide) + /track guest lookup (no auth wall) + PublicShell (UIX-01) [Wave 2]
- [x] 19-06-PLAN.md — Checkout fulfilment toggle + UK address + fee-before-payment + empty-state centring + menu empty state (UIX-04) [Wave 2]
- [x] 19-07-PLAN.md — Kitchen + order-detail: badge-clip fix + elapsed cap + real product-name render + e2e (UIX-06) [Wave 2]

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 19-08-PLAN.md — Cross-cutting sweep: purple→amber/blue + text-[10px]→text-xs + VERIFY-FIRST 401 quiet + discipline test (UIX-06) [Wave 3]

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 19-09-PLAN.md — Closure: UIX-01..06 registration + docs-freshness reconcile (schema 45, 921 → 988) + full gate + browser UAT (UIX-06) [Wave 4]

**UI hint**: yes — UI-SPEC approved (checker 6/6)

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
| 16.1. Pre-prod Hardening (Wave 0) | v2.2 | 6/6 | Complete (ready for PR) | 2026-04-28 |
| 17. Vendor Order Detail + Stripe Refund Flow | v2.2 | 4/4 | Complete    | 2026-04-28 |
| 18. Vendor Onboarding — First Slice (MVP) | v2.2 | 7/7 | Complete    | 2026-07-11 |
| 19. Full-Frontend Experience Overhaul | v2.3 | 9/9 | Complete    | 2026-07-11 |
| 20. AI-1 MCP Server (Read-Only Slice) [MVP] | v2.3 | 0/5 | Planned | - |

### Phase 20: AI-1 MCP Server (Read-Only Slice)

**Goal:** As an external AI agent holding only a tenant-scoped Keycloak client-credentials token, I want to discover and read the platform (list shops, list products, read orders) through a Model Context Protocol server, so that I can integrate with J'Toye without hand-rolling an HTTP client and without any possibility of cross-tenant access.
**Mode:** mvp
**Requirements**: AI-1
**Tracks**: GitHub issue #203 — EPIC #209 Wave 2. Read-only first slice; mutating MCP tools and #205 outbound webhooks are separate later phases.
**Depends on:** #204 idempotency contract (DONE, PR #211) + #206 scoped machine credentials (DONE, PR #212). Both merged — `integration-catalog-ro` client + `catalog:read`/`orders:read` scopes already seeded in the realm template. No blocking dependencies remain.
**Plans:** 5 plans

**Success criteria (issue #203 acceptance criteria):**
1. New TypeScript `mcp-server/` workspace using the official `@modelcontextprotocol/sdk`, packaged as its own Docker container wired into docker-compose. No new Python/Go runtime.
2. Read-only MCP tools — list shops, list products, read orders — each wraps the EXISTING core REST API over HTTP (never touches Postgres directly; core-java + RLS stay the security boundary).
3. Auth reuses #206: Keycloak client-credentials token pass-through; the `tenant_id` claim drives RLS. Read tools map to `catalog:read` / `orders:read` scopes.
4. Agent with a tenant-scoped token can list shops/products and read orders via MCP against the dev stack — LIVE E2E, not just unit.
5. Cross-tenant access attempt returns empty/403 — RLS-proven, test included.
6. Tool errors surface RFC 7807 problem-detail, not raw stack traces.
7. README documents the client-credentials setup.

**Constraints:** feature branch → PR (never main); rebuild ALL containers before any live E2E claim; dev realm re-import is a pending operational step (`docs/security-scopes.md` §Re-import) required for the `integration-catalog-ro` client to exist in the running Keycloak; docs-freshness gate must stay green (baseline metrics 1208 / schema V50 — an MCP TS test surface may add to test counts; `scripts/docs-freshness.sh --write` is the arbiter).

Plans:
- [ ] 20-01-PLAN.md — Walking slice: mcp-server workspace + list_products end-to-end (stateless HTTP, Bearer pass-through, RFC 7807 errors) [W1]
- [ ] 20-02-PLAN.md — Widen tools: list_shops + read_orders (list/shop/detail) registered on the server [W2]
- [ ] 20-03-PLAN.md — Own Docker container + compose wiring + README + tenant-B seed for the RLS proof [W2]
- [ ] 20-04-PLAN.md — docs-freshness mcp test family + metrics regen + e2e.sh/e2e-rls.sh scripts [W3]
- [ ] 20-05-PLAN.md — Rebuild all + realm re-import + live E2E (read happy-path + cross-tenant RLS proof) [W4]
