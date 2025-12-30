#!/bin/bash
# Test CRUD operations for shops

TOKEN=$(cat /tmp/token.txt)

echo "=== Testing CRUD Operations ==="

echo -e "\n1. CREATE (POST /shops):"
SHOP_JSON=$(curl -s -X POST "http://localhost:9090/shops" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"CRUD Test Shop","address":"789 Test Avenue"}')
echo "$SHOP_JSON" | jq '.'
SHOP_ID=$(echo "$SHOP_JSON" | jq -r '.id')
echo "Created shop ID: $SHOP_ID"

echo -e "\n2. READ (GET /shops/{id}):"
curl -s -X GET "http://localhost:9090/shops/$SHOP_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.'

echo -e "\n3. UPDATE (PUT /shops/{id}):"
curl -s -X PUT "http://localhost:9090/shops/$SHOP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Updated CRUD Shop","address":"999 Updated Street"}' | jq '.'

echo -e "\n4. DELETE (DELETE /shops/{id}):"
curl -s -X DELETE "http://localhost:9090/shops/$SHOP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -w "HTTP Status: %{http_code}\n"

echo -e "\n5. VERIFY DELETE (GET /shops/{id} should return 404):"
curl -s -X GET "http://localhost:9090/shops/$SHOP_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"

echo -e "\n=== CRUD Tests Complete ==="
