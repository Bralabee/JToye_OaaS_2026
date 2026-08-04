# J'Toye OaaS

**Multi-tenant SaaS platform for UK retail management with Row-Level Security**

[![Version](https://img.shields.io/badge/version-2.3.0--dev-blue.svg)](docs/CHANGELOG.md)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/Bralabee/JToye_OaaS_2026/actions)
[![Tests](https://img.shields.io/badge/tests-2207%20logical%20invocations-brightgreen.svg)](docs/metrics.json)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 Overview

J'Toye OaaS (Operations as a Service) is a production-ready, multi-tenant SaaS platform designed for retail operations. Built with enterprise-grade security featuring PostgreSQL Row-Level Security (RLS), full CRUD operations, and modern authentication.

### Tech Stack

| Layer | Technology |
|-------|------------|
| **Frontend** | Next.js 16, React 19, TypeScript, Tailwind CSS, NextAuth.js v5 |
| **Backend** | Spring Boot 3, Java 21, MapStruct 1.6.3, Redis Caching, Spring State Machine |
| **Edge** | Go 1.26, Gin, Circuit Breakers, Rate Limiting |
| **Database** | PostgreSQL 15 with Row-Level Security (RLS) |
| **Auth** | Keycloak 24 (OAuth2/OIDC) |
| **Infrastructure** | Docker, Kubernetes, Redis, RabbitMQ |

### Key Features

✅ **Multi-Tenancy** - PostgreSQL RLS with JWT-based isolation
✅ **Full CRUD** - 27 REST controllers (Shops, Products, Orders, Customers, Payments, Financial Transactions, Sync, Media, Webhooks, Onboarding, etc.)
✅ **Service Layer Architecture** - Clean separation with Service-Repository pattern
✅ **Type-Safe Mapping** - MapStruct for compile-time DTO conversion
✅ **Redis Caching** - Tenant-aware caching with performance boost
✅ **Batch Sync** - High-volume Edge-to-Core data synchronization
✅ **State Machine** - Order workflow management
✅ **Rate Limiting** - Tenant-aware Bucket4j + Redis enforcement
✅ **Audit Trail** - Hibernate Envers on all entities
✅ **Modern UI** - 16 responsive dashboard routes with animations
✅ **Production Ready** - Docker, Kubernetes, CI/CD pipeline

---

## 🚀 Quick Start

### Option 1: Docker (recommended)

Everything runs in containers. You need a `.env` first — `docker-compose.full-stack.yml` has 18
hard-required variables and will not even render its config without them.

```bash
# 1. Create your .env from the template and fill in every CHANGE_ME value
cp .env.example .env
bash scripts/verify-env.sh          # fails loudly on anything missing or weak

# 2. Start the stack
docker compose -f docker-compose.full-stack.yml up -d --build
```

> Compose **v2** (`docker compose`, a docker subcommand). The standalone `docker-compose` v1 binary
> is not installed on supported setups and exits `127 command not found`.

**Access:**
- UI: http://localhost:3000
- API: http://localhost:9090 (business endpoints are under `/api/v1` — see below)
- Keycloak: http://localhost:8085

**Login:** `tenant-a-user` / the value of `KC_SEED_USER_PASSWORD` (from your `.env`).
The sign-in page is a single **Sign in with Keycloak** button — you type credentials on Keycloak's
page, not on ours.

### Option 2: Local Development (backend outside Docker)

Runs the Core API from source against the containerised backing services.

```bash
# 1. Copy environment templates
cp .env.example .env                  # fill in the CHANGE_ME values
cp frontend/.env.local.example frontend/.env.local
cp core-java/.env.example core-java/.env
cp edge-go/.env.example edge-go/.env

# 2. Start the backing services only (Postgres, Keycloak, Redis, RabbitMQ)
docker compose -f docker-compose.full-stack.yml up -d postgres keycloak redis rabbitmq

# 3. Start backend (reads .env; runs as jtoye_app, never the jtoye superuser)
./scripts/run-app.sh

# 4. Start frontend (new terminal)
cd frontend && npm install && npm run dev
```

> Use the full-stack compose file for step 2, **not** `cd infra && docker compose up -d`.
> `infra/docker-compose.yml` declares its own `jtoye-postgres` / `jtoye-keycloak` containers on the
> same host ports (5433, 8085), so it collides with the canonical stack, and it starts neither Redis
> nor RabbitMQ — which `application.yml` expects on `localhost`.

📖 **Detailed Guide:** See [docs/guides/QUICK_START.md](docs/guides/QUICK_START.md)

---

## 📚 Documentation

### Getting Started
- **[QUICK_START.md](docs/guides/QUICK_START.md)** - Get running in minutes (all platforms)
- **[ENVIRONMENT_SETUP.md](docs/guides/ENVIRONMENT_SETUP.md)** - Environment configuration guide
- **[DOCKER_QUICK_START.md](docs/guides/DOCKER_QUICK_START.md)** - Docker-specific instructions

### Development
- **[USER_GUIDE.md](docs/guides/USER_GUIDE.md)** - How to use the application
- **[TESTING.md](docs/guides/TESTING.md)** - Testing guide with examples
- **[CONFIGURATION.md](docs/config/CONFIGURATION.md)** - Detailed configuration reference

### Deployment & Operations
- **[DEPLOYMENT_GUIDE.md](docs/guides/DEPLOYMENT_GUIDE.md)** - Production deployment
- **[PRODUCTION_READINESS_REPORT.md](docs/reports/PRODUCTION_READINESS_REPORT.md)** - Production checklist
- **[SECURITY_AUDIT_REPORT.md](docs/reports/SECURITY_AUDIT_REPORT.md)** - Security assessment

### Architecture
- **[AI_CONTEXT.md](docs/AI_CONTEXT.md)** - System context and architecture
- **[DOCUMENTATION_INDEX.md](docs/DOCUMENTATION_INDEX.md)** - Complete docs index
- **[CHANGELOG.md](docs/CHANGELOG.md)** - Version history

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Browser                                                     │
│  └─ Next.js 16 Frontend (Port 3000)                        │
│     └─ NextAuth.js v5 ← → Keycloak (Port 8085)            │
└──────────────────────┬────────────────────────────────────┘
                       │
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  Backend Services                                            │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  Core Java   │←→│  Edge Go     │←→│  Redis       │     │
│  │  Port 9090   │  │  Port 8089   │  │  Port 6379   │     │
│  │              │  │              │  │              │     │
│  │  Spring Boot │  │  Rate Limit  │  │  Cache       │     │
│  │  JWT Auth    │  │  Circuit     │  │              │     │
│  │  RLS         │  │  Breaker     │  │              │     │
│  └──────┬───────┘  └──────────────┘  └──────────────┘     │
│         │                                                   │
│         ↓                                                   │
│  ┌──────────────────────────────────────┐                  │
│  │  PostgreSQL 15 (Port 5433)          │                  │
│  │  - Row-Level Security (RLS)         │                  │
│  │  - Multi-tenant isolation           │                  │
│  │  - Audit trails (Envers)            │                  │
│  └──────────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

**Security Model:**
- JWT tokens contain `tenant_id` claim
- Every database query runs with `SET LOCAL app.current_tenant_id`
- PostgreSQL RLS enforces data isolation at database level
- No manual `WHERE tenant_id = ?` needed in code

---

## 🎯 What's Included

### Core Features

**REST API (27 controllers) — representative endpoints.** The business API is served under
`/api/v1`; the prefix is applied by `WebConfig.configurePathMatch`, so it does not appear in any
`@RequestMapping` and requesting the bare path returns `404`:

- `/api/v1/shops` - Retail location management
- `/api/v1/products` - Product catalog with allergen tracking
- `/api/v1/orders` - Order lifecycle with state machine
- `/api/v1/customers` - Customer profiles
- `/api/v1/financial-transactions` - Transaction tracking
- `/api/v1/sync/batch` - High-volume data synchronization
- `/api/v1/onboarding` - Vendor onboarding state machine

Not under the prefix (these 404 if you add it):

- `/health`, `/actuator/**` - Health and metrics
- `/v3/api-docs`, `/swagger-ui.html` - OpenAPI surface
- `/public/**` - Public storefront (also reachable at `/api/v1/public/**`)

**Frontend Dashboards:**
- Dashboard - Overview metrics
- Shops - Location management
- Products - Catalog with allergen badges
- Orders - Order workflow visualization
- Customers - Customer management

**State Machine:**
```
DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED
```

### Infrastructure

**Docker Support:**
- Multi-stage Dockerfiles for all services
- Health checks and graceful shutdown
- Non-root users for security

**Kubernetes:**
- 22 resources across 7 YAML files
- HorizontalPodAutoscaler (3-10 replicas)
- PodDisruptionBudget for high availability
- Ingress with TLS and rate limiting

**CI/CD:**
- GitHub Actions pipeline
- Automated testing, building, scanning
- Multi-platform Docker builds
- Deployment scripts

---

## 🔒 Security

### Multi-Tenant Isolation

**JWT-Based (Production):**
```bash
# Keycloak issues JWT with tenant_id claim
# Core Java validates JWT and extracts tenant_id
# RLS policies enforce isolation at database level
```

**Row-Level Security:**
```sql
CREATE POLICY tenant_isolation ON shops
  FOR ALL TO jtoye_app
  USING (tenant_id = current_tenant_id());
```

`current_tenant_id()` is a helper that reads the `app.current_tenant_id` GUC and returns `NULL`
rather than raising `22P02` when it is unset or malformed — so a bad GUC fails *filtered*, not
*errored*. Do not write the raw `current_setting('app.current_tenant_id')::uuid` cast: migration
V51 removed it from every remaining policy, and `RlsContractTest.noPolicyUsesRawTenantGucCast`
sweeps `pg_policy` to stop it coming back.

**Compliance:**
- ✅ Natasha's Law - Full ingredient and allergen labeling
- ✅ HMRC VAT - VAT rate tracking on transactions
- ✅ Audit Trail - Hibernate Envers on all entities

---

## 📊 Status

### Current Version: 2.3.0 (in development) — latest release tag `v2.2`

The artifact version (`build.gradle.kts`, `frontend/package.json`) is **2.3.0**; milestone v2.3 is
not yet released, so no `v2.3` git tag exists and `docs/CHANGELOG.md` keeps its work under
`[Unreleased]`. The three `2.1.0` image tags in `k8s/base/*-deployment.yaml` are a deliberately
inert placeholder — every deploy re-pins to `:<git-sha>` and a premortem guard fails the job if
that static default ever survives to an `apply`, so it is intentionally not version-tracked.

**Test Results** (counts verified by `scripts/docs-freshness.sh`; see `docs/metrics.json`):
- Backend (Java): 1425 `@Test` methods across 242 files ✅ (Testcontainers with real Postgres + RLS, require Docker)
- Edge (Go): 78 `Test*` functions across 10 files ✅
- Frontend (Jest): 607 `it/test` blocks across 81 files ✅
- Frontend E2E (Playwright): 49 `test()` blocks across 15 specs ✅
- MCP server (vitest): 48 `it/test` blocks across 8 files ✅
- **Total: 2207 logical test invocations** ✅

Database schema version: **V60** (Flyway).

> These numbers are guarded end-to-end by two CI gates in
> `.github/workflows/docs-freshness.yml`: `scripts/docs-freshness.sh` asserts
> `docs/metrics.json` against the source tree, and `scripts/check-doc-metrics.sh`
> asserts the numbers quoted *in this file* (and in `CLAUDE.md` / `AGENTS.md`)
> against `docs/metrics.json`. Before the second gate existed this block had
> drifted to `921` while the tree was at `1895` — the first gate never read it.

**Features:**
- [x] Full CRUD operations
- [x] Multi-tenant isolation
- [x] JWT authentication
- [x] Row-Level Security
- [x] State machine
- [x] Audit trails
- [x] Modern UI
- [x] Docker deployment
- [x] Kubernetes manifests
- [x] CI/CD pipeline
- [x] API versioning (`/api/v1/`)
- [x] Vendor marketing dashboard (promotions & announcements)
- [x] Real-time kitchen display (WebSocket/STOMP)
- [x] Stripe payment integration

---

## 🛠️ Development

### Prerequisites

- **Java 21** (Eclipse Temurin recommended). JDK 25 is incompatible with Gradle 8.10.
- **Node.js 24+** (with npm)
- **Go 1.26+** (`edge-go/go.mod` declares `go 1.26.0`)
- **Docker** with **Compose v2** (`docker compose`, not the standalone `docker-compose` v1 binary)

### Project Structure

```
JToye_OaaS_2026/
├── core-java/          # Spring Boot backend
├── edge-go/            # Go API gateway
├── frontend/           # Next.js 16 UI
├── infra/              # Docker Compose, Keycloak, DB
├── k8s/                # Kubernetes manifests
├── docs/               # Documentation
└── scripts/            # Build and deployment scripts
```

### Common Commands

```bash
# Run all tests
./gradlew :core-java:test
cd edge-go && go test ./...

# Build Docker images
./scripts/build-images.sh

# Deploy to Kubernetes
./scripts/deploy.sh staging

# Run smoke tests
./scripts/smoke-test.sh
```

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

**Development Guidelines:**
- Follow existing code style
- Add tests for new features
- Update documentation
- Ensure all tests pass

---

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🔗 Links

- **Documentation:** [docs/DOCUMENTATION_INDEX.md](docs/DOCUMENTATION_INDEX.md)
- **Issues:** [GitHub Issues](https://github.com/Bralabee/JToye_OaaS_2026/issues)
- **Changelog:** [CHANGELOG.md](docs/CHANGELOG.md)

---

## 📞 Support

- **Quick Start Issues:** See [docs/guides/QUICK_START.md](docs/guides/QUICK_START.md)
- **Environment Setup:** See [docs/guides/ENVIRONMENT_SETUP.md](docs/guides/ENVIRONMENT_SETUP.md)
- **Configuration:** See [docs/config/CONFIGURATION.md](docs/config/CONFIGURATION.md)
- **Testing:** See [docs/guides/TESTING.md](docs/guides/TESTING.md)
- **Docker Networking Issues:** See [docs/troubleshooting/DOCKER_IPTABLES_ISSUE.md](docs/troubleshooting/DOCKER_IPTABLES_ISSUE.md) ⚠️

---

**Built with ❤️ for modern retail operations**
