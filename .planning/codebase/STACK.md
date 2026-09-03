# Technology Stack

**Analysis Date:** 2026-09-03

## Languages

**Primary:**
- Java 25 (Temurin) — Core API (`core-java/`), toolchain pinned in `core-java/build.gradle.kts:13` (`JavaLanguageVersion.of(25)`) and root `build.gradle.kts:9`. Docker build/runtime stages use `eclipse-temurin:25-jdk-alpine` / `eclipse-temurin:25-jre-alpine` (`core-java/Dockerfile`). CI pins `java-version: '25'` / `distribution: 'temurin'` via `actions/setup-java@v6` in `.github/workflows/ci-cd.yaml` (4 jobs: test, integration-tests, code-review-gate-checks, and one more).
- TypeScript 5.9.3 — Frontend (`frontend/package.json` `devDependencies.typescript`), Next.js 16.3.2 + React 19.2.8. `frontend/tsconfig.json` strict mode, `target: ES2017`.
- Go 1.27 — Edge API gateway (`edge-go/go.mod:3` `go 1.27.0`; `edge-go/Dockerfile` builds on `golang:1.27-alpine`; CI pins `go-version: '1.27'` via `actions/setup-go@v7`). **Note:** project prose elsewhere (CLAUDE.md, README) says "Go 1.26" — that is stale; the manifest and Dockerfile and CI all agree on 1.27.

**Secondary:**
- TypeScript (Node/ESM) — MCP server (`mcp-server/`), `mcp-server/package.json` devDependencies `typescript: ~5.9`, runtime `node:24-alpine` (`mcp-server/Dockerfile`).
- SQL (PostgreSQL 15 dialect) — Flyway migrations, `core-java/src/main/resources/db/migration/` (66 versioned files, V1–V66; see Configuration below).
- YAML — Spring profiles, Kustomize manifests, GitHub Actions workflows.

## Runtime

**Environment:**
- JVM — Java 25 (Temurin), Core API execution. `gradle.properties` enables toolchain auto-detect/auto-download.
- Node.js 24 — Frontend build/runtime (`frontend/Dockerfile` builder+runner both `node:24-alpine`; CI `node-version: '24'`) and MCP server runtime (`mcp-server/Dockerfile` `node:24-alpine`).
- Go 1.27 runtime — Edge gateway, statically linked (`CGO_ENABLED=0`), deployed on a `scratch` base image (`edge-go/Dockerfile`).
- PostgreSQL 15-alpine — Database (`docker-compose.full-stack.yml:43` `postgres:15-alpine`; `infra/docker-compose.yml:13` `postgres:15`; `infra/backups/Dockerfile` uses `postgres:15-bookworm` for pg_dump tooling).

