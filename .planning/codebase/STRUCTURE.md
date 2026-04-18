# Codebase Structure

**Analysis Date:** 2026-04-18

## Directory Layout

```
JToye_OaaS_2026/
├── core-java/                     # Spring Boot backend (port 9090)
│   ├── src/main/java/uk/jtoye/core/
│   │   ├── ai/                    # LLM integration (Ollama image analysis)
│   │   ├── audit/                 # AuditService, Envers helpers
│   │   ├── common/                # Shared utilities, constants
│   │   ├── config/                # CacheConfig, CorsConfig, EnversConfig,
│   │   │                          #   OpenApiConfig, RateLimitConfig,
│   │   │                          #   RabbitMQConfig, ScheduledCleanupService,
│   │   │                          #   BusinessMetricsService,
│   │   │                          #   TenantAwareCacheKeyGenerator,
│   │   │                          #   TenantCacheEvictor
│   │   ├── controller/            # SecurityHealthController (cross-cutting)
│   │   ├── customer/              # Customer domain
│   │   ├── exception/             # GlobalExceptionHandler, ErrorResponse,
│   │   │                          #   ResourceNotFoundException, InvalidStateTransitionException
│   │   ├── finance/               # FinancialTransaction domain, VAT
│   │   ├── gdpr/                  # Data export/deletion requests
│   │   ├── notification/          # Email service, RabbitMQ publishers
│   │   ├── order/                 # Order domain + Spring State Machine + SSE
│   │   ├── payment/               # Stripe integration + PaymentEventOutbox
│   │   │                          #   (PaymentController, PaymentService,
│   │   │                          #    PaymentEventOutbox, PaymentEventOutboxFlusher,
│   │   │                          #    PaymentEventPublisher, PaymentEventAuditListener,
│   │   │                          #    StripeProperties)
│   │   ├── product/               # Product domain, image gallery
│   │   ├── review/                # Customer reviews
│   │   ├── security/              # TenantContext, JwtTenantFilter, SecurityConfig,
│   │   │                          #   TenantSetLocalAspect, TenantContextCleanupFilter,
│   │   │                          #   RateLimitInterceptor
│   │   ├── shop/                  # Shop domain (tenant-owned storefront)
│   │   ├── storage/               # S3/MinIO file operations
│   │   ├── storefront/            # Public (unauth) endpoints — v2.1 extended
│   │   │                          #   PublicStorefrontController (promotions,
│   │   │                          #   announcements, guest orders, tracking)
│   │   │                          #   PublicStorefrontService + dto/
│   │   ├── sync/                  # SyncController, batch sync from edge devices
│   │   ├── tenant/                # Tenant provisioning (dev profile)
│   │   ├── websocket/             # STOMP/WS — v2.1 relay-capable
│   │   │                          #   WebSocketConfig (in-memory | relay modes)
│   │   │                          #   TenantChannelInterceptor (P1 hardened)
│   │   │                          #   JwtHandshakeInterceptor
│   │   │                          #   (JWT in STOMP CONNECT headers)
│   │   └── CoreApplication.java   # @SpringBootApplication entry point
│   ├── src/main/resources/
│   │   ├── application.yml        # Base config
│   │   ├── application-{dev,test,staging,prod}.yml
│   │   ├── db/migration/          # 33 Flyway migrations (V1 … V33)
│   │   │                          # V33__fix_rls_policies.sql (latest, v2.1)
│   │   └── logback-spring.xml
│   ├── src/test/java/             # 341 passing tests (unit + integration)
│   ├── build.gradle.kts           # Spring Boot 3.4.2, JDK 21
│   └── build-local/               # Gradle output (redirected from build/)
│
├── edge-go/                       # Go 1.22 API gateway (port 8080)
│   ├── cmd/edge/main.go           # Gin router, middleware wiring
│   ├── internal/
│   │   ├── core/                  # HTTP client + sony/gobreaker circuit breaker
│   │   ├── middleware/            # JWT validation (golang-jwt/jwt v5)
│   │   └── whatsapp/              # Webhook parser
│   ├── go.mod / go.sum            # Gin, zap, gobreaker
│   ├── Dockerfile                 # multi-stage, scratch runtime
│   └── (57 passing tests — up from 21 in v2.0; P1 audit hardening)
│
├── frontend/                      # Next.js 16 UI (port 3000, dev 3100)
│   ├── app/                       # App Router
│   │   ├── dashboard/             # Admin UI (shops, products, orders, customers,
│   │   │                          #   promotions, announcements, kitchen, finance)
│   │   ├── shop/                  # Customer storefront
│   │   │   ├── [slug]/page.tsx    # Shop detail + product browse
│   │   │   ├── [slug]/cart/       # Cart page (v2.1 — standalone route)
│   │   │   │   └── page.tsx
│   │   │   ├── [slug]/checkout/   # Stripe checkout
│   │   │   ├── [slug]/orders/     # Order tracking by number
│   │   │   ├── orders/            # v2.1 — customer order history by email
│   │   │   │   └── page.tsx       #       (previously 404'd)
│   │   │   ├── auth/              # Customer auth (email + magic link)
│   │   │   ├── error.tsx          # v2.1 P1 — route error boundary
│   │   │   └── layout.tsx
│   │   ├── auth/                  # Admin auth (Keycloak OIDC via NextAuth)
│   │   ├── track/                 # Public order tracking landing
│   │   ├── api/auth/              # NextAuth v5 route handlers
│   │   ├── error.tsx              # v2.1 P1 — root error boundary
│   │   └── page.tsx               # Root redirect (auth-aware)
│   ├── components/
│   │   ├── dashboard/             # Admin UI: sidebar, shell, data tables
│   │   ├── storefront/            # cart-provider, product-detail-modal,
│   │   │                          #   storefront-nav, require-customer-auth
│   │   ├── ui/                    # Radix-based primitives
│   │   └── providers.tsx
│   ├── lib/
│   │   ├── api-client.ts          # Axios + JWT interceptor, 401 redirect
│   │   ├── public-api-client.ts   # Unauthenticated client (storefront)
│   │   ├── customer-auth.ts       # Storefront customer auth helpers
│   │   ├── order-history.ts       # Order lookup by email
│   │   ├── env-validation.ts      # Runtime env var validation
│   │   └── utils.ts
│   ├── hooks/                     # React hooks (use-toast, etc.)
│   ├── types/                     # TypeScript type definitions
│   ├── e2e/                       # Playwright end-to-end suites
│   │   ├── kitchen-flow.spec.ts   # Kitchen display workflow
│   │   ├── storefront-flows.spec.ts  # v2.1 — browse→cart→checkout flows
│   │   └── stomp-relay.spec.ts    # v2.1 — cross-replica broadcast verification
│   ├── __tests__/                 # Jest unit tests (76 passing)
│   ├── auth.ts                    # NextAuth v5 config
│   ├── middleware.ts              # Route-level auth gating
│   ├── next.config.mjs            # standalone output, image remotePatterns
│   ├── instrumentation.ts         # Next.js instrumentation hook
│   ├── jest.config.js / jest.setup.js
│   ├── playwright.config.ts
│   ├── package.json               # React 19, Next 16.2.2, NextAuth v5, Tailwind
│   └── tsconfig.json
│
├── infra/                         # Local + dev infrastructure
│   ├── docker-compose.yml         # PostgreSQL, Redis, RabbitMQ, Keycloak
│   ├── docker-compose.hostnet.yml # Host-network variant for Linux
│   ├── keycloak/                  # Realm config (jtoye-dev)
│   ├── rabbitmq/                  # v2.1 new
│   │   └── enabled_plugins        # [management, prometheus, stomp] — STOMP is v2.1
│   ├── monitoring/                # v2.1 new observability tier
│   │   ├── docker-compose.monitoring.yml  # Prometheus + Alertmanager + Grafana
│   │   ├── prometheus/
│   │   │   ├── prometheus.yml     # Scrape targets (Core, Edge, RabbitMQ, Redis)
│   │   │   └── alerts.yml         # 14 alert rules incl. StompBrokerLag,
│   │   │                          #   ServiceDown, RedisDown, HighErrorRate
│   │   ├── alertmanager/          # v2.1 new
│   │   │   ├── alertmanager.yml.tmpl   # Env-var templated receiver config
│   │   │   └── entrypoint.sh      # Renders template → alertmanager.yml on start
│   │   ├── grafana/
│   │   │   ├── provisioning/      # Datasources + dashboard providers
│   │   │   └── dashboards/
│   │   │       └── stomp-dashboard.json  # v2.1 — STOMP broker metrics
│   │   ├── scripts/
│   │   │   └── smoke-test-alertmanager.sh  # v2.1 — verifies alert delivery
│   │   └── README.md              # Monitoring stack runbook
│   ├── db/                        # DB init helpers
│   ├── load-testing/              # k6 scripts (private / gitignored)
│   └── secrets/                   # Local secret material (gitignored)
│
├── k8s/                           # Kubernetes manifests
│   ├── core-deployment.yaml       # Replicas 3-10 via HPA
│   ├── edge-deployment.yaml
│   ├── frontend-deployment.yaml
│   ├── postgres-configmap.yaml    # RLS policy init
│   ├── redis-statefulset.yaml
│   ├── rabbitmq-statefulset.yaml
│   ├── ingress.yaml               # TLS, path routing (/api → core, / → frontend)
│   └── hpa.yaml                   # HorizontalPodAutoscaler
│
├── docs/                          # Documentation
│   ├── guides/                    # QUICK_START.md, ENVIRONMENT_SETUP.md
│   ├── config/                    # CONFIGURATION.md (env vars, profiles)
│   ├── reports/                   # Production readiness, security audit
│   ├── runbooks/                  # Alert response runbooks (mostly stubs; ServiceDown filled)
│   ├── AI_CONTEXT.md              # System architecture context
│   └── DOCUMENTATION_INDEX.md
│
├── .github/                       # CI/CD
│   └── workflows/
│       ├── ci-cd.yaml             # Build + test pipeline
│       └── gitleaks.yml           # v2.1 new — secret scanning on every push/PR
│
├── .planning/                     # GSD artefacts
│   ├── codebase/                  # Codebase maps (this file)
│   ├── milestones/                # v2.1 — shipped milestone archives
│   │   ├── v2.1-ROADMAP.md
│   │   ├── v2.1-REQUIREMENTS.md
│   │   ├── v2.1-MILESTONE-AUDIT.md
│   │   └── v2.1-phases/           # Archived phase plans
│   │       ├── 09-repository-secrets-alerting/
│   │       ├── 10-storefront-marketing-render-missing-customer-routes/
│   │       └── 11-stomp-broker-relay-for-horizontal-scale/
│   ├── phases/                    # v2.1 — EMPTY (all phases moved to milestones/v2.1-phases/)
│   ├── housekeeping/
│   ├── quick/                     # /gsd-quick task archives
│   ├── research/
│   ├── state-of-codebase/
│   ├── DEEP-AUDIT-2026-04-16.md
│   ├── MILESTONES.md              # Chronological shipped-milestone log
│   ├── PROJECT.md
│   ├── ROADMAP.md
│   └── STATE.md
│
├── scripts/                       # Build, deploy, smoke tests
│   ├── run-app.sh                 # Start Spring Boot locally
│   ├── start-dev.sh / stop-dev.sh # Dev stack lifecycle
│   ├── build-images.sh            # Docker multi-platform builds
│   ├── deploy.sh                  # Kubernetes deployment
│   ├── smoke-test.sh              # Generic smoke tests
│   ├── smoke-test-stomp-relay.sh  # v2.1 — verifies 2 STOMP connections from scaled replicas
│   ├── pre-commit-gitleaks.sh     # v2.1 — local secret-scan hook
│   ├── verify-env.sh              # Environment sanity check
│   ├── fix-bridge-network.sh
│   └── fix-testcontainers-docker.sh
│
├── backups/                       # Local DB snapshots (gitignored)
├── build/                         # Gradle-generated (gitignored)
├── logs/                          # Runtime logs (gitignored)
├── docker-compose.full-stack.yml  # All-in-one local dev environment
├── build.gradle.kts               # Root Gradle config
├── settings.gradle.kts
├── .env / .env.example            # Environment configuration
├── .gitleaks.toml                 # v2.1 new — gitleaks allowlist + rules
├── CLAUDE.md                      # Claude agent project instructions
├── HANDOFF.md                     # Session handoff notes
├── README.md
├── CHANGELOG.md
└── LICENSE
```

