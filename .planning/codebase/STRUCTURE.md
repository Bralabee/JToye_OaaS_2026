# Codebase Structure

**Analysis Date:** 2026-04-07

## Directory Layout

```
JToye_OaaS_2026/
├── core-java/              # Spring Boot backend (Port 9090)
│   ├── src/main/java/      # Source code (pkg: uk.jtoye.core)
│   ├── src/main/resources/ # Config, migrations, OpenAPI schemas
│   ├── src/test/java/      # 130+ unit and integration tests
│   ├── build.gradle.kts    # Dependencies, Spring Boot 3, Java 21
│   └── build-local/        # Build artifacts (redirected from 'build')
│
├── edge-go/                # Go API gateway (Port 8080/8089)
│   ├── cmd/edge/           # Main entry point
│   ├── internal/           # Circuit breaker, JWT, WhatsApp, Core client
│   ├── go.mod / go.sum     # Go 1.22, Gin, zap, Resilience4j equiv
│   └── (26 unit tests)
│
├── frontend/               # Next.js 16 UI (Port 3000)
│   ├── app/                # App router: dashboard, shop, auth flows
│   │   ├── dashboard/      # Admin dashboards (shops, products, orders, customers)
│   │   ├── shop/           # Customer storefront pages
│   │   ├── auth/           # Auth UI (login, callback)
│   │   └── api/auth/       # NextAuth.js route handlers
│   ├── components/         # React components (dashboard, storefront, UI lib)
│   ├── lib/                # Utilities, API client, auth helpers
│   ├── types/              # TypeScript type definitions
│   ├── package.json        # React 19, Next.js 16, NextAuth v5, Tailwind
│   └── (43 unit tests + E2E)
│
├── infra/                  # Docker Compose definitions
│   ├── docker-compose.yml  # PostgreSQL, Redis, RabbitMQ, Keycloak
│   └── keycloak/           # Keycloak realm config (jtoye-dev)
│
├── k8s/                    # Kubernetes manifests (22 resources)
│   ├── core-deployment.yaml
│   ├── edge-deployment.yaml
│   ├── frontend-deployment.yaml
│   ├── postgres-configmap.yaml
│   ├── redis-statefulset.yaml
│   ├── rabbitmq-statefulset.yaml
│   ├── ingress.yaml
│   └── hpa.yaml            # HorizontalPodAutoscaler (3-10 replicas)
│
├── docs/                   # Documentation
│   ├── guides/             # QUICK_START.md, ENVIRONMENT_SETUP.md
│   ├── config/             # CONFIGURATION.md (env vars, profiles)
│   ├── reports/            # PRODUCTION_READINESS_REPORT.md, SECURITY_AUDIT_REPORT.md
│   ├── AI_CONTEXT.md       # System architecture for Claude
│   └── DOCUMENTATION_INDEX.md
│
├── .github/                # CI/CD workflows (GitHub Actions)
│   └── workflows/          # test.yml, build.yml, deploy.yml
│
├── .planning/              # GSD codebase mapping output
│   └── codebase/           # ARCHITECTURE.md, STRUCTURE.md, etc.
│
├── scripts/                # Build and deployment scripts
│   ├── run-app.sh          # Start Spring Boot locally
│   ├── build-images.sh     # Docker multi-platform builds
│   ├── deploy.sh           # Kubernetes deployment script
│   └── smoke-test.sh       # Smoke test suite
│
├── docker-compose.full-stack.yml  # All-in-one local dev environment
├── build.gradle.kts        # Root Gradle config
├── README.md               # Project overview, quick start
├── CHANGELOG.md            # Version history and features
└── LICENSE                 # MIT
```

## Directory Purposes

**core-java/src/main/java/uk/jtoye/core/:**

Domain-driven structure. Each domain folder (shop, order, product, customer, finance, etc.) contains:
- `*Controller.java` - REST endpoint handlers with @RequestMapping, Swagger annotations
- `*Service.java` - @Service with @Transactional, business logic, caching, state machines
- `*Repository.java` - JpaRepository extensions with @Query custom methods
- `*Entity.java` - JPA entity with @Entity, @Table, @Audited for Envers
- `*Mapper.java` - MapStruct mappers for DTO conversion
- `dto/` subdirectory - Request/Response DTOs (@Valid annotations)

Common folders:
- `security/` - TenantContext, JwtTenantFilter, TenantFilter, SecurityConfig, JwtDecoder
- `config/` - CacheConfig, RateLimitConfig, OpenApiConfig, EnversConfig, CorsConfig, RabbitMQConfig, ScheduledCleanupService, BusinessMetricsService
- `exception/` - ResourceNotFoundException, InvalidStateTransitionException, ErrorResponse
- `common/` - Shared utility classes, constants
- `audit/` - AuditService for Envers interaction
- `storage/` - StorageService for S3/MinIO file operations
- `notification/` - EmailNotificationService, RabbitMQ message publishing
- `controller/` - SecurityHealthController (non-domain specific)

