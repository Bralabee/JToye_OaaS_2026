# JToye OaaS Platform - Kubernetes Deployment Guide

## Overview
This guide provides comprehensive instructions for deploying the JToye OaaS platform to Kubernetes clusters in production and staging environments.

> **Scope: staging and production.** For the local minikube rehearsal, see
> [`k8s/LOCAL.md`](./LOCAL.md). The `k8s/local` overlay consumes the docker-compose backing services
> over `host.minikube.internal` and is brought up by `scripts/k8s-local-up.sh` — do not read the
> production recipe below as the local one. `k8s/LOCAL.md` also states plainly which controls a
> local run does **not** exercise (no TLS/cert-manager, no nginx security-header snippet, no
> NetworkPolicy enforcement), and carries the rehearsal-evidence template.

## Prerequisites

### Required Tools
- `kubectl` v1.27+ configured with cluster access
- `kustomize` v5.0+ (or use kubectl built-in kustomize)
- `helm` v3.12+ for cert-manager and NGINX ingress
- `kubeseal` (if using Sealed Secrets)

### Required Infrastructure
1. **Kubernetes Cluster**: v1.27+ (tested on EKS, GKE, AKS)
2. **Database**: PostgreSQL 15+ (managed service recommended)
3. **Cache**: Redis 7+ (managed service recommended)
4. **Message Queue**: RabbitMQ 3.12+
5. **Identity Provider**: Keycloak 22+ deployment
6. **DNS**: Configured for your domains
7. **TLS Certificates**: cert-manager with Let's Encrypt

## Pre-Deployment Checklist

### 1. Cluster Setup
- [ ] Kubernetes cluster v1.27+ provisioned
- [ ] kubectl context configured correctly
- [ ] Sufficient resources (minimum: 8 vCPU, 16GB RAM)
- [ ] Storage classes configured for persistent volumes
- [ ] Network policies enabled (optional but recommended)

### 2. Install Required Controllers

#### NGINX Ingress Controller
```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update

helm install nginx-ingress ingress-nginx/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace \
  --set controller.metrics.enabled=true \
  --set controller.podAnnotations."prometheus\.io/scrape"=true \
  --set controller.podAnnotations."prometheus\.io/port"=10254
```

#### Cert-Manager (for TLS)
```bash
helm repo add jetstack https://charts.jetstack.io
helm repo update

helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --set installCRDs=true

# Create Let's Encrypt ClusterIssuer
kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: devops@jtoye.co.uk
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx
EOF
```

#### Metrics Server (for HPA)
```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### 3. Secret Management

> **REQUIRED BEFORE `kubectl apply -k` (issue #100):** the kustomize builds
> ship NO `kind: Secret` objects — the old plaintext template is now the
> reference-only file `k8s/base/secrets-template.yaml.example`. Every secret
> below must exist in the target namespace before deployment, or pods stay in
> `CreateContainerConfigError` and the pg-backup CronJob fails:
> `postgres-credentials` (incl. `backup-username`/`backup-password`),
> `s3-backup-credentials`, `keycloak-credentials`, `nextauth-secret`,
> `redis-credentials`, `rabbitmq-credentials`.
> CI guard: `k8s/scripts/check-no-plaintext-secrets.sh`.

#### Option A: Manual Secret Creation (Not Recommended for Production)
```bash
# PostgreSQL credentials (backup-* keys: BYPASSRLS dump role for pg-backup, #90 —
# create the role via infra/backups/create-backup-role.sql)
kubectl create secret generic postgres-credentials \
  --from-literal=host=postgresql-primary.jtoye-infrastructure.svc.cluster.local \
  --from-literal=port=5432 \
  --from-literal=database=jtoye \
  --from-literal=username=jtoye \
  --from-literal=password='YOUR_SECURE_PASSWORD_HERE' \
  --from-literal=backup-username=jtoye_backup \
  --from-literal=backup-password='YOUR_BACKUP_ROLE_PASSWORD_HERE' \
  -n jtoye-production

