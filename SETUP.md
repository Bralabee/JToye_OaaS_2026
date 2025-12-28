# JToye OaaS 2026 - Setup Instructions

## Prerequisites
- Java 21
- Docker & Docker Compose
- Gradle (wrapper included)

## Database Setup

The application uses PostgreSQL running in Docker on port **5433** (not the default 5432).

### Start the database:
```bash
cd infra
docker-compose up -d postgres
```

### Verify database is running:
```bash
docker ps | grep jtoye-postgres
```

## Running the Application

### Option 1: Using the run script (Recommended)
```bash
./run-app.sh
```

### Option 2: Using Gradle directly
```bash
cd core-java
DB_PORT=5433 ../gradlew bootRun
```

### Option 3: IntelliJ IDEA Run Configuration

1. Open `Run` → `Edit Configurations...`
2. Select or create `CoreApplication` configuration
3. Add **Environment variables**:
   ```
   DB_PORT=5433
   ```
4. Click `Apply` and `OK`
5. Run the application

## Configuration

The application uses these database settings (from `application.yml`):

- **Host**: `localhost` (override with `DB_HOST`)
- **Port**: `5432` (override with `DB_PORT=5433` ⚠️ **REQUIRED**)
- **Database**: `jtoye` (override with `DB_NAME`)
- **Username**: `jtoye` (override with `DB_USER`)
- **Password**: `secret` (override with `DB_PASSWORD`)

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

Flyway migrations are applied automatically on startup. Current migrations:
- V1: Base schema (tables, types)
- V2: RLS policies
- V3: Unique constraints
- V4: Envers audit tables
