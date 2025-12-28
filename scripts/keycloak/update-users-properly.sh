#!/bin/bash
set -e

echo "=== Updating User Attributes Properly ==="

ADMIN_TOKEN=$(curl -s -X POST http://localhost:8085/realms/master/protocol/openid-connect/token \
  -d 'username=admin' \
  -d 'password=admin123' \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' | jq -r '.access_token')

# Get tenant-a-user and update
echo "1. Updating tenant-a-user..."
USER_ID_A=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-a-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

USER_DATA_A=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID_A" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

UPDATED_USER_A=$(echo "$USER_DATA_A" | jq '.attributes = {"tenant_id": ["00000000-0000-0000-0000-000000000001"]}')

curl -s -X PUT "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID_A" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$UPDATED_USER_A"

echo "   ✓ Updated"

# Get tenant-b-user and update
echo "2. Updating tenant-b-user..."
USER_ID_B=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-b-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

USER_DATA_B=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID_B" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

UPDATED_USER_B=$(echo "$USER_DATA_B" | jq '.attributes = {"tenant_id": ["00000000-0000-0000-0000-000000000002"]}')

curl -s -X PUT "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID_B" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$UPDATED_USER_B"

echo "   ✓ Updated"

echo ""
echo "3. Verifying attributes..."
ATTRS_A=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID_A" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.attributes')
echo "   tenant-a-user: $ATTRS_A"

ATTRS_B=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID_B" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.attributes')
echo "   tenant-b-user: $ATTRS_B"

echo ""
echo "4. Testing new tokens..."
TOKEN_A=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

TENANT_ID_A=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id')
echo "   tenant-a-user token tenant_id: $TENANT_ID_A"

TOKEN_B=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' | jq -r '.access_token')

TENANT_ID_B=$(echo "$TOKEN_B" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id')
echo "   tenant-b-user token tenant_id: $TENANT_ID_B"

echo ""
if [ "$TENANT_ID_A" == "00000000-0000-0000-0000-000000000001" ] && [ "$TENANT_ID_B" == "00000000-0000-0000-0000-000000000002" ]; then
    echo "✓ SUCCESS: Tenant IDs are correctly included in JWT tokens"
else
    echo "✗ FAILED: Tenant IDs not in tokens"
fi
