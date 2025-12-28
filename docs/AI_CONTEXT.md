### System Context for AI Agents

Project: J'Toye OaaS (UK Retail 2026)

Stack
- Core: Java 21, Spring Boot 3, JPA/Hibernate Envers, Spring Security, OAuth2 Resource Server (JWT)
- Edge: Go 1.22, Gin
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
   - Natasha’s Law: All product records must include `ingredients_text` and `allergen_mask`.
   - HMRC VAT: All financial records must include `vat_rate_enum`.

Architecture Boundaries
- Core (Java): system of record, complex logic, auditing, label PDFs, tax logic.
- Edge (Go): webhooks, real‑time, rate limiting, circuit breakers, batch sync.

Key Paths
- Core app entry: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Security: `core-java/src/main/java/uk/jtoye/core/security/*`
- RLS SQL: `core-java/src/main/resources/db/migration/V1__base_schema.sql`, `V2__rls_policies.sql`
- Domain (initial): `Shop`, `Product` entities, repos, controllers under `core-java/src/main/java/uk/jtoye/core/`
- Edge entry: `edge-go/cmd/edge/main.go`
- Infra: `infra/docker-compose.yml`, `infra/docker-compose.hostnet.yml`

Runtime Assumptions (Dev)
- Keycloak issuer: `http://localhost:8081/realms/jtoye-dev`
- JWT must contain `tenant_id` (recommended) or use `X-Tenant-Id` header as a fallback.

Coding Style
- Strong typing, DTOs for API payloads, avoid exposing entities directly.
- Keep code idiomatic to the module’s ecosystem (Spring idioms in Java, Gin idioms in Go).

Definition of Done for DB Access
- If code touches the DB, confirm a tenant is present in `TenantContext` and the call runs within a `@Transactional` boundary (so `SET LOCAL` applies). If not, fix the call site.
