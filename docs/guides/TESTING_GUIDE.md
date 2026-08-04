# Multi-Tenant JWT Authentication Testing Guide

## Overview
This guide demonstrates how to test the multi-tenant JWT authentication system with Row-Level Security (RLS) isolation.

## Prerequisites
- Backing services running:
  `docker compose -f docker-compose.full-stack.yml up -d postgres keycloak redis rabbitmq`
  (not `cd infra && docker compose up -d` — that file re-declares `jtoye-postgres` / `jtoye-keycloak`
  on the same host ports and collides with the canonical stack)
- Core Java API running on port 9090 (via IntelliJ or `./scripts/run-app.sh`)
- Your `.env` loaded into the shell, since the commands below read credentials from it:
  `set -a; . ./.env; set +a`
- Test data loaded in database

## Quick Test

Mint a token and use it. This replaces a former reference to
`scripts/testing/diagnose-jwt-issue.sh`, which does not exist anywhere in the repository — the
guide's opening command could not run.

```bash
set -a; . ./.env; set +a

TOK=$(curl -s -d grant_type=password \
  -d "client_id=$KEYCLOAK_CLIENT_ID" -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d username=tenant-a-user -d "password=$KC_SEED_USER_PASSWORD" \
  http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token | jq -r .access_token)

# 1. the token carries a tenant_id claim
echo "$TOK" | cut -d. -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id'

# 2. a JWT-only request works — no X-Tenant-Id header needed
curl -s -o /dev/null -w 'with token: %{http_code}\n' \
  -H "Authorization: Bearer $TOK" http://localhost:9090/api/v1/shops

# 3. and the same request without one does not
curl -s -o /dev/null -w 'no token  : %{http_code}\n' http://localhost:9090/api/v1/shops
```

Expected output:
```
00000000-0000-0000-0000-000000000001
with token: 200
no token  : 401
```

Run step 3 as well as step 2. A 200 on its own does not show the filter is enforcing anything.

## Detailed Testing

### 1. Load Test Data

```bash
# Load sample tenants, shops, and products
docker compose -f docker-compose.full-stack.yml exec -T postgres psql -U "$POSTGRES_USER" -d jtoye <<'SQL'
INSERT INTO tenants (id, name) VALUES
  ('00000000-0000-0000-0000-000000000001', 'Tenant A Corp'),
  ('00000000-0000-0000-0000-000000000002', 'Tenant B Ltd')
ON CONFLICT (id) DO NOTHING;

INSERT INTO shops (id, tenant_id, name, address) VALUES
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Tenant A - Main Store', '123 Main St'),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Tenant A - Outlet Store', '456 Outlet Rd'),
  ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'Tenant B - Flagship Store', '789 High St'),
  ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'Tenant B - Pop-up Shop', '321 Pop St')
ON CONFLICT (id) DO NOTHING;

INSERT INTO products (id, tenant_id, sku, title, ingredients_text, allergen_mask) VALUES
  ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'TENANT-A-SKU-001', 'Tenant A - Premium Bread', 'Wheat flour, water, yeast', 1),
  ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'TENANT-A-SKU-002', 'Tenant A - Croissant', 'Wheat flour, butter, eggs', 3),
  ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'TENANT-A-SKU-003', 'Tenant A - Chocolate Cake', 'Flour, cocoa, eggs', 7),
  ('20000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'TENANT-B-SKU-001', 'Tenant B - Artisan Sourdough', 'Sourdough starter, flour', 1),
  ('20000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000002', 'TENANT-B-SKU-002', 'Tenant B - Gluten-Free Muffin', 'Rice flour, eggs', 2),
  ('20000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000002', 'TENANT-B-SKU-003', 'Tenant B - Vegan Cookie', 'Oat flour, coconut oil', 0)
ON CONFLICT (id) DO NOTHING;
SQL
```

### 2. Get JWT Tokens for Each Tenant

```bash
KC=http://localhost:8085

# The client is confidential, so client_secret is REQUIRED. Omitting it returns
# {"error":"unauthorized_client"} — which looks like a bad password but is not.
set -a; . ./.env; set +a

# Get token for Tenant A user
TOKEN_A=$(curl -s \
  -d 'grant_type=password' \
  -d "client_id=$KEYCLOAK_CLIENT_ID" \
  -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d 'username=tenant-a-user' \
  -d "password=$KC_SEED_USER_PASSWORD" \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# Get token for Tenant B user
TOKEN_B=$(curl -s \
  -d 'grant_type=password' \
  -d "client_id=$KEYCLOAK_CLIENT_ID" \
  -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d 'username=tenant-b-user' \
  -d "password=$KC_SEED_USER_PASSWORD" \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

echo "✓ Tokens obtained"
```

### 3. Verify JWT Claims

```bash
# Decode Tenant A token to see tenant_id claim
echo $TOKEN_A | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{tenant_id, groups}'

# Expected output — the tenant_id claim is what matters here:
# {
#   "tenant_id": "00000000-0000-0000-0000-000000000001",
#   "groups": null
# }
#
# `groups` is only populated when the realm's group-membership mapper is enabled for the client;
# a null `groups` with a correct `tenant_id` is fine, because JwtTenantFilter reads tenant_id.
```

### 4. Test Tenant Isolation

```bash
# Tenant A sees only Tenant A shops
echo "=== Tenant A Shops ==="
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/api/v1/shops | jq '.content[] | .name'

# Tenant B sees only Tenant B shops
echo -e "\n=== Tenant B Shops ==="
curl -s -H "Authorization: Bearer $TOKEN_B" http://localhost:9090/api/v1/shops | jq '.content[] | .name'

# Verify products are also isolated
echo -e "\n=== Tenant A Products ==="
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/api/v1/products | jq '.content[] | .title'

echo -e "\n=== Tenant B Products ==="
curl -s -H "Authorization: Bearer $TOKEN_B" http://localhost:9090/api/v1/products | jq '.content[] | .title'
```

