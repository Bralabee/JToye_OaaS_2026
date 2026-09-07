# Codebase Structure

**Analysis Date:** 2026-09-03

## Directory Layout

```
JToye_OaaS_2026/
├── core-java/                      # Spring Boot 3.5.16 backend (port 9090), JDK 25 / Gradle 9.7.1
│   ├── src/main/java/uk/jtoye/core/
│   │   ├── ai/                     # Ollama image-analysis integration (2 files)
│   │   ├── audit/                  # AuditService, Envers helpers (4 files)
│   │   ├── common/                 # GlobalExceptionHandler + shared utilities (3 files)
│   │   ├── config/                 # CacheConfig, CorsConfig, RabbitMQConfig, RateLimitConfig,
│   │   │                           #   BusinessMetricsService, TenantAwareCacheKeyGenerator, etc. (19 files)
│   │   ├── controller/             # SecurityHealthController (cross-cutting, 1 file)
│   │   ├── customer/                # CustomerController + CRUD (6 files)
│   │   ├── dev/                     # Development-only helpers, disabled in prod (2 files)
│   │   ├── exception/                # Custom exception hierarchy (17 files)
│   │   ├── finance/                  # FinancialTransactionController, VAT ledger (8 files)
│   │   ├── gdpr/                     # DsarIntakeController/Verification, erasure records (14 files)
│   │   ├── geo/                      # Postcode centroid search, distance (7 files)
│   │   ├── media/                    # CoW media_asset model + async upload pipeline (26 files)
│   │   ├── notification/             # Email/webhook consent, PublicUnsubscribeController (4 files)
│   │   ├── onboarding/               # Vendor onboarding state machine + admin queue (23 files)
│   │   ├── order/                    # Order state machine, SSE, allergen snapshot (28 files)
│   │   ├── payment/                  # PaymentController, Stripe, refunds, event outbox (~13 files)
│   │   ├── product/                  # ProductController, full-text search (12 files)
│   │   ├── review/                   # Reviews (3 files)
│   │   ├── security/                 # JWT filters, TenantContext, RLS GUC pinning (18 files)
│   │   │   └── access/               #   shop_staff vendor-scoped access layer (16 files)
│   │   ├── shop/                     # ShopController, Announcement, Promotion (18 files)
│   │   ├── storage/                  # S3/MinIO abstraction (3 files)
│   │   ├── storefront/               # PublicStorefrontController — unauthenticated reads (3 files)
│   │   ├── sync/                     # SyncController — batch sync from edge (3 files)
│   │   ├── tenant/                   # TenantAdminController, DevTenantController (10 files)
│   │   ├── webhook/                  # Outbound webhook subscriptions + delivery (18 files)
│   │   ├── websocket/                # STOMP config, StompDestinations, tenant interceptor (3 files)
│   │   └── CoreApplication.java      # Entry point
│   ├── src/main/resources/
│   │   ├── application*.yml          # base + dev/test/staging/prod profiles
│   │   ├── db/migration/             # Flyway V1..V66 (66 files)
│   │   ├── geo/                      # Postcode dataset (gzipped, OGL v3) + SOURCE.md
│   │   └── dev/                      # Dev-profile seed data
│   ├── src/test/java/uk/jtoye/core/  # Mirrors main/ package-for-package; unit + Testcontainers integration tests
│   └── build.gradle.kts, build-local/ (live build output — build/ is stale, do not read from it)
│
├── edge-go/                        # Go 1.27 / Gin edge gateway (port 8080)
│   ├── cmd/edge/                   # main.go (entry), handlers.go, metrics.go, docs.go, types.go
│   ├── internal/
│   │   ├── auth/                   # Keycloak service-token provider (WhatsApp intake)
│   │   ├── core/                   # Core API client + circuit breaker (client.go, contract.go, orders.go)
│   │   ├── middleware/             # JWT validation middleware
│   │   └── whatsapp/               # Inbound webhook HMAC verification + parser
│   ├── docs/                       # swaggo-generated OpenAPI (docs.go, swagger.json/yaml)
│   └── Dockerfile                  # multi-stage, scratch-based runtime
│
├── frontend/                       # Next.js 16.3.2 / React 19 (App Router)
│   ├── app/
│   │   ├── page.tsx                # Public marketing landing page
│   │   ├── layout.tsx              # Root layout
│   │   ├── auth/signin/            # Vendor sign-in
│   │   ├── api/                    # Route handlers: auth/, customer-auth/, customer-orders/,
│   │   │                           #   health/, vendor-auth/ (NextAuth + session bridging)
│   │   ├── dashboard/               # Vendor admin surface (authenticated)
│   │   │   ├── customers/ finance/ kitchen/ marketing/ media/ onboarding/
│   │   │   │   orders/ payments/ products/ shops/ staff/ webhooks/
│   │   ├── shop/                    # Public + customer storefront
│   │   │   ├── [slug]/              #   shop detail, cart, checkout (dynamic route)
│   │   │   ├── auth/ signin/         #   customer auth
│   │   │   └── orders/               #   customer order tracking
│   │   ├── track/                   # Order tracking (public link)
│   │   ├── unsubscribe/             # Public unsubscribe landing
│   │   ├── legal/                   # accessibility/ cookies/ privacy/ retention
│   │   ├── business-model-guide/, competitive/, for-operators/  # Marketing pages
│   │   └── fonts/                   # next/font local assets
│   ├── components/
│   │   ├── dashboard/ (6)  layout/ (1)  legal/ (3)  marketing/ (10)
│   │   ├── platform/ (1)   public/ (6)  storefront/ (9)  ui/ (22 — shadcn/Radix primitives)
│   ├── lib/                         # 46 files: api-client.ts, storefront-server.ts, customer-auth*.ts,
│   │                                #   vat.ts, delivery-fee.ts, structured-data.ts (SEO/JSON-LD), etc.
│   ├── hooks/                       # use-stomp, use-order-events, use-customer-session, use-toast, etc. (10)
│   ├── types/                       # api.ts, storefront.ts, next-auth.d.ts, jest-axe.d.ts
│   ├── middleware.ts                # Next.js edge middleware (session-based routing)
│   ├── e2e/                         # Playwright specs (27 spec files) + helpers/
│   └── public/brand/                # Static brand assets
│
├── mcp-server/                     # Node/TypeScript MCP tool server (AI agent surface)
│   ├── src/
│   │   ├── index.ts                # MCP server entry point
│   │   ├── server.ts                # Tool registration
│   │   ├── core-client.ts           # HTTP client → CORE_BASE_URL (default http://core-java:9090)
│   │   ├── errors.ts                # Typed MCP error mapping
│   │   └── tools/                   # list-shops, list-products, read-orders,
│   │                                #   create-order, create-customer (+ .test.ts siblings)
│   └── scripts/                     # Build/publish helpers
│
├── k8s/                             # Kustomize deploy manifests — staging/prod deploy target
│   ├── base/                        # core-java/edge-go/frontend Deployments, ingress, sse-ingress,
│   │                                #   configmap, pg-backup-cronjob, secrets-template.yaml.example
│   ├── local/                       # minikube overlay (namespace, scale-patch, ingress-patch)
│   ├── staging/                     # staging overlay (hosts patch, configmap patch)
│   ├── production/                  # production overlay (namespace, configmap patch)
│   ├── scripts/                     # deploy helper scripts specific to k8s
│   └── goldens/                     # Golden-file fixtures for `kubectl kustomize` CI diff gates
│
├── infra/                           # Local-dev infrastructure (compose-adjacent, NOT k8s)
│   ├── docker-compose.yml, docker-compose.hostnet.yml, .env.example
│   ├── db/init/                     # DB bootstrap SQL (runtime role grants)
│   ├── keycloak/themes/jtoye/       # Custom Keycloak login theme
│   ├── monitoring/                  # prometheus/, grafana/, alertmanager/ configs + templates
│   ├── rabbitmq/                    # Broker definitions
│   ├── load-testing/                # k6/baseline configs
│   ├── secrets/                     # Local secret templates (not real secrets)
│   ├── backups/                     # Backup/restore drill artifacts
│   └── dependency-horizons.yaml     # Version-support-window tracking (feeds check-dependency-horizons.sh)
│
├── scripts/                         # 69 top-level shell scripts — CI gates (check-*.sh), deploy.sh,
│                                     #   start-dev.sh/stop-dev.sh (hybrid runtime), seed-*.sh, smoke-test*.sh
│
├── docs/                            # analysis/ api/ architecture/ (incl. decisions/ ADR-0001..0005)
│                                     #   archive/ audit/ config/ guides/ integration/ legal/ ops/
│                                     #   planning/ reports/ runbooks/ security/ setup/ status/ troubleshooting/
│
├── .planning/                       # GSD workflow artifacts
│   ├── codebase/                    # THIS document + STACK/INTEGRATIONS/CONVENTIONS/TESTING/CONCERNS
│   ├── phases/                      # Numbered phase plans (21-35+)
│   ├── quick/                       # /gsd-quick task records
│   ├── debug/, research/, sketches/, specs/, milestones/, housekeeping/, graphs/, state-of-codebase/
│
├── docker-compose.full-stack.yml   # CANONICAL local dev + E2E runtime (all services incl. Mailhog)
├── docker-compose.frontend-3100.yml # Frontend-only compose variant
├── .env / .env.example              # Environment configuration (never read .env contents)
├── build.gradle.kts, settings.gradle.kts, gradlew  # Root Gradle wrapper for core-java
├── AGENTS.md, CLAUDE.md, README.md, HANDOFF.md      # Project/AI-agent instructions and status
└── SECURITY-FINDINGS.md, SPRING_FRAMEWORK_UPGRADE_REPORT.md, ORDER_NUMBER_GENERATION_REPORT.md
```

