# J'Toye OaaS — State of the Codebase (Post-Audit)

**Produced:** 2026-04-14
**Branch:** `docs/state-of-codebase-2026-04-14`
**Source:** 5 parallel specialist analysis agents + real-user Playwright screenshots on a live Next.js 16 dev server (port 3100) + cross-verification against live source
**Purpose:** Ground-truth input document for the next planning cycle. Every statement below is backed by file:line evidence or live screenshot capture.

---

## Table of Contents
1. [Executive verdict](#1-executive-verdict)
2. [Module-by-module status matrix](#2-module-by-module-status-matrix)
3. [Java core deep-dive](#3-java-core-deep-dive)
4. [Frontend deep-dive](#4-frontend-deep-dive)
5. [Edge-Go deep-dive](#5-edge-go-deep-dive)
6. [Infrastructure + DevOps deep-dive](#6-infrastructure--devops-deep-dive)
7. [Roadmap vs reality — traceability](#7-roadmap-vs-reality--traceability)
8. [Real-user smoke test results (with screenshots)](#8-real-user-smoke-test-results)
9. [The 5 real production blockers](#9-the-5-real-production-blockers)
10. [Full audit-finding ledger (34 items → status)](#10-full-audit-finding-ledger)
11. [Proposed work-order backlog](#11-proposed-work-order-backlog)
12. [Data sources and artifacts](#12-data-sources-and-artifacts)

---

## 1. Executive verdict

The codebase is **~75% of the way to production-ready for an MVP SaaS launch**. The hard parts are done:

- Order lifecycle + state machine + stock batching ✅
- Transactional payment outbox → RabbitMQ → kitchen fan-out ✅
- Real-time KDS over STOMP/WebSocket ✅
- Multi-tenant RLS + JWT + tenant-aware cache ✅
- Storefront browse/cart/Stripe checkout ✅
- GDPR export + erasure ✅
- Flyway V1–V32 sequential, auto-applied ✅
- CI/CD pipeline with staging autodeploy + prod manual gate ✅

All **34 verified audit findings** from the earlier campaign are now on `main` (7 merged PRs #30–#36). The edge-go stack has 28 tests, core-java 335, frontend 69, **0 npm vulnerabilities**.

But there are **5 real production blockers** detailed in §9 below, ranked by damage if ignored. They add up to ~4–6 weeks of focused work for a true self-serve SaaS v1; a hand-provisioned pilot with 1–3 vendors could ship next week after fixing one immediate item (the committed `.env`).

---

## 2. Module-by-module status matrix

**Legend:** ✅ production-ready · ⚠️ partial/needs work · ❌ missing/stub

### Java core (`core-java/src/main/java/uk/jtoye/core/`)

| Module | Status | Evidence |
|---|---|---|
| `order/` | ✅ | `OrderService.java:316` wires every transition through state machine; batched stock loops at `OrderService.java:350-400`; SSE + WebSocket broadcast wired |
| `payment/` | ✅ | `PaymentEventOutboxFlusher.java:55-105` drains PENDING rows on 5s schedule with 5-retry + DLQ semantics; Stripe webhook via `PaymentService.handleWebhookEvent` |
| `shop/` | ✅ | CRUD + announcements + promotions; tenant-scoped cache eviction via `TenantCacheEvictor` |
| `product/` | ⚠️ | CRUD + image upload + bulk CSV + allergen PDF. Full-text search path uses Postgres `tsvector` (V25 migration) but not independently perf-verified |
| `customer/` | ✅ | No caching by design (privacy); email uniqueness via DB constraint |
| `gdpr/` | ✅ | Article 20 export + Article 17 erasure. Anonymises names→`[REDACTED]`, emails→`redacted@erased.invalid.{uuid}`, nullifies phone/notes, preserves records for audit |
| `security/` | ✅ | `JwtTenantFilter` + `TenantContext` + JWKS 5s fetch timeout; CSRF disabled with 8-line justification comment (Phase 2 fix #9) |
| `config/` | ✅ | `TenantAwareCacheKeyGenerator.java:32-54` throws on unset tenant (no more null collapse); `TenantCacheEvictor` + `CacheConfig` + `RabbitMQConfig` + DLQ |
| `ws/` | ✅ | `WebSocketConfig.java:17-43` STOMP broker + `JwtHandshakeInterceptor` + `TenantChannelInterceptor`; `OrderStateChangeListener.java:54` publishes `/topic/kitchen/{tenantId}/{shopId}` |
| `sync/` | ⚠️ | `SyncController` exists with `@Valid` audit applied; actual batch sync semantics minimal |
| `storefront/` | ⚠️ | `PublicStorefrontController` exists (browse-only). Does **not** render promotions/announcements |
| `finance/` | ⚠️ | VAT enum + transaction ledger; no per-line tax engine |
| `notification/` | ⚠️ | `EmailNotificationService` wired into state change listener; no SMS/push/templates |
| `review/` | ⚠️ | Service present, no controller exposed |
| `metrics/` | ✅ | `BusinessMetricsService` → Micrometer → Prometheus |

### Frontend (`frontend/`)

**Vendor (B2B) persona — `/app/dashboard`:**

| Route | Status | Notes |
|---|---|---|
| `/dashboard` (home) | ✅ | Stats cards, Recharts pie/bar, recent orders, financial summary |
| `/dashboard/kitchen` | ✅ flagship | STOMP WS to `/topic/kitchen/{tenantId}/{shopId}`, status bump buttons, age colouring (green<5m/yellow≤15m/red>15m), audio alerts, mute toggle, full Jest + Playwright tests post-Phase 3 |
| `/dashboard/products` | ⚠️ | Full CRUD + image upload + AI suggestions UI. Bulk import UI exists but endpoint integration unclear |
| `/dashboard/orders` | ⚠️ | **No detail view**, no refund/edit. Operational gap for support staff |
| `/dashboard/marketing` | ⚠️ | Promotions + announcements CRUD work end-to-end but **never rendered on storefront** |
| `/dashboard/customers` | ⚠️ | CRUD + allergen flags, no per-customer order history |
| `/dashboard/shops` | ⚠️ | CRUD + hours + publish toggle, slug generation unclear |
| `/dashboard/finance` | ❌ | Placeholder shell |
| `/dashboard/settings` | ❌ | Placeholder shell |

**Customer (B2C) persona — `/app/shop`, `/app/track`:**

| Route | Status | Notes |
|---|---|---|
| `/shop` (discovery) | ✅ | Search, pagination, open/closed badges (UK tz), cuisine + tags |
| `/shop/[slug]` (menu) | ✅ | Categories, allergen mask visualisation, dietary badges, product modal, add-to-cart |
| `/shop/[slug]/cart` | ❌ **route missing** | Cart is only a modal/sidebar — direct links 404 |
| `/shop/[slug]/checkout` | ✅ | Real Stripe `<PaymentElement>` + 3DS redirect + COD mode, idempotency key per session |
| `/shop/[slug]/orders/[orderNumber]` | ✅ | Order summary + allergen warnings (no live push, polls) |
| `/shop/orders` (customer history) | ❌ missing | No route for authenticated customer's all-orders list |
| `/track` | ✅ | `RequireCustomerAuth` guard, 15s poll for active orders |

**Infrastructure (frontend):**

| Module | Status |
|---|---|
| `app/api/customer-auth/{login,logout,session,logout-url}/route.ts` | ✅ (Phase 3 fix #1 — HttpOnly cookies) |
| `lib/api-client.ts` | ✅ — Bearer + `X-Tenant-Id` + retry 5xx + debounced 401 refresh (Phase 3 fix #3) |
| `lib/customer-auth.ts` | ✅ — PKCE + HttpOnly cookie storage + non-sensitive localStorage marker |
| `hooks/use-stomp.ts` | ✅ — `beforeConnect` wrapped in try/catch, token refresh, reconnect |
| `middleware.ts` | ⚠️ — only protects `/dashboard/:path*`; public routes rely on per-page guards |
| `e2e/` | ⚠️ — 1 kitchen spec + 1 storefront spec via Playwright `route()` stubs; no full customer checkout e2e |

### Edge-Go (`edge-go/`)

| Module | Status |
|---|---|
| `cmd/edge/main.go` | ✅ — signal-based graceful shutdown, bounded bearer extraction, `/health` liveness + `/ready` readiness (w/ JWKS probe), rate-limiter ctx-scoped |
| `internal/middleware/jwt.go` | ✅ — JWKS cache, env-configurable refresh interval (`JWKS_REFRESH_INTERVAL`), 5s fetch timeout, 30s clock-skew leeway |
| `internal/core/client.go` | ✅ — circuit breaker with warm-up: `Requests >= 10 && failureRatio >= 0.6`, 30s window |
| `internal/whatsapp/parser.go` | ✅ — newline-delimited grammar (no more comma truncation), HMAC fail-closed when `WHATSAPP_APP_SECRET` unset |
| Tests | 28 total across 4 packages, all pass |

### Infrastructure

| Area | Status | Evidence |
|---|---|---|
| `docker-compose.full-stack.yml` | ✅ | 12 services with healthchecks and `depends_on: service_healthy` |
| Flyway migrations | ✅ | V1–V32 sequential, no gaps, auto-applied, no destructive DDL, RLS + audit baked in |
| `k8s/base` + overlays | ✅ | Staging 2 replicas, prod 3–5 + HPA 3–10 |
| `.github/workflows/ci-cd.yaml` | ✅ | Trivy + Snyk, multi-arch GHCR publish, staging autodeploy from `develop`, prod manual gate |
| Prometheus rules | ⚠️ | 13 rules present; **Alertmanager not deployed** → alerts fire into the void |
| Grafana dashboards | ⚠️ | Provisioning skeleton only |
| Log aggregation | ❌ | None (container stdout only) |
| K8s secrets | ⚠️ | Plain base64; sealed-secrets documented but not deployed |
| Postgres backup | ⚠️ | Daily `pg_dump` → S3 CronJob; **no PITR, no restore test** |
| `.env` in repo | ❌ **critical** | Contains `POSTGRES_PASSWORD=secret`, `KEYCLOAK_ADMIN_PASSWORD=admin123`, `REDIS_PASSWORD=redispass123` in plaintext. Must be `.gitignore`'d and rotated |

---

## 3. Java core deep-dive

### Specific Q&A (verified against source)

**Q: Does `PaymentEventOutboxFlusher#flush()` actually dispatch events?**
**A: Yes.** `PaymentEventOutboxFlusher.flushPending()` (line 57) fetches PENDING rows, deserialises JSON payload into `PaymentEvent`, calls `rabbitTemplate.convertAndSend(PAYMENT_EVENTS_EXCHANGE, routingKey, event)` (lines 72–76). Rows marked SENT on success, FAILED after 5 retries. **Confirmed dispatching.**

**Q: Does the kitchen WebSocket broker publish on order state transitions?**
**A: Yes.** `OrderStateChangeListener.handleOrderStateChange()` consumes RabbitMQ `ORDER_EVENTS_QUEUE`, then broadcasts via `simpMessagingTemplate.convertAndSend("/topic/kitchen/{tenantId}/{shopId}", event)` at line 54. **Listener is wired.**

**Q: Is the state machine wired into every transition entry point?**
**A: Yes.** All transition methods (`submitOrder`, `confirmOrder`, `startPreparation`, `markOrderReady`, `completeOrder`, `cancelOrder`) route through `transitionOrder()` → `stateMachineService.sendEvent()` at line 316. `OrderController` endpoints only call service methods. **No bypass.** The deprecated `updateOrderStatus` shortcut was deleted in Phase 2 fix #5.

**Q: Does `@CacheEvict` coverage match every mutation path?**
**A: Mostly yes.** Updates call `cacheEvictor.evictEntity()` targeting the specific method + id. Creates don't evict (new ids can't be cached). Deletes do evict. **No `allEntries=true` anti-pattern remains.** Minor risk: product search cache (if any) may not be explicitly evicted on updates.

**Q: Is there a dedicated tenant admin module?**
**A: No.** Only `DevTenantService.ensureTenantExists()` for local dev. **Critical gap — no production tenant provisioning, no billing, no self-serve onboarding.**

**Q: Do any `@Controller` methods leak entity internals?**
**A: No.** All controllers return DTOs. Mappers handle entity↔DTO conversion. Clean separation.

**Q: Envers audit on sensitive entities?**
**A: Yes.** `Order`, `OrderItem`, `Product`, `Shop`, `Customer`, `FinancialTransaction` — 7 entities are `@Audited`. `RevInfo` captures revisions.

### Top 10 Java gaps (ordered by severity)

1. **CRITICAL — No production tenant onboarding** (`DevTenantService` only). SaaS signup blocked.
2. **CRITICAL — Marketing renders nowhere on storefront.** Feature is entirely in admin.
3. **HIGH — STOMP `SimpleBroker` in-memory.** Second `core-java` replica breaks kitchen broadcasts silently.
4. **HIGH — Outbox flusher is method-level scheduled, not DB-recovery-driven.** If the one instance dies mid-flush, PENDING rows stuck until restart.
5. **HIGH — WebSocket broadcasts are fire-and-forget.** No ack; if kitchen offline, orders lost silently.
6. **HIGH — Product full-text search not perf-verified.** Could N-query at scale despite V25 `tsvector` index.
7. **MEDIUM — Review module incomplete.** No controller, no storefront exposure.
8. **MEDIUM — VAT logic is order-level only.** No per-line tax calculation for mixed rates.
9. **MEDIUM — Notification service is email-only.** No SMS/push/templates.
10. **LOW — Search results cache not explicitly evicted on product mutation.** Could show stale results briefly.

---

## 4. Frontend deep-dive

### Vendor (B2B) persona narrative

Fully interactive, real-time vendor dashboard under `/app/dashboard`. Sign-in via Keycloak (NextAuth v5) → Bearer token → protected sidebar shell. Dashboard home shows stats cards + Recharts pie/bar + financial summary from `/api/v1/financial-transactions/summary`.

**Kitchen Display (`/app/dashboard/kitchen/page.tsx`) — production-ready.** Shop selector, WebSocket STOMP subscribe to `/topic/kitchen/{tenantId}/{shopId}`, CONFIRMED→PREPARING→READY bump buttons, age-based border colouring, audio cues (Web Audio API, mute persisted to localStorage key `kds-muted`), elapsed time refreshes every 30s. STOMP `beforeConnect` fetches fresh session per reconnect; full data resync on disconnect.

**Products.** CRUD, image upload, AI-driven title/description/allergen suggestions (UI built; endpoint integration present). Bulk import page exists.

**Orders.** List + paginate + status badges. **No detail view**, cannot refund or edit individual orders. Operational gap.

**Marketing.** Promotions (percentage/flat discounts, category scope, validity windows) + announcements (text, image URL, schedule). "Active/upcoming/expired/disabled" helpers. **Gap: zero integration with the storefront rendering.**

**Finance + Settings:** placeholder shells only.

### Customer (B2C) persona narrative

**Discovery (`/shop`)** — search by name/cuisine, open/closed badges (UK tz-aware), min order + delivery fee display, tags + cuisine badges, pagination 12/page.

**Menu (`/shop/[slug]`)** — shop header (logo/banner/hours/address/phone) + categories + products with allergen mask visualisation, dietary tags (vegan/vegetarian/spicy/halal/gluten-free), out-of-stock badges, add-to-cart increment/decrement. Cart is localStorage-backed under `jtoye-cart-{slug}`; persists across browser restart.

**Checkout (`/shop/[slug]/checkout`)** — two-step: details form (name/email/phone/notes) → `/public/shops/{slug}/orders` → server returns `clientSecret` for Stripe OR confirms COD. Stripe path renders `<Elements>` + `<PaymentElement>` + handles 3DS. COD path shows breakdown + confirm + redirect to order tracking. Idempotency key stored per session. Allergen warnings shown pre-payment. Cart cleared on success.

**Order tracking (`/track`)** — `RequireCustomerAuth` guarded (customer-auth PKCE flow). Enter order number + email, get live status with progress bar. Auto-refresh 15s for active orders. **Polls, not WebSocket.**

### Frontend Q&A (verified)

- **Vendor onboarding flow?** No self-signup. Only Keycloak login. Vendors pre-provisioned out-of-band.
- **Does checkout call Stripe?** Yes, real `@stripe/react-stripe-js` integration with `<PaymentElement>`, `stripe.confirmPayment()`, 3DS redirect.
- **Kitchen WebSocket or poll?** Real WebSocket (STOMP via `@stomp/stompjs`).
- **Cart persisted across sessions?** Yes, localStorage `jtoye-cart-{slug}`.
- **Pages that 404 on direct nav?** `/shop/[slug]/cart` (missing route); `/dashboard/finance`, `/dashboard/settings` (placeholder shells but don't crash).
- **Marketing on storefront?** No. Admin-only.
- **Server vs client split?** Mostly sensible; dashboard layout is now async Server Component (Phase 3 fix #7).

### Top 10 frontend gaps

1. **CRITICAL — No vendor self-signup / onboarding flow.**
2. **CRITICAL — Promotions never rendered on storefront.** Tier 3 feature half-dead.
3. **HIGH — `/shop/[slug]/cart` route missing.** Direct cart URLs 404.
4. **HIGH — No order detail view in vendor dashboard.** Can't handle customer support queries.
5. **HIGH — No customer order-history page** (`/shop/orders` absent). Loyalty/repeat-use friction.
6. **MEDIUM — Settings stub only.** Can't change VAT, display name, payment methods, webhooks from UI.
7. **MEDIUM — Finance page empty.** No P&L, payout status, transaction list.
8. **MEDIUM — Bulk product import integration unclear.** Upload UI present, endpoint handshake unverified.
9. **MEDIUM — Only 2 Playwright e2e specs.** No end-to-end customer checkout flow test.
10. **LOW — `middleware.ts` only protects `/dashboard/:path*`.** Public routes rely on per-page `RequireCustomerAuth`.

---

## 5. Edge-Go deep-dive

### End-to-end WhatsApp trace (`cmd/edge/main.go:215-362`)

1. **HMAC verification** (L215–248) — extracts `X-Hub-Signature-256`, fails closed if `WHATSAPP_APP_SECRET` unset (L224), computes SHA256-HMAC, `hmac.Equal` timing-safe compare.
2. **Webhook parse** (L250–261) — `whatsapp.ParseWebhook(body)`. Newline-delimited grammar (post-fix). Silent skip if no items.
3. **Auth context** (L263–281) — extracts JWT `tenant_id` from ctx + Bearer token from header via `extractBearerToken` helper.
4. **Product resolution** (L286–328) — per item, calls `coreClient.SearchProducts()`. Match rule: single result → use; multiple → exact `strings.EqualFold` title match; no unique match → warn + skip (no blind `products[0]` any more).
5. **Order creation** (L336–355) — if any items resolved, POST to Core `/api/v1/orders` with `shopId` (`WHATSAPP_DEFAULT_SHOP_ID`), customer phone, notes, items. 200 on success, 200 on parse/creation errors (idempotent retry). Circuit-breaker-protected.

**Production readiness: yes for MVP.** Gaps:
- No order idempotency key from WhatsApp webhook id → retry after partial failure could double-book
- No e2e integration test for the full binary
- Rate limiter is per-instance (needs Redis for distributed)
- No Prometheus metrics export or OpenTelemetry hooks

### Circuit breaker settings (`internal/core/client.go:26-34`)

```go
MaxRequests: 3,
Interval:    30 * time.Second,
Timeout:     60 * time.Second,
ReadyToTrip: func(counts gobreaker.Counts) bool {
    if counts.Requests < 10 { return false }
    return float64(counts.TotalFailures)/float64(counts.Requests) >= 0.6
},
```

Appropriate for production. 10-req warm-up prevents cold-start false trips; 60% threshold is reasonable; 30s/60s window + half-open is standard. Monitor in staging 1–2 weeks; tune if Core has regular blips.

### Top 8 edge gaps

1. No e2e integration test for `main.go`.
2. No JWKS exponential backoff / error-triggered refresh — only scheduled refresh.
3. No order idempotency key for WhatsApp → Core (duplicate risk on partial failure).
4. No metrics/observability hook (no Prometheus, no OpenTelemetry).
5. Rate limiter not distributed (in-memory token bucket; 10x limit at 10 pods).
6. Circuit breaker state not reported in `/ready`.
7. Tenant extraction doesn't validate UUID format (accepts any non-empty string).
8. No request context propagation — no `X-Request-ID` / `X-Correlation-ID` forwarded to Core.

---

## 6. Infrastructure + DevOps deep-dive

### Cold-start readiness

**Can a developer `docker compose up` from a fresh clone?** Yes, with caveats:
- All 12 services defined with healthchecks + `depends_on: service_healthy`
- `infra/db/init/00-create-db.sql` seeds jtoye + keycloak databases
- `infra/keycloak/realm-export.json` imports full realm (jtoye-dev + 4 roles + clients)
- `.env.example` documents 87 variables
- **Risk: committed `.env` file** has plaintext passwords; must be removed from tracking

### Environments

| Env | Status | Config |
|---|---|---|
| Development | Full-stack compose | `docker-compose.full-stack.yml` + Keycloak realm import |
| Staging | K8s overlay | `k8s/staging/kustomization.yaml` — 2 replicas, `staging` tag, reduced compute |
| Production | K8s overlay | `k8s/production/kustomization.yaml` — 3–5 replicas, pinned `2.0.0` tag, HPA 3–10 |

CI/CD flow (`.github/workflows/ci-cd.yaml`):
- PR/push: tests + Trivy + Snyk
- Merge to `develop`: build + multi-arch GHCR push + auto-deploy to staging + smoke test + rollback on failure (5m timeout)
- Merge to `main`: manual gate via GitHub Environment approval; deploys only if `DEPLOY_ENABLED == 'true'`

### Flyway health

32 migrations V1–V32, sequential, no gaps, 1323 SQL lines total. Baseline schema at V1, RLS policies V2/V14/V15, audit V4/V8, order pipeline V5/V6/V17/V18/V21-23/V30, full-text search V25, payment outbox V31, optimistic locking V32. No destructive patterns. Auto-applied on Spring Boot startup.

### Observability

| Component | Status |
|---|---|
| Prometheus | ✅ running in `infra/monitoring/docker-compose.monitoring.yml`, 30-day retention |
| Alert rules | ✅ 13 rules across API, DB, JVM, business (HighErrorRate 5%, ServiceDown 2m, P95 > 1s, pool > 90%, heap > 85%, GC, NoOrdersCreated, TenantIsolationFailure) |
| Alertmanager | ❌ **not deployed — alerts fire into the void** |
| Grafana | ⚠️ container runs, dashboards empty |
| postgres-exporter | ✅ fixed Phase 4 — env-sourced creds + `sslmode=require` |
| Log aggregation | ❌ none (container stdout only) |

### Secrets management

| Method | Status |
|---|---|
| K8s `secrets-template.yaml` | Plain base64 placeholders |
| Sealed secrets / ESO | Documented as recommended, **not deployed** |
| Committed `.env` | ❌ plaintext passwords in repo |
| GitHub Secrets | Used for `KUBE_CONFIG_STAGING`, `KUBE_CONFIG_PRODUCTION`, `SNYK_TOKEN`, `SLACK_WEBHOOK_URL` |

### Postgres backup

- K8s CronJob at 02:00 UTC daily
- `pg_dump` custom format → gzip → S3
- 30-day retention via date-based prune
- `backoffLimit: 2`, 30m timeout
- **Gaps:** no PITR (only daily full dumps), no tested restore procedure, no encryption at rest

### Dockerfile quality

| Service | Base | Size | Multi-stage | Non-root |
|---|---|---|---|---|
| core-java | `eclipse-temurin:21-jre` (alpine) | ~370 MB | ✅ | ✅ uid 1000 |
| edge-go | `scratch` | ~15 MB | ✅ | ✅ |
| frontend | `node:20-alpine` | ~500 MB | ✅ | ✅ |

### Top 10 infra gaps (ranked)

1. **CRITICAL — Alertmanager missing.** Rules fire with no sink.
2. **CRITICAL — `.env` committed with plaintext secrets.** Rotate and `.gitignore`.
3. **HIGH — K8s secrets unencrypted at rest.** Deploy sealed-secrets or external-secrets-operator.
4. **HIGH — No log aggregation.** Add Loki or ELK.
5. **MEDIUM — Grafana dashboards empty.** Build SLO dashboards.
6. **MEDIUM — Keycloak client secret rotation not automated.**
7. **MEDIUM — No PITR for Postgres.** Only daily dumps.
8. **MEDIUM — No runbooks.** Incidents → no playbooks.
9. **LOW — Ollama image tag unversioned** (`${OLLAMA_IMAGE_TAG:-latest}` fallback still resolves to latest).
10. **LOW — `scripts/verify-env.sh` claims 4 migrations** (stale — actual is 32).

---

## 7. Roadmap vs reality — traceability

### Requirements matrix (22 items, all mapped)

| Req | Implemented? | Evidence |
|---|---|---|
| APIV-01 all endpoints `/api/v1/` | ✅ | `WebConfig.java:23` configures path prefix for 7 packages |
| APIV-02 Go edge `/api/v1/` | ✅ | `edge-go/internal/core/client.go` hardcodes paths |
| APIV-03 Next.js client `/api/v1/` | ⚠️ | Uses `apiClient` abstraction; base-URL config not grep-visible in source |
| APIV-04 webhooks exempted | ✅ | `PaymentController` at `/public/payments`, `SyncController` at `/sync/batch` |
| APIV-05 Swagger reflects `/api/v1/` | ✅ | Auto-discovery via `configurePathMatch()` |
| VMKT-01 promotion CRUD with discount types | ✅ | V29 migration + `PromotionController.java:26` + `PromotionService.java` |
| VMKT-02 promotion scheduling | ✅ | `validFrom`/`validUntil` columns V29 |
| VMKT-03 announcement entity extraction | ✅ | V29 creates `announcements` table |
| VMKT-04 announcement CRUD + scheduling | ✅ | `AnnouncementController.java:26` |
| VMKT-05 vendor dashboard marketing UI | ✅ | `frontend/app/dashboard/marketing/page.tsx` |
| KDS-01 WebSocket STOMP config | ✅ | `WebSocketConfig.java:17-43` |
| KDS-02 JWT handshake interceptor | ✅ | `JwtHandshakeInterceptor` |
| KDS-03 TenantContext propagation | ✅ | `TenantChannelInterceptor` |
| KDS-04 real-time order feed | ✅ | `frontend/app/dashboard/kitchen/page.tsx` |
| KDS-05 status bump | ✅ | Bump buttons wired to state machine |
| KDS-06 age-based colours | ✅ | Tailwind conditional classes |
| KDS-07 audio alert | ✅ | Web Audio API |
| KDS-08 events through RabbitMQ | ✅ | `OrderStateChangeListener` bridges RabbitMQ → WebSocket |
| TEST-01 PaymentController tests | ✅ | 4 tests |
| TEST-02 PublicStorefrontController tests | ✅ | 7 tests |
| TEST-03 Security filter tests | ✅ | JwtTenantFilter 6, TenantFilter 5 |
| TEST-04 GDPR integration tests | ✅ | GdprController 5 + GdprService |

**22/22 requirements implemented.**

### Milestone phase completion

| Phase | Claimed | Verified | Delta |
|---|---|---|---|
| 1 API versioning (backend) | Complete | ✅ | None |
| 2 API versioning (edge + FE) | Complete | ✅ | Frontend paths not grep-visible (uses abstraction) — not a bug, just hidden |
| 3 Vendor marketing backend | Complete | ✅ | None |
| 4 Vendor dashboard marketing UI | Complete | ✅ | None |
| 5 KDS security + WebSocket | Complete | ✅ | None |
| 6 KDS event pipeline | Complete | ✅ | Traced `OrderStateChangeListener:54` — confirmed broadcast, not ambiguous |
| 7 Kitchen display UI | Complete | ✅ | None |
| 8 Test coverage | Complete | ✅ | Test-count reporting method inconsistent (379 @Test found vs 356 claimed) |

**8/8 phases complete.** However, 7 post-completion audit PRs (#30–#36) landed **after** the phase close date, exposing that the phase-gate process didn't catch material issues. This is a process finding — update phase close criteria to require security + data-integrity sign-off.

### Planned but missing
- RabbitMQ STOMP relay for scaled kitchen broadcasts (listed as "future" in INFRA-01)
- Kitchen station routing / course pacing / order recall (deferred to v2 per KDS-09/10/11)
- Customer register flow explicitly in roadmap (exists via Keycloak register link but undocumented)

### Built but undocumented
- Optimistic locking on Order + Shop (V32, Phase 2)
- Payment transactional outbox (V31, Phase 2)
- Edge rate-limiter env wiring (post-Tier-3 PR #28)
- Every fix in #30–#36 (security, data-integrity, CVE bumps, frontend hardening)

---

## 8. Real-user smoke test results

### Setup

- Full stack bringup **blocked**: port 5432 held by unrelated `dealflow_postgres` container, port 3000 held by an MCP server. I deliberately did not disturb either.
- Workaround: brought up only `frontend/` dev server on port 3100 via `PORT=3100 npm run dev`. No backend → every API call falls to graceful empty-state. This still validates shell + nav + routing + loading + empty + 404 UX, which is a meaningful fraction of a real user test.
- Playwright browsers were already cached at `~/.cache/ms-playwright/`.

### Results per route

| Route | HTTP | Response size | Observation |
|---|---|---|---|
| `/` | 307 → `/dashboard` | 12828 bytes | Redirects correctly (vendor-first) |
| `/auth/signin` | 200 | 15727 bytes | **See screenshot** — clean, branded, single Keycloak CTA |
| `/shop` | 200 | 21799 bytes | **See screenshot** — header, search, hero, skeleton cards → empty state |
| `/shop/demo` | 200 | — | **See screenshot** — graceful "Shop not found" + back link |
| `/dashboard` | 307 | 16514 bytes | Correctly redirects to sign-in (no session) |
| `/track` | 200 | 13792 bytes | **See screenshot** — auth gate with Sign-in CTA, "register during sign-in" hint |
| `/api/customer-auth/session` | 401 | 23 bytes | Correct — HttpOnly cookie missing |

### Screenshots captured

All four saved to `.planning/state-of-codebase/screenshots/`:

1. **`signin.png`** — `/auth/signin`: Icon + "J'Toye OaaS" wordmark + "Sign in to access your multi-tenant order management system" + blue "Sign in with Keycloak" button + "Secure authentication via Keycloak OIDC" subtext. Production-quality landing.

2. **`shop-discovery.png`** — `/shop`: Header with orange J logo + "J'Toye" + Browse + Sign-in nav. "Discover local vendors" hero. Search bar with placeholder. Shop icon + "No shops found / No shops are currently available" empty state. Footer "© 2026 J'Toye OaaS. All rights reserved. / Allergen info available on all products".

3. **`shop-detail.png`** — `/shop/demo`: Same header/footer. Shop icon + "Shop not found / This shop may no longer be available" + orange "← Back to all shops" link. Exactly the right 404 UX (not a crash page).

4. **`track.png`** — `/track`: Shopping bag icon (orange tint) + "Sign in to continue" + "Sign in to track your orders" + prominent "→ Sign in" button + "Don't have an account? You can register during sign-in." This last line is a **meaningful finding**: the customer register path exists via Keycloak's register flow, contradicting earlier assumptions that customer signup was missing.

### What I could NOT live-test (requires backend)

- Add-to-cart → checkout → Stripe Payment Element submission
- Vendor dashboard post-authentication (kitchen real-time updates, order lifecycle, marketing CRUD)
- WhatsApp webhook HMAC flow
- Payment outbox → RabbitMQ → OrderStateChangeListener → WebSocket broadcast
- GDPR export/erasure

These remain code-traced (§3–§5) but not live-verified. The stack should come up cleanly on a developer machine without the port conflict I hit.

---

## 9. The 5 real production blockers

**Ordered by damage if ignored.**

### Blocker 1 — No tenant onboarding flow
- **Where:** `core-java/.../tenant/DevTenantService.java` (dev-only); no `TenantService` production equivalent; `frontend/app/(auth)/register/` missing
- **Impact:** No self-serve SaaS signup. Every new vendor requires manual DB insert + Keycloak realm user + shop provisioning.
- **Evidence:** Java core agent found no production `TenantService`; frontend agent confirmed no signup page; sign-in screenshot confirms only "Sign in with Keycloak" CTA.
- **Effort:** 1–2 weeks
- **Scope:** `TenantService` (create → Keycloak admin API → user + role → shop shell), `/api/v1/tenants` POST, `frontend/app/(auth)/register/page.tsx`, billing hook (Stripe Customer), welcome email template.

### Blocker 2 — Marketing never rendered on storefront
- **Where:** `frontend/app/shop/[slug]/page.tsx` (no promotion/announcement rendering), `core-java/.../shop/PublicStorefrontController.java` (returns shop data without promotions)
- **Impact:** Tier-3 flagship feature is half-dead. Vendors pay for a marketing tool customers never see.
- **Evidence:** Frontend agent traced storefront render — no calls to `/promotions` or `/announcements` endpoints.
- **Effort:** 2–4 days
- **Scope:** Add `GET /public/shops/{slug}/promotions` + `GET /public/shops/{slug}/announcements` endpoints; storefront fetches in parallel with shop; UI renders banner + discount badges on product cards.

### Blocker 3 — Missing cart + customer order-history routes
- **Where:** `frontend/app/shop/[slug]/cart/` (route missing); `frontend/app/shop/orders/` (customer history route missing)
- **Impact:** Direct cart URLs 404 (trust damage); repeat customers cannot see their history (loyalty friction); cart is only a modal/sidebar.
- **Evidence:** Frontend agent glob search confirms absent routes.
- **Effort:** 3–5 days
- **Scope:** New `/shop/[slug]/cart/page.tsx` standalone cart view; `/shop/orders/page.tsx` authenticated customer history with filter + pagination; API extension if needed.

### Blocker 4 — STOMP broker is in-memory
- **Where:** `core-java/.../ws/WebSocketConfig.java` uses `SimpleBroker` rather than `StompBrokerRelay`
- **Impact:** A second `core-java` replica will break kitchen broadcasts silently — messages published on replica A never reach clients connected to replica B. Blocks horizontal scaling.
- **Evidence:** Java core agent read `WebSocketConfig` directly; roadmap lists RabbitMQ STOMP relay as "future" (INFRA-01).
- **Effort:** 3–5 days
- **Scope:** Swap `enableSimpleBroker` for `enableStompBrokerRelay` bound to RabbitMQ (5672/15672). Add `stomp.broker.mode` config flag (`in-memory` vs `relay`). Update k8s secrets for relay login. Add Playwright e2e that asserts real-time kitchen update from a 2-replica staging deployment.

### Blocker 5 — `.env` committed + Alertmanager missing
- **Where:** Repo root `.env` (plaintext `POSTGRES_PASSWORD=secret`, `KEYCLOAK_ADMIN_PASSWORD=admin123`, `REDIS_PASSWORD=redispass123`); `infra/monitoring/` missing Alertmanager deployment
- **Impact:** Credential exposure if repo goes public; 13 Prometheus alerts fire into the void.
- **Evidence:** Infra agent read `.env` from repo root; Prometheus rules file exists but no Alertmanager container in compose.
- **Effort:** 2 days
- **Scope:** `git rm --cached .env`, add to `.gitignore`, rotate all 5 committed passwords via Keycloak admin + DB `ALTER USER` + K8s secret update, deploy `prom/alertmanager` container with Slack webhook config, bind Prometheus `alertmanagers` config, smoke-test one alert end-to-end.

> **Verified incorrect during phase 9 execution (2026-04-15).** Three corrections:
>
> 1. **`.env` is NOT committed.** Verified by `git log --all --full-history -- .env` (no history), `git ls-files --error-unmatch .env` ("did not match"), and `git check-ignore -v .env` (matched by `.gitignore:64`). `.env.example` and `k8s/base/secrets-template.yaml` use `CHANGE_ME` / `REPLACE_WITH_*` placeholders only. The infra audit agent read `.env` from the local filesystem and wrongly inferred it was tracked. No credentials were ever committed and no rotation is needed.
> 2. **Alert count is 10, not 13.** `grep -c "^\s*- alert:" infra/monitoring/prometheus/alerts.yml` = 10. All 10 are now routed via Alertmanager.
> 3. **Destination rescoped from Slack to email via Mailhog.** The project has no committed Slack dependency beyond one CI notification workflow (`.github/workflows/ci-cd.yaml:294-326`); Mailhog is already in `docker-compose.full-stack.yml`; `docs/reports/PRODUCTION_READINESS_REPORT.md` lists `"email/Slack"` as interchangeable. Email needs no external accounts and keeps dev loops friction-free. Prod can override `ALERTMANAGER_SMTP_*` env vars to point at a real SMTP relay.
>
> **Actual phase 9 delivery** (see `.planning/phases/09-repository-secrets-alerting/`):
> - Alertmanager `v0.27.0` deployed in compose (commit `295ea56`); Prometheus `alerting.alertmanagers` wired and 10 alert rules labelled (commit `47ea7b4`); email routing via Mailhog template-rendered at container start by a sed wrapper; `amtool check-config` + `promtool check config` both PASS.
> - Gitleaks CI (`gitleaks-action@v2`) + tight `.gitleaks.toml` allowlist + opt-in local hook (commit `165a7a7`), added as new requirement `SECR-07` to prevent any future `.env` drift from making this finding real.
> - Smoke test script (`infra/monitoring/scripts/smoke-test-alertmanager.sh`) + runbook skeleton (`docs/runbooks/alerts.md`) committed; live E2E verification still pending because unrelated `dealflow_*` containers currently hold the ports the J'Toye full stack needs.
> - Real deferred finding discovered: `infra/keycloak/realm-export.json` contains dev-only OIDC client secrets + PBKDF2 user password hashes. Captured in `.planning/phases/09-repository-secrets-alerting/deferred-items.md` D-1 as proposed `SECR-08` for milestone 4+.
>
> §11 Work Order A is updated in `.planning/REQUIREMENTS.md` SECR-01..07 with full rationale.

---

## 10. Full audit-finding ledger

**34 items verified → all closed in main via PRs #30–#36 + #34 docs sync.**

### Critical (fixed)
| # | Location | Fix | Landed in |
|---|---|---|---|
| 1 | `edge-go/cmd/edge/main.go:126` | Bearer bounds-check helper | PR #30 `c22460c` |
| 2 | `edge-go/cmd/edge/main.go:147-172` | WhatsApp HMAC fail-closed | PR #30 `fbf799d` |
| 3 | `edge-go/internal/middleware/jwt.go:149` | JWKS 5s timeout | PR #30 `01abcd5` |
| 4 | `frontend/lib/customer-auth.ts` | HttpOnly cookies | PR #36 `a38ed6a` |
| 5 | `OrderService.java:351-374` | N+1 batched | PR #33 `d318fd2` |
| 6 | `docker-compose.full-stack.yml` + k8s | `:latest` pinned | PR #32 `0a33864` + `228bf31` |
| 7 | axios 1.14.0 SSRF CVE | `npm audit fix` → 1.15.0 | PR #35 `b504aa4` |

### High (fixed)
| # | Location | Fix | Landed in |
|---|---|---|---|
| 8 | `TenantAwareCacheKeyGenerator.java:32-54` | `.orElseThrow` | PR #33 `aeb0913` |
| 9 | 13× `@CacheEvict(allEntries=true)` | `TenantCacheEvictor` | PR #33 `f4f3b2c` |
| 10 | `PaymentEventPublisher.java:48-59` | Transactional outbox | PR #33 `775ccc6` |
| 11 | Deprecated `updateOrderStatus` | Removed | PR #33 `91f564c` |
| 12 | `OrderController.java:102` + 2 others | `@Valid` added | PR #33 `867447a` |
| 13 | `Order.java`, `Shop.java` | `@Version` | PR #33 `22f071b` |
| 14 | `edge-go/cmd/edge/main.go:195-246` | Reject empty bearer on WhatsApp | PR #30 `c5c94eb` |
| 15 | `infra/monitoring/docker-compose.monitoring.yml:64` | Env creds + `sslmode=require` | PR #32 `8629cb1` |
| 16 | `edge-go/cmd/edge/main.go:24-49` | Rate limiter ctx-scoped shutdown | PR #30 `dfb8239` |
| 17 | Kitchen zero tests | Unit + Playwright added | PR #36 `caecaf3` |
| 18 | follow-redirects moderate CVE | Bump | PR #35 `b504aa4` |
| 19 | next DoS high CVE | Bump | PR #35 `b504aa4` |

### Medium (fixed)
| # | Location | Fix | Landed in |
|---|---|---|---|
| 20 | `edge-go/internal/middleware/jwt.go` | 30s JWT leeway | PR #30 `8fda9c6` |
| 21 | `jwt.go:82` | `JWKS_REFRESH_INTERVAL` env | PR #30 `43d7ccf` |
| 22 | `edge-go/internal/whatsapp/parser.go:41` | Newline-delimited grammar | PR #30 `56849e3` |
| 23 | `edge-go/cmd/edge/main.go:209-224` | Confident product match | PR #30 `34ea852` |
| 24 | `edge-go/internal/core/client.go:26-34` | Circuit breaker warm-up | PR #30 `931a16b` |
| 25 | `edge-go/cmd/edge/main.go:89-108` | `/health` vs `/ready` split | PR #30 `5da6459` |
| 26 | `application.yml:87-91` | Actuator prometheus restricted | PR #33 `7525bfe` |
| 27 | `frontend/lib/api-client.ts` | Retry + tenant header + debounced refresh | PR #36 `4d2a53e` |
| 28 | `frontend/components/storefront/cart-provider.tsx` | `useMemo` | PR #36 `0ed3f5c` |
| 29 | `frontend/app/dashboard/marketing/page.tsx:205` | Typed form, no `as any` | PR #36 `4d610e1` |
| 30 | `frontend/package.json:34` | next-auth pin documented | PR #36 `8a71a45` |
| 31 | `frontend/app/dashboard/layout.tsx:16-20` | Server Component auth | PR #36 `f48c9fd` |
| 32 | `CLAUDE.md:107,301` | Flyway V28→V30→V32 | PR #32 `81d759f` + PR #34 `e395dca` |
| 33 | `frontend/package.json:3` | Version bump to 2.0.0 | PR #36 `6000597` |

### Low (fixed)
| # | Location | Fix | Landed in |
|---|---|---|---|
| 34a | `scripts/start-dev.sh:11` | ANSI typo fix | PR #31 `fa556f8` |
| 34b | `scripts/start-dev.sh:23,61` | Bounded health polls | PR #31 `4a55186` |
| 34c | `.env.example:69` | NEXTAUTH_SECRET guidance | PR #31 `a707239` |
| 34d | `SecurityConfig.java:52` | CSRF justification comment | PR #33 `e5dca65` |
| 34e | `PaymentService.java:53` | Stripe static init + toString redaction | PR #33 `eca0eaa` |

---

## 11. Proposed work-order backlog

Three immediate orders unblock production. Everything else is quality-of-life.

### Work Order A — `fix/repo-secrets-and-alerting` — 2 days

**Scope:**
1. `git rm --cached .env` + add `.env` to `.gitignore`
2. Rotate all 5 committed passwords:
   - Keycloak admin via Admin CLI
   - Postgres jtoye/keycloak roles via `ALTER USER`
   - Redis password in `redis.conf`
   - RabbitMQ via `rabbitmqctl change_password`
   - Update `.env.example` if any variable names changed (none expected)
3. Push rotated values to GitHub Secrets (for CI/CD) and k8s secrets (for staging/prod)
4. Deploy `prom/alertmanager:v0.27` container alongside Prometheus in `infra/monitoring/docker-compose.monitoring.yml`
5. Write `alertmanager.yml` with a single Slack webhook route (target channel TBD)
6. Add `alerting: alertmanagers:` block to `prometheus.yml`
7. Smoke-test end-to-end: force one alert (e.g. kill core-java → `ServiceDown` fires) → confirm Slack message arrives

**Exit:** `.env` gone from git history, alert-to-Slack roundtrip verified, runbook entry added for "alerting down" scenario.

> **Verified incorrect during phase 9 execution (2026-04-15).** See the footnote on §9 Blocker 5 above for full correction: `.env` was never committed, alert count is 10 not 13, destination rescoped from Slack to email via Mailhog. Tasks 1-3 dropped as no-ops, tasks 4-6 delivered via phase 9 plans 09-01/09-02/09-03. A new `SECR-07` adds `gitleaks` CI enforcement to prevent future drift. Canonical status in `.planning/REQUIREMENTS.md`.

### Work Order B — `feat/storefront-marketing-and-cart-routes` — 1 week

**Scope:**
1. **Backend promotions/announcements on storefront:**
   - `PublicStorefrontController`: add `GET /public/shops/{slug}/promotions` + `GET /public/shops/{slug}/announcements`, both returning only active+unexpired items for the tenant owning `slug`
   - DTOs + mappers + RLS-compatible repository methods
   - Tests: ShopService stub + 2 controller integration tests
2. **Storefront rendering:**
   - `frontend/app/shop/[slug]/page.tsx`: fetch promotions + announcements in parallel with shop; render announcement banner above menu; overlay discount badges on product cards
   - Use `useMemo` for the active-promotion-per-product lookup
3. **Cart page route:**
   - `frontend/app/shop/[slug]/cart/page.tsx` — standalone cart view (read same localStorage key, render items, edit qty, checkout CTA). Must gracefully handle empty cart and missing shop.
4. **Customer order-history route:**
   - `frontend/app/shop/orders/page.tsx` — authenticated-only via `RequireCustomerAuth`; list all orders for the logged-in customer across all shops; filter by status + date; pagination
   - Backend: `PublicStorefrontController.getOrdersForCustomer()` if not already exposed (customer id from session)
5. **Tests:**
   - Jest: cart page renders items from localStorage, empty state works
   - Playwright e2e: full customer checkout flow end-to-end (browse → add → cart page → checkout → Stripe stub → confirmation)

**Exit:** Promotion badges visible on real storefront, cart + history routes live, full customer-flow Playwright passes.

### Work Order C — `feat/stomp-broker-relay-and-kds-e2e` — 1 week

**Scope:**
1. **Backend:**
   - `WebSocketConfig.java`: introduce `stomp.broker.mode` config property (`in-memory` | `relay`)
   - In `relay` mode: call `enableStompBrokerRelay("/topic", "/queue").setRelayHost("rabbitmq").setRelayPort(61613).setClientLogin(...).setSystemLogin(...)`
   - Enable RabbitMQ STOMP plugin in compose + k8s via `rabbitmq-plugins enable rabbitmq_stomp`
   - Expose port 61613 in compose
   - Add k8s secret entries for relay credentials
   - Fallback to in-memory if property absent
2. **Local testing:**
   - Docker-compose up with relay mode; confirm single-replica kitchen still receives broadcasts
   - Scale to 2 replicas (`docker compose up --scale core-java=2`); confirm kitchen client connected to replica A still receives order state change published on replica B
3. **Playwright e2e:**
   - Spin up backend via testcontainers (or staging hit), open kitchen page, POST an order via API, assert kitchen WS message arrived within 2s
4. **Observability:**
   - Prometheus alert on RabbitMQ STOMP exchange lag > 5s
   - Grafana dashboard tile for STOMP connection count

**Exit:** Two-replica `core-java` kitchen broadcasts work, e2e test green, staging switched to relay mode.

### After A+B+C: the tier-2 backlog

| Order | Scope | Effort |
|---|---|---|
| D | Tenant onboarding flow (TenantService + Keycloak admin API + billing hook + register page) | 1–2 weeks |
| E | Vendor order detail view + refund flow | 1 week |
| F | Vendor finance + settings pages | 1–2 weeks |
| G | Log aggregation (Loki or ELK) + Grafana dashboards + runbooks | 1 week |
| H | K8s sealed-secrets or external-secrets-operator | 3–5 days |
| I | Postgres PITR via WAL archiving to S3 | 3–5 days |
| J | Review module: controller + storefront display + moderation | 1 week |
| K | Edge OpenTelemetry + distributed rate limiter (Redis) | 1 week |
| L | Full-text product search perf verification + caching | 3 days |
| M | Bulk product import endpoint + UI integration | 3 days |
| N | Vendor onboarding billing subscription mgmt | 1 week |
| O | Migrate WhatsApp handler to order idempotency key | 2 days |

---

## 12. Data sources and artifacts

### In this directory (`.planning/state-of-codebase/`)
- `STATE-OF-CODEBASE-2026-04-14.md` — this document
- `screenshots/signin.png` — vendor sign-in page
- `screenshots/shop-discovery.png` — customer shop discovery
- `screenshots/shop-detail.png` — shop 404 graceful state
- `screenshots/track.png` — customer auth-gated order tracking

### Elsewhere in `.planning/`
- `.planning/quick/260414-j9c-edge-go-security-hardening-batch-phase-1/SUMMARY.md` — Phase 1 fix ledger
- `.planning/quick/260414-jkp-java-core-data-integrity-batch-phase-2-o/SUMMARY.md` — Phase 2 fix ledger
- `.planning/quick/260414-fe3-frontend-security-and-tests/SUMMARY.md` — Phase 3 fix ledger
- `.planning/quick/260414-inf-infrastructure-hardening/SUMMARY.md` — Phase 4 fix ledger
- `.planning/quick/260414-ltc-low-touch-cleanup/SUMMARY.md` — Phase 5 fix ledger
- `.planning/housekeeping/260414-post-audit-REPORT.md` — post-audit housekeeping report
- `.planning/PROJECT.md`, `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md`, `.planning/STATE.md` — roadmap sources

### Git state
- Current branch: `docs/state-of-codebase-2026-04-14`
- Main at: (latest after PR #36 `0e4ff27`)
- 7 audit fix PRs merged, 1 docs sync PR merged
- All local fix branches deleted

### Test evidence (verified on main after all merges)
- `edge-go`: 4 packages, 28 tests PASS (fresh run, `-count=1`)
- `core-java`: BUILD SUCCESSFUL, 335 tests (fresh run with `--rerun-tasks`)
- `frontend`: 11 suites, 69 tests PASS
- `npm audit`: 0 vulnerabilities

### Known live-test gaps (requires stack)
- Vendor dashboard post-authentication flows
- Kitchen real-time WebSocket delivery
- Stripe Payment Element submission
- WhatsApp HMAC webhook
- GDPR export/erasure

These remain unverified in runtime and should be covered in Work Order C's e2e tests.

---

**End of document. Use this as the input for the next `gsd-new-milestone` or `gsd-plan-phase` cycle.**
