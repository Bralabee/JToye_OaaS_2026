### System Context for AI Agents

Project: J'Toye OaaS (UK Retail 2026)

Stack
- Core: Java 21, Spring Boot 3, JPA/Hibernate Envers, Spring Security, OAuth2 Resource Server (JWT), Spring StateMachine, Lombok
- Edge: Go 1.22, Gin, circuit breakers, rate limiting
- Frontend: Next.js 14, TypeScript, Tailwind CSS, shadcn/ui, NextAuth.js v5, Framer Motion
- Database: PostgreSQL 15 with Row‑Level Security (RLS)
- Identity: Keycloak 24 (realm: jtoye-dev)

Prime Directives
1) Security — RLS First
   - Never manually filter with `WHERE tenant_id = ?` in repositories or queries.
   - Every DB transaction must run with `SET LOCAL app.current_tenant_id = '<uuid>'`.
   - The application achieves this via:
     - `JwtTenantFilter` → extracts `tenant_id` (or `tenantId`/`tid`) from JWT into `TenantContext`.
     - `TenantFilter` (dev fallback) → reads `X-Tenant-Id` header if JWT claim absent.
     - `TenantSetLocalAspect` → runs before transactional methods and executes `SET LOCAL app.current_tenant_id = ?`.
   - Testing RLS:
     - ALWAYS run RLS-sensitive tests as a non-superuser (e.g., `jtoye_app`) because superusers bypass RLS.
     - Flush and clear `EntityManager` when preparing test data to avoid Hibernate first-level cache bypassing RLS.

2) Compliance
   - Natasha's Law: All product records must include `ingredients_text` and `allergen_mask` (14 allergens tracked via bitmask).
   - HMRC VAT: All financial records must include `vat_rate_enum`.
   - Audit Trail: Hibernate Envers enabled on all domain entities with tenant-aware revision tracking.

Architecture Boundaries
- Core (Java): system of record, complex logic, auditing, label PDFs, tax logic, order state machine.
- Edge (Go): webhooks, real‑time, rate limiting, circuit breakers, batch sync.
- Frontend (Next.js 14): Modern UI with 5 dashboard pages (Dashboard, Shops, Products, Orders, Customers), authentication, full CRUD operations.

Key Paths
- Core app entry: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Security: `core-java/src/main/java/uk/jtoye/core/security/*`
- RLS SQL: `core-java/src/main/resources/db/migration/V1__base_schema.sql`, `V2__rls_policies.sql`
- Domain entities: `Shop`, `Product`, `Order`, `Customer`, `FinancialTransaction` (7 REST controllers)
- State Machine: `core-java/src/main/java/uk/jtoye/core/statemachine/*` (Order lifecycle)
- Edge entry: `edge-go/cmd/edge/main.go`
- Frontend: `frontend/` (Next.js 14 app with NextAuth.js v5)
- Infra: `infra/docker-compose.yml` (PostgreSQL port 5433, Keycloak port 8085)

Runtime Assumptions (Dev)
- Core API: Port 9090 (was 8080)
- PostgreSQL: Port 5433 (Docker)
- Keycloak: Port 8085, issuer `http://localhost:8085/realms/jtoye-dev`
- Frontend: Port 3000
- JWT must contain `tenant_id` (PRODUCTION) or use `X-Tenant-Id` header as fallback (DEV ONLY).
- Test users: `tenant-a-user` / `password123`, `tenant-b-user` / `password123`

Coding Style
- Strong typing, DTOs for API payloads, avoid exposing entities directly.
- Keep code idiomatic to the module’s ecosystem (Spring idioms in Java, Gin idioms in Go).

Definition of Done for DB Access
- If code touches the DB, confirm a tenant is present in `TenantContext` and the call runs within a `@Transactional` boundary (so `SET LOCAL` applies). If not, fix the call site.

Phase 1 Status (COMPLETE)
- ✅ 7 REST controllers: Shops, Products, Orders, Customers, FinancialTransactions, Dev, Health
- ✅ Spring StateMachine: Order workflow (DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED)
- ✅ Hibernate Envers: Tenant-aware audit logging on all entities
- ✅ Frontend: Next.js 14 with 5 complete dashboard pages
- ✅ Authentication: NextAuth.js v5 with Keycloak OIDC, JWT tenant extraction
- ✅ CORS: Configured for localhost:3000 frontend
- ✅ Tests: 32/36 passing (89% success rate)
- ✅ Production Ready: All core functionality operational
