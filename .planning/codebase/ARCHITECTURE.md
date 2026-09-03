<!-- refreshed: 2026-09-03 -->
# Architecture

**Analysis Date:** 2026-09-03

## System Overview

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                         Browser / Storefront Customer                     │
└───────────────────────────────────┬───────────────────────────────────────┘
                                     │ HTTPS
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│  Frontend — Next.js 16 App Router                    `frontend/app/`      │
│  Vendor dashboard (`app/dashboard/`) + public storefront (`app/shop/`)    │
│  Calls Core DIRECTLY via NEXT_PUBLIC_API_URL — does NOT go through edge   │
└───────────┬────────────────────────────────────────────────┬──────────────┘
            │ REST (axios)                                   │ STOMP/WS
            ▼                                                 ▼
┌───────────────────────────┐                    ┌──────────────────────────┐
│  Core Java — Spring Boot  │◄───────────────────►│  RabbitMQ (STOMP relay)  │
│  `core-java/src/main/...` │   AMQP publish/sub   │  Kitchen Display topics  │
└───────────┬───────────────┘                    └──────────────────────────┘
            │ JDBC (RLS-enforced, tenant GUC pinned per-transaction)
            ▼
┌───────────────────────────────────────────────────────────────────────────┐
│  PostgreSQL 15 — RLS-enabled, FORCE RLS on every tenant table             │
│  `core-java/src/main/resources/db/migration/` (V1..V66)                   │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│  Edge Gateway — Go/Gin               `edge-go/cmd/edge/main.go`           │
│  Proxies exactly ONE business route: POST /api/v1/sync/batch (JWT-gated)  │
│  Plus: /health, /ready, /metrics, /openapi.json+/docs, WhatsApp webhook   │
│  Called by high-volume edge sync clients — NOT by the frontend or MCP     │
└───────────────────────────────────┬───────────────────────────────────────┘
                                     │ HTTP + sony/gobreaker circuit breaker
                                     ▼
                              Core Java (same as above)

