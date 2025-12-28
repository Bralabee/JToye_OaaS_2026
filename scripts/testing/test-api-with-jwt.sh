#!/bin/bash

echo "=== Testing API with JWT Tokens ==="
echo ""

API_URL="http://localhost:9090"

# Get JWT tokens
echo "1. Obtaining JWT tokens..."
TOKEN_A=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

TOKEN_B=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_A" == "null" ] || [ -z "$TOKEN_A" ]; then
    echo "✗ Failed to get Tenant A token"
    exit 1
fi

if [ "$TOKEN_B" == "null" ] || [ -z "$TOKEN_B" ]; then
    echo "✗ Failed to get Tenant B token"
    exit 1
fi

echo "   ✓ Tenant A token obtained"
echo "   ✓ Tenant B token obtained"
echo ""

# Test 1: Tenant A shops
echo "2. Testing GET /shops as Tenant A..."
SHOPS_A=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$API_URL/shops")
SHOPS_A_COUNT=$(echo "$SHOPS_A" | jq '. | length // 0')
echo "   Response: $SHOPS_A_COUNT shops"
if [ "$SHOPS_A_COUNT" -gt 0 ]; then
    echo "$SHOPS_A" | jq -r '.[] | "   - \(.name)"'
fi

if [ "$SHOPS_A_COUNT" == "2" ]; then
    echo "   ✓ PASS (expected 2 shops)"
else
    echo "   ✗ FAIL (expected 2, got $SHOPS_A_COUNT)"
fi
echo ""

# Test 2: Tenant B shops
echo "3. Testing GET /shops as Tenant B..."
SHOPS_B=$(curl -s -H "Authorization: Bearer $TOKEN_B" "$API_URL/shops")
SHOPS_B_COUNT=$(echo "$SHOPS_B" | jq '. | length // 0')
echo "   Response: $SHOPS_B_COUNT shops"
if [ "$SHOPS_B_COUNT" -gt 0 ]; then
    echo "$SHOPS_B" | jq -r '.[] | "   - \(.name)"'
fi

if [ "$SHOPS_B_COUNT" == "2" ]; then
    echo "   ✓ PASS (expected 2 shops)"
else
    echo "   ✗ FAIL (expected 2, got $SHOPS_B_COUNT)"
fi
echo ""

# Test 3: Tenant A products
echo "4. Testing GET /products as Tenant A..."
PRODUCTS_A=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$API_URL/products")
PRODUCTS_A_COUNT=$(echo "$PRODUCTS_A" | jq '. | length // 0')
echo "   Response: $PRODUCTS_A_COUNT products"
if [ "$PRODUCTS_A_COUNT" -gt 0 ]; then
    echo "$PRODUCTS_A" | jq -r '.[] | "   - \(.sku): \(.title)"'
fi

if [ "$PRODUCTS_A_COUNT" == "3" ]; then
    echo "   ✓ PASS (expected 3 products)"
else
    echo "   ✗ FAIL (expected 3, got $PRODUCTS_A_COUNT)"
fi
echo ""

# Test 4: Tenant B products
echo "5. Testing GET /products as Tenant B..."
PRODUCTS_B=$(curl -s -H "Authorization: Bearer $TOKEN_B" "$API_URL/products")
PRODUCTS_B_COUNT=$(echo "$PRODUCTS_B" | jq '. | length // 0')
echo "   Response: $PRODUCTS_B_COUNT products"
if [ "$PRODUCTS_B_COUNT" -gt 0 ]; then
    echo "$PRODUCTS_B" | jq -r '.[] | "   - \(.sku): \(.title)"'
fi

if [ "$PRODUCTS_B_COUNT" == "3" ]; then
    echo "   ✓ PASS (expected 3 products)"
else
    echo "   ✗ FAIL (expected 3, got $PRODUCTS_B_COUNT)"
fi
echo ""

# Test 5: Request without JWT
echo "6. Testing GET /shops without JWT (should return 401)..."
NO_AUTH_RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" "$API_URL/shops")
HTTP_CODE=$(echo "$NO_AUTH_RESPONSE" | grep "HTTP_CODE" | cut -d: -f2)

if [ "$HTTP_CODE" == "401" ]; then
    echo "   ✓ PASS (returned 401 Unauthorized)"
else
    echo "   ✗ FAIL (expected 401, got $HTTP_CODE)"
fi
echo ""

# Test 6: Cross-tenant access attempt
echo "7. Testing cross-tenant isolation..."
echo "   Tenant A data:"
TENANT_A_SHOP_NAMES=$(echo "$SHOPS_A" | jq -r '.[].name' | sort)
echo "   Tenant B data:"
TENANT_B_SHOP_NAMES=$(echo "$SHOPS_B" | jq -r '.[].name' | sort)

if [ "$TENANT_A_SHOP_NAMES" != "$TENANT_B_SHOP_NAMES" ]; then
    echo "   ✓ PASS (tenants see different data)"
else
    echo "   ✗ FAIL (tenants see same data - RLS not working)"
fi
echo ""

# Summary
echo "=== Test Summary ==="
PASS_COUNT=0
FAIL_COUNT=0

if [ "$SHOPS_A_COUNT" == "2" ]; then PASS_COUNT=$((PASS_COUNT + 1)); else FAIL_COUNT=$((FAIL_COUNT + 1)); fi
if [ "$SHOPS_B_COUNT" == "2" ]; then PASS_COUNT=$((PASS_COUNT + 1)); else FAIL_COUNT=$((FAIL_COUNT + 1)); fi
if [ "$PRODUCTS_A_COUNT" == "3" ]; then PASS_COUNT=$((PASS_COUNT + 1)); else FAIL_COUNT=$((FAIL_COUNT + 1)); fi
if [ "$PRODUCTS_B_COUNT" == "3" ]; then PASS_COUNT=$((PASS_COUNT + 1)); else FAIL_COUNT=$((FAIL_COUNT + 1)); fi
if [ "$HTTP_CODE" == "401" ]; then PASS_COUNT=$((PASS_COUNT + 1)); else FAIL_COUNT=$((FAIL_COUNT + 1)); fi
if [ "$TENANT_A_SHOP_NAMES" != "$TENANT_B_SHOP_NAMES" ]; then PASS_COUNT=$((PASS_COUNT + 1)); else FAIL_COUNT=$((FAIL_COUNT + 1)); fi

echo "Tests Passed: $PASS_COUNT/6"
echo "Tests Failed: $FAIL_COUNT/6"
echo ""

if [ "$FAIL_COUNT" == "0" ]; then
    echo "✓ ALL TESTS PASSED - Multi-tenant RLS isolation is working correctly!"
else
    echo "⚠ Some tests failed - review results above"
fi