## Directory Purposes

**`core-java/src/main/java/uk/jtoye/core/`:**
Domain-driven layout. Each domain folder (shop, order, product, customer, review, finance, payment, gdpr, storefront, sync, tenant) contains:
- `*Controller.java` — REST endpoints with `@RequestMapping`, Swagger annotations
- `*Service.java` — `@Service`/`@Transactional` business logic, caching, state machines
- `*Repository.java` — `JpaRepository` extensions with `@Query`
- `*Entity.java` — JPA entities, often `@Audited` via Envers
- `*Mapper.java` — MapStruct DTO mappers
- `dto/` — request/response DTOs with Jakarta Validation

Cross-cutting packages:
- `security/` — TenantContext, JwtTenantFilter, SecurityConfig, TenantSetLocalAspect, TenantContextCleanupFilter, RateLimitInterceptor
- `config/` — CacheConfig, CorsConfig, EnversConfig, OpenApiConfig, RateLimitConfig, RabbitMQConfig, TenantAwareCacheKeyGenerator, TenantCacheEvictor, BusinessMetricsService, ScheduledCleanupService, DatabaseConfigurationValidator
- `exception/` — GlobalExceptionHandler, ErrorResponse, domain exceptions
- `websocket/` (v2.1) — WebSocketConfig, TenantChannelInterceptor, JwtHandshakeInterceptor
- `audit/`, `storage/`, `notification/`, `ai/`, `controller/` (SecurityHealthController)

