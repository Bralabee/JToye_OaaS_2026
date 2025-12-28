J’Toye OaaS — Phase 0/1 Scaffolding

What’s included
- core-java: Spring Boot 3 (System of Record)
  - Dependencies: Web, Data JPA, AOP, Security (JWT), Envers, StateMachine, JasperReports, Flyway, PostgreSQL, Validation
  - Endpoints:
    - Public: `GET /health`
    - Protected (JWT required): `GET/POST /shops`, `GET/POST /products`, `POST /dev/tenants/ensure`
  - RLS wiring:
    - `JwtTenantFilter` extracts tenant from JWT claims in order: `tenant_id` → `tenantId` → `tid`
    - `TenantFilter` reads `X-Tenant-Id` header as a dev fallback if JWT lacks tenant
    - `TenantSetLocalAspect` executes `SET LOCAL app.current_tenant_id = ?` inside transactions
  - Flyway migrations:
    - `V1__base_schema.sql` — Base schema, `vat_rate_enum`, mandatory `ingredients_text`, `allergen_mask`
    - `V2__rls_policies.sql` — RLS policies using `current_setting('app.current_tenant_id')`

- edge-go: Go 1.22 Gin service (System of Engagement)
  - Token-bucket rate limiting middleware
  - `GET /health`, `POST /sync/batch`, `POST /webhooks/whatsapp`

- infra: Docker Compose for Postgres 15 + Keycloak (basic realm and clients)
  - Postgres 15 with init script creating `jtoye` DB and role
  - Keycloak dev mode with realm import (`jtoye-dev`), clients `core-api`, `edge-api`, test user `dev-user/password`
  - `.env.example` for defaults and `docker-compose.hostnet.yml` override for host-network fallback

Prerequisites
- Java 21
- Go 1.22+
- Docker + Docker Compose

Quick start (recommended)
```bash
# Start infrastructure
cd infra && docker-compose up -d

# Start core service (using helper script)
./run-app.sh

# Health checks
curl http://localhost:9090/health
curl http://localhost:9090/actuator/health

# Swagger UI: http://localhost:9090/swagger-ui.html
# Keycloak UI: http://localhost:8085
```

Manual start (optional)
1) Infrastructure
```bash
cd infra && docker-compose up -d
```

2) Core service
```bash
# Option 1: Using the run script
./run-app.sh

# Option 2: Direct gradle command with environment variables
DB_PORT=5433 ./gradlew :core-java:bootRun
```
*Note: The core-java service runs on port **9090** (not 8080) and connects to PostgreSQL on port **5433** (not 5432). You must set `DB_PORT=5433` when running the application.*

*The core-java build is configured to use `build-local/` directory by default to avoid permission issues with the standard `build/` directory often encountered in mixed environments.*

3) Edge service
```bash
cd edge-go && go run ./cmd/edge
```

Configuration
- **Core API**: Port 9090 (configurable via `SERVER_PORT` env var)
- **PostgreSQL**: Port 5433 (Docker), requires `DB_PORT=5433` env var
- **Keycloak**: Port 8085
- See `SETUP.md` for detailed setup instructions and troubleshooting

Security / OIDC
- Resource server issuer: `${KC_ISSUER_URI:-http://localhost:8081/realms/jtoye-dev}`
- All non-health endpoints require a valid Keycloak JWT for realm `jtoye-dev`.
- Tenant resolution priority:
  1) JWT claim `tenant_id` (preferred) → `tenantId` → `tid`
  2) Dev fallback header `X-Tenant-Id: <uuid>`

Verify domain endpoints and RLS
1) Get a token:
```bash
KC=http://localhost:8085
TOKEN=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=dev-user' \
  -d 'password=password' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)
```
2) Choose tenants (dev):
```bash
TENANT_A=8d5e8f7a-9c2d-4c1a-9c2f-1f1a2b3c4d5e
TENANT_B=11111111-2222-3333-4444-555555555555
```
3) Ensure tenant rows, then create/list data for RLS demonstration:
```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" \
  "http://localhost:9090/dev/tenants/ensure?name=Tenant-A"

curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Main Street Shop","address":"1 Main St"}' \
  http://localhost:9090/shops | jq

curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"YAM-5KG","title":"Yam 5kg","ingredientsText":"Yam (100%)","allergenMask":0}' \
  http://localhost:9090/products | jq

curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" http://localhost:9090/shops | jq
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" http://localhost:9090/products | jq

curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_B" \
  "http://localhost:9090/dev/tenants/ensure?name=Tenant-B"

curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_B" http://localhost:9090/shops | jq
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_B" http://localhost:9090/products | jq
```

Data Security (RLS)
- Never manually filter by `tenant_id` in code — let Postgres RLS enforce isolation.
- Every transactional DB call runs under `SET LOCAL app.current_tenant_id` set from `TenantContext`.
- `TenantContext` is populated by JWT claims; header fallback exists in dev only.

For AI agents
- See `docs/AI_CONTEXT.md` for the strict Security & Compliance directives and project guardrails.

Roadmap (per manifesto)
- Core-Java: enrich domain (DTO-first), Envers auditing config/tables, StateMachine for orders, JasperReports labels.
- Infra: map `tenant_id` into JWT via Keycloak protocol mapper; optional CI.
- Edge-Go: WhatsApp bridge + conflict resolution; circuit breakers.