The property to check is **disjointness**, not a fixed count: the two lists must share no entry.
Exact counts depend on whatever seed and probe data your database happens to hold, so asserting a
literal number here only guarantees the doc goes stale.

### 5. Verify RLS at Database Level

⚠️ **Connect as `jtoye_app`, never as `jtoye`.** `jtoye` is a PostgreSQL **superuser**, and
superusers **bypass RLS entirely** — running these checks as `jtoye` returns every tenant's rows and
proves nothing. Measured on the dev stack: `SELECT count(*) FROM customers` with no tenant context
returned **18** as `jtoye` and **0** as `jtoye_app`.

Run these **from the host**, through the published port `5433`. Inside the container every
connection matches a `trust` line in `pg_hba.conf` (both the unix socket and `127.0.0.1`), so
`docker exec … psql` accepts any password and any credential check there is vacuous. From the host
the connection falls through to `scram-sha-256` and the credential is really checked.

```bash
set -a; . ./.env; set +a

# Direct database query without RLS context, as the APPLICATION role
PGPASSWORD="$DB_PASSWORD" psql -h localhost -p 5433 -U "$DB_USER" -d jtoye \
  -c "SELECT count(*) FROM customers;"
# Expected: 0 — RLS blocks every row when no tenant context is set

# Query WITH RLS context returns exactly that tenant's rows
PGPASSWORD="$DB_PASSWORD" psql -h localhost -p 5433 -U "$DB_USER" -d jtoye <<'SQL'
BEGIN;
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
SELECT count(*) FROM customers;
ROLLBACK;
SQL
# Expected: > 0, and a different count for tenant ...0002

# FALSIFICATION: the same query as the superuser, which BYPASSES RLS.
# If this returns the same number as the app role above, your check is measuring nothing.
PGPASSWORD="$POSTGRES_PASSWORD" psql -h localhost -p 5433 -U "$POSTGRES_USER" -d jtoye \
  -c "SELECT count(*) FROM customers;"
# Expected: the FULL cross-tenant count — this is why the app never runs as this role
```

> Use `customers` rather than `shops` for this check. `shops` carries a deliberate
> `shops_public_read` policy (`published = true OR tenant_id = current_tenant_id()`), so the app
> role legitimately sees published shops with no tenant context — a non-zero result there is
> correct behaviour, not an RLS failure.

## Test Scenarios

### Scenario 1: JWT-Only Authentication (Production Mode)
```bash
# No X-Tenant-ID header needed
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/api/v1/shops | jq '.totalElements'
# Expected: a non-zero count, and the SAME count in both scenarios (JWT tenant wins)
```

### Scenario 2: Header Fallback (Dev Mode)
```bash
# Get a generic token (without tenant_id claim)
TOKEN_DEV=$(curl -s \
  -d 'grant_type=password' \
  -d "client_id=$KEYCLOAK_CLIENT_ID" \
  -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d 'username=tenant-a-user' \
  -d "password=$KC_SEED_USER_PASSWORD" \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# Use X-Tenant-ID header as fallback
curl -s -H "Authorization: Bearer $TOKEN_DEV" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000001" \
  http://localhost:9090/api/v1/shops | jq '.totalElements'
# Expected: a non-zero count, and the SAME count in both scenarios (JWT tenant wins)
```

### Scenario 3: JWT Overrides Header (Security Test)
```bash
# JWT tenant_id takes priority even if conflicting header is provided
curl -s -H "Authorization: Bearer $TOKEN_A" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000002" \
  http://localhost:9090/api/v1/shops | jq '.content[] | .name'
# Expected: Tenant A shops only (JWT wins)
```

## Troubleshooting

### No data returned
1. Check if test data is loaded:
   ```bash
   docker exec -i jtoye-postgres psql -U jtoye -d jtoye -c "SELECT COUNT(*) FROM shops;"
   ```
2. If empty, reload test data (see step 1)
3. Check IntelliJ logs for `JwtTenantFilter` and `Aspect` messages

### JWT token expired
Tokens expire after a short time. Generate fresh tokens:
```bash
# Re-run step 2 to get new tokens
```

### Application not responding
1. Check health endpoint: `curl http://localhost:9090/health`
2. Verify IntelliJ Run console shows "Started CoreApplication"
3. Check Docker containers: `docker ps | grep jtoye`

## Architecture Components

### Filter Chain Order
1. **TenantFilter** (runs before auth) - Sets tenant from X-Tenant-ID header if present
2. **Spring Security Authentication** - Validates JWT token
3. **BearerTokenAuthenticationFilter** - Processes Bearer token
4. **JwtTenantFilter** (runs after auth) - Extracts tenant_id from JWT, overrides header value

### RLS Enforcement
- **TenantSetLocalAspect** - AOP aspect that runs before `@Transactional` methods
- Executes `SET LOCAL app.current_tenant_id = '<uuid>'` on database connection
- PostgreSQL RLS policies automatically filter rows based on `current_setting('app.current_tenant_id')`

### Key Files
- `core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter.java` - JWT tenant extraction
- `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` - RLS context setter
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` - Filter chain configuration
- `core-java/src/main/resources/db/migration/V2__rls_policies.sql` - RLS policy definitions

## Success Criteria

✅ **System is working correctly when:**
- JWT tokens contain `tenant_id` claim
- Each tenant sees only their own data via API
- Cross-tenant queries return empty results
- Database queries without RLS context return no rows
- IntelliJ logs show "JwtTenantFilter: set TenantContext" and "Aspect: Successfully set app.current_tenant_id"