**`core-java/src/main/resources/`:**
- `application.yml` — base config (active profile via `SPRING_PROFILES_ACTIVE`)
- `application-{dev,test,staging,prod}.yml`
- `db/migration/V{n}__*.sql` — 33 Flyway migrations as of v2.1; latest is `V33__fix_rls_policies.sql`

**`core-java/src/test/java/`:**
Mirror of main source tree. Conventions:
- `*ControllerTest.java` — MockMvc integration tests
- `*ServiceTest.java` — unit tests with mocked repositories
- `*RepositoryTest.java` — `@DataJpaTest` with H2
- `integration/` — Testcontainers, tagged `@Tag("testcontainers")`

**`edge-go/internal/`:**
- `core/` — HTTP client, circuit breaker (`sony/gobreaker`), health check plumbing
- `middleware/` — JWT validation against Keycloak JWKS
- `whatsapp/` — WhatsApp webhook parser/handler
- Tests co-located (`*_test.go`) — 57 passing

**`frontend/app/`:**
Next.js App Router. Each directory with `page.tsx` is a route; `[name]` segments are dynamic.
- `dashboard/` — admin UI (B2B)
- `shop/` — customer storefront (B2C)
  - `[slug]/page.tsx` — shop detail + browse
  - `[slug]/cart/page.tsx` — **v2.1 new** standalone cart
  - `[slug]/checkout/page.tsx` — Stripe checkout
  - `[slug]/orders/[orderNumber]/page.tsx` — order tracking
  - `orders/page.tsx` — **v2.1 new** customer-wide order history by email
  - `auth/` — customer auth flow
  - `error.tsx` — **v2.1 new** route-scoped error boundary