# S3 credentials for the pg-backup CronJob (#90) — scope to a bucket-limited
# IAM user / MinIO service account (PutObject/ListBucket/DeleteObject only)
kubectl create secret generic s3-backup-credentials \
  --from-literal=access-key='YOUR_S3_ACCESS_KEY' \
  --from-literal=secret-key='YOUR_S3_SECRET_KEY' \
  -n jtoye-production

# Redis credentials
kubectl create secret generic redis-credentials \
  --from-literal=password='YOUR_REDIS_PASSWORD_HERE' \
  -n jtoye-production

# RabbitMQ credentials (stomp-* keys: STOMP relay login consumed by core-java)
kubectl create secret generic rabbitmq-credentials \
  --from-literal=username=jtoye \
  --from-literal=password='YOUR_RABBITMQ_PASSWORD_HERE' \
  --from-literal=stomp-login=jtoye \
  --from-literal=stomp-passcode='YOUR_RABBITMQ_PASSWORD_HERE' \
  -n jtoye-production

# Keycloak credentials
kubectl create secret generic keycloak-credentials \
  --from-literal=admin-username=admin \
  --from-literal=admin-password='YOUR_KEYCLOAK_PASSWORD_HERE' \
  --from-literal=frontend-client-secret='YOUR_FRONTEND_CLIENT_SECRET' \
  --from-literal=core-api-client-secret='YOUR_CORE_API_CLIENT_SECRET' \
  -n jtoye-production

# NextAuth secret (generate with: openssl rand -base64 32)
kubectl create secret generic nextauth-secret \
  --from-literal=secret='YOUR_32_CHAR_NEXTAUTH_SECRET' \
  -n jtoye-production
```

#### Option B: Sealed Secrets (Recommended)
```bash
# Install Sealed Secrets controller
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm install sealed-secrets sealed-secrets/sealed-secrets \
  --namespace kube-system

# Create sealed secrets: copy the reference shape, substitute real values,
# seal, then SHRED the plaintext. Full workflow (incl. the batch script
# k8s/scripts/seal-secrets.sh): docs/runbooks/sealed-secrets.md
cp k8s/base/secrets-template.yaml.example /tmp/plaintext-secrets.yaml
# ... edit /tmp/plaintext-secrets.yaml: replace every REPLACE_WITH_* ...
kubeseal --format=yaml < /tmp/plaintext-secrets.yaml > k8s/production/sealed-secrets.yaml
shred -u /tmp/plaintext-secrets.yaml
kubectl apply -f k8s/production/sealed-secrets.yaml
```

#### Option C: External Secrets Operator with AWS Secrets Manager (Best for AWS)
```bash
# Install External Secrets Operator
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  --namespace external-secrets-system \
  --create-namespace

# Configure AWS Secrets Manager integration
# See: https://external-secrets.io/latest/provider/aws-secrets-manager/
```

### 4. DNS Configuration
Configure DNS records for your domains:
- `api.jtoye.co.uk` → Ingress LoadBalancer IP
- `app.jtoye.co.uk` → Ingress LoadBalancer IP
- `auth.jtoye.co.uk` → Keycloak LoadBalancer IP

Get the LoadBalancer IP:
```bash
kubectl get svc nginx-ingress-ingress-nginx-controller -n ingress-nginx
```

## Deployment Steps

### Production Deployment

#### 1. Update Image Tags
Edit `k8s/production/kustomization.yaml` and update image tags to the desired version:
```yaml
images:
  - name: ghcr.io/bralabee/jtoye-core-java
    newTag: 2.1.0  # Update this
  - name: ghcr.io/bralabee/jtoye-edge-go
    newTag: 2.1.0  # Update this
  - name: ghcr.io/bralabee/jtoye-frontend
    newTag: 2.1.0  # Update this
```

#### 2. Review Generated Manifests
```bash
# Preview what will be deployed
kubectl kustomize k8s/production
```

#### 3. Apply Configuration
```bash
# Deploy to production
kubectl apply -k k8s/production

# Alternative: Build and apply separately
kustomize build k8s/production | kubectl apply -f -
```

#### 4. Verify Deployment
```bash
# Check namespace and pods
kubectl get all -n jtoye-production

