#!/bin/bash
set -e

echo "=== Keycloak Groups Solution (Option C) ==="
echo "This is the proper multi-tenant solution using groups"
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

# Create group for Tenant A
echo "2. Creating group for Tenant A..."
CREATE_RESP=$(curl -s -w "\nHTTP:%{http_code}" -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/groups" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-a",
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000001"]
    }
  }')

HTTP_CODE=$(echo "$CREATE_RESP" | grep "HTTP:" | cut -d: -f2)
if [ "$HTTP_CODE" == "201" ] || [ "$HTTP_CODE" == "409" ]; then
    echo "   ✓ Tenant A group created/exists"
else
    echo "   ⚠ HTTP $HTTP_CODE (may already exist)"
fi

# Create group for Tenant B
echo "3. Creating group for Tenant B..."
CREATE_RESP=$(curl -s -w "\nHTTP:%{http_code}" -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/groups" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-b",
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000002"]
    }
  }')

HTTP_CODE=$(echo "$CREATE_RESP" | grep "HTTP:" | cut -d: -f2)
if [ "$HTTP_CODE" == "201" ] || [ "$HTTP_CODE" == "409" ]; then
    echo "   ✓ Tenant B group created/exists"
else
    echo "   ⚠ HTTP $HTTP_CODE (may already exist)"
fi
echo ""

# Get group IDs
echo "4. Getting group IDs..."
GROUP_A_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/groups?search=tenant-a" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

GROUP_B_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/groups?search=tenant-b" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

echo "   Group tenant-a ID: $GROUP_A_ID"
echo "   Group tenant-b ID: $GROUP_B_ID"
echo ""

# Get user IDs
echo "5. Getting user IDs..."
USER_A_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-a-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

USER_B_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-b-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

echo "   User tenant-a-user ID: $USER_A_ID"
echo "   User tenant-b-user ID: $USER_B_ID"
echo ""

# Assign users to groups
echo "6. Assigning users to groups..."
curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_A_ID}/groups/${GROUP_A_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json"
echo "   ✓ tenant-a-user → tenant-a group"

curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_B_ID}/groups/${GROUP_B_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json"
echo "   ✓ tenant-b-user → tenant-b group"
echo ""

# Add group attribute mapper to test-client
echo "7. Adding group attribute mapper to test-client..."
CLIENT_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[] | select(.clientId=="test-client") | .id')

# Remove old mapper if exists
MAPPER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[] | select(.name=="tenant-id-mapper") | .id // empty')

if [ -n "$MAPPER_ID" ]; then
    curl -s -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models/${MAPPER_ID}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}"
fi

# Add group attribute mapper
curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-id-group-mapper",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-group-membership-mapper",
    "config": {
      "claim.name": "groups",
      "full.path": "false",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "true"
    }
  }'

echo "   ✓ Group membership mapper added"
echo ""

# Also add a mapper to extract tenant_id from group attributes
curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-id-from-group-attr",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-usermodel-attribute-mapper",
    "config": {
      "user.attribute": "tenant_id",
      "claim.name": "tenant_id",
      "jsonType.label": "String",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "true",
      "aggregate.attrs": "true"
    }
  }' 2>/dev/null || echo "   (secondary mapper may have failed)"

echo ""

# Test tokens
echo "8. Testing JWT tokens..."
TOKEN_A=$(curl -s -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_A" != "null" ] && [ -n "$TOKEN_A" ]; then
    echo "   Testing tenant-a-user token..."
    DECODED=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null)
    GROUPS=$(echo "$DECODED" | jq -r '.groups // empty')
    TENANT_ID=$(echo "$DECODED" | jq -r '.tenant_id // "null"')

    echo "   Groups in token: $GROUPS"
    echo "   tenant_id in token: $TENANT_ID"

    if [ "$TENANT_ID" != "null" ]; then
        echo "   ✓ SUCCESS: tenant_id found in token!"
    elif echo "$GROUPS" | grep -q "tenant-a"; then
        echo "   ✓ Group membership working (can derive tenant_id from group)"
    else
        echo "   ⚠ Neither tenant_id nor groups found - may need manual UI configuration"
    fi
fi

echo ""
echo "=== Groups Solution Complete ==="
echo ""
echo "If tenant_id is still null, you need to:"
echo "1. Open Keycloak UI: http://localhost:8085"
echo "2. Go to: Clients → test-client → Client scopes"
echo "3. Click on the client scope → Mappers → Add mapper"
echo "4. Choose 'User Attribute' and map 'tenant_id' attribute from user/group"
echo ""
