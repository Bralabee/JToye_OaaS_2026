# Multi-Tenant JWT Authentication Testing Guide

## Overview
This guide demonstrates how to test the multi-tenant JWT authentication system with Row-Level Security (RLS) isolation.

## Prerequisites
- Infrastructure running: `cd infra && docker-compose up -d`
- Core Java API running on port 9090 (via IntelliJ or `./scripts/run-app.sh`)
- Test data loaded in database

## Quick Test

Run the diagnostic script to verify everything is working:
```bash
bash scripts/testing/diagnose-jwt-issue.sh
```

Expected output:
```
=== Diagnosis ===
✓ JWT has tenant_id claim: 00000000-0000-0000-0000-000000000001
✓ JWT-only request works! JwtTenantFilter is functioning correctly
```

## Detailed Testing

### 1. Load Test Data

```bash
# Load sample tenants, shops, and products
docker exec -i jtoye-postgres psql -U postgres -d jtoye <<'SQL'
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

# Get token for Tenant A user
TOKEN_A=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# Get token for Tenant B user
TOKEN_B=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-b-user' \
  -d 'password=password' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

echo "✓ Tokens obtained"
```

### 3. Verify JWT Claims

```bash
# Decode Tenant A token to see tenant_id claim
echo $TOKEN_A | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{tenant_id, groups}'

# Expected output:
# {
#   "tenant_id": "00000000-0000-0000-0000-000000000001",
#   "groups": ["tenant-a"]
# }
```

### 4. Test Tenant Isolation

```bash
# Tenant A should see only Tenant A shops (2 shops)
echo "=== Tenant A Shops ==="
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/shops | jq '.content[] | .name'
# Expected:
# "Tenant A - Main Store"
# "Tenant A - Outlet Store"

# Tenant B should see only Tenant B shops (2 shops)
echo -e "\n=== Tenant B Shops ==="
curl -s -H "Authorization: Bearer $TOKEN_B" http://localhost:9090/shops | jq '.content[] | .name'
# Expected:
# "Tenant B - Flagship Store"
# "Tenant B - Pop-up Shop"

# Verify products are also isolated
echo -e "\n=== Tenant A Products ==="
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/products | jq '.content[] | .title'
# Expected: 3 Tenant A products

echo -e "\n=== Tenant B Products ==="
curl -s -H "Authorization: Bearer $TOKEN_B" http://localhost:9090/products | jq '.content[] | .title'
# Expected: 3 Tenant B products
```

### 5. Verify RLS at Database Level

```bash
# Direct database query without RLS context should return empty
docker exec -i jtoye-postgres psql -U jtoye -d jtoye -c "SELECT name FROM shops;"
# Expected: 0 rows (RLS blocks access without tenant context)

# Query WITH RLS context should return tenant-specific data
docker exec -i jtoye-postgres psql -U jtoye -d jtoye <<'SQL'
BEGIN;
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
SELECT name FROM shops;
ROLLBACK;
SQL
# Expected: 2 Tenant A shops
```

## Test Scenarios

### Scenario 1: JWT-Only Authentication (Production Mode)
```bash
# No X-Tenant-ID header needed
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/shops | jq '.totalElements'
# Expected: 2
```

### Scenario 2: Header Fallback (Dev Mode)
```bash
# Get a generic token (without tenant_id claim)
TOKEN_DEV=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=dev-user' \
  -d 'password=password' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# Use X-Tenant-ID header as fallback
curl -s -H "Authorization: Bearer $TOKEN_DEV" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000001" \
  http://localhost:9090/shops | jq '.totalElements'
# Expected: 2
```

### Scenario 3: JWT Overrides Header (Security Test)
```bash
# JWT tenant_id takes priority even if conflicting header is provided
curl -s -H "Authorization: Bearer $TOKEN_A" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000002" \
  http://localhost:9090/shops | jq '.content[] | .name'
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
