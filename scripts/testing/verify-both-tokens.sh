#!/bin/bash

echo "=== Verifying JWT Tokens with tenant_id ==="
echo ""

echo "1. Tenant A User:"
TOKEN_A=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

TENANT_ID_A=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id')
GROUPS_A=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.groups[]')

echo "   tenant_id: $TENANT_ID_A"
echo "   groups: $GROUPS_A"

if [ "$TENANT_ID_A" == "00000000-0000-0000-0000-000000000001" ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL"
fi

echo ""
echo "2. Tenant B User:"
TOKEN_B=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' | jq -r '.access_token')

TENANT_ID_B=$(echo "$TOKEN_B" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id')
GROUPS_B=$(echo "$TOKEN_B" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.groups[]')

echo "   tenant_id: $TENANT_ID_B"
echo "   groups: $GROUPS_B"

if [ "$TENANT_ID_B" == "00000000-0000-0000-0000-000000000002" ]; then
    echo "   ✓ PASS"
else
    echo "   ✗ FAIL"
fi

echo ""
if [ "$TENANT_ID_A" == "00000000-0000-0000-0000-000000000001" ] && [ "$TENANT_ID_B" == "00000000-0000-0000-0000-000000000002" ]; then
    echo "✓ SUCCESS: Both tokens have correct tenant_id claims!"
    echo ""
    echo "Saving tokens..."
    cat > /tmp/working-tokens.sh <<EOF
#!/bin/bash
export TOKEN_A='$TOKEN_A'
export TOKEN_B='$TOKEN_B'
echo "✓ JWT tokens loaded with tenant_id claims"
echo "  Tenant A: $TENANT_ID_A"
echo "  Tenant B: $TENANT_ID_B"
EOF
    chmod +x /tmp/working-tokens.sh
    echo "✓ Tokens saved to /tmp/working-tokens.sh"
else
    echo "✗ FAILED: One or both tokens have incorrect tenant_id"
fi
