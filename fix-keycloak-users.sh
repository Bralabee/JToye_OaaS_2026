#!/bin/bash
set -e

echo "=== Fixing Keycloak User Attributes (Option A) ==="
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

# Delete tenant-a-user
echo "2. Deleting tenant-a-user..."
USER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-a-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

if [ -n "$USER_ID" ]; then
    curl -s -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}"
    echo "   ✓ User deleted (ID: $USER_ID)"
else
    echo "   ℹ User not found, skipping delete"
fi

# Recreate tenant-a-user with attributes
echo "   Creating tenant-a-user with attributes..."
CREATE_RESPONSE=$(curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -w "\nHTTP_STATUS:%{http_code}" \
  -d '{
    "username": "tenant-a-user",
    "enabled": true,
    "email": "usera@tenanta.com",
    "emailVerified": true,
    "firstName": "User",
    "lastName": "A",
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000001"]
    },
    "credentials": [{
      "type": "password",
      "value": "password123",
      "temporary": false
    }]
  }')

HTTP_STATUS=$(echo "$CREATE_RESPONSE" | grep "HTTP_STATUS" | cut -d: -f2)
if [ "$HTTP_STATUS" == "201" ]; then
    echo "   ✓ tenant-a-user created successfully"
else
    echo "   ✗ Failed to create user (HTTP $HTTP_STATUS)"
fi
echo ""

# Delete tenant-b-user
echo "3. Deleting tenant-b-user..."
USER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-b-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

if [ -n "$USER_ID" ]; then
    curl -s -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}"
    echo "   ✓ User deleted (ID: $USER_ID)"
else
    echo "   ℹ User not found, skipping delete"
fi

# Recreate tenant-b-user with attributes
echo "   Creating tenant-b-user with attributes..."
CREATE_RESPONSE=$(curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -w "\nHTTP_STATUS:%{http_code}" \
  -d '{
    "username": "tenant-b-user",
    "enabled": true,
    "email": "userb@tenantb.com",
    "emailVerified": true,
    "firstName": "User",
    "lastName": "B",
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000002"]
    },
    "credentials": [{
      "type": "password",
      "value": "password123",
      "temporary": false
    }]
  }')

HTTP_STATUS=$(echo "$CREATE_RESPONSE" | grep "HTTP_STATUS" | cut -d: -f2)
if [ "$HTTP_STATUS" == "201" ]; then
    echo "   ✓ tenant-b-user created successfully"
else
    echo "   ✗ Failed to create user (HTTP $HTTP_STATUS)"
fi
echo ""

# Verify attributes
echo "4. Verifying user attributes..."
USER_ID_A=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-a-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id')

ATTRS_A=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID_A}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.attributes.tenant_id[0] // "null"')

echo "   tenant-a-user tenant_id: $ATTRS_A"

USER_ID_B=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-b-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id')

ATTRS_B=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID_B}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.attributes.tenant_id[0] // "null"')

echo "   tenant-b-user tenant_id: $ATTRS_B"
echo ""

# Test JWT tokens
echo "5. Testing JWT token generation..."
TOKEN_A=$(curl -s -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_A" != "null" ] && [ -n "$TOKEN_A" ]; then
    TENANT_ID_A=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id // "null"')
    echo "   tenant-a-user token generated"
    echo "   Token tenant_id claim: $TENANT_ID_A"

    if [ "$TENANT_ID_A" == "00000000-0000-0000-0000-000000000001" ]; then
        echo "   ✓ SUCCESS: tenant_id in token matches expected value!"
    else
        echo "   ✗ FAILED: tenant_id is $TENANT_ID_A (expected: 00000000-0000-0000-0000-000000000001)"
    fi
else
    echo "   ✗ Failed to generate token"
fi
echo ""

TOKEN_B=$(curl -s -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_B" != "null" ] && [ -n "$TOKEN_B" ]; then
    TENANT_ID_B=$(echo "$TOKEN_B" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id // "null"')
    echo "   tenant-b-user token generated"
    echo "   Token tenant_id claim: $TENANT_ID_B"

    if [ "$TENANT_ID_B" == "00000000-0000-0000-0000-000000000002" ]; then
        echo "   ✓ SUCCESS: tenant_id in token matches expected value!"
    else
        echo "   ✗ FAILED: tenant_id is $TENANT_ID_B (expected: 00000000-0000-0000-0000-000000000002)"
    fi
else
    echo "   ✗ Failed to generate token"
fi
echo ""

# Save tokens for API testing
if [ "$TENANT_ID_A" == "00000000-0000-0000-0000-000000000001" ] && [ "$TENANT_ID_B" == "00000000-0000-0000-0000-000000000002" ]; then
    cat > /tmp/jwt-tokens-fixed.sh <<EOF
#!/bin/bash
# JWT tokens with working tenant_id claims
export TOKEN_A='$TOKEN_A'
export TOKEN_B='$TOKEN_B'
echo "✓ Tokens loaded with tenant_id claims:"
echo "  TOKEN_A (Tenant A): \${TOKEN_A:0:50}..."
echo "  TOKEN_B (Tenant B): \${TOKEN_B:0:50}..."
EOF
    chmod +x /tmp/jwt-tokens-fixed.sh
    echo "✓ Tokens saved to /tmp/jwt-tokens-fixed.sh"
    echo "  Source with: source /tmp/jwt-tokens-fixed.sh"
fi

echo ""
echo "=== Fix Complete ==="