# Check HPA status
kubectl get hpa -n jtoye-production

# Check PDB status
kubectl get pdb -n jtoye-production

# Check ingress
kubectl get ingress -n jtoye-production

# Check TLS certificate
kubectl get certificate -n jtoye-production

# View pod logs
kubectl logs -f deployment/core-java -n jtoye-production
kubectl logs -f deployment/edge-go -n jtoye-production
kubectl logs -f deployment/frontend -n jtoye-production
```

#### 5. Health Check Verification
```bash
# Core Java health
kubectl port-forward svc/core-java 9090:9090 -n jtoye-production
curl http://localhost:9090/actuator/health

# Edge Go health
kubectl port-forward svc/edge-go 8080:8080 -n jtoye-production
curl http://localhost:8080/health

# Frontend health
kubectl port-forward svc/frontend 3000:3000 -n jtoye-production
curl http://localhost:3000/api/health
```

### Staging Deployment

CI auto-deploys staging from `main` when the repository variable
`DEPLOY_STAGING_ENABLED` is set to `'true'` (see `.github/workflows/ci-cd.yaml`
`deploy-staging`). This replaces the old never-functional develop-branch gate —
staging is off by default and only fires once the variable is enabled, exactly
like the production `DEPLOY_ENABLED` gate. The CI job pins the full-sha image
tag at deploy time via `kustomize edit set image`.

For a manual deploy, follow the same steps as production but use `k8s/staging`:
```bash
# Deploy to staging
kubectl apply -k k8s/staging

# Verify deployment
kubectl get all -n jtoye-staging
```

## Rolling Updates

### Zero-Downtime Deployment Process
```bash
# 1. Update image tag in kustomization.yaml
# 2. Apply the update
kubectl apply -k k8s/production

# 3. Watch rollout status
kubectl rollout status deployment/core-java -n jtoye-production
kubectl rollout status deployment/edge-go -n jtoye-production
kubectl rollout status deployment/frontend -n jtoye-production

# 4. Verify new pods are running
kubectl get pods -n jtoye-production -l app=core-java
```

### Rollback if Issues Occur
```bash
# Rollback to previous version
kubectl rollout undo deployment/core-java -n jtoye-production

# Rollback to specific revision
kubectl rollout history deployment/core-java -n jtoye-production
kubectl rollout undo deployment/core-java --to-revision=2 -n jtoye-production
```

## Scaling

### Manual Scaling
```bash
# Scale deployments manually
kubectl scale deployment/core-java --replicas=5 -n jtoye-production
kubectl scale deployment/edge-go --replicas=10 -n jtoye-production
kubectl scale deployment/frontend --replicas=5 -n jtoye-production
```

### Horizontal Pod Autoscaler (HPA)
HPA is already configured and will automatically scale on load:
- **core-java**: 3-10 replicas (CPU: 70% — CPU only; a memory target pins a
  JVM workload at maxReplicas because the JVM commits ~75% of its memory limit
  as heap regardless of load, see issue #94)
- **edge-go**: 5-20 replicas (CPU: 60%, Memory: 70%)
- **frontend**: 3-10 replicas (CPU: 70%)

View HPA status:
```bash
kubectl get hpa -n jtoye-production -w
```

### Database Connection Budget (issue #94)

Postgres for this platform (managed in the `jtoye-infrastructure` namespace,
NOT by this kustomization) **MUST run with `max_connections=200`** — the same
explicit value the local compose stack sets (`docker-compose.full-stack.yml`).
Do not leave it at the PG15 default (100): the budget below assumes 200.

Connection math at the HPA replica ceiling (per environment/namespace — each
points at its own Postgres via the `postgres-credentials` secret):

| Consumer                                   | Connections |
|--------------------------------------------|-------------|
| core-java: (maxReplicas 10 + surge 1) × pool 10 | 110    |
| Keycloak (Agroal pool, capped)             | 20          |
| pg-backup CronJob (pg_dump)                | 1           |
| postgres-exporter                          | 2           |
| **Total application demand**               | **133**     |
| superuser_reserved_connections             | 3           |
| **max_connections required**               | **200** (133 ≤ 157 = 80% of 197 usable → ~32% headroom) |

Every number in that table is parsed from the real files and re-asserted on
every CI run by `k8s/scripts/check-connection-math.sh` (job `k8s-validate`).
If you change `maxReplicas`, `DB_POOL_SIZE`, the Hikari profile defaults, or
`max_connections`, the gate fails until the math balances again with >=20%
headroom. If load tests (#115) ever show 10 connections per pod saturating,
prefer introducing PgBouncer (transaction mode) in front of Postgres over
inflating pool sizes.

## K8s static gates

Five scripts under `k8s/scripts/` run on every PR in the `k8s-validate` job
(`.github/workflows/ci-cd.yaml`). They are client-side only — no cluster access,
no credentials — and the whole set takes about two seconds. Run all five locally
before pushing a `k8s/` change:

```bash
# The complete static set. Stops at the first gate that fails.
bash k8s/scripts/check-no-plaintext-secrets.sh \
  && bash k8s/scripts/check-connection-math.sh \
  && bash k8s/scripts/check-env-contract.sh \
  && bash k8s/scripts/check-render-invariants.sh \
  && bash k8s/scripts/render-golden.sh \
  && echo ALL_GATES_GREEN