┌───────────────────────────────────────────────────────────────────────────┐
│  MCP Server — Node/TypeScript          `mcp-server/src/`                  │
│  AI agent tool surface (list-shops, list-products, read-orders,           │
│  create-order, create-customer). Calls Core DIRECTLY at CORE_BASE_URL     │
│  (default http://core-java:9090) — does NOT traverse the Go edge          │
└───────────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| Frontend (Next.js) | Vendor dashboard + customer storefront UI, session mgmt via NextAuth | `frontend/app/`, `frontend/middleware.ts` |
| Core Java | Full REST API, business logic, state machines, RLS-scoped persistence | `core-java/src/main/java/uk/jtoye/core/` |
| Edge Gateway (Go) | Rate limiting, JWT validation, circuit-breaker proxy for ONE route + WhatsApp intake | `edge-go/cmd/edge/main.go`, `edge-go/internal/` |
| MCP Server | AI-agent-facing mutating/reading tool surface over Core's REST API | `mcp-server/src/index.ts`, `mcp-server/src/tools/` |
| PostgreSQL | Tenant-isolated storage, RLS policies, Envers audit mirrors (`*_aud` tables) | `core-java/src/main/resources/db/migration/` |
| RabbitMQ | Transactional-outbox event fan-out (payment, media) + STOMP relay for KDS | `infra/rabbitmq/`, `core-java/.../config/RabbitMQConfig.java` |
| Keycloak | OIDC/OAuth2 identity provider — vendor realm + customer realm | `infra/keycloak/` |
| Redis | Tenant-scoped cache (Spring Cache), session support | `core-java/.../config/CacheConfig.java` |
| MinIO / S3 | Object storage for normalized media derivatives | `core-java/.../media/MediaAssetService.java` |

## Pattern Overview

**Overall:** Multi-tenant, RLS-first, service-repository layered monolith (Core Java) fronted by a thin Go edge for one high-volume route, with a Next.js frontend and an MCP tool server both talking to Core directly.

**Key Characteristics:**
- Tenant isolation enforced at THREE layers simultaneously: Postgres RLS (FORCE RLS + a safe `current_tenant_id()` SQL helper), a per-request `TenantContext` ThreadLocal populated from the JWT, and a per-transaction GUC pin (`SET LOCAL app.current_tenant_id`) applied by an AOP aspect before every `@Transactional` method and every repository/JdbcTemplate call.
- A second, application-layer access boundary (`shop_staff`) sits INSIDE the tenant wall — it scopes which shops a tenant user may act on, but never widens or replaces RLS; a caller must already be inside the correct tenant before `ShopAccessService` is consulted at all.
- Transactional-outbox pattern used twice (payment, media) with near-identical mechanics: `FOR UPDATE SKIP LOCKED` batch claim, exponential backoff with a cap, and a periodic "resurrect" sweep for rows stuck past a threshold — deliberately NOT shared as one generic outbox because payment and media dispatch onto different exchanges with different poison-message handling.
- Order lifecycle is a genuine Spring State Machine (not just an enum with `if` checks): states are stored on `Order.status` (stateless machine, rehydrated per transition) and validated transitions are the only way `OrderStatus` changes.
- Real-time kitchen display updates travel over STOMP-over-RabbitMQ, not Spring's in-memory broker in relay mode; destination shape is centralized in one class (`StompDestinations`) because the broker (AMQP routing key) and the in-memory dev broker accept different address grammars.
- Copy-on-write media model: `media_asset` is immutable-by-convention (ref-counted, deduplicated by `(tenant_id, sha256)`), reached only through an async pipeline (quarantine → validate → normalize → outbox-publish → worker promotes to ACTIVE), never a synchronous upload-to-serve path.
- Edge (Go) and MCP (Node) are two independent, un-proxied callers of Core — there is no API-gateway-for-everything; the edge exists specifically for the high-volume batch-sync route plus channels Core cannot front-door itself (webhook HMAC verification, a process-wide DoS-guard rate limiter).

## Layers

**Frontend (Next.js 16 App Router):**
- Purpose: Vendor-facing admin dashboard and customer-facing storefront, server components + client components
- Location: `frontend/app/` (routes), `frontend/components/` (UI), `frontend/lib/` (API clients, business logic helpers), `frontend/hooks/` (React hooks)
- Contains: Page routes (`app/dashboard/*`, `app/shop/[slug]/*`), NextAuth route handlers (`app/api/auth/`), server-side data loaders (`lib/storefront-server.ts`)
- Depends on: Core Java REST API (`NEXT_PUBLIC_API_URL`), NextAuth.js against Keycloak, STOMP-over-WebSocket for kitchen display (`hooks/use-stomp.ts`)
- Used by: Browsers (vendor admins via `/dashboard`, customers via `/shop/[slug]`, public marketing pages at `/`)

**Edge Gateway (Go/Gin):**
- Purpose: Rate limiting, JWT validation, circuit-breaker protection for ONE proxied business route, plus channels Core cannot front-door directly
- Location: `edge-go/cmd/edge/` (entry point + handlers), `edge-go/internal/` (auth, core client, middleware, whatsapp parser)
- Contains: Gin router setup (`main.go`), Prometheus metrics (`metrics.go`), OpenAPI docs registration (`docs.go`), swaggo-annotated handlers (`handlers.go`)
- Depends on: Core Java (`CORE_API_URL`, default `http://localhost:9090`), Keycloak JWKS for JWT validation, `sony/gobreaker` circuit breaker with NO fallback (breaker-open or transport error → 502)
- Used by: High-volume edge-sync clients calling `POST /api/v1/sync/batch`; Meta's WhatsApp webhook infrastructure calling `POST /api/v1/webhooks/whatsapp` (HMAC-signed, no JWT). **The frontend and mcp-server bypass the edge entirely and call Core directly.**

**Core Java (Spring Boot 3.5.16):**
- Purpose: Full REST API surface — CRUD, state machines, business rules, tenant isolation enforcement
- Location: `core-java/src/main/java/uk/jtoye/core/` — one package per domain (`shop`, `product`, `order`, `customer`, `payment`, `media`, `onboarding`, `security/access`, `gdpr`, `webhook`, `notification`, `finance`, `geo`, `storefront`, `sync`, `tenant`, `ai`, `audit`, `review`, `storage`, `common`, `exception`, `config`, `security`, `websocket`)
- Contains: `@RestController` classes, `@Service` business logic, `@Repository`/`JpaRepository` data access, JPA entities, MapStruct mappers, `@Aspect` cross-cutting concerns (tenant GUC pinning)
- Depends on: PostgreSQL (RLS-enabled), Redis (cache), RabbitMQ (outbox + STOMP relay), Stripe API, S3/MinIO, Keycloak (JWKS + admin API for deprovisioning), Ollama (image analysis, `ai` package)
- Used by: Frontend, edge gateway (one route), MCP server, batch sync clients

**Repository Layer:**
- Purpose: ORM abstraction for tenant-scoped Postgres queries
- Location: One `<Entity>Repository.java` per domain package, colocated with its entity (e.g. `core-java/.../order/OrderRepository.java`)
- Contains: `JpaRepository<Entity, UUID>` extensions, `@Query` methods for full-text search and custom filters
- Depends on: PostgreSQL JDBC driver; every query executes under the RLS session GUC set by `TenantSetLocalAspect`
- Used by: Service layer exclusively — controllers never touch repositories directly

**Database (PostgreSQL 15, RLS-enabled):**
- Purpose: Multi-tenant data storage with row-level tenant isolation and Envers audit trails
- Location: Schema in `core-java/src/main/resources/db/migration/` — 66 Flyway migrations (V1 through V66; `spring.flyway.out-of-order=true` is required because several slots were filled non-sequentially, e.g. V44 after V45/V46)
- Contains: Tenant tables (`shops`, `products`, `orders`, `customers`, `financial_transactions`, `media_asset`, `shop_staff`, `webhook_subscription`, etc.) with `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`, matching `*_aud` Envers mirror tables for audited entities, and a small set of deliberately RLS-EXEMPT platform tables (`tenants`, `postcode_centroid`, `dsar_request`) each justified in `RlsContractTest.EXEMPT_TABLES`
- Depends on: JDBC driver, the `current_tenant_id()` SQL helper function (introduced `V1__base_schema.sql`, hardened in `V51__rls_uuid_cast_safety.sql` to fail filtered rather than error on a non-UUID GUC)
- Used by: Core Java service layer via JPA; trigger-free — audit rows are written by Hibernate Envers, not DB triggers

## Data Flow

### Primary Request Path (authenticated vendor API call)

1. Request arrives at Core Java with a Keycloak-issued JWT in the `Authorization` header (`core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java`)
2. Spring Security validates the JWT signature/expiry against Keycloak JWKS (OAuth2 Resource Server)
3. `JwtTenantFilter` (`core-java/.../security/JwtTenantFilter.java`, `@Order(200)`, runs after core Security filters) extracts `tenant_id`/`tenantId`/`tid` from JWT claims and sets `TenantContext` (ThreadLocal). In non-prod profiles only, `TenantFilter` (`core-java/.../security/TenantFilter.java`, `@Profile({"dev","local","test"})`) falls back to an `X-Tenant-Id` header if the JWT carried none — inert in `prod`.
4. Request dispatches to a `@RestController` (e.g. `ShopController`), which delegates to a `@Transactional` `@Service`
5. Before the transactional method body runs, `TenantSetLocalAspect` (`core-java/.../security/TenantSetLocalAspect.java`, `@Before` advice on `@Transactional` execution AND on `Repository`/`JdbcTemplate` calls) pins `SET LOCAL app.current_tenant_id = '<uuid>'` on the JDBC connection for that transaction — or resets it if `TenantContext` is empty
6. If the endpoint is shop-scoped (not just tenant-scoped), `ShopAccessService.require(shopId, role)` (`core-java/.../security/access/ShopAccessService.java`) checks the caller's `shop_staff` membership — an application-layer gate INSIDE the already-established tenant boundary, never a substitute for it
7. Repository call executes; Postgres RLS policies filter rows using `current_tenant_id()`, which reads the pinned GUC — a query issued with no GUC set returns zero rows, never another tenant's rows
8. Response serialized via MapStruct-generated DTO mapper; errors are converted to RFC 7807 `ProblemDetail` by `GlobalExceptionHandler` (`core-java/.../common/GlobalExceptionHandler.java`, `@RestControllerAdvice`)

### Order Lifecycle Flow

1. `OrderController.createOrder` → `OrderService.createOrder` persists `Order` in `DRAFT`, snapshotting `product_name` and the write-time allergen bitmasks onto each `OrderItem` (never a live join back to `products`)
2. State transitions (`DRAFT→PENDING→CONFIRMED→PREPARING→READY→COMPLETED`, plus `CANCELLED`/`REFUNDED` branches) are validated by `OrderStateMachineService` against `OrderStateMachineConfig` (`core-java/.../order/OrderStateMachineConfig.java`) — states live on `Order.status`, the state machine itself is stateless and rehydrated per call
3. A successful transition publishes an `OrderStateChangeEvent` (`core-java/.../order/OrderStateChangeEvent.java`), consumed by `OrderStateChangeListener` (`@RabbitListener`), which broadcasts to the kitchen display over STOMP (destination built by `StompDestinations`, tenant-scoped and enforced by `TenantChannelInterceptor`) and triggers customer notification email — deliberately in that swallow-safe order so a destination-shape defect cannot also kill order-confirmation email
4. `OrderSseFanoutListener`/`OrderSseService` additionally push order updates to the frontend dashboard over Server-Sent Events

### Payment / Media Outbox Flow (transactional outbox pattern)

1. A business transaction (payment capture, media asset promotion) inserts BOTH the domain row and an outbox row (`payment_event_outbox` or `media_event_outbox`) in the same DB transaction — the event can never be "lost" relative to the state change that produced it
2. `PaymentEventOutboxFlusher` / `MediaEventOutboxFlusher` (`@Scheduled(fixedDelayString=...)`, default 5s) claim a batch with `SELECT ... FOR UPDATE SKIP LOCKED`, publish to RabbitMQ, and mark rows dispatched
3. On publish failure, `attempts` increments and `computeBackoffMillis` applies exponential backoff (base × 2^(attempts-1), capped) before the row is eligible again
4. A separate `@Scheduled` "resurrect" sweep (default 300s) catches rows stuck past a stall threshold, distinguishing "never dispatched" from "dispatched but stalled" (media additionally tracks `process_attempts` + `quarantine_expires_at` on `media_asset` itself so a broker outage cannot cause `MediaPendingReaper` to delete quarantined bytes still awaiting dispatch)

### Media Upload Flow (copy-on-write, async pipeline)

1. Vendor uploads via `MediaUploadController` → reject-early on `Content-Length` (413), then a `MediaAsset` row is created `PENDING` and bytes go to quarantine storage
2. An outbox row is enqueued; `MediaProcessingWorker` (`@RabbitListener`) pins the tenant GUC, magic-byte-sniffs the format, guards against decompression bombs on header read, decode-verifies, strips EXIF, transcodes to WebP + a 400px thumbnail
3. On success the asset flips to `ACTIVE` (optimistic-locked via `media_asset.version` so a stale `MediaPendingReaper` sweep can never race the worker and flip an already-ACTIVE asset back to `FAILED`); on failure it flips `FAILED` with a reason
4. `product_media` is a join table (`product_id`, `asset_id`, `is_primary`, `sort_order`) — a product references assets rather than owning bytes, and physical MinIO deletion only happens at ref-count `COUNT(*)=0`

**State Management:**
- Order state: `Order.status` column, DB-sourced, machine-validated
- Tenant state: `TenantContext` ThreadLocal (request-scoped) + Postgres session GUC (transaction-scoped) — two independent mechanisms that must agree; `TenantContextCleanupFilter` clears the ThreadLocal at request end to prevent thread-pool leakage across requests
- Frontend session state: NextAuth.js JWT session in an HTTP-only cookie, refreshed against Keycloak's token endpoint (`frontend/lib/customer-token-refresh.ts`, `frontend/lib/session-callback.ts`)
- Business metrics: `BusinessMetricsService` (`core-java/.../config/BusinessMetricsService.java`) — scheduled task publishing to Micrometer/Prometheus

## Key Abstractions

**TenantContext:**
- Purpose: Thread-local holder of the current tenant's UUID for request scope
- Examples: `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java`
- Pattern: `ThreadLocal<UUID>` with static `set()`/`get()`/`clear()`. Populated by `JwtTenantFilter` (JWT-first) or `TenantFilter` (header fallback, non-prod only), consulted by `TenantSetLocalAspect` to pin the DB session GUC, cleared by `TenantContextCleanupFilter`.

**ShopAccessService (vendor-scoped access, Phase 23):**
- Purpose: The single in-tenant authorization seam deciding which shops a tenant user may act on — layered strictly INSIDE the RLS tenant wall, never a replacement for it
- Examples: `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java`, backed by `ShopStaff` entity + `shop_staff` table (V52/V57 migrations)
- Pattern: `require(shopId, role)` / `grantedShopIds()` consulted per shop-scoped call; per-user membership cached (`@Cacheable("shopMembership")`, tenant-isolated key, evictable on grant/revoke); a realm-`admin` JWT authority is an implicit `GROUP_ADMIN` (D-03); first authenticated request from an ungranted user JIT-provisions a tenant-wide `GROUP_ADMIN` grant for their own `sub` (D-04, race-safe `INSERT ... ON CONFLICT DO NOTHING`); a config-gated "strict scoping" switch (default OFF) de-honours JIT-sourced tenant-wide admin once flipped ON.

**Service/Repository separation:**
- Purpose: Separate business logic (Service) from data access (Repository)
- Examples: `ShopService`/`ShopRepository`, `OrderService`/`OrderRepository`, `MediaAssetService`/`MediaAssetRepository`
- Pattern: Service is `@Transactional`, owns caching/validation/state transitions; Repository is a `JpaRepository` extension with `@Query` methods only — no business logic

**Mapper (Entity ↔ DTO):**
- Purpose: Compile-time-safe conversion between JPA entities and API DTOs
- Examples: `ShopMapper`, `OrderMapper`, `ProductMapper` — colocated with their entity in each domain package
- Pattern: `@Mapper(componentModel = "spring")` interfaces; MapStruct's annotation processor generates the implementation at compile time

**Order State Machine:**
- Purpose: Enforce valid order lifecycle transitions only
- Examples: `OrderStateMachineConfig`, `OrderStateMachineService` (`core-java/src/main/java/uk/jtoye/core/order/`)
- Pattern: Spring State Machine, `OrderStatus` enum states, `OrderEvent` enum triggers, transitions declared in a `StateMachineConfigurerAdapter`; state persists on `Order.status`, not in the machine instance (stateless, rehydrated per call)

**Transactional Outbox:**
- Purpose: Guarantee an event is never lost or duplicated relative to the DB transaction that produced it
- Examples: `payment_event_outbox`/`PaymentEventOutboxFlusher`, `media_event_outbox`/`MediaEventOutboxFlusher`
- Pattern: Outbox row inserted in the same transaction as the domain change; a `@Scheduled` flusher claims with `FOR UPDATE SKIP LOCKED`, publishes to RabbitMQ, applies exponential backoff on failure, and a separate resurrect sweep recovers stalled rows

**StompDestinations (single source of truth for broker addressing):**
- Purpose: Prevent destination-shape drift between the publisher and the tenant-enforcing interceptor
- Examples: `core-java/src/main/java/uk/jtoye/core/websocket/StompDestinations.java`, consumed by `TenantChannelInterceptor`
- Pattern: `/topic/{feature}.{tenantId}[.{qualifier}]` — one dot-segment (RabbitMQ STOMP plugin maps `/topic/<name>` to an AMQP routing key, which cannot contain `/`); shape asserted at construction time, not on the publish path (a throw there would dead-letter the whole order-state listener, killing kitchen updates AND confirmation email together)

**Tenant-Aware Cache Key Generator:**
- Purpose: Prevent cross-tenant Redis cache key collisions
- Examples: `core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java`, wired in `CacheConfig`
- Pattern: `KeyGenerator` bean reading `TenantContext.get()` and prefixing the cache key (`tenant:{tid}:...`)

**Circuit Breaker (edge → Core):**
- Purpose: Protect the edge from Core outages, fail fast rather than hang
- Examples: `edge-go/internal/core/client.go` (`sony/gobreaker`)
- Pattern: HTTP client wrapped by `c.breaker.Execute()`; on the Go edge there is deliberately NO fallback — breaker-open or a transport error returns 502 directly to the caller (the frontend/mcp-server bypass the edge entirely so this only affects sync-batch clients)

## Entry Points

**Core Java application:**
- Location: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Triggers: Spring Boot application start (`SpringApplication.run()`)
- Responsibilities: `@EnableAsync` + `@EnableScheduling` (backs the outbox flushers, cleanup jobs, business metrics); redirects `/` to `/swagger-ui.html`; serves `/health` returning plain `"OK"`

**Frontend root route:**
- Location: `frontend/app/page.tsx`
- Triggers: Browser navigates to `/`
- Responsibilities: Public marketing landing page (storefront discovery + "run your own" pitch) — NOT an auth redirect; `frontend/middleware.ts` handles session-based routing for `/dashboard` and `/shop` paths

**Edge gateway:**
- Location: `edge-go/cmd/edge/main.go`
- Triggers: Docker container start / direct binary execution
- Responsibilities: Initialize Gin router; register `/health`, `/ready` (probe-exempt from the rate limiter, `rateLimiterExemptPaths`); register `/metrics` on either the app port or a separate management port (`EDGE_MANAGEMENT_PORT`); register `/openapi.json` + `/docs`; register the PUBLIC `POST /api/v1/webhooks/whatsapp` (HMAC-verified, no JWT); register the ONE JWT-protected business route `POST /api/v1/sync/batch`. Confirmed by direct read of `main.go` (2026-09-03): no other business route is proxied.

**MCP server:**
- Location: `mcp-server/src/index.ts`, tool implementations in `mcp-server/src/tools/`
- Triggers: MCP client (AI agent) connects over stdio/SSE
- Responsibilities: Exposes `list-shops`, `list-products`, `read-orders` (read tools) and `create-order`, `create-customer` (mutating tools, Phase 25) — all call `core-client.ts`, which targets `CORE_BASE_URL` (default `http://core-java:9090`) directly, never the edge

**REST controllers (`core-java/src/main/java/uk/jtoye/core/`), confirmed present on disk 2026-09-03:**
- `ShopController` (`shop/`) — shop CRUD, search, image upload
- `AnnouncementController`, `PromotionController` (`shop/`) — vendor marketing surfaces
- `ProductController` (`product/`) — CRUD, filtering, full-text search, gallery
- `OrderController` (`order/`) — CRUD, state transitions, SSE stream
- `CustomerController` (`customer/`) — CRUD, email lookup
- `PaymentController`, `RefundController` (`payment/`) — Stripe integration, webhook handling, refunds
- `FinancialTransactionController` (`finance/`) — VAT tracking, transaction ledger
- `SyncController` (`sync/`) — high-volume batch sync from edge
- `MediaController`, `MediaUploadController` (`media/`) — async upload pipeline, review queue
- `OnboardingController`, `OnboardingAdminController` (`onboarding/`) — vendor onboarding state machine + admin queue
- `StaffController` (`security/access/`) — `shop_staff` grant management, GROUP_ADMIN-only, hard-mapped at `/api/v1/staff` (deliberately outside the standard `API_V1_PACKAGES` auto-mapping)
- `GdprController`, `DsarIntakeController`, `DsarVerificationController` (`gdpr/`) — erasure records + platform-level DSAR intake queue
- `WebhookSubscriptionController`, `WebhookDeliveryController` (`webhook/`) — outbound webhook management
- `PublicUnsubscribeController` (`notification/consent/`) — public unsubscribe link handler
- `PublicStorefrontController` (`storefront/`) — public/unauthenticated storefront read API
- `TenantAdminController`, `DevTenantController` (`tenant/`) — tenant lifecycle admin (prod) / dev-only tenant CRUD (disabled in production)
- `SecurityHealthController` (`controller/`) — cross-cutting security health probe

## Architectural Constraints

- **Threading:** Core Java is a standard Spring MVC servlet thread-per-request model; `TenantContext` is a `ThreadLocal` and therefore MUST be cleared at request end (`TenantContextCleanupFilter`) to avoid leaking a tenant id across pooled threads. `@EnableAsync` methods do NOT automatically inherit `TenantContext` — any `@Async` code that needs tenant scoping must re-establish it explicitly.
- **Global state:** `TenantContext` (`security/TenantContext.java`) is the one deliberate ThreadLocal singleton; `StompDestinations` and `RlsContractTest.EXEMPT_TABLES` are compile-time constant registries, not mutable global state.
- **RLS/GUC coupling:** every code path that touches the database inside a transaction depends on `TenantSetLocalAspect` having already run. A `@Transactional` method invoked without `TenantContext` set does not error — RLS silently returns zero rows. This is a recurring trap for migration backfills (must loop tenants + `set_config` explicitly) and for any new async/scheduled code path that queries tenant tables without first setting `TenantContext`.
- **Two independent, un-proxied Core callers:** the Go edge and the MCP server both call Core directly and neither traverses the other. A change to Core's auth model must be validated against three call paths (frontend, edge, MCP), not one.
- **Circular imports:** none observed at the Java package level (domain packages depend inward toward `security`/`config`/`common`, not on each other in a cycle).

## Anti-Patterns

### Bypassing TenantSetLocalAspect

**What happens:** Code that queries a tenant-scoped repository from outside a `@Transactional` context (e.g. a raw `EntityManager` call in a non-transactional helper, or a background thread that never called `TenantContext.set()`).
**Why it's wrong:** RLS silently filters to zero rows rather than throwing — a bug here looks like "no data found," not an error, and is easy to miss in testing on a single-tenant fixture.
**Do this instead:** Always operate inside a `@Transactional` service method after `TenantContext.set(tenantId)` has been called for that thread; see the recurring backfill pattern in Flyway migrations that loop tenants and call `set_config` per tenant explicitly (documented directly in migration file headers, e.g. V54-V56).

### Publishing broker messages with an unvalidated destination on the hot path

**What happens:** An earlier version of the kitchen-display publisher built STOMP destination strings by concatenation on the publish path itself.
**Why it's wrong:** A malformed destination is deterministic per (tenant, shop) — it fails on EVERY message for that pair, and if the validation throw sits inside the `@RabbitListener` catch block, it dead-letters the whole order-state-change handler, silently killing both kitchen updates and customer confirmation email for that tenant.
**Do this instead:** Build and validate the destination shape at construction time in a pure function (`StompDestinations.assertPublishable`), decoupled from the listener's execution path — see the file-level Javadoc in `core-java/src/main/java/uk/jtoye/core/websocket/StompDestinations.java` for the full incident rationale.

### Treating shop_staff as a second tenant boundary

**What happens:** Adding a new shop-scoped endpoint that checks `ShopAccessService` but skips confirming the request already passed through the standard tenant-resolution filter chain.
**Why it's wrong:** `ShopAccessService` deliberately assumes it runs AFTER `TenantSetLocalAspect` has pinned the GUC (its JIT-provision logic explicitly documents this — see the class Javadoc "Pitfall 4"); calling it before a tenant is established would let it silently no-op or misattribute a grant.
**Do this instead:** Route every shop-scoped call through the existing controller → `@Transactional` service → `ShopAccessService.require()` chain; never call `ShopAccessService` directly from a filter or non-transactional context.

## Error Handling

**Strategy:** Centralized RFC 7807 (`application/problem+json`) responses via a single `@RestControllerAdvice`.

**Patterns:**
- `ResourceNotFoundException` (404), `InvalidStateTransitionException` (400), `ShopAccessDeniedException` (403), `MissingTenantContextException` (500 — indicates a security-configuration bug, not a client error), `IdempotencyConflictException`/`IdempotencyPayloadMismatchException` (409/422), `InsufficientStockException`, `TenantAccessDeniedException` — all caught by `GlobalExceptionHandler` (`core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java`, `@RestControllerAdvice`) and converted to typed `ProblemDetail` responses
- `@Valid` on `@RequestBody` triggers automatic `ConstraintViolationException`/`ValidationException` → 400 conversion
- Go edge: `fmt.Errorf("context: %w", err)` error-chain wrapping; explicit `httpResp.StatusCode >= 400` checks; circuit-breaker errors surface through `c.breaker.Execute()`

## Cross-Cutting Concerns

**Logging:** SLF4J (Java, service-layer entry points + state changes at INFO, exceptions at ERROR); `go.uber.org/zap` structured logging (Go edge); browser console (frontend, no centralized client logger).

**Validation:** Jakarta Bean Validation (`@Valid`) on request DTOs (Java); Zod schemas (frontend forms); explicit type/error checks (Go).

**Authentication:** Keycloak-issued JWT validated by Spring OAuth2 Resource Server (Core) and by a hand-rolled JWKS-fetching middleware (Go edge, `edge-go/internal/middleware/jwt.go`); NextAuth.js session cookie on the frontend, itself backed by the same Keycloak realm(s) — a separate customer realm exists per `docs/architecture/decisions/ADR-0005-customer-realm-identity-providers.md`.

**Multi-tenancy:** Three-layer enforcement described in Data Flow step 1-7 above — this is the platform's single most load-bearing cross-cutting concern and the one most migrations/tests are written to protect (`RlsContractTest` sweeps every table for a policy and rejects raw `::uuid` GUC casts).

---

*Architecture analysis: 2026-09-03*