- `auth/` — admin Keycloak/OIDC flow
- `track/` — public order tracking landing
- `api/auth/[...nextauth]/` — NextAuth v5 handler
- `error.tsx` — **v2.1 new** root error boundary

**`frontend/components/`:**
- `dashboard/` — admin shell, sidebar, data tables
- `storefront/` — cart provider, product-detail modal, nav, require-customer-auth wrapper
- `ui/` — Radix + Tailwind primitives (Button, Input, Modal, etc.)

**`frontend/lib/`:**
- `api-client.ts` — authenticated axios, JWT interceptor, 401→signin
- `public-api-client.ts` — unauthenticated client for storefront
- `customer-auth.ts`, `order-history.ts`, `env-validation.ts`, `utils.ts`

**`frontend/e2e/`:**
- `kitchen-flow.spec.ts` — kitchen display happy path
- `storefront-flows.spec.ts` — **v2.1 extended** browse→cart→Stripe checkout
- `stomp-relay.spec.ts` — **v2.1 new** cross-replica broadcast verification (requires `--scale core-java=2`)

**`infra/monitoring/`** (v2.1 new observability tier):
- `docker-compose.monitoring.yml` — Prometheus, Alertmanager, Grafana services
- `prometheus/prometheus.yml` — scrape config
- `prometheus/alerts.yml` — 14 alert rules (ServiceDown, StompBrokerLag, RedisDown, HighErrorRate, etc.)
- `alertmanager/alertmanager.yml.tmpl` — receiver config template (SMTP vars)
- `alertmanager/entrypoint.sh` — renders template on container start
- `grafana/provisioning/` — auto-load datasources + dashboard providers
- `grafana/dashboards/stomp-dashboard.json` — STOMP broker metrics visualisation
- `scripts/smoke-test-alertmanager.sh` — verifies alert fire → receiver path