## Directory Purposes

**`core-java/src/main/java/uk/jtoye/core/`:**
- Purpose: All backend business logic, one package per domain
- Contains: `@RestController`, `@Service`, `@Repository`/`JpaRepository`, `@Entity`, MapStruct `@Mapper`, `@Aspect` classes, colocated per domain (no separate `controllers/`, `services/`, `repositories/` top-level split)
- Key files: `CoreApplication.java` (entry point), `security/TenantContext.java` + `security/TenantSetLocalAspect.java` + `security/JwtTenantFilter.java` (tenant isolation core), `common/GlobalExceptionHandler.java` (error handling)

**`core-java/src/main/resources/db/migration/`:**
- Purpose: Flyway-versioned schema, the single source of truth for the database
- Contains: 66 `V<N>__description.sql` files; RLS policies are defined per-table alongside the table itself, not in a separate "policies" directory
- Note: `spring.flyway.out-of-order=true` is required — several version slots were filled non-sequentially (e.g. V44 landed after V45/V46 were already shipped)

**`edge-go/cmd/edge/`:**
- Purpose: The Go binary's entry point and Gin route handlers
- Contains: `main.go` (router wiring, only ONE business route proxied: `POST /api/v1/sync/batch`), `handlers.go`, `metrics.go`, `docs.go`
- Key files: `main.go` — read this directly before restating what the edge proxies; it changes independently of documentation