```

| Script | What it guarantees |
|--------|--------------------|
| `check-no-plaintext-secrets.sh` | `k8s/base` and every overlay build, and no build output contains a top-level `kind: Secret` or a `REPLACE_WITH_*` placeholder (outside the known non-secret `deployment.timestamp` annotation). Plaintext Secret material can never become a live kustomize resource (#100). |
| `check-connection-math.sh` | HPA `maxReplicas` × Hikari pool (+ Keycloak + pg-backup + exporter + reserved slots) fits Postgres `max_connections` with ≥20% headroom, the k8s `DB_POOL_SIZE` env matches the `application-prod.yml` default, and the core-java HPA carries no memory metric (#94). See "Database Connection Budget" above. |
| `check-env-contract.sh` | The core-java env contract in **both** directions: every env name the manifest injects is read by some `application*.yml` (a wrong name silently resolves to a Spring default — this is how the AMQP pool authenticated as the wrong user), and every `${PLACEHOLDER}` whose default is local-only or absent is either supplied by a manifest or carries an **explicit allowlist entry with a reason**. The allowlist is itself gated: a blank reason, a duplicate, or a now-unnecessary entry fails. Requires GNU `grep -P`. Covers **core-java only**. |
| `check-render-invariants.sh` | Assertions on the kustomize **render**, which is what actually reaches a cluster: no hardcoded Postgres port in the base; no EnvVar carrying both `value` and `valueFrom` (accepted by `kubectl kustomize`, **rejected** by the API server at apply time); no common labels injected into the kube-dns DNS-egress `podSelector` (that selector then matches nothing and core-java loses all DNS egress under an enforcing CNI); no `localhost`/`127.0.0.1`/`minioadmin` literal in a non-local render; and no DB **superuser** named as the `postgres-credentials` app username in `k8s/QUICK_START.md` or `k8s/base/secrets-template.yaml.example`. |
| `render-golden.sh` | The `kubectl kustomize k8s/staging` and `k8s/production` output is byte-identical to the reviewed goldens in `k8s/goldens/`. A `k8s/base` edit that changes either render without a regenerated golden fails the PR. |

**Exit-code convention (shared by all five):**

| Code | Meaning |
|------|---------|
| `0` | Clean — the assertion holds. |
| `1` | Violation — a real defect in the manifests, config or docs. Fix the input, not the gate. |
| `2` | Parse or tooling failure — a missing tool (`kubectl`, GNU `grep -P`), a failed `kustomize` build, a missing baseline, or a parser that can no longer find its subject. A `2` means the assertion is **void, not passing**: fix the parser rather than deleting the invariant. |

## Runtime-parity gates

Two scripts under `scripts/` share the exit-code convention above but answer a
different question from the five static gates. The static gates ask *is the
configuration correct*. These ask **is the thing actually running the code you
think it is** — the question no static gate, test suite or HTTP health check can
answer, because an `HTTP 200` and a rendered page title are byte-identical from a
stale image and a current one.

They exist because of a measured failure on 2026-07-26. Phase 26 restored the
canonical compose runtime with `docker compose start core-java frontend edge-go
mcp-server`; `start` starts existing containers and never builds, so the core-java
that came back up was serving a jar from before the phase's own
`application.yml` change. Simultaneously the branch was three commits behind
`origin/main`, so the frontend image was missing three merged UI PRs — which no
rebuild could have fixed, because the code was not in the tree being built.
Verification, code review, security and a full regression sweep were all green. A
human caught it by eye. See CLAUDE.md, "Falsifiable evidence + runtime parity".

```bash
# Before trusting any local E2E result, and before opening a PR:
bash scripts/check-branch-behind-base.sh \
  && bash scripts/check-runtime-freshness.sh \
  && echo RUNTIME_PARITY_PROVEN
