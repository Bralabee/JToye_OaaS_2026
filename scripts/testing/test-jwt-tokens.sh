#!/bin/bash

echo "=== Testing JWT Token Generation ==="
echo ""

# Get token for Tenant A
echo "1. Getting token for tenant-a-user..."
TOKEN_A=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_A" != "null" ] && [ -n "$TOKEN_A" ]; then
  echo "   ✓ Token generated"
  TENANT_ID_A=$(echo "$TOKEN_A" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id')
  echo "   Tenant ID in token: $TENANT_ID_A"
  echo ""
  echo "   Export as:"
  echo "   export TOKEN_A='$TOKEN_A'"
else
  echo "   ✗ Failed to generate token"
fi

echo ""

# Get token for Tenant B
echo "2. Getting token for tenant-b-user..."
TOKEN_B=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' | jq -r '.access_token')

if [ "$TOKEN_B" != "null" ] && [ -n "$TOKEN_B" ]; then
  echo "   ✓ Token generated"
  TENANT_ID_B=$(echo "$TOKEN_B" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq -r '.tenant_id')
  echo "   Tenant ID in token: $TENANT_ID_B"
  echo ""
  echo "   Export as:"
  echo "   export TOKEN_B='$TOKEN_B'"
else
  echo "   ✗ Failed to generate token"
fi

echo ""
echo "=== Testing Core API with JWT ==="
echo ""

# Test Core API with Tenant A token
if [ "$TOKEN_A" != "null" ] && [ -n "$TOKEN_A" ]; then
  echo "3. Testing /actuator/health with Tenant A token..."
  RESPONSE=$(curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/actuator/health)
  echo "   Response: $RESPONSE"
  echo ""
fi

# Save tokens to file for later use
cat > /tmp/jwt-tokens.sh <<EOF
#!/bin/bash
# JWT tokens for testing (valid for 1 hour)
export TOKEN_A='$TOKEN_A'
export TOKEN_B='$TOKEN_B'

echo "Tokens loaded:"
echo "  TOKEN_A (tenant-a-user): \${TOKEN_A:0:50}..."
echo "  TOKEN_B (tenant-b-user): \${TOKEN_B:0:50}..."
EOF
chmod +x /tmp/jwt-tokens.sh

echo "✓ Tokens saved to /tmp/jwt-tokens.sh"
echo "  Source it with: source /tmp/jwt-tokens.sh"
echo ""
