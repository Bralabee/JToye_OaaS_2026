#!/bin/bash

ADMIN_TOKEN=$(curl -s -X POST http://localhost:8085/realms/master/protocol/openid-connect/token \
  -d 'username=admin' \
  -d 'password=admin123' \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' | jq -r '.access_token')

echo "Checking tenant-a-user attributes..."
USER_ID=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-a-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.attributes'

echo ""
echo "Checking tenant-b-user attributes..."
USER_ID=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-b-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.attributes'
