# Technology Stack

**Analysis Date:** 2026-04-18

## Languages

**Primary:**
- Java 21 - Core API (Spring Boot 3.5.16) — toolchain `JavaLanguageVersion` pinned in `core-java/build.gradle.kts:12-13`
- TypeScript 5 - Frontend (Next.js 16.3.2, React 19); `typescript` pinned in `frontend/package.json:66`
- Go 1.27 - Edge API gateway (Gin) — `go 1.27.0` in `edge-go/go.mod:3`

**Secondary:**
- SQL (PostgreSQL 15) - Schema evolution via Flyway migrations (`core-java/src/main/resources/db/migration/`)
- YAML - Spring profiles, docker-compose, Prometheus/Alertmanager/Grafana config
- Shell - Alertmanager entrypoint template rendering (`infra/monitoring/alertmanager/entrypoint.sh`)

## Runtime

**Environment:**
- JVM (Java 21) - Core API execution
- Node.js 24+ - Frontend build and runtime
- Go 1.22 runtime - Edge gateway
- PostgreSQL 15-alpine - Database (shared with Keycloak; separate DB)
- Redis 7-alpine - Cache + STOMP user destination resolution support
- RabbitMQ 4.3.4-management-alpine - AMQP + STOMP relay

**Package Manager:**
- Gradle 8.10+ (Kotlin DSL) - Java build (`settings.gradle.kts`, `core-java/build.gradle.kts`)
- npm - Node.js dependencies (`frontend/package.json`)
- go mod - Go dependencies (`edge-go/go.mod`, `edge-go/go.sum`)

**Lockfile:**
- Gradle: `gradle/wrapper/gradle-wrapper.properties` pins the wrapper version
- npm: `frontend/package-lock.json` present
- Go: `edge-go/go.sum` present

## Frameworks

**Core (Backend):**
- Spring Boot 3.5.16 (`core-java/build.gradle.kts:2`) - Web framework, DI, auto-configuration
- Spring Data JPA - ORM and database abstraction
- Spring Security - Authentication/authorization
- Spring OAuth2 Resource Server - JWT validation against Keycloak JWKS
- Spring AOP - Aspect-oriented programming (`@Cacheable`, tenant guards)
- Spring Cache - Redis-backed distributed cache
- Spring AMQP - RabbitMQ publisher/consumer for order events (`spring-boot-starter-amqp`, `core-java/build.gradle.kts:52`)
- Spring WebSocket + STOMP - Kitchen Display System real-time messaging (`spring-boot-starter-websocket`, `core-java/build.gradle.kts:108`)
- Spring WebFlux (`spring-boot-starter-webflux`) - Non-blocking WebClient for Claude/Ollama AI calls (`core-java/build.gradle.kts:134`)
- Spring Statemachine 4.0.2 - Order lifecycle state machine (`core-java/build.gradle.kts:95`)

**API & Observability:**
- Spring Actuator - `/actuator/health`, `/actuator/prometheus`
- SpringDoc OpenAPI 2.8.6 - Swagger/OpenAPI documentation (`core-java/build.gradle.kts:164`)
- Micrometer Prometheus - Metrics export (`io.micrometer:micrometer-registry-prometheus`)
- Micrometer Tracing (`micrometer-tracing-bridge-brave`) + Zipkin Reporter - Distributed tracing (`core-java/build.gradle.kts:138`)

**Frontend:**
- Next.js 16.3.2 - React framework, file-based routing, standalone output
- React 19 + React DOM 19
- React Hook Form 7.85.0 + @hookform/resolvers 5.9.1
- Next-Auth 5.0.0-beta.32 - Keycloak OIDC session handling
- TailwindCSS 3.4.1 + tailwind-merge 3.4.0 + tailwindcss-animate 1.0.7
- Radix UI primitives (alert-dialog, dialog, dropdown-menu, label, select, slot, tabs, toast)
- Zod 4.4.3 - Schema validation
- @stomp/stompjs 7.3.0 - Browser STOMP client for KDS WebSocket (added in v2.1)
- Framer Motion 12.43.0 - Animations
- Recharts 3.10.1 - Admin dashboard charts
- date-fns 4.4.0, clsx 2.1.1, class-variance-authority 0.7.1, lucide-react 1.33.0

**Edge Gateway:**
- Gin v1.12.0 - HTTP routing and middleware (`edge-go/go.mod:6`)
- golang-jwt/jwt v5 (v5.2.1) - JWT validation against Keycloak JWKS
- uber/zap v1.27.0 - Structured logging
- sony/gobreaker v1.0.0 - Circuit breaker pattern fronting Core API
- go-playground/validator v10 - Request validation (transitive via Gin)

