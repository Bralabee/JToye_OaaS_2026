# Architecture

**Analysis Date:** 2026-04-07

## Pattern Overview

**Overall:** Tiered multi-tenant SaaS architecture with horizontal separation of concerns across Java backend, Go edge layer, and Next.js frontend, all secured by JWT/Keycloak and PostgreSQL Row-Level Security.

**Key Characteristics:**
- Multi-tenant isolation enforced at database (RLS policies), middleware (JWT extraction), and application layers
- Service-Repository pattern for business logic layering with clean separation
- Event-driven state machine for order workflows with Spring State Machine
- Tenant-aware caching with Redis, scoped by TenantContext
- Edge-to-Core data synchronization via high-volume batch API
- JWT-first authentication with Keycloak OAuth2/OIDC

## Layers

**Presentation (Frontend):**
- Purpose: Customer-facing UI and admin dashboards for multi-tenant operations
- Location: `frontend/app/`, `frontend/components/`
- Contains: Next.js 16 page routes, React components, form validation, API client integration
- Depends on: Backend Core API (`NEXT_PUBLIC_API_URL`), NextAuth.js for session management
- Used by: Browser clients (B2B admin dashboard, B2C customer storefronts)

**API Gateway (Edge - Go):**
- Purpose: Rate limiting, JWT validation, circuit breaker protection, request routing
- Location: `edge-go/cmd/edge/main.go`, `edge-go/internal/`
- Contains: Token bucket rate limiter, JWT middleware, Core API client with circuit breaker, WhatsApp webhook handler
- Depends on: Core Java API, Keycloak for JWKS, RabbitMQ for async messaging
- Used by: Storefront pages, mobile clients, external webhook integrations

**Business Logic (Spring Boot Core):**
- Purpose: Full REST API surface with CRUD operations, state management, tenant isolation
- Location: `core-java/src/main/java/uk/jtoye/core/`
- Contains: REST controllers, service layer, repository layer, domain entities, mappers, configurations
- Depends on: PostgreSQL database (RLS-enabled), Redis cache, RabbitMQ, Stripe API, S3/MinIO storage, Keycloak
- Used by: Frontend, Edge gateway, batch sync operations, webhook processors

**Data Access (JPA/Spring Data):**
- Purpose: ORM abstraction for tenant-scoped database queries
- Location: `core-java/src/main/java/uk/jtoye/core/*/` (repository interfaces in each domain folder)
- Contains: JpaRepository extensions with custom queries, named queries for full-text search
- Depends on: PostgreSQL JDBC driver, Flyway for schema migration
- Used by: Service layer exclusively

**Database (PostgreSQL):**
- Purpose: Multi-tenant data storage with RLS enforcement and audit trails
- Location: Schema defined in `core-java/src/main/resources/db/migration/` (28 Flyway migrations)
- Contains: Tables (shops, products, orders, customers, financial_transactions, reviews, etc.), RLS policies per table, audit tables via Envers
- Depends on: JDBC driver, Java code for policy setup
- Used by: Core Java service layer via JPA, trigger functions for audit events

## Data Flow

**Customer Places Order (Storefront → Core → Database):**

1. Customer fills cart in storefront UI (`frontend/app/shop/[slug]/checkout/page.tsx`)
2. POST /orders from Edge → Core with JWT token and order payload
3. Edge validates JWT, applies rate limit, forwards to Core (with circuit breaker)
4. Core receives request, JwtTenantFilter extracts tenant_id from token into TenantContext
5. SecurityFilterChain validates bearer token via Keycloak JWKS
6. OrderController → OrderService creates Order entity
7. OrderService triggers OrderStateMachineService to transition from DRAFT → PENDING
8. Repository persists Order with @Audited annotations, Envers audits the insert
9. PostgreSQL RLS policy `SET LOCAL app.current_tenant_id` automatically scopes insert to tenant
10. Order confirmation email queued to RabbitMQ, async EmailNotificationService processes
11. Response returned: 201 Created with OrderDto (mapped from Order entity via MapStruct)

**Admin Dashboard Fetches Shops (Dashboard → Core → Cache → Database):**