```

| Script | What it guarantees |
|--------|--------------------|
| `check-runtime-freshness.sh` | For **every** compose service with a `build:` stanza and a running container: (1) the image's **`.Metadata.LastTagTime`** is at or after the newest commit touching the paths that image is built from, and (2) the container's image **ID** equals the ID the tag now points at. (1) catches "source changed, nobody rebuilt"; (2) catches "image rebuilt, container only `start`ed, so the new image is not running". The service set, each build context and each Dockerfile are read from `docker compose config`; the build **inputs** are the host-side `COPY`/`ADD` operands of that Dockerfile plus the Dockerfile itself, so nothing is hardcoded here. **A built service that is missing or not `running` VOIDs the whole run (exit 2)** — see below. |
| `check-branch-behind-base.sh` | `HEAD..<remote>/<default-branch>` is empty — this branch contains every commit already on its base. The base branch is **resolved** (`--base`, `$BASE_REF`, `$GITHUB_BASE_REF`, `refs/remotes/origin/HEAD`, then `git ls-remote --symref`), never hardcoded to `main`. Being *ahead* is normal and is not a finding; only being behind is. |

**Why `.Metadata.LastTagTime` and not `.Created`.** Docker preserves the original
`.Created` across a fully-cached rebuild and across a `docker pull`. Measured on
the dev host with all four app images correctly rebuilt at 01:44 UTC on
2026-07-26, `.Created` lagged `.LastTagTime` by 5h34m for core-java and by
**277 hours** for edge-go. Swapping this one field in the gate — same script,
`.Created` substituted — reports core-java as DRIFT while it is provably current.
A gate that cries wolf gets ignored, so the field choice is load-bearing.

**Why an old image is not automatically stale.** Each service is compared only
against **its own** build inputs. edge-go's image legitimately predates the phase
because zero Go files changed in it, and the gate passes it. Comparing every image
against the repo's newest commit would flag edge-go forever.

**Why `git log --full-history`.** Plain `git log -- <paths>` applies history
simplification and, when a merge is TREESAME to one parent, reports that parent's
older commit instead of the merge — understating the bar by days and letting an
image built in the gap falsely pass. Measured difference on the Phase 26 branch for
core-java's build inputs: 7h45m.

**CI wiring, and why it is deliberately asymmetric.** `check-branch-behind-base.sh`
runs in the `branch-parity` job of `.github/workflows/ci-cd.yaml`, on
`pull_request` only, against the PR **head** SHA — not the checkout's HEAD, which
on a `pull_request` event is GitHub's synthetic merge commit and therefore contains
the base by construction, which would make the assertion vacuously green.
`check-runtime-freshness.sh` is **not** in CI and must not be added: a CI runner has
no running containers, so it could only ever exit 2 (VOID) there, and the pressure
to "fix" a permanently-VOID job with `|| true` would convert it into exactly the
kind of gate that is green because it measures nothing. It belongs where a runtime
exists — local dev, before E2E, and at the end of any phase that hands a runtime
back (`k8s/LOCAL.md` §10).

**A stopped stack is VOID, not clean — and so is a stopped *service*.** During a
local-k8s rehearsal the four app containers are intentionally down; the freshness
gate then reports every service UNVERIFIED and exits **2**. That is correct: a
runtime that is not running cannot be proven fresh.

Tightened 2026-07-27 (plan 27-00 Task 6) to apply **per service**. The check
previously fired only when *every* built service was unverifiable, so stopping one
of four printed `PASS: 3 running built service(s) match the source tree
(1 unverified)` and exited **0** — an unproven service reported inside a pass,
where "we could not check it" was rendered indistinguishable from "we checked it
and it was fine". Now **any** missing or non-`running` built service VOIDs the run.
Drift still outranks VOID: a runtime *known* stale (exit 1) is a stronger statement
than one that could not be evaluated. There is deliberately **no** bypass flag — an
`--allow-unverified` switch is how a check earns a `|| true`; a deliberate subset
run scopes itself with `--compose-file`.

### Golden-render workflow (required after any intentional `k8s/base` change)

The goldens are the reviewable record of what a base edit does to the shipped
staging and production output. They are **never hand-edited** — `--write` is the
arbiter:

```bash
# 1. Optional but recommended: name a pre-change baseline BEFORE editing.
k8s/scripts/render-golden.sh --snapshot my-change

