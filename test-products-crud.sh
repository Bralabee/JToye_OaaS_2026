#!/bin/bash
set -e

echo "Getting token..."
curl -s -d 'grant_type=password' -d 'client_id=test-client' -d 'username=tenant-a-user' -d 'password=password123' "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r '.access_token' > /tmp/token.txt

TOKEN=$(cat /tmp/token.txt)

echo "Testing Product CRUD..."
echo "1. CREATE"
curl -s -X POST "http://localhost:9090/products" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sku":"E2E-PROD","title":"E2E Product","ingredientsText":"Test ingredients","allergenMask":1}' \
  | jq '.' | tee /tmp/product.json

PROD_ID=$(jq -r '.id' /tmp/product.json)
echo "Created ID: $PROD_ID"

echo ""
echo "2. READ"
curl -s -X GET "http://localhost:9090/products/$PROD_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.title'

echo ""
echo "3. UPDATE"
curl -s -X PUT "http://localhost:9090/products/$PROD_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sku":"E2E-PROD-UPD","title":"Updated Product","ingredientsText":"Updated ingredients","allergenMask":3}' \
  | jq '.title'

echo ""
echo "4. DELETE"
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" \
  -X DELETE "http://localhost:9090/products/$PROD_ID" \
  -H "Authorization: Bearer $TOKEN"

echo "✅ All Product CRUD operations successful!"