1. Admin logs into dashboard at `/dashboard`
2. NextAuth.js session maintains JWT from Keycloak
3. JavaScript calls GET /shops with Bearer token via apiClient (axios interceptor adds token)
4. Edge validates JWT, rate limits, forwards to Core
5. ShopController calls ShopService.getAllShops(pageable)
6. ShopService is marked @Cacheable("shops", keyGenerator="tenantAwareCacheKeyGenerator")
7. TenantAwareCacheKeyGenerator creates key: `shops#{tenantId}#{pageNumber}#{pageSize}`
8. Cache miss: ShopRepository.findAll(pageable) queries PostgreSQL
9. RLS automatically filters results by current_setting('app.current_tenant_id')
10. Shops mapped to ShopDto via MapStruct
11. Result cached in Redis for 15 minutes (CacheConfig TTL)
12. Subsequent requests within TTL return from Redis cache
13. Admin updates shop → CacheEvict removes all "shops" entries

**Batch Sync (Edge → Core Sync API):**

1. Edge device (POS, mobile app) collects orders offline
2. When online, POST /sync/batch sends N orders to Core as SyncRequest batch
3. Core SyncController validates batch integrity and JWT
4. SyncService iterates batch, creates/updates Order entities for each
5. For each order, OrderStateMachineService applies state transitions based on external state
6. RLS automatically partitions by tenant during batch persist
7. Audit trail captured by Envers for all batch changes
8. Response: SyncResponse with per-order status (success/failure/conflict)
9. Edge device retries failed items on next sync

**Order State Transition (Service Layer):**

1. Order in PENDING status, user clicks "Confirm"
2. OrderController.updateOrderStatus(id, CONFIRMED)
3. OrderStateMachineService.transition(order, CONFIRM_EVENT)
4. Spring State Machine checks OrderStateMachineConfig: PENDING → CONFIRMED allowed?
5. If yes: executes action (logs "Order confirmed"), updates order.status
6. If no (invalid): throws InvalidStateTransitionException
7. OrderRepository persists updated order
8. Envers records status change in orders_aud table with timestamp, user
9. Response: 200 OK with updated OrderDto

**State Management:**
- Order state: Stored in Order.status field, validated by state machine, sourced from database
- Cache state: TenantContext.CURRENT (ThreadLocal), populated by JwtTenantFilter per request
- Session state: NextAuth.js session in browser cookie, refreshable from Keycloak token endpoint
- Business metrics: Captured by BusinessMetricsService (scheduled task) and published to Micrometer metrics for Prometheus scrape

## Key Abstractions

**TenantContext (Multi-Tenant Isolation):**
- Purpose: Thread-local holder of current tenant_id for request scope
- Examples: `uk.jtoye.core.security.TenantContext`
- Pattern: ThreadLocal<UUID> with static get()/set()/clear() methods. JwtTenantFilter populates on each request, cleared after response.

**Service-Repository Pattern:**
- Purpose: Separate business logic (Service) from data access (Repository)
- Examples: `ShopService` / `ShopRepository`, `OrderService` / `OrderRepository`, `CustomerService` / `CustomerRepository`
- Pattern: Service is @Transactional, handles caching, validation, state transitions. Repository is JpaRepository extension with @Query methods.

**MapStruct Mappers:**
- Purpose: Compile-time safe DTO ↔ Entity conversion
- Examples: `ShopMapper`, `OrderMapper`, `ProductMapper` (located alongside entities in each domain)
- Pattern: Interfaces with @Mapper(componentModel="spring") and abstract mapping methods, processor generates implementations at compile time.

**Spring State Machine:**
- Purpose: Enforce valid order lifecycle transitions
- Examples: `OrderStateMachineConfig`, `OrderStateMachineService`
- Pattern: States (OrderStatus enum: DRAFT, PENDING, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED), Events (OrderEvent enum), Transitions defined in StateMachineConfigurerAdapter.

**Tenant-Aware Cache Key Generator:**
- Purpose: Prevent cross-tenant cache key collisions
- Examples: `TenantAwareCacheKeyGenerator` (no source file found, but used in CacheConfig)
- Pattern: Bean implementing KeyGenerator, reads TenantContext.get() and appends to cache key.