**Package Manager:**
- Gradle 9.7.1 (Kotlin DSL) — `gradle/wrapper/gradle-wrapper.properties` pins `distributionUrl` to `gradle-9.7.1-bin.zip`. Lockfile: none (Gradle doesn't use one by default here); dependency versions pinned via `io.spring.dependency-management` BOM + explicit `implementation(...)` coordinates in `core-java/build.gradle.kts`.
- npm — Frontend and MCP server. Lockfile present: `frontend/package-lock.json`, `mcp-server/package-lock.json` (implicit from `npm ci` usage in both Dockerfiles).
- go mod — Edge gateway. Lockfile present: `edge-go/go.sum`.

## Frameworks

**Core:**
- Spring Boot 3.5.16 — Web framework, DI, auto-configuration (`core-java/build.gradle.kts:2`).
- Spring Data JPA + Hibernate ORM (Boot-managed version) — ORM/persistence, plus Hibernate Envers for `_aud` audit-history tables.
- Spring Security + Spring OAuth2 Resource Server — JWT/OIDC validation against Keycloak, dual-realm (staff `jtoye-dev` + customer `jtoye-customers`).
- Spring WebFlux (`spring-boot-starter-webflux`) — non-blocking `WebClient` used for the Anthropic/AI call path and other outbound HTTP (FHRS, Companies House, webhook delivery).
- Spring AMQP — RabbitMQ integration (listeners, transactional outbox flushers for payment/media events).
- Spring WebSocket (STOMP) — real-time KDS/order updates; in-memory broker locally, RabbitMQ STOMP relay in k8s (`stomp.broker.mode`).
- Spring State Machine 4.0.2 (`spring-statemachine-starter`) — order lifecycle state machine.
- Spring Cache + Spring Data Redis — tenant-aware caching.
- Spring AOP — cross-cutting concerns (tenant pinning, caching).
- Next.js 16.3.2 + React 19.2.8 — Frontend framework (file-based routing, standalone output build).
- Gin v1.12.0 — Go HTTP routing/middleware for the edge gateway (`edge-go/go.mod:6`).

**Testing:**
- JUnit 5 (via `spring-boot-starter-test`) — Java unit/integration tests.
- Testcontainers 1.21.4 (`testcontainers`, `postgresql`, `rabbitmq`, `junit-jupiter` modules) — real Postgres + RLS and real-broker fan-out proofs; run via the dedicated `integrationTest` Gradle task, tagged `testcontainers`, excluded from the default `test` task.
- H2 (`com.h2database:h2`) — lightweight in-memory unit tests.
- JaCoCo 0.8.15 (pinned explicitly, `core-java/build.gradle.kts:362`; required for JDK 25 class-file support — 0.8.12 cannot read major version 69) — coverage, aggregated over `test.exec` + `integrationTest.exec`.
- Jest 29.7.0 + @testing-library/react 16.3.0 + jest-environment-jsdom 30.4.1 — Frontend unit/component tests.
- jest-axe 11.0.0 + @axe-core/playwright 4.13.0 + axe-core 4.13.0 — Accessibility testing.
- @playwright/test 1.62.1 — E2E browser automation (`frontend/playwright.config.ts`).
- vitest ^4 — MCP server unit tests (`mcp-server/package.json`).

**Build/Dev:**
- Spring Boot Gradle Plugin 3.5.16 — bootJar packaging, redirected to `core-java/build-local/` (`layout.buildDirectory.set(file("build-local"))`) — `core-java/build/` is a stale artifact directory, never read.
- Flyway 3-part: `flyway-core` + `flyway-database-postgresql` (Boot-managed versions) — schema migration.
- Lombok + MapStruct 1.6.3 (+ `lombok-mapstruct-binding` 0.2.0) — boilerplate reduction / compile-time DTO mapping.
- ESLint 9 flat config (`frontend/eslint.config.mjs`) — the only lint config; Next 16 removed `next lint`. Spreads `eslint-config-next@16.3.2`'s native flat-config arrays (`/core-web-vitals`, `/typescript`) directly — do NOT wrap with `FlatCompat` (crashes with a circular-structure error per that file's own header).
- TailwindCSS 3.4.1 + PostCSS 8.5.12 — Frontend styling.
- tsx ^4 — MCP server dev-mode TS execution (`mcp-server/package.json` `dev` script).
- cross-env 10.1.0 — cross-platform env var injection for `npm run dev`.

## Key Dependencies

**Critical:**
- PostgreSQL JDBC Driver 42.7.13 (`core-java/build.gradle.kts:163`) — explicit pin, not Boot-managed.
- AWS SDK v2 BOM 2.54.3 (`software.amazon.awssdk:bom`) + `software.amazon.awssdk:s3` — S3-compatible object storage client (MinIO in dev, real S3 in prod).
- Stripe Java SDK 33.3.0 — Payment intents, Connect (destination charges), webhook signature verification.
- @stripe/react-stripe-js 6.8.2 + @stripe/stripe-js 9.14.0 — Frontend Stripe Elements integration.
- next-auth 5.0.0-beta.32 (`@auth/core` pinned via `overrides` to `0.41.3`) — Session/auth middleware, Keycloak OIDC provider.
- @modelcontextprotocol/sdk ^1.29.0 — MCP server protocol implementation (`mcp-server/package.json`).
- golang-jwt/jwt/v5 v5.3.1 — Edge gateway JWT validation against Keycloak JWKS.
- sony/gobreaker v1.0.0 — Edge gateway circuit breaker (no fallback; breaker-open returns 502).