**`frontend/app/dashboard/`:**
- Purpose: Authenticated vendor admin surface — one subdirectory per feature area, each with its own `page.tsx`
- Contains: `customers/`, `finance/`, `kitchen/`, `marketing/`, `media/`, `onboarding/` (2 pages), `orders/` (2 pages), `payments/` (2 pages), `products/` (2 pages), `shops/`, `staff/`, `webhooks/` (2 pages)

**`frontend/app/shop/[slug]/`:**
- Purpose: Public/customer-facing storefront for a single shop, dynamic route on the shop slug
- Contains: `page.tsx` (shop detail), `cart/page.tsx`, `checkout/page.tsx`, `layout.tsx`, `loading.tsx`, `not-found.tsx`, `shop-detail-client.tsx`

**`frontend/lib/`:**
- Purpose: Framework-agnostic business logic and API clients shared across pages/components
- Contains: `api-client.ts` (axios instance, `NEXT_PUBLIC_API_URL`), `storefront-server.ts` (server-side data loaders), `vat.ts`/`delivery-fee.ts`/`minimum-order.ts` (checkout money logic), `structured-data.ts` (SEO JSON-LD), `customer-auth*.ts`/`vendor-*.ts` (session lifecycle)

**`mcp-server/src/tools/`:**
- Purpose: One file per MCP tool exposed to AI agents
- Contains: `list-shops.ts`, `list-products.ts`, `read-orders.ts` (read-only), `create-order.ts`, `create-customer.ts` (mutating, Phase 25) — each paired with a `.test.ts` sibling