**Circuit Breaker (Edge Go):**
- Purpose: Protect edge from Core outages with fallback degradation
- Examples: `edge-go/internal/core/client.go` with Resilience4j fallback (Spring side) or Gin middleware (Go side)
- Pattern: HTTP client with timeout + retry logic, falls back to cached response or returns 503.

## Entry Points

**Backend API Entry:**
- Location: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Triggers: Spring Boot application start (`SpringApplication.run()`)
- Responsibilities: Enable async execution, enable scheduling (for cleanup jobs), redirect root to Swagger UI, serve /health endpoint

**Frontend Entry:**
- Location: `frontend/app/page.tsx`
- Triggers: Browser navigates to /
- Responsibilities: Redirect authenticated users to /dashboard, redirect unauthenticated to /auth/signin

**Edge Gateway Entry:**
- Location: `edge-go/cmd/edge/main.go`
- Triggers: Docker container startup or direct binary execution
- Responsibilities: Initialize Gin router, attach JWT middleware, rate limiter, route /health, /sync/batch, /orders, /whatsapp to Core API with circuit breaker

**REST API Endpoints (8 controllers):**
- `ShopController` (`/shops`): GET, POST, PUT, DELETE, search, image upload
- `ProductController` (`/products`): CRUD with filtering, full-text search, image gallery
- `OrderController` (`/orders`): CRUD, state transitions, SSE for real-time updates
- `CustomerController` (`/customers`): CRUD with email lookup
- `PaymentController` (`/payments`): Stripe integration, webhook handling
- `FinancialTransactionController` (`/financial-transactions`): VAT tracking, transaction ledger
- `SyncController` (`/sync/batch`): High-volume batch sync from edge
- `DevTenantController` (`/dev/tenants`): Development-only tenant CRUD (disabled in production)

## Error Handling

**Strategy:** Centralized exception handling via @ControllerAdvice, consistent ErrorResponse DTO format, HTTP status codes per error type.

**Patterns:**
- `ResourceNotFoundException` (404): Thrown when entity not found by ID or unique constraint
- `InvalidStateTransitionException` (400): Thrown when state machine rejects a transition
- `IllegalStateException` (500): Thrown when TenantContext is not set (indicates security configuration error)
- `ConstraintViolationException` (400): From @Valid on @RequestBody, automatic Spring conversion
- `ValidationException` (400): From Jakarta Validation annotations
- All exceptions caught by @ExceptionHandler methods, converted to ErrorResponse (timestamp, status, error, message, path), returned as JSON with appropriate HTTP status

Response format (from `ErrorResponse.java`):
```json
{
  "timestamp": "2026-04-07T12:34:56Z",
  "status": 404,
  "error": "Not Found",
  "message": "Shop not found: 550e8400-e29b-41d4-a716-446655440000",
  "path": "/shops/550e8400-e29b-41d4-a716-446655440000"
}
```

## Cross-Cutting Concerns

**Logging:** SLF4J with Logback configuration, structured logs via zap in Go edge layer. Log level INFO in production, DEBUG in development.

**Validation:** Jakarta Validation annotations (@NotNull, @Size, @Pattern) on DTOs and entities, triggered by @Valid on controller parameters. Error messages mapped to user-facing text.

**Authentication:** JWT Bearer token from Keycloak OAuth2/OIDC, validated by Spring Security via JwtDecoder with JWKS fetch from Keycloak, tenant_id claim extracted by JwtTenantFilter.

**Authorization:** Role-based access control (RBAC) via Keycloak realm roles, Spring Security checks. Row-Level Security (RLS) on all queries ensures database-level isolation.

**Audit Trail:** Hibernate Envers on all @Entity classes marked @Audited, captures inserts/updates/deletes with timestamp and user context. Audit tables: {entity}_aud.

**Caching:** Redis with per-cache TTL (products 10min, shops 15min), tenant-aware keys, CacheEvict on mutations. Disabled in test profile.

**Rate Limiting:** Go edge layer via token bucket (20 RPS global), Bucket4j on Core (per-tenant rate limits via Redis), tenant_id from JWT claim.

**Metrics & Observability:** Micrometer Prometheus registry, Zipkin/Brave for distributed tracing, /actuator/metrics endpoint.

---

*Architecture analysis: 2026-04-07*
