# Technology Stack

**Analysis Date:** 2026-04-07

## Languages

**Primary:**
- Java 21 - Core API (Spring Boot 3.4.2)
- TypeScript 5 - Frontend (Next.js 16.2.2, React 19)
- Go 1.22 - Edge API gateway (Gin)

**Secondary:**
- SQL (PostgreSQL) - Database migrations via Flyway
- YAML - Configuration management

## Runtime

**Environment:**
- JVM (Java 21) - Core API execution
- Node.js 20+ - Frontend build and runtime
- Go 1.22 runtime - Edge gateway
- PostgreSQL 15 - Database

**Package Manager:**
- Gradle 8.10+ (Kotlin DSL) - Java/Spring Boot build
- npm - Node.js dependencies
- go mod - Go dependencies

**Lockfile:**
- Gradle: Present (`gradle-wrapper` properties)
- npm: package-lock.json (implicit)
- Go: go.sum

## Frameworks

**Core (Backend):**
- Spring Boot 3.4.2 - Web framework, dependency injection, auto-configuration
- Spring Data JPA - ORM and database abstraction
- Spring Security - Authentication and authorization
- Spring OAuth2 Resource Server - JWT/OIDC token validation
- Spring AOP - Aspect-oriented programming
- Spring Cache - Distributed caching with Redis
- Spring AMQP - RabbitMQ message queue integration

**API & Observability:**
- Spring Actuator - Metrics and health endpoints
- SpringDoc OpenAPI 2.8.6 - Swagger/OpenAPI documentation
- Micrometer Prometheus - Metrics export
- Micrometer Tracing (Brave/Zipkin) - Distributed tracing

**Frontend:**
- Next.js 16.2.2 - React framework with file-based routing
- React 19 - UI component library
- React Hook Form 7.69.0 - Form state management
- Next-Auth 5.0.0-beta.30 - Authentication middleware
- TailwindCSS 3.4.1 - Utility-first CSS framework
- Radix UI - Headless component library
- Zod 4.2.1 - Schema validation

**Edge Gateway:**
- Gin v1.10.0 - HTTP routing and middleware
- golang-jwt/jwt v5 - JWT validation
- uber/zap - Structured logging
- sony/gobreaker - Circuit breaker pattern

**Testing:**
- JUnit 5 - Java test framework
- Testcontainers 1.21.3 - Docker-based integration testing
- Spring Boot Test - Testing utilities and test containers
- Jest 29.7.0 - JavaScript test runner
- @testing-library/react - React component testing
- @playwright/test 1.59.1 - E2E browser automation

**Build & Development:**
- Spring Boot Gradle Plugin 3.4.2 - JAR packaging
- Flyway - Database migration management
- Lombok - Boilerplate reduction (code generation)
- MapStruct 1.5.5 - Type-safe DTO mapping

## Key Dependencies

**Critical (Backend):**
- PostgreSQL JDBC Driver 42.7.3 - Database connectivity
- Hibernate ORM 3.4.2 (via Spring Boot) - JPA implementation
- Hibernate Envers - Audit history tracking
- AWS SDK v2 (2.25.60) - S3 API for image storage

**Critical (Frontend):**
- Stripe React/JS 6.1.0, 9.0.1 - Payment processing UI integration
- Axios 1.13.2 - HTTP client for API calls
- Framer Motion 12.23.26 - Animation library
- Recharts 3.8.1 - Charts and data visualization

**Infrastructure:**
- Redis 7 - Session and cache store
- RabbitMQ 3.12 - Message queue (AMQP)
- Keycloak 24.0.5 - Identity provider (OIDC/OAuth2)
- MinIO (latest) - S3-compatible object storage for images
- Ollama (latest) - Local LLM for image analysis
- Mailhog v1.0.1 - Local SMTP for email testing

**Resilience & Rate Limiting:**
- Resilience4j 2.2.0 - Circuit breakers and retry logic
  - Circuit breakers: stripe, email, ai services
  - Retry: email, ai services
