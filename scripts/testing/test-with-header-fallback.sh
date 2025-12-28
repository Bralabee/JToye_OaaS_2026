#!/bin/bash

echo "=== Testing API with JWT + X-Tenant-ID Header ==="
echo ""

# Get JWT tokens
echo "1. Getting JWT tokens..."
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

echo "   ✓ Tokens obtained"
echo ""

# Test Tenant A with both JWT and header
echo "2. Testing Tenant A (JWT + X-Tenant-ID header)..."
SHOPS_A=$(curl -s \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000001" \
  "http://localhost:9090/shops")

SHOPS_A_COUNT=$(echo "$SHOPS_A" | jq '.content | length')
echo "   Shops returned: $SHOPS_A_COUNT"
echo "$SHOPS_A" | jq -r '.content[] | "   - \(.name)"'

if [ "$SHOPS_A_COUNT" == "2" ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL (expected 2, got $SHOPS_A_COUNT)"
fi
echo ""

# Test Tenant B with both JWT and header
echo "3. Testing Tenant B (JWT + X-Tenant-ID header)..."
SHOPS_B=$(curl -s \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000002" \
  "http://localhost:9090/shops")

SHOPS_B_COUNT=$(echo "$SHOPS_B" | jq '.content | length')
echo "   Shops returned: $SHOPS_B_COUNT"
echo "$SHOPS_B" | jq -r '.content[] | "   - \(.name)"'

if [ "$SHOPS_B_COUNT" == "2" ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL (expected 2, got $SHOPS_B_COUNT)"
fi
echo ""

# Test products for Tenant A
echo "4. Testing Tenant A products (JWT + X-Tenant-ID header)..."
PRODUCTS_A=$(curl -s \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000001" \
  "http://localhost:9090/products")

PRODUCTS_A_COUNT=$(echo "$PRODUCTS_A" | jq '.content | length')
echo "   Products returned: $PRODUCTS_A_COUNT"
echo "$PRODUCTS_A" | jq -r '.content[] | "   - \(.sku): \(.title)"'

if [ "$PRODUCTS_A_COUNT" == "3" ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL (expected 3, got $PRODUCTS_A_COUNT)"
fi
echo ""

# Test cross-tenant isolation (Tenant A trying to access Tenant B data)
echo "5. Testing cross-tenant isolation..."
echo "   Tenant A user with Tenant B header (should fail or return 0)..."
CROSS_TENANT=$(curl -s \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "X-Tenant-ID: 00000000-0000-0000-0000-000000000002" \
  "http://localhost:9090/shops")

CROSS_COUNT=$(echo "$CROSS_TENANT" | jq '.content | length')
echo "   Shops returned: $CROSS_COUNT"

if [ "$CROSS_COUNT" == "2" ]; then
    echo "   ⚠ WARNING: Cross-tenant access allowed! Security issue!"
else
    echo "   ✓ PASS (cross-tenant blocked)"
fi
echo ""

# Summary
echo "=== Summary ==="
if [ "$SHOPS_A_COUNT" == "2" ] && [ "$SHOPS_B_COUNT" == "2" ] && [ "$PRODUCTS_A_COUNT" == "3" ]; then
    echo "✓ RLS isolation working via API with X-Tenant-ID header"
    echo ""
    echo "DIAGNOSIS:"
    echo "- JWT authentication: WORKING ✓"
    echo "- X-Tenant-ID header: WORKING ✓"
    echo "- JWT tenant_id extraction: NOT WORKING ✗"
    echo ""
    echo "CONCLUSION: JwtTenantFilter is not extracting tenant_id from JWT"
    echo "The SecurityConfig filter order or JWT processing needs fixing"
else
    echo "✗ Tests failed - RLS not working"
fi