**core-java/src/main/resources/:**
- `application.yml` - Spring Boot config (profiles: dev, test, prod)
- `application-{profile}.yml` - Profile-specific overrides (DB URL, Redis, Keycloak issuer)
- `db/migration/` - 28 Flyway SQL migrations (V1__*.sql through V28__*.sql) for schema, RLS policies, initial data
- `logback-spring.xml` - Logging configuration

**core-java/src/test/java/:**
Mirror of main structure: test files co-located by package. Examples:
- `*ControllerTest.java` - MockMvc integration tests
- `*ServiceTest.java` - Unit tests with mocked repositories
- `*RepositoryTest.java` - @DataJpaTest with H2 in-memory DB
- `integration/` - Testcontainers-based tests marked @Tag("testcontainers")

**edge-go/internal/:**
- `core/` - HTTP client to Spring Boot API, circuit breaker, health checks
- `middleware/` - JWT validation from Keycloak JWKS
- `whatsapp/` - Webhook parser, message handling
- `main.go` - Gin router setup, middleware chain, request forwarding

**frontend/app/:**
Next.js App Router structure. Page routes are directories with `page.tsx`:
- `dashboard/` - Admin interface (shops, products, orders, customers CRUD)
  - `shops/page.tsx` - Shop list/create
  - `products/page.tsx` - Product catalog management
  - `orders/page.tsx` - Order workflow with SSE real-time updates
  - `customers/page.tsx` - Customer directory
- `shop/` - Public storefront (customer-facing, dynamic by shop slug)
  - `[slug]/page.tsx` - Shop detail, product browsing
  - `[slug]/cart/page.tsx` - Shopping cart
  - `[slug]/checkout/page.tsx` - Order placement
  - `[slug]/orders/[orderNumber]/page.tsx` - Order status tracking
- `auth/` - Authentication flows
  - `signin/page.tsx` - Keycloak login form
  - `callback/page.tsx` - OAuth callback handler
- `api/auth/[...nextauth]/` - NextAuth.js route handler, token refresh

**frontend/components/:**
- `dashboard/` - Admin UI components (sidebar, layout, data tables)
- `storefront/` - Customer UI components (product cards, cart, checkout)
- `ui/` - Reusable UI primitives (Button, Input, Modal, Badge, etc.)

**frontend/lib/:**
- `api-client.ts` - Axios instance with JWT interceptor and 401 redirect
- `public-api-client.ts` - Public API client (no auth, for storefront)
- `customer-auth.ts` - Customer authentication helpers
- `order-history.ts` - Order lookup by email
- `env-validation.ts` - Runtime env var validation
- `utils.ts` - Shared helpers (classname merging, formatting)

**infra/:**
- `docker-compose.yml` - PostgreSQL (5433), Redis (6379), RabbitMQ (5672), Keycloak (8085)
- `keycloak/realm-config.json` - jtoye-dev realm, client definitions, users (tenant-a-user, tenant-b-user)

**k8s/:**
- `core-deployment.yaml` - Spring Boot service, replicas 3-10 (HPA), liveness/readiness probes
- `postgres-configmap.yaml` - RLS policy initialization, Flyway migrations
- `ingress.yaml` - TLS termination, path-based routing (/api → core, / → frontend)
- All resources in default namespace or jtoye namespace (check KUSTOMIZATION)

## Key File Locations

