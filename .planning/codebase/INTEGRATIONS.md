# External Integrations

**Analysis Date:** 2026-04-18

## APIs & External Services

**Payment Processing:**
- Stripe — Online card payments + COD fallback
  - SDK: `com.stripe:stripe-java:28.2.0` (`core-java/build.gradle.kts:60`)
  - Frontend: `@stripe/react-stripe-js 6.1.0`, `@stripe/stripe-js 9.0.1` (`frontend/package.json:25-26`)
  - Implementation: `core-java/src/main/java/uk/jtoye/core/payment/` (PaymentService, webhook controller)
  - Auth: `STRIPE_API_KEY` (sk_test_/sk_live_), `STRIPE_WEBHOOK_SECRET` (whsec_), `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`
  - Failure mode: Resilience4j circuit breaker (`resilience4j.circuitbreaker.instances.stripe`, 50% failure threshold, 30s wait)
  - Fallback: COD (cash on delivery) flow available when Stripe unreachable
  - Outbox: `payment_event_outbox` table (`V31__payment_event_outbox.sql`, RLS fixed in V33) for reliable event dispatch

**AI & Image Analysis:**
- Ollama (default) — Local GPU-accelerated vision model
  - Endpoint: `OLLAMA_URL` (default `http://ollama:11434`)
  - Model: `OLLAMA_MODEL` (default `gemma3:12b`, pulled by `ollama-init` sidecar)
  - Implementation: `core-java/src/main/java/uk/jtoye/core/ai/` via Spring `WebClient` (WebFlux)
  - GPU: NVIDIA device reservation in `docker-compose.full-stack.yml:294-301`
  - Failure mode: Resilience4j circuit breaker (50% threshold, 60s wait, max 2 retries)
  - Fallback: Switch to Anthropic Claude via `AI_PROVIDER=anthropic`
- Anthropic Claude — Cloud vision alternative
  - Auth: `ANTHROPIC_API_KEY` (sk-...)
  - Model: Claude Sonnet 4 (`claude-sonnet-4-20250514`)
  - Transport: Spring WebFlux `WebClient` (no dedicated Anthropic SDK)

## Data Storage

**Databases:**
- PostgreSQL 15-alpine (`docker-compose.full-stack.yml:14`)
  - Connection: `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`
  - Env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
  - Client: Spring Data JPA + Hibernate ORM (managed by Spring Boot BOM)
  - Migrations: Flyway, 33 versioned files in `core-java/src/main/resources/db/migration/`
  - Multi-tenancy: Row-level security (RLS policies), tenant context set per request via `TenantContext` ThreadLocal + `JwtTenantFilter`
  - Schema version: **V33** (`V33__fix_rls_policies.sql` — RLS fixes for promotions, announcements, reviews, `payment_event_outbox`)
  - Keycloak uses a sibling database in the same Postgres instance (`KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak`)

**File Storage:**
- MinIO (S3-compatible, local dev) / AWS S3 (prod)
  - Endpoint: `S3_ENDPOINT` (`http://minio:9000` dev)
  - Credentials: `S3_ACCESS_KEY`, `S3_SECRET_KEY` (default `minioadmin`/`minioadmin`)
  - Bucket: `S3_BUCKET` (`jtoye-images`), public-read policy applied by `minio-init` sidecar (`docker-compose.full-stack.yml:264-278`)
  - Public URL: `S3_PUBLIC_URL`
  - SDK: AWS SDK v2 BOM 2.25.60 → `software.amazon.awssdk:s3` (`core-java/build.gradle.kts:45-46`)
  - Implementation: `core-java/src/main/java/uk/jtoye/core/storage/`
  - Constraints: max 5MB, allowed types `image/jpeg|png|webp|gif`

**Caching:**
- Redis 7-alpine (`docker-compose.full-stack.yml:71`)
  - Connection: `REDIS_HOST:REDIS_PORT` (dev `redis:6379`)
  - Auth: `REDIS_PASSWORD` (required — compose passes `--requirepass`)
  - Client: Spring Data Redis (Lettuce) + Spring Cache
  - Also backs Bucket4j distributed rate limiting (`bucket4j-redis 8.10.1`)
  - Timeout: 2s (dev), 3s (prod)
  - Metrics: scraped via `oliver006/redis_exporter:v1.58.0` on port 9121

## Message Queue

**RabbitMQ 3.12 (AMQP + STOMP):**
- Image: `rabbitmq:3.12-management-alpine` (`docker-compose.full-stack.yml:88`)
- Plugins enabled (`infra/rabbitmq/enabled_plugins`):
  - `rabbitmq_management` + `rabbitmq_management_agent` — port 15672 UI
  - `rabbitmq_prometheus` — metrics scrape target
  - `rabbitmq_stomp` — **new in v2.1**, port 61613, backs Spring STOMP broker relay