**Testing:**
- JUnit 5 (spring-boot-starter-test) - Java unit/integration tests
- Spring Security Test - `@WithMockUser`, security-aware MockMvc
- Testcontainers 1.21.4 (+ postgresql, junit-jupiter) - Dockerized integration tests, excluded by default, opt-in via `-PincludeIntegration` (`org.testcontainers:testcontainers`, `core-java/build.gradle.kts:189-190`)
- H2 - Lightweight in-memory JPA tests
- Jest 29.7.0 + jest-environment-jsdom 30.3.0 - JS test runner
- @testing-library/react 16.3.0, @testing-library/jest-dom 7.0.1, @testing-library/user-event 14.6.5
- @playwright/test 1.62.1 - E2E browser automation
- Go `testing` stdlib + table-driven tests

**Build & Development:**
- Spring Boot Gradle Plugin 3.5.16 + io.spring.dependency-management 1.1.7
- Build output redirected to `build-local/` to avoid root-owned `build/` permission issues (`core-java/build.gradle.kts:19`)
- Flyway core + flyway-database-postgresql - DB migrations
- Lombok + lombok-mapstruct-binding 0.2.0 - Boilerplate reduction
- MapStruct 1.6.3 - Compile-time DTO ↔ entity mapping

## Key Dependencies

**Critical (Backend):**
- PostgreSQL JDBC 42.7.13 (`core-java/build.gradle.kts:163`)
- Hibernate ORM (managed by Spring Boot BOM) + Hibernate Envers for audit history
- AWS SDK v2 BOM 2.54.3 + `software.amazon.awssdk:s3` (`core-java/build.gradle.kts:119-120`)
- Stripe Java SDK 33.3.0 (`core-java/build.gradle.kts:145`)
- OpenPDF 2.0.3 - Allergen label PDF generation (`core-java/build.gradle.kts:148`)
- ~~JasperReports~~ — **REMOVED 2026-07-27** (`core-java/build.gradle.kts:153`). Never used (zero imports, zero `.jrxml`/`.jasper` templates) and the sole source of `commons-beanutils`; removing it cleared three Trivy image-gate HIGHs (CVE-2025-48734, CVE-2025-10492, CVE-2026-6009). PDF generation is OpenPDF.

**Resilience & Rate Limiting:**
- Resilience4j Spring Boot 3 Starter 2.4.0 - Circuit breakers for stripe/email/ai
- Bucket4j core 8.10.1 + bucket4j-redis 8.10.1 - Token bucket rate limiting backed by Redis

**Critical (Frontend):**
- @stripe/react-stripe-js 6.1.0, @stripe/stripe-js 9.0.1 - Stripe Elements
- axios 1.19.0 - HTTP client
- @stomp/stompjs 7.3.0 - KDS WebSocket client (v2.1 addition)
- framer-motion 12.43.0, recharts 3.10.1

**Infrastructure (from `docker-compose.full-stack.yml` / `infra/monitoring/docker-compose.monitoring.yml`):**
- postgres:15-alpine
- quay.io/keycloak/keycloak:24.0.5
- redis:7-alpine
- rabbitmq:4.3.4-management-alpine — ports 5672 (AMQP), 15672 (mgmt UI), 61613 (STOMP, v2.1) — plugins `rabbitmq_management`, `rabbitmq_management_agent`, `rabbitmq_prometheus`, `rabbitmq_stomp` (`infra/rabbitmq/enabled_plugins`)
- minio/minio:latest + minio/mc:latest (init sidecar with public-read policy on `jtoye-images`)
- ollama/ollama:latest (NVIDIA GPU reservation, `gemma3:12b` pulled by sidecar)
- mailhog/mailhog:v1.0.1 — SMTP 1025, Web UI 8025

**Monitoring Stack (`infra/monitoring/docker-compose.monitoring.yml`):**
- prom/prometheus:v2.48.0 — host port 9091
- grafana/grafana:10.2.2 + grafana-piechart-panel — host port 3001
- prom/alertmanager:v0.27.0 — host port 9093, config rendered from `alertmanager.yml.tmpl` by `entrypoint.sh` (new in v2.1 / phase 9)
- oliver006/redis_exporter:v1.58.0 — port 9121
- prometheuscommunity/postgres-exporter:v0.15.0 — port 9187

## Configuration

**Environment Variables:**
- `.env` at repo root (required for docker-compose, gitignored)
- `frontend/.env.local` (gitignored) derived from `frontend/.env.local.example`
- Secret scanning enforced in CI (`.github/workflows/gitleaks.yml`)

