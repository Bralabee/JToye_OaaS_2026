# Configuration Guide

Detailed configuration reference for J'Toye OaaS.

---

## Table of Contents

1. [Service Ports](#service-ports)
2. [Security & Authentication](#security--authentication)
3. [Multi-Tenant Configuration](#multi-tenant-configuration)
4. [Database Configuration](#database-configuration)
5. [Keycloak Setup](#keycloak-setup)
6. [Testing Multi-Tenancy](#testing-multi-tenancy)

---

## Service Ports

### Default Ports (Local Development)

| Service | Port | Protocol | Purpose |
|---------|------|----------|---------|
| Frontend | 3000 | HTTP | Next.js UI |
| Core API | 9090 | HTTP | Spring Boot backend |
| Edge Gateway | 8080 (local)<br/>8089 (Docker) | HTTP | Go API gateway |
| PostgreSQL | 5433 (host)<br/>5432 (container) | TCP | Database |
| Keycloak | 8085 (host)<br/>8080 (container) | HTTP | Identity provider |
| Redis | 6379 | TCP | Cache |
| RabbitMQ | 5672 (AMQP)<br/>15672 (Management UI) | TCP/HTTP | Message queue |

### Changing Ports

**Core API (`core-java/.env`):**
```bash
SERVER_PORT=9090  # Change to desired port
```

**Frontend (`frontend/.env.local`):**
```bash
# If you changed Core API port, update this too
NEXT_PUBLIC_API_URL=http://localhost:9090
```

**Edge Gateway (`edge-go/.env`):**
```bash
PORT=8080  # Internal port
```

---

## Security & Authentication

### OAuth2 / OIDC Flow

J'Toye OaaS uses Keycloak for authentication with OAuth2/OIDC:

```
User → Frontend → Keycloak (Login) → Keycloak (Issues JWT) → Frontend → Core API (validates JWT)
```

### JWT Token Structure

Tokens contain these critical claims:

```json
{
  "iss": "http://localhost:8085/realms/jtoye-dev",
  "sub": "user-uuid",
  "tenant_id": "00000000-0000-0000-0000-000000000001",
  "preferred_username": "tenant-a-user",
  "email": "user@example.com",
  "realm_access": {
    "roles": ["user"]
  }
}
```

**Critical:** The `tenant_id` claim drives Row-Level Security (RLS).

### Issuer Configuration

**All services must use matching issuer URLs:**

**Frontend (`frontend/.env.local`):**
```bash
KEYCLOAK_ISSUER=http://localhost:8085/realms/jtoye-dev
```

**Core Java (`core-java/.env`):**
```bash
KC_ISSUER_URI=http://localhost:8085/realms/jtoye-dev
```

**Edge Go (`edge-go/.env`):**
```bash
KC_ISSUER_URI=http://localhost:8085/realms/jtoye-dev
```

⚠️ **Important:** Docker internal networking uses different URLs (see Docker Compose files).

---

## Multi-Tenant Configuration

### Tenant Resolution Priority

The system extracts tenant ID in this order:

1. **JWT claim** (production):
   - `tenant_id` (preferred)
   - `tenantId` (alternative)
   - `tid` (fallback)

2. **HTTP Header** (development only):
   - `X-Tenant-Id: <uuid>`

### Tenant-Aware Components

**JwtTenantFilter (`core-java`):**
```java
// Extracts tenant_id from JWT claims
// Stores in TenantContext for request scope
```

**TenantSetLocalAspect (`core-java`):**
```java
@Before("@annotation(org.springframework.transaction.annotation.Transactional)")
// Executes: SET LOCAL app.current_tenant_id = '<uuid>'
// Before every @Transactional method
```

**Row-Level Security Policies (PostgreSQL):**
```sql
CREATE POLICY tenant_isolation ON shops
  FOR ALL
  TO jtoye_app
  USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
```

### Default Tenant IDs

```bash
# Tenant A
TENANT_A=00000000-0000-0000-0000-000000000001

# Tenant B
TENANT_B=00000000-0000-0000-0000-000000000002
```

---

## Database Configuration

### Connection Details

**Local Development (`core-java/.env`):**
```bash
DB_HOST=localhost
DB_PORT=5433         # Host port (Docker maps 5433→5432)
DB_NAME=jtoye
DB_USER=jtoye
DB_PASSWORD=secret
```

**Docker Internal (`docker-compose.full-stack.yml`):**
```yaml
DB_HOST=postgres     # Docker service name
DB_PORT=5432         # Container port
DB_NAME=jtoye
DB_USER=jtoye
DB_PASSWORD=secret
```

### Connection Pool

**Hikari Configuration (`application.yml`):**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### Database Users

| User | Purpose | RLS Enforced |
|------|---------|--------------|
| `postgres` | Superuser | No (bypasses RLS) |
| `jtoye` | Application | Yes |
| `jtoye_app` | Tests | Yes |

⚠️ **Never use superuser for application!** Superusers bypass RLS.

---

## Keycloak Setup

### Pre-configured Realm

The project includes `infra/keycloak/realm-export.json` with:

**Realm:** `jtoye-dev`

**Clients:**
- `core-api` - Backend service
  - Client ID: `core-api`
  - Secret: `core-api-secret-2026`
  - Access Type: Confidential

- `frontend` - Next.js application
  - Client ID: `frontend`
  - Redirect URIs: `http://localhost:3000/*`

**Groups:**
- `tenant-a` - Tenant A users
  - Attribute: `tenant_id = 00000000-0000-0000-0000-000000000001`
- `tenant-b` - Tenant B users
  - Attribute: `tenant_id = 00000000-0000-0000-0000-000000000002`

**Users:**
| Username | Password | Group | Role |
|----------|----------|-------|------|
| admin-user | admin123 | - | Admin |
| tenant-a-user | password123 | tenant-a | User |
| tenant-b-user | password123 | tenant-b | User |

**Protocol Mapper:**
- Name: `tenant_id`
- Type: User Attribute
- User Attribute: `tenant_id`
- Token Claim Name: `tenant_id`
- Claim JSON Type: String
- Add to ID token: Yes
- Add to access token: Yes

### Accessing Keycloak Admin

```
URL: http://localhost:8085
Username: admin
Password: admin123
```

### Getting a Token (CLI)

```bash
KC=http://localhost:8085

# Get token for Tenant A user
TOKEN_A=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

echo "Token: $TOKEN_A"

# Decode token (check tenant_id claim)
echo $TOKEN_A | cut -d. -f2 | base64 -d | jq .
```

---

## Testing Multi-Tenancy

### JWT-Based Testing (Production Mode)

**1. Get tokens for different tenants:**
```bash
KC=http://localhost:8085

TOKEN_A=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-a-user' \
  -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

TOKEN_B=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=tenant-b-user' \
  -d 'password=password123' \
  "$KC/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)
```

**2. Create data for Tenant A:**
```bash
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant A Shop","address":"123 Main St"}'
```

**3. Create data for Tenant B:**
```bash
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant B Shop","address":"456 Oak Ave"}'
```

**4. Verify isolation:**
```bash
# Tenant A can only see their shop
curl -s -H "Authorization: Bearer $TOKEN_A" http://localhost:9090/shops | jq '.content[] | .name'
# Output: "Tenant A Shop"

# Tenant B can only see their shop
curl -s -H "Authorization: Bearer $TOKEN_B" http://localhost:9090/shops | jq '.content[] | .name'
# Output: "Tenant B Shop"
```

### Header-Based Testing (Development Mode)

⚠️ **Only use in development!** Production relies on JWT claims.

```bash
# Get any valid token
TOKEN=$(curl -s \
  -d 'grant_type=password' \
  -d 'client_id=core-api' \
  -d 'username=admin-user' \
  -d 'password=admin123' \
  "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token" | jq -r .access_token)

TENANT_A=00000000-0000-0000-0000-000000000001
TENANT_B=00000000-0000-0000-0000-000000000002

# Create as Tenant A
curl -X POST http://localhost:9090/shops \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Shop A","address":"123 Test St"}'

# List as Tenant A
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_A" \
  http://localhost:9090/shops | jq

# List as Tenant B (should be empty)
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_B" \
  http://localhost:9090/shops | jq
```

---

## Advanced Configuration

### Build Directory Override

**Issue:** Permission conflicts with Docker-mounted Gradle cache

**Solution:** Use custom build directory
```bash
./gradlew -PbuildDir=build-local :core-java:bootRun
```

Or set in `core-java/.env`:
```bash
GRADLE_OPTS=-PbuildDir=build-local
```

### Spring Profiles

**Available profiles:**
- `dev` - Development (verbose logging, dev tools)
- `prod` - Production (optimized, minimal logging)
- `local` - Local overrides

**Set in `core-java/.env`:**
```bash
SPRING_PROFILES_ACTIVE=dev
```

### Logging Levels

**Customize in `core-java/.env`:**
```bash
LOG_LEVEL=INFO              # Application logs
SQL_LOG_LEVEL=WARN          # SQL queries
SECURITY_LOG_LEVEL=DEBUG    # Security/auth logs
```

---

## Production Considerations

### Environment Variables (Production)

⚠️ **Never use default values in production!**

**Change these immediately:**
```bash
# Frontend
NEXTAUTH_SECRET=<generate-32-char-random-string>
KEYCLOAK_CLIENT_SECRET=<from-keycloak-admin>

# Database
DB_PASSWORD=<strong-random-password>

# Keycloak
KC_ADMIN_PASSWORD=<strong-random-password>

# Redis
REDIS_PASSWORD=<strong-random-password>

# RabbitMQ
RABBITMQ_PASSWORD=<strong-random-password>
```

### Secret Generation

**Linux/Mac:**
```bash
# Generate NEXTAUTH_SECRET
openssl rand -base64 32

# Generate passwords
openssl rand -base64 24
```

**Windows PowerShell:**
```powershell
# Generate random string
-join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object {[char]$_})
```

### TLS/HTTPS

In production, all services should use HTTPS:

```bash
# Frontend
NEXTAUTH_URL=https://app.yourdomain.com
NEXT_PUBLIC_API_URL=https://api.yourdomain.com

# Keycloak
KEYCLOAK_ISSUER=https://auth.yourdomain.com/realms/jtoye-prod
```

---

## Troubleshooting

### "Issuer mismatch" errors

**Symptom:** JWT validation fails with issuer mismatch

**Cause:** Keycloak issuer URL doesn't match config

**Fix:** Ensure all services use identical issuer URL:
```bash
# Check token issuer
echo $TOKEN | cut -d. -f2 | base64 -d | jq .iss

# Should match KEYCLOAK_ISSUER in all .env files
```

### RLS not working / Cross-tenant data visible

**Symptom:** Users see data from other tenants

**Possible causes:**
1. Using superuser for application (bypasses RLS)
2. `TenantSetLocalAspect` not executing
3. Missing `@Transactional` annotation

**Fix:**
```bash
# 1. Verify using correct database user
# Check application.yml: spring.datasource.username should be 'jtoye' not 'postgres'

# 2. Check logs for "SET LOCAL" execution
# Should see: "Setting tenant context: <uuid>" before queries

# 3. Ensure methods use @Transactional
# Repository methods need @Transactional in service layer
```

---

**See Also:**
- [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md) - Environment variable setup
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Production deployment
- [SECURITY_AUDIT_REPORT.md](SECURITY_AUDIT_REPORT.md) - Security assessment
