# J'Toye OaaS

**Multi-tenant SaaS platform for UK retail management with Row-Level Security**

[![Version](https://img.shields.io/badge/version-0.7.0-blue.svg)](CHANGELOG.md)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/jtoye/oaas/actions)
[![Tests](https://img.shields.io/badge/tests-53%2F53%20passing-brightgreen.svg)](#)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 Overview

J'Toye OaaS (Operations as a Service) is a production-ready, multi-tenant SaaS platform designed for retail operations. Built with enterprise-grade security featuring PostgreSQL Row-Level Security (RLS), full CRUD operations, and modern authentication.

### Tech Stack

| Layer | Technology |
|-------|------------|
| **Frontend** | Next.js 14, TypeScript, Tailwind CSS, NextAuth.js v5 |
| **Backend** | Spring Boot 3, Java 21, Hibernate Envers, Spring State Machine |
| **Edge** | Go 1.22, Gin, Circuit Breakers, Rate Limiting |
| **Database** | PostgreSQL 15 with Row-Level Security (RLS) |
| **Auth** | Keycloak 24 (OAuth2/OIDC) |
| **Infrastructure** | Docker, Kubernetes, Redis, RabbitMQ |

### Key Features

✅ **Multi-Tenancy** - PostgreSQL RLS with JWT-based isolation
✅ **Full CRUD** - 7 REST controllers (Shops, Products, Orders, Customers, etc.)
✅ **State Machine** - Order workflow management
✅ **Audit Trail** - Hibernate Envers on all entities
✅ **Modern UI** - 5 responsive dashboards with animations
✅ **Production Ready** - Docker, Kubernetes, CI/CD pipeline

---

## 🚀 Quick Start

### Option 1: Docker (2 Minutes)

✅ **No configuration required!** Everything runs in containers.

```bash
docker-compose -f docker-compose.full-stack.yml up
```

**Access:**
- UI: http://localhost:3000
- API: http://localhost:9090
- Keycloak: http://localhost:8085

**Login:** `tenant-a-user` / `password123`

### Option 2: Local Development (10 Minutes)

⚠️ **Requires environment setup first!**

```bash
# 1. Copy environment templates
cp frontend/.env.local.example frontend/.env.local
cp core-java/.env.example core-java/.env
cp edge-go/.env.example edge-go/.env
cp infra/.env.example infra/.env

# 2. Start infrastructure
cd infra && docker-compose up -d && cd ..

# 3. Start backend
./run-app.sh

# 4. Start frontend (new terminal)
cd frontend && npm install && npm run dev
```

📖 **Detailed Guide:** See [docs/QUICK_START.md](docs/QUICK_START.md)

---

## 📚 Documentation

### Getting Started
- **[QUICK_START.md](docs/QUICK_START.md)** - Get running in minutes (all platforms)
- **[ENVIRONMENT_SETUP.md](docs/ENVIRONMENT_SETUP.md)** - Environment configuration guide
- **[DOCKER_QUICK_START.md](docs/DOCKER_QUICK_START.md)** - Docker-specific instructions

### Development
- **[USER_GUIDE.md](docs/USER_GUIDE.md)** - How to use the application
- **[TESTING.md](docs/TESTING.md)** - Testing guide with examples
- **[CONFIGURATION.md](docs/CONFIGURATION.md)** - Detailed configuration reference

### Deployment & Operations
- **[DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md)** - Production deployment
- **[PRODUCTION_READINESS_REPORT.md](docs/PRODUCTION_READINESS_REPORT.md)** - Production checklist
- **[SECURITY_AUDIT_REPORT.md](docs/SECURITY_AUDIT_REPORT.md)** - Security assessment

### Architecture
- **[AI_CONTEXT.md](docs/AI_CONTEXT.md)** - System context and architecture
- **[DOCUMENTATION_INDEX.md](docs/DOCUMENTATION_INDEX.md)** - Complete docs index
- **[CHANGELOG.md](docs/CHANGELOG.md)** - Version history

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Browser                                                     │
│  └─ Next.js 14 Frontend (Port 3000)                        │
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

**7 REST APIs:**
- `/shops` - Retail location management
- `/products` - Product catalog with allergen tracking
- `/orders` - Order lifecycle with state machine
- `/customers` - Customer profiles
- `/financial-transactions` - Transaction tracking
- `/dev/tenants` - Tenant management (dev only)
- `/health` - Health check endpoint

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
- Size-optimized images (core: 200MB, edge: 15MB, frontend: 150MB)
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
  USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
```

**Compliance:**
- ✅ Natasha's Law - Full ingredient and allergen labeling
- ✅ HMRC VAT - VAT rate tracking on transactions
- ✅ Audit Trail - Hibernate Envers on all entities

---

## 📊 Status

### Current Version: v0.7.0

**Test Results:**
- Backend: 41/41 passing ✅
- Edge: 12/12 passing ✅
- Total: 53/53 (100%) ✅

**Production Readiness:** 95/100

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

---

## 🛠️ Development

### Prerequisites

- **Java 21** (Eclipse Temurin recommended)
- **Node.js 20+** (with npm)
- **Go 1.22+**
- **Docker & Docker Compose**

### Project Structure

```
JToye_OaaS_2026/
├── core-java/          # Spring Boot backend
├── edge-go/            # Go API gateway
├── frontend/           # Next.js 14 UI
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
- **Issues:** [GitHub Issues](https://github.com/jtoye/oaas/issues)
- **Changelog:** [CHANGELOG.md](docs/CHANGELOG.md)

---

## 📞 Support

- **Quick Start Issues:** See [docs/QUICK_START.md](docs/QUICK_START.md)
- **Environment Setup:** See [docs/ENVIRONMENT_SETUP.md](docs/ENVIRONMENT_SETUP.md)
- **Configuration:** See [docs/CONFIGURATION.md](docs/CONFIGURATION.md)
- **Testing:** See [docs/TESTING.md](docs/TESTING.md)
- **Docker Networking Issues:** See [docs/DOCKER_IPTABLES_ISSUE.md](docs/DOCKER_IPTABLES_ISSUE.md) ⚠️

---

**Built with ❤️ for modern retail operations**
