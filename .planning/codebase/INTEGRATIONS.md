# External Integrations

**Analysis Date:** 2026-04-07

## APIs & External Services

**Payment Processing:**
- Stripe - Online and COD payment processing
  - SDK: `com.stripe:stripe-java:28.2.0`
  - Implementation: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java`
  - Auth: `STRIPE_API_KEY` (sk_test_... or sk_live_...)
  - Webhook secret: `STRIPE_WEBHOOK_SECRET` (whsec_...)
  - Circuit breaker: Enabled (failure threshold 50%, wait 30s)
  - Fallback: Supports COD (cash on delivery) if Stripe unavailable

**AI & Image Analysis:**
- Ollama - Local LLM for image recognition (default)
  - URL: `OLLAMA_URL` (http://localhost:11434 or external)
  - Model: `OLLAMA_MODEL` (gemma3:12b recommended)
  - Implementation: `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java`
  - Uses: Food identification, ingredient extraction, dietary info
  - Circuit breaker: Enabled (failure threshold 50%, wait 60s, max 2 retries)
  - Fallback: Can switch to Anthropic Claude if Ollama unavailable

- Anthropic Claude - Cloud-based vision alternative
  - API Key: `ANTHROPIC_API_KEY` (sk-... format)
  - Model: Claude Sonnet 4 (claude-sonnet-4-20250514)
  - Provider switch: `AI_PROVIDER=anthropic` (default: ollama)
  - Cost: Per-call pricing for vision analysis

## Data Storage

**Databases:**

- PostgreSQL 15
  - Connection: `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`
  - Env vars: DB_HOST, DB_PORT, DB_USER, DB_PASSWORD
  - Default: localhost:5432/jtoye (dev), external managed service (prod)
  - Client: Spring Data JPA + Hibernate ORM
  - Migrations: Flyway (location: `core-java/src/main/resources/db/migration/`)
  - Multi-tenancy: Row-level security (RLS policies in database)
  - Schema version: V28 (shop configuration)

**File Storage:**

- MinIO (S3-compatible, local dev)
  - Endpoint: `S3_ENDPOINT` (http://minio:9000 dev, AWS S3 prod)
  - Credentials: `S3_ACCESS_KEY`, `S3_SECRET_KEY` (minio default: minioadmin/minioadmin)
  - Bucket: `S3_BUCKET` (jtoye-images)
  - Public URL: `S3_PUBLIC_URL` (http://localhost:9000/jtoye-images dev, CloudFront prod)
  - Max file size: 5MB (configurable)
  - Allowed types: image/jpeg, image/png, image/webp, image/gif
  - SDK: AWS SDK v2 (software.amazon.awssdk:s3)
  - Implementation: `core-java/src/main/java/uk/jtoye/core/storage/` (storage service)

**Caching:**

- Redis 7
  - Connection: `REDIS_HOST:REDIS_PORT` (default: localhost:6379)
  - Auth: `REDIS_PASSWORD` (required in prod, empty in dev)
  - Spring Cache type: redis (via spring-boot-starter-data-redis)
  - Client: Lettuce connection pooling
  - TTL: Application-specific (rate limits, session tokens)
  - Timeout: 2s (dev), 3s (prod)

## Message Queue

**RabbitMQ (AMQP):**
- Connection: `RABBITMQ_HOST:RABBITMQ_PORT` (default: localhost:5672)
- Auth: `RABBITMQ_USER`, `RABBITMQ_PASSWORD`
- Management UI: http://localhost:15672 (dev)
- SDK: spring-boot-starter-amqp
- Use cases:
  - Order event publishing (OrderEventPublisher)
  - Asynchronous notifications
  - Dead-letter queue (DLQ) for failed messages
  - Resilience: DLQ captures failures, cleanup jobs process stale messages
- Exchanges/Queues: Declared dynamically at startup

## Authentication & Identity

**Auth Provider:**
- Keycloak 24.0.5 (Open-source identity provider)
  - Server URL: `KC_ISSUER_URI` (http://keycloak:8080/realms/jtoye-dev dev, external prod)
  - Public URL: `KEYCLOAK_ISSUER` (http://localhost:8085/realms/jtoye-dev for browser)
  - Internal URL: `KEYCLOAK_ISSUER_INTERNAL` (http://keycloak:8080/realms/jtoye-dev for backend)
  - Realm: jtoye-dev
  - Admin: `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`

**Backend (Spring Security):**
- OAuth2 Resource Server configuration
  - Token validation: JWT issued by Keycloak
  - Location: `core-java/src/main/java/uk/jtoye/core/config/SecurityConfig.java`
  - Token extraction: Bearer token from Authorization header
  - Claims: Used for tenant context and user identity

**Frontend (Next-Auth):**
- Provider: Keycloak
  - Client ID: `KEYCLOAK_CLIENT_ID` (core-api)
  - Client Secret: `KEYCLOAK_CLIENT_SECRET`
  - Session secret: `NEXTAUTH_SECRET` (generated, required)
  - Implementation: `frontend/auth.ts`
  - Features:
    - Token refresh on expiry (refresh_token grant)
    - JWT callback stores access token in session
    - NextAuth session callback provides token to app
  - Endpoints:
    - Auth: `${KEYCLOAK_ISSUER}/protocol/openid-connect/auth`
    - Token: `${KEYCLOAK_ISSUER}/protocol/openid-connect/token`
    - UserInfo: `${KEYCLOAK_ISSUER}/protocol/openid-connect/userinfo`

## Email & Notifications

**SMTP Configuration:**
- Host: `SMTP_HOST` (localhost:mailhog dev, SendGrid/AWS SES prod)
- Port: `SMTP_PORT` (1025 mailhog dev, 587 prod TLS)
- Auth: `SMTP_AUTH`, `SMTP_STARTTLS` flags
- Credentials: `SMTP_USERNAME`, `SMTP_PASSWORD`
- Spring Mail: spring-boot-starter-mail
- Implementation: `core-java/src/main/java/uk/jtoye/core/notification/EmailService.java`
- Circuit breaker: Email failures (threshold 50%, wait 60s, retry max 3 times)

**Local Testing:**
- Mailhog: `mailhog/mailhog:v1.0.1`
  - SMTP: localhost:1025
  - Web UI: http://localhost:8025
  - No auth required, captures all sent emails

**Email Notifications:**
- Feature toggle: `NOTIFICATION_EMAIL_ENABLED` (true/false)
- Tracking base URL: `NOTIFICATION_EMAIL_TRACKING_BASE_URL` (for pixel tracking)
- From address: `NOTIFICATION_EMAIL_FROM` (noreply@jtoye.uk default)
- Use cases: Order confirmations, password resets, delivery updates

## Monitoring & Observability

**Metrics Export:**
- Prometheus format
  - Endpoint: `/actuator/prometheus`
  - Micrometer exporter: micrometer-registry-prometheus
  - Metrics: JVM, HTTP requests, business metrics (orders, payments)

**Health & Liveness:**
- Spring Actuator
  - Endpoint: `/actuator/health`
  - Details: Shown when authorized (user has role)
  - Components monitored: DB, Redis, RabbitMQ, Keycloak

**Distributed Tracing:**
- Zipkin backend with Brave instrumentation
  - Endpoint: `ZIPKIN_ENDPOINT` (http://localhost:9411/api/v2/spans)
  - Sampling rate: `TRACING_PROBABILITY` (10% default, increase in dev)
  - Libraries: micrometer-tracing-bridge-brave, zipkin-reporter-brave
  - Span tags: traceId, spanId in logs (pattern: %X{traceId}, %X{spanId})

**Logging:**
- Spring Boot native logging (SLF4J + Logback)
  - Log levels: `LOG_LEVEL`, `SQL_LOG_LEVEL`, `SECURITY_LOG_LEVEL`
  - Pattern: Includes application name, trace ID, span ID
  - Structured logging: Uber Zap for edge-go

## CI/CD & Deployment

**Hosting:**
- Docker Compose - Local development stack (full-stack.yml)
- Kubernetes - Production cluster (manifests in `k8s/`)
- Supported platforms: AWS EKS, GCP GKE, on-premise Kubernetes

**Container Images:**
- core-java: Multi-stage Dockerfile, JDK 21 base, optimized for Spring Boot
- edge-go: Multi-stage Golang, scratch runtime (<15MB)
- frontend: Node.js base, standalone Next.js output

**GitHub CI/CD:**
- Workflows: `.github/workflows/` (see copilot-instructions.md)
- Status: Check latest commits for active pipeline

## Environment Configuration

**Required Environment Variables (Production):**

Database:
- `DB_HOST` - PostgreSQL hostname
- `DB_PORT` - PostgreSQL port (5432)
- `DB_NAME` - Database name (jtoye)
- `DB_USER` - Database application user (jtoye_app)
- `DB_PASSWORD` - Database password

Keycloak:
- `KC_ISSUER_URI` - JWT issuer (internal, backend only)
- `KEYCLOAK_ISSUER` - Public issuer URL (browser and API)
- `KEYCLOAK_CLIENT_ID` - Frontend OAuth2 client (core-api)
- `KEYCLOAK_CLIENT_SECRET` - Client secret

Redis:
- `REDIS_HOST` - Redis hostname
- `REDIS_PASSWORD` - Redis password (required in prod)

RabbitMQ:
- `RABBITMQ_HOST` - RabbitMQ hostname
- `RABBITMQ_USER` - User (default: jtoye)
- `RABBITMQ_PASSWORD` - Password

Payment (Stripe):
- `STRIPE_API_KEY` - Secret API key (sk_live_...)
- `STRIPE_WEBHOOK_SECRET` - Webhook signing secret
- `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` - Public key (sk_test_... or sk_live_...)

AI:
- `AI_PROVIDER` - "ollama" or "anthropic"
- `OLLAMA_URL` - Ollama endpoint (if provider=ollama)
- `OLLAMA_MODEL` - Model name (gemma3:12b)
- `ANTHROPIC_API_KEY` - Claude API key (if provider=anthropic)

Storage:
- `S3_ENDPOINT` - S3 API endpoint
- `S3_ACCESS_KEY` - AWS or MinIO access key
- `S3_SECRET_KEY` - AWS or MinIO secret key
- `S3_BUCKET` - Bucket name (jtoye-images)
- `S3_PUBLIC_URL` - Public-facing URL for images

Frontend/Session:
- `NEXTAUTH_SECRET` - NextAuth session encryption key
- `NEXTAUTH_URL` - Canonical NextAuth URL (http://localhost:3000 dev)

CORS:
- `CORS_ALLOWED_ORIGINS` - Comma-separated origin allowlist

**Optional Variables:**

Email:
- `SMTP_HOST` - SMTP server (mailhog dev)
- `SMTP_PORT` - SMTP port (1025 mailhog, 587 prod)
- `SMTP_USERNAME` - SMTP user
- `SMTP_PASSWORD` - SMTP password
- `SMTP_AUTH` - Enable authentication (false mailhog, true prod)
- `SMTP_STARTTLS` - Enable STARTTLS (false mailhog, true prod)
- `NOTIFICATION_EMAIL_ENABLED` - Feature toggle (true)
- `NOTIFICATION_EMAIL_FROM` - From address (noreply@jtoye.uk)
- `NOTIFICATION_EMAIL_TRACKING_BASE_URL` - Pixel tracking domain

Observability:
- `LOG_LEVEL` - Root log level (INFO)
- `SQL_LOG_LEVEL` - Hibernate SQL logging (WARN)
- `SECURITY_LOG_LEVEL` - Spring Security logging (INFO)
- `TRACING_PROBABILITY` - Distributed trace sampling (0.1 = 10%)
- `ZIPKIN_ENDPOINT` - Zipkin API endpoint

Rate Limiting:
- `RATE_LIMIT_ENABLED` - Feature toggle (true)
- `RATE_LIMIT_PER_MINUTE` - Requests per minute (100)
- `RATE_LIMIT_BURST` - Burst capacity (20)

Cleanup:
- `CLEANUP_STALE_DRAFT_HOURS` - Delete drafts older than N hours (24)
- `CLEANUP_ORPHANED_IMAGE_DAYS` - Delete unused images older than N days (7)

**Secrets Location:**
- `.env` file (development, NOT committed to git)
- `.env.example` - Template with all required keys
- Docker secrets - Mounted at `/run/secrets/` (Kubernetes/Docker Swarm)
- AWS Secrets Manager / HashiCorp Vault - Production secret management

## Webhooks & Callbacks

**Incoming Webhooks:**

- Stripe Webhooks
  - Endpoint: `POST /api/payments/webhook` (backend)
  - Event types: payment_intent.succeeded, payment_intent.payment_failed, charge.refunded
  - Verification: Signature validation via `STRIPE_WEBHOOK_SECRET`
  - Implementation: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java`

**Outgoing Webhooks:**
- Order events published to RabbitMQ (internal message queue)
- Email notifications sent via SMTP
- No external webhook callbacks at this time

## Third-Party API Clients

**Stripe Java SDK:**
- Location: `core-java/src/main/java/uk/jtoye/core/payment/`
- Initialization: Stripe.apiKey set at service startup (@PostConstruct)
- Usage: PaymentIntent creation, payment method handling
- Fallback: COD available if Stripe unavailable

**AWS SDK (S3):**
- Platform: software.amazon.awssdk:s3 (v2.25.60 BOM)
- Works with: AWS S3 or MinIO (S3-compatible API)
- Initialization: Configured at application startup
- Endpoint override: `S3_ENDPOINT` for MinIO
- Bucket operations: Upload, download, delete images

**Anthropic Claude SDK:**
- Not directly included; uses WebClient (Spring WebFlux)
- HTTP requests to Claude API endpoint
- Implementation: `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java`

---

*Integration audit: 2026-04-07*
