# J'Toye OaaS - Comprehensive User & Developer Guide

## 1. Project Overview
J'Toye OaaS (Operations as a Service) is a production-ready, multi-tenant SaaS platform designed for retail management in the UK. It utilizes a distributed architecture with strong data isolation via PostgreSQL Row-Level Security (RLS).

### Core Components
*   **core-java**: Spring Boot 3 service (System of Record). Handles business logic, data persistence, and RLS enforcement.
*   **edge-go**: Go 1.22 Gin service (System of Engagement). Provides rate limiting, circuit breaking, and batch synchronization.
*   **frontend**: Next.js 14 application with NextAuth.js and Keycloak integration.
*   **Keycloak**: OIDC provider for identity and access management.
*   **PostgreSQL**: Relational database with RLS policies for tenant isolation.

---

## 2. Quick Start

### Prerequisites
*   Java 21, Go 1.22+, Node.js 20+
*   Docker & Docker Compose

### Option A: Full-Stack Docker (Recommended)
**✅ No environment setup required!**

```bash
docker-compose -f docker-compose.full-stack.yml up
```

See [DOCKER_QUICK_START.md](DOCKER_QUICK_START.md) for details.

### Option B: Local Development
**⚠️ Requires environment configuration first!**

1.  **Configure Environment Variables**:
    ```bash
    # Copy templates (see docs/ENVIRONMENT_SETUP.md for details)
    cp frontend/.env.local.example frontend/.env.local
    cp core-java/.env.example core-java/.env
    cp edge-go/.env.example edge-go/.env
    cp infra/.env.example infra/.env
    ```

2.  **Start Infrastructure**:
    ```bash
    cd infra && docker-compose up -d
    ```
    *Note: This uses `infra/.env` file. Postgres will take a few seconds to initialize on first run.*

3.  **Run Backend (Core)**:
    ```bash
    ./run-app.sh
    ```
    *Note: This uses `core-java/.env` file.*

4.  **Run Frontend**:
    ```bash
    cd frontend && npm install && npm run dev
    ```
    *Note: This uses `frontend/.env.local` file.*

5.  **Access**:
    *   UI: `http://localhost:3000`
    *   API: `http://localhost:9090`
    *   Swagger: `http://localhost:9090/swagger-ui.html`
    *   Keycloak: `http://localhost:8085` (admin/admin123)

---

## 3. Architecture & Security

### Multi-Tenancy (RLS)
The platform uses a "Shared Database, Shared Schema" approach with **PostgreSQL Row Level Security**.
*   **Isolation**: Every table contains a `tenant_id` column.
*   **Enforcement**: The `TenantSetLocalAspect` (AOP) executes `SET LOCAL app.current_tenant_id = ?` within every transaction.
*   **JWT Integration**: Tenant ID is extracted from JWT claims (`tenant_id`, `tenantId`, or `tid`).

### Edge Service
The Go-based `edge-go` service acts as a protective layer:
*   **Rate Limiting**: Token-bucket implementation to prevent API abuse.
*   **Resilience**: Circuit breaker patterns for downstream calls to the Core API.
*   **Batching**: `/sync/batch` endpoint for optimized data synchronization.

---

## 4. API & Development

### Key Endpoints
*   `POST /shops`: Manage retail locations.
*   `POST /products`: Product catalog management.
*   `POST /orders`: Order lifecycle management via State Machine.
*   `POST /customers`: Customer profiles and allergen tracking.
*   `GET /health`: System health monitoring.

### Testing
*   **Core Java**: `./gradlew :core-java:test`
*   **Edge Go**: `cd edge-go && go test ./...`
*   **Manual**: See `docs/guides/TESTING_GUIDE.md` for JWT-based manual test flows.

---

## 5. Deployment
The project is fully containerized and includes:
*   **Multi-stage Dockerfiles** for all services.
*   **Kubernetes Manifests**: Including HPA, Ingress, and Secret management.
*   **CI/CD**: GitHub Actions workflows for automated building and testing.

For detailed production deployment steps, refer to `docs/DEPLOYMENT_GUIDE.md`.

---

## 6. Project History & Credentials
*   **Changelog**: See `docs/CHANGELOG.md` for version history.
*   **Credentials**: See `docs/CREDENTIALS.md` for development credentials and test users.

---

## 7. Troubleshooting

### Environment Configuration Issues

**Problem: "Environment variable not defined" or authentication fails**

*Solution:*
1. Verify `.env` files exist and are properly named:
   - `frontend/.env.local` (NOT `.env.local.txt`)
   - `core-java/.env`
   - `edge-go/.env`
   - `infra/.env`
2. Check files were copied from templates:
   ```bash
   # Linux/Mac
   cp frontend/.env.local.example frontend/.env.local

   # Windows
   copy frontend\.env.local.example frontend\.env.local
   ```
3. Restart services after creating/modifying .env files
4. See [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md) for comprehensive troubleshooting

**Problem: "Missing .env.local file" in frontend**

*Symptoms:* NextAuth errors, "Configuration" error, authentication redirects failing

*Solution:*
1. Create `frontend/.env.local` from `frontend/.env.local.example`
2. Verify `KEYCLOAK_CLIENT_SECRET` is set correctly
3. Ensure `KEYCLOAK_ISSUER` matches: `http://localhost:8085/realms/jtoye-dev`

### Infrastructure Issues

*   **Port Conflicts**: Ensure ports 9090 (Core), 8089 (Edge Docker), 3000 (Frontend), 5433 (Postgres), and 8085 (Keycloak) are free.
    ```bash
    # Check if port is in use (Linux/Mac)
    lsof -i :9090

    # Windows PowerShell
    Get-NetTCPConnection -LocalPort 9090
    ```

*   **Database Connection Refused**: Verify PostgreSQL container is running:
    ```bash
    docker ps | grep postgres
    # Should show jtoye-postgres container
    ```

*   **Database Locks**: If Gradle fails due to permission issues, use `./gradlew -PbuildDir=build-local`.

*   **JWT/Authentication Issues**:
    - Verify Keycloak is running: `curl http://localhost:8085/realms/jtoye-dev`
    - Check `KC_ISSUER_URI` matches in ALL config files:
      - `frontend/.env.local` → `KEYCLOAK_ISSUER`
      - `core-java/.env` → `KC_ISSUER_URI`
      - `edge-go/.env` → `KC_ISSUER_URI`
    - All should be: `http://localhost:8085/realms/jtoye-dev`

### Platform-Specific Issues

*   **Windows: File not found but exists**: Enable "File name extensions" in File Explorer to ensure file is named `.env.local` not `.env.local.txt`

*   **Linux/Mac: Permission denied**: Set correct permissions:
    ```bash
    chmod 600 frontend/.env.local core-java/.env edge-go/.env infra/.env
    ```

*   **Shell script won't run (Windows)**: Use Git Bash or WSL, or run commands manually from scripts

For detailed troubleshooting, see [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md).
