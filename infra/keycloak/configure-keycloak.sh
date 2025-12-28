#!/bin/bash
set -e

echo "=== Keycloak Configuration Script ==="
echo "This script configures the jtoye-dev realm with multi-tenant support"
echo ""

KEYCLOAK_URL="http://localhost:8085"
REALM="jtoye-dev"

# Wait for Keycloak to be ready
echo "Waiting for Keycloak to be ready..."
until curl -sf "${KEYCLOAK_URL}/realms/${REALM}/.well-known/openid-configuration" > /dev/null 2>&1; do
    echo "  Keycloak not ready yet, waiting..."
    sleep 2
done
echo "✓ Keycloak is ready"
echo ""

# Get admin token
echo "Getting admin access token..."
ADMIN_TOKEN=$(curl -s -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=admin123" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r '.access_token')

if [ "$ADMIN_TOKEN" == "null" ] || [ -z "$ADMIN_TOKEN" ]; then
    echo "✗ Failed to get admin token"
    exit 1
fi
echo "✓ Got admin token"
echo ""

# Create or update test-client
echo "Configuring test-client..."
CLIENT_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[] | select(.clientId=="test-client") | .id')

if [ -z "$CLIENT_ID" ] || [ "$CLIENT_ID" == "null" ]; then
    echo "  Creating test-client..."
    curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      -H "Content-Type: application/json" \
      -d '{
        "clientId": "test-client",
        "enabled": true,
        "publicClient": true,
        "directAccessGrantsEnabled": true,
        "standardFlowEnabled": false,
        "protocol": "openid-connect"
      }'
    echo "  ✓ test-client created"
else
    echo "  Updating test-client..."
    curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      -H "Content-Type: application/json" \
      -d '{
        "clientId": "test-client",
        "enabled": true,
        "publicClient": true,
        "directAccessGrantsEnabled": true,
        "standardFlowEnabled": false,
        "protocol": "openid-connect"
      }'
    echo "  ✓ test-client updated"
fi

# Get test-client ID for mapper
CLIENT_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[] | select(.clientId=="test-client") | .id')

# Add tenant_id protocol mapper
echo "  Adding tenant_id protocol mapper..."
curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${CLIENT_ID}/protocol-mappers/models" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-id-mapper",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-usermodel-attribute-mapper",
    "config": {
      "user.attribute": "tenant_id",
      "claim.name": "tenant_id",
      "jsonType.label": "String",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "true"
    }
  }' 2>/dev/null || echo "  (mapper may already exist)"
echo "  ✓ Protocol mapper configured"
echo ""

# Create tenant-a-user
echo "Creating tenant-a-user..."
USER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-a-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

if [ -z "$USER_ID" ]; then
    curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      -H "Content-Type: application/json" \
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
      }'
    echo "  ✓ tenant-a-user created"
else
    echo "  ✓ tenant-a-user already exists"
fi
echo ""

# Create tenant-b-user
echo "Creating tenant-b-user..."
USER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=tenant-b-user" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id // empty')

if [ -z "$USER_ID" ]; then
    curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      -H "Content-Type: application/json" \
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
      }'
    echo "  ✓ tenant-b-user created"
else
    echo "  ✓ tenant-b-user already exists"
fi
echo ""

echo "=== Configuration Complete ==="
echo ""
echo "Test users created:"
echo "  - tenant-a-user / password123 (tenant_id: 00000000-0000-0000-0000-000000000001)"
echo "  - tenant-b-user / password123 (tenant_id: 00000000-0000-0000-0000-000000000002)"
echo ""
echo "Test JWT token generation with:"
echo "  curl -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \\"
echo "    -d 'grant_type=password' \\"
echo "    -d 'client_id=test-client' \\"
echo "    -d 'username=tenant-a-user' \\"
echo "    -d 'password=password123'"
echo ""
