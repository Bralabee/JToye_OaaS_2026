# Production Readiness Verification Checklist

## Pre-Deployment Verification Steps

### 1. Build & Dependency Resolution

```bash
# Generate Gradle wrapper first (if not present)
docker run --rm -v "$PWD":/home/gradle/project -w /home/gradle/project \
  gradle:8.10.2-jdk21 gradle wrapper

# Install Edge service dependencies
cd edge-go && go mod download && cd ..

# Build Core service
./gradlew :core-java:build
```

**Expected:** ✅ Clean build with no errors

---

### 2. Run Test Suite

```bash
# Run all tests with coverage
./gradlew :core-java:test jacocoTestReport

# View coverage report
open core-java/build/reports/jacoco/test/html/index.html
```

**Expected Results:**
- ✅ All security tests pass (RLS isolation verified)
- ✅ All integration tests pass (API contracts validated)
- ✅ All unit tests pass (business logic correct)
- ✅ Minimum 70% code coverage

**Critical Tests to Verify:**
- `TenantIsolationSecurityTest.shouldOnlySeeTenantAShopsWhenTenantContextSetToA`
- `TenantIsolationSecurityTest.shouldNotSeeAnyShopsWhenTenantContextNotSet`
- `TenantIsolationSecurityTest.shouldPreventInsertingDataForOtherTenant`
- `ShopControllerIntegrationTest.createShopWithValidTenantShouldSucceed`
- `ShopControllerIntegrationTest.listShopsShouldReturnPaginatedResults`

---

### 3. Start Services

```bash
# Option 1: Quick start with dev script
bash scripts/dev.sh

# Option 2: Manual start for verification
cd infra && docker compose up -d
./gradlew :core-java:bootRun &
cd edge-go && go run ./cmd/edge &
```

**Expected:**
- ✅ PostgreSQL running on port 5433
- ✅ Keycloak running on port 8081
- ✅ Core API running on port 8080
- ✅ Edge service running on port 8090

---

### 4. Database Migration Verification

```bash
# Connect to database
docker exec -it jtoye-postgres psql -U jtoye -d jtoye

# Verify migrations applied
SELECT version, description, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

# Expected:
# V1__base_schema.sql
# V2__rls_policies.sql
# V3__add_unique_constraints.sql
# V4__envers_audit_tables.sql

# Verify RLS enabled
SELECT schemaname, tablename, rowsecurity
FROM pg_tables
WHERE schemaname = 'public' AND rowsecurity = true;

# Expected: shops, products, financial_transactions, shops_aud, products_aud, financial_transactions_aud

# Verify unique constraints
SELECT conname, contype FROM pg_constraint
WHERE conname IN ('idx_products_tenant_sku', 'idx_shops_tenant_name');

# Exit
\q
```

**Expected:** ✅ All 4 migrations applied, RLS enabled, constraints created

---

### 5. API Documentation Verification

```bash
# Open Swagger UI
open http://localhost:8080/swagger-ui.html

# Fetch OpenAPI spec
curl http://localhost:8080/v3/api-docs | jq . > openapi.json
```

**Manual Verification:**
- ✅ All endpoints documented (Shops, Products, Dev Tenants)
- ✅ Request/response schemas complete
- ✅ Security schemes present (bearer-jwt, tenant-header)
- ✅ Example values provided
- ✅ Natasha's Law compliance notes visible on Product endpoints

---

### 6. Security & RLS Testing

