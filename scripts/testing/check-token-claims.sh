#!/bin/bash

TOKEN=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

echo "Token claims for tenant-a-user:"
echo "$TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{tenant_id, groups, preferred_username}'
