#!/bin/bash
set -e

echo "=== Adding JavaScript Protocol Mapper (Option B) ==="
echo ""

KEYCLOAK_URL="http://localhost:8085"
REALM="jtoye-dev"

# Get admin token
echo "1. Getting admin token..."
ADMIN_TOKEN=$(curl -s -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -d 'username=admin' \
  -d 'password=admin123' \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' | jq -r '.access_token')

if [ "$ADMIN_TOKEN" == "null" ] || [ -z "$ADMIN_TOKEN" ]; then
    echo "✗ Failed to get admin token"
    exit 1
fi
echo "   ✓ Admin token obtained"
echo ""

# Get test-client ID
echo "2. Getting test-client ID..."
CLIENT_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[] | select(.clientId=="test-client") | .id')

if [ -z "$CLIENT_ID" ] || [ "$CLIENT_ID" == "null" ]; then
    echo "✗ test-client not found"
    exit 1
fi
echo "   ✓ test-client ID: $CLIENT_ID"
echo ""

# Delete existing tenant-id-mapper if exists
echo "3. Removing old tenant-id-mapper..."
MAPPER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[] | select(.name=="tenant-id-mapper") | .id // empty')

if [ -n "$MAPPER_ID" ]; then
    curl -s -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models/${MAPPER_ID}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}"
    echo "   ✓ Old mapper removed"
else
    echo "   ℹ No existing mapper found"
fi
echo ""

# Add JavaScript mapper
echo "4. Adding JavaScript protocol mapper..."
# Note: Keycloak may not support JS mappers in all versions, using hardcoded mapper instead
curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-id-hardcoded-mapper",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-hardcoded-claim-mapper",
    "config": {
      "claim.name": "tenant_id",
      "claim.value": "00000000-0000-0000-0000-000000000001",
      "jsonType.label": "String",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "true"
    }
  }'

echo "   ✓ Hardcoded mapper added (testing only)"
echo ""

# Test token
echo "5. Testing JWT token with new mapper..."
TOKEN_A=$(curl -s -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_A" != "null" ] && [ -n "$TOKEN_A" ]; then
    TENANT_ID=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id // "null"')
    echo "   Token tenant_id: $TENANT_ID"

    if [ "$TENANT_ID" != "null" ]; then
        echo "   ✓ SUCCESS: tenant_id is now in the token!"
    else
        echo "   ✗ Still null - trying alternative approach..."
    fi
else
    echo "   ✗ Failed to generate token"
fi

echo ""
echo "Note: Hardcoded mapper is for testing only."
echo "For production, need user-specific mapper or groups."
echo ""
echo "=== Next: Try Option C (Groups) for proper multi-tenant support ==="