- Bucket4j 8.10.1 - Token bucket rate limiting

**Payment Processing:**
- Stripe Java SDK 28.2.0 - Payment intent creation and webhook handling
- OpenPDF 2.0.3 - PDF generation for allergen labels

**Caching & Session:**
- Spring Data Redis (Lettuce) - Redis connection pooling
  - Default pool: 8 active, 8 idle, 2 min-idle connections
  - Prod pool: 20 active, 10 idle, 5 min-idle connections

## Configuration

**Environment Variables:**
- `.env` file (required for docker-compose)
- Environment variable precedence: Spring profiles (dev, test, staging, prod)
- Config location: `core-java/src/main/resources/application*.yml`

**Key Configuration Files:**
- `application.yml` - Base configuration (all profiles)
- `application-dev.yml` - Development profile (localhost defaults)
- `application-test.yml` - Test profile (H2 in-memory, testcontainers)
- `application-staging.yml` - Staging production-like settings
- `application-prod.yml` - Production hardened settings (no SQL logging, higher pool sizes)

**Spring Profiles:**
- `dev` (default in docker-compose)
- `test` (for unit/integration tests)
- `staging` (pre-production validation)
- `prod` (hardened security and performance)

**Database Configuration:**
- Flyway migrations: `core-java/src/main/resources/db/migration/`
- Migration strategy: Versioned SQL files (V1__, V2__, etc.)
- Current schema version: V28 (shop configuration)

**Frontend Configuration:**
- Next.js config: `frontend/next.config.mjs` (standalone output, image remotePatterns)
- TypeScript config: `frontend/tsconfig.json`
- ESLint: `frontend/.eslintrc.json`

**Edge Gateway Configuration:**
- Dockerfile: `edge-go/Dockerfile` (multi-stage, scratch-based runtime)
- Binary output: `/edge` executable
- Port: 8080 (customizable via PORT env var)

## Platform Requirements

**Development:**
- Docker & Docker Compose 1.40+ (for local stack)
- Java 21 JDK
- Node.js 20+
- Go 1.22+
- Git

**Build:**
- Gradle 8.10+ (included via wrapper)
- npm (included in Node.js)
- Docker (for building multi-stage images)

**Runtime (Production):**
- Kubernetes (recommended) - See `k8s/` directory for manifests
- Docker container runtime
- PostgreSQL 15+ database
- Redis 7+ (external or managed service)
- RabbitMQ 3.12+ (external or managed service)
- Keycloak 24.0+ (external identity provider)
- AWS S3 (or S3-compatible storage like MinIO)
- SMTP server (SendGrid, AWS SES, etc.)

**Tested Versions:**
- Spring Boot: 3.4.2 (Java 21)
- PostgreSQL: 15-alpine
- Keycloak: 24.0.5
- Redis: 7-alpine
- RabbitMQ: 3.12-management-alpine
- MinIO: latest
- Go: 1.22-alpine
- Node.js: 20+
- Next.js: 16.2.2

## Performance Tuning

**Database:**
- Connection pooling: HikariCP
  - Dev: 20 max, 5 min-idle
  - Prod: 50 max, 10 min-idle
- Batch insert/update: Hibernate batch_size=20 (prod: 50)
- Query timeout: 30s
- Idle timeout: 10m

**Cache:**
- Redis timeout: 2s (dev), 3s (prod)
- Lettuce pool: 8 active, 8 idle (dev), 20 active, 10 idle (prod)

**Rate Limiting:**
- Default: 100 requests per minute per tenant
- Burst capacity: 20 requests
- Enabled by default (RATE_LIMIT_ENABLED=true)

**Tracing:**
- Sampling probability: 10% default (increase in dev)
- Zipkin endpoint: http://localhost:9411/api/v2/spans

---

*Stack analysis: 2026-04-07*