**`k8s/base/` vs `k8s/local|staging|production/`:**
- Purpose: `base/` is the shared Kustomize resource set; the three overlays patch it per-environment (namespace, replica counts, ingress hosts, configmap values)
- Deploy path: `ci-cd.yaml` deploy job + `scripts/deploy.sh` apply the appropriate overlay; this is the staging/prod target, NOT local dev
- Generated: No — hand-maintained YAML, validated by `k8s/goldens/` fixtures in CI

**`infra/`:**
- Purpose: Local-dev-adjacent infrastructure config consumed by BOTH the hybrid `scripts/start-dev.sh` runtime (`infra/` compose + `infra/.env`) and by `docker-compose.full-stack.yml` for some shared config (monitoring, Keycloak theme, DB init)
- Contains: Prometheus/Grafana/Alertmanager provisioning, Keycloak custom theme, RabbitMQ definitions, DB init SQL, dependency-horizon tracking

**`scripts/`:**
- Purpose: CI gate scripts (`check-*.sh`, ~40 of the 69 files) plus operational scripts (deploy, seed, smoke-test, dev-runtime start/stop)
- Contains: Every `check-*.sh` is wired into a GitHub Actions workflow — see `scripts/check-gate-enforcement.sh`, which itself asserts no gate script is unreferenced in CI

**`.planning/`:**
- Purpose: GSD workflow state — phase plans, research, quick-task records, this codebase map
- Contains: `codebase/` (this doc + siblings), `phases/` (numbered implementation plans), `quick/` (small task records), `debug/`, `research/`, `sketches/`, `specs/`

## Key File Locations

**Entry Points:**
- `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`: Spring Boot main class
- `edge-go/cmd/edge/main.go`: Go edge gateway main
- `frontend/app/page.tsx`: Next.js landing page (root route)
- `frontend/app/layout.tsx`: Next.js root layout
- `mcp-server/src/index.ts`: MCP server entry point

**Configuration:**
- `core-java/src/main/resources/application.yml` (+`-dev`/`-test`/`-staging`/`-prod` profile overlays)
- `frontend/next.config.mjs`, `frontend/tsconfig.json`, `frontend/eslint.config.mjs`
- `edge-go/go.mod`, `edge-go/Dockerfile`
- `docker-compose.full-stack.yml` (canonical local runtime), `infra/docker-compose.yml` (hybrid runtime)
- `k8s/base/kustomization.yaml` + overlay `kustomization.yaml` files

**Core Logic:**
- Tenant isolation: `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java`, `TenantSetLocalAspect.java`, `JwtTenantFilter.java`
- Vendor-scoped access: `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java`
- Order state machine: `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java`
- Transactional outbox: `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java`, `core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java`
- STOMP addressing: `core-java/src/main/java/uk/jtoye/core/websocket/StompDestinations.java`

**Testing:**
- Java: `core-java/src/test/java/uk/jtoye/core/` (mirrors `main/` package structure; Testcontainers-backed integration tests for RLS)
- Frontend unit: colocated `__tests__/` dirs + `*.test.tsx` under `frontend/app/`, `frontend/components/`, `frontend/hooks/`, `frontend/lib/`
- Frontend E2E: `frontend/e2e/*.spec.ts` (27 files), Playwright config at `frontend/playwright.config.ts`
- Go: `*_test.go` colocated with source in `edge-go/cmd/edge/` and `edge-go/internal/*/`
- MCP: `mcp-server/src/**/*.test.ts` (Vitest)

## Naming Conventions

**Files:**
- Java: `PascalCase.java` matching the public class/interface name (`ShopService.java`, `OrderController.java`)
- TypeScript components: `PascalCase.tsx` for components, `kebab-case.ts` for utilities/hooks (`use-stomp.ts`, `api-client.ts`)
- Go: `lowercase.go`, `_test.go` suffix for tests, colocated with the code under test
- SQL migrations: `V<N>__snake_case_description.sql` (Flyway convention, sequential except deliberately reserved out-of-order slots)

