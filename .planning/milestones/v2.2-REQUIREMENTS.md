# Requirements: J'Toye OaaS — Milestone v2.2 (Production Hardening + Vendor Order Operations)

**Defined:** 2026-04-18
**Milestone:** v2.2
**Source:** `HANDOFF.md` (P2 deep-audit backlog, 2026-04-16) + `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md §11 Work Order E`
**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## v1 Requirements

v2.2 scopes 11 requirements across 5 categories. All have file:line evidence in the source documents. Effort estimate: ~3 weeks.

### Security hardening (SEC)

Close the immediate application-security gaps flagged during the 2026-04-16 deep audit.

- [x] **SEC-01**: Application-layer tenant validation for guest tracking. Today guest/session requests (anonymous cart population, unauthenticated product listing) go through `TenantFilter` which populates `TenantContext` from the path slug without validating the request belongs to the tenant it claims. Add an explicit application-layer check in `GuestTrackingService` (or equivalent) that compares session-bound tenant against the path slug and rejects mismatches with 403. Tests cover: legitimate single-tenant browse, cross-tenant spoof attempt (403), no-tenant request (TenantContext unset → 401/400). Source: HANDOFF.md P2 "Add application-layer tenant validation for guest tracking". **DONE 2026-04-18 in Plan 13-01 (commits 1f0b9aa, 1e7f357, e978939, 9c5309b, 300cae2). Resolved as PublicStorefrontService.resolvePublicShopForSlug helper + mirror in ReviewService; no `GuestTrackingService` existed — slug is the tenant signal, JWT tenant_id is the upstream signal. 6 MockMvc+Testcontainers integration tests + 4 unit tests green.**
- [🟡] **SEC-02**: CSP (Content Security Policy) headers on Next.js frontend responses via `next.config.mjs` or middleware. Minimum directives: `default-src 'self'`, `script-src 'self' 'nonce-<random>'` (or `'unsafe-inline'` only if NextAuth requires it — verify), `style-src 'self' 'unsafe-inline'` (Tailwind), `img-src 'self' data: https:` (S3/MinIO + product images), `connect-src 'self' <api-origin> <ws-origin>`, `frame-ancestors 'none'`. Tests cover: response header presence on homepage, storefront, dashboard; CSP violations surface in browser console during Playwright run (no regressions). Source: HANDOFF.md P2 "Add CSP headers". **Operationally complete 2026-04-18 (Plan 12-02 Tasks 01-06 shipped: Content-Security-Policy-Report-Only via next.config.mjs async headers() + Jest CI gate + Playwright local/staging spec). Enforce cutover (Task 12-02-07) pending ≥1-week staging observation.**
- [x] **SEC-03**: Security response headers on Spring Boot responses via `HttpSecurity.headers()` or a `WebMvcConfigurer` filter: `X-Frame-Options: DENY`, `Strict-Transport-Security: max-age=31536000; includeSubDomains` (prod profile only; dev profile omits to allow http), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`. Tests cover: header presence on `/api/v1/shops` 200 response, header presence on 4xx responses, HSTS absent in `dev` profile and present in `prod` profile. Source: HANDOFF.md P2 "Add security headers to Spring responses". **DONE 2026-04-18 in Plan 12-01 (commits f428184, 68e903b, 953a25b, 09149c6).**

### Code quality (CQ)

Fix two known correctness bugs that were deferred from deep-audit P1.

- [x] **CQ-01**: Stock race condition fix. Currently `OrderService.createOrder()` reads `product.stock`, checks availability, and creates the order — but the stock decrement happens later (or not at all in some paths), allowing two concurrent orders to both pass the check and oversell. Fix: move the stock decrement into the `OrderStateMachine` `CONFIRM` transition, gated by optimistic lock on the Product entity (@Version already exists per V32 migration). Tests cover: serialized orders decrement once each; two concurrent confirmations on last-item-in-stock — only one succeeds, other gets `InsufficientStockException`; Testcontainers test exercises the real Postgres optimistic lock path. Source: HANDOFF.md P2 "Fix stock race condition — validate at confirmation, not creation (CQ-01)". **Shipped:** Phase 14 Plan 01 (2026-04-19) — see 14-01-SUMMARY.md.
- [x] **CQ-02**: `getSummary()` DB aggregation. Today `FinancialTransactionService.getSummary()` (or equivalent OrderService / ShopService summary method — grep to locate) calls `findAll()` and reduces in-memory. Replace with a JPQL or native-query `SUM()`/`COUNT()`/`GROUP BY` that returns aggregated rows directly. Tests cover: matching output vs old implementation on a seeded dataset; query plan uses indexes (verify with `EXPLAIN ANALYZE` in the integration test); performance measurable improvement on a 10k-row fixture. Source: HANDOFF.md P2 "Fix getSummary() to use DB aggregation instead of findAll() (CQ-02)". **Shipped:** Phase 14 Plan 02 (2026-04-19) — see 14-02-SUMMARY.md.

### Infrastructure (INF)

Close K8s-level security gaps.

- [🟡] **INF-01**: K8s NetworkPolicies in `k8s/base/` that enforce: `frontend` can only talk to `core-java` and its own CDN/image origins; `core-java` can talk to `postgres`, `redis`, `rabbitmq`, `keycloak`, `minio`, `alertmanager`, and outbound Stripe API only; `postgres`/`redis`/`rabbitmq`/`minio` only accept connections from `core-java` + maintenance tooling; deny-all for every other pod-to-pod combination. Apply via overlay in `k8s/staging/` and `k8s/production/` (actual layout — ROADMAP originally said `k8s/overlays/*`, updated to match disk). Tests cover: policy manifests validate with `kubectl --dry-run`; policy count matches spec; CIDR blocks for Stripe API documented. Source: HANDOFF.md P2 "Add K8s NetworkPolicies (INFRA-17)". **DRAFTING COMPLETE 2026-04-18 in Plan 15-01 (commits 69710e7, 1ec1187, 5ac74b2 + metadata). 6 NetworkPolicy manifests (default-deny + 4 tier allow-lists + placeholder) in `k8s/base/networkpolicies/`, wired into base kustomization. Offline validator `k8s/scripts/validate-networkpolicies.py` passes (6 files, 13 podSelector refs, all resolve). Live `kubectl --dry-run=server apply -k k8s/staging/` remains a manual cluster-admin step per the README. Stripe egress uses `0.0.0.0/0:443` with RFC1918 in except[] — documented rationale (Stripe has no stable CIDR allowlist).**
- [🟡] **INF-02**: K8s Sealed Secrets using `bitnami-labs/sealed-secrets` controller. Convert `k8s/base/secrets-template.yaml` from plain `Secret` to `SealedSecret` manifests. Document the key-rotation procedure in `docs/runbooks/sealed-secrets.md` (backup controller key, restore, rotate-on-key-compromise). Tests cover: sealed manifest decrypts on the cluster; `kubeseal` CLI reproducibility from a plaintext file; fallback plain Secret still supported for dev (unchanged). Source: HANDOFF.md P2 "Add K8s Sealed Secrets (INFRA-11)". **DRAFT-ONLY COMPLETE 2026-04-18 in Plan 15-01 (commits a3755b5, f59a0fb + metadata). Shipped `docs/runbooks/sealed-secrets.md` (11-section operational runbook including normal + emergency key rotation, rollback, off-cluster key backup), `k8s/scripts/seal-secrets.sh` (batch `kubeseal` converter with yq multi-doc split), and LEGACY header on `k8s/base/secrets-template.yaml`. Cannot install the controller or seal actual manifests from here — requires cluster-admin access. Operator install + first conversion is a 4-step checklist in 15-01-SUMMARY.md.**

### API documentation (DOC)

Fill the last surface-area documentation gap.

- [x] **DOC-01**: OpenAPI spec for the Go edge gateway. Use `swaggo/swag` or `go-swagger` to annotate Gin handlers (`cmd/edge/main.go` and `internal/`) and generate a `/openapi.json` endpoint + Swagger UI at `/docs`. Covers all edge routes: `/health`, `/ready`, `/sync/batch`, `/orders`, `/whatsapp`, `/products/search` (if present). Tests cover: spec is valid OpenAPI 3.0 per `openapi-spec-validator` npm tool in CI; every Gin route has a corresponding `@Summary`/`@Router` annotation (line-count assertion). Source: HANDOFF.md P2 "Generate OpenAPI for Go gateway". **DONE 2026-04-19 in Plan 16-01 (commits aa6e292, 1d95bb3, 36a29fc, 197243b + metadata). 4 Gin routes annotated (/health, /ready, /api/v1/sync/batch, /api/v1/webhooks/whatsapp — the only routes actually on the edge; HANDOFF's reference to /orders and /products/search was stale and those are direct core-java surface). Generated Swagger 2.0 spec at `edge-go/docs/swagger.json` (7 response-type definitions, BearerAuth security scheme); served at GET /openapi.json (embedded, no disk read) + Swagger UI at GET /docs/* with GET /docs → 301 /docs/index.html redirect. CI installs `swag@v1.16.3` before `go test` so `TestOpenAPISpec_Fresh` runs (regenerate-and-diff), then invokes `@seriousme/openapi-schema-validator validate-api` (npm) for spec validity. Swagger 2.0 (not OpenAPI 3.0) is an explicit tradeoff: swaggo/swag v1 emits 2.0, swag v2 is alpha; the npm validator accepts both — documented in 16-01-SUMMARY.md + 16-RESEARCH.md; v2.3 upgrade to swag v2 when stable. Pinned swaggo at older versions (swag v1.16.3 / gin-swagger v1.6.0 / files v1.0.1) so `go` directive stays at 1.22 per CLAUDE.md.**

### Pre-prod hardening (AUDIT-W0) — added 2026-04-27

Five pre-prod blockers identified by the 2026-04-27 council audit (`docs/audit/COUNCIL-AUDIT-2026-04-27.md`). Inserted into Phase 16.1 (between original Phases 16 and 17). Source: council audit + 8 specialist remediation docs (`docs/audit/remediation/`); findings re-verified against live code on 2026-04-27.

- [x] **AUDIT-W0-01**: `OrderSseService` cross-tenant SSE leak — every dashboard subscriber received every tenant's order state changes. Fix: per-tenant `ConcurrentHashMap<UUID, Set<SseEmitter>>` keyed at `subscribe()` from `TenantContext.get()`; `broadcast()` filters by `event.tenantId()`. Source: `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:17,29-40`. **Shipped:** Phase 16.1 Plan 02 — see 16.1-02-SUMMARY.md. Regression: `OrderSseServiceTenantIsolationTest`.

- [x] **AUDIT-W0-02**: `/public/orders` IDOR — `verify` order-number was opt-in; bare `?email=` returned the customer's full order list. Fix: `verify` parameter is now mandatory at the controller; `trackOrder(verify, email)` is called unconditionally before `getCustomerOrders(email)`; missing/blank → 400. Source: `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104`. **Shipped:** Phase 16.1 Plan 03 — see 16.1-03-SUMMARY.md. Regression: `PublicStorefrontControllerIdorTest` (4 MockMvc tests). Phase 2 magic-link + rate limiter is deferred per CONTEXT `<deferred>`.

- [x] **AUDIT-W0-03**: Stripe webhook idempotency — `handleWebhookEvent` had no de-dup; retried events double-wrote `financial_transactions` and double-published the order state change. Fix: V35 adds `processed_stripe_events(event_id TEXT PK, processed_at TIMESTAMPTZ)`; `handleWebhookEvent` runs TOCTOU-safe `INSERT ... ON CONFLICT DO NOTHING` immediately after signature verification — 0 rows affected ⇒ retry, return early. Source: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:113-132` + `core-java/src/main/resources/db/migration/V35__rls_idempotency_force_rls.sql`. **Shipped:** Phase 16.1 Plans 01 + 04 — see 16.1-04-SUMMARY.md. Regression: `StripeWebhookIdempotencyIntegrationTest` (Testcontainers Postgres).

- [x] **AUDIT-W0-04**: `reviews_tenant_write` RLS policy rewrite — V27's policy read the wrong GUC name (`app.tenant_id` instead of canonical `app.current_tenant_id`) and the OR-clause `current_setting('app.customer_email', true) = customer_email` allowed anyone setting `app.customer_email` to insert a review on any tenant_id and any order_id. Fix: V35 drops the buggy policy and recreates with canonical GUC name + EXISTS-on-orders ownership proof in the customer branch. Source: original at `core-java/src/main/resources/db/migration/V27__customer_reviews.sql:31-36`; replacement in V35. **Shipped:** Phase 16.1 Plans 01 + 05 — see 16.1-05-SUMMARY.md. Regression: `ReviewsRlsPolicyIntegrationTest` (5 behavioural tests on Testcontainers Postgres).

- [x] **AUDIT-W0-05**: FORCE ROW LEVEL SECURITY on 9 tables — `reviews`, `shop_promotions`, `shop_announcements`, and the 6 `_aud` audit tables had ENABLE but not FORCE; the table-owner DB role used by some Flyway migrations and Envers writes could bypass tenant isolation. Fix: V35 issues `ALTER TABLE ... FORCE ROW LEVEL SECURITY` for all 9. Source: per-table evidence in `docs/audit/sources/03-database-engineer.md`; replacement in V35. **Shipped:** Phase 16.1 Plans 01 + 05 — see 16.1-05-SUMMARY.md. Regression: `RlsContractTest` (walks `pg_class`, asserts ENABLE+FORCE on every non-exempt public table; future migrations missing RLS+FORCE break the build).

### Vendor operations (VOPS) — Work Order E

Ship the last piece of the vendor order-management loop.

- [x] **VOPS-01**: `/dashboard/orders/[id]` order detail view in the Next.js dashboard. Renders: order header (number, status, created/updated timestamps, state transitions timeline), customer block (name, email, phone — pulls via `OrderService.getCustomerForOrder`), item lines (product name, qty, unit price, subtotal, modifiers), payment block (Stripe payment intent ID, status, amount, refund history), action panel (status advance buttons, issue refund). Uses existing `DashboardLayout`, Radix UI primitives, TailwindCSS. Tests cover: Jest — renders with minimal fixture, renders with full fixture (refunded state), empty/loading/error states; Playwright — click from `/dashboard/orders` list lands on correct detail page, action panel visible to vendor role.
- [x] **VOPS-02**: `POST /api/v1/orders/{id}/refund` endpoint wired to Stripe refund API. Request body: `{ amount_pennies?: number, reason: "requested_by_customer" | "duplicate" | "fraudulent" | "other", note?: string }`. Behavior: partial refund if `amount_pennies` < order total, full refund if absent; uses `RefundService.createRefund` → `Stripe.Refund.create(paymentIntentId, amount)`; persists a `Refund` entity (new Flyway V34 migration — `order_id` FK, `amount_pennies`, `reason`, `stripe_refund_id`, `status`, `created_at`); publishes `order.refunded` event to RabbitMQ. Stripe webhook handler updates the `Refund.status` on `charge.refunded` / `refund.updated` events. Tests cover: full refund success path, partial refund success path, invalid amount (> order total) rejected with 400, Stripe API error propagates as 502, webhook update cycle integration test.
- [x] **VOPS-03**: Refund state transition added to `OrderStateMachine`. Event `REFUND_REQUESTED` transitions `CONFIRMED`, `PREPARING`, `READY`, `COMPLETED` → `REFUNDED`. Event is idempotent (second invocation on a `REFUNDED` order is a no-op, not an `InvalidStateTransitionException`). Transition is audited by Hibernate Envers (existing setup) and logs a structured SLF4J INFO entry. Tests cover: state machine unit tests per transition source; idempotency test; integration test that a refund via VOPS-02 triggers the state transition automatically.

### Vendor onboarding (VOB) — added 2026-07-10

The vendor-onboarding first slice (Phase 18): a tenant can self-onboard through automatic compliance gates and, when all gates pass, go live **without manual review**. Requirements derived from the ROADMAP Phase 18 success criteria; all delivered this phase.

- [x] **VOB-01**: Tenant-scoped `vendor_onboarding` aggregate + state machine under RLS (Flyway V43, ENABLE+FORCE + Envers `_aud` mirrors), the **sole writer of `Shop.published`**, with `onboarding.auto-approve` auto-advancing `PENDING_APPROVAL → APPROVED` so a fully-passing onboarding can reach live without manual review (the APPROVE guard still enforces all mandatory gates). Tests: state-machine unit + RLS Testcontainers + submit/go-live integration + cross-gate e2e. **DONE 2026-07-11 — Phase 18 (Plans 18-01, 18-02, 18-05).**
- [x] **VOB-02**: Automatic `BUSINESS_VERIFIED` (Companies House, HTTP-Basic key-as-username) + `FOOD_HYGIENE_RATING` (FSA FHRS, `min-rating`=2, mandatory `x-api-version: 2` header) gates evaluated on submit, recording pass/fail + provider evidence, degrading to `MANUAL_REVIEW` on no/ambiguous match or an API outage (never a silent pass or hard-fail). Each is circuit-broken (`@CircuitBreaker`) with an explicit timeout. **DONE 2026-07-11 — Phase 18 (Plans 18-03, 18-04).**
- [x] **VOB-03**: `ALLERGEN_DATA_COMPLETE` gate blocks `GO_LIVE` until every product carries the required V41 allergen data (durability type + shelf life + ingredients), aligned to `ProductLabelService.validatePpdsData` (Natasha's Law). **DONE 2026-07-11 — Phase 18 (Plan 18-05).**
- [x] **VOB-04**: FHRS min-rating threshold + both provider API base URLs injected via `onboarding.*` config (`${ENV:default}`), never literals; the Companies House key is redacted in `toString` and never logged. **DONE 2026-07-11 — Phase 18 (Plan 18-01).**
- [x] **VOB-05**: Test coverage (state-machine, RLS Testcontainers, gate evaluators, `onboarding.auto-approve` toggle both ways, cross-gate fully-automatic e2e) + `docs/metrics.json` reconciliation keeping the `docs-freshness` CI gate green (schema 43, 15 controllers). **DONE 2026-07-11 — Phase 18 (Plans 18-01..18-06).**

### Experience overhaul (UIX) — v2.3 — added 2026-07-11

The full-frontend experience overhaul (Phase 19): close the 15-item remediation backlog from the whole-app UI audit (`phases/18-vendor-onboarding-first-slice/18-UI-REVIEW.md`, whole-app 42/72) so every visitor lands on a coherent, comparator-grade product on mobile first. Requirements registered verbatim from the ROADMAP Phase 19 success criteria; palette stays orange/emerald/slate (the editorial/serif redesign of PR #49 was explicitly rejected). All delivered across the 9 plans of Phase 19.

- [x] **UIX-01**: `/` renders a public landing page (no blind redirect) routing the 3 personas: order food → shop directory, run your food business → `/for-operators`, sign in → dashboard; a shared public header/footer connects `/`, `/for-operators`, `/business-model-guide`, `/track`, `/shop` — zero orphan routes (every route ≥1 inbound nav link, verified by a link-graph test). Closes backlog #4 (no landing page), #5 (orphan routes / cross-links), #9 (`/track` guest lookup, shared with UIX via 19-05), #7 (marketing hardcoded-hex re-skin onto tokens). **DONE 2026-07-11 — Phase 19 (Plans 19-03 public shell + persona landing + link-graph guard; 19-05 marketing token re-skin + `/track` guest lookup + PublicShell).**
- [x] **UIX-02**: All 11 dashboard routes usable at 390px (the fixed `w-64` sidebar collapses to a drawer/bottom nav under `md:`); the Playwright mobile-viewport spec passes. Closes backlog #1 (no responsive sidebar). **DONE 2026-07-11 — Phase 19 (Plan 19-04 mobile bottom tab bar + desktop-only sidebar + `e2e/dashboard-mobile.spec.ts`).**
- [x] **UIX-03**: Kitchen display and order detail show real product names on live orders — the `OrderItem` snapshot is populated at order creation; "Unknown Product" never renders for a product that exists. Closes backlog #2 (Unknown Product), #8 (kitchen badge clip), #12 (KDS elapsed cap). **DONE 2026-07-11 — Phase 19 (Plan 19-01 `OrderItem.productName` snapshot at creation + backfill + audited-write proof; Plan 19-07 kitchen/order-detail real-name render + badge-clip fix + elapsed cap + e2e).**
- [x] **UIX-04**: Checkout collects a delivery address (persisted via Flyway V45 — V44 stays reserved for #96) and shows the fee breakdown (subtotal + delivery + VAT) BEFORE payment; the Playwright checkout e2e is updated. Closes backlog #3 (no address / fee hidden until after pay). **DONE 2026-07-11 — Phase 19 (Plan 19-01 V45 fulfilment/address backend + GDPR address scrub; Plan 19-06 checkout fulfilment toggle + UK address + fee-before-pay + storefront e2e).**
- [x] **UIX-05**: Each shop renders its own menu: seeded/live products are assigned `shop_id`, and the `ProductRepository` `IS NULL` fallback behaviour is resolved deliberately (scoping dropped the cross-shop bleed; tenant-wide items are not silently duplicated). Closes backlog #6 (multi-shop product bleed) and the duplicate-menu-rows part of #15. **DONE 2026-07-11 — Phase 19 (Plan 19-02 strict per-shop product scoping + dev-profile `DemoDataSeeder` (realistic UK data, `shop_id` assigned) + Testcontainers isolation).**
- [x] **UIX-06**: All 15 audit backlog items closed or explicitly deferred with reason; the existing 921 logical test invocations stay green (grow, not regress); the palette stays orange/emerald/slate (no editorial/serif redesign). Closes backlog #10 (purple hue), #11 (`text-[10px]`), #13 (401 console spam); documents #14 (error boundary) as LEAVE-AS-IS; and is the closure gate for the whole set. **DONE 2026-07-11 — Phase 19 (Plan 19-07 kitchen render; Plan 19-08 purple→amber/blue + sub-12px sweep + VERIFY-FIRST 401 quiet + palette-discipline test; Plan 19-09 closure — registration + docs-freshness reconcile + full gate + browser UAT).**

#### 15-item backlog closure (18-UI-REVIEW.md § Remediation Backlog)

Every one of the 15 audit backlog items maps to a closing plan, or is a documented leave-as-is / deferred edge case:

| # | Severity | Finding | Closing plan(s) / disposition |
|---|----------|---------|-------------------------------|
| 1 | Blocker | No responsive sidebar (all 11 dashboard routes) | 19-04 (mobile bottom tab bar + desktop-only sidebar) |
| 2 | Blocker | "Unknown Product" on real orders | 19-01 (populate `OrderItem.productName` at creation + backfill) + 19-07 (kitchen/order-detail render) |
| 3 | Blocker | Checkout: no address, fee hidden until after pay | 19-01 (V45 fulfilment/address backend) + 19-06 (address + fee-before-pay UI) |
| 4 | Blocker | No public landing page (`/` → login wall) | 19-03 (persona-routed landing page) |
| 5 | Blocker | Orphan routes; no cross-surface nav | 19-03 (public shell + link-graph orphan guard) + 19-05 (PublicShell cross-links) |
| 6 | Critical | Multi-shop product bleed / duplicated menus | 19-02 (strict `shop_id` scoping + clean re-seed) |
| 7 | Major | Marketing pages hardcode hex, bypass tokens | 19-05 (re-skin `operator-pitch`/`business-model-guide` onto palette tokens) |
| 8 | Major | Kitchen card status badge clipped on wrapped IDs | 19-07 (badge-clip fix) |
| 9 | Major | `/track` forces sign-in, no guest lookup | 19-05 (guest `/track` order-number + email lookup, no auth wall) |
| 10 | Minor | Undocumented purple hue | 19-08 (purple → amber/blue on the semantic palette) |
| 11 | Minor | `text-[10px]` off-scale (36× / 9 files) | 19-08 (sub-12px sweep → `text-xs`) |
| 12 | Minor | KDS elapsed time uncapped ("2245m ago") | 19-07 (elapsed cap → hours/days) |
| 13 | Minor | Repeated expected-401 console spam | 19-08 (VERIFY-FIRST customer-session 401→200 quiet handling) |
| 14 | Minor | Generic global error-boundary copy | **LEAVE-AS-IS** — acceptable last-resort fallback per `19-UI-SPEC.md` § Interaction Contracts; no code task. `app/error.tsx` intentionally unchanged. |
| 15 | Minor | Demo/seed data pollution (names, images, dupes) | 19-02 (realistic UK shop/product/customer names + dedupe). **Image sub-finding:** addressed via the SafeImage branded fallback per `19-UI-SPEC.md` Surface G — **no product photography was added** (fallback-not-photography; not rolled up as a blanket close). |

**Deferred edge case (RESEARCH OQ3).** Collection-only shops / minimum-order interplay with the fulfilment toggle is outside the 15-item backlog's explicit scope and is an **accepted deferred edge case**: the toggle ships with **Delivery as default + Collection selectable for all shops**; forcing Collection for no-delivery shops is not implemented this phase (documented in `19-RESEARCH.md` OQ3).

### AI/agent readiness (AI) — v2.3 — added 2026-07-13

EPIC #209 AI/agent-readiness remediation track. Wave 1 (idempotency #204, scoped credentials #206, observability #98) shipped as gsd-quick tasks and is GitHub-issue-tracked. Wave 2+ is tracked as roadmap phases starting with Phase 20. The unfakeable prerequisites (FORCE RLS isolation, drift-proof OpenAPI contract, scoped-token IdP, per-tenant rate limits) already exist; these requirements add the thin agent-facing surface.

- [x] **AI-1**: A Model Context Protocol server (`mcp-server/`, TypeScript, official `@modelcontextprotocol/sdk`, own Docker container) exposes read-only tenant-scoped tools — list shops, list products, read orders — each wrapping the EXISTING core REST API over HTTP (never Postgres directly; core-java + RLS stay the security boundary). Auth reuses #206 Keycloak client-credentials token pass-through, `tenant_id` claim drives RLS, read tools map to `catalog:read`/`orders:read` scopes. Cross-tenant access returns empty/403 (RLS-proven test); tool errors surface RFC 7807 problem-detail not raw stack traces; live E2E against the dev stack; README documents the client-credentials setup. Mutating MCP tools (gated by #204 Idempotency-Key) and #205 outbound webhooks are separate later phases. Source: GitHub issue #203 [AI-1], EPIC #209 Wave 2. **Phase 20.**

## Future Requirements

Deferred to v2.3+. These roll over from HANDOFF.md P2 + Work Orders that v2.2 didn't scope.

### Remaining P2 deep-audit items (6 items, carried from v2.1 HANDOFF.md)

- **FE-LOG-01**: Log frontend API errors to a collection endpoint before swallowing (currently caught and hidden)
- **REACTIVE-01**: Fix blocking reactive calls in the order state machine (Reactor blocking on main thread)
- **DTO-01**: Remove `tenantId` from response DTOs (information leakage — client should never see raw tenant UUIDs)
- **OBS-01**: Grafana dashboards for JVM, database, and business metrics (INFRA-10)
- **OBS-02**: Alertmanager inhibition rules so one root-cause incident doesn't fire 12 derivative alerts (INFRA-11 follow-up)
- **RUNBOOK-01**: Complete the 9 alert runbook TODO stubs in `docs/runbooks/alerts.md` (only ServiceDown was filled during v2.1)

### Deferred from v2.1

- **SECR-08**: Keycloak `realm-export.json` hardcoded dev secrets — allowlisted for now; needs sealed-secrets or runtime-fetched approach
- **STFR-ENUM-01**: `/public/orders?email=` enumeration risk — add auth-wall or rate-limit on the public customer order endpoint
- **NYQUIST-11**: Phase 11 VALIDATION.md closure — `/gsd-validate-phase 11` to flip `nyquist_compliant: true`

### Work Orders unscoped in v2.2

- **TNT-01..04**: Tenant self-serve onboarding with Keycloak + Stripe + welcome email (Work Order D, v3.0 signal)
- **VOPS-04**: `/dashboard/finance` page wired to ledger + VAT (Work Order F)
- **VOPS-05**: `/dashboard/settings` page wired to shop + notification prefs (Work Order F)
- **VOPS-06**: Bulk product import endpoint + UI (Work Order M)
- **PLAT-01**: Log aggregation (Loki or ELK) + runbooks (Work Order G)
- **PLAT-03**: Postgres PITR via WAL archiving to S3 (Work Order I)
- **PLAT-04**: Edge OpenTelemetry + distributed rate limiter via Redis (Work Order K)
- **PLAT-05**: Full-text product search perf verification + caching (Work Order L)
- **REV-01**: Review module controller + storefront display + moderation (Work Order J)
- **BILL-01**: Vendor billing subscription mgmt (Work Order N)
- **WHAT-01**: Migrate WhatsApp handler to order idempotency key (Work Order O)

## Out of Scope (v2.2)

Explicitly excluded from v2.2. Documented to prevent scope creep during execution.

| Feature | Reason |
|---------|--------|
| Tenant self-serve onboarding (D) | Major surface expansion; v3.0 signal, not a v2.x minor |
| Finance/settings pages (F) | Placeholder shells today; billing architecture undecided |
| Bulk import UI (M) | Endpoint spec unclear; low priority |
| Log aggregation / Loki (G) | Alertmanager + Prometheus cover the urgent monitoring surface; full log pipeline is a separate milestone |
| Postgres PITR (I) | Backup CronJob already exists; PITR is a separate effort |
| Review controller + moderation (J) | Service exists and is in use; no audit-blocking urgency |
| Edge OTel + distributed rate limiter (K) | Edge hardened in PR #30; OTel is its own observability milestone |
| Full-text search perf (L) | V25 tsvector indexes exist; perf not proven blocking |
| Billing subscription mgmt (N) | Depends on Work Order D (tenant onboarding) |
| WhatsApp idempotency key (O) | Gated by `WHATSAPP_ENABLED`; low priority |
| 6 remaining P2 HANDOFF items | FE-LOG-01, REACTIVE-01, DTO-01, OBS-01, OBS-02, RUNBOOK-01 — deferred to v2.3 to keep v2.2 bounded at ~3 weeks |
| Mobile native app | Web-first strategy unchanged |
| Real-time vendor-customer chat | High complexity, not core |

## Traceability

Which phases cover which requirements. Filled by roadmap creation 2026-04-18.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SEC-01 | Phase 13 (Plan 13-01) | Complete (2026-04-18) |
| SEC-02 | Phase 12 (Plan 12-02) | Operationally Complete (2026-04-18) — Tasks 01-06 shipped; Task 12-02-07 human-verify cutover gate pending |
| SEC-03 | Phase 12 (Plan 12-01) | Complete (2026-04-18) |
| CQ-01 | Phase 14 (Plan 14-01) | Complete (2026-04-19) |
| CQ-02 | Phase 14 (Plan 14-02) | Complete (2026-04-19) |
| INF-01 | Phase 15 (Plan 15-01) | Drafting Complete (2026-04-18) — cluster rollout pending |
| INF-02 | Phase 15 (Plan 15-01) | Drafting Complete (2026-04-18) — operator install + first conversion pending |
| DOC-01 | Phase 16 (Plan 16-01) | Complete (2026-04-19) |
| AUDIT-W0-01 | Phase 16.1 (Plan 16.1-02) | Complete (2026-04-28) |
| AUDIT-W0-02 | Phase 16.1 (Plan 16.1-03) | Complete (2026-04-28) |
| AUDIT-W0-03 | Phase 16.1 (Plans 16.1-01 + 16.1-04) | Complete (2026-04-28) |
| AUDIT-W0-04 | Phase 16.1 (Plans 16.1-01 + 16.1-05) | Complete (2026-04-28) |
| AUDIT-W0-05 | Phase 16.1 (Plans 16.1-01 + 16.1-05) | Complete (2026-04-28) |
| VOPS-01 | Phase 17 | Complete |
| VOPS-02 | Phase 17 | Complete |
| VOPS-03 | Phase 17 | Complete |
| VOB-01 | Phase 18 (Plans 18-01, 18-02, 18-05) | Complete (2026-07-11) |
| VOB-02 | Phase 18 (Plans 18-03, 18-04) | Complete (2026-07-11) |
| VOB-03 | Phase 18 (Plan 18-05) | Complete (2026-07-11) |
| VOB-04 | Phase 18 (Plan 18-01) | Complete (2026-07-11) |
| VOB-05 | Phase 18 (Plans 18-01..18-06) | Complete (2026-07-11) |
| UIX-01 | Phase 19 (Plans 19-03, 19-05) | Complete (2026-07-11) |
| UIX-02 | Phase 19 (Plan 19-04) | Complete (2026-07-11) |
| UIX-03 | Phase 19 (Plans 19-01, 19-07) | Complete (2026-07-11) |
| UIX-04 | Phase 19 (Plans 19-01, 19-06) | Complete (2026-07-11) |
| UIX-05 | Phase 19 (Plan 19-02) | Complete (2026-07-11) |
| UIX-06 | Phase 19 (Plans 19-07, 19-08, 19-09) | Complete (2026-07-11) |

**Coverage:**
- v1 requirements: 11 + 5 (AUDIT-W0) + 5 (VOB) + 6 (UIX) = 27 total (SEC ×3 + CQ ×2 + INF ×2 + DOC ×1 + AUDIT-W0 ×5 + VOPS ×3 + VOB ×5 + UIX ×6)
- Mapped to phases: 27 (phases 12–19 incl. inserted 16.1)
- Unmapped: 0 ✓

---
*Requirements defined: 2026-04-18*
*Last updated: 2026-04-18 — initial scope for milestone v2.2*
*Last updated: 2026-04-27 — registered AUDIT-W0-01..05 retrospectively from the 2026-04-27 council audit (Phase 16.1 closure).*
*Last updated: 2026-07-11 — registered VOB-01..05 (vendor onboarding first slice, Phase 18 closure).*
*Last updated: 2026-07-11 — registered UIX-01..06 (full-frontend experience overhaul, Phase 19 closure); 15-item UI-audit backlog closed/deferred (#14 LEAVE-AS-IS, #15 image sub-finding = SafeImage fallback not photography, RESEARCH OQ3 deferred edge case).*
