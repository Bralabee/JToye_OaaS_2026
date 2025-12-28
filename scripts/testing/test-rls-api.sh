#!/bin/bash

echo "=== Testing Multi-Tenant RLS Isolation via API ==="
echo ""

API_URL="http://localhost:9090"
TENANT_A_ID="00000000-0000-0000-0000-000000000001"
TENANT_B_ID="00000000-0000-0000-0000-000000000002"

echo "1. Testing /shops endpoint as Tenant A"
echo "   Request: GET /shops with X-Tenant-ID: $TENANT_A_ID"
RESPONSE_A=$(curl -s -H "X-Tenant-ID: $TENANT_A_ID" "$API_URL/shops")
SHOPS_A_COUNT=$(echo "$RESPONSE_A" | jq '. | length')
echo "   Response: $SHOPS_A_COUNT shops"
echo "   $RESPONSE_A" | jq -r '.[] | "   - \(.name)"'
echo ""

echo "2. Testing /shops endpoint as Tenant B"
echo "   Request: GET /shops with X-Tenant-ID: $TENANT_B_ID"
RESPONSE_B=$(curl -s -H "X-Tenant-ID: $TENANT_B_ID" "$API_URL/shops")
SHOPS_B_COUNT=$(echo "$RESPONSE_B" | jq '. | length')
echo "   Response: $SHOPS_B_COUNT shops"
echo "   $RESPONSE_B" | jq -r '.[] | "   - \(.name)"'
echo ""

echo "3. Testing /products endpoint as Tenant A"
echo "   Request: GET /products with X-Tenant-ID: $TENANT_A_ID"
RESPONSE_A=$(curl -s -H "X-Tenant-ID: $TENANT_A_ID" "$API_URL/products")
PRODUCTS_A_COUNT=$(echo "$RESPONSE_A" | jq '. | length')
echo "   Response: $PRODUCTS_A_COUNT products"
echo "   $RESPONSE_A" | jq -r '.[] | "   - \(.sku): \(.title)"'
echo ""

echo "4. Testing /products endpoint as Tenant B"
echo "   Request: GET /products with X-Tenant-ID: $TENANT_B_ID"
RESPONSE_B=$(curl -s -H "X-Tenant-ID: $TENANT_B_ID" "$API_URL/products")
PRODUCTS_B_COUNT=$(echo "$RESPONSE_B" | jq '. | length')
echo "   Response: $PRODUCTS_B_COUNT products"
echo "   $RESPONSE_B" | jq -r '.[] | "   - \(.sku): \(.title)"'
echo ""

echo "5. Testing /shops without tenant header (should return 401 or empty)"
echo "   Request: GET /shops (no X-Tenant-ID header)"
RESPONSE_NO_TENANT=$(curl -s -w "\nHTTP_STATUS:%{http_code}" "$API_URL/shops")
HTTP_STATUS=$(echo "$RESPONSE_NO_TENANT" | grep "HTTP_STATUS" | cut -d: -f2)
BODY=$(echo "$RESPONSE_NO_TENANT" | sed '$d')
echo "   HTTP Status: $HTTP_STATUS"
if [ "$HTTP_STATUS" == "200" ]; then
    SHOPS_NO_TENANT=$(echo "$BODY" | jq '. | length')
    echo "   Response: $SHOPS_NO_TENANT shops (empty means RLS blocked access)"
fi
echo ""

echo "=== RLS Isolation Summary ==="
echo ""
if [ "$SHOPS_A_COUNT" == "2" ] && [ "$SHOPS_B_COUNT" == "2" ]; then
    echo "✓ Shops isolation: PASSED"
    echo "  - Tenant A sees 2 shops (expected: 2)"
    echo "  - Tenant B sees 2 shops (expected: 2)"
else
    echo "✗ Shops isolation: FAILED"
    echo "  - Tenant A sees $SHOPS_A_COUNT shops (expected: 2)"
    echo "  - Tenant B sees $SHOPS_B_COUNT shops (expected: 2)"
fi

echo ""
if [ "$PRODUCTS_A_COUNT" == "3" ] && [ "$PRODUCTS_B_COUNT" == "3" ]; then
    echo "✓ Products isolation: PASSED"
    echo "  - Tenant A sees 3 products (expected: 3)"
    echo "  - Tenant B sees 3 products (expected: 3)"
else
    echo "✗ Products isolation: FAILED"
    echo "  - Tenant A sees $PRODUCTS_A_COUNT products (expected: 3)"
    echo "  - Tenant B sees $PRODUCTS_B_COUNT products (expected: 3)"
fi

echo ""
if [ "$HTTP_STATUS" == "401" ] || ([ "$HTTP_STATUS" == "200" ] && [ "$SHOPS_NO_TENANT" == "0" ]); then
    echo "✓ No-tenant-header protection: PASSED"
    echo "  - Request without tenant header returned $HTTP_STATUS"
else
    echo "✗ No-tenant-header protection: FAILED"
    echo "  - Request without tenant header returned $HTTP_STATUS with $SHOPS_NO_TENANT shops"
fi

echo ""
echo "=== Test Complete ==="
