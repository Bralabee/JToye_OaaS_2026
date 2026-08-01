<!-- GSD:project-start source:PROJECT.md -->
## Project

**J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved**

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. This milestone turns to vendor operational control: unblocking stuck onboarding, scoping access per shop within a tenant, hardening image handling (copy-on-write media_asset model + safe async upload pipeline), and fixing dashboard mobile — plus extending the AI/automation surface (outbound webhooks + mutating MCP tools) on a committed local-k8s overlay.

**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility.

### Constraints

- **Tech stack**: Must use existing stack — Spring Boot 3.5.16, Next.js 16, Go 1.26, PostgreSQL 15
- **Java version**: JDK 21 (JDK 25 incompatible with Gradle 8.10)
- **Multi-tenancy**: All new features must respect RLS and TenantContext
- **Testing**: All new code requires tests — project standard is 1903 logical invocations passing (1265 Java `@Test` methods across 219 files + 470 Jest `it/test` blocks across 66 files + 77 top-level Go `Test*` funcs across 9 files + 43 Playwright `test()` blocks across 13 specs + 48 MCP-server vitest `it/test` blocks across 8 files under `mcp-server/`). Multiple Java files use Testcontainers (real Postgres + RLS). Counts are the single source of truth in `docs/metrics.json`, enforced by **two** gates in `.github/workflows/docs-freshness.yml`, one per half of the loop: `scripts/docs-freshness.sh` (source tree → `docs/metrics.json`) and `scripts/check-doc-metrics.sh` (the numbers quoted in prose here, in `CLAUDE.md` and in `README.md` → `docs/metrics.json`). Both fail the build on drift. The second gate exists because the first never opened a doc: README sat at `921` for months while the tree was at `1895`, and `docs-freshness.sh` was green on every one of those commits.
- **Docker**: Always rebuild ALL containers after code changes before E2E testing
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 21 - Core API (Spring Boot 3.5.16)
- TypeScript 5 - Frontend (Next.js 16.2.12, React 19)
- Go 1.26 - Edge API gateway (Gin)
- SQL (PostgreSQL) - Database migrations via Flyway
- YAML - Configuration management
## Runtime
- JVM (Java 21) - Core API execution
- Node.js 24+ - Frontend build and runtime
- Go 1.26 runtime - Edge gateway
- PostgreSQL 15 - Database
- Gradle 8.10+ (Kotlin DSL) - Java/Spring Boot build
- npm - Node.js dependencies
- go mod - Go dependencies
- Gradle: Present (`gradle-wrapper` properties)
- npm: package-lock.json (implicit)
- Go: go.sum
## Frameworks
- Spring Boot 3.5.16 - Web framework, dependency injection, auto-configuration
- Spring Data JPA - ORM and database abstraction
- Spring Security - Authentication and authorization
- Spring OAuth2 Resource Server - JWT/OIDC token validation
- Spring AOP - Aspect-oriented programming
- Spring Cache - Distributed caching with Redis
- Spring AMQP - RabbitMQ message queue integration
- Spring Actuator - Metrics and health endpoints
- SpringDoc OpenAPI 2.8.6 - Swagger/OpenAPI documentation
- Micrometer Prometheus - Metrics export
- Micrometer Tracing (Brave/Zipkin) - Distributed tracing
- Next.js 16.2.12 - React framework with file-based routing
- React 19 - UI component library
- React Hook Form 7.83.0 - Form state management
- Next-Auth 5.0.0-beta.32 - Authentication middleware
- TailwindCSS 3.4.1 - Utility-first CSS framework
- Radix UI - Headless component library
- Zod 4.4.3 - Schema validation
- Gin v1.12.0 - HTTP routing and middleware
- golang-jwt/jwt v5 - JWT validation
- uber/zap - Structured logging
- sony/gobreaker - Circuit breaker pattern
- JUnit 5 - Java test framework
- Testcontainers 1.21.4 - Docker-based integration testing
- Spring Boot Test - Testing utilities and test containers
- Jest 29.7.0 - JavaScript test runner
- @testing-library/react - React component testing
- @playwright/test 1.62.0 - E2E browser automation
- Spring Boot Gradle Plugin 3.5.16 - JAR packaging
- Flyway - Database migration management
- Lombok - Boilerplate reduction (code generation)
- MapStruct 1.6.3 - Type-safe DTO mapping
## Key Dependencies
- PostgreSQL JDBC Driver 42.7.13 - Database connectivity
- Hibernate ORM (via Spring Boot 3.5.16) - JPA implementation
- Hibernate Envers - Audit history tracking
- AWS SDK v2 (2.49.6) - S3 API for image storage
- Stripe React/JS 6.8.0, 9.12.0 - Payment processing UI integration
- Axios 1.19.0 - HTTP client for API calls
- Framer Motion 12.43.0 - Animation library
- Recharts 3.10.1 - Charts and data visualization
- Redis 7 - Session and cache store
- RabbitMQ 4.3.4 - Message queue (AMQP)
- Keycloak 24.0.5 - Identity provider (OIDC/OAuth2)
- MinIO (latest) - S3-compatible object storage for images
- Ollama (latest) - Local LLM for image analysis
- Mailhog v1.0.1 - Local SMTP for email testing
- Resilience4j 2.4.0 - Circuit breakers and retry logic
- Bucket4j 8.10.1 - Token bucket rate limiting
- Stripe Java SDK 33.2.0 - Payment intent creation and webhook handling
- OpenPDF 2.0.3 - PDF generation for allergen labels
- Spring Data Redis (Lettuce) - Redis connection pooling
## Configuration
- `.env` file (required for docker-compose)
- Environment variable precedence: Spring profiles (dev, test, staging, prod)
- Config location: `core-java/src/main/resources/application*.yml`
- `application.yml` - Base configuration (all profiles)
- `application-dev.yml` - Development profile (localhost defaults)
- `application-test.yml` - Test profile (H2 in-memory, testcontainers)
- `application-staging.yml` - Staging production-like settings
- `application-prod.yml` - Production hardened settings (no SQL logging, higher pool sizes)
- `dev` (default in docker-compose)
- `test` (for unit/integration tests)
- `staging` (pre-production validation)
- `prod` (hardened security and performance)
- Flyway migrations: `core-java/src/main/resources/db/migration/`
- Migration strategy: Versioned SQL files (V1__, V2__, etc.)
- Current schema version: V60 (V60 Phase 27-01 media durability: `media_asset.process_attempts` + `quarantine_expires_at`/`quarantine_reclaimed_at` (+ `media_asset_aud` mirrors, quarantine-sweep and outbox-by-asset partial indexes) — so a broker outage no longer lets `MediaPendingReaper`'s 15-minute status-only cutoff permanently delete quarantined vendor uploads whose event provably had not dispatched. Phase 24 Image Architecture: V53 media_asset copy-on-write model + product_media join — ENABLE+FORCE RLS tenant-scoped, (tenant_id, sha256) dedup, ref-counted delete-at-0, per-tenant set_config backfill of the flat image_url/additional_image_urls[] as ACTIVE assets; V58 dedicated media_event_outbox (media.events exchange) driving the safe async upload→validate→WebP-derivative pipeline; V59 adds media_asset.version @Version optimistic lock (reaper/worker race, WR-02). Full per-migration history + RLS notes in CLAUDE.md — this AGENTS.md line is an abbreviated pointer, not the canonical ledger.)
- Next.js config: `frontend/next.config.mjs` (standalone output, image remotePatterns)
- TypeScript config: `frontend/tsconfig.json`
- ESLint: `frontend/.eslintrc.json`
- Dockerfile: `edge-go/Dockerfile` (multi-stage, scratch-based runtime)
- Binary output: `/edge` executable
- Port: 8080 (customizable via PORT env var)
## Platform Requirements
- Docker & Docker Compose 1.40+ (for local stack)
- Java 21 JDK
- Node.js 24+
- Go 1.26+
- Git
- Gradle 8.10+ (included via wrapper)
- npm (included in Node.js)
- Docker (for building multi-stage images)
- Kubernetes (recommended) - See `k8s/` directory for manifests
- Docker container runtime
- PostgreSQL 15+ database
- Redis 7+ (external or managed service)
- RabbitMQ **3.13+** minimum (4.3 recommended — the dev/compose stack pins 4.3.4). The deployed staging/production broker's version is **unverified from this repository** — see `docs/runbooks/rabbitmq-broker-upgrade.md` and ADR-0002.
- Keycloak 24.0+ (external identity provider)
- AWS S3 (or S3-compatible storage like MinIO)
- SMTP server (SendGrid, AWS SES, etc.)
- Spring Boot: 3.5.16 (Java 21)
- PostgreSQL: 15-alpine
- Keycloak: 24.0.5
- Redis: 7-alpine
- RabbitMQ: 4.3.4-management-alpine
- MinIO: latest
- Go: 1.25-alpine
- Node.js: 24+
- Next.js: 16.2.12
## Performance Tuning
- Connection pooling: HikariCP
- Batch insert/update: Hibernate batch_size=20 (prod: 50)
- Query timeout: 30s
- Idle timeout: 10m
- Redis timeout: 2s (dev), 3s (prod)
- Lettuce pool: 8 active, 8 idle (dev), 20 active, 10 idle (prod)
- Default: 100 requests per minute per tenant
- Burst capacity: 20 requests
- Enabled by default (RATE_LIMIT_ENABLED=true)
- Sampling probability: 10% default (increase in dev)
- Zipkin endpoint: http://localhost:9411/api/v2/spans
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Naming Patterns
- Page routes: `page.tsx` (Next.js convention)
- Components: PascalCase (e.g., `CartProvider.tsx`, `SafeImage.tsx`)
- Utilities/helpers: camelCase (e.g., `api-client.ts`, `use-toast.ts`)
- Test files: co-located with `__tests__` directory or `*.test.tsx` suffix
- Hooks: `use<Name>` pattern (e.g., `useToast()`, `useCart()`)
- Entity classes: PascalCase (e.g., `Shop.java`, `Product.java`, `Order.java`)
- Service classes: `<Entity>Service.java` (e.g., `ShopService.java`)
- Controller classes: `<Entity>Controller.java` (e.g., `ShopController.java`)
- Repository interfaces: `<Entity>Repository.java` (e.g., `ShopRepository.java`)
- DTO classes: `<EntityName>Dto.java` or request `<Action><Entity>Request.java`
- Mapper interfaces: `<Entity>Mapper.java` (MapStruct convention)
- Exception classes: `<Reason>Exception.java` in `exception/` package
- Package structure: `internal/<domain>` layout
- Test files: `*_test.go` suffix (standard Go convention)
- Functions: camelCase (e.g., `SearchProducts()`, `CreateOrder()`)
- Types: PascalCase (e.g., `CreateOrderRequest`, `ProductSearchResult`)
- JavaScript/TypeScript: camelCase (e.g., `addItem()`, `removeItem()`, `updateQuantity()`)
- Java: camelCase (e.g., `getShopById()`, `createShop()`, `updateShop()`)
- Go: camelCase exported, lowercase unexported (e.g., `SearchProducts()`, `createRequest()`)
- TypeScript: camelCase (e.g., `itemCount`, `totalPennies`, `shopSlug`)
- Java: camelCase (e.g., `tenantId`, `productId`, `isPublished`)
- Database columns: snake_case (e.g., `created_at`, `delivery_fee_pennies`, `opening_hours`)
- TypeScript: PascalCase (e.g., `CartItem`, `CartContextValue`, `SafeImageProps`)
- Java: PascalCase for classes/records
- Java DTOs: `<Entity>Dto` (e.g., `ShopDto`, `OrderDto`)
- TypeScript: UPPER_SNAKE_CASE (e.g., `TOAST_LIMIT = 1`, `TOAST_REMOVE_DELAY = 1000000`)
- Java: UPPER_SNAKE_CASE for static finals
- Cache keys: use annotation values (e.g., `@Cacheable(value = "shops")`)
## Code Style
- Frontend: Managed by Next.js built-in linting via ESLint config in `.eslintrc.json`
- Backend: Gradle/Spring Boot standard formatting (4-space indentation)
- Configuration: `.eslintrc.json` extends `next/core-web-vitals` and `next/typescript`
- Frontend: ESLint with Next.js and TypeScript rules
- Backend: Gradle tasks enforce Spring Boot patterns and conventions
- TypeScript/JavaScript: 2 spaces (Next.js default)
- Java: 4 spaces
- Go: tabs (Go standard)
## Import Organization
- `@/` points to frontend root directory
- Used throughout: `@/components/`, `@/lib/`, `@/hooks/`, `@/types/`
## Error Handling
- Try-catch blocks in async operations
- Axios interceptors for global error handling (see `api-client.ts`)
- 401 responses trigger redirect to `/auth/signin`
- Errors passed to error boundary or logged to console
- Toast notifications for user-facing errors (not yet implemented pattern, but `useToast` hook available)
- Custom exception hierarchy: `ResourceNotFoundException`, `InvalidStateTransitionException` in `uk.jtoye.core.exception`
- Global exception handler: `GlobalExceptionHandler` annotated with `@RestControllerAdvice`
- Returns RFC 7807 Problem Detail responses with:
- Specific handlers for:
- Error wrapping with `fmt.Errorf("context: %w", err)` for error chain preservation
- Status code checks: `if httpResp.StatusCode >= 400`
- Circuit breaker integration: errors passed through `c.breaker.Execute()` wrapper
- Error logging: typically returned to caller, let client decide logging
## Logging
- Frontend: `console.log()`, `console.error()` (browser console)
- Backend Java: SLF4J with LoggerFactory (configured in Spring Boot)
- Go: `go.uber.org/zap` for structured logging
- Service layer: entry point of significant operations
- Condition checks: `log.debug("Checking X condition")`
- State changes: `log.info("Created shop {} with ID {} for tenant {}")`
- Errors: caught exceptions before rethrowing or handling
- DEBUG: method entry, intermediate calculations, detailed flow
- INFO: business-significant operations (create, update, delete)
- WARN: recoverable issues, deprecated usage
- ERROR: exceptions, failures that need attention
## Comments
- Complex algorithm logic: explain the "why", not the "what"
- Non-obvious business rules: e.g., slug generation, UUID handling
- Workarounds and known limitations: why a shortcut exists
- Integration points with external systems
- Used sparingly but consistently
- Function-level comments for public exports in utilities
- Example from `safe-image.tsx`:
- Controller methods: OpenAPI annotations (`@Operation`, `@ApiResponse`) preferred over Javadoc
- Service methods: Brief Javadoc comment explaining purpose
- Exception classes: Single-line Javadoc explaining when thrown and resulting HTTP status
## Function Design
- Target: < 50 lines for complex business logic
- Small utility functions: < 10 lines acceptable
- Controllers: typically 5-15 lines (delegation to service)
- Frontend: use destructuring for objects (e.g., `{ shopSlug, children }`)
- Backend: individual parameters for JPA/Spring (entities, DTOs)
- Go: explicit parameters, error as last return value
- Frontend: React components return JSX, hooks return state + methods
- Backend: Services return DTOs or Optional<DTO>
- Go: multiple returns with `(result, error)` convention
## Module Design
- Frontend: Named exports for components, default export for pages
- Backend: Public classes are exported, package-private for internal classes
- Go: Capitalized identifiers are exported, lowercase unexported
- Not heavily used in this codebase
- React component groups exported individually
- Frontend: `app/` (pages), `components/`, `lib/`, `hooks/`, `types/`
- Backend: `src/main/java/uk/jtoye/core/<domain>/` (feature modules)
- Go: `internal/<domain>/` (isolated by feature)
## Specific Patterns
- Frontend: TypeScript strict mode, interface/type definitions required
- Backend: Gradle type checking, POJO/DTO validation with `@Valid`
- Go: Explicit type declarations, error type checking
- Frontend TypeScript: Optional chaining (`?.`), nullish coalescing (`??`)
- Backend Java: `Optional<T>`, null checks with guard clauses
- Go: Error-checking pattern, nil checks before dereferencing
- Frontend: React uses immutable state updates (spread operator, map/filter)
- Backend: Entity setters used in service layer, DTOs are mutable POJOs
- Functional style preferred in logic implementations (map, filter, reduce)
- Frontend: React Context and hooks for shared state
- Backend: Spring dependency injection via constructor injection
- Go: Manual injection, passing dependencies as function arguments
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- Multi-tenant isolation enforced at database (RLS policies), middleware (JWT extraction), and application layers
- Service-Repository pattern for business logic layering with clean separation
- Event-driven state machine for order workflows with Spring State Machine
- Tenant-aware caching with Redis, scoped by TenantContext
- Edge-to-Core data synchronization via high-volume batch API
- JWT-first authentication with Keycloak OAuth2/OIDC
## Layers
- Purpose: Customer-facing UI and admin dashboards for multi-tenant operations
- Location: `frontend/app/`, `frontend/components/`
- Contains: Next.js 16 page routes, React components, form validation, API client integration
- Depends on: Backend Core API (`NEXT_PUBLIC_API_URL`), NextAuth.js for session management
- Used by: Browser clients (B2B admin dashboard, B2C customer storefronts)
- Purpose: Rate limiting, JWT validation, circuit breaker protection, request routing
- Location: `edge-go/cmd/edge/main.go`, `edge-go/internal/`
- Contains: Token bucket rate limiter, JWT middleware, Core API client with circuit breaker, WhatsApp webhook handler
- Depends on: Core Java API, Keycloak for JWKS, RabbitMQ for async messaging
- Used by: Storefront pages, mobile clients, external webhook integrations
- Purpose: Full REST API surface with CRUD operations, state management, tenant isolation
- Location: `core-java/src/main/java/uk/jtoye/core/`
- Contains: REST controllers, service layer, repository layer, domain entities, mappers, configurations
- Depends on: PostgreSQL database (RLS-enabled), Redis cache, RabbitMQ, Stripe API, S3/MinIO storage, Keycloak
- Used by: Frontend, Edge gateway, batch sync operations, webhook processors
- Purpose: ORM abstraction for tenant-scoped database queries
- Location: `core-java/src/main/java/uk/jtoye/core/*/` (repository interfaces in each domain folder)
- Contains: JpaRepository extensions with custom queries, named queries for full-text search
- Depends on: PostgreSQL JDBC driver, Flyway for schema migration
- Used by: Service layer exclusively
- Purpose: Multi-tenant data storage with RLS enforcement and audit trails
- Location: Schema defined in `core-java/src/main/resources/db/migration/` (32 Flyway migrations)
- Contains: Tables (shops, products, orders, customers, financial_transactions, reviews, etc.), RLS policies per table, audit tables via Envers
- Depends on: JDBC driver, Java code for policy setup
- Used by: Core Java service layer via JPA, trigger functions for audit events
## Data Flow
- Order state: Stored in Order.status field, validated by state machine, sourced from database
- Cache state: TenantContext.CURRENT (ThreadLocal), populated by JwtTenantFilter per request
- Session state: NextAuth.js session in browser cookie, refreshable from Keycloak token endpoint
- Business metrics: Captured by BusinessMetricsService (scheduled task) and published to Micrometer metrics for Prometheus scrape
## Key Abstractions
- Purpose: Thread-local holder of current tenant_id for request scope
- Examples: `uk.jtoye.core.security.TenantContext`
- Pattern: ThreadLocal<UUID> with static get()/set()/clear() methods. JwtTenantFilter populates on each request, cleared after response.
- Purpose: Separate business logic (Service) from data access (Repository)
- Examples: `ShopService` / `ShopRepository`, `OrderService` / `OrderRepository`, `CustomerService` / `CustomerRepository`
- Pattern: Service is @Transactional, handles caching, validation, state transitions. Repository is JpaRepository extension with @Query methods.
- Purpose: Compile-time safe DTO ↔ Entity conversion
- Examples: `ShopMapper`, `OrderMapper`, `ProductMapper` (located alongside entities in each domain)
- Pattern: Interfaces with @Mapper(componentModel="spring") and abstract mapping methods, processor generates implementations at compile time.
- Purpose: Enforce valid order lifecycle transitions
- Examples: `OrderStateMachineConfig`, `OrderStateMachineService`
- Pattern: States (OrderStatus enum: DRAFT, PENDING, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED), Events (OrderEvent enum), Transitions defined in StateMachineConfigurerAdapter.
- Purpose: Prevent cross-tenant cache key collisions
- Examples: `TenantAwareCacheKeyGenerator` (no source file found, but used in CacheConfig)
- Pattern: Bean implementing KeyGenerator, reads TenantContext.get() and appends to cache key.
- Purpose: Protect edge from Core outages with fallback degradation
- Examples: `edge-go/internal/core/client.go` with Resilience4j fallback (Spring side) or Gin middleware (Go side)
- Pattern: HTTP client with timeout + retry logic, falls back to cached response or returns 503.
## Entry Points
- Location: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Triggers: Spring Boot application start (`SpringApplication.run()`)
- Responsibilities: Enable async execution, enable scheduling (for cleanup jobs), redirect root to Swagger UI, serve /health endpoint
- Location: `frontend/app/page.tsx`
- Triggers: Browser navigates to /
- Responsibilities: Redirect authenticated users to /dashboard, redirect unauthenticated to /auth/signin
- Location: `edge-go/cmd/edge/main.go`
- Triggers: Docker container startup or direct binary execution
- Responsibilities: Initialize Gin router, attach JWT middleware, rate limiter, route /health, /sync/batch, /orders, /whatsapp to Core API with circuit breaker
- `ShopController` (`/shops`): GET, POST, PUT, DELETE, search, image upload
- `ProductController` (`/products`): CRUD with filtering, full-text search, image gallery
- `OrderController` (`/orders`): CRUD, state transitions, SSE for real-time updates
- `CustomerController` (`/customers`): CRUD with email lookup
- `PaymentController` (`/payments`): Stripe integration, webhook handling
- `FinancialTransactionController` (`/financial-transactions`): VAT tracking, transaction ledger
- `SyncController` (`/sync/batch`): High-volume batch sync from edge
- `DevTenantController` (`/dev/tenants`): Development-only tenant CRUD (disabled in production)
## Error Handling
- `ResourceNotFoundException` (404): Thrown when entity not found by ID or unique constraint
- `InvalidStateTransitionException` (400): Thrown when state machine rejects a transition
- `IllegalStateException` (500): Thrown when TenantContext is not set (indicates security configuration error)
- `ConstraintViolationException` (400): From @Valid on @RequestBody, automatic Spring conversion
- `ValidationException` (400): From Jakarta Validation annotations
- All exceptions caught by @ExceptionHandler methods, converted to ErrorResponse (timestamp, status, error, message, path), returned as JSON with appropriate HTTP status
```json
```
## Cross-Cutting Concerns
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
