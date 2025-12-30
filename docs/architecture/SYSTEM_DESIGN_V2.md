# J'Toye OaaS - System Design V2 (Target: 10/10)

**Document Version:** 2.0
**Date:** 2025-12-30
**Status:** Design Phase
**Target:** Production-ready multi-tenant SaaS platform for UK retail

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Deployment Architecture](#2-deployment-architecture)
3. [Scalability & High Availability](#3-scalability--high-availability)
4. [Observability & Monitoring](#4-observability--monitoring)
5. [Data Architecture & Consistency](#5-data-architecture--consistency)
6. [Security Architecture](#6-security-architecture)
7. [Disaster Recovery & Business Continuity](#7-disaster-recovery--business-continuity)
8. [Performance Targets & SLAs](#8-performance-targets--slas)

---

## 1. Architecture Overview

### 1.1 Current Architecture (Phase 1)

```
┌─────────────────────────────────────────────────────────────────┐
│                        INTERNET                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │   Load Balancer   │ (Missing in Phase 1)
                    │   (nginx/ALB)     │
                    └─────────┬─────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
    ┌────▼─────┐      ┌──────▼──────┐      ┌─────▼──────┐
    │ frontend │      │  edge-go    │      │ core-java  │
    │ Next.js  │      │  (Gin)      │      │ (Spring)   │
    │  :3000   │      │  :8080      │      │  :9090     │
    └────┬─────┘      └──────┬──────┘      └─────┬──────┘
         │                   │                    │
         │                   └────────┬───────────┘
         │                            │
         │                   ┌────────▼──────────┐
         └──────────────────►│   Keycloak OIDC   │
                             │     :8085         │
                             └───────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                   │
            ┌───────▼────────┐              ┌──────────▼──────────┐
            │  PostgreSQL 15  │              │  (Future)           │
            │   + RLS         │              │  Message Queue      │
            │   :5433         │              │  Redis Cache        │
            └─────────────────┘              └─────────────────────┘
```

### 1.2 Target Architecture (Phase 2)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              INTERNET                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────▼────────────────┐
                    │   AWS CloudFront / Cloudflare  │
                    │   (CDN + DDoS Protection)      │
                    └───────────────┬────────────────┘
                                    │
                    ┌───────────────▼────────────────┐
                    │   Application Load Balancer    │
                    │   (ALB with WAF)               │
                    │   - Rate limiting              │
                    │   - SSL termination            │
                    └───────────────┬────────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
    ┌─────────▼────────┐   ┌───────▼──────┐   ┌─────────▼──────────┐
    │   frontend       │   │  edge-go     │   │   core-java        │
    │   (Next.js 14)   │   │  (Go 1.22)   │   │   (Spring Boot 3)  │
    │   Replicas: 3-10 │   │  Replicas: 5 │   │   Replicas: 3-10   │
    │   Stateless      │   │  Stateless   │   │   Stateless        │
    └─────────┬────────┘   └───────┬──────┘   └─────────┬──────────┘
              │                    │                     │
              │                    └──────────┬──────────┘
              │                               │
              │                 ┌─────────────▼──────────────┐
              └────────────────►│   Keycloak (HA Cluster)    │
                                │   Replicas: 2              │
                                │   Session: Redis           │
                                └─────────────┬──────────────┘
                                              │
        ┌──────────────────────┬──────────────┼────────────────┬──────────────┐
        │                      │              │                │              │
┌───────▼──────┐   ┌──────────▼─────┐  ┌────▼─────┐  ┌───────▼──────┐  ┌───▼──────┐
│ PostgreSQL   │   │ Redis Cluster  │  │ RabbitMQ │  │ Prometheus   │  │ Loki     │
│ (Primary +   │   │ (Cache +       │  │ (Message │  │ (Metrics)    │  │ (Logs)   │
│  Replicas)   │   │  Sessions)     │  │  Queue)  │  │              │  │          │
│ + RLS        │   │ Replicas: 3    │  │ HA Mode  │  │              │  │          │
└──────────────┘   └────────────────┘  └──────────┘  └──────────────┘  └──────────┘
        │
┌───────▼──────────────┐
│ S3-Compatible Storage│
│ (Backups + Assets)   │
└──────────────────────┘
```

### 1.3 Service Responsibilities (Enhanced)

| Service | Responsibility | Technology | Scale Strategy |
|---------|---------------|------------|----------------|
| **frontend** | UI, client-side routing, SSR | Next.js 14, React 18 | Horizontal (CDN + containers) |
| **edge-go** | API Gateway, rate limiting, auth proxy | Go 1.22 + Gin | Horizontal (stateless) |
| **core-java** | Business logic, domain model, auditing | Spring Boot 3, Hibernate | Horizontal (stateless) |
| **keycloak** | Identity provider, SSO, user management | Keycloak 24 | Horizontal (2+ replicas) |
| **postgresql** | Primary data store, RLS enforcement | PostgreSQL 15 | Vertical + read replicas |
| **redis** | Cache, session storage, rate limit state | Redis 7 Cluster | Horizontal (cluster mode) |
| **rabbitmq** | Async jobs, event streaming | RabbitMQ 3.12 | Horizontal (HA queues) |
| **prometheus** | Metrics collection, alerting | Prometheus + Alertmanager | Vertical (single node + federation) |
| **loki** | Log aggregation, querying | Grafana Loki | Horizontal (distributed mode) |

---

## 2. Deployment Architecture

### 2.1 Kubernetes Architecture

```yaml
# Namespace Strategy
├── jtoye-production       # Production workloads
├── jtoye-staging          # Staging environment
├── jtoye-dev              # Development environment
├── jtoye-monitoring       # Observability stack (shared)
└── jtoye-infrastructure   # PostgreSQL, Redis, RabbitMQ

# Key Resources per Namespace:
- Deployments (apps)
- Services (networking)
- Ingress (routing)
- ConfigMaps (config)
- Secrets (credentials via sealed-secrets or External Secrets Operator)
- HorizontalPodAutoscaler (auto-scaling)
- PodDisruptionBudget (HA)
- NetworkPolicy (isolation)
```

### 2.2 Container Strategy

#### 2.2.1 core-java Container

```dockerfile
# Multi-stage build for optimal size
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /app/build/libs/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:9090/actuator/health || exit 1

# JVM tuning for containerized env
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+UseG1GC \
               -XX:+UnlockExperimentalVMOptions \
               -XX:+UseStringDeduplication \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 9090
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Size Target:** < 200MB (JRE Alpine + JAR)
**Startup Time:** < 30 seconds
**Memory:** 512Mi request, 1Gi limit

#### 2.2.2 edge-go Container

```dockerfile
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o edge ./cmd/edge

FROM scratch
COPY --from=builder /app/edge /edge
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
EXPOSE 8080
ENTRYPOINT ["/edge"]
```

**Size Target:** < 15MB (scratch + static binary)
**Startup Time:** < 1 second
**Memory:** 64Mi request, 256Mi limit

#### 2.2.3 frontend Container

```dockerfile
FROM node:20-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production

FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV production
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs
COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
ENV PORT 3000
CMD ["node", "server.js"]
```

**Size Target:** < 150MB (Alpine + standalone build)
**Startup Time:** < 5 seconds
**Memory:** 256Mi request, 512Mi limit

### 2.3 Kubernetes Manifests

#### 2.3.1 core-java Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: core-java
  namespace: jtoye-production
  labels:
    app: core-java
    version: v1
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0  # Zero-downtime deployments
  selector:
    matchLabels:
      app: core-java
  template:
    metadata:
      labels:
        app: core-java
        version: v1
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9090"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - core-java
              topologyKey: kubernetes.io/hostname
      containers:
      - name: core-java
        image: jtoye/core-java:1.0.0
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 9090
          name: http
          protocol: TCP
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "production"
        - name: DB_HOST
          value: "postgresql-primary.jtoye-infrastructure.svc.cluster.local"
        - name: DB_PORT
          value: "5432"
        - name: DB_NAME
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: database
        - name: DB_USER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
        - name: KC_ISSUER_URI
          value: "https://auth.jtoye.co.uk/realms/jtoye-prod"
        - name: REDIS_HOST
          value: "redis-cluster.jtoye-infrastructure.svc.cluster.local"
        - name: RABBITMQ_HOST
          value: "rabbitmq.jtoye-infrastructure.svc.cluster.local"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9090
          initialDelaySeconds: 60
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9090
          initialDelaySeconds: 30
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        lifecycle:
          preStop:
            exec:
              command: ["sh", "-c", "sleep 10"]  # Graceful shutdown
        securityContext:
          runAsNonRoot: true
          runAsUser: 1000
          allowPrivilegeEscalation: false
          capabilities:
            drop:
            - ALL
---
apiVersion: v1
kind: Service
metadata:
  name: core-java
  namespace: jtoye-production
spec:
  type: ClusterIP
  selector:
    app: core-java
  ports:
  - port: 9090
    targetPort: 9090
    protocol: TCP
    name: http
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: core-java-hpa
  namespace: jtoye-production
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: core-java
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # Wait 5min before scaling down
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0  # Scale up immediately
      policies:
      - type: Percent
        value: 100
        periodSeconds: 30
      - type: Pods
        value: 2
        periodSeconds: 30
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: core-java-pdb
  namespace: jtoye-production
spec:
  minAvailable: 2  # Always keep 2 pods running during disruptions
  selector:
    matchLabels:
      app: core-java
```

#### 2.3.2 Ingress Configuration

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jtoye-ingress
  namespace: jtoye-production
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/rate-limit: "100"  # 100 req/min per IP
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    nginx.ingress.kubernetes.io/enable-cors: "true"
    nginx.ingress.kubernetes.io/cors-allow-origin: "https://app.jtoye.co.uk"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - api.jtoye.co.uk
    - app.jtoye.co.uk
    - auth.jtoye.co.uk
    secretName: jtoye-tls
  rules:
  - host: api.jtoye.co.uk
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: core-java
            port:
              number: 9090
  - host: app.jtoye.co.uk
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: frontend
            port:
              number: 3000
  - host: auth.jtoye.co.uk
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: keycloak
            port:
              number: 8080
```

### 2.4 CI/CD Pipeline

```yaml
# .github/workflows/production-deploy.yml
name: Production Deployment

on:
  push:
    branches: [main]
    tags: ['v*']

env:
  REGISTRY: ghcr.io
  IMAGE_PREFIX: jtoye

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run unit tests
        run: ./gradlew test

      - name: Run integration tests
        run: ./gradlew integrationTest

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-results
          path: core-java/build/reports/

  security-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: 'fs'
          scan-ref: '.'
          format: 'sarif'
          output: 'trivy-results.sarif'

      - name: Upload to GitHub Security
        uses: github/codeql-action/upload-sarif@v2
        with:
          sarif_file: 'trivy-results.sarif'

  build-and-push:
    needs: [test, security-scan]
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [core-java, edge-go, frontend]
    steps:
      - uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/${{ matrix.service }}
          tags: |
            type=ref,event=branch
            type=ref,event=pr
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}
            type=sha

      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: ./${{ matrix.service }}
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    needs: [build-and-push]
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4

      - name: Set up kubectl
        uses: azure/setup-kubectl@v3
        with:
          version: 'v1.28.0'

      - name: Configure kubeconfig
        run: |
          mkdir -p $HOME/.kube
          echo "${{ secrets.KUBE_CONFIG }}" | base64 -d > $HOME/.kube/config

      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/core-java \
            core-java=${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/core-java:${{ github.sha }} \
            -n jtoye-production
          kubectl rollout status deployment/core-java -n jtoye-production --timeout=5m

      - name: Run smoke tests
        run: |
          ./scripts/smoke-test.sh https://api.jtoye.co.uk

      - name: Rollback on failure
        if: failure()
        run: |
          kubectl rollout undo deployment/core-java -n jtoye-production
          exit 1
```

---

## 3. Scalability & High Availability

### 3.1 Horizontal Scaling Strategy

| Component | Min Replicas | Max Replicas | Scale Trigger | Target |
|-----------|--------------|--------------|---------------|--------|
| frontend | 3 | 10 | CPU > 70% | < 100ms p95 latency |
| edge-go | 5 | 20 | CPU > 60% | < 50ms p95 latency |
| core-java | 3 | 10 | CPU > 70%, Memory > 80% | < 200ms p95 latency |
| keycloak | 2 | 4 | CPU > 80% | < 300ms auth latency |
| redis | 3 (cluster) | 6 | Memory > 70% | < 1ms get latency |
| rabbitmq | 3 (HA) | 3 | Queue depth > 1000 | < 100 msgs/sec |

### 3.2 Database Scalability

```
┌─────────────────────────────────────────────────────────────┐
│                    PostgreSQL HA Setup                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────┐         ┌──────────────┐                │
│  │   Primary    │────────►│  Replica 1   │ (Read-only)   │
│  │  (Write)     │         │  Sync Repl   │                │
│  │  RLS Enabled │         │  RLS Enabled │                │
│  └──────┬───────┘         └──────────────┘                │
│         │                                                   │
│         │                  ┌──────────────┐                │
│         └─────────────────►│  Replica 2   │ (Read-only)   │
│                            │  Async Repl  │                │
│                            │  RLS Enabled │                │
│                            └──────────────┘                │
│                                                             │
│  Connection Pooling: PgBouncer (Transaction mode)          │
│  Failover: Patroni + etcd (automatic promotion)            │
│  Backup: WAL-G to S3 (PITR, 30-day retention)             │
└─────────────────────────────────────────────────────────────┘
```

**Key Decisions:**
- **Synchronous replication** to 1 replica (zero data loss)
- **Asynchronous replication** to 2nd replica (performance)
- **PgBouncer** in transaction mode (connection pooling)
- **Patroni** for automatic failover (< 30s RTO)

### 3.3 Caching Strategy

```
┌─────────────────────────────────────────────────────────────┐
│                    Multi-Layer Caching                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Layer 1: HTTP Cache (CDN)                                 │
│  ├── Static assets: 1 year (immutable)                     │
│  ├── HTML pages: 5 minutes (stale-while-revalidate)        │
│  └── API responses: No cache (dynamic)                     │
│                                                             │
│  Layer 2: Application Cache (Redis)                        │
│  ├── Session data: 24 hours                                │
│  ├── Reference data (shops, products): 1 hour              │
│  ├── User profiles: 15 minutes                             │
│  └── Rate limit counters: 1 minute                         │
│                                                             │
│  Layer 3: JPA Second-Level Cache (Hibernate)               │
│  ├── Immutable entities: Infinite (products)               │
│  ├── Rarely changed: 5 minutes (shops)                     │
│  └── Disabled for: Orders, Customers (consistency)         │
│                                                             │
│  Cache Invalidation:                                        │
│  ├── Write-through: Update cache on DB write               │
│  ├── Event-driven: Publish cache-invalidate events         │
│  └── TTL-based: Expire old entries automatically           │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Observability & Monitoring

### 4.1 Metrics Architecture (Prometheus)

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    cluster: 'jtoye-production'
    environment: 'prod'

scrape_configs:
  # Kubernetes service discovery
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs:
    - role: pod
    relabel_configs:
    - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
      action: keep
      regex: true
    - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
      action: replace
      target_label: __metrics_path__
      regex: (.+)
    - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
      action: replace
      regex: ([^:]+)(?::\d+)?;(\d+)
      replacement: $1:$2
      target_label: __address__

  # Core Java metrics
  - job_name: 'core-java'
    static_configs:
    - targets: ['core-java:9090']
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s

  # Edge Go metrics
  - job_name: 'edge-go'
    static_configs:
    - targets: ['edge-go:8080']
    metrics_path: '/metrics'
    scrape_interval: 5s

  # PostgreSQL metrics (postgres_exporter)
  - job_name: 'postgresql'
    static_configs:
    - targets: ['postgres-exporter:9187']
    scrape_interval: 30s

  # Redis metrics (redis_exporter)
  - job_name: 'redis'
    static_configs:
    - targets: ['redis-exporter:9121']
    scrape_interval: 30s

# Alerting rules
rule_files:
  - '/etc/prometheus/alerts/*.yml'

alerting:
  alertmanagers:
  - static_configs:
    - targets: ['alertmanager:9093']
```

### 4.2 Key Metrics & Alerts

```yaml
# alerts/sla-violations.yml
groups:
- name: SLA Violations
  interval: 30s
  rules:
  # API Latency
  - alert: HighAPILatency
    expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 0.2
    for: 5m
    labels:
      severity: warning
      component: core-java
    annotations:
      summary: "API p95 latency above 200ms"
      description: "{{ $labels.uri }} has p95 latency of {{ $value }}s"

  # Error Rate
  - alert: HighErrorRate
    expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
    for: 2m
    labels:
      severity: critical
      component: core-java
    annotations:
      summary: "Error rate above 5%"
      description: "{{ $labels.uri }} has error rate of {{ $value | humanizePercentage }}"

  # Database Connection Pool
  - alert: ConnectionPoolExhausted
    expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
    for: 5m
    labels:
      severity: warning
      component: postgresql
    annotations:
      summary: "Connection pool 90% utilized"
      description: "{{ $value | humanizePercentage }} of connections in use"

  # Circuit Breaker Open
  - alert: CircuitBreakerOpen
    expr: circuitbreaker_state{state="open"} > 0
    for: 1m
    labels:
      severity: critical
      component: edge-go
    annotations:
      summary: "Circuit breaker {{ $labels.name }} is OPEN"
      description: "Core API is unavailable from edge service"

  # RabbitMQ Queue Backlog
  - alert: MessageQueueBacklog
    expr: rabbitmq_queue_messages > 1000
    for: 10m
    labels:
      severity: warning
      component: rabbitmq
    annotations:
      summary: "Queue {{ $labels.queue }} has {{ $value }} messages"

  # Tenant Isolation Violation (Custom Metric)
  - alert: TenantIsolationViolation
    expr: rate(rls_policy_violations_total[5m]) > 0
    for: 1m
    labels:
      severity: critical
      component: postgresql
    annotations:
      summary: "RLS policy violation detected"
      description: "Tenant isolation compromised - immediate investigation required"
```

### 4.3 Logging Strategy (Loki + Promtail)

```yaml
# promtail-config.yml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  # Kubernetes pod logs
  - job_name: kubernetes-pods
    kubernetes_sd_configs:
    - role: pod
    pipeline_stages:
    - docker: {}
    - json:
        expressions:
          timestamp: time
          level: level
          message: msg
          tenant_id: tenant_id
          user_id: user_id
          trace_id: trace_id
    - labels:
        level:
        tenant_id:
        trace_id:
    - timestamp:
        source: timestamp
        format: RFC3339
    relabel_configs:
    - source_labels: [__meta_kubernetes_pod_label_app]
      target_label: app
    - source_labels: [__meta_kubernetes_namespace]
      target_label: namespace
```

**Log Retention:**
- **Production**: 30 days (compressed)
- **Audit logs**: 7 years (compliance)
- **Debug logs**: 7 days

### 4.4 Distributed Tracing (OpenTelemetry + Jaeger)

```yaml
# otel-collector-config.yml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 10s
    send_batch_size: 1024

  # Add tenant_id to all spans
  resource:
    attributes:
    - key: tenant_id
      from_attribute: tenant_id
      action: upsert

exporters:
  jaeger:
    endpoint: jaeger:14250
    tls:
      insecure: true

  prometheus:
    endpoint: "0.0.0.0:8889"
    namespace: traces

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch, resource]
      exporters: [jaeger, prometheus]
```

**Trace Sampling:**
- **Production**: 1% sampling (10,000 traces/day expected)
- **Errors**: 100% sampling (all errors traced)
- **Slow requests**: 100% sampling (> 1s latency)

### 4.5 Grafana Dashboards

**Dashboard 1: System Overview**
- Request rate (req/sec)
- Error rate (%)
- Latency percentiles (p50, p95, p99)
- Active users
- Tenant distribution

**Dashboard 2: Service Health**
- CPU/Memory per service
- JVM heap usage (core-java)
- Goroutine count (edge-go)
- Pod restart count
- HPA status

**Dashboard 3: Database Performance**
- Query latency
- Connection pool usage
- Slow query log (> 100ms)
- RLS policy execution time
- Replication lag

**Dashboard 4: Business Metrics**
- Orders per minute
- Revenue per tenant
- Failed transactions
- API usage per tenant
- Top products

---

## 5. Data Architecture & Consistency

### 5.1 Consistency Patterns

**Strong Consistency (ACID)**
- Order state transitions → PostgreSQL transactions
- Financial transactions → PostgreSQL with row-level locking
- Customer data → PostgreSQL with optimistic locking

**Eventual Consistency**
- Analytics/reporting → Read replicas (acceptable lag: 5s)
- Search indexes → Elasticsearch (lag: 30s)
- Cache invalidation → Pub/sub (lag: < 1s)

### 5.2 Distributed Transaction Patterns

```java
/**
 * SAGA Pattern for Order Processing
 *
 * Scenario: Create order + Reserve inventory + Create financial transaction
 *
 * Steps:
 * 1. Create order (status: DRAFT)
 * 2. Publish OrderCreatedEvent → RabbitMQ
 * 3. InventoryService reserves stock (compensating: release stock)
 * 4. FinanceService creates transaction (compensating: refund)
 * 5. OrderService transitions to CONFIRMED
 *
 * Failure handling:
 * - If step 3 fails: Mark order as FAILED, no compensation needed
 * - If step 4 fails: Release inventory, mark order as FAILED
 * - If step 5 fails: Refund transaction, release inventory, mark FAILED
 */

@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        // Step 1: Create order in DRAFT
        Order order = new Order();
        order.setStatus(OrderStatus.DRAFT);
        order.setTenantId(TenantContext.get().orElseThrow());
        Order saved = orderRepository.save(order);

        // Step 2: Publish event for async processing
        OrderCreatedEvent event = new OrderCreatedEvent(
            saved.getId(),
            saved.getTenantId(),
            request.items()
        );
        rabbitTemplate.convertAndSend("order.exchange", "order.created", event);

        return toDto(saved);
    }

    @RabbitListener(queues = "inventory.reserved")
    @Transactional
    public void handleInventoryReserved(InventoryReservedEvent event) {
        // Step 3 succeeded, continue saga
        Order order = orderRepository.findById(event.orderId()).orElseThrow();

        // Trigger financial transaction
        FinancialTransactionRequest txnRequest = new FinancialTransactionRequest(
            event.orderId(),
            event.totalAmount()
        );
        rabbitTemplate.convertAndSend("finance.exchange", "transaction.create", txnRequest);
    }

    @RabbitListener(queues = "inventory.reservation-failed")
    @Transactional
    public void handleInventoryReservationFailed(InventoryReservationFailedEvent event) {
        // Compensating transaction: Mark order as FAILED
        Order order = orderRepository.findById(event.orderId()).orElseThrow();
        order.setStatus(OrderStatus.FAILED);
        order.setNotes("Inventory reservation failed: " + event.reason());
        orderRepository.save(order);
    }
}
```

### 5.3 Event Sourcing (Future Enhancement)

```java
/**
 * Event Sourcing for Order State Machine
 *
 * Benefits:
 * - Complete audit trail (every state change recorded)
 * - Time travel (replay to any point)
 * - Event-driven architecture (decouple services)
 *
 * Trade-offs:
 * - Complexity (need event store, projections)
 * - Eventual consistency (read models lag)
 */

@Entity
@Table(name = "order_events")
public class OrderEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID tenantId;  // RLS still applies!

    @Column(nullable = false)
    private String eventType;  // ORDER_CREATED, ORDER_CONFIRMED, etc.

    @Column(columnDefinition = "JSONB", nullable = false)
    private String payload;

    @Column(nullable = false)
    private Long version;  // For optimistic locking

    @CreationTimestamp
    private OffsetDateTime occurredAt;

    private String userId;  // Who triggered the event
}

// Projection: Current order state
@Entity
@Table(name = "order_projections")
public class OrderProjection {
    @Id
    private UUID orderId;
    private OrderStatus currentStatus;
    private Long lastProcessedVersion;
    private OffsetDateTime updatedAt;
}
```

### 5.4 Data Migration Strategy

```sql
-- Zero-downtime migration pattern: "Expand-Migrate-Contract"

-- Phase 1: EXPAND (add new column, keep old)
ALTER TABLE orders ADD COLUMN customer_id_v2 UUID;
CREATE INDEX CONCURRENTLY idx_orders_customer_v2 ON orders(customer_id_v2);

-- Phase 2: MIGRATE (backfill data)
UPDATE orders
SET customer_id_v2 = customer_id
WHERE customer_id_v2 IS NULL
  AND customer_id IS NOT NULL;

-- Phase 3: Dual-write period (application writes to both columns)
-- Run for 1-2 weeks to ensure no data loss

-- Phase 4: CONTRACT (drop old column)
ALTER TABLE orders DROP COLUMN customer_id;
ALTER TABLE orders RENAME COLUMN customer_id_v2 TO customer_id;
```

---

## 6. Security Architecture

### 6.1 Defense in Depth

```
Layer 7: Application Security
├── Input validation (JSR-303 Bean Validation)
├── Output encoding (prevent XSS)
├── CSRF protection (SameSite cookies)
└── SQL injection prevention (JPA Criteria API, no raw SQL)

Layer 6: Authentication & Authorization
├── JWT validation (RS256 signatures)
├── Token expiration (15 min access, 7 day refresh)
├── Multi-factor authentication (TOTP)
└── Role-based access control (tenant-admin, shop-manager, staff)

Layer 5: Network Security
├── TLS 1.3 (all communications encrypted)
├── mTLS between services (Istio service mesh)
├── Network policies (Kubernetes)
└── Zero-trust networking (Calico)

Layer 4: Tenant Isolation
├── PostgreSQL RLS (database-level enforcement)
├── JWT tenant_id claim (application-level)
├── Separate Keycloak groups per tenant
└── Redis key prefixes (tenant:uuid:*)

Layer 3: Data Security
├── Encryption at rest (PostgreSQL TDE)
├── Encryption in transit (TLS 1.3)
├── PII encryption (AES-256-GCM for sensitive fields)
└── Secrets management (HashiCorp Vault / AWS Secrets Manager)

Layer 2: Infrastructure Security
├── Container image scanning (Trivy)
├── Runtime security (Falco)
├── Pod security policies (enforce non-root, no privileged)
└── Vulnerability patching (automated Renovate bot)

Layer 1: Physical Security
├── Cloud provider compliance (SOC 2, ISO 27001)
├── DDoS protection (Cloudflare)
├── WAF rules (OWASP Top 10)
└── Rate limiting (per tenant, per IP)
```

### 6.2 Threat Model

| Threat | Impact | Likelihood | Mitigation | Residual Risk |
|--------|--------|------------|------------|---------------|
| **SQL Injection** | Critical | Low | JPA Criteria API, RLS | Very Low |
| **Cross-tenant access** | Critical | Medium | RLS + JWT validation | Low |
| **JWT token theft** | High | Medium | Short expiration, HTTPS only | Medium |
| **DDoS attack** | High | High | Rate limiting, Cloudflare | Low |
| **Insider threat** | Critical | Low | Audit logs, RBAC, RLS | Medium |
| **Container escape** | Critical | Very Low | Non-root, seccomp, AppArmor | Very Low |
| **Supply chain attack** | High | Medium | Image scanning, SBOM | Medium |
| **Data breach** | Critical | Low | Encryption, RLS, audit trail | Low |

### 6.3 Security Checklist

**Pre-Production:**
- [ ] Penetration testing (OWASP Top 10)
- [ ] Dependency vulnerability scan (Snyk, Dependabot)
- [ ] Container image scan (Trivy, Grype)
- [ ] Secrets rotation (Vault)
- [ ] TLS certificate expiration monitoring
- [ ] Security headers (HSTS, CSP, X-Frame-Options)
- [ ] Rate limiting per tenant (100 req/min default)
- [ ] DDoS protection (Cloudflare)
- [ ] WAF rules (OWASP ModSecurity Core Rule Set)

**Post-Production:**
- [ ] Security incident response plan
- [ ] Quarterly security audits
- [ ] Bug bounty program
- [ ] SOC 2 Type II certification
- [ ] GDPR compliance audit
- [ ] PCI-DSS compliance (if handling payments)

---

## 7. Disaster Recovery & Business Continuity

### 7.1 Backup Strategy

```yaml
# Backup Schedule
PostgreSQL:
  Full Backup: Daily at 2 AM UTC
  Incremental: Every 6 hours
  WAL Archive: Continuous (streaming to S3)
  Retention: 30 days online, 7 years cold storage
  Test Restore: Weekly (automated)
  PITR: Yes (point-in-time recovery to any second)

Redis:
  RDB Snapshot: Every 1 hour
  AOF: Enabled (fsync every second)
  Retention: 7 days

Keycloak:
  Database backup: Same as PostgreSQL
  Realm export: Daily
  Retention: 30 days

Application Config:
  Git repository: GitHub (unlimited retention)
  Secrets: Vault backup (encrypted, daily)
```

### 7.2 Recovery Procedures

**RTO (Recovery Time Objective):**
- **Critical services** (auth, core API): 30 minutes
- **Non-critical** (analytics): 4 hours

**RPO (Recovery Point Objective):**
- **Financial data**: 0 seconds (synchronous replication)
- **User data**: 5 minutes (WAL streaming)
- **Analytics**: 1 hour (acceptable data loss)

**Disaster Scenarios:**

| Scenario | Likelihood | Impact | Detection | Recovery | RTO | RPO |
|----------|-----------|--------|-----------|----------|-----|-----|
| Single pod crash | High | Low | Immediate (K8s) | Auto-restart | 10s | 0s |
| Node failure | Medium | Medium | 30s (kubelet) | Pod rescheduling | 2min | 0s |
| AZ failure | Low | High | 1min (monitoring) | Failover to other AZ | 10min | 5min |
| Region failure | Very Low | Critical | 5min | Failover to DR region | 30min | 5min |
| Data corruption | Low | Critical | Variable | Restore from backup | 2hr | 1hr |
| Ransomware | Very Low | Critical | Variable | Restore from cold backup | 4hr | 24hr |

### 7.3 Multi-Region Architecture (Future)

```
┌────────────────────────────────────────────────────────────────┐
│                     Global DNS (Route53)                        │
│              Latency-based routing + Health checks              │
└──────────────────────┬──────────────────┬──────────────────────┘
                       │                  │
        ┌──────────────▼──────────┐   ┌──▼─────────────────────┐
        │   Region: eu-west-1     │   │  Region: eu-west-2     │
        │   (Primary)             │   │  (DR / Read-only)      │
        ├─────────────────────────┤   ├────────────────────────┤
        │ K8s Cluster (3 AZs)     │   │ K8s Cluster (2 AZs)    │
        │ PostgreSQL (Primary)    │───┤ PostgreSQL (Replica)   │
        │ Redis (Active)          │   │ Redis (Standby)        │
        │ RabbitMQ (Active)       │   │ RabbitMQ (Standby)     │
        └─────────────────────────┘   └────────────────────────┘
                  │                              │
                  └──────────┬───────────────────┘
                             │
                    ┌────────▼──────────┐
                    │  S3 Replication   │
                    │  (Cross-region)   │
                    └───────────────────┘
```

---

## 8. Performance Targets & SLAs

### 8.1 Service Level Objectives (SLOs)

| Metric | Target | Measurement Window |
|--------|--------|-------------------|
| **Availability** | 99.9% (43 min downtime/month) | 30 days |
| **API Latency (p95)** | < 200ms | 5 minutes |
| **API Latency (p99)** | < 500ms | 5 minutes |
| **Error Rate** | < 0.1% | 5 minutes |
| **Database Query Latency (p95)** | < 50ms | 5 minutes |
| **Auth Flow Latency** | < 500ms | 5 minutes |
| **Page Load Time (p95)** | < 2s | 1 hour |

### 8.2 Capacity Planning

**Current Capacity (Phase 1):**
- Users: 100 concurrent
- Requests: 50 req/sec
- Database: 100 GB
- Tenants: 10

**Target Capacity (Phase 2):**
- Users: 10,000 concurrent
- Requests: 5,000 req/sec
- Database: 1 TB
- Tenants: 1,000

**Scaling Plan:**
- **Horizontal**: Add pods when CPU > 70%
- **Vertical**: Increase pod resources at 80% utilization
- **Database**: Add read replicas at 60% load
- **Cache**: Scale Redis cluster at 70% memory

### 8.3 Load Testing Plan

```yaml
# k6-load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export let options = {
  stages: [
    { duration: '5m', target: 100 },   // Ramp-up
    { duration: '10m', target: 100 },  // Sustained load
    { duration: '5m', target: 500 },   // Peak load
    { duration: '10m', target: 500 },  // Sustained peak
    { duration: '5m', target: 0 },     // Ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // Test: Get shops
  let res = http.get('https://api.jtoye.co.uk/shops', {
    headers: { 'Authorization': `Bearer ${__ENV.JWT_TOKEN}` },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 200ms': (r) => r.timings.duration < 200,
  });

  sleep(1);
}
```

---

## 9. Implementation Roadmap

### Phase 2.1: Deployment Foundation (Weeks 1-2)
- [ ] Create Dockerfiles for all services
- [ ] Set up container registry (GHCR)
- [ ] Create Kubernetes manifests
- [ ] Set up CI/CD pipeline (GitHub Actions)
- [ ] Deploy to staging environment
- [ ] Run smoke tests

### Phase 2.2: Observability Stack (Weeks 3-4)
- [ ] Deploy Prometheus + Grafana
- [ ] Deploy Loki + Promtail
- [ ] Deploy Jaeger (tracing)
- [ ] Create dashboards
- [ ] Configure alerts
- [ ] Set up on-call rotation (PagerDuty)

### Phase 2.3: High Availability (Weeks 5-6)
- [ ] Set up PostgreSQL replication
- [ ] Deploy Redis cluster
- [ ] Deploy RabbitMQ HA
- [ ] Configure HPA for all services
- [ ] Test failover scenarios
- [ ] Document runbooks

### Phase 2.4: Security Hardening (Weeks 7-8)
- [ ] Implement secrets management (Vault)
- [ ] Add WAF rules
- [ ] Enable mTLS (Istio)
- [ ] Run penetration tests
- [ ] Fix vulnerabilities
- [ ] Security audit

### Phase 2.5: Performance Optimization (Weeks 9-10)
- [ ] Add Redis caching
- [ ] Implement query optimization
- [ ] Add read replicas
- [ ] Run load tests
- [ ] Tune JVM/container resources
- [ ] Document performance baselines

### Phase 2.6: Production Launch (Weeks 11-12)
- [ ] Deploy to production
- [ ] Run final smoke tests
- [ ] Monitor for 48 hours
- [ ] Gradual traffic ramp-up
- [ ] Document lessons learned
- [ ] Celebrate! 🎉

---

## 10. Success Criteria

**System Design 10/10 Achieved When:**

- ✅ All services containerized with multi-stage builds
- ✅ Kubernetes manifests for all components
- ✅ CI/CD pipeline with automated testing
- ✅ Horizontal pod autoscaling configured
- ✅ Database HA with automatic failover
- ✅ Multi-layer caching strategy implemented
- ✅ Prometheus + Grafana + Loki deployed
- ✅ Distributed tracing operational
- ✅ Alert rules covering all critical paths
- ✅ Disaster recovery tested quarterly
- ✅ Load testing validates 5,000 req/sec
- ✅ Security audit passed
- ✅ Documentation complete
- ✅ Runbooks for all failure scenarios

---

**Document Approved By:** [Pending]
**Next Review:** Q2 2026
**Contact:** architecture@jtoye.co.uk
