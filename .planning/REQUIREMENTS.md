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
| CID-01 | Phase 18 (Plans 18-01 + 18-02) | Complete (2026-07-09) |

**CID-01 — Customer Identity Separation (B2C/B2B):** Storefront customers authenticate against a dedicated Keycloak realm (`jtoye-customers`) with self-registration + email/password, isolated from the staff/vendor realm (`jtoye-dev`); the admin login no longer exposes customer self-registration. Backend unchanged (customer tokens are frontend-only). Social/Google login deferred (Phase 2).

**Coverage:**
- v1 requirements: 11 + 5 (AUDIT-W0) = 16 total (SEC ×3 + CQ ×2 + INF ×2 + DOC ×1 + AUDIT-W0 ×5 + VOPS ×3)
- Mapped to phases: 16 (phases 12–17 incl. inserted 16.1)
- Unmapped: 0 ✓

---
*Requirements defined: 2026-04-18*
*Last updated: 2026-04-18 — initial scope for milestone v2.2*
*Last updated: 2026-04-27 — registered AUDIT-W0-01..05 retrospectively from the 2026-04-27 council audit (Phase 16.1 closure).*
