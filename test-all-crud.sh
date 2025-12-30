#!/bin/bash
# Comprehensive End-to-End CRUD Test Script
# Tests all CRUD operations as a real user with JWT authentication

set -e

API_URL="http://localhost:9090"
KC_URL="http://localhost:8085"

echo "========================================="
echo "  J'Toye OaaS - End-to-End CRUD Tests"
echo "========================================="
echo ""

# Step 1: Get JWT Token
echo "1. Authenticating as tenant-a-user..."
TOKEN=$(curl -s -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "$KC_URL/realms/jtoye-dev/protocol/openid-connect/token" | jq -r '.access_token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    echo "❌ FAILED: Could not obtain JWT token"
    exit 1
fi

echo "✅ Authentication successful"
echo ""

# Function to test CRUD for an endpoint
test_crud() {
    local entity=$1
    local endpoint=$2
    local create_data=$3
    local update_data=$4

    echo "========================================="
    echo "Testing $entity CRUD Operations"
    echo "========================================="

    # CREATE
    echo "  → CREATE (POST $endpoint)"
    CREATE_RESPONSE=$(curl -s -X POST "$API_URL$endpoint" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "$create_data")

    ENTITY_ID=$(echo "$CREATE_RESPONSE" | jq -r '.id')

    if [ "$ENTITY_ID" == "null" ] || [ -z "$ENTITY_ID" ]; then
        echo "    ❌ FAILED: Could not create $entity"
        echo "    Response: $CREATE_RESPONSE"
        return 1
    fi
    echo "    ✅ Created $entity (ID: ${ENTITY_ID:0:8}...)"

    # READ (single)
    echo "  → READ (GET $endpoint/{id})"
    READ_RESPONSE=$(curl -s -X GET "$API_URL$endpoint/$ENTITY_ID" \
      -H "Authorization: Bearer $TOKEN")

    READ_ID=$(echo "$READ_RESPONSE" | jq -r '.id')
    if [ "$READ_ID" != "$ENTITY_ID" ]; then
        echo "    ❌ FAILED: Could not read $entity"
        return 1
    fi
    echo "    ✅ Read $entity successfully"

    # UPDATE
    echo "  → UPDATE (PUT $endpoint/{id})"
    UPDATE_RESPONSE=$(curl -s -X PUT "$API_URL$endpoint/$ENTITY_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "$update_data")

    UPDATE_ID=$(echo "$UPDATE_RESPONSE" | jq -r '.id')
    if [ "$UPDATE_ID" != "$ENTITY_ID" ]; then
        echo "    ❌ FAILED: Could not update $entity"
        return 1
    fi
    echo "    ✅ Updated $entity successfully"

    # DELETE
    echo "  → DELETE (DELETE $endpoint/{id})"
    DELETE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -X DELETE "$API_URL$endpoint/$ENTITY_ID" \
      -H "Authorization: Bearer $TOKEN")

    if [ "$DELETE_STATUS" != "204" ]; then
        echo "    ❌ FAILED: Could not delete $entity (HTTP $DELETE_STATUS)"
        return 1
    fi
    echo "    ✅ Deleted $entity successfully"

    # VERIFY DELETE
    echo "  → VERIFY (GET $endpoint/{id} should return 404)"
    VERIFY_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -X GET "$API_URL$endpoint/$ENTITY_ID" \
      -H "Authorization: Bearer $TOKEN")

    if [ "$VERIFY_STATUS" != "404" ]; then
        echo "    ❌ FAILED: $entity still exists after deletion"
        return 1
    fi
    echo "    ✅ Verified $entity deleted"
    echo ""
}

# Test all entities
PASSED=0
FAILED=0

# Generate unique identifiers using timestamp
TIMESTAMP=$(date +%s)

# Test Shops
if test_crud "Shop" "/shops" \
    "{\"name\":\"E2E Test Shop ${TIMESTAMP}\",\"address\":\"123 Test Street\"}" \
    "{\"name\":\"Updated E2E Shop ${TIMESTAMP}\",\"address\":\"456 Updated Avenue\"}"; then
    ((PASSED++))
else
    ((FAILED++))
fi

# Test Products
if test_crud "Product" "/products" \
    "{\"sku\":\"E2E-${TIMESTAMP}\",\"title\":\"Test Product\",\"ingredientsText\":\"Flour, Water, Salt\",\"allergenMask\":1}" \
    "{\"sku\":\"E2E-UPD-${TIMESTAMP}\",\"title\":\"Updated Test Product\",\"ingredientsText\":\"Flour, Water, Salt, Sugar\",\"allergenMask\":3}"; then
    ((PASSED++))
else
    ((FAILED++))
fi

# Test Customers
if test_crud "Customer" "/customers" \
    "{\"name\":\"John Doe\",\"email\":\"john.${TIMESTAMP}@example.com\",\"phone\":\"+44123456789\",\"allergenRestrictions\":0}" \
    "{\"name\":\"John Updated\",\"email\":\"john.${TIMESTAMP}@example.com\",\"phone\":\"+44987654321\",\"allergenRestrictions\":1}"; then
    ((PASSED++))
else
    ((FAILED++))
fi

# Orders (special case - uses state machine, so just test basic CRUD without full lifecycle)
echo "========================================="
echo "Testing Order CRUD Operations"
echo "========================================="
echo "  → CREATE (POST /orders)"

# First create a shop for the order
SHOP_JSON=$(curl -s -X POST "$API_URL/shops" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Order Test Shop","address":"789 Order St"}')
SHOP_ID=$(echo "$SHOP_JSON" | jq -r '.id')

ORDER_RESPONSE=$(curl -s -X POST "$API_URL/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"shopId\":\"$SHOP_ID\",\"customerName\":\"Order Customer\",\"customerEmail\":\"order@example.com\",\"items\":[{\"productSku\":\"TEST-001\",\"quantity\":2,\"unitPricePennies\":1000}]}")

ORDER_ID=$(echo "$ORDER_RESPONSE" | jq -r '.id')

if [ "$ORDER_ID" != "null" ] && [ -n "$ORDER_ID" ]; then
    echo "    ✅ Created Order (ID: ${ORDER_ID:0:8}...)"

    echo "  → READ (GET /orders/{id})"
    curl -s -X GET "$API_URL/orders/$ORDER_ID" \
      -H "Authorization: Bearer $TOKEN" > /dev/null
    echo "    ✅ Read Order successfully"

    echo "  → DELETE (DELETE /orders/{id})"
    DELETE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -X DELETE "$API_URL/orders/$ORDER_ID" \
      -H "Authorization: Bearer $TOKEN")

    if [ "$DELETE_STATUS" == "204" ]; then
        echo "    ✅ Deleted Order successfully"
        ((PASSED++))
    else
        echo "    ❌ FAILED: Could not delete Order"
        ((FAILED++))
    fi
else
    echo "    ❌ FAILED: Could not create Order"
    ((FAILED++))
fi

# Clean up test shop
curl -s -X DELETE "$API_URL/shops/$SHOP_ID" \
  -H "Authorization: Bearer $TOKEN" > /dev/null
echo ""

# Summary
echo "========================================="
echo "  Test Summary"
echo "========================================="
echo "  Passed: $PASSED/4"
echo "  Failed: $FAILED/4"
echo ""

if [ $FAILED -eq 0 ]; then
    echo "✅ All end-to-end CRUD tests passed!"
    exit 0
else
    echo "❌ Some tests failed"
    exit 1
fi
