# JToye OaaS 2026 - Setup Instructions

## Quick Start (Recommended)

### Full-Stack Docker Setup
**No local installations required!** Run everything in Docker:

```bash
# Start all services (PostgreSQL, Keycloak, Redis, RabbitMQ, Backend, Edge, Frontend)
docker compose -f docker-compose.full-stack.yml up
```

Access the application:
- **Frontend**: http://localhost:3000
- **Backend API**: http://localhost:9090
- **Keycloak**: http://localhost:8085

Login with: `tenant-a-user` / the value of `KC_SEED_USER_PASSWORD` (from your `.env`)

Stop the stack:
```bash
docker compose -f docker-compose.full-stack.yml down
```

---

## Developer Setup (Local Development)

### Prerequisites
- **Java 25** (Eclipse Temurin recommended) — the bundled Gradle 9.7.1 wrapper is required (JDK 25 needs Gradle >= 9.1). Always invoke `./gradlew`, never a system `gradle`.
- **Node.js 24+** (for frontend)
- **Go 1.26+** (for edge-go)
- **Docker & Docker Compose** (for infrastructure)

### 1. Start Infrastructure Services

Start PostgreSQL, Keycloak, Redis, and RabbitMQ:
```bash
docker compose -f docker-compose.full-stack.yml up -d postgres keycloak redis rabbitmq
```

Verify services are running:
```bash
docker compose -f docker-compose.full-stack.yml ps
```

### 2. Running Services Locally

### Option 1: Using the run script (Recommended)
```bash
./scripts/run-app.sh
```

#### Core Java Backend

**Option A: Using Gradle**
```bash
cd core-java
../gradlew bootRun --args='--spring.profiles.active=local'
```

**Option B: IntelliJ IDEA**
1. Open `Run` → `Edit Configurations...`
2. Select `CoreApplication`
3. Set **Active profiles**: `local`
4. Run the application

The `local` profile is pre-configured to connect to PostgreSQL on port 5433.

#### Edge Go Service
```bash
cd edge-go
go run ./cmd/edge
```

#### Frontend
```bash
cd frontend
npm install
npm run dev
```

Access at: http://localhost:3000

## Configuration

The application uses these database settings (from `application.yml`):

- **Host**: `localhost` (override with `DB_HOST`)
- **Port**: `5432` (override with `DB_PORT=5433` ⚠️ **REQUIRED**)
- **Database**: `jtoye` (override with `DB_NAME`)
- **Username**: `jtoye_runtime` (set via `DB_USER`; this is what `.env.example` ships)
- **Migrator**: `jtoye_app` (set via `DB_MIGRATION_USER`) — used by Flyway only. It OWNS the
  public tables, and `DatabaseConfigurationValidator.validateNotTableOwner()` refuses to boot
  the application as a role that owns tables, so it is not a substitute for `DB_USER`.
- **Password**: *empty* (you **must** supply `DB_PASSWORD`)

⚠️ Do not set `DB_USER=jtoye`. `jtoye` is a PostgreSQL superuser; superusers bypass row-level
security, and `DatabaseConfigurationValidator` refuses to start the application if it detects one.

## Troubleshooting

### "Password authentication failed for user 'jtoye'"
- **Cause**: Application is trying to connect to wrong port (5432 instead of 5433)
- **Fix**: Set `DB_PORT=5433` environment variable

### "Port 9090 was already in use"
- **Cause**: Another instance is already running
- **Fix**: Kill the process or use a different port:
  ```bash
  # Option 1: Kill existing process
  lsof -ti:9090 | xargs kill -9

  # Option 2: Use different port
  SERVER_PORT=9091 DB_PORT=5433 ./gradlew bootRun
  ```

### "permission denied to create role"
- **Cause**: Migration script tried to create a user (fixed in code)
- **Fix**: Already fixed in `V1__base_schema.sql`

## API Endpoints

Once running, access:
- **API**: http://localhost:9090
- **Swagger UI**: http://localhost:9090/swagger-ui.html
- **Health**: http://localhost:9090/actuator/health
- **Keycloak**: http://localhost:8085

## Port Configuration

The application uses the following ports:
- **Core API**: 9090 (configurable via `SERVER_PORT` env var)
- **PostgreSQL**: 5433 (Docker)
- **Keycloak**: 8085 (Docker)

## Database Migrations

Flyway migrations are applied automatically on startup.

This guide deliberately does **not** enumerate them: the schema head is a gated number and is
recorded once, in `README.md` ("Database schema version"), which `scripts/check-doc-metrics.sh`
checks against `docs/metrics.json` on every build. Restating it here would create a second copy
that nothing checks — the exact drift that left this file claiming "V1..V4".

The migration files themselves are in `core-java/src/main/resources/db/migration/`.