**Infrastructure:**
- com.rabbitmq:amqp-client — pinned to 5.33.1 via the `rabbit-amqp-client.version` Gradle extra property (NOT a direct dependency; see extensive in-file rationale) to close 6 HIGH/MEDIUM CVEs Boot's own 5.25.0 BOM pin would otherwise ship.
- Resilience4j 2.4.0 (`resilience4j-spring-boot3`) — circuit breakers for Stripe, FHRS, Companies House, email, AI, webhook egress (config in `application.yml:724-775`).
- Bucket4j 8.10.1 (`bucket4j-core`, `bucket4j-redis`) — Redis-backed token-bucket rate limiting.
- Micrometer Prometheus + Micrometer Tracing (Brave/Zipkin bridge) — metrics + distributed tracing.
- com.sksamuel.scrimage 4.6.7 (`scrimage-core`, `scrimage-webp`) + TwelveMonkeys ImageIO 3.14.0 (`imageio-webp`, `imageio-core`) — image decode/resize/WebP transcode pipeline (Phase 24 media pipeline); scrimage-webp's bundled `cwebp` is glibc-linked and does NOT run on the Alpine (musl) runtime image, so the Dockerfile installs `libwebp-tools` and points the JVM at `/usr/bin` via `-Dcom.sksamuel.scrimage.webp.binary.dir`.
- OpenPDF 2.0.3 (`com.github.librepdf:openpdf`) — PDF generation for allergen labels (JasperReports was removed 2026-07-27 as unused, closing 3 Trivy HIGHs).
- Framer Motion 13.1.1, GSAP 3.15.0 (+`@gsap/react` 2.1.2) — animation.
- Recharts 3.10.1 — dashboard charts.
- Radix UI (`@radix-ui/react-*`) — headless component primitives.
- Zod 4.4.3 (core-java's DTOs use Bean Validation instead) / 4.x in frontend and mcp-server — schema validation.
- React Hook Form 7.85.0 + @hookform/resolvers 5.9.1 — form state.
- @stomp/stompjs 7.3.0 + @microsoft/fetch-event-source 2.0.1 — Frontend real-time (STOMP over WebSocket, SSE consumption).
- pino ^10 — MCP server structured logging.
- express ^5 — MCP server HTTP host (Streamable HTTP transport).
- prometheus/client_golang v1.24.1 — Edge gateway Prometheus metrics.
- uber/zap v1.28.0 — Edge gateway structured logging.
- swaggo/swag v1.16.6 + gin-swagger v1.6.1 — Edge gateway OpenAPI docs (`/openapi.json`, `/docs`).

## Configuration

**Environment:**
- `.env` file present (git-ignored) — read by `docker-compose.full-stack.yml`; `.env.example` present as the template (both exist at repo root; contents not read per secret-handling policy).
- Spring profile precedence via `SPRING_PROFILES_ACTIVE`: `dev` (compose default), `local`, `staging`, `test`, `prod`.
- `application.yml` (793 lines) is the base — nearly every integration/tunable is `${ENV_VAR:default}` (house convention: "no literals in code").
- Profile overlays only override what differs: `application-dev.yml` (56 lines — relaxed customer-JWT email-verification, health probes), `application-staging.yml` (150 lines — wider actuator exposure, debug logging), `application-prod.yml` (158 lines — separate management port 9091, hardened error/logging, no Swagger by default), `application-test.yml` (H2 + Testcontainers wiring, not fully enumerated here), `application-local.yml` (hybrid host-process dev runtime).
- `frontend/lib/env-validation.ts` classifies `NEXT_PUBLIC_*` vars as required vs optional; several (`NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`) must be supplied as Docker **build args**, not just runtime env — they are inlined into the browser bundle at `next build` time and a runtime-only value arrives too late (enforced by a fail-fast `RUN` gate in `frontend/Dockerfile`).

**Build:**
- Gradle: root `build.gradle.kts` (plugin versions, JDK 25 toolchain, `group = "uk.jtoye"`, `version = "2.3.0"`), `settings.gradle.kts` (single subproject: `core-java`), `core-java/build.gradle.kts` (dependencies, JaCoCo, integrationTest task, OpenAPI snapshot tasks).
- Next.js: `frontend/next.config.mjs` (`output: 'standalone'`, `typescript.tsconfigPath: 'tsconfig.build.json'` — shipped code only, tests type-checked separately by bare `tsc --noEmit` in CI; image `remotePatterns` allow `localhost:9000/jtoye-images/**` for local MinIO).
- TypeScript: `frontend/tsconfig.json` (strict, `@/*` path alias to frontend root), `mcp-server/tsconfig.json` (separate project).
- ESLint: `frontend/eslint.config.mjs` (flat config, ESLint 9).
- Go: `edge-go/go.mod` / `go.sum`; `edge-go/Dockerfile` (Go version, CI setup-go pins, and `infra/dependency-horizons.yaml` Go rows must all move in lockstep per that Dockerfile's own header comment).
- Docker: 5 Dockerfiles — `core-java/Dockerfile`, `edge-go/Dockerfile`, `frontend/Dockerfile`, `mcp-server/Dockerfile`, `infra/backups/Dockerfile` — every one has a Dependabot `docker` ecosystem entry in `.github/dependabot.yml` (a gap here is CI-enforced by `scripts/check-image-supply-chain.sh`).
- Flyway migrations: `core-java/src/main/resources/db/migration/` — **66 versioned files, V1 through V66** (confirmed by numeric sort of the directory listing; V64 is NOT the head — that is stale prose elsewhere). `spring.flyway.out-of-order=true` is set in `application.yml`, `application-staging.yml`, and `application-prod.yml` (base + both deployed profiles), because reserved slot V44 was filled after V45/V46 shipped and deployed DBs are already stamped past it.
  - Most recent migrations: V63 (order-line allergen snapshot), V64 (grant TRUNCATE on `postcode_centroid` to `jtoye_runtime`), V65 (`_aud` INSERT policies tenant-check), V66 (`order_unit_count`).
  - **Merged vs branch:** `origin/main` is stamped at **V64**. V65 and V66 exist only on the current branch `feature/qa-remediate-20260902` (commits `766e5e96`, `bba882aa`), so `CLAUDE.md`'s "Current schema version: V64" is correct *for merged state* and becomes stale the moment this branch merges. Verified 2026-09-03 with `git ls-tree origin/main`.

## Platform Requirements

**Development:**
- Docker & Docker Compose v2+ (`docker compose` subcommand — the v1 `docker-compose` binary is explicitly not installed/supported per `docker-compose.full-stack.yml` header).
- JDK 25 (Temurin recommended, matches CI).
- Node.js 24+.
- Go 1.27+.
- Gradle 9.7+ (wrapper included, pinned to 9.7.1).
- Local runtime is Docker Compose (`docker-compose.full-stack.yml`) — the canonical local dev + E2E runtime, XOR with a local minikube (never run both; they share the dev DB).

**Production:**
- Kubernetes via Kustomize: `k8s/base` + `k8s/staging` / `k8s/production` overlays. Images pushed to `ghcr.io/bralabee/jtoye-{core-java,edge-go,frontend,pg-backup}`; `newTag` in overlay `kustomization.yaml` defaults to `2.1.0` but CI pins the exact full-sha tag at deploy time via `kustomize edit set image`.
- PostgreSQL 15+ (managed/external in prod).
- Redis 7+ (external/managed).
- RabbitMQ 4.3.4-management-alpine pinned in compose; **the deployed staging/production broker version is unverified from this repository** (see `docs/runbooks/rabbitmq-broker-upgrade.md`, ADR-0002) — minimum supported is 3.13+. RabbitMQ 4.3 community support ends **2026-11-30**, tracked in `infra/dependency-horizons.yaml`.
- Keycloak 24.0.5 (external IdP in prod).
- S3 (AWS or S3-compatible) for image storage — MinIO is dev-only.
- SMTP provider (Mailhog is dev-only, e.g. SendGrid/SES in prod).

## Observability Stack (self-hosted, compose-based)

- Prometheus v2.48.0 (`prom/prometheus`) — `infra/monitoring/docker-compose.monitoring.yml:35`.
- Grafana 10.2.2 (`grafana/grafana`) — `infra/monitoring/docker-compose.monitoring.yml:84`.
- Alertmanager v0.27.0 (`prom/alertmanager`) — `infra/monitoring/docker-compose.monitoring.yml:115`.
- redis_exporter v1.58.0, postgres_exporter v0.15.0 — infra metric exporters.
- No hosted/SaaS error tracker (no Sentry or equivalent) is wired into either `core-java/build.gradle.kts` or `frontend/package.json`.

## Security Scanning / Supply Chain (CI)

- Trivy (`aquasecurity/trivy-action` pinned to a commit SHA, `# v0.36.0`) — image + filesystem scans, fails on fixable CRITICAL/HIGH, SARIF uploaded to GitHub code scanning. Runs in the `ci-cd.yaml` build-and-push job and a filesystem-scan job.
- gitleaks (`.github/workflows/gitleaks.yml`) — secret scanning on PR + push to main.
- pii-guard (`.github/workflows/pii-guard.yml`) — zero-tolerance guard against re-introducing DB dumps / `.sql.gz` files.
- Dependabot (`.github/dependabot.yml`) — weekly, per-ecosystem: gradle (`/core-java` only, deliberately not root — avoids duplicate PRs), gomod (`/edge-go`), npm (`/frontend`; eslint major bump blocked pending `eslint-plugin-react` peer support for eslint 10), docker (one entry per Dockerfile: core-java, edge-go, frontend, mcp-server, infra/backups), github-actions.
- `base-image-freshness.yml` — separate daily scan of published base image tags (Dependabot only bumps the tag string, not floating-tag content drift).

---

*Stack analysis: 2026-09-03*
