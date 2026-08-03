# Quick Start Guide

Get J'Toye OaaS running in minutes!

---

## Choose Your Path

### 🐳 Option 1: Docker (Recommended - 2 Minutes)

**Best for:** First-time users, quick demos, testing

✅ **No local installations needed**
✅ **Works on all platforms**
⚠️ **A repo-root `.env` is required** — `docker-compose.full-stack.yml` declares 18 variables in
`${VAR:?}` form, so without it even `docker compose config` fails.

```bash
cp .env.example .env          # then fill in every CHANGE_ME value
bash scripts/verify-env.sh    # fails loudly if anything is missing or weak

docker compose -f docker-compose.full-stack.yml up
```

**Access the application:**
- Frontend: http://localhost:3000
- Core API: http://localhost:9090
- Keycloak: http://localhost:8085

**Test credentials** (all seed users share the value of `KC_SEED_USER_PASSWORD` from your `.env`):
- Tenant A: `tenant-a-user` / `$KC_SEED_USER_PASSWORD`
- Tenant B: `tenant-b-user` / `$KC_SEED_USER_PASSWORD`
- Admin: `admin-user` / `$KC_SEED_USER_PASSWORD`

**Stop the stack:**
```bash
docker compose -f docker-compose.full-stack.yml down
```

---

### 💻 Option 2: Local Development (5-10 Minutes)

**Best for:** Active development, debugging, IDE integration

**Prerequisites:**
- Java 21 (JDK 25 is incompatible with Gradle 8.10), Node.js 24+, Go 1.26+
- Docker with **Compose v2** (`docker compose`) — for the backing services only

#### Step 1: Environment Setup

**Linux/Mac:**
```bash
cp .env.example .env                                  # repo root — the compose stack reads this
cp frontend/.env.local.example frontend/.env.local
cp core-java/.env.example core-java/.env
cp edge-go/.env.example edge-go/.env
```

Fill in every `CHANGE_ME`. `scripts/run-app.sh` sources the repo-root `.env` first and then
`core-java/.env`, so a value set in the latter wins for the backend.

**Windows (Command Prompt):**
```cmd
copy frontend\.env.local.example frontend\.env.local
copy core-java\.env.example core-java\.env
copy edge-go\.env.example edge-go\.env
copy infra\.env.example infra\.env
```

**Windows (PowerShell):**
```powershell
Copy-Item frontend\.env.local.example frontend\.env.local
Copy-Item core-java\.env.example core-java\.env
Copy-Item edge-go\.env.example edge-go\.env
Copy-Item infra\.env.example infra\.env
```

#### Step 2: Start Infrastructure

```bash
cd infra
docker compose up -d
cd ..
```

**Verify:**
```bash
docker ps | grep jtoye
# Should show: jtoye-postgres and jtoye-keycloak
```

#### Step 3: Start Backend

```bash
./scripts/run-app.sh
```

**Or manually:**
```bash
DB_PORT=5433 ./gradlew :core-java:bootRun
```

**Verify:**
```bash
curl http://localhost:9090/actuator/health
# Should return: {"status":"UP"}
```

#### Step 4: Start Frontend

```bash
cd frontend
npm install
npm run dev
```

**Verify:** Open http://localhost:3000

#### Step 5: (Optional) Start Edge Service

```bash
cd edge-go
go run ./cmd/edge
```

---

## Verification Checklist

Once services are running, verify everything works:

- [ ] **Frontend loads:** http://localhost:3000
- [ ] **Sign in works:** Use `tenant-a-user` / the value of `KC_SEED_USER_PASSWORD`
- [ ] **Dashboard visible:** Should show shops, products, orders
- [ ] **API responds:** http://localhost:9090/actuator/health
- [ ] **Keycloak accessible:** http://localhost:8085

---

## Common Issues

### "Cannot connect to database"

**Cause:** PostgreSQL not running or wrong port

**Fix:**
```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# If not running, start infrastructure
docker compose -f docker-compose.full-stack.yml up -d postgres keycloak redis rabbitmq
```

### "Authentication failed" or "Configuration error"

**Cause:** Missing or incorrect `.env.local` file

**Fix:**
```bash
# Verify file exists
ls -la frontend/.env.local

# If missing, copy from template
cp frontend/.env.local.example frontend/.env.local

# Restart frontend
cd frontend && npm run dev
```

### "Port already in use"

**Cause:** Another service using required ports

**Fix:**
```bash
# Find what's using the port (example: 9090)
# Linux/Mac:
lsof -i :9090

# Windows PowerShell:
Get-NetTCPConnection -LocalPort 9090

# Stop the conflicting service or use different ports
```

### Frontend shows blank page

**Cause:** Environment variables not loaded or backend not running

**Fix:**
1. Check backend is running: `curl http://localhost:9090/actuator/health`
2. Verify `NEXT_PUBLIC_API_URL=http://localhost:9090` in `frontend/.env.local`
3. Restart frontend: `cd frontend && npm run dev`

---

## Next Steps

Now that you're running:

1. **Explore the UI:** Browse shops, products, orders at http://localhost:3000
2. **Try the API:** See [TESTING.md](TESTING.md) for API examples
3. **Read the docs:**
   - [USER_GUIDE.md](USER_GUIDE.md) - How to use the application
   - [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Production deployment
   - [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md) - Detailed configuration

---

## Development Workflow

### Making Changes

**Backend (Java):**
```bash
# Changes auto-reload with Spring DevTools
# Just save your file and wait ~5 seconds
```

**Frontend (Next.js):**
```bash
# Hot reload enabled
# Save file → refresh browser
```

**Database Changes:**
```bash
# Create new Flyway migration in:
# core-java/src/main/resources/db/migration/

# Name format: V{version}__{description}.sql
# Example: V3__add_inventory_table.sql
```

### Running Tests

**Backend:**
```bash
./gradlew :core-java:test
```

**Edge:**
```bash
cd edge-go && go test ./...
```

**Frontend:**
```bash
cd frontend && npm test
```

---

## Stopping Services

**Docker (full stack):**
```bash
docker compose -f docker-compose.full-stack.yml down

# To also remove data:
docker compose -f docker-compose.full-stack.yml down -v
```

**Local development:**
1. Stop frontend: `Ctrl+C` in terminal
2. Stop backend: `Ctrl+C` in terminal
3. Stop infrastructure:
   ```bash
   docker compose -f docker-compose.full-stack.yml down
   ```

---

**Need Help?** See [TROUBLESHOOTING](ENVIRONMENT_SETUP.md#troubleshooting) for comprehensive troubleshooting guide.
