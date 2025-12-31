# Docker Networking Fix - December 31, 2025

**Issue:** Docker full-stack authentication failing with NextAuth.js issuer mismatch  
**Status:** ✅ RESOLVED  
**Date:** December 31, 2025

---

## Problem Summary

When running the full-stack application in Docker (`docker-compose.full-stack.yml`), authentication was failing with:

```
GET http://localhost:3000/api/auth/error?error=Configuration
Status 500 Internal Server Error

Error: unexpected "iss" (issuer) response parameter value
Expected: http://keycloak:8080/realms/jtoye-dev
Actual: http://localhost:8085/realms/jtoye-dev
```

### Root Cause

**Token Issuer Mismatch:** The Keycloak token contained `iss: http://localhost:8085/realms/jtoye-dev` (from browser OAuth flow), but the NextAuth.js frontend expected `iss: http://keycloak:8080/realms/jtoye-dev` (internal Docker network).

### Why This Happened

1. Browser redirects to `http://localhost:8085` for OAuth (host port mapping)
2. Keycloak issues token with that issuer in the JWT
3. Frontend container tried to validate using `http://keycloak:8080` (Docker DNS)
4. **Mismatch** → Authentication failed

---

## Solution

### Key Changes to `docker-compose.full-stack.yml`

#### 1. Keycloak Configuration
```yaml
keycloak:
  environment:
    KC_HOSTNAME_URL: http://localhost:8085  # ✅ Consistent issuer for all contexts
    KC_HOSTNAME_STRICT: false
```

#### 2. Frontend Configuration
```yaml
frontend:
  environment:
    KEYCLOAK_ISSUER: http://localhost:8085/realms/jtoye-dev  # ✅ Match token issuer
  extra_hosts:
    - "localhost:host-gateway"  # ✅ Allow container to reach host's localhost
```

#### 3. Removed Invalid Approach
```yaml
# ❌ REMOVED - Breaks Docker isolation
# network_mode: host
```

---

## How It Works Now

### OAuth Flow
```
1. Browser → http://localhost:8085 (Keycloak on host via port mapping)
   ↓
2. User authenticates
   ↓
3. Keycloak issues token with: iss: http://localhost:8085/realms/jtoye-dev
   ↓
4. Browser redirects to frontend with token
   ↓
5. Frontend container validates token:
   - Uses extra_hosts: localhost:host-gateway to reach localhost:8085
   - KEYCLOAK_ISSUER matches token issuer ✅
   ↓
6. Success! User authenticated
```

### Network Architecture
```
Browser (Host)
    │
    ├─→ localhost:3000 → Frontend container
    └─→ localhost:8085 → Keycloak container
                              ↑
Frontend container ──────────┘
(via host-gateway)
```

---

## Testing Results

### ✅ All Services Healthy
```bash
docker-compose -f docker-compose.full-stack.yml ps
```
- PostgreSQL: ✅ Healthy
- Keycloak: ✅ Healthy  
- Redis: ✅ Healthy
- RabbitMQ: ✅ Healthy
- Core Java: ✅ Healthy
- Edge Go: ✅ Healthy
- Frontend: ✅ Healthy

### ✅ Authentication Flow Working
- Sign-in page loads: http://localhost:3000/auth/signin
- Keycloak redirect works
- Token validation succeeds
- Dashboard access granted

### ✅ CRUD Operations Working
Successfully tested all operations with proper authentication:
1. **CREATE:** Customer added with tenant isolation
2. **READ:** Retrieved paginated customer list  
3. **READ (ID):** Retrieved specific customer
4. **UPDATE:** Modified customer details
5. **DELETE:** Removed customer (HTTP 204)
6. **VERIFY:** Confirmed deletion (404)

---

## Important Notes

### API Endpoint Structure
**✅ Correct:** `http://localhost:9090/customers`  
**❌ Wrong:** `http://localhost:9090/api/v1/customers`

There is NO `/api/v1` prefix. Endpoints:
- `/customers`
- `/products`
- `/orders`
- `/shops`
- `/financial-transactions`

### Required Headers
```bash
Authorization: Bearer <token>
X-Tenant-Id: <uuid>  # Note: lowercase 'd' in 'Id'
```

### Tenant IDs
- Tenant A: `00000000-0000-0000-0000-000000000001`
- Tenant B: `00000000-0000-0000-0000-000000000002`

---

## Documentation Updates

### New Documents Created
1. **`docs/DOCKER_QUICK_START.md`** - Complete quick start guide with:
   - One-command setup
   - Architecture diagrams
   - CRUD testing examples
   - Troubleshooting guide

### Updated Documents
1. **`docs/AI_CONTEXT.md`**
   - Added Docker networking section
   - Updated runtime assumptions
   - Added tenant ID reference

2. **`docs/DEPLOYMENT_GUIDE.md`**
   - Updated section 3.2.1 with correct networking explanation
   - Removed /etc/hosts requirement
   - Added OAuth flow documentation

3. **`docs/DOCUMENTATION_INDEX.md`**
   - Added link to new Docker quick start guide
   - Updated numbering

---

## Key Learnings

### ✅ Do This
- Use `localhost:8085` for all Keycloak references (consistency)
- Use `extra_hosts: localhost:host-gateway` for containers needing host access
- Configure Keycloak with `KC_HOSTNAME_URL` for consistent token issuer
- Keep services on Docker networks (don't break isolation)

### ❌ Avoid This
- Using `network_mode: host` (breaks Docker isolation)
- Using different issuers for different contexts (causes validation failures)
- Requiring host configuration like /etc/hosts (not portable)
- Mixing internal DNS names with external URLs in token validation

---

## Commands Reference

### Start Full Stack
```bash
docker-compose -f docker-compose.full-stack.yml up -d --build
```

### Get Token for API Testing
```bash
docker exec jtoye-core-java curl -s -X POST \
  http://keycloak:8080/realms/jtoye-dev/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=core-api" \
  -d "client_secret=core-api-secret-2026" \
  -d "grant_type=client_credentials" | jq -r '.access_token'
```

### Test CRUD
```bash
# See docs/DOCKER_QUICK_START.md for complete examples
```

### Monitor Logs
```bash
docker logs -f jtoye-frontend
docker logs -f jtoye-core-java
```

---

## Verification Checklist

- [x] All services start and reach healthy status
- [x] Frontend loads at http://localhost:3000
- [x] Keycloak accessible at http://localhost:8085
- [x] Authentication flow completes successfully
- [x] Token validation works
- [x] CRUD operations succeed with proper tenant isolation
- [x] No host configuration required
- [x] Documentation updated for humans and AI

---

**Resolution Date:** December 31, 2025  
**Tested By:** Claude (AI Agent)  
**Verified By:** User (sanmi)  
**Next Review:** Q1 2026

---

## Related Documents
- [DOCKER_QUICK_START.md](../DOCKER_QUICK_START.md)
- [DEPLOYMENT_GUIDE.md](../DEPLOYMENT_GUIDE.md)
- [AI_CONTEXT.md](../AI_CONTEXT.md)
