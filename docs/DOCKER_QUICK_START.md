# Docker Quick Start Guide

**J'Toye OaaS - Full Stack Docker Setup**

## ✅ No Environment Setup Required

The full-stack Docker Compose configuration has all values hardcoded for simplicity. **No `.env` files are needed!**

This is the easiest way to get the entire application running.

## One-Command Setup

```bash
docker-compose -f docker-compose.full-stack.yml up -d --build
```

Wait 1-2 minutes for all services to start, then access:
- **Frontend**: http://localhost:3000
- **Core API**: http://localhost:9090
- **Keycloak**: http://localhost:8085

---

## What Gets Started

| Service | Port | Description |
|---------|------|-------------|
| PostgreSQL | 5433 | Database with RLS policies |
| Keycloak | 8085 | Identity provider (OIDC) |
| Redis | 6379 | Cache |
| RabbitMQ | 5672, 15672 | Message queue + management UI |
| Core Java API | 9090 | Spring Boot backend |
| Edge Go Gateway | 8089 | Go API gateway |
| Frontend | 3000 | Next.js 14 UI |

---

## Test the System

### 1. Test Authentication

```bash
# Open browser
http://localhost:3000/auth/signin

# Login with
Username: admin
Password: admin123
```

### 2. Test API (CRUD Operations)

```bash
# Get authentication token (from inside Docker network)
docker exec jtoye-core-java curl -s -X POST \
  http://keycloak:8080/realms/jtoye-dev/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=core-api" \
  -d "client_secret=core-api-secret-2026" \
  -d "grant_type=client_credentials" | jq -r '.access_token' > /tmp/token.txt

# Set tenant ID
TENANT_ID="00000000-0000-0000-0000-000000000001"
TOKEN=$(cat /tmp/token.txt)

# CREATE - Add a customer
curl -X POST http://localhost:9090/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Customer","email":"test@example.com","phone":"+1234567890"}'

# READ - Get all customers
curl -X GET http://localhost:9090/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID"

# UPDATE - Update a customer (replace {id} with actual ID)
curl -X PUT http://localhost:9090/customers/{id} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/json" \
  -d '{"name":"Updated Customer","email":"updated@example.com","phone":"+9876543210"}'

# DELETE - Delete a customer
curl -X DELETE http://localhost:9090/customers/{id} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID"
```

---

## Important Notes

### API Endpoints
**✅ Correct:** `http://localhost:9090/customers`
**❌ Wrong:** `http://localhost:9090/api/v1/customers`

The API has NO `/api/v1` prefix. Endpoints are:
- `/customers`
- `/products`
- `/orders`
- `/shops`
- `/financial-transactions`

### Required Headers
```
Authorization: Bearer <token>
X-Tenant-Id: <tenant-uuid>
```

Note: Header name is `X-Tenant-Id` (lowercase 'd'), not `X-Tenant-ID`.

### Tenant IDs
- Tenant A: `00000000-0000-0000-0000-000000000001`
- Tenant B: `00000000-0000-0000-0000-000000000002`

---

## Common Commands

```bash
# View logs
docker-compose -f docker-compose.full-stack.yml logs -f

# View specific service logs
docker logs -f jtoye-frontend
docker logs -f jtoye-core-java
docker logs -f jtoye-keycloak

# Check service status
docker-compose -f docker-compose.full-stack.yml ps

# Restart a service
docker restart jtoye-frontend

# Stop all services
docker-compose -f docker-compose.full-stack.yml down

# Stop and remove volumes (clean slate)
docker-compose -f docker-compose.full-stack.yml down -v

# Rebuild and restart
docker-compose -f docker-compose.full-stack.yml up -d --build
```

---

## Networking Architecture

### How OAuth Works in Docker

1. **Browser** accesses `http://localhost:8085` (Keycloak on host)
2. **Keycloak** issues token with issuer: `http://localhost:8085/realms/jtoye-dev`
3. **Frontend container** validates token:
   - Has `extra_hosts: localhost:host-gateway` to reach host's localhost
   - Uses `KEYCLOAK_ISSUER: http://localhost:8085/realms/jtoye-dev`
   - Token issuer matches expected issuer ✅
4. **Success** - User authenticated

### Why This Works

- **No /etc/hosts required** - Everything uses localhost
- **Keycloak accessible from both:**
  - Browser: `localhost:8085` → Host port mapping
  - Frontend container: `localhost:8085` → `host-gateway` extra_hosts entry
- **Token issuer consistency** - All components use `localhost:8085`

---

## Troubleshooting

### Authentication Error "Configuration"

If you see this error:
```
GET http://localhost:3000/api/auth/error?error=Configuration
Status 500
```

**Cause:** Frontend can't validate the token because issuer mismatch.

**Solution:** 
```bash
# Recreate frontend and keycloak with correct configuration
docker-compose -f docker-compose.full-stack.yml up -d --force-recreate keycloak frontend
```

### Port Conflicts

If ports are already in use:
```bash
# Find what's using the port
lsof -i :9090
lsof -i :3000
lsof -i :8085

# Kill the process or stop the service
```

### Services Not Healthy

```bash
# Wait longer (services take 1-2 minutes to start)
sleep 60

# Check health status
docker-compose -f docker-compose.full-stack.yml ps

# If still unhealthy, check logs
docker logs jtoye-core-java --tail 50
```

### Database Issues

```bash
# Connect to PostgreSQL
docker exec -it jtoye-postgres psql -U jtoye -d jtoye

# Check tenants exist
SELECT * FROM tenants;

# Check RLS is enabled
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname='public';

# Exit psql
\q
```

---

## Credentials Reference

| Service | Username | Password |
|---------|----------|----------|
| Keycloak Admin | admin | admin123 |
| PostgreSQL | jtoye | secret |
| RabbitMQ | jtoye | rabbitmqpass123 |
| Redis | - | redispass123 |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Browser (Host)                                             │
│  → http://localhost:3000 (Frontend)                         │
│  → http://localhost:8085 (Keycloak OAuth)                   │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ (port mappings)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Docker Network: jtoye-network                              │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  Frontend    │  │  Core Java   │  │  Edge Go     │     │
│  │  :3000       │  │  :9090       │  │  :8089       │     │
│  │  extra_hosts:│  │              │  │              │     │
│  │  localhost→  │  │              │  │              │     │
│  │  host-gateway│  │              │  │              │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                 │                 │              │
│         └─────────────────┴─────────────────┘              │
│                           │                                 │
│         ┌─────────────────┼─────────────────┐              │
│         │                 │                 │              │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐    │
│  │  Keycloak    │  │  PostgreSQL  │  │  Redis       │    │
│  │  :8080       │  │  :5432       │  │  :6379       │    │
│  │              │  │  (RLS)       │  │              │    │
│  └──────────────┘  └──────────────┘  └──────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

**Last Updated:** 2025-12-31
**Docker Compose File:** `docker-compose.full-stack.yml`
**For Issues:** Check `docs/DEPLOYMENT_GUIDE.md` Section 8 (Troubleshooting)
