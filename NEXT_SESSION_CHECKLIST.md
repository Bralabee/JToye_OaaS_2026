# Next Session Checklist

## ✅ Pre-Session Verification

Run these commands to verify infrastructure is ready:

```bash
# 1. Check Docker containers are running
docker ps | grep jtoye
# Expected: jtoye-postgres (port 5433), jtoye-keycloak (port 8085)

# 2. Verify Keycloak is accessible
curl -s http://localhost:8085/realms/jtoye-dev/.well-known/openid-configuration | jq -r '.issuer'
# Expected: http://localhost:8085/realms/jtoye-dev

# 3. Check port 9090 is available for Core Java API
lsof -ti:9090
# Expected: (no output - port is free)

# 4. Start Core Java API in IntelliJ
# Then verify: curl http://localhost:9090/actuator/health
# Expected: {"status":"UP"}
```

---

## 🎯 Session Goal: Fix Keycloak JWT Claims

**Objective**: Get `tenant_id` claim in JWT tokens (currently `null`)

**Current State**:
- ✅ Keycloak running with realm `jtoye-dev`
- ✅ Users created: `tenant-a-user`, `tenant-b-user`
- ✅ Tokens generate successfully
- ❌ `tenant_id` claim is `null` in tokens

---

## 🔧 Solution Options (Try in Order)

### Option A: Delete and Recreate Users (15 min)

```bash
# Get admin token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8085/realms/master/protocol/openid-connect/token \
  -d 'username=admin' \
  -d 'password=admin123' \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' | jq -r '.access_token')

# Get user ID
USER_ID=$(curl -s -X GET "http://localhost:8085/admin/realms/jtoye-dev/users?username=tenant-a-user" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

# Delete user
curl -X DELETE "http://localhost:8085/admin/realms/jtoye-dev/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Recreate with attributes in POST (not PUT)
curl -X POST "http://localhost:8085/admin/realms/jtoye-dev/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "tenant-a-user",
    "enabled": true,
    "email": "usera@tenanta.com",
    "emailVerified": true,
    "attributes": {
      "tenant_id": ["00000000-0000-0000-0000-000000000001"]
    },
    "credentials": [{
      "type": "password",
      "value": "password123",
      "temporary": false
    }]
  }'

# Verify token
bash test-jwt-tokens.sh
```

### Option B: JavaScript Protocol Mapper (20 min)

1. Open Keycloak Admin UI: http://localhost:8085
2. Login: admin / admin123
3. Go to: Clients → test-client → Client scopes → test-client-dedicated → Mappers
4. Click "Add mapper" → "By configuration" → "Script mapper"
5. Configuration:
   - **Name**: `tenant-id-script-mapper`
   - **Mapper Type**: `Script Mapper`
   - **Script**:
     ```javascript
     var username = user.getUsername();
     if (username === 'tenant-a-user') {
         '00000000-0000-0000-0000-000000000001';
     } else if (username === 'tenant-b-user') {
         '00000000-0000-0000-0000-000000000002';
     } else {
         null;
     }
     ```
   - **Token Claim Name**: `tenant_id`
   - **Claim JSON Type**: String
   - **Add to ID token**: ON
   - **Add to access token**: ON
6. Save and test: `bash test-jwt-tokens.sh`

### Option C: Use Keycloak Groups (30 min)

```bash
# Create groups
curl -X POST "http://localhost:8085/admin/realms/jtoye-dev/groups" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "tenant-a", "attributes": {"tenant_id": ["00000000-0000-0000-0000-000000000001"]}}'

curl -X POST "http://localhost:8085/admin/realms/jtoye-dev/groups" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "tenant-b", "attributes": {"tenant_id": ["00000000-0000-0000-0000-000000000002"]}}'

# Assign users to groups (UI or API)
# Then create group attribute mapper in client
```

### Option D: Temporary Dev Workaround (5 min)

**For immediate testing only** - allows X-Tenant-ID header without JWT:

Edit `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java`:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/health", "/actuator/health", "/actuator/info").permitAll()
    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
    .requestMatchers("/shops", "/products").permitAll()  // ← ADD THIS LINE
    .anyRequest().authenticated()
)
```

Restart application, then: `bash test-rls-api.sh`

**Remember to remove this before production!**

---

## ✅ Verification Steps

After implementing a solution:

```bash
# 1. Generate token
bash test-jwt-tokens.sh
# Should show: Tenant ID in token: 00000000-0000-0000-0000-000000000001

# 2. Test API with JWT
TOKEN_A=$(curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=test-client' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' | jq -r '.access_token')

curl -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/shops | jq '.'
# Should return 2 shops for Tenant A

# 3. Run full RLS test
# Update test-rls-api.sh to use JWT tokens instead of X-Tenant-ID
# Then run: bash test-rls-api.sh
```

---

## 📋 After Fixing JWT Claims

Once JWT claims are working, proceed with:

1. **Update API test script** to use JWT tokens
2. **Complete RLS isolation testing** (should show perfect tenant isolation)
3. **Build Edge Go service** (`cd edge-go && go build`)
4. **Test Edge → Core integration**
5. **End-to-end integration testing**
6. **Production readiness checklist**

---

## 📁 Key Files Reference

- `SESSION_HANDOFF.md` - Complete context (600+ lines)
- `test-jwt-tokens.sh` - Test JWT generation
- `test-rls-api.sh` - API isolation testing
- `infra/keycloak/configure-keycloak.sh` - Keycloak setup
- `create-test-data-corrected.sql` - Test data (already loaded)

---

## 🔍 Troubleshooting

### If tokens still don't have tenant_id:
```bash
# Check in Keycloak UI
http://localhost:8085 → Users → tenant-a-user → Attributes
# Should show: tenant_id = 00000000-0000-0000-0000-000000000001

# Check protocol mapper
Clients → test-client → Client scopes → Evaluate → tenant-a-user
# Preview should show tenant_id in token
```

### If API returns 401:
```bash
# Check issuer matches
curl http://localhost:9090/v3/api-docs | jq '.components.securitySchemes'
# Should show: http://localhost:8085/realms/jtoye-dev
```

### If RLS doesn't work:
```bash
# Test database directly
docker exec -it jtoye-postgres psql -U jtoye -d jtoye
\d shops  # Should show RLS policy
SELECT current_tenant_id();  # Test function
```

---

## 🎯 Success Criteria

You'll know it's working when:
1. ✅ `bash test-jwt-tokens.sh` shows non-null tenant_id
2. ✅ API calls with JWT return correct tenant-specific data
3. ✅ Tenant A cannot see Tenant B data (and vice versa)
4. ✅ API calls without JWT return 401 Unauthorized

---

**Estimated Time**: 30-60 minutes to fix JWT + test thoroughly

Good luck! 🚀