**Key Configuration Files:**
- `core-java/src/main/resources/application.yml` - Base profile
- `application-dev.yml`, `application-test.yml`, `application-staging.yml`, `application-prod.yml`
- `frontend/next.config.mjs` - standalone output, image remotePatterns
- `frontend/tsconfig.json`, `frontend/.eslintrc.json`
- `edge-go/Dockerfile` - Multi-stage, scratch runtime (<15MB)

**Spring Profiles:**
- `dev` (default in docker-compose), `test`, `staging`, `prod`

**Database Configuration:**
- Flyway migrations: `core-java/src/main/resources/db/migration/` — **33 migration files**, current head `V33__fix_rls_policies.sql` (RLS policy fixes for promotions/announcements/reviews/payment_event_outbox)
- Migration strategy: Versioned SQL (`V1__..V33__`)
- RLS enforced on all tenant-scoped tables

**STOMP Broker Mode (v2.1):**
- `STOMP_BROKER_MODE` env var: `in-memory` (default, dev single-replica) or `relay` (RabbitMQ STOMP, required for horizontal scaling)
- `STOMP_RELAY_HOST=rabbitmq`, `STOMP_RELAY_PORT=61613` (see `core-java/.../websocket/WebSocketConfig.java`)

## Platform Requirements

**Development:**
- Docker + Docker Compose (Docker Engine 29+ / API >= 1.40; Testcontainers env var `DOCKER_API_VERSION=1.45` in `core-java/build.gradle.kts:198`)
- Java 21 JDK (JDK 25 incompatible with Gradle 8.10)
- Node.js 24+
- Go 1.22+
- Git
- Optional: NVIDIA GPU + Container Toolkit for Ollama image analysis

**Build:**
- Gradle 8.10+ via wrapper
- npm (bundled with Node.js)
- Docker (for multi-stage images and Testcontainers)

**Runtime (Production):**
- Kubernetes (manifests in `k8s/`)
- PostgreSQL 15+, Redis 7+, RabbitMQ **3.13+** minimum / 4.3 recommended (STOMP plugin required; deployed staging-prod version unverified from this repo — see `docs/runbooks/rabbitmq-broker-upgrade.md`), Keycloak 24.0+
- S3 or S3-compatible storage (MinIO for dev)
- SMTP relay (Mailhog dev; SendGrid/SES/etc. prod)
- Prometheus + Alertmanager + Grafana stack

**Container/Image Versions (source of truth):**
- Spring Boot 3.5.16 on Java 21 — `core-java/build.gradle.kts:2,9`
- postgres:15-alpine — `docker-compose.full-stack.yml:43`
- keycloak:24.0.5 — `docker-compose.full-stack.yml:145`
- redis:7-alpine — `docker-compose.full-stack.yml:193`
- rabbitmq:4.3.4-management-alpine — `docker-compose.full-stack.yml:217`
- mailhog/mailhog:v1.0.1 — `docker-compose.full-stack.yml:695`
- prom/prometheus:v2.48.0 — `infra/monitoring/docker-compose.monitoring.yml:35`
- grafana/grafana:10.2.2 — `infra/monitoring/docker-compose.monitoring.yml:84`
- prom/alertmanager:v0.27.0 — `infra/monitoring/docker-compose.monitoring.yml:115`
- Next.js 16.3.2 — `frontend/package.json:37`
- Go 1.27 (`go 1.27.0`) — `edge-go/go.mod:3`

## Test Suite

**Current counts (verified 2026-04-18):**
- Java: 48 test classes / ~390 `@Test` and `@ParameterizedTest` methods (Testcontainers tests excluded by default; run via `./gradlew test -PincludeIntegration`)
- Jest: 16 test files / ~76 test cases (`frontend/**/*.test.*`)
- Go: 5 test files / ~50 test functions (`edge-go/**/*_test.go`)
- CLAUDE.md references "474+ tests passing (341 Java + 76 Jest + 57 Go)" — Jest count still matches; Java/Go counts have shifted post-v2.1 (v2.1 added Go audit tests, Java STOMP relay tests, frontend STOMP client tests)

## Performance Tuning

**Database:**
- HikariCP connection pool — Dev: 20 max / 5 min-idle; Prod: 50 max / 10 min-idle
- Hibernate batch_size: 20 (dev), 50 (prod); query timeout 30s; idle 10m

**Cache:**
- Redis timeout: 2s (dev), 3s (prod)
- Lettuce pool: 8 active / 8 idle (dev), 20 active / 10 idle (prod)

**Rate Limiting (Bucket4j + Redis):**
- Default: 100 req/min per tenant, burst 20
- Toggle: `RATE_LIMIT_ENABLED=true` (default on)

**Tracing:**
- `TRACING_PROBABILITY=0.1` default (10%)
- Zipkin endpoint: `http://localhost:9411/api/v2/spans` (dev)

---

*Stack analysis: 2026-04-18*