**`infra/rabbitmq/enabled_plugins`** (v2.1 new):
Enables `rabbitmq_stomp` (port 61613) alongside management + prometheus, so Core's `StompBrokerRelay` can connect.

**`k8s/`:**
- `core-deployment.yaml` — Spring Boot with liveness/readiness, replicas via HPA
- `postgres-configmap.yaml` — RLS policy bootstrap
- `ingress.yaml` — TLS termination, path-based routing

**`.planning/`:**
GSD workflow state. Note the v2.1 reorganisation: `phases/` is now **empty** — all shipped phase folders moved to `milestones/v2.1-phases/`. New phases will populate `phases/` again when v2.2 work begins.

## Key File Locations

**Entry Points:**
- Backend: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Frontend: `frontend/app/page.tsx`
- Edge Gateway: `edge-go/cmd/edge/main.go`

**Configuration:**
- Spring profiles: `core-java/src/main/resources/application*.yml`
- Environment vars: `.env`, `.env.example` (root); `frontend/.env.local.example`
- DB migrations: `core-java/src/main/resources/db/migration/V*.sql` (through V33)
- Kubernetes: `k8s/*.yaml`
- Docker Compose: `docker-compose.full-stack.yml`, `infra/docker-compose.yml`, `infra/monitoring/docker-compose.monitoring.yml`

**Core Logic:**
- Order state machine: `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java`
- Multi-tenant isolation: `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java`, `JwtTenantFilter.java`, `TenantSetLocalAspect.java`
- WebSocket/STOMP: `core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java`, `TenantChannelInterceptor.java`
- Public storefront: `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java`, `PublicStorefrontService.java`
- Stripe outbox: `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java`, `PaymentEventOutboxFlusher.java`
- Caching: `core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java`, `TenantAwareCacheKeyGenerator.java`, `TenantCacheEvictor.java`
- Rate limiting: `edge-go/cmd/edge/main.go` (token bucket), `core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java` (Bucket4j)
- Audit trail: `core-java/src/main/java/uk/jtoye/core/audit/AuditService.java` + Envers

**Testing:**
- Backend: `core-java/src/test/java/uk/jtoye/core/` (341 tests)
- Frontend unit: `frontend/__tests__/`, `frontend/components/**/__tests__/`, `frontend/lib/__tests__/` (76 Jest tests)
- Frontend E2E: `frontend/e2e/` (Playwright — 3 suites)
- Go: colocated `*_test.go` (57 tests)

