#!/bin/bash

echo "=== JWT Diagnostic Tool ==="
echo ""

# Get token
echo "1. Generating JWT token for tenant-a-user..."
TOKEN=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

echo "   ✓ Token generated"
echo ""

# Decode and show claims
echo "2. JWT Claims:"
CLAIMS=$(echo "$TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null)
echo "$CLAIMS" | jq '{tenant_id, tenantId, tid, preferred_username, groups}'
echo ""

# Extract tenant_id
TENANT_ID=$(echo "$CLAIMS" | jq -r '.tenant_id // "NOT_FOUND"')
echo "3. Extracted tenant_id: $TENANT_ID"
echo ""

# Test API with JWT only
echo "4. Testing API with JWT (no X-Tenant-ID header)..."
API_RESPONSE=$(curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:9090/shops")
SHOP_COUNT=$(echo "$API_RESPONSE" | jq '.content | length')
echo "   Shops returned: $SHOP_COUNT"
echo ""

# Test API with both JWT and header
echo "5. Testing API with JWT + X-Tenant-ID header..."
API_RESPONSE_WITH_HEADER=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT_ID" \
  "http://localhost:9090/shops")
SHOP_COUNT_WITH_HEADER=$(echo "$API_RESPONSE_WITH_HEADER" | jq '.content | length')
echo "   Shops returned: $SHOP_COUNT_WITH_HEADER"
echo ""

# Diagnosis
echo "=== Diagnosis ==="
if [ "$TENANT_ID" != "NOT_FOUND" ] && [ "$TENANT_ID" != "null" ]; then
    echo "✓ JWT has tenant_id claim: $TENANT_ID"
else
    echo "✗ JWT missing tenant_id claim!"
    exit 1
fi

if [ "$SHOP_COUNT" == "2" ]; then
    echo "✓ JWT-only request works! JwtTenantFilter is functioning correctly"
elif [ "$SHOP_COUNT_WITH_HEADER" == "2" ]; then
    echo "✗ JWT-only returns 0 shops, but JWT+header works"
    echo "  → JwtTenantFilter is NOT extracting tenant_id from JWT"
    echo "  → Possible causes:"
    echo "    - Filter not executing (wrong order?)"
    echo "    - JWT extraction logic failing"
    echo "    - TenantContext not being set"
else
    echo "✗ Neither JWT-only nor JWT+header work"
    echo "  → RLS mechanism broken or data missing"
fi
