# J'Toye OaaS -- Comprehensive Project Analysis

> **Generated**: 2026-04-01  
> **Method**: Full codebase crawl -- every source file, config, migration, test, and doc read  
> **Purpose**: Baseline understanding for continuous improvement

---

## What This Project Is

**J'Toye OaaS (Operations as a Service)** is a multi-tenant SaaS platform for UK retail management. It manages shops, products, orders, customers, and financial transactions for multiple independent tenants, with hard data isolation enforced at the database level via PostgreSQL Row-Level Security (RLS).

The platform is designed for a specific UK retail compliance context: **Natasha's Law** (allergen/ingredient labeling) and **HMRC VAT tracking**.

---

## Architecture At a Glance

```
Browser  -->  Next.js 14 (3000)  -->  Keycloak (8085)  [OAuth2/OIDC]
                   |
                   v
           Core Java (9090)         <-->  Edge Go (8089)
          Spring Boot 3.4.2              Gin + Circuit Breaker
          JWT + RLS + Caching             Rate Limiting
                   |                      WhatsApp Webhook
                   v
          PostgreSQL 15 (5433)      Redis (6379)       RabbitMQ (5672)
          Row-Level Security        Tenant-Aware Cache   (provisioned, not wired)
          Flyway Migrations         Bucket4j Rate Limits
          Hibernate Envers Audit
```

### Three Service Tiers

| Tier | Tech | Purpose | Source Files | Tests |
|------|------|---------|-------------|-------|
| **core-java** | Spring Boot 3.4.2, Java 21 | Domain logic, REST API, RLS, audit | 72 | 18 test classes |
| **edge-go** | Go 1.22, Gin | API gateway, circuit breaker, webhook | 6 | 3 test files (12 tests) |
| **frontend** | Next.js 14, TypeScript | Dashboard UI, Keycloak auth | 36 | 5 test suites |

### Codebase Stats

| Metric | Value |
|--------|-------|
| Java source files | 72 |
| Java test files | 18 |
| Go source files | 6 |
| TypeScript/TSX files | 36 |
| Flyway migrations | 15 |
| Documentation lines | 23,288 |
| Doc files | 50+ |
| Git commits | 30+ |

---

## Domain Model (6 Entities)

| Entity | Table | Tenant-Scoped | Audited | Key Design |
|--------|-------|:---:|:---:|------------|
| **Shop** | `shops` | Yes | Yes | Name unique per tenant |
| **Product** | `products` | Yes | Yes | SKU unique per tenant, Natasha's Law (ingredientsText + allergenMask) |
| **Customer** | `customers` | Yes | Yes | Email unique per tenant, allergen restrictions bitmask |
| **Order** | `orders` | Yes | Yes | State machine (7 statuses), auto-generated order number, customer FK |
| **OrderItem** | `order_items` | Yes | Yes | Price snapshot at order time (immutable) |
| **FinancialTransaction** | `financial_transactions` | Yes | Yes | Append-only ledger, VAT rates, immutable |
| **Tenant** | `tenants` | No (global) | No | Master tenant registry |

### Order State Machine

```
DRAFT --> PENDING --> CONFIRMED --> PREPARING --> READY --> COMPLETED
  |         |           |             |
  +-------- +---------- +--- CANCEL --+   (from any non-terminal state)
```

Events: SUBMIT, CONFIRM, START_PREP, MARK_READY, COMPLETE, CANCEL

### Allergen System (Natasha's Law)

14 allergens tracked as bitmasks (bits 0-13):

| Bit | Allergen | Bit | Allergen |
|-----|----------|-----|----------|
| 0 | Gluten | 7 | Nuts |
| 1 | Crustaceans | 8 | Celery |
| 2 | Eggs | 9 | Mustard |
| 3 | Fish | 10 | Sesame |
| 4 | Peanuts | 11 | Sulphites |
| 5 | Soybeans | 12 | Lupin |
| 6 | Milk | 13 | Molluscs |

Max allergenMask value: 16383 (all 14 bits set).

---

## 8 REST Controllers

