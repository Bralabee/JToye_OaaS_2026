# API Reference - J'Toye OaaS

**Version:** 0.7.1
**Base URL:** `http://localhost:9090`
**Authentication:** Bearer JWT Token

---

## Table of Contents

1. [Authentication](#authentication)
2. [Shops API](#shops-api)
3. [Products API](#products-api)
4. [Orders API](#orders-api)
5. [Customers API](#customers-api)
6. [Health & Security](#health--security)

---

## Authentication

All API endpoints require authentication except `/health` and `/actuator/health`.

### Get JWT Token

```bash
curl -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123'
```

**Response:**
```json
{
  "access_token": "eyJhbGci...",
  "token_type": "Bearer",
  "expires_in": 300
}
```

### Use Token

```bash
curl http://localhost:9090/shops \
  -H "Authorization: Bearer eyJhbGci..."
```

---

## Shops API

### List All Shops

```
GET /shops
```

**Query Parameters:**
- `page` (optional): Page number (default: 0)
- `size` (optional): Page size (default: 20)

**Response:**
```json
{
  "content": [
    {
      "id": "uuid",
      "name": "Shop Name",
      "address": "123 Street",
      "createdAt": "2026-01-06T12:00:00Z"
    }
  ],
  "totalElements": 10,
  "totalPages": 1
}
```

### Get Shop by ID

```
GET /shops/{id}
```

**Response:** `200 OK` with shop object, or `404 Not Found`

### Create Shop

```
POST /shops
Content-Type: application/json

{
  "name": "New Shop",
  "address": "123 Street",
  "phone": "+44 20 1234 5678"
}
```

**Response:** `201 Created` with shop object

### Update Shop

```
PUT /shops/{id}
Content-Type: application/json

{
  "name": "Updated Name",
  "address": "New Address"
}
```

**Response:** `200 OK` with updated shop

### Delete Shop

```
DELETE /shops/{id}
```

**Response:** `204 No Content`

---

## Products API

### List All Products

```
GET /products
```

**Response:** Paginated list of products

### Create Product

```
POST /products
Content-Type: application/json

{
  "sku": "PROD-001",
  "title": "Product Title",
  "ingredientsText": "Flour (80%), Water (20%)",
  "allergenMask": 2,
  "priceGbp": 9.99
}
```

**Allergen Mask Values:**
- Bit 0 (1): Celery
- Bit 1 (2): Gluten
- Bit 2 (4): Crustaceans
- Bit 3 (8): Eggs
- Bit 4 (16): Fish
- Bit 5 (32): Lupin
- Bit 6 (64): Milk
- Bit 7 (128): Molluscs
- Bit 8 (256): Mustard
- Bit 9 (512): Nuts
- Bit 10 (1024): Peanuts
- Bit 11 (2048): Sesame
- Bit 12 (4096): Soya
- Bit 13 (8192): Sulphites

**Example:** Milk (64) + Eggs (8) = 72

---

## Orders API

### List All Orders

```
GET /orders
```

### Create Order

```
POST /orders
Content-Type: application/json

{
  "shopId": "uuid",
  "customerName": "John Doe",
  "customerEmail": "john@example.com",
  "customerPhone": "+44 20 1234 5678",
  "items": [
    {
      "productId": "uuid",
      "quantity": 2
    }
  ]
}
```

**Response:**
```json
{
  "id": "uuid",
  "orderNumber": "ORD-xxx",
  "status": "DRAFT",
  "totalAmountPennies": 2000
}
```

### Get Order by ID

```
GET /orders/{id}
```

### Get Orders by Status

```
GET /orders/status/{status}
```

**Valid statuses:**
- `DRAFT`
- `PENDING`
- `CONFIRMED`
- `PREPARING`
- `READY`
- `COMPLETED`
- `CANCELLED`

---

## Order State Machine

Orders follow a defined workflow with validation:

```
DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED
  ↓       ↓          ↓           ↓          ↓
  └───────┴──────────┴───────────┴──────────→ CANCELLED
```

### Submit Order

```
POST /orders/{id}/submit
```

**Transition:** `DRAFT → PENDING`

**Response:** `200 OK` with updated order

### Confirm Order

```
POST /orders/{id}/confirm
```

**Transition:** `PENDING → CONFIRMED`

### Start Preparation

```
POST /orders/{id}/start-preparation
```

**Transition:** `CONFIRMED → PREPARING`

### Mark Ready

```
POST /orders/{id}/mark-ready
```

**Transition:** `PREPARING → READY`

### Complete Order

```
POST /orders/{id}/complete
```

**Transition:** `READY → COMPLETED`

### Cancel Order

```
POST /orders/{id}/cancel
```

**Transition:** `ANY → CANCELLED`

---

## Customers API

### List All Customers

```
GET /customers
```

### Create Customer

```
POST /customers
Content-Type: application/json

{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "+44 20 1234 5678",
  "allergenMask": 72
}
```

**Response:** `201 Created` with customer object

---

## Health & Security

### Application Health

```
GET /actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "diskSpace": {"status": "UP"}
  }
}
```

### Security Health

```
GET /health/security
```

**Response:**
```json
{
  "username": "jtoye_app",
  "isSuperuser": false,
  "rlsEnabled": true,
  "tablesWithRls": 5,
  "status": "SECURE"
}
```

**Use this to verify RLS is correctly configured.**

If `isSuperuser: true` or `status: "INSECURE"`, multi-tenant isolation is BROKEN.

---

## Error Responses

### Standard Error Format

```json
{
  "type": "https://jtoye.uk/errors/resource-not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Shop not found: uuid",
  "instance": "/shops/uuid"
}
```

### Common Error Codes

| Code | Title | Description |
|------|-------|-------------|
| 400 | Validation Error | Request body validation failed |
| 401 | Unauthorized | Missing or invalid JWT token |
| 403 | Forbidden | Insufficient permissions |
| 404 | Not Found | Resource not found (or RLS filtered) |
| 409 | Conflict | Duplicate resource (e.g., SKU) |
| 500 | Internal Server Error | Unexpected server error |

### Validation Error Example

```json
{
  "type": "https://jtoye.uk/errors/validation",
  "title": "Validation Error",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/orders",
  "errors": {
    "shopId": "must not be null",
    "items": "must not be empty"
  }
}
```

### Invalid State Transition

```json
{
  "type": "https://jtoye.uk/errors/invalid-state-transition",
  "title": "Invalid State Transition",
  "status": 400,
  "detail": "Invalid state transition for order uuid: cannot apply event CONFIRM in state DRAFT",
  "instance": "/orders/uuid/confirm"
}
```

**Solution:** Use correct endpoint for current state (e.g., `/submit` for DRAFT orders)

---

## Rate Limiting

Edge Go API Gateway implements rate limiting:

- **Default:** 100 requests/minute per IP
- **Burst:** 20 requests
- **Response:** `429 Too Many Requests`

---

## Interactive API Documentation

**Swagger UI:** http://localhost:9090/swagger-ui.html

**OpenAPI Spec:** http://localhost:9090/v3/api-docs

---

## Complete Workflow Example

```bash
# 1. Get token
TOKEN=$(curl -s -d 'grant_type=password' -d 'client_id=test-client' -d 'username=tenant-a-user' -d 'password=password123' "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

# 2. Create shop
SHOP=$(curl -s -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"My Shop","address":"123 Street"}')
SHOP_ID=$(echo "$SHOP" | jq -r .id)

# 3. Create product
PROD=$(curl -s -X POST http://localhost:9090/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sku":"PROD-001","title":"Product","ingredientsText":"Test","allergenMask":0,"priceGbp":10}')
PROD_ID=$(echo "$PROD" | jq -r .id)

# 4. Create order
ORDER=$(curl -s -X POST http://localhost:9090/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"shopId\":\"$SHOP_ID\",\"customerName\":\"John Doe\",\"items\":[{\"productId\":\"$PROD_ID\",\"quantity\":2}]}")
ORDER_ID=$(echo "$ORDER" | jq -r .id)

# 5. Submit order
curl -s -X POST http://localhost:9090/orders/$ORDER_ID/submit \
  -H "Authorization: Bearer $TOKEN"

# 6. Confirm order
curl -s -X POST http://localhost:9090/orders/$ORDER_ID/confirm \
  -H "Authorization: Bearer $TOKEN"

# 7. Start preparation
curl -s -X POST http://localhost:9090/orders/$ORDER_ID/start-preparation \
  -H "Authorization: Bearer $TOKEN"

# 8. Mark ready
curl -s -X POST http://localhost:9090/orders/$ORDER_ID/mark-ready \
  -H "Authorization: Bearer $TOKEN"

# 9. Complete order
curl -s -X POST http://localhost:9090/orders/$ORDER_ID/complete \
  -H "Authorization: Bearer $TOKEN"

# 10. Verify final status
curl -s http://localhost:9090/orders/$ORDER_ID \
  -H "Authorization: Bearer $TOKEN" | jq .status
# Should return: "COMPLETED"
```

---

## Multi-Tenant Behavior

All API endpoints are **automatically tenant-scoped** via Row-Level Security (RLS).

**What this means:**
- You can ONLY see/modify data for YOUR tenant
- Attempting to access another tenant's data returns `404 Not Found`
- No special headers or query parameters needed
- Tenant is extracted from JWT `tenant_id` claim

**Example:**
```bash
# Tenant A creates shop
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"name":"Tenant A Shop"}'

# Tenant B tries to list shops
curl http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_B"

# Result: Does NOT see Tenant A's shop (RLS filters it out)
```

---

## Notes

- All timestamps are in ISO 8601 format with UTC timezone
- All amounts are in pennies (GBP minor units)
- UUIDs are version 4 (random)
- Pagination uses zero-based indexing
- All endpoints support CORS for `http://localhost:3000`

---

**For implementation details, see:**
- [SECURITY_ARCHITECTURE.md](SECURITY_ARCHITECTURE.md) - RLS and multi-tenancy
- [USER_GUIDE.md](USER_GUIDE.md) - End-user workflows
- Swagger UI - Interactive API testing