**Directories:**
- Java: `internal/<domain>` — actually `uk/jtoye/core/<domain>` package-per-feature, flat (no `controllers/services/repositories` top-level split)
- Go: `internal/<domain>` — genuinely isolated by feature, enforced by Go's `internal/` visibility rule
- Frontend routes: Next.js file-based routing — a directory under `app/` becomes a URL segment; `[slug]` denotes a dynamic segment; `__tests__` denotes colocated tests excluded from routing

## Where to Add New Code

**New backend feature (domain):**
- Create a new package under `core-java/src/main/java/uk/jtoye/core/<domain>/` following the existing pattern: `<Entity>.java`, `<Entity>Repository.java`, `<Entity>Service.java`, `<Entity>Controller.java`, `<Entity>Mapper.java`, `dto/` subpackage for request/response DTOs
- Add a Flyway migration in `core-java/src/main/resources/db/migration/V<next>__description.sql` with `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` and a tenant policy using the `current_tenant_id()` helper (never a raw `::uuid` GUC cast — `RlsContractTest` rejects it)
- Tests: mirror the package under `core-java/src/test/java/uk/jtoye/core/<domain>/`; use Testcontainers for anything touching RLS

**New vendor dashboard page:**
- Add `frontend/app/dashboard/<feature>/page.tsx`; shared UI in `frontend/components/dashboard/`; API calls via a new or existing `frontend/lib/<feature>-api.ts` client hitting `NEXT_PUBLIC_API_URL` directly (never through the Go edge)

**New storefront-facing page:**
- Add under `frontend/app/shop/` or `frontend/app/shop/[slug]/`; ensure metadata (`export const metadata`), canonical/OG tags, and JSON-LD (`frontend/lib/structured-data.ts`) are set — public pages are SEO-contracted per project CLAUDE.md quality dimensions

**New MCP tool:**
- Add `mcp-server/src/tools/<tool-name>.ts` + `<tool-name>.test.ts`; register in `mcp-server/src/server.ts`; call `core-client.ts` directly against `CORE_BASE_URL`

**New CI gate:**
- Add `scripts/check-<name>.sh`; wire it into the relevant `.github/workflows/*.yml`; `scripts/check-gate-enforcement.sh` will fail CI if a new `check-*.sh` is added but never referenced

**Utilities:**
- Java shared helpers: `core-java/src/main/java/uk/jtoye/core/common/`
- Frontend shared helpers: `frontend/lib/utils.ts` (generic) or a new `frontend/lib/<concern>.ts` file for a named concern (matches existing pattern — one file per concern, not a catch-all)

## Special Directories

**`core-java/build-local/`:**
- Purpose: The LIVE local Gradle build output (JaCoCo reports, compiled classes, generated OpenAPI spec)
- Generated: Yes
- Committed: No
- Note: `core-java/build/` also exists but is STALE — always read build artifacts from `build-local/`, never `build/` (see project-level trap memory on stale build directories)

**`k8s/goldens/`:**
- Purpose: Golden-file fixtures asserting `kubectl kustomize` output shape hasn't drifted unexpectedly across `k8s/base` + overlay changes
- Generated: No (hand-committed expected output, regenerated deliberately when a change is intentional)
- Committed: Yes

**`.qa-council/`:**
- Purpose: QA council audit run artifacts (findings, adjudication records) from the project's structured audit process
- Generated: Yes (per audit run)
- Committed: Yes (audit trail)

**`frontend/coverage/`, `frontend/playwright-report/`, `frontend/test-results/`, `frontend/e2e-artifacts/`:**
- Purpose: Test/coverage tool output
- Generated: Yes
- Committed: No (or selectively — `e2e-artifacts/` has shown up with specific phase subfolders committed for evidence, e.g. `35-12/`)

**`.evidence/`:**
- Purpose: Falsifiable-evidence artifacts (fail-direction + pass-direction proof pairs) required by the project's evidence-standards quality dimension
- Generated: Yes, per verification run
- Committed: Yes

---

*Structure analysis: 2026-09-03*