| Controller | Base Path | Operations | Caching |
|-----------|-----------|------------|---------|
| ShopController | `/shops` | Full CRUD (pageable) | Yes (15m TTL) |
| ProductController | `/products` | Full CRUD (pageable) | Yes (10m TTL) |
| CustomerController | `/customers` | Full CRUD (pageable) | No (privacy) |
| OrderController | `/orders` | CRUD + 6 state transitions | No (volatile) |
| FinancialTransactionController | `/financial-transactions` | Create + Read only | No (compliance) |
| SyncController | `/sync/batch` | Batch upsert (shops/products) | Evicts both |
| DevTenantController | `/dev/tenants` | Ensure tenant exists (dev only) | No |
| SecurityHealthController | `/health/security` | RLS status check | No |

### Order State Transition Endpoints

| Endpoint | Transition |
|----------|-----------|
| `POST /orders/{id}/submit` | DRAFT -> PENDING |
| `POST /orders/{id}/confirm` | PENDING -> CONFIRMED |
| `POST /orders/{id}/start-preparation` | CONFIRMED -> PREPARING |
| `POST /orders/{id}/mark-ready` | PREPARING -> READY |
| `POST /orders/{id}/complete` | READY -> COMPLETED |
| `POST /orders/{id}/cancel` | Any non-terminal -> CANCELLED |

---

## Multi-Tenancy & Security

The security model is **defense-in-depth**, layered as:

1. **Keycloak** issues JWTs with `tenant_id` claim embedded via protocol mapper
2. **JwtTenantFilter** extracts tenant from JWT, stores in ThreadLocal (`TenantContext`)
3. **TenantSetLocalAspect** (AOP) runs before every `@Transactional` method, executes `SET LOCAL app.current_tenant_id = ?` via PreparedStatement
4. **PostgreSQL RLS policies** on every tenant-scoped table filter by `current_setting('app.current_tenant_id')`
5. **DatabaseConfigurationValidator** at startup verifies the app user is NOT a superuser (superusers bypass RLS), RLS is enabled, and policies exist
6. **TenantContextCleanupFilter** (HIGHEST_PRECEDENCE) ensures ThreadLocal cleanup after every request

**Database users**: `jtoye` (owner/admin), `jtoye_app` (application user, non-superuser, subject to RLS).

### Rate Limiting

- **Core Java**: Bucket4j + Redis, per-tenant, 100 req/min with 20-token burst
- **Edge Go**: Token bucket, 20 req/s with 40 burst (global, not per-tenant)
- **K8s Ingress**: NGINX rate limiting, 100 RPS per IP

---

## Database Migrations (Flyway)

| Migration | Purpose |
|-----------|---------|
| V1 | Base schema (tenants, shops, products, financial_transactions) + UUID extension + current_tenant_id() function |
| V2 | Row-Level Security policies on all tenant-scoped tables |
| V3 | Unique constraints: (tenant_id, sku) on products, (tenant_id, name) on shops |
| V4 | Envers audit tables with RLS for shops_aud, products_aud, financial_transactions_aud, revinfo |
| V5 | Orders and order_items tables with status enum |
| V6 | Fix order_status column type to VARCHAR |
| V7 | Add price_pennies column to products |
| V8 | Add tenant_id and user_id columns to revinfo |
| V9 | Customers table with unique (tenant_id, email) constraint |
| V10 | Add customer_id to orders_aud table |
| V11 | Fix audit table RLS to handle deletes properly |
| V12 | Convert vat_rate from enum to VARCHAR in audit tables |
| V13 | Seed default tenants for testing |
| V14 | Fix customers RLS policies |
| V15 | Standardize and fix order/order_item RLS policies |

---

## Infrastructure

| Component | Port | Purpose |
|-----------|------|---------|
| PostgreSQL 15 | 5433 | Primary datastore + RLS |
| Keycloak 24 | 8085 | OAuth2/OIDC provider |
| Redis 7 | 6379 | Caching + rate limit buckets |
| RabbitMQ 3.12 | 5672/15672 | Message queue (provisioned, **not yet wired**) |
| Core Java | 9090 | Spring Boot backend |
| Edge Go | 8089 | API gateway |
| Frontend | 3000 | Next.js UI |
| Prometheus | 9091 | Metrics collection |
| Grafana | 3001 | Dashboards |

