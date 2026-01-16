# QA Test Plan - J'Toye OaaS

**Version:** 1.0
**Date:** 2026-01-03
**Status:** Comprehensive Test Plan

---

## Table of Contents

1. [Fresh Clone Experience](#fresh-clone-experience)
2. [Pre-Test Setup](#pre-test-setup)
3. [Functional Testing](#functional-testing)
4. [Security Testing](#security-testing)
5. [Performance Testing](#performance-testing)
6. [Integration Testing](#integration-testing)
7. [End-to-End Scenarios](#end-to-end-scenarios)
8. [QA Checklist](#qa-checklist)

---

## Fresh Clone Experience

### Scenario: New Developer Joins Team

**Objective:** Verify a colleague can clone and run the project successfully.

#### Test Steps:

**1. Clone Repository**
```bash
git clone <repository-url>
cd JToye_OaaS_2026
```

**Expected:**
- ✅ Repository clones successfully
- ✅ All files present
- ✅ No `.env` files in repository (should be gitignored)

**Verify:**
```bash
ls -la frontend/.env.local       # Should NOT exist
ls -la frontend/.env.local.example  # Should exist
```

---

**2. Read Documentation**

**Test:** First-time user reads README.md

**Expected:**
- ✅ README is concise (<200 lines)
- ✅ Clear "Quick Start" section visible
- ✅ Two options presented: Docker (easy) vs Local (development)
- ✅ Links to detailed guides present

**Test Counts:**
- Total Tests: 156 ✅
- Backend: 144 ✅
- Edge: 12 ✅
- Pass Rate: 100% ✅

---

**3. Option A: Docker Quick Start (Recommended Path)**

**Command:**
```bash
docker compose -f docker-compose.full-stack.yml up
```

**Expected Behavior:**
- ✅ Builds all 3 images (core-java, edge-go, frontend)
- ✅ Starts 7 services (PostgreSQL, Keycloak, Redis, RabbitMQ, Core, Edge, Frontend)
- ✅ Health checks pass within 2 minutes
- ✅ No errors in logs

**Timing Expectations:**
- Image builds: 5-10 minutes (first time)
- Service startup: 1-2 minutes
- Total: 6-12 minutes

**Verification:**
```bash
# Check all containers running
docker compose -f docker-compose.full-stack.yml ps

# Should show 7 services:
# - jtoye-postgres (healthy)
# - jtoye-keycloak (healthy)
# - jtoye-redis (healthy)
# - jtoye-rabbitmq (healthy)
# - jtoye-core-java (healthy)
# - jtoye-edge-go (healthy)
# - jtoye-frontend (healthy)
```

**Access URLs:**
```bash
# Frontend
curl -I http://localhost:3000
# Expected: HTTP/1.1 200 OK

# Core API Health
curl http://localhost:9090/actuator/health
# Expected: {"status":"UP"}

# Edge Health
curl http://localhost:8089/health
# Expected: {"edge":"OK","core":{"healthy":true}}

# Keycloak
curl -I http://localhost:8085
# Expected: HTTP/1.1 200
```

**Issues to Check:**
- [ ] Port conflicts (9090, 3000, 8085, 8089, 5433)
- [ ] Docker out of memory (needs 4GB+)
- [ ] Build failures (check logs)
- [ ] Network issues

---

**4. Option B: Local Development Setup**

**Prerequisites Check:**
```bash
# Java
java -version
# Expected: java 21 or higher

# Node.js
node -v
# Expected: v20 or higher

# Go
go version
# Expected: go1.22 or higher

# Docker
docker --version
# Expected: Docker version 24.0+
```

**Step 1: Environment File Setup**

**Linux/Mac:**
```bash
cp frontend/.env.local.example frontend/.env.local
cp core-java/.env.example core-java/.env
cp edge-go/.env.example edge-go/.env
cp infra/.env.example infra/.env
```

**Windows (Command Prompt):**
```cmd
copy frontend\.env.local.example frontend\.env.local
copy core-java\.env.example core-java\.env
copy edge-go\.env.example edge-go\.env
copy infra\.env.example infra\.env
```

**Windows (PowerShell):**
```powershell
Copy-Item frontend\.env.local.example frontend\.env.local
Copy-Item core-java\.env.example core-java\.env
Copy-Item edge-go\.env.example edge-go\.env
Copy-Item infra\.env.example infra\.env
```

**Verification:**
```bash
# Check files created
ls -la frontend/.env.local
ls -la core-java/.env
ls -la edge-go/.env
ls -la infra/.env

# Check git status (should NOT show .env files)
git status
# .env files should not appear (gitignored)
```

**Expected:**
- ✅ All 4 .env files created
- ✅ Files NOT tracked by Git
- ✅ Default values present in files

---

**Step 2: Start Infrastructure**

```bash
cd infra
docker compose up -d
cd ..
```

**Verification:**
```bash
# Check containers
docker ps | grep -E "jtoye-postgres|jtoye-keycloak"

# Wait for Keycloak (takes 30-60 seconds)
sleep 60

# Test PostgreSQL
docker exec -it jtoye-postgres psql -U jtoye -d jtoye -c "SELECT 1"
# Expected: 1 row returned

# Test Keycloak
curl http://localhost:8085/realms/jtoye-dev
# Expected: JSON response with realm info
```

**Expected:**
- ✅ PostgreSQL running on port 5433
- ✅ Keycloak running on port 8085
- ✅ Keycloak realm imported
- ✅ Test users exist

---

**Step 3: Start Backend**

```bash
./scripts/run-app.sh
```

**Expected:**
- ✅ Gradle downloads dependencies (first time: 2-3 minutes)
- ✅ Flyway runs migrations
- ✅ Application starts on port 9090
- ✅ Health endpoint responds

**Verification:**
```bash
# In another terminal
curl http://localhost:9090/actuator/health
# Expected: {"status":"UP","components":{"db":{"status":"UP"},...}}

# Check Swagger UI
curl -I http://localhost:9090/swagger-ui.html
# Expected: HTTP/1.1 200
```

**Common Issues:**
- [ ] Port 9090 already in use
- [ ] Database connection refused (Postgres not ready)
- [ ] Flyway migration errors
- [ ] Java version mismatch

---

**Step 4: Start Frontend**

```bash
cd frontend
npm install
npm run dev
```

**Expected:**
- ✅ Dependencies install (first time: 1-2 minutes)
- ✅ Environment variables validated at startup
- ✅ Next.js starts on port 3000
- ✅ No build errors

**Verification:**
```bash
# In another terminal
curl -I http://localhost:3000
# Expected: HTTP/1.1 200

# Check for environment validation
# Logs should show: "✅ Environment variables validated successfully"
```

**Common Issues:**
- [ ] Missing .env.local file
- [ ] Environment validation fails
- [ ] Port 3000 in use
- [ ] npm install failures

---

## Pre-Test Setup

### Test Data Requirements

**Test Users (Pre-configured in Keycloak):**

| Username | Password | Tenant | Purpose |
|----------|----------|--------|---------|
| admin-user | admin123 | - | Admin testing |
| tenant-a-user | password123 | Tenant A | Tenant A testing |
| tenant-b-user | password123 | Tenant B | Tenant B testing |

**Test Tenant IDs:**
```bash
TENANT_A=00000000-0000-0000-0000-000000000001
TENANT_B=00000000-0000-0000-0000-000000000002
```

### Getting Authentication Tokens

**Option 1: Via Keycloak API (Recommended)**

```bash
KC=http://localhost:8085

# Get token for Tenant A
TOKEN_A=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

echo "Tenant A Token: $TOKEN_A"

# Get token for Tenant B
TOKEN_B=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

echo "Tenant B Token: $TOKEN_B"
```

**Option 2: Via Frontend (Manual)**
1. Go to http://localhost:3000
2. Click "Sign In"
3. Login with tenant-a-user / password123
4. Open Browser DevTools → Application → Cookies
5. Find `next-auth.session-token`

---

## Functional Testing

### 1. Shops CRUD Operations

#### Create Shop

**Request:**
```bash
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "QA Test Shop",
    "address": "123 Test Street, London",
    "phone": "+44 20 1234 5678"
  }' | jq

# Save the ID
SHOP_ID=$(curl -s -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"QA Shop","address":"Test St"}' | jq -r .id)
```

**Expected:**
- ✅ HTTP 201 Created
- ✅ Response contains `id`, `name`, `address`, `phone`, `tenantId`
- ✅ `tenantId` matches Tenant A

**Test Cases:**
- [ ] Create shop with all fields
- [ ] Create shop with minimal fields (name + address only)
- [ ] Create shop with missing required field (should fail 400)
- [ ] Create shop without token (should fail 401)
- [ ] Create shop with Tenant B token (should succeed with Tenant B ID)

---

#### Read Shop (List)

**Request:**
```bash
curl -s -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/shops | jq
```

**Expected:**
- ✅ HTTP 200 OK
- ✅ Returns paginated response
- ✅ Only Tenant A's shops visible
- ✅ `content` array contains shops
- ✅ Pagination metadata present (`totalElements`, `totalPages`)

**Test Cases:**
- [ ] List shops for Tenant A
- [ ] List shops for Tenant B (different data)
- [ ] List with pagination (`?page=0&size=10`)
- [ ] List with no shops (empty array)

---

#### Read Shop (Single)

**Request:**
```bash
curl -s -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/shops/$SHOP_ID | jq
```

**Expected:**
- ✅ HTTP 200 OK
- ✅ Returns single shop object
- ✅ All fields present

**Test Cases:**
- [ ] Get existing shop
- [ ] Get non-existent shop (should fail 404)
- [ ] Get shop from another tenant (should fail 404 due to RLS)

---

#### Update Shop

**Request:**
```bash
curl -X PUT http://localhost:9090/shops/$SHOP_ID \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated QA Shop",
    "address": "456 New Address",
    "phone": "+44 20 9876 5432"
  }' | jq
```

**Expected:**
- ✅ HTTP 200 OK
- ✅ Shop updated with new values
- ✅ `id` and `tenantId` unchanged

**Test Cases:**
- [ ] Update all fields
- [ ] Update single field
- [ ] Update with invalid data (should fail 400)
- [ ] Update another tenant's shop (should fail 404)

---

#### Delete Shop

**Request:**
```bash
curl -X DELETE http://localhost:9090/shops/$SHOP_ID \
  -H "Authorization: Bearer $TOKEN_A"
```

**Expected:**
- ✅ HTTP 204 No Content
- ✅ Shop deleted from database
- ✅ Subsequent GET returns 404

**Test Cases:**
- [ ] Delete existing shop
- [ ] Delete non-existent shop (should fail 404)
- [ ] Delete another tenant's shop (should fail 404)
- [ ] Verify shop no longer in list

---

### 2. Products CRUD Operations

#### Create Product

**Request:**
```bash
curl -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "QA-TEST-001",
    "title": "QA Test Product",
    "ingredientsText": "Test ingredients (100%)",
    "allergenMask": 0,
    "pricePennies": 999
  }' | jq

PRODUCT_ID=$(curl -s -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"sku":"QA-001","title":"Test","ingredientsText":"Test","allergenMask":0}' | jq -r .id)
```

**Expected:**
- ✅ HTTP 201 Created
- ✅ Product created with all fields
- ✅ `allergenMask` defaults to 0 (no allergens)
- ✅ Natasha's Law compliance (`ingredientsText` required)

**Test Cases:**
- [ ] Create with all fields
- [ ] Create without `ingredientsText` (should fail 400 - Natasha's Law)
- [ ] Create without `allergenMask` (should default to 0)
- [ ] Create duplicate SKU (should fail 409)
- [ ] Create with allergen mask (test values 1-16383)

---

#### Allergen Mask Testing

**Allergen values (bitmask):**
```
Bit 0 (1):     Celery
Bit 1 (2):     Gluten
Bit 2 (4):     Crustaceans
Bit 3 (8):     Eggs
Bit 4 (16):    Fish
Bit 5 (32):    Lupin
Bit 6 (64):    Milk
Bit 7 (128):   Molluscs
Bit 8 (256):   Mustard
Bit 9 (512):   Nuts
Bit 10 (1024): Peanuts
Bit 11 (2048): Sesame
Bit 12 (4096): Soya
Bit 13 (8192): Sulphites
```

**Test Cases:**
```bash
# Product with milk (64) and eggs (8)
allergenMask=$((64 + 8))  # = 72
curl -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d "{\"sku\":\"MILK-EGGS\",\"title\":\"Test\",\"ingredientsText\":\"Milk, Eggs\",\"allergenMask\":$allergenMask}"
```

**Verify:**
- [ ] Allergen mask stored correctly
- [ ] Allergen mask retrieved correctly
- [ ] Frontend displays correct allergen badges

---

### 3. Orders and State Machine Testing

#### Order Lifecycle

**States:**
```
DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED
```

**Create Order (DRAFT):**
```bash
ORDER_ID=$(curl -s -X POST http://localhost:9090/orders \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"customerName":"QA Test Customer"}' | jq -r .id)

echo "Order ID: $ORDER_ID"
```

**Confirm Order (DRAFT → PENDING):**
```bash
curl -X POST http://localhost:9090/orders/$ORDER_ID/confirm \
  -H "Authorization: Bearer $TOKEN_A"

# Verify state
curl -s -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/orders/$ORDER_ID | jq '.status'
# Expected: "PENDING"
```

**Prepare Order (PENDING → CONFIRMED):**
```bash
curl -X POST http://localhost:9090/orders/$ORDER_ID/prepare \
  -H "Authorization: Bearer $TOKEN_A"
```

**Ready Order (CONFIRMED → PREPARING → READY):**
```bash
curl -X POST http://localhost:9090/orders/$ORDER_ID/ready \
  -H "Authorization: Bearer $TOKEN_A"
```

**Complete Order (READY → COMPLETED):**
```bash
curl -X POST http://localhost:9090/orders/$ORDER_ID/complete \
  -H "Authorization: Bearer $TOKEN_A"
```

**Test Cases:**
- [ ] Create order in DRAFT state
- [ ] Transition through all states successfully
- [ ] Invalid state transitions rejected (e.g., DRAFT → COMPLETED)
- [ ] State transition without auth fails
- [ ] State visible in audit logs (Envers)

---

### 4. Customers CRUD Operations

**Create Customer:**
```bash
CUSTOMER_ID=$(curl -s -X POST http://localhost:9090/customers \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "QA Test Customer",
    "email": "qa@test.com",
    "phone": "+44 20 1111 2222",
    "allergenMask": 72
  }' | jq -r .id)
```

**Test Cases:**
- [ ] Create customer with allergen mask
- [ ] Update customer allergen preferences
- [ ] Retrieve customer details
- [ ] Delete customer

### 5. Batch Sync API

**Sync Batch (from Edge):**
```bash
curl -X POST http://localhost:9090/sync/batch \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{
    "items": [
      {
        "type": "shop",
        "name": "Edge Shop",
        "address": "456 Edge Rd"
      },
      {
        "type": "product",
        "sku": "EDGE-001",
        "title": "Edge Product",
        "pricePennies": 1250,
        "ingredientsText": "Natural",
        "allergenMask": 0
      }
    ]
  }'
```

**Test Cases:**
- [ ] Batch sync with shops only
- [ ] Batch sync with products only
- [ ] Mixed batch processing
- [ ] Cache eviction verified (Shops/Products caches cleared)

---

## Security Testing

### 1. Multi-Tenant Isolation (RLS)

**Critical Test:** Verify tenant data isolation

**Setup:**
```bash
# Create shop for Tenant A
SHOP_A=$(curl -s -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant A Shop","address":"A Street"}' | jq -r .id)

# Create shop for Tenant B
SHOP_B=$(curl -s -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant B Shop","address":"B Avenue"}' | jq -r .id)
```

**Test Isolation:**
```bash
# Tenant A lists shops (should only see their own)
curl -s -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/shops | jq '.content[] | .name'
# Expected: ["Tenant A Shop"]

# Tenant B lists shops (should only see their own)
curl -s -H "Authorization: Bearer $TOKEN_B" \
  http://localhost:9090/shops | jq '.content[] | .name'
# Expected: ["Tenant B Shop"]

# Tenant A tries to access Tenant B's shop
curl -I -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/shops/$SHOP_B
# Expected: HTTP/1.1 404 Not Found
```

**Test Cases:**
- [ ] Tenant A cannot see Tenant B's shops
- [ ] Tenant B cannot see Tenant A's shops
- [ ] Tenant A cannot access Tenant B's shop by ID
- [ ] Tenant B cannot update Tenant A's shop
- [ ] Tenant A cannot delete Tenant B's shop
- [ ] Same isolation for Products
- [ ] Same isolation for Orders
- [ ] Same isolation for Customers

---

### 2. Authentication Testing

**No Token:**
```bash
curl -I http://localhost:9090/shops
# Expected: HTTP/1.1 401 Unauthorized
```

**Invalid Token:**
```bash
curl -I -H "Authorization: Bearer invalid-token-here" \
  http://localhost:9090/shops
# Expected: HTTP/1.1 401 Unauthorized
```

**Expired Token:**
```bash
# Get token, wait for expiration (default: 5 minutes), then use
# Expected: HTTP/1.1 401 Unauthorized
```

**Test Cases:**
- [ ] All endpoints require authentication (except /health, /actuator/health)
- [ ] Invalid tokens rejected
- [ ] Expired tokens rejected
- [ ] Malformed Authorization header rejected

---

### 3. Authorization Testing

**Test Cases:**
- [ ] JWT must contain `tenant_id` claim
- [ ] Requests without `tenant_id` fail or use header fallback (dev only)
- [ ] Admin users can access all tenants (if implemented)
- [ ] Regular users limited to their tenant

---

## Performance Testing

### 1. Load Testing

**Basic Load Test:**
```bash
# Install hey (HTTP load generator)
# go install github.com/rakyll/hey@latest

# Test shops endpoint
hey -n 1000 -c 10 \
  -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/shops

# Expected:
# - 95th percentile < 200ms
# - No errors
# - Success rate 100%
```

**Test Cases:**
- [ ] 100 concurrent users
- [ ] 1000 requests/second
- [ ] Response time < 200ms (p95)
- [ ] No errors under load
- [ ] Database connections don't exhaust

---

### 2. Database Performance

**Query Performance:**
```bash
# Connect to database
docker exec -it jtoye-postgres psql -U jtoye -d jtoye

# Enable query timing
\timing on

# Test RLS performance
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
SELECT COUNT(*) FROM shops;
-- Expected: < 10ms

# Test with indexes
EXPLAIN ANALYZE SELECT * FROM shops WHERE tenant_id = '00000000-0000-0000-0000-000000000001';
-- Should use index scan, not sequential
```

**Test Cases:**
- [ ] Queries use indexes
- [ ] RLS policies don't cause performance degradation
- [ ] Connection pool doesn't exhaust
- [ ] No N+1 query problems

---

## Integration Testing

### 1. Full CRUD Workflow

**Scenario: Create shop, products, customer, and order**

```bash
# 1. Create shop
SHOP=$(curl -s -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Integration Test Shop","address":"Test St"}' | jq -r .id)

# 2. Create products
PROD1=$(curl -s -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"sku":"INT-001","title":"Product 1","ingredientsText":"Test","allergenMask":0,"pricePennies":1000}' | jq -r .id)

PROD2=$(curl -s -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"sku":"INT-002","title":"Product 2","ingredientsText":"Test","allergenMask":0,"pricePennies":2000}' | jq -r .id)

# 3. Create customer
CUSTOMER=$(curl -s -X POST http://localhost:9090/customers \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Customer","email":"test@example.com","allergenMask":0}' | jq -r .id)

# 4. Create order
ORDER=$(curl -s -X POST http://localhost:9090/orders \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d "{\"customerName\":\"Test Customer\"}" | jq -r .id)

# 5. Complete order lifecycle
curl -X POST http://localhost:9090/orders/$ORDER/confirm \
  -H "Authorization: Bearer $TOKEN_A"
curl -X POST http://localhost:9090/orders/$ORDER/prepare \
  -H "Authorization: Bearer $TOKEN_A"
curl -X POST http://localhost:9090/orders/$ORDER/ready \
  -H "Authorization: Bearer $TOKEN_A"
curl -X POST http://localhost:9090/orders/$ORDER/complete \
  -H "Authorization: Bearer $TOKEN_A"

# 6. Verify final state
curl -s -H "Authorization: Bearer $TOKEN_A" \
  http://localhost:9090/orders/$ORDER | jq '{id, status, customerName}'
```

**Expected:**
- ✅ All entities created successfully
- ✅ Order transitions through all states
- ✅ Final state is COMPLETED
- ✅ All data linked correctly

---

### 2. Frontend Integration

**Manual Test:**
1. Open http://localhost:3000
2. Sign in with `tenant-a-user` / `password123`
3. Navigate to Dashboard
4. Go to Shops page
5. Create new shop
6. Verify shop appears in list
7. Edit shop
8. Delete shop
9. Repeat for Products, Orders, Customers

**Test Cases:**
- [ ] Login flow works
- [ ] Dashboard shows metrics
- [ ] CRUD operations work in UI
- [ ] Forms validate input
- [ ] Errors displayed to user
- [ ] Toast notifications work
- [ ] Tenant isolation in UI

---

## End-to-End Scenarios

### Scenario 1: New Tenant Onboarding

**Steps:**
1. DevOps creates tenant in Keycloak
2. Adds `tenant_id` attribute to group
3. Creates test user in group
4. User logs in via frontend
5. Creates first shop
6. Adds products
7. Creates first order

**Verification:**
- [ ] User can only see their data
- [ ] RLS enforced throughout
- [ ] Audit trail created (Envers)

---

### Scenario 2: Order Fulfillment

**Steps:**
1. Customer creates order (DRAFT)
2. Staff confirms order (PENDING)
3. Kitchen prepares order (CONFIRMED → PREPARING)
4. Order ready for pickup (READY)
5. Customer collects order (COMPLETED)

**Verification:**
- [ ] State transitions valid
- [ ] Invalid transitions rejected
- [ ] State visible in UI
- [ ] Audit trail complete

---

### Scenario 3: Multi-Tenant Concurrent Operations

**Steps:**
1. Tenant A creates shop
2. Tenant B creates shop (simultaneously)
3. Both tenants create products
4. Both tenants create orders
5. Verify complete isolation

**Verification:**
- [ ] No cross-tenant data visible
- [ ] No race conditions
- [ ] Database constraints enforced

---

## QA Checklist

### Pre-Deployment Checklist

#### Environment Setup
- [ ] Fresh clone successful
- [ ] Documentation clear and accurate
- [ ] Environment files created correctly
- [ ] Docker build successful
- [ ] Local development setup works
- [ ] All services start successfully

#### Functional Testing
- [ ] Shops CRUD - All operations work
- [ ] Products CRUD - All operations work
- [ ] Orders CRUD - All operations work
- [ ] Customers CRUD - All operations work
- [ ] State machine transitions work
- [ ] Allergen mask handling correct
- [ ] Pagination works
- [ ] Search/filter works (if implemented)

#### Security Testing
- [ ] Multi-tenant isolation verified
- [ ] RLS policies enforced
- [ ] Authentication required for all endpoints
- [ ] Invalid tokens rejected
- [ ] Tenant A cannot access Tenant B data
- [ ] Tenant B cannot access Tenant A data
- [ ] Audit trail (Envers) working

#### Integration Testing
- [ ] Frontend → Backend integration
- [ ] Backend → Database integration
- [ ] Backend → Keycloak integration
- [ ] Full workflow (shop → product → order)
- [ ] Concurrent operations successful

#### Performance Testing
- [ ] Response times acceptable (< 200ms p95)
- [ ] Load test passed (100 concurrent users)
- [ ] No memory leaks
- [ ] Database queries optimized
- [ ] Connection pools don't exhaust

#### Documentation Testing
- [ ] README accurate
- [ ] QUICK_START.md tested
- [ ] ENVIRONMENT_SETUP.md accurate
- [ ] All links work (no 404s)
- [ ] Code examples work
- [ ] Troubleshooting accurate

#### Platform Testing
- [ ] Works on Linux
- [ ] Works on macOS
- [ ] Works on Windows (WSL)
- [ ] Works with Docker Desktop
- [ ] Environment variables validated

### Post-Deployment Checklist

#### Smoke Tests
- [ ] Health endpoints respond
- [ ] Can create shop
- [ ] Can create product
- [ ] Can create order
- [ ] Can login via UI
- [ ] Multi-tenancy working

#### Monitoring
- [ ] Logs accessible
- [ ] Metrics available
- [ ] Alerts configured
- [ ] Error rates acceptable
- [ ] Performance metrics good

---

## Test Execution Log

**Tester:**
**Date:**
**Environment:** [ ] Local [ ] Docker [ ] Staging [ ] Production
**Version:**

### Test Results Summary

| Category | Total Tests | Passed | Failed | Blocked | Pass Rate |
|----------|-------------|--------|--------|---------|-----------|
| Fresh Clone | | | | | |
| Functional | | | | | |
| Security | | | | | |
| Performance | | | | | |
| Integration | | | | | |
| **TOTAL** | | | | | |

### Issues Found

| ID | Severity | Description | Steps to Reproduce | Status |
|----|----------|-------------|-------------------|--------|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |

### Notes

---

## Test Automation

### Automated Test Scripts

**Location:** `scripts/testing/`

```bash
# Run all automated tests
./scripts/testing/test-all.sh

# Run specific test suite
./scripts/testing/test-shops-crud.sh
./scripts/testing/test-multi-tenancy.sh
./scripts/testing/test-order-lifecycle.sh
```

### CI/CD Integration

Tests run automatically on:
- [ ] Pull requests
- [ ] Merge to main
- [ ] Tagged releases
- [ ] Scheduled (nightly)

---

## Success Criteria

**Project passes QA if:**
- ✅ 100% of critical tests pass
- ✅ 95%+ of all tests pass
- ✅ No high-severity bugs
- ✅ Fresh clone works on all platforms
- ✅ Multi-tenant isolation verified
- ✅ Performance targets met
- ✅ Documentation accurate

---

**Last Updated:** 2026-01-03
**Version:** 1.0
**Status:** Ready for QA Testing
