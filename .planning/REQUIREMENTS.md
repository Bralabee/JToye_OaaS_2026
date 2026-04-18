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

- [ ] **CQ-01**: Stock race condition fix. Currently `OrderService.createOrder()` reads `product.stock`, checks availability, and creates the order — but the stock decrement happens later (or not at all in some paths), allowing two concurrent orders to both pass the check and oversell. Fix: move the stock decrement into the `OrderStateMachine` `CONFIRM` transition, gated by optimistic lock on the Product entity (@Version already exists per V32 migration). Tests cover: serialized orders decrement once each; two concurrent confirmations on last-item-in-stock — only one succeeds, other gets `InsufficientStockException`; Testcontainers test exercises the real Postgres optimistic lock path. Source: HANDOFF.md P2 "Fix stock race condition — validate at confirmation, not creation (CQ-01)".
- [ ] **CQ-02**: `getSummary()` DB aggregation. Today `FinancialTransactionService.getSummary()` (or equivalent OrderService / ShopService summary method — grep to locate) calls `findAll()` and reduces in-memory. Replace with a JPQL or native-query `SUM()`/`COUNT()`/`GROUP BY` that returns aggregated rows directly. Tests cover: matching output vs old implementation on a seeded dataset; query plan uses indexes (verify with `EXPLAIN ANALYZE` in the integration test); performance measurable improvement on a 10k-row fixture. Source: HANDOFF.md P2 "Fix getSummary() to use DB aggregation instead of findAll() (CQ-02)".

### Infrastructure (INF)

Close K8s-level security gaps.

- [ ] **INF-01**: K8s NetworkPolicies in `k8s/base/` that enforce: `frontend` can only talk to `core-java` and its own CDN/image origins; `core-java` can talk to `postgres`, `redis`, `rabbitmq`, `keycloak`, `minio`, `alertmanager`, and outbound Stripe API only; `postgres`/`redis`/`rabbitmq`/`minio` only accept connections from `core-java` + maintenance tooling; deny-all for every other pod-to-pod combination. Apply via overlay in `k8s/overlays/staging` and `k8s/overlays/prod`. Tests cover: policy manifests validate with `kubectl --dry-run`; policy count matches spec; CIDR blocks for Stripe API documented. Source: HANDOFF.md P2 "Add K8s NetworkPolicies (INFRA-17)".
- [ ] **INF-02**: K8s Sealed Secrets using `bitnami-labs/sealed-secrets` controller. Convert `k8s/base/secrets-template.yaml` from plain `Secret` to `SealedSecret` manifests. Document the key-rotation procedure in `docs/runbooks/sealed-secrets.md` (backup controller key, restore, rotate-on-key-compromise). Tests cover: sealed manifest decrypts on the cluster; `kubeseal` CLI reproducibility from a plaintext file; fallback plain Secret still supported for dev (unchanged). Source: HANDOFF.md P2 "Add K8s Sealed Secrets (INFRA-11)".

### API documentation (DOC)

Fill the last surface-area documentation gap.

- [ ] **DOC-01**: OpenAPI spec for the Go edge gateway. Use `swaggo/swag` or `go-swagger` to annotate Gin handlers (`cmd/edge/main.go` and `internal/`) and generate a `/openapi.json` endpoint + Swagger UI at `/docs`. Covers all edge routes: `/health`, `/ready`, `/sync/batch`, `/orders`, `/whatsapp`, `/products/search` (if present). Tests cover: spec is valid OpenAPI 3.0 per `openapi-spec-validator` npm tool in CI; every Gin route has a corresponding `@Summary`/`@Router` annotation (line-count assertion). Source: HANDOFF.md P2 "Generate OpenAPI for Go gateway".

### Vendor operations (VOPS) — Work Order E

Ship the last piece of the vendor order-management loop.

- [ ] **VOPS-01**: `/dashboard/orders/[id]` order detail view in the Next.js dashboard. Renders: order header (number, status, created/updated timestamps, state transitions timeline), customer block (name, email, phone — pulls via `OrderService.getCustomerForOrder`), item lines (product name, qty, unit price, subtotal, modifiers), payment block (Stripe payment intent ID, status, amount, refund history), action panel (status advance buttons, issue refund). Uses existing `DashboardLayout`, Radix UI primitives, TailwindCSS. Tests cover: Jest — renders with minimal fixture, renders with full fixture (refunded state), empty/loading/error states; Playwright — click from `/dashboard/orders` list lands on correct detail page, action panel visible to vendor role.
- [ ] **VOPS-02**: `POST /api/v1/orders/{id}/refund` endpoint wired to Stripe refund API. Request body: `{ amount_pennies?: number, reason: "requested_by_customer" | "duplicate" | "fraudulent" | "other", note?: string }`. Behavior: partial refund if `amount_pennies` < order total, full refund if absent; uses `RefundService.createRefund` → `Stripe.Refund.create(paymentIntentId, amount)`; persists a `Refund` entity (new Flyway V34 migration — `order_id` FK, `amount_pennies`, `reason`, `stripe_refund_id`, `status`, `created_at`); publishes `order.refunded` event to RabbitMQ. Stripe webhook handler updates the `Refund.status` on `charge.refunded` / `refund.updated` events. Tests cover: full refund success path, partial refund success path, invalid amount (> order total) rejected with 400, Stripe API error propagates as 502, webhook update cycle integration test.
- [ ] **VOPS-03**: Refund state transition added to `OrderStateMachine`. Event `REFUND_REQUESTED` transitions `CONFIRMED`, `PREPARING`, `READY`, `COMPLETED` → `REFUNDED`. Event is idempotent (second invocation on a `REFUNDED` order is a no-op, not an `InvalidStateTransitionException`). Transition is audited by Hibernate Envers (existing setup) and logs a structured SLF4J INFO entry. Tests cover: state machine unit tests per transition source; idempotency test; integration test that a refund via VOPS-02 triggers the state transition automatically.

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
| CQ-01 | Phase 14 | Pending |
| CQ-02 | Phase 14 | Pending |
| INF-01 | Phase 15 | Pending |
| INF-02 | Phase 15 | Pending |
| DOC-01 | Phase 16 | Pending |
| VOPS-01 | Phase 17 | Pending |
| VOPS-02 | Phase 17 | Pending |
| VOPS-03 | Phase 17 | Pending |

**Coverage:**
- v1 requirements: 11 total (SEC ×3 + CQ ×2 + INF ×2 + DOC ×1 + VOPS ×3)
- Mapped to phases: 11 (phases 12–17)
- Unmapped: 0 ✓

---
*Requirements defined: 2026-04-18*
*Last updated: 2026-04-18 — initial scope for milestone v2.2*
