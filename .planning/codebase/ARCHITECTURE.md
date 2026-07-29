# Architecture

**Analysis Date:** 2026-04-18

## Pattern Overview

**Overall:** Tiered multi-tenant SaaS architecture split across three independently-deployable tiers — Next.js 16 frontend, Go 1.22 edge gateway, Spring Boot 3.4.2 core API — backed by PostgreSQL 15 with Row-Level Security. Post-v2.1 the core tier is horizontally scalable: an external STOMP broker relay on RabbitMQ replaces the in-memory SimpleBroker, so kitchen display WebSocket broadcasts survive across N replicas.

**Key Characteristics:**
- Multi-tenant isolation enforced at **four** layers: JWT claim (Keycloak), `JwtTenantFilter`/HTTP, `TenantChannelInterceptor`/STOMP, PostgreSQL RLS (database).
- Service–Repository pattern for REST business logic; dedicated `PublicStorefrontService` for unauthenticated customer-facing reads.
- Event-driven state machine for order workflows (Spring State Machine) with `OrderEventPublisher` for side-effects.
- Tenant-aware Redis caching via `TenantAwareCacheKeyGenerator` with explicit `TenantCacheEvictor` hooks.
- Edge-to-Core data synchronisation via high-volume batch API with circuit breaker (`sony/gobreaker`).
- Stripe inbound webhooks terminate at Core, persist to `PaymentEventOutbox`, then drain async via `PaymentEventOutboxFlusher`.
- Real-time push over native WebSocket+STOMP (no SockJS), with broker mode selectable per-replica via `stomp.broker.mode`.
- Observability stack: Prometheus scrape → Alertmanager routing → Grafana dashboards; 14 alert rules in `infra/monitoring/prometheus/alerts.yml` routed via the new v2.1 Alertmanager tier.

## Layers

**Presentation (Frontend):**
- Purpose: B2B admin dashboards and B2C customer storefronts
- Location: `frontend/app/`, `frontend/components/`
- Contains: Next.js 16 App Router pages, React 19 components, React Hook Form, NextAuth v5 sessions, STOMP client (`@stomp/stompjs`) for kitchen display and order-status topics
- Depends on: Core Java API via `NEXT_PUBLIC_API_URL`, Keycloak OIDC via NextAuth
- Used by: Browsers (port 3000, dev port 3100 when MCP server holds 3000)

