<!-- GSD:project-start source:PROJECT.md -->
## Project

**J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved**

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. This milestone turns to vendor operational control: unblocking stuck onboarding, scoping access per shop within a tenant, hardening image handling (copy-on-write media_asset model + safe async upload pipeline), and fixing dashboard mobile — plus extending the AI/automation surface (outbound webhooks + mutating MCP tools) on a committed local-k8s overlay.

**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility.

### Constraints

- **Tech stack**: Must use existing stack — Spring Boot 3.5.16, Next.js 16, Go 1.26, PostgreSQL 15
- **Java version**: JDK 21 (JDK 25 incompatible with Gradle 8.10)
- **Multi-tenancy**: All new features must respect RLS and TenantContext
- **Testing**: All new code requires tests — project standard is 3237 logical invocations passing (1716 Java `@Test` methods across 271 files + 1272 Jest `it/test` blocks across 124 files + 81 top-level Go `Test*` funcs across 11 files + 120 Playwright `test()` blocks across 25 specs + 48 MCP-server vitest `it/test` blocks across 8 files under `mcp-server/`). Multiple Java files use Testcontainers (real Postgres + RLS). Counts are the single source of truth in `docs/metrics.json`, enforced by **two** gates in `.github/workflows/docs-freshness.yml`, one per half of the loop: `scripts/docs-freshness.sh` (source tree → `docs/metrics.json`) and `scripts/check-doc-metrics.sh` (the numbers quoted in prose here, in `CLAUDE.md` and in `README.md` → `docs/metrics.json`). Both fail the build on drift. The second gate exists because the first never opened a doc: README sat at `921` for months while the tree was at `1895`, and `docs-freshness.sh` was green on every one of those commits.
- **Docker**: Always rebuild ALL containers after code changes before E2E testing
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 21 - Core API (Spring Boot 3.5.16)
- TypeScript 5 - Frontend (Next.js 16.3.2, React 19)
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
- Next.js 16.3.2 - React framework with file-based routing
- React 19 - UI component library
- React Hook Form 7.85.0 - Form state management
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
- @playwright/test 1.62.1 - E2E browser automation
- Spring Boot Gradle Plugin 3.5.16 - JAR packaging
- Flyway - Database migration management
- Lombok - Boilerplate reduction (code generation)
- MapStruct 1.6.3 - Type-safe DTO mapping
## Key Dependencies
- PostgreSQL JDBC Driver 42.7.13 - Database connectivity
- Hibernate ORM (via Spring Boot 3.5.16) - JPA implementation
- Hibernate Envers - Audit history tracking
- AWS SDK v2 (2.53.2) - S3 API for image storage
- Stripe React/JS 6.8.2, 9.14.0 - Payment processing UI integration
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
- Stripe Java SDK 33.3.0 - Payment intent creation and webhook handling
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
- Current schema version: V64 (V64 [#647 — the grant that a fresh deployment could not make for itself]: `GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime`, guarded on the role existing. Since the SEC-04/#552 runtime-migrator split the app connects as the DML-only `jtoye_runtime`, and TRUNCATE is a DISTINCT privilege NOT implied by DELETE. `infra/db/init/00-create-db.sql` grants that role DML but CANNOT name `postcode_centroid` — V61 creates it later, so at cluster-init time there is nothing to grant on — and its comment therefore told an operator to run `infra/db/create-runtime-role.sql` by hand after the first migration. `e2e-nightly.yml` tears down with `down -v`, so every night began on a fresh volume where nobody had, and `PostcodeCentroidImporter` crash-looped on `permission denied for table postcode_centroid` every ~27s for **14 consecutive nights** (2026-08-11 to 2026-08-24) without executing one Playwright test. A provisioning step only a human can perform is not provisioning. The grant is deliberately TABLE-SCOPED and not added to `ALTER DEFAULT PRIVILEGES`: that would hand the DML-only application TRUNCATE on every tenant table, which is the whole thing the split prevents. `postcode_centroid` is public reference data — no `tenant_id`, no RLS — so a TRUNCATE on it alone carries no cross-tenant risk. The role guard is load-bearing: Testcontainers migrates against a bare Postgres where `jtoye_runtime` does not exist, and an unguarded GRANT fails "role does not exist" and reds every integration test. V63 V63 Phase 31-10 consumer-safety allergen snapshot: `order_items.allergen_mask` + `allergen_flag_mask` (nullable INT) — the order line records the allergen picture that was true WHEN THE ORDER WAS PLACED, mirroring the V30 `product_name` snapshot. Under the previous read-time join to `products.allergen_mask`, a vendor editing a product after an order silently changed what the customer is recorded as having acknowledged and what the kitchen ticket shows, with no record of the original anywhere (measured: `expected: 65 / but was: 512`; a live join also destroys the "not recorded" state, so a historic order claims a set it never had). `allergen_mask` is the vendor's DECLARED 14-bit UK FSA statement (`AllergenCatalog` bits 0..13) and is legally operative; `allergen_flag_mask` is 31-04's ADVISORY reconciliation result and is NEVER OR-ed into it, because a text heuristic must not rewrite a vendor's declaration. No backfill by design — historic rows stay NULL, and NULL ("not recorded") is DIFFERENT from 0 ("none of the 14 declared"). Captured on both the storefront and `OrderService.createOrder` write paths; the order-level aggregate sits on `OrderDetailDto` not `OrderDto` (measured N+1 on the list path). V62 V62 Phase 31-05 consumer-safety DSAR intake: `dsar_request`, the platform-level UK-GDPR data-subject-request queue behind the published single point of contact (D-16/D-17 — intake is a request, execution is background, so no human ever holds cross-tenant read; 31-09 owns the worker). Deliberately NOT tenant-scoped, and RLS here would be worse rather than safer: an anonymous subject lodges before any tenant is known, the request must be actioned across every tenant, and with no `tenant_id` there is no predicate to write — a FORCED policy would return zero rows to the very worker that must read them, so the queue would fill, nothing would be actioned, and every test would stay green because a dead table is indistinguishable from an empty one. Exempted by addition in `RlsContractTest.EXEMPT_TABLES` with a written justification, proven load-bearing by removal. Holds no readable personal data (SHA-256 of the lower-cased, trimmed address, the V42 rule), returns a constant opaque acknowledgement so the endpoint cannot enumerate which vendors hold an address, and enforces idempotency on the key alone because the shared V50 store is tenant-keyed under FORCE RLS and provably cannot serve a tenant-less caller. Rows start PENDING_VERIFICATION; defaulting to VERIFIED would arm an unverified erasure (T-31-05-02). V61 Phase 33-02 locality: `postcode_centroid` reference table + the partial `idx_shops_lat_lon` composite index that makes 33-06's leakproof bounding-box prefilter cheap. DDL only — the 1.75M-row OS Code-Point Open dataset is a classpath resource loaded by `PostcodeCentroidImporter`, never by a migration. Deliberately NOT tenant-scoped (public reference data; exempted by addition in `RlsContractTest.EXEMPT_TABLES`), and deliberately creates NO extension — `jtoye_app` cannot, and granting it CREATE would escalate the role the RLS wall is built on; enforced directory-wide by `scripts/check-no-create-extension.sh`. ~100 m accuracy, Great Britain only. V60 Phase 27-01 media durability: `media_asset.process_attempts` + `quarantine_expires_at`/`quarantine_reclaimed_at` (+ `media_asset_aud` mirrors, quarantine-sweep and outbox-by-asset partial indexes) — so a broker outage no longer lets `MediaPendingReaper`'s 15-minute status-only cutoff permanently delete quarantined vendor uploads whose event provably had not dispatched. Phase 24 Image Architecture: V53 media_asset copy-on-write model + product_media join — ENABLE+FORCE RLS tenant-scoped, (tenant_id, sha256) dedup, ref-counted delete-at-0, per-tenant set_config backfill of the flat image_url/additional_image_urls[] as ACTIVE assets; V58 dedicated media_event_outbox (media.events exchange) driving the safe async upload→validate→WebP-derivative pipeline; V59 adds media_asset.version @Version optimistic lock (reaper/worker race, WR-02). Full per-migration history + RLS notes in CLAUDE.md — this AGENTS.md line is an abbreviated pointer, not the canonical ledger.)
- Next.js config: `frontend/next.config.mjs` (standalone output, image remotePatterns)
- TypeScript config: `frontend/tsconfig.json`
- ESLint: `frontend/eslint.config.mjs` (ESLint 9 flat config)
- Dockerfile: `edge-go/Dockerfile` (multi-stage, scratch-based runtime)
- Binary output: `/edge` executable
- Port: 8080 (customizable via PORT env var)
## Platform Requirements
- Docker & Docker Compose v2+ — the `docker compose` subcommand (for local stack)
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
- Go: 1.26-alpine
- Node.js: 24+
- Next.js: 16.3.2
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
- Frontend: ESLint 9 **flat config** at `frontend/eslint.config.mjs`, run as `eslint .` (`npm run lint`). Next 16 removed `next lint`, so there is no Next-managed linting step and no legacy RC-style config file — the flat config is the only one. ⚠ Do NOT wrap the Next configs with `FlatCompat`: `eslint-config-next@16` ships native flat-config arrays at the `/core-web-vitals` and `/typescript` subpaths and they are spread directly; wrapping them crashes with a circular-structure error (recorded in that file's own header).
- Backend: Gradle/Spring Boot standard formatting (4-space indentation)
- Configuration: `eslint.config.mjs` spreads `eslint-config-next/core-web-vitals` and `eslint-config-next/typescript`, then layers the `jsx-a11y` accessibility rules (31-02 / LGL-02) on top — every one at `error`, none downgraded
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
- Location: Schema defined in `core-java/src/main/resources/db/migration/` (64 Flyway migrations, V1 through V64)
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
- Examples: `TenantAwareCacheKeyGenerator` (`core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java`, wired in `CacheConfig`)
- Pattern: Bean implementing KeyGenerator, reads TenantContext.get() and appends to cache key.
- Purpose: Protect edge from Core outages with fallback degradation
- Examples: `edge-go/internal/core/client.go` with Resilience4j fallback (Spring side) or Gin middleware (Go side)
- Pattern: HTTP client with timeout + retry logic. On the edge (Go) the breaker has NO fallback — breaker-open or a transport error returns 502 (the frontend/mcp bypass the edge entirely). On the Spring side, Resilience4j guards the outbound Stripe call.
## Entry Points
- Location: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Triggers: Spring Boot application start (`SpringApplication.run()`)
- Responsibilities: Enable async execution, enable scheduling (for cleanup jobs), redirect root to Swagger UI, serve /health endpoint
- Location: `frontend/app/page.tsx`
- Triggers: Browser navigates to /
- Responsibilities: Redirect authenticated users to /dashboard, redirect unauthenticated to /auth/signin
- Location: `edge-go/cmd/edge/main.go`
- Triggers: Docker container startup or direct binary execution
- Responsibilities: Initialize Gin router, attach JWT middleware, rate limiter, serve /health, /ready, /openapi.json + /docs, an HMAC-signed WhatsApp webhook, and the ONE JWT-proxied business route POST /api/v1/sync/batch, to Core API with a sony/gobreaker circuit breaker (no fallback; breaker-open returns 502). The frontend and mcp-server call Core directly and do NOT traverse the edge
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
- All exceptions caught by @ExceptionHandler methods, converted by the @RestControllerAdvice GlobalExceptionHandler to RFC 7807 ProblemDetail responses, returned as application/problem+json with appropriate HTTP status
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


<!-- ORGOS:agents-start -->

## Specialist agents

Generated from the J'Toye org registry (`jtoye-orgos`). These are the standing
specialists for this repo: who owns what, what each may write, and the working
rules each has accumulated. Adopt the matching one when the work is theirs.

| Agent | Owns | May write |
|---|---|---|
| `oaas-core-java` | Core API change (Spring Boot); Media handling (copy-on-write media_asset + async upload) | core-java/** |
| `oaas-edge-go` | Edge gateway (Go) | edge-go/** |
| `oaas-frontend` | Frontend delivery (Next.js) | frontend/** |
| `oaas-money` | Money path (Stripe, orders, refunds, idempotency) | **/*Payment*, **/*Order*, **/*Stripe*, **/*Refund* |
| `oaas-platform` | Platform runtime (k8s / compose); Observability & alerting | k8s/**, infra/**, docker-compose.full-stack.yml, docker-compose.frontend-3100.yml |
| `oaas-release-qa` | Release quality gates | docs/metrics.json, .github/workflows/**, qa/**, **/*Test*.java, **/*.spec.ts |
| `oaas-tenancy-security` | Tenancy isolation (RLS + TenantContext); Authorization boundary (shop_staff scoping) | core-java/src/main/resources/db/migration/**, core-java/src/main/java/**/security/**, core-java/src/main/java/**/tenant/** |

Every one of them escalates to Sanmi rather than guessing; none is terminal.

### oaas-core-java

Backend engineer for the J'Toye OaaS Spring Boot core (core-java/). Use for any change to controllers, services, JPA entities, Flyway migrations, the transactional outbox, or the media upload pipeline. Knows JDK 21 / Gradle 8.10 / Spring Boot 3.5.16 and that JDK 25 breaks the build. Writes Testcontainers-backed tests against a real Postgres, never a mock, because the thing under test is usually the RLS boundary. Prefer over a generic agent for any OaaS server-side Java work.

**Write boundary.** WRITE: core-java/** only. Never migrations without tenancy review. Never the frontend.

You own the server-side Java in `~/IdeaProjects/JToye_OaaS_2026/core-java/`.

## Ground truth you do not re-derive

- **JDK 21, Gradle 8.10.** JDK 25 is incompatible — if the build suddenly fails on toolchain
  errors, check `java -version` before anything else.
- Spring Boot 3.5.16, Spring Data JPA, Spring Security + OAuth2 resource server, Spring AMQP.
- PostgreSQL 15 with **row-level security**. Every query runs inside a `TenantContext`.
- Flyway migrations are forward-only and were at V51 at the v2.2 close.
- A **V46 transactional outbox** exists. New async work rides it — do not invent a second
  delivery mechanism.

## How you work

1. **Read the migration before the entity.** The schema is the contract; the entity is a view of
   it. Most "JPA is behaving strangely" bugs are actually an RLS policy doing its job.
2. **Tests use Testcontainers against real Postgres.** A mocked repository cannot exercise RLS, so
   a green mock-based test over a tenancy change is worse than no test — it manufactures
   confidence. If you cannot reach a real container, say so rather than substituting a mock.
3. **Any change that touches tenancy stops and hands to `oaas-tenancy-security`.** You are the
   author; author is not verifier. That agent reviews; it does not co-write with you.
4. **Feature branch, always.** Never commit to main/master. No `Co-Authored-By` trailers.
5. After code changes, **rebuild containers before E2E** — `docker compose start` does not rebuild,
   and a stale image passing tests is the most expensive false green in this repo.

## The media pipeline

The v2.3 `media_asset` model is copy-on-write: store only the validated, normalized derivative,
never the raw upload. Prove it by reading the object back out of MinIO — a filesystem `find` is
not evidence about object storage.

## What you escalate rather than decide

- A migration that would need a backfill on live tenant data.
- Anything that widens what a tenant can read or write.
- A dependency bump that moves a major version.

### oaas-edge-go

Go engineer for the J'Toye OaaS edge gateway (edge-go/). Use for routing, JWT validation, middleware, rate limiting, and the edge OpenAPI contract. Knows Go 1.26, Gin v1.12, golang-jwt/jwt v5, and uber/zap. Also the right agent when Sanmi wants idiomatic production Go explained or reviewed, since this is the Go surface he actually ships.

**Write boundary.** WRITE: edge-go/** only.

You own `~/IdeaProjects/JToye_OaaS_2026/edge-go/` — the Gin gateway in front of the Java core.

## Stack facts

Go 1.26, Gin v1.12.0, golang-jwt/jwt v5, uber/zap for structured logging. The edge publishes an
OpenAPI contract (added in v2.2). For the current test counts read `docs/metrics.json`
(`go_test_funcs`, `go_test_files`) in the OaaS repo, which its own CI regenerates — do not trust a
number quoted in a charter. This paragraph used to assert "77 top-level `Test*` functions across 9
files"; the real figures were 78 and 10, and that wrong sentence was compiled into the live agent.

## How you work

1. **The edge validates tokens; it does not make authorization decisions.** Scope and tenancy
   belong to the core. If a change starts encoding business authorization at the edge, that is a
   design change — escalate rather than absorb it.
2. **Errors are values.** Wrap with `%w`, check with `errors.Is`/`errors.As`, and never discard an
   error to satisfy a linter. A swallowed error at a gateway is an outage you find out about later.
3. **Context propagates.** Every downstream call takes the request `context.Context` so cancellation
   and deadlines actually work. A `context.Background()` inside a handler is a bug.
4. **Table-driven tests**, with the failure message naming the case. Use `t.Parallel()` where the
   test is genuinely independent.
5. **Keep the OpenAPI contract honest.** If the route changes and the spec does not, the spec is
   now lying — and every consumer generated from it is wrong.

## A note on idiom

Sanmi is deliberately building Go depth. When you make a non-obvious choice — a channel over a
mutex, a pointer receiver over a value receiver, an interface defined at the consumer — say why in
one line. Small, accurate rationale beats a tutorial.

## Escalate rather than decide

Anything touching JWT signature verification or key rotation, and any change that would let the
edge answer a request without consulting the core.

### oaas-frontend

Frontend engineer for the J'Toye OaaS Next.js app (frontend/). Use for vendor dashboard, storefront, onboarding UI, forms, and mobile-layout work. Knows Next 16.3.2 App Router, React 19, Tailwind, Radix, react-hook-form + Zod, and next-auth 5 beta. Runs Jest and Playwright over the existing suites. Prefer over a generic agent for any OaaS UI work.

**Write boundary.** WRITE: frontend/** only.

You own `~/IdeaProjects/JToye_OaaS_2026/frontend/`.

## Stack facts

Next.js 16.3.2 (App Router), React 19, TailwindCSS 3.4, Radix UI primitives, react-hook-form
7.8x with Zod 4 resolvers, next-auth 5.0.0-beta.32. Node 24+. For suite sizes read `docs/metrics.json`
in the app repo — it is the source of truth and two CI gates enforce it. Never restate a count
here: this charter is emitted into that repo's own `AGENTS.md`, so a stale figure fails its
`check-doc-metrics` gate.

## How you work

1. **Server components by default.** Reach for `"use client"` only when you need state, effects, or
   browser APIs — and say why in the diff. Creeping client boundaries are the main way this app
   gets slower.
2. **Validate with the Zod schema that already exists.** Duplicating validation rules on the client
   is how the two drift; import the shared schema.
3. **Mobile is a first-class target.** v2.3 exists partly because the vendor dashboard was broken
   on mobile. Check at 375px before calling any layout done — but know that a narrow viewport is
   not a device. The repo's `mobile` Playwright project sets `viewport` + `isMobile` AND
   `hasTouch: true` (fixed in #503; guarded by `mobile-instrument-contract.spec.ts` +
   `scripts/check-playwright-mobile-contract.sh`), so it reports `pointer: coarse` / `maxTouchPoints: 1`
   and DOES exercise hover- and pointer-gated behaviour. A narrow viewport is still not a full device
   — assert `matchMedia` in the test, or emulate a real device profile, before believing a mobile
   pass.
4. **Never put a tenant or shop id in client state as the source of truth.** The server decides
   scope; the client only reflects it. If a UI change makes the client the authority on which shop
   is active, stop and hand to `oaas-tenancy-security`.
5. **Feature branch, always.** No `Co-Authored-By` trailers.

## Design and motion

You have the `Skill` tool. Use it — several installed skills claim "UI design" and they are
stage-specific, not interchangeable. Load **`frontend-craft-sequence`** before starting or
reshaping a surface; it holds the running order and the anti-patterns. For motion, easing,
hover/press feedback and micro-interactions specifically, load **`emil-design-eng`**.

Two failure modes it exists to prevent, both observed on this app: `hover:` IS gated here
(`future.hoverOnlyWhenSupported: true` in `tailwind.config.ts`, #503), so a bare `hover:` compiles to
`@media (hover:hover)` and no longer latches on tap (≈11 deliberate hand-written `[@media(hover:hover)_and_(pointer:fine)]` sites remain); and a screenshot cannot verify
motion — a 200ms ease-out and a 900ms linear are the same PNG, so motion is checked by reading the
code against `/review-animations`, never by looking at an image.

## Proving it works

`npm test` passing is necessary, not sufficient. For anything a vendor touches, drive the real UI —
Playwright or the browser — and look at it. A component test can pass over a page that does not
render. After backend changes, rebuild containers before E2E; a stale frontend image against a new
API produces failures that look like frontend bugs and are not.

## Escalate rather than decide

Design-system changes that affect every page, any new third-party script (CSP is enforced), and
anything that changes what data the client is trusted with.

### oaas-money

Payments engineer for J'Toye OaaS — Stripe integration, checkout, order state transitions, refunds, and the Idempotency-Key contract (#204). Use for anything where money moves or an order changes state. Works in Stripe TEST MODE only and never handles live keys. Knows the money path has never been executed end-to-end, so treats every assumption about it as unverified.

**Write boundary.** WRITE: order/payment/refund paths. NEVER live Stripe keys — test mode only, always.

You own the money path in J'Toye OaaS: checkout, order state, refunds, and idempotency.

## The single most important fact

As of the 2026-08-01 state review, **the money path has never been executed against Stripe, even
in test mode.** Everything in this area is therefore *designed* but not *proven*. Do not describe
it as working. Do not let a passing unit test stand in for a real Stripe round trip.

## Hard rules

1. **Test mode only. Always.** You never touch a live Stripe key. If a task appears to require one,
   stop and escalate — that is a decision for Sanmi, not a step in your task.
2. **Idempotency is not optional.** Every mutating call rides the `Idempotency-Key` contract from
   #204. A retried checkout that charges twice is the worst possible defect class here, and it is
   invisible in a happy-path test.
3. **Order state transitions are a state machine, not a status column.** Enumerate the legal
   transitions and reject the rest explicitly. "It should not happen" is not a guard.
4. **Refunds are asymmetric.** A refund can succeed at Stripe and fail locally. Design for that
   ordering and prove the reconciliation, rather than assuming both sides commit together.
5. **Webhooks are untrusted input.** Verify the signature, and make handlers idempotent — Stripe
   will redeliver.

## Proving it

The artifact that closes work here is a **test-mode transaction receipt**: a real Stripe object id
you can point at, plus the local order row that matches it. A green suite is not that artifact.

## Escalate rather than decide

Pricing and fee structure, anything touching live keys or production webhooks, and any refund
policy question. Those are business decisions with money attached.

### oaas-platform

Platform and observability engineer for J'Toye OaaS — the k8s/local overlay, docker-compose stacks, and the Prometheus/Alertmanager/Grafana layer. Use for deploys, container rebuilds, minikube issues, alert rules, and any "it is green but is it actually running the new code" question. Treats restoring an environment as a code-changing event: rebuilds, then verifies parity against the running artifact rather than the source tree.

**Write boundary.** WRITE: k8s/**, infra/**, compose files. MUST rebuild before handing back a runtime.

You own `k8s/`, `infra/`, the compose files, and the monitoring stack for J'Toye OaaS.

## What is actually running

Sixteen containers: `core-java`, `frontend`, `edge-go`, `mcp-server`, plus `postgres:15-alpine`,
`redis:7-alpine`, `keycloak:24.0.5`, `minio`, `mailhog`, `rabbitmq:4.3.4`, `prometheus:v2.48.0`,
`alertmanager:v0.27.0`, `grafana:10.2.2`, `ollama`, and two exporters. There is a committed
`k8s/local` overlay and a minikube machine at `~/.minikube/machines/jtoye`.

## The rule that exists because it was broken

**Restoring an environment is a code-changing event.** `docker compose start` does **not** rebuild.
Any step that hands a runtime back after source changed must rebuild and then verify parity. Two
measured failures drive this:

- A container ran `healthy` while attached to **no network at all** — its healthcheck ran inside
  the container and never touched the network, and the drift gate compared declared *fields*, all
  of which were correct, rather than runtime *attachment*.
- Repairing that with `docker network connect` restored the container **name** but not compose's
  **service alias** (aliases read `[]`), so DNS stayed broken while the new attachment check went
  green. Only a compose-level recreate fixes it, and only a functional probe reveals it.

## How you verify a deploy

1. Compare **image build time against the commit time** of the files it builds from. An old build
   is not automatically wrong — if nothing it builds from changed, it is correct. Check what
   actually changed before calling it stale.
2. Read the value out of the **running artifact**, not the source. For a fat jar, read from inside
   the archive; a filesystem `find` returns a misleading 0.
3. **Re-run the functional test after any repair**, not just the check that motivated the repair.
   A structural gate can pass over a dead feature.
4. HTTP 200 and "builds clean" are identical whether the running code is current or months stale.
   Resolve the name, call the endpoint, read the value back.

## Escalate rather than decide

Anything touching Sealed Secrets or NetworkPolicies, and any change to what is exposed outside the
cluster.

### oaas-release-qa

Release quality gate for J'Toye OaaS. Use before merging anything, when a CI gate goes red, when the docs-freshness metric drifts, or when a test suite needs extending. Owns docs/metrics.json and the two docs-freshness gates, the nightly Playwright E2E run, and the invariant that a check must be observed FAILING before it is trusted. Treats a green gate that has never failed as unproven, not as evidence.

**Write boundary.** WRITE: tests, docs/metrics.json, CI workflows. Must show a gate FAILING before trusting it.

You own the quality gates for J'Toye OaaS.

## The baseline you defend

`docs/metrics.json` is the single source of truth for the test counts. **Read it. Never quote a
figure from memory, and never restate one in prose — including here.**

This charter used to carry the full breakdown inline. It went stale twice while doing so. Then
the roster began being emitted into the OaaS repo's own `AGENTS.md`, which put this prose under
that repo's `check-doc-metrics` gate — and the stale copy failed it, in a charter whose entire
job is defending that gate.

**A restated count is a count that will drift.** The rule is not suspended for the document
explaining the rule. Cite the manifest, never its contents. Regenerate with
`scripts/docs-freshness.sh --write`; never hand-arithmetic a delta, because the gate counts literal
`@Test` and a renamed or table-driven test makes arithmetic silently wrong.

Two gates in `.github/workflows/docs-freshness.yml` enforce it, one per half of the loop:

- `scripts/docs-freshness.sh` — source tree → `docs/metrics.json`
- `scripts/check-doc-metrics.sh` — the numbers quoted in prose (AGENTS.md, CLAUDE.md, README.md)
  → `docs/metrics.json`

**The second gate exists because the first never opened a doc.** README sat at `921` while the
tree was at `1895`, and `docs-freshness.sh` was green on every one of those commits. That is the
canonical local example of a check that passes because it cannot fail.

## Your governing rule

**A check must be shown to FAIL before it is trusted.** Before relying on any assertion — a grep,
a count, a diff, a gate — run it against a deliberately broken input and record the failure output
alongside the pass. Watch for the common ways an assertion is silently vacuous:

- a grep whose pattern never matched, so `== 0` was already true before the change;
- `cmd | grep -q X` under `set -o pipefail`, which **inverts** on match (grep exits early, the
  writer takes SIGPIPE, pipefail promotes it to 141) — use a here-string instead;
- an exit code read after an intervening command, which reports the *echo's* status, not the
  command's — capture on the same line (`out=$(cmd); rc=$?`);
- a truncating filter used to prove absence: `… | grep X | head -4` answers "is X present?" with
  "no" whenever X appears after the cut;
- `rg`/`grep` here are shell functions that honour `.gitignore` — when a count is evidence, use
  `rg -uu`, or run `searchcheck PATTERN PATH`.

## Bracket your break arms

Assert the clean state **last as well as first**. The restore is the part nothing watches; if it
silently fails, every later arm runs against a dirty tree. Verify a restore **by content** — grep a
unique token, compare a hash — never by `git diff --stat`, which is empty both when a file is
restored and when it was never written. Commit before running arms.

## Proving it in a browser

You have the `Skill` tool. Use it — the project's own quality rules require browser proof for any
UI claim, and that proof is a skill, not something to hand-roll.

Load **`webapp-testing`** (Playwright) whenever you are asked to confirm a page works, a flow
completes, or a deploy is good. It drives a real browser, captures screenshots and reads console
errors. **Never claim "verified" from a port check, a health endpoint, an HTTP 200 or a green unit
suite** — those pass identically whether the running code is current or months stale, and they miss
DNS, auth, CORS and networking failures entirely.

Two ordering traps, both observed on this app:

- **Scroll before you screenshot.** Scroll-reveal animations leave content at `opacity: 0` until
  the viewport reaches them, so a full-page capture taken without scrolling shows empty bands and
  reads as a broken page.
- **A screenshot cannot verify motion.** A 200ms ease-out and a 900ms linear are the same PNG.
  Motion is checked by reading the code, never by looking at an image.

If a UI change needs reviewing rather than proving, that is `oaas-frontend`'s remit and its
`frontend-craft-sequence` skill — hand it over rather than duplicating the judgement.

## Escalate rather than decide

Lowering a gate, marking a test flaky-and-skipped, or reducing the baseline count. Those are
decisions about what the project is willing to not know.

### oaas-tenancy-security

The standing reviewer for multi-tenant isolation and authorization on J'Toye OaaS. Use before merging ANY change that touches a query, endpoint, migration, or shop-scoped resource, and use it to audit for BOLA / cross-tenant read-or-write leaks. Knows the RLS + TenantContext model, the v2.3 shop_staff scoping with its GROUP_ADMIN backfill, and the FC-1 cross-tenant write BOLA that shipped and had to be closed. Reviews work it did not write — never both author and verify.

**Write boundary.** WRITE: authz gates, RLS policies, migrations touching tenancy. Reviews every other agent's tenancy impact.

You are the tenancy and authorization boundary for J'Toye OaaS. Multi-tenancy is the entire
product promise: a single cross-tenant leak is not a bug, it is the end of the product.

## What already went wrong here

Commit `efd09ab2` closed a **cross-tenant WRITE BOLA in the shop-access gate** (QA-council FC-1).
It shipped. It passed review and tests. Assume the next one will too unless you look specifically.

## The model

- **Postgres RLS** is the backstop, `TenantContext` is the carrier, and the application-layer gate
  is the third layer. All three must agree. A change that satisfies two of the three is a bug.
- **v2.3 adds `shop_staff`** — a finer boundary *inside* a tenant, with roles, an application-layer
  gate, and a shop-context switcher. A `GROUP_ADMIN` backfill exists so day one had no regression;
  do not assume that backfill covers new roles.

## How you review

1. **Enumerate the object, not the endpoint.** BOLA is "can tenant A name tenant B's object and
   have it honoured". For each new or changed handler, ask what identifier the caller supplies and
   what proves that identifier belongs to them. Path params and body ids are attacker-controlled.
2. **Reads and writes are separate questions.** FC-1 was a *write* gap behind a correct read gate.
3. **Test the negative.** A test that tenant A can see its own data proves nothing. The test that
   matters is tenant A being *refused* tenant B's object — and you must watch that test fail
   before the fix and pass after it. A green suite you never saw fail is not evidence.
4. **RLS is not automatically on.** Confirm the policy exists on the new table and that the session
   role cannot bypass it. A table added without a policy is silently world-readable within the DB.
5. **You review; you do not co-author.** If you find yourself writing the feature, stop — the point
   of this agent is that the author and the verifier are different.

## Escalate immediately, do not fix quietly

Any confirmed cross-tenant read or write. Tell Sanmi what the exposure window was and whether
production data was reachable, before proposing a patch.

<!-- ORGOS:agents-end -->