**Entry Points:**
- Backend: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java` (@SpringBootApplication, main method)
- Frontend: `frontend/app/page.tsx` (root path, redirects to dashboard)
- Edge Gateway: `edge-go/cmd/edge/main.go` (Gin router initialization, all middleware)

**Configuration:**
- Spring profiles: `core-java/src/main/resources/application*.yml`
- Environment vars: `.env`, `.env.example` (root level)
- Database migrations: `core-java/src/main/resources/db/migration/V*.sql`
- Kubernetes configs: `k8s/*.yaml`
- Docker Compose: `docker-compose.full-stack.yml`

**Core Logic:**
- Order state machine: `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java`
- Multi-tenant isolation: `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java`, `JwtTenantFilter.java`
- Caching strategy: `core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java`
- Rate limiting: Edge Go `cmd/edge/main.go` (token bucket), Core `core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java`
- Audit trail: `core-java/src/main/java/uk/jtoye/core/audit/AuditService.java`, Hibernate Envers config

**Testing:**
- Backend unit tests: `core-java/src/test/java/uk/jtoye/core/` (mirrors main structure)
- Backend integration tests: `core-java/src/test/java/uk/jtoye/core/integration/` (marked @Tag("testcontainers"))
- Frontend unit tests: `frontend/**/__tests__/` (Jest with jsdom)
- Frontend E2E tests: `frontend/e2e/` (Playwright, run against deployed app)

## Naming Conventions

**Files:**
- Entities: PascalCase, no suffix (e.g., `Shop.java`, `Order.java`)
- Controllers: PascalCase + "Controller" (e.g., `ShopController.java`)
- Services: PascalCase + "Service" (e.g., `ShopService.java`)
- Repositories: PascalCase + "Repository" (e.g., `ShopRepository.java`)
- Mappers: PascalCase + "Mapper" (e.g., `ShopMapper.java`)
- DTOs: PascalCase + "Dto" or "Request"/"Response" (e.g., `ShopDto.java`, `CreateShopRequest.java`)
- Test files: Test class name + "Test" (e.g., `ShopServiceTest.java`, `ShopControllerTest.java`)
- SQL migrations: `V{number}__{description}.sql` (e.g., `V1__initial_schema.sql`)

**Java Packages:**
- Domain-first: `uk.jtoye.core.{domain}.{sublayer}` (e.g., `uk.jtoye.core.shop`, `uk.jtoye.core.order.dto`)
- Cross-domain: `uk.jtoye.core.{concern}` (e.g., `uk.jtoye.core.security`, `uk.jtoye.core.config`)

**TypeScript/JavaScript:**
- Pages: `page.tsx` (Next.js convention, no prefixes)
- Components: PascalCase + `.tsx` (e.g., `ShopCard.tsx`, `ProductTable.tsx`)
- Utilities/Hooks: camelCase (e.g., `api-client.ts`, `useCart.ts`)
- Tests: `*.test.ts` or `*.spec.ts` (Jest convention)

**Directories:**
- Domains: plural lowercase (e.g., `shops/`, `products/`, `orders/`)
- Dynamic routes: `[paramName]` (Next.js and Spring param conventions)
- API routes: `/api/{resource}/{action}`

## Where to Add New Code

**New REST Endpoint:**
1. Create domain folder: `core-java/src/main/java/uk/jtoye/core/{newdomain}/`
2. Add Entity: `{Domain}.java` with @Entity, @Table, @Audited
3. Add Repository: `{Domain}Repository.java` extends JpaRepository, add @Query methods if needed
4. Add Service: `{Domain}Service.java` with @Service, @Transactional, business logic, caching
5. Add Mapper: `{Domain}Mapper.java` extends MapStruct Mapper
6. Add DTO classes: `{newdomain}/dto/{Domain}Dto.java`, `Create{Domain}Request.java`
7. Add Controller: `{Domain}Controller.java` with @RestController, @RequestMapping, endpoint methods
8. Add tests: `core-java/src/test/java/uk/jtoye/core/{newdomain}/` mirror of main
9. Add database migration: `core-java/src/main/resources/db/migration/V{N}__{description}.sql` (include RLS policy)
10. Document in OpenAPI: @Tag, @Operation, @ApiResponse on controller

**New Frontend Page:**
1. Create directory: `frontend/app/{feature}/` or nest under existing feature
2. Add `page.tsx` with export default Page component
3. Create dynamic route as `[paramName]` if needed
4. Add components: `frontend/components/{feature}/` for feature-specific UI
5. Add utilities: `frontend/lib/{feature}-*.ts` for API calls, business logic
6. Add tests: `frontend/app/{feature}/__tests__/` or `components/{feature}/__tests__/`
7. Update layout if needed: `layout.tsx` for nested routes with shared structure

**New Service/Utility:**
- Shared backend logic: Add to `core-java/src/main/java/uk/jtoye/core/common/` or domain-specific `service/`
- Shared frontend logic: Add to `frontend/lib/` as utility functions or hooks
- Do not create new top-level packages; nest under domains or config

**Database Schema Change:**
1. Create migration: `core-java/src/main/resources/db/migration/V{N}__{description}.sql`
2. Include table creation, columns, indexes
3. Add RLS policies for multi-tenant tables: `CREATE POLICY tenant_isolation ON {table} USING (tenant_id = current_setting('app.current_tenant_id')::uuid)`
4. If audited entity: Envers will auto-create `{table}_aud` and trigger functions
5. Migration runs automatically on startup via Flyway

**Configuration Change:**
- Spring property: Update `core-java/src/main/resources/application.yml` or environment variable
- Frontend env: Update `frontend/.env.local` (development) or CI/CD secrets for production
- Go edge config: Add to `edge-go/cmd/edge/main.go` getEnv() calls
- Kubernetes: Update ConfigMap in `k8s/` and redeploy

## Special Directories

**build/ (build-local/):**
- Purpose: Gradle build output directory
- Generated: Yes (redirected from 'build' to 'build-local' to avoid permission issues)
- Committed: No (.gitignore excludes)

**node_modules/:**
- Purpose: npm package dependencies
- Generated: Yes (npm install)
- Committed: No (package-lock.json locked, .gitignore excludes)

**.next/:**
- Purpose: Next.js build output and cache
- Generated: Yes (npm run build or dev mode)
- Committed: No (.gitignore excludes)

**coverage/:**
- Purpose: Test coverage reports (Jest, Go)
- Generated: Yes (npm run test:coverage, go test -cover)
- Committed: No (.gitignore excludes)

**logs/:**
- Purpose: Application runtime logs
- Generated: Yes (Spring Boot, Go edge logging)
- Committed: No (.gitignore excludes)

**.gradle/, .gradle-docker/, .gradle-local/:**
- Purpose: Gradle cache and wrapper directories
- Generated: Yes (Gradle daemon, dependency caches)
- Committed: Partial (gradlew executable committed, caches excluded)

**backups/:**
- Purpose: Database backups, snapshots
- Generated: Yes (manual or scheduled backup scripts)
- Committed: No

---

*Structure analysis: 2026-04-07*
