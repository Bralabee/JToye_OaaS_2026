#!/bin/bash
set -e

echo "=== Fixing Tenant Attributes ==="

ADMIN_TOKEN=$(curl -s -X POST http://localhost:8085/realms/master/protocol/openid-connect/token \
  -d 'username=admin' \
  -d 'password=admin123' \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' | jq -r '.access_token')

# Update tenant-a-user
echo "Updating tenant-a-user with tenant_id..."
USER_ID=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-a-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

curl -s -X PUT "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000001"]
    }
  }'
echo "  ✓ tenant-a-user updated"

# Update tenant-b-user
echo "Updating tenant-b-user with tenant_id..."
USER_ID=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-b-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

curl -s -X PUT "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000002"]
    }
  }'
echo "  ✓ tenant-b-user updated"

echo ""
echo "=== Verification ==="
bash /home/sanmi/IdeaProjects/JToye_OaaS_2026/check-tenant-attributes.sh