# 2. Make the k8s/base edit.

# 3. Regenerate the goldens and review what changed in the render.
k8s/scripts/render-golden.sh --write
k8s/scripts/render-golden.sh --diff-since my-change   # '<' removed, '>' added

# 4. Commit the regenerated goldens in the SAME PR as the base edit.
git add k8s/goldens k8s/base
```

Committing the golden diff alongside the base edit is what makes the change
reviewable: a reviewer sees both the cause and its full effect on every
environment, including the additions and removals the edit did *not* intend.
`k8s/goldens/` deliberately contains no `kustomization.yaml`, so it is never
mistaken for a fourth overlay by the other gates' discovery loop.

## Monitoring and Observability

### Prometheus Metrics
All services expose Prometheus metrics:
- **core-java**: `/actuator/prometheus` on port 9090
- **edge-go**: `/metrics` on port 8080

### Logs
```bash
# Tail logs for all pods of a deployment
kubectl logs -f deployment/core-java -n jtoye-production --all-containers=true

# Stream logs from all pods with specific label
kubectl logs -f -l app=core-java -n jtoye-production

# Get logs from previous pod instance (useful after crashes)
kubectl logs deployment/core-java -n jtoye-production --previous
```

### Events
```bash
# View recent events in namespace
kubectl get events -n jtoye-production --sort-by='.lastTimestamp'

# Watch events in real-time
kubectl get events -n jtoye-production -w
```

## Troubleshooting

### Pod Not Starting
```bash
# Describe pod to see events
kubectl describe pod <pod-name> -n jtoye-production

# Common issues:
# - ImagePullBackOff: Check image registry credentials
# - CrashLoopBackOff: Check logs with kubectl logs
# - Pending: Check resource constraints and node capacity
```

### Service Not Accessible
```bash
# Check service endpoints
kubectl get endpoints -n jtoye-production

# Verify service selector matches pod labels
kubectl get pods -n jtoye-production --show-labels

# Test service connectivity from within cluster
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -n jtoye-production -- sh
# Then run: curl http://core-java:9090/actuator/health
```

### Ingress Issues
```bash
# Check ingress configuration
kubectl describe ingress jtoye-ingress -n jtoye-production

# View NGINX ingress controller logs
kubectl logs -f -n ingress-nginx -l app.kubernetes.io/component=controller

# Check TLS certificate status
kubectl get certificate -n jtoye-production
kubectl describe certificate jtoye-tls -n jtoye-production
```

### Database Connection Issues
```bash
# Verify database credentials secret
kubectl get secret postgres-credentials -n jtoye-production -o yaml

