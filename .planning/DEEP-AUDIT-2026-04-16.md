# J'Toye OaaS Deep Audit Report

**Date:** 2026-04-16
**Branch:** feature/phase-11-plan-revision
**Method:** 8 parallel deep-dive agents + direct verification
**Scope:** Full codebase (36k LOC across Java, TypeScript, Go)

---

## CRITICAL VULNERABILITIES (Must Fix Immediately)

### TENANT-01: ScheduledCleanupService deletes ALL tenants' draft orders
- **File:** `core-java/src/main/java/uk/jtoye/core/config/ScheduledCleanupService.java:38-51`
- **Issue:** `@Scheduled` method has no TenantContext set. `orderRepository.findByStatus(DRAFT)` runs with NULL tenant, bypassing RLS. `deleteAll()` removes stale drafts from ALL tenants.
- **Impact:** CRITICAL - cross-tenant data destruction on a daily cron
- **Fix:** Wrap in per-tenant loop with `TenantContext.set(tenantId)` / `clear()` in finally

### TENANT-02: PaymentEventOutboxFlusher queries ALL tenants' payment events
- **File:** `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java:55-59`
- **Issue:** `@Scheduled` method with no TenantContext. `payment_event_outbox` table has NO RLS policy. Query returns PENDING events across all tenants.
- **Impact:** CRITICAL - payment event data leaks across tenants; downstream RabbitMQ listeners receive cross-tenant events
- **Fix:** (1) Add RLS to `payment_event_outbox` table, (2) Refactor flusher to iterate per tenant

### TENANT-03: shop_promotions and shop_announcements have unrestricted SELECT
- **Files:** `V28__shop_config.sql:28`, `V29__vendor_marketing.sql:27`
- **Policy:** `FOR SELECT USING (true)` - allows reading ALL tenants' promotions/announcements
- **Impact:** HIGH - tenant marketing data leaks via `/public/shops/{slug}/promotions`
- **Fix:** Change policy to `USING (tenant_id = current_tenant_id() OR EXISTS (SELECT 1 FROM shops WHERE shops.id = shop_promotions.shop_id AND shops.published = true))`

### TENANT-04: reviews have unrestricted SELECT
- **File:** `V27__customer_reviews.sql:27-29`
- **Policy:** `FOR SELECT USING (true)` - allows reading ALL tenants' reviews
- **Impact:** HIGH - customer reviews and ratings leak across tenants
- **Fix:** Add tenant filter to SELECT policy

### SEC-01: Keycloak client secret in example file
- **File:** `frontend/.env.local.example:27`
- **Content:** `KEYCLOAK_CLIENT_SECRET=core-api-secret-2026` (real secret, not placeholder)
- **Fix:** Replace with `KEYCLOAK_CLIENT_SECRET=CHANGE_ME`

### SEC-02: WebSocket CORS allows all origins
- **File:** `core-java/.../websocket/WebSocketConfig.java`
- **Content:** `.setAllowedOriginPatterns("*")`
- **Fix:** Restrict to known origins

### SEC-03: JWT passed via WebSocket query parameter
- **File:** `core-java/.../websocket/JwtHandshakeInterceptor.java`
- **Issue:** `/ws?token=<jwt>` - token visible in browser history, logs, proxy logs
- **Fix:** Use STOMP CONNECT frame headers

### MON-01: No disk space Prometheus alert
- **File:** `infra/monitoring/prometheus/alerts.yml`
- **Fix:** Add node_filesystem alert at 85% threshold

### MON-02: No Keycloak health alert
- **Fix:** Add `up{job="keycloak"} == 0` rule

---

## CODE QUALITY FINDINGS

### Verified from line-by-line service review:

| Finding | Severity | File | Line |
|---------|----------|------|------|
| Stock validated at creation but decremented at confirmation - race window | RISK | OrderService.java | 117-128 |
| FinancialTransactionService.getSummary() loads ALL records into memory | SMELL | FinancialTransactionService.java | 111-149 |
| Unbounded pagination - no max-page-size configured | RISK | application.yml | missing |
| tenantId field exposed in OrderDto/CustomerDto responses | SMELL | OrderDto.java, CustomerDto.java | - |
| Blocking reactive calls (.block()) on state machine operations | SMELL | OrderStateMachineService.java | 52-116 |
| Frontend silently swallows API errors (4 catch blocks return empty) | RISK | app/shop/[slug]/page.tsx | - |
| No unhandledrejection handler in frontend | RISK | - | - |

### Verified OK (no issues found):
- Order state machine transitions (properly validates, no invalid paths)
- Optimistic locking on orders/shops (@Version field)
- N+1 prevention (adjustStockInBatch uses findAllById)
- Cache invalidation (targeted TenantCacheEvictor, not allEntries)
- Stripe webhook signature verification (fail-closed)
- PaymentService tenant context isolation (try-finally cleanup)
- Frontend cart localStorage isolation (keyed by shop slug)
- No XSS (no dangerouslySetInnerHTML usage)
- Go circuit breaker, rate limiter, graceful shutdown all correct
- Go JWT validation with JWKS refresh, clock skew tolerance, issuer verification

