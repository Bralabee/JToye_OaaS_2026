#!/bin/bash
KC=http://localhost:8081
TOKEN=$(curl -s -d 'grant_type=password' -d 'client_id=core-api' -d 'username=dev-user' -d 'password=password' "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)
TENANT_A=8d5e8f7a-9c2d-4c1a-9c2f-1f1a2b3c4d5e
TENANT_B=11111111-2222-3333-4444-555555555555

echo "Ensuring Tenant A exists..."
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" "http://localhost:8080/dev/tenants/ensure?name=Tenant-A"

echo -e "\nCreating shop for Tenant A..."
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" -H 'Content-Type: application/json' -d '{"name":"Main Street Shop","address":"1 Main St"}' http://localhost:8080/shops | jq

echo -e "\nListing shops for Tenant A..."
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" http://localhost:8080/shops | jq

echo -e "\nEnsuring Tenant B exists..."
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_B" "http://localhost:8080/dev/tenants/ensure?name=Tenant-B"

echo -e "\nListing shops for Tenant B..."
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_B" http://localhost:8080/shops | jq