- Ports: 5672 (AMQP), 15672 (mgmt UI), 61613 (STOMP)
- Auth: `RABBITMQ_USER` / `RABBITMQ_PASSWORD` (also reused as STOMP client/system login)
- Java client: `spring-boot-starter-amqp` (`core-java/build.gradle.kts:32`)
- Use cases:
  - Order event publishing (`OrderEventPublisher`)
  - Email/notification async dispatch
  - Dead-letter queue for failed messages; cleanup jobs re-drive stale messages
- Failure mode: RabbitTemplate publisher confirms + DLQ fallback; consumers retry with backoff

**STOMP Broker (v2.1):**
- Spring WebSocket broker configured in `core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java`
- Two modes via `STOMP_BROKER_MODE`:
  - `in-memory` (default) — SimpleBroker for single-replica dev
  - `relay` — `StompBrokerRelay` pointing at RabbitMQ port 61613 (required for horizontal scaling so all core-java replicas share KDS broadcasts)
- JWT auth at STOMP CONNECT frame via `TenantChannelInterceptor` + `JwtHandshakeInterceptor`
- Browser client: `@stomp/stompjs 7.3.0` (`frontend/package.json:24`)

## Authentication & Identity

**Auth Provider:**
- Keycloak 24.0.5 (`quay.io/keycloak/keycloak:24.0.5`)
  - Realm: `jtoye-dev` (imported from `infra/keycloak/realm-export.json`)
  - Internal URL (in-cluster): `KC_ISSUER_URI=http://keycloak:8080/realms/jtoye-dev`
  - Public/browser URL: `KEYCLOAK_ISSUER=http://localhost:8085/realms/jtoye-dev`
  - Admin: `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`
  - Health/metrics exposed (`KC_HEALTH_ENABLED=true`, `KC_METRICS_ENABLED=true`)

**Backend (Spring Security):**
- `spring-boot-starter-oauth2-resource-server` (`core-java/build.gradle.kts:22`)
- Config: `core-java/src/main/java/uk/jtoye/core/security/` (`SecurityConfig`, `JwtTenantFilter`, `TenantContext`)
- Token validation: JWT signature + issuer check against Keycloak JWKS
- Tenant extraction from JWT claim → `TenantContext` ThreadLocal → RLS `set_config` per request

**Edge Gateway (Go):**
- `github.com/golang-jwt/jwt/v5 v5.2.1` — JWT parsing
- JWKS fetch + caching against `KC_ISSUER_URI`
- Injects validated tenant claim before forwarding to core-java

**Frontend (Next-Auth 5.0.0-beta.30):**
- Provider: Keycloak OIDC
  - Client ID: `KEYCLOAK_CLIENT_ID` (default `core-api`)
  - Client secret: `KEYCLOAK_CLIENT_SECRET`
  - Session secret: `NEXTAUTH_SECRET`
  - Implementation: `frontend/auth.ts`
- Dual-URL pattern for container networking:
  - `KEYCLOAK_ISSUER` — browser/public (localhost:8085)
  - `KEYCLOAK_ISSUER_INTERNAL` — server-side token refresh (keycloak:8080)
- Refresh-token grant handled in JWT callback; access token exposed to client via session callback
- Note: Dev env uses port **3100** in some configs (MCP server holds 3000); CORS + Keycloak redirects include both

## Email & Notifications

**SMTP (application email):**
- Host/port: `SMTP_HOST`, `SMTP_PORT` (`mailhog:1025` dev; SendGrid/SES 587 prod)
- Auth: `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_USERNAME`, `SMTP_PASSWORD`
- Client: `spring-boot-starter-mail`
- Implementation: `core-java/src/main/java/uk/jtoye/core/notification/`
- Failure mode: Resilience4j circuit breaker (`email` instance, 50% threshold, 60s wait) + up-to-3 retries
- Toggles: `NOTIFICATION_EMAIL_ENABLED`, tracking pixel via `NOTIFICATION_EMAIL_TRACKING_BASE_URL`, sender `NOTIFICATION_EMAIL_FROM`

**Mailhog (dev SMTP sink):**
- `mailhog/mailhog:v1.0.1` (`docker-compose.full-stack.yml:322`)
- SMTP at 1025, Web UI at 8025, no auth, no TLS — captures all outbound mail

