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

### Local Setup
1.  **Start Infrastructure**:
    ```bash
    cd infra && docker-compose up -d
    ```
2.  **Run Backend (Core)**:
    ```bash
    ./run-app.sh
    ```
3.  **Run Frontend**:
    ```bash
    cd frontend && npm install && npm run dev
    ```
4.  **Access**:
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
*   **Port Conflicts**: Ensure ports 9090 (Core), 8080 (Edge), 3000 (Frontend), 5433 (Postgres), and 8085 (Keycloak) are free.
*   **Database Locks**: If Gradle fails due to permission issues, use `./gradlew -PbuildDir=build-local`.
*   **JWT Issues**: Verify Keycloak is running and that the `KC_ISSUER_URI` in `.env` matches your local environment.