---

## MULTI-TENANCY MAP

### Tables WITH RLS (15 tables):
shops, products, orders, order_items, customers, financial_transactions, reviews, shop_promotions, shop_announcements, shops_aud, products_aud, orders_aud, order_items_aud, customers_aud, financial_transactions_aud

### Tables WITHOUT RLS (3 tables):
- `payment_event_outbox` - **VULNERABILITY** (should have RLS)
- `revinfo` - OK (Hibernate Envers global revision tracker)
- `tenants` - OK (admin-only tenant registry)

### RLS Policies with `USING (true)` (UNRESTRICTED READ):
- `shop_promotions_read` - **VULNERABILITY**
- `shop_announcements_read` - **VULNERABILITY**
- `reviews_tenant_read` - **VULNERABILITY**

### @Scheduled methods without TenantContext:
- `ScheduledCleanupService.cleanupStaleDraftOrders()` - **VULNERABILITY** (deletes across tenants)
- `PaymentEventOutboxFlusher.flushPending()` - **VULNERABILITY** (reads across tenants)

### @RabbitListener with TenantContext:
- `OrderStateChangeListener` - OK (explicitly sets TenantContext from event)

### @Async without TenantContext:
- `EmailNotificationService` - OK (no database access, just sends email)

---

## SECURITY MAP

### permitAll() endpoints:
- `/`, `/health`, `/actuator/health`, `/actuator/info` - OK
- `/v3/api-docs/**`, `/swagger-ui/**` - WARNING (should gate in prod)
- `/public/**` - OK (intentional public storefront)
- `/ws/**` - WARNING (WebSocket handshake, JWT validated in interceptor)

### Guest/Anonymous paths:
- Guest order tracking relies entirely on RLS (no app-layer validation)
- Guest order creation sets tenant from shop lookup (safe)

---

## INFRASTRUCTURE

### Docker:
- No USER directive in any Dockerfile (running as root)
- No resource limits in docker-compose services
- STOMP port 61613 properly exposed for relay mode

### Kubernetes:
- SecurityContext present on all deployments (runAsNonRoot, drop ALL)
- Resource limits configured for all pods
- No NetworkPolicies
- Secrets use stringData (not sealed)
- PodDisruptionBudgets present

### Monitoring:
- 11 Prometheus alert rules (10 original + StompBrokerLag)
- Missing: disk space, Keycloak health, Redis cache hit rate, certificate expiry
- Alertmanager: single email receiver (Mailhog in dev)
- Grafana: STOMP dashboard auto-provisioned

---

## TEST COVERAGE

| Stack | Test Files | Test Methods | Status |
|-------|-----------|-------------|--------|
| Java (JUnit 5) | 48 | 385 | All pass |
| Frontend (Jest) | 13 suites | 76 | All pass |
| Playwright E2E | 3 specs | - | Exist, not run |
| Go Gateway | **0** | **0** | **No tests** |
| **Total** | 64 | **461** | 461 passing |

### npm: 0 vulnerabilities, 16 outdated packages
### Gradle: 0 CVEs

---

## DEPENDENCY STATUS

### npm outdated (notable):
- tailwindcss 3.4.19 -> 4.2.2 (major)
- typescript 5.9.3 -> 6.0.2 (major)
- lucide-react 0.562.0 -> 1.8.0 (major)
- next-auth 5.0.0-beta.30 -> 5.0.0-beta.31

---

## DOCUMENTATION STATUS

| Document | Status |
|----------|--------|
| CLAUDE.md versions | OK |
| CLAUDE.md schema V32 | OK |
| CLAUDE.md test count (310+) | Understated (actual: 461) |
| ROADMAP Phases 9-10 | Branch divergence (complete on main, not on this branch) |
| STATE.md counters | Stale (will resolve on merge) |

---

## PRIORITY ACTION PLAN

### P0 - CRITICAL (Fix before any deployment)
1. Fix ScheduledCleanupService cross-tenant deletion
2. Add RLS to payment_event_outbox + fix flusher
3. Fix shop_promotions/announcements/reviews RLS policies
4. Replace secret in .env.local.example
5. Restrict WebSocket CORS origins

### P1 - HIGH (Fix before production)
6. Add pagination max-page-size limit
7. Add disk space Prometheus alert
8. Add Keycloak health alert
9. Write Go gateway tests
10. Move JWT from WS query param to STOMP headers
11. Add K8s Sealed Secrets

### P2 - MEDIUM
12. Fix stock race condition (validate at confirmation)
13. Fix getSummary() to use DB aggregation
14. Add error.tsx boundaries to Next.js
15. Add application-layer tenant validation for guest tracking
16. Document RLS dependency in native query comments
17. Add NetworkPolicies to K8s
18. Add security headers to Spring responses

### P3 - LOW
19. Remove tenantId from response DTOs
20. Fix blocking reactive calls in state machine
21. Add CSP headers
22. Log frontend API errors before swallowing
23. Generate OpenAPI for Go gateway