```bash
# Get JWT token
KC=http://localhost:8081
TOKEN=$(curl -s -d 'grant_type=password' -d 'client_id=core-api' \
  -d 'username=dev-user' -d 'password=password' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# Test 1: Create tenants
TENANT_A=8d5e8f7a-9c2d-4c1a-9c2f-1f1a2b3c4d5e
TENANT_B=11111111-2222-3333-4444-555555555555

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  "http://localhost:8080/dev/tenants/ensure?name=TenantA"

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_B" \
  "http://localhost:8080/dev/tenants/ensure?name=TenantB"

# Test 2: Create shops for each tenant
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Shop A1","address":"Address A"}' \
  http://localhost:8080/shops | jq .

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_B" \
  -H "Content-Type: application/json" \
  -d '{"name":"Shop B1","address":"Address B"}' \
  http://localhost:8080/shops | jq .

# Test 3: Verify Tenant A only sees their data
SHOPS_A=$(curl -s -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  http://localhost:8080/shops | jq '.content | length')

echo "Tenant A shops count: $SHOPS_A"

# Test 4: Verify Tenant B only sees their data
SHOPS_B=$(curl -s -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_B" \
  http://localhost:8080/shops | jq '.content | length')

echo "Tenant B shops count: $SHOPS_B"

# Test 5: Test duplicate SKU prevention
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{
    "sku":"TEST-SKU-001",
    "title":"Product 1",
    "ingredientsText":"Ingredients",
    "allergenMask":0
  }' http://localhost:8080/products

# Should succeed first time, fail on duplicate
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{
    "sku":"TEST-SKU-001",
    "title":"Product 1 Duplicate",
    "ingredientsText":"Ingredients",
    "allergenMask":0
  }' http://localhost:8080/products
```

**Expected Results:**
- ✅ `$SHOPS_A` = 1 (only Shop A1 visible to Tenant A)
- ✅ `$SHOPS_B` = 1 (only Shop B1 visible to Tenant B)
- ✅ Duplicate SKU returns HTTP 409 Conflict with error message
- ✅ No cross-tenant data leakage

---

### 7. Pagination Testing

```bash
# Create 25 shops for pagination test
for i in {1..25}; do
  curl -s -X POST -H "Authorization: Bearer $TOKEN" \
    -H "X-Tenant-Id: $TENANT_A" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Shop $i\",\"address\":\"Address $i\"}" \
    http://localhost:8080/shops > /dev/null
done

# Test pagination
curl -s -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  "http://localhost:8080/shops?page=0&size=10" | jq '{
    totalElements: .totalElements,
    totalPages: .totalPages,
    currentPage: .number,
    pageSize: .size,
    contentSize: (.content | length)
  }'

# Test sorting
curl -s -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  "http://localhost:8080/shops?sort=name,asc&size=5" \
  | jq '.content[].name'
```

**Expected Results:**
- ✅ `totalElements`: 26 (1 original + 25 new)
- ✅ `totalPages`: 3
- ✅ `pageSize`: 10
- ✅ `contentSize`: 10
- ✅ Sorting returns alphabetically ordered names

---

### 8. Edge Service Integration Testing

```bash
# Test 1: JWT validation on Edge
curl -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"items": [{"id": 1, "data": "test"}]}' \
  http://localhost:8090/sync/batch

# Expected: HTTP 202 Accepted (after Core API integration completes)

# Test 2: Missing JWT
curl http://localhost:8090/sync/batch

# Expected: HTTP 401 Unauthorized

# Test 3: Invalid JWT
curl -H "Authorization: Bearer invalid_token" \
  http://localhost:8090/sync/batch

# Expected: HTTP 401 Unauthorized

# Test 4: Rate limiting
for i in {1..50}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8090/sync/batch
done | sort | uniq -c

# Expected: Mix of 202 and 429 (rate limited after burst)
```

**Expected Results:**
- ✅ Valid JWT accepted
- ✅ Missing/invalid JWT rejected with 401
- ✅ Rate limiting triggers at ~40-50 requests (20 RPS + 40 burst)

---

### 9. Error Handling Verification

```bash
# Test 1: Validation error
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{"name":""}' \
  http://localhost:8080/shops | jq .

# Expected: HTTP 400 with RFC 7807 Problem Detail

# Test 2: Missing tenant context
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Shop"}' \
  http://localhost:8080/shops | jq .

# Expected: HTTP 400 "Tenant is not set"

# Test 3: Invalid UUID
curl -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: not-a-uuid" \
  http://localhost:8080/shops | jq .

# Expected: HTTP 400 "Invalid X-Tenant-Id header"
```