# Test database connectivity from a pod
kubectl run -it --rm psql --image=postgres:15 --restart=Never -n jtoye-production -- \
  psql -h postgresql-primary.jtoye-infrastructure.svc.cluster.local -U jtoye -d jtoye
```

## Maintenance

### Cluster Updates
```bash
# Before cluster upgrade, ensure PDBs are in place
kubectl get pdb -n jtoye-production

# Drain nodes one at a time
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data

# After node is drained and updated
kubectl uncordon <node-name>
```

### Database Migrations
Database migrations are handled automatically by Spring Boot on startup (Flyway/Liquibase).
Ensure the `core-java` startupProbe allows sufficient time (5 minutes configured).

### Backup Recommendations
1. **Database**: Use managed database backup features (AWS RDS snapshots, etc.)
2. **Secrets**: Backup sealed secrets or external secrets configuration
3. **Manifests**: Store in Git (already done)

## Security Best Practices

### Current Security Features
- [x] Non-root containers (all services)
- [x] Read-only root filesystem (where applicable)
- [x] Dropped all capabilities
- [x] Resource limits enforced
- [x] TLS encryption for all ingress traffic
- [x] Security headers (HSTS, X-Frame-Options, etc.)
- [x] Rate limiting per IP
- [x] CORS properly configured
- [x] Secrets externalized from code

### Recommended Enhancements
- [ ] Enable Network Policies to restrict pod-to-pod communication
- [ ] Implement Pod Security Standards (PSS) at namespace level
- [ ] Enable audit logging for API server
- [ ] Use OPA/Gatekeeper for policy enforcement
- [ ] Implement Falco for runtime security monitoring

## Production Readiness Checklist

### Before Go-Live
- [ ] All secrets created and validated
- [ ] DNS records configured and propagated
- [ ] TLS certificates issued and valid
- [ ] Database migrations tested
- [ ] Health checks responding correctly
- [ ] HPA tested under load
- [ ] PDBs configured correctly
- [ ] Resource limits tuned based on load testing
- [ ] Monitoring and alerting configured
- [ ] Backup strategy validated
- [ ] Disaster recovery plan documented
- [ ] Runbook created for common issues
- [ ] Security scan completed (container images, manifests)
- [ ] Load testing completed
- [ ] Rollback procedure tested

### Post-Deployment Validation
- [ ] All pods running and healthy
- [ ] Services accessible via ingress
- [ ] Authentication working (Keycloak integration)
- [ ] Database connectivity verified
- [ ] Redis caching operational
- [ ] RabbitMQ message queue functional
- [ ] Metrics being collected by Prometheus
- [ ] Logs being aggregated (if ELK/Loki configured)
- [ ] SSL/TLS certificates valid (A+ rating on SSL Labs)
- [ ] Rate limiting working as expected
- [ ] CORS policies working correctly
- [ ] End-to-end smoke tests passing

## Resource Requirements

### Minimum Cluster Capacity
**Production:**
- Nodes: 3+ (for HA)
- vCPU: 12 cores (core-java: 3x1, edge-go: 5x0.5, frontend: 3x0.5)
- Memory: 12GB (core-java: 3x1GB, edge-go: 5x256MB, frontend: 3x512MB)
- Storage: 50GB for logs and temporary data

**Staging:**
- Nodes: 2+
- vCPU: 6 cores
- Memory: 6GB
- Storage: 20GB

### Under Load (with HPA)
**Production:**
- vCPU: up to 25 cores (core-java: 10, edge-go: 10, frontend: 5)
- Memory: up to 22GB (core-java: 10GB, edge-go: 5GB, frontend: 5GB)

## Support and Contact
For issues or questions:
- Technical Lead: devops@jtoye.co.uk
- On-Call: Use PagerDuty/OpsGenie
- Documentation: https://github.com/jtoye/oaas-platform/wiki

## Version History
- 2.1.0 (2026-07-06): Image tags aligned to product version 2.1.0; kustomize overlays fixed (each env owns its namespace) and `bases`/`commonLabels`/`patchesStrategicMerge` deprecations cleared.
- v0.8.0 (2026-01-16): Initial production-ready deployment with security enhancements
