# J'Toye OaaS 2026 - User Guide

**Last Updated:** December 30, 2025
**Version:** 0.7.0 (Phase 2.1)
**Audience:** Developers, QA Engineers, API Users

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Authentication Setup](#authentication-setup)
4. [API Endpoints](#api-endpoints)
5. [Manual Testing with cURL](#manual-testing-with-curl)
6. [Manual Testing with Postman](#manual-testing-with-postman)
7. [Testing Multi-Tenant Isolation](#testing-multi-tenant-isolation)
8. [Common Workflows](#common-workflows)
9. [Troubleshooting](#troubleshooting)
10. [Appendix](#appendix)

---

## Overview

J'Toye OaaS 2026 is a multi-tenant e-commerce platform with:
- **JWT Authentication** via Keycloak
- **Row-Level Security (RLS)** for tenant isolation
- **REST APIs** for Shops, Products, and Orders
- **Audit Trail** via Hibernate Envers

### Key Features
- ✅ Multi-tenant architecture (Tenant A and Tenant B)
- ✅ JWT-based authentication (no header spoofing)
- ✅ Database-level tenant isolation (PostgreSQL RLS)
- ✅ Order management with status workflow
- ✅ Product catalog management
- ✅ Shop management
- ✅ Audit history for all entities

---

## Quick Start

### Prerequisites
- Docker and Docker Compose installed
- `curl` or Postman for API testing
- `jq` for JSON processing (optional but recommended)

### 1. Start the Application

```bash
# Clone and navigate to project
cd /path/to/JToye_OaaS_2026

# Start all services (PostgreSQL, Keycloak, core-java)
./scripts/run-app.sh
```

Wait for services to be ready:
- **PostgreSQL**: Port 5433
- **Keycloak**: http://localhost:8080
- **core-java API**: http://localhost:9090
- **edge-go API**: http://localhost:8089 (Docker) or 8080 (Local)
- **Frontend UI**: http://localhost:3000

### 2. Verify Services

```bash
# Check API health
curl http://localhost:9090/health
```

Expected output: `OK`

```bash
# Check Edge health
curl http://localhost:8089/health
```

### 3. Load Test Data

```bash
# Load shops and products for both tenants
bash scripts/testing/diagnose-jwt-issue.sh
```

---

## Authentication Setup

### Understanding Multi-Tenant Authentication

The system has **2 pre-configured tenants**:

| Tenant | Tenant ID | Username | Password | Keycloak Group |
|--------|-----------|----------|----------|----------------|
| Tenant A | `00000000-0000-0000-0000-000000000001` | `tenant-a-user` | `password` | `tenant-a` |
| Tenant B | `00000000-0000-0000-0000-000000000002` | `tenant-b-user` | `password` | `tenant-b` |

### Get JWT Token for Tenant A

```bash
export TENANT_A_TOKEN=$(curl -s -X POST \
  'http://localhost:8080/realms/jtoye/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=jtoye-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password' \
  | jq -r '.access_token')

echo "Tenant A Token: $TENANT_A_TOKEN"
```

### Get JWT Token for Tenant B

```bash
export TENANT_B_TOKEN=$(curl -s -X POST \
  'http://localhost:8080/realms/jtoye/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=jtoye-client' \
  -d 'username=tenant-b-user' \
  -d 'password=password' \
  | jq -r '.access_token')

echo "Tenant B Token: $TENANT_B_TOKEN"
```

### Verify Token Claims

```bash
# Decode Tenant A token (JWT payload is base64 encoded)
echo $TENANT_A_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq

# Look for tenant_id claim:
# {
#   "tenant_id": "00000000-0000-0000-0000-000000000001",
#   ...
# }
```

---

## API Endpoints

### Base URL
```
http://localhost:9090
```

### Available Endpoints

#### Health & Monitoring
- `GET /actuator/health` - Health check
- `GET /actuator/info` - Application info

#### Shops API
- `GET /shops` - List all shops (tenant-scoped)
- `GET /shops/{id}` - Get shop by ID
- `POST /shops` - Create shop
- `PUT /shops/{id}` - Update shop
- `DELETE /shops/{id}` - Delete shop

#### Products API
- `GET /products` - List all products (tenant-scoped)
- `GET /products/{id}` - Get product by ID
- `POST /products` - Create product
- `PUT /products/{id}` - Update product
- `DELETE /products/{id}` - Delete product

#### Orders API
- `GET /orders` - List all orders (paginated, tenant-scoped)
- `GET /orders/{id}` - Get order by ID
- `POST /orders` - Create order
- `DELETE /orders/{id}` - Delete order
- Transitions:
  - `POST /orders/{id}/submit` - Draft to Pending
  - `POST /orders/{id}/confirm` - Pending to Confirmed
  - `POST /orders/{id}/start-prep` - Confirmed to Preparing
  - `POST /orders/{id}/mark-ready` - Preparing to Ready
  - `POST /orders/{id}/complete` - Ready to Completed
  - `POST /orders/{id}/cancel` - Cancel at any stage

#### Customers API
- `GET /customers` - List all customers (tenant-scoped)
- `POST /customers` - Create customer
- `PUT /customers/{id}` - Update customer
- `DELETE /customers/{id}` - Delete customer

#### Financial Transactions API
- `GET /financial-transactions` - List all transactions (tenant-scoped)
- `POST /financial-transactions` - Create transaction (calculates VAT)

### Order Status Values
- `DRAFT` - Order being created
- `PENDING` - Order submitted, awaiting confirmation
- `CONFIRMED` - Order confirmed
- `PREPARING` - Order being prepared
- `READY` - Order ready for pickup/delivery
- `COMPLETED` - Order completed
- `CANCELLED` - Order cancelled

---

## Manual Testing with cURL

### 1. List Shops (Tenant A)

```bash
curl -s -X GET \
  'http://localhost:9090/shops' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Array of shops belonging to Tenant A only
```

### 2. Create a Shop (Tenant A)

```bash
curl -s -X POST \
  'http://localhost:9090/shops' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "My Coffee Shop",
    "address": "123 Main Street, London"
  }' | jq

# Expected: Created shop with generated ID
# Save the shop ID for later use
export SHOP_ID="<shop-id-from-response>"
```

### 3. List Products (Tenant A)

```bash
curl -s -X GET \
  'http://localhost:9090/products' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Array of products belonging to Tenant A only
```

### 4. Create a Product (Tenant A)

```bash
curl -s -X POST \
  'http://localhost:9090/products' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "sku": "COFFEE-001",
    "title": "Espresso",
    "ingredientsText": "Coffee beans, water",
    "allergenMask": 0
  }' | jq

# Expected: Created product with generated ID
# Save the product ID for later use
export PRODUCT_ID="<product-id-from-response>"
```

### 5. Create an Order (Tenant A)

```bash
curl -s -X POST \
  'http://localhost:9090/orders' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"shopId\": \"$SHOP_ID\",
    \"customerName\": \"John Doe\",
    \"customerEmail\": \"john@example.com\",
    \"customerPhone\": \"+44 20 1234 5678\",
    \"items\": [
      {
        \"productId\": \"$PRODUCT_ID\",
        \"quantity\": 2
      }
    ]
  }" | jq

# Expected: Created order with auto-generated order number (ORD-{timestamp}-{random})
# Save the order ID for later use
export ORDER_ID="<order-id-from-response>"
```

### 6. Get Order by ID

```bash
curl -s -X GET \
  "http://localhost:9090/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Full order details with items
```

### 7. Update Order Status

```bash
# Progress order from DRAFT to PENDING
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=PENDING" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Order with updated status

# Progress to CONFIRMED
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=CONFIRMED" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Progress to PREPARING
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=PREPARING" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Progress to READY
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=READY" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Progress to COMPLETED
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=COMPLETED" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq
```

### 8. Filter Orders by Status

```bash
curl -s -X GET \
  'http://localhost:9090/orders/status/COMPLETED' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Array of completed orders for Tenant A
```

### 9. Get Orders for a Shop

```bash
curl -s -X GET \
  "http://localhost:9090/orders/shop/$SHOP_ID" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Array of orders for the specified shop
```

### 10. List All Orders (Paginated)

```bash
# First page (20 items)
curl -s -X GET \
  'http://localhost:9090/orders?page=0&size=20' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  | jq

# Expected: Paginated response with orders, totalElements, totalPages, etc.
```

---

## Testing Multi-Tenant Isolation

### Verify Tenant A Cannot Access Tenant B's Data

```bash
# 1. Create a shop as Tenant B
TENANT_B_SHOP=$(curl -s -X POST \
  'http://localhost:9090/shops' \
  -H "Authorization: Bearer $TENANT_B_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Tenant B Shop",
    "address": "456 Other Street"
  }' | jq -r '.id')

echo "Tenant B Shop ID: $TENANT_B_SHOP"

# 2. Try to access Tenant B's shop as Tenant A (should fail)
curl -s -X GET \
  "http://localhost:9090/shops/$TENANT_B_SHOP" \
  -H "Authorization: Bearer $TENANT_A_TOKEN"

# Expected: 404 Not Found (RLS blocks access)

# 3. Verify Tenant B CAN access their own shop
curl -s -X GET \
  "http://localhost:9090/shops/$TENANT_B_SHOP" \
  -H "Authorization: Bearer $TENANT_B_TOKEN" \
  | jq

# Expected: Shop details returned
```

### Verify Order Isolation

```bash
# 1. Create order as Tenant A (using previously created shop/product)
TENANT_A_ORDER=$(curl -s -X POST \
  'http://localhost:9090/orders' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"shopId\": \"$SHOP_ID\",
    \"customerName\": \"Alice\",
    \"items\": [
      {
        \"productId\": \"$PRODUCT_ID\",
        \"quantity\": 1
      }
    ]
  }" | jq -r '.id')

echo "Tenant A Order ID: $TENANT_A_ORDER"

# 2. Try to access as Tenant B (should fail)
curl -s -X GET \
  "http://localhost:9090/orders/$TENANT_A_ORDER" \
  -H "Authorization: Bearer $TENANT_B_TOKEN"

# Expected: 404 Not Found (RLS blocks access)

# 3. List orders as Tenant B (should not include Tenant A's order)
curl -s -X GET \
  'http://localhost:9090/orders' \
  -H "Authorization: Bearer $TENANT_B_TOKEN" \
  | jq

# Expected: Empty array or only Tenant B's orders
```

---

## Manual Testing with Postman

### 1. Import Collection

Create a new Postman collection named "J'Toye OaaS 2026"

### 2. Setup Environment Variables

Create environment with these variables:
- `base_url`: `http://localhost:9090`
- `keycloak_url`: `http://localhost:8080`
- `tenant_a_token`: (leave empty, will be set by script)
- `tenant_b_token`: (leave empty, will be set by script)
- `shop_id`: (leave empty, will be set from response)
- `product_id`: (leave empty, will be set from response)
- `order_id`: (leave empty, will be set from response)

### 3. Get Tenant A Token

**Request:**
```
POST {{keycloak_url}}/realms/jtoye/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

Body (x-www-form-urlencoded):
grant_type: password
client_id: jtoye-client
username: tenant-a-user
password: password
```

**Tests Script:**
```javascript
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

var jsonData = pm.response.json();
pm.environment.set("tenant_a_token", jsonData.access_token);
```

### 4. Get Tenant B Token

Same as above but:
- Set environment variable: `tenant_b_token`
- Username: `tenant-b-user`

### 5. List Shops (Tenant A)

**Request:**
```
GET {{base_url}}/shops
Authorization: Bearer {{tenant_a_token}}
```

**Tests Script:**
```javascript
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

pm.test("Response is array", function () {
    var jsonData = pm.response.json();
    pm.expect(jsonData).to.be.an('array');
});
```

### 6. Create Shop (Tenant A)

**Request:**
```
POST {{base_url}}/shops
Authorization: Bearer {{tenant_a_token}}
Content-Type: application/json

Body (JSON):
{
  "name": "Postman Test Shop",
  "address": "123 API Street"
}
```

**Tests Script:**
```javascript
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

var jsonData = pm.response.json();
pm.environment.set("shop_id", jsonData.id);
```

### 7. Create Product (Tenant A)

**Request:**
```
POST {{base_url}}/products
Authorization: Bearer {{tenant_a_token}}
Content-Type: application/json

Body (JSON):
{
  "sku": "TEST-001",
  "title": "Test Product",
  "ingredientsText": "Test ingredients",
  "allergenMask": 0
}
```

**Tests Script:**
```javascript
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

var jsonData = pm.response.json();
pm.environment.set("product_id", jsonData.id);
```

### 8. Create Order (Tenant A)

**Request:**
```
POST {{base_url}}/orders
Authorization: Bearer {{tenant_a_token}}
Content-Type: application/json

Body (JSON):
{
  "shopId": "{{shop_id}}",
  "customerName": "Postman Test User",
  "customerEmail": "test@example.com",
  "customerPhone": "+44 20 1234 5678",
  "items": [
    {
      "productId": "{{product_id}}",
      "quantity": 2
    }
  ]
}
```

**Tests Script:**
```javascript
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

var jsonData = pm.response.json();
pm.environment.set("order_id", jsonData.id);

pm.test("Order number is generated", function () {
    pm.expect(jsonData.orderNumber).to.match(/^ORD-\d+-\d+$/);
});

pm.test("Status is DRAFT", function () {
    pm.expect(jsonData.status).to.eql("DRAFT");
});
```

### 9. Update Order Status

**Request:**
```
PATCH {{base_url}}/orders/{{order_id}}/status?status=PENDING
Authorization: Bearer {{tenant_a_token}}
```

**Tests Script:**
```javascript
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

pm.test("Status updated to PENDING", function () {
    var jsonData = pm.response.json();
    pm.expect(jsonData.status).to.eql("PENDING");
});
```

### 10. Test Tenant Isolation

**Request:**
```
GET {{base_url}}/shops/{{shop_id}}
Authorization: Bearer {{tenant_b_token}}
```

**Tests Script:**
```javascript
pm.test("Status code is 404 (tenant isolation)", function () {
    pm.response.to.have.status(404);
});
```

---

## Common Workflows

### Workflow 1: Complete Order Lifecycle

```bash
# 1. Create order
ORDER_ID=$(curl -s -X POST \
  'http://localhost:9090/orders' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"shopId\": \"$SHOP_ID\",
    \"customerName\": \"Jane Doe\",
    \"items\": [{\"productId\": \"$PRODUCT_ID\", \"quantity\": 3}]
  }" | jq -r '.id')

# 2. Submit order (DRAFT → PENDING)
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=PENDING" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq

# 3. Confirm order (PENDING → CONFIRMED)
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=CONFIRMED" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq

# 4. Start preparation (CONFIRMED → PREPARING)
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=PREPARING" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq

# 5. Mark ready (PREPARING → READY)
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=READY" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq

# 6. Complete order (READY → COMPLETED)
curl -s -X PATCH \
  "http://localhost:9090/orders/$ORDER_ID/status?status=COMPLETED" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq

# 7. View completed order
curl -s -X GET \
  "http://localhost:9090/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq
```

### Workflow 2: Multi-Shop Setup

```bash
# 1. Create multiple shops
for shop in "Downtown Cafe" "Airport Branch" "Mall Kiosk"; do
  curl -s -X POST \
    'http://localhost:9090/shops' \
    -H "Authorization: Bearer $TENANT_A_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"name\": \"$shop\", \"address\": \"Location TBD\"}" | jq -r '.id'
done

# 2. List all shops
curl -s -X GET \
  'http://localhost:9090/shops' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq
```

### Workflow 3: Product Catalog Setup

```bash
# Array of products
products='[
  {"sku":"ESP-001","title":"Espresso","ingredientsText":"Coffee beans, water"},
  {"sku":"LAT-001","title":"Latte","ingredientsText":"Coffee, milk"},
  {"sku":"CAP-001","title":"Cappuccino","ingredientsText":"Coffee, milk, foam"}
]'

# Create each product
echo $products | jq -c '.[]' | while read product; do
  curl -s -X POST \
    'http://localhost:9090/products' \
    -H "Authorization: Bearer $TENANT_A_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$product" | jq -r '.id'
  sleep 0.5
done

# List all products
curl -s -X GET \
  'http://localhost:9090/products' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq
```

### Workflow 4: Bulk Order Creation

```bash
# Create 10 test orders
for i in {1..10}; do
  curl -s -X POST \
    'http://localhost:9090/orders' \
    -H "Authorization: Bearer $TENANT_A_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{
      \"shopId\": \"$SHOP_ID\",
      \"customerName\": \"Customer $i\",
      \"items\": [{\"productId\": \"$PRODUCT_ID\", \"quantity\": $i}]
    }" | jq -r '.orderNumber'
  sleep 0.5
done

# List all orders
curl -s -X GET \
  'http://localhost:9090/orders?size=50' \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq
```

---

## Troubleshooting

### Issue 1: "Unauthorized" or 401 Response

**Symptoms:**
```json
{
  "error": "Unauthorized",
  "status": 401
}
```

**Solutions:**
1. Check token is valid and not expired (tokens expire after 5 minutes by default)
2. Regenerate token:
   ```bash
   export TENANT_A_TOKEN=$(curl -s -X POST \
     'http://localhost:8080/realms/jtoye/protocol/openid-connect/token' \
     -H 'Content-Type: application/x-www-form-urlencoded' \
     -d 'grant_type=password' \
     -d 'client_id=jtoye-client' \
     -d 'username=tenant-a-user' \
     -d 'password=password' \
     | jq -r '.access_token')
   ```
3. Verify Keycloak is running: `curl http://localhost:8080/realms/jtoye`

### Issue 2: "404 Not Found" for Existing Resource

**Symptoms:**
- GET request returns 404
- Resource exists in database

**Solutions:**
1. Verify you're using the correct tenant token
2. Check resource belongs to your tenant:
   ```bash
   # Connect to database
   docker exec -it jtoye-postgres psql -U jtoye -d jtoye

   # Check resource tenant_id
   SELECT id, tenant_id FROM shops WHERE id = '<shop-id>';
   ```
3. Ensure RLS is working: See [TESTING_GUIDE.md](TESTING_GUIDE.md)

### Issue 3: Empty Response Array

**Symptoms:**
```json
[]
```

**Solutions:**
1. Verify test data is loaded:
   ```bash
   bash scripts/testing/diagnose-jwt-issue.sh
   ```
2. Check you're using correct tenant token
3. Verify database has data:
   ```bash
   docker exec -it jtoye-postgres psql -U jtoye -d jtoye -c \
     "SELECT COUNT(*) FROM shops;"
   ```

### Issue 4: Order Creation Fails

**Symptoms:**
```json
{
  "error": "Bad Request",
  "message": "Validation failed"
}
```

**Solutions:**
1. Verify shop exists and belongs to your tenant
2. Verify all products exist and belong to your tenant
3. Check request body has all required fields:
   - `shopId` (required)
   - `items` (required, non-empty array)
   - Each item must have `productId` and `quantity`
4. Example valid request:
   ```json
   {
     "shopId": "valid-shop-id",
     "customerName": "John Doe",
     "items": [
       {
         "productId": "valid-product-id",
         "quantity": 1
       }
     ]
   }
   ```

### Issue 5: Cannot Update Order Status

**Symptoms:**
- PATCH request fails
- Status doesn't change

**Solutions:**
1. Verify order exists and belongs to your tenant
2. Check status value is valid:
   - Valid: `DRAFT`, `PENDING`, `CONFIRMED`, `PREPARING`, `READY`, `COMPLETED`, `CANCELLED`
   - Case-sensitive!
3. Correct syntax:
   ```bash
   curl -X PATCH \
     "http://localhost:9090/orders/$ORDER_ID/status?status=PENDING" \
     -H "Authorization: Bearer $TENANT_A_TOKEN"
   ```

### Issue 6: Database Connection Error

**Symptoms:**
```
Unable to acquire JDBC Connection
```

**Solutions:**
1. Verify PostgreSQL is running:
   ```bash
   docker ps | grep jtoye-postgres
   ```
2. Check port 5433 is available:
   ```bash
   netstat -an | grep 5433
   ```
3. Restart services:
   ```bash
   ./scripts/run-app.sh
   ```

---

## Appendix

### A. Complete cURL Script for Testing

Save as `test-api.sh`:

```bash
#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

BASE_URL="http://localhost:9090"
KEYCLOAK_URL="http://localhost:8080"

echo "=== J'Toye OaaS 2026 API Test Script ==="
echo

# 1. Get Tenant A token
echo "1. Getting Tenant A token..."
TENANT_A_TOKEN=$(curl -s -X POST \
  "${KEYCLOAK_URL}/realms/jtoye/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=jtoye-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password' \
  | jq -r '.access_token')

if [ -z "$TENANT_A_TOKEN" ] || [ "$TENANT_A_TOKEN" = "null" ]; then
  echo -e "${RED}Failed to get token${NC}"
  exit 1
fi
echo -e "${GREEN}✓ Token obtained${NC}"
echo

# 2. Create shop
echo "2. Creating shop..."
SHOP_RESPONSE=$(curl -s -X POST \
  "${BASE_URL}/shops" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Test Shop",
    "address": "123 Test Street"
  }')

SHOP_ID=$(echo $SHOP_RESPONSE | jq -r '.id')
echo -e "${GREEN}✓ Shop created: $SHOP_ID${NC}"
echo

# 3. Create product
echo "3. Creating product..."
PRODUCT_RESPONSE=$(curl -s -X POST \
  "${BASE_URL}/products" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "sku": "TEST-001",
    "title": "Test Product",
    "ingredientsText": "Test ingredients",
    "allergenMask": 0
  }')

PRODUCT_ID=$(echo $PRODUCT_RESPONSE | jq -r '.id')
echo -e "${GREEN}✓ Product created: $PRODUCT_ID${NC}"
echo

# 4. Create order
echo "4. Creating order..."
ORDER_RESPONSE=$(curl -s -X POST \
  "${BASE_URL}/orders" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"shopId\": \"$SHOP_ID\",
    \"customerName\": \"Test Customer\",
    \"customerEmail\": \"test@example.com\",
    \"items\": [
      {
        \"productId\": \"$PRODUCT_ID\",
        \"quantity\": 2
      }
    ]
  }")

ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.id')
ORDER_NUMBER=$(echo $ORDER_RESPONSE | jq -r '.orderNumber')
echo -e "${GREEN}✓ Order created: $ORDER_NUMBER ($ORDER_ID)${NC}"
echo

# 5. Update order status
echo "5. Updating order status to PENDING..."
curl -s -X PATCH \
  "${BASE_URL}/orders/$ORDER_ID/status?status=PENDING" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq
echo -e "${GREEN}✓ Order status updated${NC}"
echo

# 6. Get order
echo "6. Retrieving order..."
curl -s -X GET \
  "${BASE_URL}/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TENANT_A_TOKEN" | jq
echo

echo -e "${GREEN}=== All tests completed successfully ===${NC}"
```

Make executable and run:
```bash
chmod +x test-api.sh
./test-api.sh
```

### B. Useful Database Queries

Connect to database:
```bash
docker exec -it jtoye-postgres psql -U jtoye -d jtoye
```

**Check tenant data:**
```sql
-- List all tenants
SELECT * FROM tenants;

-- Count shops per tenant
SELECT tenant_id, COUNT(*) FROM shops GROUP BY tenant_id;

-- Count products per tenant
SELECT tenant_id, COUNT(*) FROM products GROUP BY tenant_id;

-- Count orders per tenant
SELECT tenant_id, COUNT(*) FROM orders GROUP BY tenant_id;
```

**Check order details:**
```sql
-- List orders with their items
SELECT
  o.order_number,
  o.customer_name,
  o.status,
  o.total_amount_pennies,
  COUNT(oi.id) as item_count
FROM orders o
LEFT JOIN order_items oi ON oi.order_id = o.id
GROUP BY o.id, o.order_number, o.customer_name, o.status, o.total_amount_pennies
ORDER BY o.created_at DESC;

-- View order with items details
SELECT
  o.order_number,
  o.status,
  p.title as product_title,
  oi.quantity,
  oi.unit_price_pennies,
  oi.total_price_pennies
FROM orders o
JOIN order_items oi ON oi.order_id = o.id
JOIN products p ON p.id = oi.product_id
WHERE o.order_number = 'ORD-1234567890-123';
```

**Check audit history:**
```sql
-- View shop audit history
SELECT * FROM shops_aud ORDER BY rev DESC LIMIT 10;

-- View order audit history
SELECT * FROM orders_aud ORDER BY rev DESC LIMIT 10;
```

### C. Environment Variables Reference

```bash
# Keycloak
KEYCLOAK_URL=http://localhost:8080
KEYCLOAK_REALM=jtoye
KEYCLOAK_CLIENT_ID=jtoye-client

# Core Java API
API_BASE_URL=http://localhost:9090
API_PORT=9090

# PostgreSQL
DB_HOST=localhost
DB_PORT=5433
DB_NAME=jtoye
DB_USER=jtoye
DB_PASSWORD=jtoye_password

# Test Users
TENANT_A_USER=tenant-a-user
TENANT_A_PASSWORD=password
TENANT_B_USER=tenant-b-user
TENANT_B_PASSWORD=password

# Tenant IDs
TENANT_A_ID=00000000-0000-0000-0000-000000000001
TENANT_B_ID=00000000-0000-0000-0000-000000000002
```

### D. Quick Reference Commands

```bash
# Start services
./scripts/run-app.sh

# Stop services
docker-compose -f infra/docker-compose.yml down

# View logs
docker logs jtoye-core-java -f
docker logs jtoye-keycloak -f
docker logs jtoye-postgres -f

# Run tests
cd core-java && export DB_PORT=5433 && ../gradlew test

# Access database
docker exec -it jtoye-postgres psql -U jtoye -d jtoye

# Check API health
curl http://localhost:9090/actuator/health

# Get token
curl -s -X POST \
  'http://localhost:8080/realms/jtoye/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=jtoye-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password' \
  | jq -r '.access_token'
```

---

## Further Reading

- [TESTING_GUIDE.md](TESTING_GUIDE.md) - Comprehensive testing procedures
- [../planning/PHASE_1_PLAN.md](../planning/PHASE_1_PLAN.md) - Phase 1 implementation details
- [../status/PROJECT_STATUS.md](../status/PROJECT_STATUS.md) - Current project status
- [../../README.md](../../README.md) - Project overview
- [../CHANGELOG.md](../CHANGELOG.md) - Version history

---

**Need Help?**
- Check the [Troubleshooting](#troubleshooting) section above
- Review [TESTING_GUIDE.md](TESTING_GUIDE.md) for detailed diagnostic procedures
- Check application logs: `docker logs jtoye-core-java -f`

**Found an Issue?**
Please document:
1. Exact API endpoint and request
2. Token used (first/last 10 characters only)
3. Expected vs actual response
4. Error messages from logs
