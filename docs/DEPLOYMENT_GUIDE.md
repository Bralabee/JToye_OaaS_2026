# J'Toye OaaS - Deployment Guide

**Version:** 2.0 (Phase 2.1 Complete)
**Date:** 2025-12-30
**Status:** Production Ready

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development](#local-development)
4. [Docker Deployment](#docker-deployment)
5. [Kubernetes Deployment](#kubernetes-deployment)
6. [CI/CD Pipeline](#cicd-pipeline)
7. [Post-Deployment](#post-deployment)
8. [Troubleshooting](#troubleshooting)

---

## 1. Overview

J'Toye OaaS uses a modern containerized architecture with three main services:

| Service | Technology | Port | Image Size | Startup Time |
|---------|-----------|------|------------|--------------|
| **core-java** | Spring Boot 3 + JDK 21 | 9090 | ~200MB | ~30s |
| **edge-go** | Go 1.22 (static binary) | 8080 | ~15MB | ~1s |
| **frontend** | Next.js 14 (standalone) | 3000 | ~150MB | ~5s |

**Infrastructure:**
- PostgreSQL 15 (primary + replicas)
- Keycloak 24 (OIDC provider)
- Redis 7 (cache + sessions)
- RabbitMQ 3.12 (message queue)

---

## 2. Prerequisites

### 2.1 Development

```bash
# Required
- Java 21 (Eclipse Temurin recommended)
- Node.js 20+ (with npm)
- Go 1.22+
- Docker 24+ with Docker Compose
- Git

# Optional (for K8s)
- kubectl 1.28+
- Helm 3+
- k9s (Kubernetes TUI)
```

### 2.2 Production

```bash
# Infrastructure
- Kubernetes 1.28+ cluster
- Ingress controller (nginx-ingress)
- Cert-manager (for TLS certificates)
- Container registry (GitHub Container Registry)

# Secrets Management (choose one)
- Sealed Secrets
- External Secrets Operator + AWS Secrets Manager
- HashiCorp Vault

# Monitoring (recommended)
- Prometheus + Grafana
- Loki (log aggregation)
- Jaeger (distributed tracing)
```

---

## 3. Local Development

### 3.1 Quick Start (without Docker)

```bash
# 1. Start infrastructure
cd infra && docker-compose up -d

# 2. Start backend
./run-app.sh

# 3. Start frontend (in new terminal)
cd frontend
npm install
npm run dev

# 4. Access
# - Frontend: http://localhost:3000
# - Backend: http://localhost:9090
# - Swagger: http://localhost:9090/swagger-ui.html
# - Keycloak: http://localhost:8085 (admin/admin123)
```

### 3.2 Full Stack with Docker Compose

```bash
# Build all images
./scripts/build-images.sh

# Start all services
docker-compose -f docker-compose.full-stack.yml up -d

# View logs
docker-compose -f docker-compose.full-stack.yml logs -f

# Stop services
docker-compose -f docker-compose.full-stack.yml down
```

### 3.3 Environment Variables

```bash
# Backend (core-java)
export SPRING_PROFILES_ACTIVE=dev
export DB_HOST=localhost
export DB_PORT=5433
export DB_USER=jtoye
export DB_PASSWORD=secret
export KC_ISSUER_URI=http://localhost:8085/realms/jtoye-dev

# Frontend
export NEXT_PUBLIC_API_URL=http://localhost:9090
export NEXTAUTH_URL=http://localhost:3000
export NEXTAUTH_SECRET=$(openssl rand -base64 32)
export KEYCLOAK_ISSUER=http://localhost:8085/realms/jtoye-dev
```

---

## 4. Docker Deployment

### 4.1 Build Images

```bash
# Build all services
./scripts/build-images.sh v1.0.0

# Or build individually
docker build -t jtoye/core-java:v1.0.0 -f core-java/Dockerfile .
docker build -t jtoye/edge-go:v1.0.0 -f edge-go/Dockerfile ./edge-go
docker build -t jtoye/frontend:v1.0.0 -f frontend/Dockerfile ./frontend
```

### 4.2 Push to Registry

```bash
# Login to GitHub Container Registry
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin

# Tag images
docker tag jtoye/core-java:v1.0.0 ghcr.io/jtoye/core-java:v1.0.0
docker tag jtoye/edge-go:v1.0.0 ghcr.io/jtoye/edge-go:v1.0.0
docker tag jtoye/frontend:v1.0.0 ghcr.io/jtoye/frontend:v1.0.0

# Push
docker push ghcr.io/jtoye/core-java:v1.0.0
docker push ghcr.io/jtoye/edge-go:v1.0.0
docker push ghcr.io/jtoye/frontend:v1.0.0
```

### 4.3 Run Smoke Tests

```bash
# Start services
docker-compose -f docker-compose.full-stack.yml up -d

# Wait for services to be ready
sleep 60

# Run smoke tests
./scripts/smoke-test.sh http://localhost:9090

# Expected output:
# ✓ All smoke tests passed! (8/8)
```

---

## 5. Kubernetes Deployment

### 5.1 Prerequisites

```bash
# Verify cluster access
kubectl cluster-info
kubectl get nodes

# Create namespaces
kubectl apply -f k8s/base/namespace.yaml

# Verify namespaces
kubectl get namespaces | grep jtoye
```

### 5.2 Create Secrets

**Option A: Manual (Dev/Staging only)**

```bash
# PostgreSQL credentials
kubectl create secret generic postgres-credentials \
  --from-literal=host=postgresql-primary.jtoye-infrastructure.svc.cluster.local \
  --from-literal=port=5432 \
  --from-literal=database=jtoye \
  --from-literal=username=jtoye \
  --from-literal=password='REPLACE_WITH_SECURE_PASSWORD' \
  -n jtoye-production

# NextAuth secret
kubectl create secret generic nextauth-secret \
  --from-literal=secret=$(openssl rand -base64 32) \
  -n jtoye-production

# Keycloak credentials
kubectl create secret generic keycloak-credentials \
  --from-literal=admin-username=admin \
  --from-literal=admin-password='REPLACE_WITH_SECURE_PASSWORD' \
  --from-literal=frontend-client-secret='REPLACE_FROM_KEYCLOAK' \
  -n jtoye-production
```

**Option B: Sealed Secrets (Production)**

```bash
# Install Sealed Secrets controller
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.24.0/controller.yaml

# Create sealed secret
kubectl create secret generic postgres-credentials \
  --from-literal=password='YOUR_PASSWORD' \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > k8s/production/sealed-postgres-secret.yaml

# Apply sealed secret
kubectl apply -f k8s/production/sealed-postgres-secret.yaml -n jtoye-production
```

### 5.3 Deploy ConfigMap

```bash
# Edit k8s/base/configmap.yaml with production values
vim k8s/base/configmap.yaml

# Apply
kubectl apply -f k8s/base/configmap.yaml -n jtoye-production
```

### 5.4 Deploy Services

**Option A: Using Script**

```bash
# Deploy all services to production
./scripts/deploy.sh production all

# Or deploy individual service
./scripts/deploy.sh production core-java
```

**Option B: Manual**

```bash
# Deploy core-java
kubectl apply -f k8s/base/core-java-deployment.yaml -n jtoye-production
kubectl rollout status deployment/core-java -n jtoye-production

# Deploy edge-go
kubectl apply -f k8s/base/edge-go-deployment.yaml -n jtoye-production
kubectl rollout status deployment/edge-go -n jtoye-production

# Deploy frontend
kubectl apply -f k8s/base/frontend-deployment.yaml -n jtoye-production
kubectl rollout status deployment/frontend -n jtoye-production

# Deploy ingress
kubectl apply -f k8s/base/ingress.yaml -n jtoye-production
```

### 5.5 Verify Deployment

```bash
# Check pods
kubectl get pods -n jtoye-production

# Expected output:
# NAME                        READY   STATUS    RESTARTS   AGE
# core-java-xxxxx            1/1     Running   0          2m
# core-java-yyyyy            1/1     Running   0          2m
# core-java-zzzzz            1/1     Running   0          2m
# edge-go-xxxxx              1/1     Running   0          1m
# frontend-xxxxx             1/1     Running   0          1m

# Check services
kubectl get svc -n jtoye-production

# Check ingress
kubectl get ingress -n jtoye-production

# Check HPA (autoscaling)
kubectl get hpa -n jtoye-production
```

### 5.6 Monitor Logs

```bash
# Tail logs for specific service
kubectl logs -f deployment/core-java -n jtoye-production

# Tail logs with label selector
kubectl logs -f -l app=core-java -n jtoye-production --tail=100

# View logs from all pods
kubectl logs -f -l component=backend -n jtoye-production
```

---

## 6. CI/CD Pipeline

### 6.1 GitHub Actions Setup

**Required Secrets (GitHub Repository Settings):**

```bash
# Kubernetes access
KUBE_CONFIG_STAGING     # Base64-encoded kubeconfig for staging
KUBE_CONFIG_PRODUCTION  # Base64-encoded kubeconfig for production

# Container registry
GITHUB_TOKEN            # Automatically provided by GitHub

# Security scanning
SNYK_TOKEN              # From snyk.io (optional)

# Notifications
SLACK_WEBHOOK_URL       # Slack webhook for deployment notifications
```

**How to create kubeconfig secret:**

```bash
# Encode kubeconfig
cat ~/.kube/config | base64 -w 0

# Add to GitHub Secrets:
# Settings → Secrets and variables → Actions → New repository secret
# Name: KUBE_CONFIG_PRODUCTION
# Value: <paste base64 string>
```

### 6.2 Pipeline Workflow

```
Push to branch → Tests → Security Scan → Build Images → Deploy

Branches:
- main       → Production deployment
- develop    → Staging deployment
- phase-*    → Tests only (no deployment)
- PR         → Tests + security scan
```

### 6.3 Triggering Deployments

```bash
# Automatic deployment on push to main
git push origin main

# Or create a release
git tag v1.0.0
git push origin v1.0.0

# Manual deployment via GitHub UI:
# Actions → CI/CD Pipeline → Run workflow
```

### 6.4 Rollback Procedure

```bash
# View deployment history
kubectl rollout history deployment/core-java -n jtoye-production

# Rollback to previous version
kubectl rollout undo deployment/core-java -n jtoye-production

# Rollback to specific revision
kubectl rollout undo deployment/core-java --to-revision=3 -n jtoye-production

# Verify rollback
kubectl rollout status deployment/core-java -n jtoye-production
```

---

## 7. Post-Deployment

### 7.1 Health Checks

```bash
# Application health
curl https://api.jtoye.co.uk/actuator/health

# Expected output:
# {"status":"UP","groups":["liveness","readiness"]}

# Detailed health (requires authentication)
curl https://api.jtoye.co.uk/actuator/health/readiness
```

### 7.2 Smoke Tests

```bash
# Run smoke tests against production
./scripts/smoke-test.sh https://api.jtoye.co.uk

# Expected: 8/8 tests passing
```

### 7.3 Monitoring Setup

```bash
# Check Prometheus targets
kubectl port-forward -n monitoring svc/prometheus 9090:9090
# Open: http://localhost:9090/targets

# Access Grafana dashboards
kubectl port-forward -n monitoring svc/grafana 3000:3000
# Open: http://localhost:3000
# Login: admin / <password from secret>

# View logs in Loki
kubectl port-forward -n monitoring svc/loki 3100:3100
```

### 7.4 Performance Validation

```bash
# Load testing with k6 (from system design doc)
k6 run scripts/load-test.js

# Expected:
# - p95 latency < 200ms
# - p99 latency < 500ms
# - Error rate < 0.1%
# - Throughput: 5,000 req/sec
```

---

## 8. Troubleshooting

### 8.1 Common Issues

**Issue: Pod stuck in `CrashLoopBackOff`**

```bash
# Check logs
kubectl logs deployment/core-java -n jtoye-production

# Describe pod for events
kubectl describe pod <pod-name> -n jtoye-production

# Common causes:
# - Database connection failure (check secrets)
# - Keycloak not accessible (check configmap)
# - Out of memory (increase resources)
```

**Issue: 401 Unauthorized on all requests**

```bash
# Verify Keycloak is accessible
kubectl exec -it deployment/core-java -n jtoye-production -- \
  curl -f http://keycloak:8080/realms/jtoye-prod

# Check JWT issuer configuration
kubectl get configmap app-config -n jtoye-production -o yaml

# Verify secrets exist
kubectl get secrets -n jtoye-production
```

**Issue: Database connection timeout**

```bash
# Test database connectivity from pod
kubectl exec -it deployment/core-java -n jtoye-production -- \
  nc -zv postgresql-primary 5432

# Check database credentials
kubectl get secret postgres-credentials -n jtoye-production -o yaml

# Verify RLS policies
docker exec jtoye-postgres psql -U jtoye -d jtoye -c "SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname='public';"
```

### 8.2 Debugging Commands

```bash
# Get pod shell
kubectl exec -it deployment/core-java -n jtoye-production -- /bin/sh

# View environment variables
kubectl exec deployment/core-java -n jtoye-production -- env | grep -E 'DB_|KC_|REDIS'

# Test internal service connectivity
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl http://core-java:9090/actuator/health

# Check resource usage
kubectl top pods -n jtoye-production
kubectl top nodes
```

### 8.3 Emergency Procedures

**Complete service restart:**

```bash
# Restart all pods
kubectl rollout restart deployment/core-java -n jtoye-production
kubectl rollout restart deployment/edge-go -n jtoye-production
kubectl rollout restart deployment/frontend -n jtoye-production

# Wait for completion
kubectl rollout status deployment/core-java -n jtoye-production
```

**Scale down for maintenance:**

```bash
# Scale to 0 replicas
kubectl scale deployment/core-java --replicas=0 -n jtoye-production

# Scale back up
kubectl scale deployment/core-java --replicas=3 -n jtoye-production
```

---

## 9. Security Checklist

### Pre-Production

- [ ] All secrets stored in sealed secrets (not plain YAML)
- [ ] TLS certificates configured (cert-manager + Let's Encrypt)
- [ ] Network policies applied (pod-to-pod isolation)
- [ ] Security scanning passed (Trivy, Snyk)
- [ ] RBAC configured (least privilege)
- [ ] Pod security policies enforced (non-root, read-only filesystem)
- [ ] Ingress WAF rules configured
- [ ] Rate limiting configured (nginx-ingress)

### Post-Production

- [ ] Monitoring alerts configured (PagerDuty, Slack)
- [ ] Log aggregation working (Loki)
- [ ] Backup strategy validated (database PITR)
- [ ] Disaster recovery tested (RTO < 30min)
- [ ] Security audit completed
- [ ] Penetration testing passed

---

## 10. Contact & Support

**Documentation:**
- System Design: `docs/architecture/SYSTEM_DESIGN_V2.md`
- Security Audit: `docs/SECURITY_AUDIT_REPORT.md`
- API Docs: https://api.jtoye.co.uk/swagger-ui.html

**Support:**
- Email: devops@jtoye.co.uk
- Slack: #jtoye-oaas-support
- On-call: PagerDuty integration

**Repository:**
- GitHub: https://github.com/jtoye/oaas
- Issues: https://github.com/jtoye/oaas/issues

---

**Last Updated:** 2025-12-30
**Version:** 2.0 (Phase 2.1 Complete)
**Next Review:** Q1 2026
