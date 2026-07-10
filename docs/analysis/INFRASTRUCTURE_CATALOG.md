# Infrastructure & Operations -- Complete Catalog

> **Generated**: 2026-04-01  
> **Scope**: infra/, k8s/, scripts/, .github/, backups/

---

## Docker Compose Files

### `docker-compose.full-stack.yml` (root)
Complete stack with zero configuration required:
- PostgreSQL 15 (5433), Keycloak 24 (8085), Redis 7 (6379), RabbitMQ 3.12 (5672/15672)
- Core Java (9090), Edge Go (8089), Frontend (3000)
- All services with health checks and dependency ordering
- Bridge network: `jtoye-network`

### `infra/docker-compose.yml`
Development infrastructure only (PostgreSQL + Keycloak) for local development.

### `infra/docker-compose.hostnet.yml`
Alternative Keycloak config using host networking (port 8081) for systems with bridge issues.

### `infra/monitoring/docker-compose.monitoring.yml`
Observability stack: Prometheus (9091), Grafana (3001), PostgreSQL Exporter (9187).

---

## Database Initialization

### `infra/db/init/00-create-db.sql`
- Creates `jtoye` and `keycloak` databases
- Creates roles: `jtoye` (owner), `jtoye_app` (application user, non-superuser)
- Grants permissions: CONNECT, USAGE, ALL on tables/sequences
- Installs `uuid-ossp` extension

---

## Keycloak

### `infra/keycloak/realm-export.json`
Pre-configured realm (`jtoye-dev`) with:
- Clients: `core-api` (secret: `core-api-secret-2026`), `frontend`
- Groups: `tenant-a`, `tenant-b` with `tenant_id` attributes (UUIDs)
- Users: `tenant-a-user`, `tenant-b-user`, `admin-user` (password: `password123`)
- Protocol mappers: `tenant_id` claim injected into JWT from group attributes

---

## Monitoring

### Prometheus (`infra/monitoring/prometheus/`)
- **prometheus.yml**: 15s scrape interval, targets: core-java:9090, edge-go:8080, postgres-exporter:9187
- **alerts.yml**: 7 alert rules:
  - Critical: HighErrorRate (>5% for 5m), ServiceDown, DatabaseDown
  - Warning: HighResponseTime (>1s p95 for 5m), HighMemoryUsage (>85%), FrequentGC
  - Info: NoOrdersCreated (24h)

### Grafana (`infra/monitoring/grafana/provisioning/`)
- Pre-configured Prometheus datasource
- Dashboard provisioning directory

---

## Kubernetes (`k8s/`)

### Base Resources (`k8s/base/`)

| File | Resources |
|------|-----------|
| `namespace.yaml` | 3 namespaces: jtoye-production, jtoye-staging, jtoye-dev |
| `configmap.yaml` | App config: URLs, hosts, log levels |
| `secrets-template.yaml.example` | Reference-only Secret shapes — NOT a kustomize resource since #100; secrets are created out-of-band (SealedSecrets / kubectl) |
| `core-java-deployment.yaml` | Deployment (3 replicas, 500m/512Mi -> 1000m/1Gi) + Service + HPA (3-10) + PDB (min 2) |
| `edge-go-deployment.yaml` | Deployment (5 replicas, 100m/64Mi -> 500m/256Mi) + Service + HPA (5-20) + PDB (min 3) |
| `frontend-deployment.yaml` | Deployment (3 replicas, 200m/256Mi -> 500m/512Mi) + Service + HPA (3-10) + PDB (min 2) |
| `ingress.yaml` | NGINX Ingress with TLS, rate limiting, security headers |
| `kustomization.yaml` | Base kustomization including all resources |

### Deployment Features
- Pod anti-affinity (prefer different nodes)
- Non-root security contexts
- Startup probes (core-java: 5-minute max)
- Liveness + readiness probes
- Resource requests and limits

### Ingress Configuration
- Hosts: `api.jtoye.co.uk`, `app.jtoye.co.uk`, `auth.jtoye.co.uk`
- TLS: cert-manager + Let's Encrypt
- Rate limiting: 100 RPS per IP, 5x burst, 50 concurrent connections
- Security headers: HSTS, X-Frame-Options (DENY), X-Content-Type-Options (nosniff), CSP, Referrer-Policy
- CORS: Enabled for app.jtoye.co.uk
- Timeouts: 60s (connect, send, read)

### Environment Overlays
- `k8s/staging/` -- Staging config patches
- `k8s/production/` -- Production config patches, pinned image tags

---

## Scripts (`scripts/`)

| Script | Purpose |
|--------|---------|
| `start-dev.sh` | Start full dev environment (infra + backend + frontend) |
| `stop-dev.sh` | Stop all development services |
| `run-app.sh` | Run Spring Boot application |
| `build-images.sh` | Build Docker images locally (all 3 services) |
| `deploy.sh` | K8s deployment: `./deploy.sh [env] [service]` |
| `smoke-test.sh` | 8 validation tests (health, actuator, swagger, CORS, security) |
| `verify-env.sh` | 8 environment verification tests (health, DB, Flyway, RLS) |
| `fix-bridge-network.sh` | Docker iptables networking fix |

---

## CI/CD (`.github/workflows/ci-cd.yaml`)

### Pipeline Stages

```
Push/PR  -->  Test  -->  Security Scan  -->  Build & Push  -->  Deploy
```

1. **Test**: Java (Gradle), Go, Node tests with PostgreSQL service container
2. **Security Scan**: Trivy filesystem scan + Snyk
3. **Build & Push**: Docker build matrix (3 services), multi-platform (amd64/arm64), GHCR push, Trivy image scan
4. **Deploy Staging**: On `develop` push, kubectl rollout, smoke tests, auto-rollback on failure
5. **Deploy Production**: On `main`/release, sequential deployment, health checks, Slack notifications, auto-rollback

---

## Backups (`backups/`)

- 70 automated PostgreSQL backups (Dec 2025 - Mar 2026)
- Format: `jtoye_jtoye_YYYYMMDD_HHMMSS.sql.gz`
- Schedule: Daily at 2 AM
- Retention: 30 days
- Script: `infra/backups/backup.sh`

---

## Secrets Management

- `infra/secrets/.env.template` -- Production secrets template
- `infra/secrets/generate-secrets.sh` -- Generates secure random values
- K8s options: Manual, Sealed Secrets, External Secrets Operator