**Alertmanager email routing (new in v2.1 / phase 9):**
- `prom/alertmanager:v0.27.0` on port 9093 (`infra/monitoring/docker-compose.monitoring.yml:62-89`)
- Config rendered at container start from `infra/monitoring/alertmanager/alertmanager.yml.tmpl` by `entrypoint.sh` (Alertmanager has no native env-var substitution — sed placeholder approach)
- Default route targets Mailhog on `jtoye-network`:
  - `ALERTMANAGER_SMTP_SMARTHOST` (default `mailhog:1025`)
  - `ALERTMANAGER_SMTP_FROM` (default `alerts@jtoye.local`)
  - `ALERTMANAGER_SMTP_TO` (default `ops@jtoye.local`)
  - `ALERTMANAGER_SMTP_REQUIRE_TLS` (default `false`)
- Single receiver `email-default`, `group_by: [alertname, service]`, `repeat_interval: 12h`
- Alert rules sourced from `infra/monitoring/prometheus/alerts.yml`
- Rendered config (`alertmanager.yml`) is gitignored

## Monitoring & Observability

**Prometheus (`prom/prometheus:v2.48.0`):**
- Host port 9091 (container 9090)
- Config: `infra/monitoring/prometheus/prometheus.yml.tmpl` + `alerts.yml`. **The `.tmpl` is the
  source**; `entrypoint.sh` renders it to `/etc/prometheus/prometheus.yml` at container start
  (27-00). There is no checked-in `prometheus.yml` — editing that path creates a file the running
  Prometheus ignores.
- 30d TSDB retention
- Scrape targets: core-java (`/actuator/prometheus`), redis-exporter:9121, postgres-exporter:9187, RabbitMQ Prometheus plugin

**Grafana (`grafana/grafana:10.2.2`):**
- Host port 3001 (container 3000)
- Credentials: `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` (required — compose fails without it)
- Provisioned datasource + dashboards from `infra/monitoring/grafana/provisioning/` and `dashboards/`
- Plugin: `grafana-piechart-panel`

**Alertmanager (`prom/alertmanager:v0.27.0`):** see Email & Notifications above

**Exporters:**
- `oliver006/redis_exporter:v1.58.0` — port 9121
- `prometheuscommunity/postgres-exporter:v0.15.0` — port 9187 (uses dedicated `POSTGRES_EXPORTER_USER` with SSL)
- RabbitMQ Prometheus plugin (built-in via `rabbitmq_prometheus`)

**Spring Actuator:**
- `/actuator/health` — DB, Redis, RabbitMQ, disk, etc.
- `/actuator/prometheus` — Micrometer metrics (JVM, HTTP, business metrics)
- `/actuator/info`

**Distributed Tracing:**
- Micrometer Tracing with Brave bridge → Zipkin reporter (`core-java/build.gradle.kts:53-54`)
- Endpoint: `ZIPKIN_ENDPOINT` (default `http://localhost:9411/api/v2/spans`)
- Sampling: `TRACING_PROBABILITY=0.1`
- Log correlation: `%X{traceId}`, `%X{spanId}` MDC fields

**Logging:**
- Core Java: SLF4J + Logback (Spring Boot default); levels via `LOG_LEVEL`, `SQL_LOG_LEVEL`, `SECURITY_LOG_LEVEL`
- Edge Go: `go.uber.org/zap` structured JSON logs

**Error Tracking:**
- **Sentry: NOT installed** — no dependency in `core-java/build.gradle.kts`, `frontend/package.json`, or `edge-go/go.mod`. Error surfacing is via Prometheus alerts + Alertmanager email + application logs.

## CI/CD & Deployment

**Hosting:**
- Local dev: Docker Compose (`docker-compose.full-stack.yml` + `infra/monitoring/docker-compose.monitoring.yml`)
- Production: Kubernetes — manifests in `k8s/`
- Supported: AWS EKS, GCP GKE, on-prem Kubernetes

**Container Images built by repo:**
- core-java: Multi-stage, JDK 21 base (`core-java/Dockerfile`)
- edge-go: Multi-stage Go, scratch runtime, binary `/edge`, port 8080 (`edge-go/Dockerfile`)
- frontend: Node.js base, Next.js standalone output (`frontend/Dockerfile`)

**GitHub Actions (`.github/workflows/`):**
- `ci-cd.yaml` — build + test pipeline
- `gitleaks.yml` — secret scanning (added during post-audit hardening in v2.1)

## Webhooks & Callbacks

**Incoming webhooks:**

- Stripe webhooks
  - Endpoint: `POST /api/payments/webhook` on core-java
  - Events: `payment_intent.succeeded`, `payment_intent.payment_failed`, `charge.refunded`
  - Verification: HMAC signature via `STRIPE_WEBHOOK_SECRET`
  - Persistence: `payment_event_outbox` table (V31, RLS fixed in V33) for idempotent replay