**API Gateway (Edge Go):**
- Purpose: Token-bucket rate limiting, JWT validation, circuit-breaker-protected fan-out to Core, WhatsApp webhook intake
- Location: `edge-go/cmd/edge/main.go`, `edge-go/internal/`
- Contains: Gin router, `golang-jwt/jwt v5` middleware, Core HTTP client with `sony/gobreaker`, zap structured logging
- Depends on: Core Java API, Keycloak JWKS endpoint
- Used by: Storefront pages, mobile clients, external webhook integrations
- Tests: 57 passing (up from 21 in v2.0; hardened in P1 audit PR #40)

**Business Logic (Spring Boot Core):**
- Purpose: Full REST API surface — CRUD, state management, tenant isolation, Stripe payments, real-time STOMP
- Location: `core-java/src/main/java/uk/jtoye/core/`
- Contains: REST controllers (`/api/v1/**` + `/public/**` + `/ws`), services, JPA repositories, MapStruct mappers, Spring State Machine, WebSocket broker configuration, scheduled flushers
- Depends on: PostgreSQL (RLS-enabled), Redis (cache + Lettuce pool), RabbitMQ (AMQP + STOMP plugin), Stripe API, S3/MinIO, Keycloak
- Used by: Frontend, Edge gateway, batch sync, Stripe webhook callbacks
- Tests: 341 passing

**Real-time Messaging (WebSocket/STOMP):**
- Purpose: Push kitchen-display order events and storefront order-status changes to connected clients
- Location: `core-java/src/main/java/uk/jtoye/core/websocket/`
- Contains: `WebSocketConfig`, `TenantChannelInterceptor`, `JwtHandshakeInterceptor`
- Modes (new in v2.1):
  - `in-memory` — Spring `SimpleBroker` on `/topic` (single-replica dev)
  - `relay` — `StompBrokerRelay` → RabbitMQ STOMP plugin on `:61613` (required for ≥2 replicas)
- Depends on: RabbitMQ `rabbitmq_stomp` plugin (enabled in `infra/rabbitmq/enabled_plugins`), `JwtDecoder`, `TenantContext`

**Data Access (JPA/Spring Data):**
- Purpose: ORM abstraction for tenant-scoped database queries
- Location: `core-java/src/main/java/uk/jtoye/core/*/` (repository interfaces per domain)
- Contains: `JpaRepository` extensions, `@Query` methods, Envers `@Audited` hooks
- Depends on: PostgreSQL JDBC driver 42.7.3, Flyway 33 migrations (`V1__` … `V33__fix_rls_policies.sql`)

**Database (PostgreSQL 15):**
- Purpose: Multi-tenant storage with RLS enforcement + Envers audit trails + payment-event outbox
- Location: Schema defined in `core-java/src/main/resources/db/migration/` (**33** Flyway migrations as of v2.1)
- Contains: Domain tables (shops, products, orders, customers, financial_transactions, reviews, promotions, announcements), `payment_event_outbox`, `_aud` audit tables, `REVINFO`, RLS policies per tenant-scoped table
- Latest migration: `V33__fix_rls_policies.sql` (promotions/announcements/reviews/payment_event_outbox policies)

**Observability Tier (new in v2.1):**
- Purpose: Metrics scrape, alert routing, dashboards
- Location: `infra/monitoring/`
- Contains: Prometheus (`prometheus.yml`, `alerts.yml` — 14 rules), Alertmanager (`alertmanager.yml.tmpl` + `entrypoint.sh` for env-var substitution), Grafana (provisioning + `stomp-dashboard.json`), smoke tests
- Routes: Critical alerts → Mailhog (dev) or SMTP receiver (prod); warning alerts → same pipeline grouped by `severity`.

## Request Flow (ASCII)

```
                                     ┌────────────────────────────────┐
                                     │           Keycloak             │
                                     │   (realm: jtoye-dev, JWKS)     │
                                     └──────────────┬─────────────────┘
                                                    │ OIDC / JWKS
      ┌────────────────────────┐                    │
      │  Browser (3000/3100)   │  HTTPS + Bearer    │
      │  Next.js 16 + NextAuth │────────────────────┼────────┐
      └─────────────┬──────────┘                    │        │
                    │                               │        │
                    │ REST (+ STOMP over WS)        │        │
                    ▼                               │        │
      ┌────────────────────────┐                    │        │
      │    Edge Go (:8080)     │  JWKS fetch        │        │
      │  Gin + jwt + gobreaker │────────────────────┘        │
      │  rate-limit: 20 RPS    │                             │
      └─────────────┬──────────┘                             │
                    │ HTTP fan-out                           │
                    ▼                                        ▼
      ┌─────────────────────────────────────────────────────────────┐
      │              Spring Boot Core (:9090)  — N replicas         │
      │                                                             │
      │   ┌───────────────┐  ┌──────────────────┐  ┌─────────────┐  │
      │   │ JwtTenantFilt │→ │ REST Controllers │→ │  Services   │  │
      │   └───────────────┘  └──────────────────┘  └──────┬──────┘  │
      │          │                                        │         │
      │          ▼                                        ▼         │
      │  TenantContext (ThreadLocal<UUID>)        JPA Repositories  │
      │          │                                        │         │
      │          │  SET LOCAL app.current_tenant_id       │         │
      │          └────────────────────────────────────────┘         │
      └───────┬─────────────────┬──────────────────┬────────────────┘
              │                 │                  │
              ▼                 ▼                  ▼
     ┌────────────────┐  ┌───────────┐   ┌─────────────────────────┐
     │ PostgreSQL 15  │  │  Redis 7  │   │     RabbitMQ 4.3.4      │
     │  RLS policies  │  │  (cache)  │   │  AMQP + STOMP (:61613)  │
     │  Envers audit  │  │           │   │     plugin: stomp       │
     └────────────────┘  └───────────┘   └─────────────────────────┘
                                                     ▲
                                                     │ StompBrokerRelay
                                                     │ (when mode=relay)
```

## Multi-Tenant Isolation Model

Tenant isolation is enforced at **four** defence-in-depth layers. Any path that bypasses one is caught by the next.

**Layer 1 — JWT (Keycloak):**
- Issuer: Keycloak realm `jtoye-dev` (dev) / production realm
- Claim sources checked in order: `tenant_id`, `tenantId`, `tid` (see `JwtTenantFilter` and `TenantChannelInterceptor.extractTenantId`)

**Layer 2 — HTTP Filter:**
- `core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter.java` runs per request, extracts claim, populates `TenantContext.set(uuid)`.
- `TenantContextCleanupFilter` clears the ThreadLocal in a `finally` to prevent leakage across pooled threads.
- `TenantSetLocalAspect` issues `SET LOCAL app.current_tenant_id = ...` on every transactional method, driving the RLS policy.

**Layer 3 — STOMP Channel Interceptor (v2.1 hardened):**
- `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java` implements `ExecutorChannelInterceptor` (runs clean-up on handler thread).
- `CONNECT`: validates JWT from STOMP `Authorization: Bearer ...` header first, falls back to handshake session attribute (P1 audit fix — avoids URL query-param leakage).
- `SUBSCRIBE`: **all** `/topic/**` destinations must have a tenant UUID segment at index 3 (e.g. `/topic/kitchen/{tenantId}/{shopId}`); cross-tenant subscriptions throw `MessageDeliveryException`.
- `SEND`: propagates `TenantContext` for downstream `@MessageMapping` handlers.
- `afterMessageHandled`: always `TenantContext.clear()`.

**Layer 4 — PostgreSQL RLS:**
- Every tenant-scoped table carries `tenant_id UUID NOT NULL` + a policy `USING (tenant_id = current_setting('app.current_tenant_id')::uuid)`.
- `V33__fix_rls_policies.sql` (v2.1) closed gaps on `promotions`, `announcements`, `reviews`, `payment_event_outbox`.
- Policies are enforced for `SELECT`, `INSERT`, `UPDATE`, `DELETE` — a bug in the service layer cannot leak across tenants.

## Caching Strategy

**Store:** Redis 7 (Lettuce client, pool: 8 active/8 idle dev, 20 active/10 idle prod).

**Key generator:** `core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java` — prefixes every key with `tenantId` from `TenantContext`. Prevents cross-tenant cache hits even if a service forgets to scope its query.

**Configuration:** `CacheConfig.java` defines per-cache TTLs (products 10 min, shops 15 min). Test profile disables caching.

**Eviction:** `TenantCacheEvictor.java` provides explicit tenant-scoped eviction for multi-tenant admin scenarios. Mutation methods also carry `@CacheEvict` for targeted invalidation.

## Order State Machine

**Location:** `core-java/src/main/java/uk/jtoye/core/order/`

**States (`OrderStatus`):** DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED | CANCELLED

**Events (`OrderEvent`):** SUBMIT, CONFIRM, START_PREP, MARK_READY, COMPLETE, CANCEL

**Wiring:**
- `OrderStateMachineConfig` — `StateMachineConfigurerAdapter` defines transitions + guards
- `OrderStateMachineService` — wraps state-machine execution inside transactional service methods
- `OrderStateChangeListener` → publishes `OrderStateChangeEvent` → `OrderEventPublisher` pushes to STOMP `/topic/kitchen/{tenantId}/{shopId}` and `/topic/orders/{tenantId}/{orderId}`
- Invalid transitions throw `InvalidStateTransitionException` (400)

## Stripe Webhook Inbound Flow

```
  Stripe Event ─► Edge Go /webhooks/stripe (signature verify)
                       │
                       ▼
             Core PaymentController.handleWebhook
                       │
                       ▼
         PaymentService.processEvent (idempotency check)
                       │
                       ▼
    PaymentEventOutbox row (INSERT, tenant_id, payload, attempts=0)
                       │
                       ▼ (scheduled @Scheduled fixedDelay)
         PaymentEventOutboxFlusher drains rows
                       │
                       ▼
         PaymentEventPublisher → domain side-effects
           (order status update, financial_transaction insert)
```

**Rationale:** Stripe expects 2xx within ~15s. The outbox pattern acknowledges fast, processes async, and survives Core restarts without losing events. `PaymentEventAuditListener` records Envers audit rows for every state change.

## WebSocket Topology (v2.1)

**Single-replica (dev / `in-memory` mode):**
```
Browser ──WS──► Core replica #1 ── SimpleBroker ──► subscribed clients
```

**Multi-replica (`relay` mode — enables horizontal scale):**
```
   Browser A ──WS──► Core replica #1 ──┐
                                       │
   Browser B ──WS──► Core replica #2 ──┼──► RabbitMQ STOMP plugin (:61613)
                                       │       │
   Browser C ──WS──► Core replica #3 ──┘       │
                                               ▼
                                          fan-out across
                                          ALL subscribed replicas
                                          (each forwards to its WS clients)
```

**Switch:** Set `stomp.broker.mode=relay` + `stomp.broker.relay-host=rabbitmq`. `WebSocketConfig.configureMessageBroker` branches on this flag at bean initialization.

**Horizontal-scale story (v2.1 enabled):** Before v2.1, scaling Core to N replicas split WebSocket clients into isolated groups — a publish on replica #1 never reached subscribers on replica #2. With `StompBrokerRelay` enabled, all replicas connect as clients to RabbitMQ's STOMP plugin; broadcasts flow through the broker and fan out across every replica. Verified in `frontend/e2e/stomp-relay.spec.ts` (cross-replica publish→receive within 2s) and `scripts/smoke-test-stomp-relay.sh` (asserts 2 active STOMP connections after `docker compose ... --scale core-java=2`).

**Monitoring:** `stomp_broker_lag_seconds` gauge → `StompBrokerLag` alert in `infra/monitoring/prometheus/alerts.yml` → Alertmanager → ops; visualised by `infra/monitoring/grafana/dashboards/stomp-dashboard.json`.

## Data Flow

**Customer Places Order (Storefront → Edge → Core → DB):**

1. Customer fills cart in `frontend/app/shop/[slug]/cart/page.tsx`, proceeds to `/shop/[slug]/checkout`
2. Frontend POSTs to Edge `/orders` with Bearer JWT + `GuestOrderRequest`
3. Edge validates JWT against Keycloak JWKS, applies rate limit (20 RPS), forwards via circuit-breaker client
4. Core `PublicStorefrontController` (public guest flow) or `OrderController` (authenticated) receives the request; `JwtTenantFilter` populates `TenantContext`
5. `OrderService` creates `Order` entity; `OrderStateMachineService` transitions DRAFT → PENDING
6. `TenantSetLocalAspect` issues `SET LOCAL app.current_tenant_id` → RLS scopes the insert
7. Envers writes to `orders_aud`; `OrderStateChangeListener` fires `OrderEventPublisher` → STOMP broadcast
8. Stripe PaymentIntent created by `PaymentService`; client confirms via Stripe.js
9. Stripe webhook returns asynchronously → `PaymentEventOutbox` drain → order marked CONFIRMED
10. Response: 201 Created + `OrderDto` (MapStruct-mapped)

**Admin Dashboard Fetches Shops (Dashboard → Edge → Core → Cache → DB):**

1. Admin session via NextAuth; JWT attached by `frontend/lib/api-client.ts` axios interceptor
2. GET `/api/v1/shops` via Edge
3. Core `ShopController.list` → `ShopService.getAllShops` (`@Cacheable("shops", keyGenerator="tenantAwareCacheKeyGenerator")`)
4. Cache key: `shops::{tenantId}::{pageNumber}::{pageSize}`
5. Miss → `ShopRepository.findAll(pageable)` with RLS-scoped results → map to `ShopDto` → cache 15 min
6. Hit → return cached `Page<ShopDto>` directly

**Batch Sync (Edge device → Core Sync API):**

1. Offline edge device buffers orders, POSTs `/sync/batch` when online
2. Core `SyncController` validates batch integrity + JWT
3. `SyncService` iterates, upserts orders, walks state machine as needed
4. RLS partitions per tenant; Envers audits every row
5. Response: `SyncResponse` per-order status (success/conflict/failure) — edge retries failures

**Kitchen Display (Real-time, v2.1 multi-replica):**

1. Kitchen terminal opens `frontend/app/dashboard/kitchen/page.tsx`, STOMP client connects to `ws://<edge>/ws`
2. Edge proxies the WebSocket upgrade to Core; `JwtHandshakeInterceptor` caches token on session
3. Client sends `STOMP CONNECT` with `Authorization: Bearer ...` → `TenantChannelInterceptor` validates JWT, stores `tenantId` on session
4. Client subscribes to `/topic/kitchen/{tenantId}/{shopId}` → interceptor asserts URL tenant == session tenant
5. When any replica transitions an order to PREPARING/READY, `OrderEventPublisher` publishes to `/topic/kitchen/{tenantId}/{shopId}`
6. In `relay` mode, message flows Core → RabbitMQ → ALL replicas → every subscribed kitchen terminal receives it

## Key Abstractions

**`TenantContext`:**
- Purpose: Thread-local UUID holder for request scope
- Location: `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java`
- Pattern: `ThreadLocal<UUID>` with static `get()/set()/clear()`; populated by `JwtTenantFilter` (HTTP) or `TenantChannelInterceptor` (STOMP)

**Service–Repository Pattern:**
- Examples: `ShopService`/`ShopRepository`, `OrderService`/`OrderRepository`, `PublicStorefrontService` (public, no auth)
- Pattern: `@Service` + `@Transactional` wraps caching + validation + state machines; repository is a `JpaRepository` extension

**MapStruct Mappers:**
- Examples: `ShopMapper`, `OrderMapper`, `ProductMapper`, `PublicProductDto` mappers in `storefront/dto/`
- Pattern: `@Mapper(componentModel="spring")` interfaces; impls generated at compile time

**Spring State Machine:**
- Examples: `OrderStateMachineConfig`, `OrderStateMachineService`
- Pattern: Enum states/events, `StateMachineConfigurerAdapter`, guards for transition validity

**`TenantAwareCacheKeyGenerator`:**
- Location: `core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java`
- Pattern: `KeyGenerator` bean reading `TenantContext.get()` and prefixing cache keys

**`GlobalExceptionHandler` (`@RestControllerAdvice`):**
- Location: `core-java/src/main/java/uk/jtoye/core/exception/GlobalExceptionHandler.java`
- Converts `ResourceNotFoundException`, `InvalidStateTransitionException`, `ConstraintViolationException`, and generic `Throwable` into RFC 7807 Problem Detail JSON

**`StompBrokerRelay` (via `WebSocketConfig`):**
- Purpose: Replace in-memory broker with external RabbitMQ STOMP broker for horizontal scale
- Configuration: `stomp.broker.mode=relay` + relay host/port/credentials
- Rationale: Enables `--scale core-java=N` without losing WS broadcasts

**`PaymentEventOutbox`:**
- Location: `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java` + `PaymentEventOutboxFlusher.java`
- Pattern: Transactional outbox; persists Stripe events before ack, drains async — survives Core restarts without losing webhooks

**Circuit Breaker (Edge Go):**
- Location: `edge-go/internal/core/client.go` (uses `sony/gobreaker`)
- Pattern: HTTP client wraps each Core call; opens on consecutive failures; returns 503 + fallback response

## Entry Points

**Backend API Entry:**
- Location: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Triggers: `SpringApplication.run()`
- Responsibilities: Enable `@EnableAsync`, `@EnableScheduling` (payment outbox flusher, cleanup jobs), redirect `/` → Swagger UI, expose `/actuator/health`

**Frontend Entry:**
- Location: `frontend/app/page.tsx`
- Triggers: Browser navigates to `/`
- Responsibilities: Redirect authenticated → `/dashboard`, unauthenticated → `/auth/signin`

**Edge Gateway Entry:**
- Location: `edge-go/cmd/edge/main.go`
- Triggers: Docker startup or direct binary
- Responsibilities: Gin router, JWT middleware, token-bucket limiter, routes `/health`, `/sync/batch`, `/orders`, `/whatsapp`, `/webhooks/stripe`, `/ws` with circuit-breaker forwarding

**REST API Controllers:**
- `ShopController` (`/api/v1/shops`)
- `ProductController` (`/api/v1/products`)
- `OrderController` (`/api/v1/orders`) — includes SSE endpoint `OrderSseService`
- `CustomerController` (`/api/v1/customers`)
- `PaymentController` (`/api/v1/payments` + `/webhooks/stripe`)
- `FinancialTransactionController` (`/api/v1/financial-transactions`)
- `SyncController` (`/api/v1/sync/batch`)
- `DevTenantController` (`/dev/tenants` — dev profile only)
- `PublicStorefrontController` (`/public/**` — **v2.1 extended** with `getShopPromotions`, `getShopAnnouncements`, `getShopProducts`, `getShopConfig`, guest order placement/tracking)
- `SecurityHealthController` (`/api/security/health`)

**WebSocket Endpoint:**
- `/ws` (registered in `WebSocketConfig.registerStompEndpoints`), no SockJS fallback
- Handshake interceptor: `JwtHandshakeInterceptor`
- Channel interceptor: `TenantChannelInterceptor`

## Error Handling

**Strategy:** Centralised `@RestControllerAdvice` in `GlobalExceptionHandler`; consistent RFC 7807 Problem Detail responses; specific HTTP status per exception type.

**Patterns:**
- `ResourceNotFoundException` → 404
- `InvalidStateTransitionException` → 400 (state machine rejection)
- `IllegalStateException` (missing `TenantContext`) → 500 (indicates security misconfig)
- `ConstraintViolationException`, `MethodArgumentNotValidException` → 400 (Jakarta Validation)
- `MessageDeliveryException` (STOMP tenant/JWT violation) → STOMP `ERROR` frame, connection closed

**Response format (`ErrorResponse.java`):**
```json
{
  "timestamp": "2026-04-18T12:34:56Z",
  "status": 404,
  "error": "Not Found",
  "message": "Shop not found: 550e8400-e29b-41d4-a716-446655440000",
  "path": "/api/v1/shops/550e8400-e29b-41d4-a716-446655440000"
}
```

**Frontend error boundaries (new in v2.1 P1 audit):**
- `frontend/app/error.tsx`, `frontend/app/shop/error.tsx` — per-route boundaries surface recoverable errors to users instead of blank screens.

## Cross-Cutting Concerns

**Logging:** SLF4J + Logback (Core), `go.uber.org/zap` structured JSON (Edge), `console.*` (Frontend, with plans to add client telemetry). Default log level INFO (prod) / DEBUG (dev).

**Validation:** Jakarta Validation annotations on DTOs, `@Valid` on controller parameters, triggers `ConstraintViolationException` mapped to 400 via `GlobalExceptionHandler`.

**Authentication:**
- HTTP: Spring OAuth2 Resource Server validates JWT via `JwtDecoder` (JWKS fetched from Keycloak).
- WebSocket: Bearer token preferred from STOMP `Authorization` header (P1 hardening), handshake session attribute as fallback.

**Authorisation:** Keycloak realm roles → Spring Security `@PreAuthorize`. RLS policies provide defence-in-depth at the database layer.

**Audit Trail:** Hibernate Envers on `@Audited` entities; audit tables `{entity}_aud` joined against `REVINFO` with `V8__add_tenant_context_to_revinfo.sql` capturing tenant on every revision.

**Caching:** Redis, per-cache TTL, tenant-aware keys via `TenantAwareCacheKeyGenerator`; disabled in test profile.

**Rate Limiting:**
- Edge: token-bucket in Go (20 RPS global default)
- Core: Bucket4j + Redis for per-tenant quotas (`RateLimitConfig.java`, `RateLimitInterceptor.java`)

**Metrics & Observability:**
- Micrometer Prometheus registry + `/actuator/prometheus`
- Brave tracing → Zipkin endpoint (10% sampling default)
- Prometheus scrapes Core, Edge, RabbitMQ (via `rabbitmq_prometheus` plugin), Redis (redis-exporter added in v2.1 P1 audit)
- Alertmanager (v2.1 new) routes alerts to email receiver (Mailhog dev / SMTP prod) — config template at `infra/monitoring/alertmanager/alertmanager.yml.tmpl` rendered at container start by `entrypoint.sh`
- Grafana dashboards provisioned from `infra/monitoring/grafana/provisioning/` and `infra/monitoring/grafana/dashboards/stomp-dashboard.json`

**Secret Hygiene (v2.1 new):** `gitleaks` CI job (`.github/workflows/gitleaks.yml`) + local pre-commit hook (`scripts/pre-commit-gitleaks.sh`) backed by `.gitleaks.toml` allowlist; prevents credentials landing in git.

---

*Architecture analysis: 2026-04-18*
