# Testing Guide

Comprehensive testing guide for J'Toye OaaS.

---

## Table of Contents

1. [Running Tests](#running-tests)
2. [API Testing](#api-testing)
3. [Manual Testing](#manual-testing)
4. [Test Data](#test-data)
5. [Integration Tests](#integration-tests)

---

## Running Tests

### Backend (Java)

**Run all tests:**
```bash
./gradlew :core-java:test
```

**Run specific test class:**
```bash
./gradlew :core-java:test --tests "uk.jtoye.core.controller.ShopControllerTest"
```

**Run with logging:**
```bash
./gradlew :core-java:test --info
```

**Test report:**
```
core-java/build-local/reports/tests/test/index.html
```

### Edge Service (Go)

**Run all tests:**
```bash
cd edge-go
go test ./...
```

**With coverage:**
```bash
go test -cover ./...
```

**Verbose output:**
```bash
go test -v ./...
```

### Frontend (Next.js)

**Run tests (if configured):**
```bash
cd frontend
npm test
```

---

## API Testing

### Prerequisites

**1. Ensure services are running:**
```bash
# Check Core API
curl http://localhost:9090/actuator/health

# Check Keycloak
curl http://localhost:8085/realms/jtoye-dev
```

**2. Get authentication token:**
```bash
KC=http://localhost:8085

TOKEN=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

echo "Token: $TOKEN"
```

### CRUD Operations

#### Shops API

**Create:**
```bash
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Main Street Shop",
    "address": "123 Main St, London",
    "phone": "+44 20 1234 5678"
  }'
```

**Read (List):**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/shops | jq
```

**Read (Single):**
```bash
SHOP_ID=<id-from-create>
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/shops/$SHOP_ID | jq
```

**Update:**
```bash
curl -X PUT http://localhost:9090/shops/$SHOP_ID \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Shop Name",
    "address": "456 New Address",
    "phone": "+44 20 9876 5432"
  }'
```

**Delete:**
```bash
curl -X DELETE http://localhost:9090/shops/$SHOP_ID \
  -H "Authorization: Bearer $TOKEN"
```

#### Products API

**Create:**
```bash
curl -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "YAM-5KG",
    "title": "Nigerian Yam 5kg",
    "ingredientsText": "Yam (100%)",
    "allergenMask": 0,
    "priceGbp": 15.99
  }'
```

**List with pagination:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/products?page=0&size=10" | jq
```

**Search by SKU:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/products?sku=YAM-5KG" | jq
```

#### Orders API

**Create Order:**
```bash
curl -X POST http://localhost:9090/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "customerName": "John Doe",
    "items": [
      {"productSku": "YAM-5KG", "quantity": 2}
    ]
  }'
```

**Get Order:**
```bash
ORDER_ID=<id-from-create>
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/orders/$ORDER_ID | jq
```

**Transition Order State:**
```bash
# DRAFT → PENDING
curl -X POST http://localhost:9090/orders/$ORDER_ID/confirm \
  -H "Authorization: Bearer $TOKEN"

# PENDING → CONFIRMED
curl -X POST http://localhost:9090/orders/$ORDER_ID/prepare \
  -H "Authorization: Bearer $TOKEN"

# CONFIRMED → PREPARING
curl -X POST http://localhost:9090/orders/$ORDER_ID/ready \
  -H "Authorization: Bearer $TOKEN"

# PREPARING → READY
curl -X POST http://localhost:9090/orders/$ORDER_ID/complete \
  -H "Authorization: Bearer $TOKEN"
```

#### Customers API

**Create:**
```bash
curl -X POST http://localhost:9090/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Jane Smith",
    "email": "jane@example.com",
    "phone": "+44 20 1111 2222",
    "allergenMask": 5
  }'
```

**Note:** `allergenMask` is a bitmask for 14 allergens (0 = no allergies).

---

## Manual Testing

### Test Multi-Tenancy

**1. Create data as Tenant A:**
```bash
TOKEN_A=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant A Shop","address":"A Street"}'
```

**2. Create data as Tenant B:**
```bash
TOKEN_B=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' \
  "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant B Shop","address":"B Avenue"}'
```

**3. Verify isolation:**
```bash
# Tenant A sees only their data
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/shops | jq '.content[].name'
# Output: ["Tenant A Shop"]

# Tenant B sees only their data
curl -s -H "Authorization: Bearer $TOKEN_B" http://localhost:9090/shops | jq '.content[].name'
# Output: ["Tenant B Shop"]
```

### Test State Machine (Orders)

**Create and transition an order through all states:**

```bash
TOKEN=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# 1. Create order (DRAFT)
ORDER_ID=$(curl -s -X POST http://localhost:9090/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Test User"}' | jq -r .id)

echo "Order ID: $ORDER_ID"

# 2. Confirm (DRAFT → PENDING)
curl -X POST http://localhost:9090/orders/$ORDER_ID/confirm \
  -H "Authorization: Bearer $TOKEN"

# 3. Check state
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/orders/$ORDER_ID | jq .status

# 4. Prepare (PENDING → CONFIRMED)
curl -X POST http://localhost:9090/orders/$ORDER_ID/prepare \
  -H "Authorization: Bearer $TOKEN"

# 5. Ready (CONFIRMED → PREPARING)
curl -X POST http://localhost:9090/orders/$ORDER_ID/ready \
  -H "Authorization: Bearer $TOKEN"

# 6. Complete (PREPARING → READY → COMPLETED)
curl -X POST http://localhost:9090/orders/$ORDER_ID/complete \
  -H "Authorization: Bearer $TOKEN"

# 7. Verify final state
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/orders/$ORDER_ID | jq '{id: .id, status: .status}'
```

### Test Frontend UI

**1. Sign in:**
- Go to: http://localhost:3000
- Click "Sign In"
- Username: `tenant-a-user`
- Password: `password123`

**2. Navigate pages:**
- Dashboard - Overview metrics
- Shops - CRUD operations on shops
- Products - CRUD operations on products
- Orders - View and manage orders
- Customers - Customer management

**3. Test CRUD:**
- Create a new shop
- Edit the shop
- Delete the shop
- Verify changes persist

**4. Test tenant isolation:**
- Sign out
- Sign in as `tenant-b-user` / `password123`
- Verify you don't see Tenant A's data

---

## Test Data

### Pre-configured Users

| Username | Password | Tenant | Description |
|----------|----------|--------|-------------|
| admin-user | admin123 | - | System admin |
| tenant-a-user | password123 | Tenant A | Regular user |
| tenant-b-user | password123 | Tenant B | Regular user |

### Sample Data Creation

**Bulk create shops:**
```bash
TOKEN=$(curl -s -d 'grant_type=password' -d 'client_id=core-api' \
  -d 'username=tenant-a-user' -d 'password=password123' \
  "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

for i in {1..5}; do
  curl -s -X POST http://localhost:9090/shops \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Shop $i\",\"address\":\"$i Test Street\"}"
done
```

**Bulk create products:**
```bash
PRODUCTS=("YAM-5KG:Nigerian Yam 5kg:15.99" "RICE-2KG:Basmati Rice 2kg:8.99" "OIL-1L:Palm Oil 1L:12.50")

for product in "${PRODUCTS[@]}"; do
  IFS=':' read -r sku title price <<< "$product"
  curl -s -X POST http://localhost:9090/products \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"sku\":\"$sku\",\"title\":\"$title\",\"priceGbp\":$price,\"ingredientsText\":\"100% natural\",\"allergenMask\":0}"
done
```

---

## Integration Tests

### RLS Integration Test

**Verify RLS at database level:**

```bash
# Connect to database
docker exec -it jtoye-postgres psql -U jtoye -d jtoye

# Create test data as superuser (bypasses RLS for setup)
INSERT INTO tenants (id, name) VALUES
  ('00000000-0000-0000-0000-000000000001', 'Tenant A'),
  ('00000000-0000-0000-0000-000000000002', 'Tenant B');

INSERT INTO shops (id, tenant_id, name, address) VALUES
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'Shop A', 'Address A'),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'Shop B', 'Address B');

# Switch to app user (RLS enforced)
SET ROLE jtoye;

# Set tenant context
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';

# Should only see Tenant A's shop
SELECT name FROM shops;
-- Output: Shop A

# Change tenant
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000002';

# Should only see Tenant B's shop
SELECT name FROM shops;
-- Output: Shop B
```

### End-to-End Test

**Complete workflow test:**

```bash
#!/bin/bash
set -e

KC=http://localhost:8085
API=http://localhost:9090

# 1. Get token
echo "1. Getting authentication token..."
TOKEN=$(curl -s -d 'grant_type=password' -d 'client_id=core-api' \
  -d 'username=tenant-a-user' -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# 2. Create shop
echo "2. Creating shop..."
SHOP_ID=$(curl -s -X POST $API/shops \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"E2E Test Shop","address":"123 Test St"}' | jq -r .id)

echo "Shop ID: $SHOP_ID"

# 3. Create product
echo "3. Creating product..."
PRODUCT_ID=$(curl -s -X POST $API/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sku":"TEST-1","title":"Test Product","ingredientsText":"Test","allergenMask":0,"priceGbp":9.99}' | jq -r .id)

echo "Product ID: $PRODUCT_ID"

# 4. Create customer
echo "4. Creating customer..."
CUSTOMER_ID=$(curl -s -X POST $API/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Customer","email":"test@example.com","allergenMask":0}' | jq -r .id)

echo "Customer ID: $CUSTOMER_ID"

# 5. Create order
echo "5. Creating order..."
ORDER_ID=$(curl -s -X POST $API/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"customerName\":\"Test Customer\"}" | jq -r .id)

echo "Order ID: $ORDER_ID"

# 6. Verify all created
echo "6. Verifying data..."
curl -s -H "Authorization: Bearer $TOKEN" $API/shops/$SHOP_ID | jq .name
curl -s -H "Authorization: Bearer $TOKEN" $API/products/$PRODUCT_ID | jq .title
curl -s -H "Authorization: Bearer $TOKEN" $API/customers/$CUSTOMER_ID | jq .name
curl -s -H "Authorization: Bearer $TOKEN" $API/orders/$ORDER_ID | jq .status

echo "✅ E2E test passed!"
```

---

## CI/CD Testing

Tests run automatically in GitHub Actions:

**Workflow:** `.github/workflows/ci-cd.yml`

**Stages:**
1. Build
2. Unit Tests
3. Integration Tests
4. Security Scan
5. Docker Build

**View results:**
```
https://github.com/your-org/JToye_OaaS_2026/actions
```

---

## Performance Testing

**Load testing:**
```bash
cd infra/load-testing
./load-test.sh
```

See [load-testing/README.md](../infra/load-testing/README.md) for details.

---

## Troubleshooting Tests

### Tests fail with "Connection refused"

**Cause:** PostgreSQL not running

**Fix:**
```bash
cd infra && docker-compose up -d
# Wait 10 seconds for startup
./gradlew :core-java:test
```

### Tests fail with "Unauthorized"

**Cause:** Keycloak not running or token expired

**Fix:**
```bash
# Verify Keycloak is running
curl http://localhost:8085/realms/jtoye-dev

# Get fresh token
TOKEN=$(curl -s -d 'grant_type=password' -d 'client_id=core-api' \
  -d 'username=tenant-a-user' -d 'password=password123' \
  "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)
```

### RLS tests show wrong data

**Cause:** Using superuser or tenant context not set

**Fix:**
```sql
-- Check current role
SELECT current_user;
-- Should be 'jtoye' or 'jtoye_app', NOT 'postgres'

-- Check tenant setting
SELECT current_setting('app.current_tenant_id', true);
-- Should show a UUID

-- Set if missing
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
```

---

**See Also:**
- [CONFIGURATION.md](CONFIGURATION.md) - Authentication setup
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Production testing
- [USER_GUIDE.md](USER_GUIDE.md) - Manual testing workflows