### Keycloak Configuration

- Realm: `jtoye-dev`
- Clients: `core-api`, `frontend`
- Test users: `tenant-a-user`, `tenant-b-user`, `admin-user` (password: `password123`)
- Groups: `tenant-a`, `tenant-b` with tenant_id attributes
- Protocol mappers inject `tenant_id` into JWT

### Docker

`docker-compose.full-stack.yml` brings up all 7 services with zero .env configuration required.

### Kubernetes

Kustomize-based with base + staging/production overlays:
- 3 namespaces (dev, staging, production)
- HPA: core-java (3-10), edge-go (5-20), frontend (3-10)
- PDB: min 2-3 pods available
- NGINX Ingress with TLS (cert-manager + Let's Encrypt)
- Domains: `api.jtoye.co.uk`, `app.jtoye.co.uk`, `auth.jtoye.co.uk`
- Security headers: HSTS, X-Frame-Options, CSP, Referrer-Policy

### CI/CD (GitHub Actions)

Pipeline: Test -> Security Scan (Trivy + Snyk) -> Build & Push (multi-arch GHCR) -> Deploy Staging -> Deploy Production (with rollback)

---

## Frontend Details

### 5 Dashboard Pages

| Page | Features |
|------|----------|
| Dashboard | 4 stat cards (shops/products/orders/customers count), recent orders table |
| Shops | CRUD table, create/edit dialog |
| Products | CRUD table, allergen badge display, create/edit with allergen bitmask |
| Orders | State flow visualization, CRUD + state transitions, create with shop/product selection |
| Customers | CRUD table, allergen restrictions management |

### Tech Details

- **Auth**: NextAuth.js v5 + Keycloak (stores access/refresh/id tokens)
- **UI**: shadcn/ui (11 components), Framer Motion animations, Tailwind CSS
- **Forms**: react-hook-form + Zod validation
- **API**: Axios client with auto-injected Bearer token, 401 redirect interceptor
- **Middleware**: Protects `/dashboard/*` routes, redirects to `/auth/signin`

---

## Edge Gateway Details

### 3 Endpoints

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /health` | None | Core API health probe (2s timeout) |
| `POST /sync/batch` | JWT | Batch sync forwarding to Core API |
| `POST /webhooks/whatsapp` | Optional HMAC | WhatsApp webhook receiver |

### Resilience Patterns

- **Circuit Breaker** (Sony gobreaker): 60% failure threshold, 60s timeout, 3 half-open requests
- **Rate Limiter**: Token bucket, 20 req/s, 40 burst
- **JWT Validation**: RSA signature via JWKS, 5-minute key cache
- **Structured Logging**: Uber zap (production mode)

---

## Observability

| Component | Technology | Detail |
|-----------|-----------|--------|
| Metrics | Micrometer + Prometheus | /actuator/prometheus endpoint |
| Tracing | Brave + Zipkin | 10% sampling, configurable |
| Logging | SLF4J + Logback | JSON (prod), human-readable (dev), traceId/spanId correlation |
| Health | Spring Actuator | Liveness + readiness probes |
| Alerts | Prometheus rules | 7 rules (HighErrorRate, ServiceDown, DatabaseDown, etc.) |
| Dashboards | Grafana | Pre-provisioned datasource |

---

## Application Profiles

| Profile | DB Pool | Batch Size | Logging | Swagger | Rate Limiting |
|---------|---------|-----------|---------|---------|--------------|
| default/dev | 20 | 20 | DEBUG | Enabled | Enabled |
| local | 20 | 20 | DEBUG | Enabled | Enabled |
| test | auto-create | 20 | DEBUG | Enabled | **Disabled** |
| staging | 30 | 30 | DEBUG, JSON | Enabled | Enabled |
| prod | 50 | 50 | INFO, JSON | **Disabled** | Enabled |