**Observability & Ops (v2.1 new):**
- Alertmanager: `infra/monitoring/alertmanager/`
- Prometheus alerts: `infra/monitoring/prometheus/alerts.yml`
- Grafana STOMP dashboard: `infra/monitoring/grafana/dashboards/stomp-dashboard.json`
- Alertmanager smoke test: `infra/monitoring/scripts/smoke-test-alertmanager.sh`
- STOMP relay smoke test: `scripts/smoke-test-stomp-relay.sh`
- Secret scanning: `.gitleaks.toml`, `scripts/pre-commit-gitleaks.sh`, `.github/workflows/gitleaks.yml`

## Naming Conventions

**Java Files:**
- Entities: PascalCase, no suffix (`Shop.java`, `Order.java`)
- Controllers: `<Entity>Controller.java`
- Services: `<Entity>Service.java`
- Repositories: `<Entity>Repository.java`
- Mappers: `<Entity>Mapper.java`
- DTOs: `<Entity>Dto.java`, `Create<Entity>Request.java`, `<Entity>Response.java`
- Tests: `<Class>Test.java`
- SQL migrations: `V{number}__{description}.sql`

**Java Packages:**
- Domain-first: `uk.jtoye.core.{domain}.{sublayer}` — e.g. `uk.jtoye.core.shop`, `uk.jtoye.core.order.dto`
- Cross-cutting: `uk.jtoye.core.{concern}` — e.g. `uk.jtoye.core.security`, `uk.jtoye.core.config`, `uk.jtoye.core.websocket`

**TypeScript/JavaScript:**
- Pages: `page.tsx` (Next.js convention)
- Components: PascalCase `.tsx` (e.g. `CartProvider.tsx`, `SafeImage.tsx`)
- Utilities/hooks: kebab-case `.ts` (e.g. `api-client.ts`, `use-toast.ts`)
- Tests: `*.test.ts(x)` or `*.spec.ts(x)`; E2E lives in `frontend/e2e/*.spec.ts`

**Go:**
- Packages: `internal/<domain>/`
- Files: snake_case (`jwt_middleware.go`), tests `*_test.go`
- Exported identifiers: PascalCase

**Directories:**
- Domains: plural lowercase (`shops/`, `products/`, `orders/`)
- Dynamic routes: `[paramName]` (Next.js)
- API routes: `/api/v1/{resource}` (authenticated), `/public/{resource}` (unauthenticated)

## Where to Add New Code

**New REST Endpoint (authenticated):**
1. Create domain folder: `core-java/src/main/java/uk/jtoye/core/{newdomain}/`
2. Add Entity (`@Entity`, `@Table`, `@Audited` if auditable)
3. Add Repository (`JpaRepository` extension, `@Query` where needed)
4. Add Service (`@Service`, `@Transactional`, `@Cacheable` via `tenantAwareCacheKeyGenerator`)
5. Add Mapper (MapStruct `@Mapper(componentModel="spring")`)
6. Add DTOs under `{newdomain}/dto/` with Jakarta Validation
7. Add Controller under `@RequestMapping("/api/v1/{resource}")` with Swagger annotations
8. Add mirrored tests in `core-java/src/test/java/uk/jtoye/core/{newdomain}/`
9. Add Flyway migration `V{next}__{description}.sql` including RLS policy for new tenant-scoped tables
10. Document with `@Tag`, `@Operation`, `@ApiResponse` for OpenAPI

**New Public (Unauthenticated) Endpoint:**
1. Add method to `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` under `/public/**`
2. Implement in `PublicStorefrontService` — explicitly guard against leaking non-published content
3. Ensure `SecurityConfig` permits the path (it already opens `/public/**`)
4. Add Playwright coverage in `frontend/e2e/storefront-flows.spec.ts`