- WhatsApp Cloud API webhook (gated, currently edge-only stub)
  - Endpoint: `POST /api/v1/webhooks/whatsapp` on edge-go (`edge-go/cmd/edge/main.go:215`)
  - Signature: `X-Hub-Signature-256` HMAC-SHA256 verified against `WHATSAPP_APP_SECRET` (fail-closed — refuses webhook if secret unset; previously would silently skip, fixed in P1 audit)
  - Parser: `edge-go/internal/whatsapp/parser.go`
  - Default shop: `WHATSAPP_DEFAULT_SHOP_ID` (if unset, handler errors rather than fabricating tenant)
  - Status: Low-priority rollout — handler exists but SMS/WhatsApp rollout gated per `v2.1-REQUIREMENTS.md`; no `WHATSAPP_ENABLED` env toggle currently in code (feature is always reachable when `WHATSAPP_APP_SECRET` is configured)
  - Failure mode: Returns 200 on parse failures to prevent Meta retry loops; rejects missing/invalid signatures with 401

**Outgoing:**
- Order and notification events published to RabbitMQ (internal fabric)
- Email notifications via SMTP
- Alertmanager → SMTP (email) — no Slack/PagerDuty webhooks currently configured
- No outbound HTTP webhooks to customer systems at this time

## Third-Party API Clients

**Stripe Java SDK:**
- Location: `core-java/src/main/java/uk/jtoye/core/payment/`
- `Stripe.apiKey` set in `@PostConstruct` of PaymentService
- PaymentIntent creation, webhook construction, refunds

**AWS SDK v2 (S3):**
- Platform BOM 2.25.60, `s3` client module
- Works against AWS S3 or MinIO via `S3_ENDPOINT` override + path-style addressing
- Operations: upload/download/delete product/shop images

**Anthropic Claude:**
- No dedicated SDK — direct HTTP via Spring `WebClient` (WebFlux)
- Location: `core-java/src/main/java/uk/jtoye/core/ai/`

**Ollama:**
- HTTP via `WebClient`, `POST /api/generate` with base64-encoded image + prompt
- Location: `core-java/src/main/java/uk/jtoye/core/ai/`

## Environment Configuration Summary

**Required (production):**
- Database: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- Keycloak: `KC_ISSUER_URI`, `KEYCLOAK_ISSUER`, `KEYCLOAK_ISSUER_INTERNAL`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`
- Redis: `REDIS_HOST`, `REDIS_PASSWORD`
- RabbitMQ: `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`
- STOMP: `STOMP_BROKER_MODE=relay`, `STOMP_RELAY_HOST`, `STOMP_RELAY_PORT`, relay credentials
- Stripe: `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`
- AI: `AI_PROVIDER`, `OLLAMA_URL`, `OLLAMA_MODEL`, `ANTHROPIC_API_KEY`
- Storage: `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET`, `S3_PUBLIC_URL`
- Session: `NEXTAUTH_SECRET`, `NEXTAUTH_URL`
- CORS: `CORS_ALLOWED_ORIGINS` (must include both `:3000` and `:3100` for dev)
- WhatsApp (if enabled): `WHATSAPP_APP_SECRET`, `WHATSAPP_DEFAULT_SHOP_ID`
- Monitoring: `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`, `POSTGRES_EXPORTER_USER`, `POSTGRES_EXPORTER_PASSWORD`, `ALERTMANAGER_SMTP_*`

**Optional:**
- SMTP: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`
- Notifications: `NOTIFICATION_EMAIL_ENABLED`, `NOTIFICATION_EMAIL_FROM`, `NOTIFICATION_EMAIL_TRACKING_BASE_URL`
- Observability: `LOG_LEVEL`, `SQL_LOG_LEVEL`, `SECURITY_LOG_LEVEL`, `TRACING_PROBABILITY`, `ZIPKIN_ENDPOINT`
- Rate limiting: `RATE_LIMIT_ENABLED`, `RATE_LIMIT_PER_MINUTE`, `RATE_LIMIT_BURST`
- Cleanup jobs: `CLEANUP_STALE_DRAFT_HOURS`, `CLEANUP_ORPHANED_IMAGE_DAYS`

**Secrets Location:**
- `.env` (repo root, gitignored)
- `frontend/.env.local` (gitignored; template `frontend/.env.local.example`)
- Docker secrets `/run/secrets/` in Swarm/K8s
- Production: AWS Secrets Manager / HashiCorp Vault
- Enforcement: `gitleaks` CI workflow blocks accidental secret commits

---

*Integration audit: 2026-04-18*
