#!/bin/bash

TOKEN_A=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

echo "Sample shops returned by API (first 2):"
curl -s -H "Authorization: Bearer $TOKEN_A" "http://localhost:9090/shops" | jq '.[0:2]'

echo ""
echo "Total count:"
curl -s -H "Authorization: Bearer $TOKEN_A" "http://localhost:9090/shops" | jq '. | length'