**New WebSocket Topic:**
1. Choose destination format `/topic/{feature}/{tenantId}/{subScope}` — the tenant UUID **must** be at segment index 3 so `TenantChannelInterceptor.validateSubscription` accepts it
2. Publish via `SimpMessagingTemplate.convertAndSend(...)` from a service; `OrderEventPublisher` is the reference implementation
3. If running with `stomp.broker.mode=relay`, no extra work — the relay forwards automatically
4. Add unit + Playwright coverage; use `frontend/e2e/stomp-relay.spec.ts` as template for cross-replica assertions

**New Frontend Page:**
1. Create directory under `frontend/app/{feature}/` (or nested); add `page.tsx`
2. Use `[paramName]` for dynamic segments
3. Feature-specific components → `frontend/components/{feature}/`
4. Shared API helpers → `frontend/lib/{feature}-*.ts` (auth-required uses `api-client.ts`, public uses `public-api-client.ts`)
5. Add unit tests under `__tests__/`; E2E under `frontend/e2e/`
6. Add error boundary (`error.tsx`) if the route handles user-visible failure paths

**New Service/Utility:**
- Shared backend logic: `core-java/src/main/java/uk/jtoye/core/common/` or domain-specific `service/`
- Shared frontend logic: `frontend/lib/` (utilities) or `frontend/hooks/` (hooks)
- Do not introduce new top-level Java packages; nest under an existing domain or `config/`

**Database Schema Change:**
1. New migration: `core-java/src/main/resources/db/migration/V{next}__{description}.sql` (next is V34 as of v2.1)
2. For tenant-scoped tables include:
   ```sql
   ALTER TABLE {table} ENABLE ROW LEVEL SECURITY;
   CREATE POLICY tenant_isolation ON {table}
     USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
   ```
3. Audited entity → Envers creates `{table}_aud` automatically
4. Migration runs on startup via Flyway — no manual execution

**New Prometheus Alert:**
1. Add rule group to `infra/monitoring/prometheus/alerts.yml`
2. Confirm severity label (`critical` or `warning`) — Alertmanager routes on this
3. Add runbook entry in `docs/runbooks/alerts.md`
4. Extend `infra/monitoring/scripts/smoke-test-alertmanager.sh` if end-to-end validation is needed

**Configuration Change:**
- Spring property: edit `core-java/src/main/resources/application*.yml` or env var
- Frontend env: `frontend/.env.local` (dev) or CI/CD secret (prod); `frontend/.env.local.example` documents shape
- Go edge: env var read in `edge-go/cmd/edge/main.go`
- Kubernetes: update ConfigMap in `k8s/` and redeploy

## Special Directories

**`build/`, `build-local/`:**
- Gradle output (redirected from `build/` to `build-local/` to dodge permission issues in some dev environments)
- Generated: Yes | Committed: No (gitignored)

**`node_modules/`, `.next/`:**
- npm packages and Next.js build cache
- Generated: Yes | Committed: No

**`coverage/`, `test-results/`, `playwright-report/`:**
- Test coverage and report outputs
- Generated: Yes | Committed: No

**`logs/`:**
- Runtime logs (Spring Boot, Edge Go)
- Generated: Yes | Committed: No

**`.gradle/`, `.gradle-docker/`, `.gradle-local/`:**
- Gradle wrapper + caches
- Committed: Partial (`gradlew` executable, caches gitignored)

**`backups/`:**
- Local DB snapshots
- Generated: Yes | Committed: No

**`infra/secrets/`, `infra/load-testing/`, `infra/backups/`:**
- Private local material; directory-level perms `drwx------`
- Committed: No

**`.planning/phases/`:**
- Currently **empty** after v2.1 archive (all content in `.planning/milestones/v2.1-phases/`)
- Future phase plans will repopulate this directory for v2.2

**`.planning/milestones/v2.1-phases/`:**
- Read-only archive of shipped phases (09, 10, 11) — SUMMARY.md, VERIFICATION.md, etc.
- Referenced from `.planning/MILESTONES.md` chronological log

---

*Structure analysis: 2026-04-18*