**Expected Results:**
- ✅ All errors return RFC 7807 Problem Detail format
- ✅ Validation errors include field-level details
- ✅ No stack traces exposed in responses

---

### 10. Audit Trail Verification

```bash
# Connect to database
docker exec -it jtoye-postgres psql -U jtoye -d jtoye

# Check revinfo table
SELECT * FROM revinfo ORDER BY rev DESC LIMIT 5;

# Check shops audit
SELECT id, rev, revtype, name FROM shops_aud ORDER BY rev DESC LIMIT 10;

# Verify RLS on audit tables (should only show current tenant's history)
-- Set tenant context
SET LOCAL app.current_tenant_id = '8d5e8f7a-9c2d-4c1a-9c2f-1f1a2b3c4d5e';
SELECT COUNT(*) FROM shops_aud;

-- Reset
RESET app.current_tenant_id;
\q
```

**Expected:**
- ✅ `revinfo` contains revision entries
- ✅ `shops_aud` contains audit records with revtype (0=INSERT, 1=UPDATE, 2=DELETE)
- ✅ RLS filters audit tables by tenant

---

### 11. Performance & Resource Checks

```bash
# Check connection pool
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .

# Check memory usage
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq .

# Check database connections
docker exec -it jtoye-postgres psql -U jtoye -d jtoye \
  -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'jtoye';"
```

**Expected:**
- ✅ Active connections < maximum pool size
- ✅ Memory usage stable
- ✅ Database connections match HikariCP pool size

---

### 12. Logging Verification

```bash
# Check Core API logs
tail -f /tmp/core-java.log | grep -E "(ERROR|WARN|tenant)"

# Check Edge service logs
tail -f /tmp/edge-go.log | grep -E "(error|warn|tenant)"
```

**Expected:**
- ✅ Structured log format
- ✅ No ERROR or WARN messages under normal operation
- ✅ Tenant IDs visible in transactional logs

---

## Production Deployment Checklist

### Environment Variables

Core API:
```bash
export DB_HOST=<production-db-host>
export DB_PORT=5432
export DB_NAME=jtoye
export DB_USER=<secure-username>
export DB_PASSWORD=<secure-password>
export DB_POOL_SIZE=50
export KC_ISSUER_URI=https://keycloak.prod.jtoye.uk/realms/jtoye-prod
export LOG_LEVEL=INFO
export SQL_LOG_LEVEL=WARN
export SWAGGER_ENABLED=false
export SPRING_PROFILES_ACTIVE=prod
```

Edge Service:
```bash
export CORE_API_URL=https://api.jtoye.uk
export KC_ISSUER_URI=https://keycloak.prod.jtoye.uk/realms/jtoye-prod
export PORT=8090
```

### Pre-Production Tasks

- [ ] Generate production-grade secrets
- [ ] Configure SSL/TLS certificates
- [ ] Set up database backups
- [ ] Configure log aggregation (ELK/Loki)
- [ ] Set up monitoring dashboards (Grafana)
- [ ] Configure alerting rules
- [ ] Run load tests (recommended: 100+ concurrent users)
- [ ] Perform security scan (OWASP ZAP, SonarQube)
- [ ] Review and approve Flyway migrations
- [ ] Create disaster recovery plan

---

## Sign-Off

### Development Team
- [ ] All tests passing
- [ ] Code reviewed
- [ ] Documentation complete
- [ ] No known critical bugs

### Security Team
- [ ] RLS verified
- [ ] JWT validation tested
- [ ] Audit trail confirmed
- [ ] Penetration testing complete

### Operations Team
- [ ] Deployment runbook reviewed
- [ ] Rollback procedure tested
- [ ] Monitoring configured
- [ ] Alerts configured

---

**Date:** _____________
**Approved By:** _____________
**Version:** 0.2.0-RELEASE-CANDIDATE