---

## ADDITIONAL FINDINGS (Deep Dive Wave 2)

### Auth Flow Vulnerabilities
- **AUTH-01 [HIGH]**: Non-kitchen WebSocket destinations bypass tenant validation (TenantChannelInterceptor.java:93-96). Only `/topic/kitchen/*` is checked — any future broadcast channel would be unprotected.
- **AUTH-02 [CRITICAL RISK]**: If DB_USER env is set to superuser (`jtoye` instead of `jtoye_app`), ALL RLS policies are bypassed. Production must enforce non-superuser connection.
- **AUTH-03 [MEDIUM]**: NextAuth refresh token error doesn't clear session (auth.ts:84) — stale tokens persist.
- **AUTH-04 [MEDIUM]**: Go gateway validates tenant_id at route level, not middleware level — error-prone pattern.

### Error Handling Silent Failures
- **ERR-01 [SILENT]**: Stripe payment succeeds but charge details retrieval fails — user card info (brand/last4) lost silently (PaymentService.java:157-166)
- **ERR-02 [SILENT]**: Financial transaction creation fails after order state transition — inconsistent DB state (PaymentService.java:187-192)
- **ERR-03 [SILENT]**: S3 image upload fails but product metadata persists — dangling image reference (StorageService.java:84-92)
- **ERR-04 [SILENT]**: Email SMTP failures swallowed in @Async — user never knows confirmation wasn't sent (EmailNotificationService.java:145-151)
- **ERR-05 [SILENT]**: WebSocket broadcast failures caught and logged but order update never retried (OrderStateChangeListener.java:51-61)
- **ERR-06 [BUG]**: No error.tsx global error boundary in Next.js frontend

### Code Quality
- **CQ-01 [RISK]**: Stock validated at order creation but decremented at confirmation — race window where two orders pass validation but exceed available stock (OrderService.java:117-128)
- **CQ-02 [RISK]**: FinancialTransactionService.getSummary() calls findAll() and aggregates in memory — will OOM on high-volume tenants (lines 111-149)
- **CQ-03 [RISK]**: No max-page-size configured — `?size=999999` causes memory exhaustion (missing spring.data.web.pageable.max-page-size)

### Go Gateway Test Gaps (31 missing tests)
- Rate limiter: 5 tests (0 exist — core DDoS protection untested)
- Circuit breaker: 5 tests (incomplete — may not trip correctly)
- JWT validation edge cases: 6 tests (expired, wrong issuer, missing kid)
- WhatsApp webhook: 9 integration tests (signature, errors, product search)
- Health endpoints: 5 tests (liveness/readiness untested)
- Proxy routing: 3 tests (header forwarding, status mapping)

### Infrastructure (from infra agent)
- Dockerfiles run as root (no USER directive)
- No resource limits in docker-compose services
- K8s: No NetworkPolicies (lateral movement unrestricted)
- K8s: Missing startupProbe on frontend deployment
- Alertmanager: Single email receiver only (no PagerDuty/Slack escalation)
- Missing alerts: disk space, Keycloak health, Redis cache hit rate, certificate expiry

### Infrastructure Deep-Dive (from infra agent — 129 items checked)
- **Dockerfiles**: All 3 use multi-stage builds, non-root users, HEALTHCHECKs. [OK]
- **INFRA-02 [CRITICAL]**: `redis-exporter` service missing — prometheus.yml scrape job configured but service not deployed (will always fail)
- **INFRA-03 [CRITICAL]**: `frontend` depends_on lacks `condition: service_healthy` — race condition with core-java
- **INFRA-04 [MISCONFIGURED]**: core-java CPU limit 1000m too tight for 3 replicas under HPA — recommend 2000m
- **INFRA-05 [MISCONFIGURED]**: core-java preStop `sleep 10` too short — Spring Boot graceful shutdown needs 35-40s
- **INFRA-06 [MISCONFIGURED]**: StompBrokerLag `for: 5s` too aggressive — will flap, recommend 30s
- **INFRA-07 [MISCONFIGURED]**: HighErrorRate threshold 5% too lenient for <99.9% SLA — recommend 2-3%
- **INFRA-08 [MISCONFIGURED]**: FrequentGarbageCollection threshold 10/s triggers on normal load — recommend 50/s
- **INFRA-09 [MISCONFIGURED]**: NoOrdersCreated URI hardcoded `/orders` — misses `/api/v1/orders`
- **INFRA-10 [GAP]**: No Grafana dashboards for: JVM health, database, business metrics, application overview (only STOMP dashboard exists)
- **INFRA-11 [GAP]**: No Alertmanager inhibition rules — database down cascades noisy pool/connection alerts
- **INFRA-12 [GAP]**: No backup verification in pg-backup cronjob — no `pg_restore -l` integrity check
- **INFRA-13 [GAP]**: No certificate expiry monitoring despite cert-manager in k8s
- **INFRA-14 [GAP]**: postgres-exporter has no healthcheck in monitoring compose
- **INFRA-15 [GAP]**: No alert runbook documentation (monitoring README lists as TODO)
