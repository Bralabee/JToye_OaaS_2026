# Environment Strategy

**Multi-Environment Deployment Strategy for J'Toye OaaS**

---

## Overview

Now that the project is environment-aware with proper configuration management, this document outlines how environments differ and how to promote code safely from development → staging → production.

---

## Table of Contents

1. [Environment Comparison](#environment-comparison)
2. [Configuration Management](#configuration-management)
3. [Environment-Specific Settings](#environment-specific-settings)
4. [Security Implications](#security-implications)
5. [Deployment Strategy](#deployment-strategy)
6. [Validation & Testing](#validation--testing)
7. [Troubleshooting](#troubleshooting)

---

## Environment Comparison

### Development (Local/Docker)

**Purpose:** Individual developer workstations

**Characteristics:**
- ✅ Runs on localhost
- ✅ Uses `.env` files (not committed)
- ✅ Hardcoded credentials acceptable
- ✅ Swagger UI enabled
- ✅ Detailed error messages
- ✅ Verbose logging (DEBUG/INFO)
- ✅ Single-node setup
- ⚠️ No TLS/HTTPS
- ⚠️ Development realm (`jtoye-dev`)

**Infrastructure:**
```
localhost:3000    → Frontend (Next.js dev server)
localhost:9090    → Core API (Gradle bootRun)
localhost:8089    → Edge Gateway (go run)
localhost:5433    → PostgreSQL (Docker)
localhost:8085    → Keycloak (Docker)
localhost:6379    → Redis (Docker)
```

**Configuration Source:**
- Frontend: `frontend/.env.local`
- Core: `core-java/.env` + `application.yml` + `application-local.yml`
- Edge: `edge-go/.env`
- Infrastructure: `infra/.env`

---

### Staging

**Purpose:** Pre-production testing, QA validation

**Characteristics:**
- ✅ Production-like environment
- ✅ Uses Kubernetes Secrets/ConfigMaps
- ✅ Real DNS names (`staging.jtoye.co.uk`)
- ✅ TLS certificates (Let's Encrypt)
- ✅ Production realm (`jtoye-prod`) but separate data
- ⚠️ Swagger UI enabled (protected)
- ⚠️ Detailed errors (for debugging)
- ✅ INFO-level logging
- ✅ Multi-node with reduced replicas (1-3)
- ✅ Smaller resource limits

**Infrastructure:**
```
https://app-staging.jtoye.co.uk     → Frontend
https://api-staging.jtoye.co.uk     → Core API
https://edge-staging.jtoye.co.uk    → Edge Gateway
https://auth-staging.jtoye.co.uk    → Keycloak

Kubernetes Cluster (staging namespace)
- PostgreSQL: Cloud DB (staging instance)
- Redis: Managed Redis or cluster
- RabbitMQ: Managed or cluster
```

**Configuration Source:**
- Kubernetes ConfigMaps (`k8s/staging/configmap.yaml`)
- Kubernetes Secrets (`k8s/staging/sealed-secrets.yaml`)
- Environment variables from K8s manifests

---

### Production

**Purpose:** Live customer-facing environment

**Characteristics:**
- ✅ Maximum security and reliability
- ✅ Kubernetes Secrets/ConfigMaps ONLY
- ✅ Real DNS (`app.jtoye.co.uk`, `api.jtoye.co.uk`)
- ✅ TLS with proper certificates
- ✅ Production Keycloak realm
- ❌ Swagger UI disabled
- ❌ Minimal error messages (security)
- ✅ WARN-level logging (errors only)
- ✅ Multi-node HA (3-10 replicas)
- ✅ Full resource limits & HPA
- ✅ PodDisruptionBudgets
- ✅ Network policies
- ✅ Monitoring & alerting

**Infrastructure:**
```
https://app.jtoye.co.uk      → Frontend (CDN)
https://api.jtoye.co.uk      → Core API (Ingress → K8s)
https://edge.jtoye.co.uk     → Edge Gateway
https://auth.jtoye.co.uk     → Keycloak

Kubernetes Cluster (production namespace)
- PostgreSQL: Cloud DB with replicas (AWS RDS, GCP CloudSQL)
- Redis: Managed Redis Cluster (ElastiCache, Memorystore)
- RabbitMQ: Managed RabbitMQ (CloudAMQP)
```

**Configuration Source:**
- Kubernetes ConfigMaps (`k8s/production/configmap.yaml`)
- Sealed Secrets (`k8s/production/sealed-secrets.yaml`)
- External Secrets Operator (AWS Secrets Manager, Vault)

---

## Configuration Management

### 12-Factor App Principles

✅ **I. Codebase** - One codebase, many deploys
✅ **II. Dependencies** - Explicitly declared (Gradle, npm, go.mod)
✅ **III. Config** - **Stored in environment** (this doc!)
✅ **IV. Backing Services** - Attached resources
✅ **XI. Logs** - Treat logs as event streams

### Configuration Hierarchy

**Priority (highest to lowest):**

1. **Environment Variables** (runtime)
2. **Kubernetes ConfigMap/Secrets** (K8s only)
3. **Spring Profile-Specific YAML** (`application-{profile}.yml`)
4. **Default YAML** (`application.yml`)

**Example (Core API Database URL):**

```yaml
# 1. application.yml (default)
spring.datasource.url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5433}/${DB_NAME:jtoye}

# 2. Environment variable (overrides)
DB_HOST=prod-db.example.com
DB_PORT=5432

# Final: jdbc:postgresql://prod-db.example.com:5432/jtoye
```

### Environment Variables by Service

#### **Core Java (Spring Boot)**

| Variable | Dev | Staging | Production | Notes |
|----------|-----|---------|------------|-------|
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` | `prod` | Activates profile-specific config |
| `DB_HOST` | `localhost` | `postgres.staging` | Cloud DB endpoint | Database host |
| `DB_PORT` | `5433` | `5432` | `5432` | Database port |
| `DB_USER` | `jtoye` | `jtoye_app` | `jtoye_app` | DB user |
| `DB_PASSWORD` | `secret` | K8s Secret | K8s Secret | **Never hardcode!** |
| `KC_ISSUER_URI` | `http://localhost:8085/realms/jtoye-dev` | `https://auth-staging.jtoye.co.uk/realms/jtoye-prod` | `https://auth.jtoye.co.uk/realms/jtoye-prod` | Keycloak issuer |
| `LOG_LEVEL` | `INFO` | `INFO` | `WARN` | Application logging |
| `SWAGGER_ENABLED` | `true` | `true` (protected) | `false` | Swagger UI |

#### **Frontend (Next.js)**

| Variable | Dev | Staging | Production | Notes |
|----------|-----|---------|------------|-------|
| `NODE_ENV` | `development` | `production` | `production` | Node environment |
| `NEXT_PUBLIC_API_URL` | `http://localhost:9090` | `https://api-staging.jtoye.co.uk` | `https://api.jtoye.co.uk` | Backend API |
| `NEXTAUTH_URL` | `http://localhost:3000` | `https://app-staging.jtoye.co.uk` | `https://app.jtoye.co.uk` | NextAuth base URL |
| `NEXTAUTH_SECRET` | Dev random | K8s Secret | K8s Secret | **32+ chars random** |
| `KEYCLOAK_ISSUER` | `http://localhost:8085/realms/jtoye-dev` | `https://auth-staging.jtoye.co.uk/realms/jtoye-prod` | `https://auth.jtoye.co.uk/realms/jtoye-prod` | Must match token issuer |
| `KEYCLOAK_CLIENT_SECRET` | `core-api-secret-2026` | K8s Secret | K8s Secret | From Keycloak |

#### **Edge Go (API Gateway)**

| Variable | Dev | Staging | Production | Notes |
|----------|-----|---------|------------|-------|
| `CORE_API_URL` | `http://localhost:9090` | `http://core-java:9090` | `http://core-java:9090` | Internal K8s service |
| `KC_ISSUER_URI` | `http://localhost:8085/realms/jtoye-dev` | `https://auth-staging.jtoye.co.uk/realms/jtoye-prod` | `https://auth.jtoye.co.uk/realms/jtoye-prod` | JWT validation |
| `PORT` | `8080` | `8080` | `8080` | Internal port |

---

## Environment-Specific Settings

### Development

**application-local.yml:**
```yaml
# Already exists, no changes needed
spring:
  jpa:
    show-sql: true
logging:
  level:
    uk.jtoye: DEBUG
```

**Frontend `.env.local`:**
```bash
# Local development
NEXT_PUBLIC_API_URL=http://localhost:9090
KEYCLOAK_ISSUER=http://localhost:8085/realms/jtoye-dev
```

### Staging

**Kubernetes ConfigMap (`k8s/staging/configmap.yaml`):**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: jtoye-staging
data:
  SPRING_PROFILES_ACTIVE: "prod"

  # Database
  DB_HOST: "postgres-staging.jtoye-infrastructure.svc.cluster.local"
  DB_PORT: "5432"
  DB_NAME: "jtoye_staging"

  # Keycloak
  KC_ISSUER_URI: "https://auth-staging.jtoye.co.uk/realms/jtoye-prod"

  # Logging
  LOG_LEVEL: "INFO"
  SQL_LOG_LEVEL: "WARN"

  # Features
  SWAGGER_ENABLED: "true"
```

**Kubernetes Secret (Sealed):**
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
  namespace: jtoye-staging
type: Opaque
data:
  DB_PASSWORD: <base64-encoded>
  KEYCLOAK_CLIENT_SECRET: <base64-encoded>
  NEXTAUTH_SECRET: <base64-encoded>
  REDIS_PASSWORD: <base64-encoded>
```

### Production

**Kubernetes ConfigMap (`k8s/production/configmap.yaml`):**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: jtoye-production
data:
  SPRING_PROFILES_ACTIVE: "prod"

  # Database (Cloud)
  DB_HOST: "jtoye-prod.abc123.eu-west-2.rds.amazonaws.com"
  DB_PORT: "5432"
  DB_NAME: "jtoye_production"

  # Keycloak
  KC_ISSUER_URI: "https://auth.jtoye.co.uk/realms/jtoye-prod"

  # Logging
  LOG_LEVEL: "WARN"
  SQL_LOG_LEVEL: "ERROR"
  SECURITY_LOG_LEVEL: "WARN"

  # Features
  SWAGGER_ENABLED: "false"

  # Performance
  DB_POOL_SIZE: "50"
  DB_POOL_MIN_IDLE: "10"
```

---

## Security Implications

### Secret Management

#### ❌ **NEVER Do This:**

```bash
# DON'T hardcode production secrets in code
DB_PASSWORD=productionSecretPassword123

# DON'T commit .env files to Git
git add .env.production

# DON'T use same secrets across environments
```

#### ✅ **DO This:**

**Development:**
- Use `.env` files (gitignored)
- Simple passwords acceptable (`secret`, `password123`)
- Document in `docs/CREDENTIALS.md`

**Staging:**
- Use Kubernetes Secrets
- Rotate quarterly
- Different from production

**Production:**
- Use **Sealed Secrets** or **External Secrets Operator**
- Rotate monthly
- Generated secrets (32+ characters)
- Store in **AWS Secrets Manager** / **HashiCorp Vault** / **GCP Secret Manager**

### Generating Production Secrets

```bash
# NEXTAUTH_SECRET (32 chars)
openssl rand -base64 32

# Database password (24 chars, alphanumeric)
openssl rand -base64 24 | tr -d /+= | cut -c1-24

# Keycloak client secret
# Generated in Keycloak admin console (Clients → core-api → Credentials)
```

### Secret Rotation Strategy

**Quarterly (Staging):**
1. Generate new secret
2. Update K8s Secret
3. Restart pods (rolling)
4. Verify no disruption
5. Document change

**Monthly (Production):**
1. Generate new secret
2. Update in AWS Secrets Manager / Vault
3. Update K8s ExternalSecret
4. Rolling restart pods
5. Monitor for errors
6. Notify team

---

## Deployment Strategy

### Environment Promotion Flow

```
Developer Workstation (Local)
        ↓
    Git Commit
        ↓
    GitHub (main branch)
        ↓
    CI/CD Pipeline
        ├─ Build & Test
        ├─ Security Scan
        └─ Build Docker Images
        ↓
    Dev Environment (Docker Compose)
        ↓ (Manual approval)
    Staging Environment (K8s)
        ↓ (Automated tests + Manual QA)
    Production Environment (K8s)
```

### Pre-Deployment Checklist

#### **To Staging:**

- [ ] All tests passing locally
- [ ] Code reviewed and approved
- [ ] Environment variables documented
- [ ] Database migrations tested
- [ ] Swagger API docs updated
- [ ] Feature flags configured

**Command:**
```bash
# 1. Tag release
git tag -a v0.8.0-rc1 -m "Release candidate for staging"
git push origin v0.8.0-rc1

# 2. Deploy to staging
kubectl config use-context staging
kubectl apply -k k8s/staging/

# 3. Verify deployment
kubectl -n jtoye-staging get pods
kubectl -n jtoye-staging logs -f deployment/core-java

# 4. Run smoke tests
./scripts/smoke-test.sh https://api-staging.jtoye.co.uk
```

#### **To Production:**

- [ ] Successfully tested in staging for 24+ hours
- [ ] No critical bugs reported
- [ ] Performance metrics acceptable
- [ ] Database backup completed
- [ ] Rollback plan documented
- [ ] Stakeholders notified
- [ ] Maintenance window scheduled (if needed)

**Command:**
```bash
# 1. Final tag
git tag -a v0.8.0 -m "Production release v0.8.0"
git push origin v0.8.0

# 2. Deploy to production
kubectl config use-context production
kubectl apply -k k8s/production/

# 3. Monitor deployment
kubectl -n jtoye-production rollout status deployment/core-java
kubectl -n jtoye-production get pods -w

# 4. Smoke test
./scripts/smoke-test.sh https://api.jtoye.co.uk

# 5. Monitor metrics
# - Check Grafana dashboards
# - Watch error rates
# - Monitor response times
```

### Rollback Procedure

**If deployment fails:**

```bash
# 1. Immediate rollback
kubectl -n jtoye-production rollout undo deployment/core-java

# 2. Verify rollback
kubectl -n jtoye-production rollout status deployment/core-java

# 3. Check pods are healthy
kubectl -n jtoye-production get pods

# 4. Investigate issue
kubectl -n jtoye-production logs deployment/core-java --previous

# 5. Notify team
# - Post in Slack/Teams
# - Update status page
# - Document issue
```

---

## Validation & Testing

### Environment-Specific Tests

#### **Development:**
```bash
# Unit tests
./gradlew :core-java:test

# Integration tests
./gradlew :core-java:integrationTest

# Manual API tests
curl http://localhost:9090/actuator/health
```

#### **Staging:**
```bash
# Automated smoke tests
./scripts/smoke-test.sh https://api-staging.jtoye.co.uk

# Load tests
cd infra/load-testing
./load-test.sh https://api-staging.jtoye.co.uk

# Security scan
trivy image ghcr.io/jtoye/core-java:v0.8.0-rc1

# Manual QA
# - Login as test users
# - Perform CRUD operations
# - Verify multi-tenancy
# - Test edge cases
```

#### **Production:**
```bash
# Pre-deployment checks
./scripts/smoke-test.sh https://api.jtoye.co.uk

# Post-deployment monitoring
# - Watch Grafana dashboards (5 minutes)
# - Check error logs
# - Verify key metrics:
#   * Response time < 200ms (p95)
#   * Error rate < 0.1%
#   * CPU < 70%
#   * Memory < 80%
```

### Health Check Endpoints

**All environments should respond:**

```bash
# Backend health
curl https://api.{env}.jtoye.co.uk/actuator/health
# Expected: {"status":"UP"}

# Frontend health
curl https://app.{env}.jtoye.co.uk/api/health
# Expected: 200 OK

# Database connectivity
curl https://api.{env}.jtoye.co.uk/actuator/health/db
# Expected: {"status":"UP"}
```

---

## Troubleshooting

### Common Issues by Environment

#### **Development: "Environment variable not set"**

**Symptom:**
```
Error: KEYCLOAK_CLIENT_SECRET is not defined
```

**Fix:**
```bash
# Verify .env.local exists
ls -la frontend/.env.local

# If missing, copy from template
cp frontend/.env.local.example frontend/.env.local

# Restart service
cd frontend && npm run dev
```

---

#### **Staging: "Issuer mismatch"**

**Symptom:**
```
JWT issuer mismatch: expected https://auth-staging.jtoye.co.uk/realms/jtoye-prod,
got http://localhost:8085/realms/jtoye-dev
```

**Fix:**
```bash
# Check ConfigMap
kubectl -n jtoye-staging get configmap app-config -o yaml | grep KC_ISSUER_URI

# Update if wrong
kubectl -n jtoye-staging edit configmap app-config

# Restart pods
kubectl -n jtoye-staging rollout restart deployment/core-java
```

---

#### **Production: "Secret not found"**

**Symptom:**
```
Pod status: CreateContainerConfigError
Error: couldn't find key DB_PASSWORD in Secret jtoye-production/app-secrets
```

**Fix:**
```bash
# Check if secret exists
kubectl -n jtoye-production get secret app-secrets

# Recreate from Sealed Secret
kubectl apply -f k8s/production/sealed-secrets.yaml

# Or create manually (emergency only!)
kubectl -n jtoye-production create secret generic app-secrets \
  --from-literal=DB_PASSWORD='xxx' \
  --from-literal=KEYCLOAK_CLIENT_SECRET='yyy' \
  --from-literal=NEXTAUTH_SECRET='zzz'
```

---

## Best Practices

### ✅ DO:

1. **Use environment variables for all configuration**
2. **Never hardcode secrets in code**
3. **Test configuration changes in staging first**
4. **Use Spring profiles** (`dev`, `prod`)
5. **Validate environment variables at startup**
6. **Document all environment variables**
7. **Rotate secrets regularly**
8. **Monitor configuration changes**
9. **Use Sealed Secrets for production**
10. **Keep development simple** (`.env` files OK)

### ❌ DON'T:

1. **Never commit `.env` files**
2. **Never use production secrets in staging**
3. **Never deploy to production without staging test**
4. **Never hardcode URLs/passwords**
5. **Never expose Swagger in production**
6. **Never use DEBUG logging in production**
7. **Never skip security scans**
8. **Never ignore health check failures**

---

## Summary

Now that your project is environment-aware:

**Development:**
- ✅ Uses `.env` files (simple, gitignored)
- ✅ Localhost everything
- ✅ Hardcoded dev credentials OK

**Staging:**
- ✅ Kubernetes ConfigMaps + Secrets
- ✅ Production-like setup
- ✅ Real DNS + TLS
- ✅ Different secrets from production

**Production:**
- ✅ Sealed Secrets / External Secrets
- ✅ Maximum security
- ✅ Monitoring & alerting
- ✅ Rollback capability

**Key Takeaways:**
1. **Configuration is external** - Not in code
2. **Secrets are environment-specific** - Never reuse across environments
3. **Promote code, not config** - Each environment has own config
4. **Validate early** - Fail fast if config missing
5. **Document everything** - This document!

---

**See Also:**
- [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md) - Local development setup
- [CONFIGURATION.md](CONFIGURATION.md) - Detailed configuration reference
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Kubernetes deployment
- [QUICK_START.md](QUICK_START.md) - Getting started

---

**Last Updated:** 2026-01-03
**Status:** ✅ Production Ready
