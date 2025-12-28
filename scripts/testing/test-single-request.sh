#!/bin/bash

echo "Getting token..."
TOKEN=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

echo "Token (first 50 chars): ${TOKEN:0:50}..."
echo ""

echo "Making API request to /shops..."
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -H "Authorization: Bearer $TOKEN" "http://localhost:9090/shops")
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "HTTP Status: $HTTP_CODE"
echo "Response body:"
echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
