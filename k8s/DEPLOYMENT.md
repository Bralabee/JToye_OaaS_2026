# JToye OaaS Platform - Kubernetes Deployment Guide

## Overview
This guide provides comprehensive instructions for deploying the JToye OaaS platform to Kubernetes clusters in production and staging environments.

> **Scope: staging and production.** For the local minikube rehearsal, see
> [`k8s/LOCAL.md`](./LOCAL.md). The `k8s/local` overlay consumes the docker-compose backing services
> over `host.minikube.internal` and is brought up by `scripts/k8s-local-up.sh` — do not read the
> production recipe below as the local one. `k8s/LOCAL.md` also states plainly which controls a
> local run does **not** exercise (no TLS/cert-manager, no nginx security-header snippet, no
> NetworkPolicy enforcement), and carries the rehearsal-evidence template.

### Architecture diagrams
Two interactive topology views, generated from [`SYSTEM_DESIGN_V2.md §1`](../docs/architecture/SYSTEM_DESIGN_V2.md) and kept honest against this tree:

- **[Deployment Topology](https://claude.ai/code/artifact/6b995fb5-84f8-4725-8446-79cea792f55e)** — the Kustomize `base` → `local` / `staging` / `production` overlay tree, the base resource inventory (three app tiers, two Ingresses, the pg-backup CronJob, six NetworkPolicies, zero committed Secrets), and the render-verification gate suite. All six gates — `render-golden`, `check-render-invariants`, `check-env-contract`, `check-connection-math`, `check-no-plaintext-secrets`, `validate-networkpolicies.py` — are enforced in CI and were confirmed green (with a fail-direction check) against `main`.
- **[Backend Communication Topology](https://claude.ai/code/artifact/b45c4f2b-367d-4ee4-8d55-23100bf7a8de)** — how the services actually talk (sync REST, async AMQP / STOMP / SSE, OIDC auth), with the async-plane defect ledger (#266 relay, #310 webhook).

> These are internal claude.ai artifacts; the links resolve for teammates the artifacts have been shared with.

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
4. **Message Queue**: RabbitMQ **3.13+** minimum, 4.3 recommended (dev/compose pins 4.3.4). The deployed broker's version is unverified from this repo — see `docs/runbooks/rabbitmq-broker-upgrade.md` and ADR-0002.
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
    email: devops@olajay.co.uk
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
- `api.olajay.co.uk` → Ingress LoadBalancer IP
- `app.olajay.co.uk` → Ingress LoadBalancer IP
- `auth.olajay.co.uk` → Keycloak LoadBalancer IP

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
| `check-env-contract.sh` | The env contract in **both** directions for **all three built services** (core-java, edge-go, frontend — widened from core-java-only by #298). Direction (a): every env name a Deployment injects is read by that service (a wrong name silently resolves to the service's own default — this is how the AMQP pool authenticated as the wrong user). Direction (b): every name a service reads is supplied, or carries an **explicit allowlist entry with a reason**. Per-service specifics: core-java parses `${PLACEHOLDER}`s across `application*.yml` and fails a default that is absent, local-only, **or an unresolved Spring property chain**; edge-go parses `os.Getenv`/`getEnv` literals and uses the strong form (read-and-not-injected is a violation) because the weak default-shape form would have been vacuous for `JWT_EXPECTED_ISSUER`, whose default is a variable; the frontend parses literal `process.env.NAME` **plus** the names `env-validation.ts` declares for its dynamic `process.env[expr]` reads, and encodes the **build-time/runtime split** — a `NEXT_PUBLIC_*` name is supplied by an `ARG` in `frontend/Dockerfile`, and one that is *both* ARG-declared and injected as a runtime `env:` is a non-allowlistable dead-config violation (D-18). Every extractor is self-tested against a synthetic control, so a regex that matches nothing exits **2 (VOID)** instead of reporting a clean contract over an unexamined service. The allowlists are themselves gated: a blank reason, a duplicate, a now-unnecessary entry, or an `OPEN DEFECT` reason citing no issue number all fail; `OPEN DEFECT` entries are printed under their own heading on every run. Requires GNU `grep -P`. |
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

**A third gate covers a hazard neither of the two above can see: a pinned dependency that is
*current* in the tree and *out of support* in the world.** `scripts/check-dependency-horizons.sh`
(plan **27-00**, not this section's pair) fails the build before a pin's support horizon lapses.
Runtime-parity asks "is the running thing the branch?"; the horizon gate asks "should the branch
still be running that at all?" — a broker can be perfectly fresh by `.Metadata.LastTagTime` and two
years past its last security patch, which is exactly what was found for RabbitMQ 3.12 (last patched
2024-05-06, discovered 2026-07-27).

Note it **cannot** see the staging/production RabbitMQ broker: that broker is provisioned outside
this repository and carries no manifest here, so its row is `pin: unknown` / `owner: UNASSIGNED`
with a dated `manual_review`. See [`docs/runbooks/rabbitmq-broker-upgrade.md`](../docs/runbooks/rabbitmq-broker-upgrade.md)
§7 for the operator action and ADR-0002 for the open ownership question.

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

### The gate now runs itself — `.githooks/post-merge`

The gate above was correct and nobody invoked it. Measured on 2026-07-30: **#380**
merged, changing `core-java` sources; `git pull` on the machine hosting the stack
said nothing, and the container kept serving an image built four hours earlier —
through two further PRs — until someone ran the gate by hand. The same session had
already produced the mirror-image failure, where a fix was verified against a
temporary build nobody was looking at.

`.githooks/post-merge` runs `check-runtime-freshness.sh` after every merge, which is
the instant staleness is *created*, and names the fix:

```
[post-merge] runtime parity: this pull made the running stack STALE
  core-java    DRIFT  image tagged 2026-07-30 15:51:39 UTC / newest commit e5f7a9e8 20:49:54

  fix:  bash scripts/sync-runtime.sh
```

| | |
|---|---|
| **Advisory, never blocking** | Every path exits 0, and git ignores a post-merge hook's status anyway. It must never be why a pull "fails", or it gets disabled. |
| **Silent unless it matters** | Speaks only when the stack is **UP and DRIFTED**. A hook that prints on every pull is noise, and noise gets uninstalled. |
| **VOID is not a pass** | Stack down → the gate exits 2 → the hook reports *"no opinion"* under `JTOYE_HOOK_VERBOSE=1` and stays quiet otherwise. It never launders "could not check" into "clean". |
| **Skips in a worktree** | Compose derives its project name from the directory, so from a worktree the gate queries an empty project namespace and calls every service NOT RUNNING. The main checkout's own pull does the real check. |

`scripts/sync-runtime.sh` is the fix it names: it asks the gate what drifted, rebuilds
exactly those with `up -d --build`, then **re-asserts with the same gate** — so it
cannot report success over a runtime the gate would still call stale. The service
names are parsed from the gate's output, so the parse is asserted: *drift reported
but zero names parsed* is a VOID, never "nothing to do".

**Enabling it — and why there is nothing to configure.** This machine runs a global
hook set (`core.hooksPath = ~/.git-hooks`) whose members are *dispatchers*: git only
ever runs one hooks directory, so each global hook delegates to a repo-local
`.githooks/<name>` when the repo commits an executable one. **A repo opts in by
committing the file and nothing else.**

`scripts/install-hooks.sh` therefore deliberately does **not** set `core.hooksPath` —
a per-repo value *replaces* the global directory and would disable the sibling
`prepare-commit-msg` and `pre-push` hooks. What it does instead is remove a
repo-level override that *shadows* the dispatcher (this checkout carried one pointing
at a directory holding zero hooks, which disabled all three), and it refuses if the
shadowed directory is non-empty.

**Why the gate itself is not in CI, and what is.** Checked rather than assumed: this
repo has **0 self-hosted runners**, every job runs on `ubuntu-latest`, and
`DEPLOY_ENABLED` is unset so the deploy jobs never run — a GitHub-hosted runner has no
runtime to inspect, local or deployed, so the gate could only ever exit 2 there. A
permanently-VOID job trains people to add `|| true`. What CI *can* assert, and now
does as the fifth Operational Contracts step, is `install-hooks.sh --check`: that the
hook is **executable in the git index**. A hook committed `100644` is skipped silently
by both git and the dispatcher's `[[ -x ]]`, and that symptom is identical to a clean
run. The index is checked rather than the filesystem, because an uncommitted local
`chmod` is lost on the next clone.

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

## Operational contracts

The single inventory of **every** gate this repository runs, old and new. The two
sections above ("K8s static gates", "Runtime-parity gates") remain the detailed
reference for their own gates; this section is the map, plus the three gates Phase 27
added and the two it deliberately left out of CI.

**All of them share one exit-code convention.** It is stated once here and holds
everywhere:

| Code | Meaning |
|------|---------|
| `0` | Clean — the assertion holds. |
| `1` | Violation — a real defect. Fix the input, never the gate. |
| `2` | **VOID** — could not evaluate: a missing tool, an unreachable source, an unparseable input, or an empty discovery result. A `2` is **not** a pass. "Could not check" is never "clean". |

### The static set — runs in CI on every PR

| Gate | Job | What it pins |
|---|---|---|
| `k8s/scripts/check-no-plaintext-secrets.sh` | `k8s-validate` | No plaintext Secret material in any kustomize build (#100). |
| `k8s/scripts/check-connection-math.sh` | `k8s-validate` | The DB connection budget fits `max_connections` with ≥20% headroom (#94). |
| `k8s/scripts/check-env-contract.sh` | `k8s-validate` | The env contract in both directions for core-java, edge-go **and** the frontend (D-07/D-08, widened by #298), including the frontend's build-time/runtime channel split. |
| `k8s/scripts/check-render-invariants.sh` | `k8s-validate` | Render-level invariants (DB port, kube-dns selector, no localhost literal, no DB superuser). |
| `k8s/scripts/render-golden.sh` | `k8s-validate` | staging/production renders are byte-identical to their reviewed goldens. |
| `scripts/check-branch-behind-base.sh` | `branch-parity` | This branch contains every commit already on its base. |
| **`scripts/check-terminal-states.sh`** | **`ops-contracts`** | Every terminal failure state — DLQ, poison-outbox migration, terminal `FAILED` enum constant, scrape job — has a register row, a named alert and a runbook section. A new DLQ constant with no register row fails the build. Plan 27-00, finding F-9. |
| **`scripts/check-dependency-horizons.sh`** | **`ops-contracts`** | Every pinned image and toolchain carries a live support horizon. Past horizon, inside the 90-day warn window, stale cache, wrong slug, catalogue-vs-vendor conflict, manifest↔source drift either way, or a lapsed `manual_review` all fail. Plan 27-00, finding F-6. |
| **`scripts/check-alert-rules.sh`** | **`ops-contracts`** | `promtool check rules`, plus every live rule carrying its labels and annotations and having a `## <AlertName>` runbook heading. Plan 27-03, finding F-8: *"there is no CI validation of `alerts.yml` at all"*. |
| **`scripts/check-doc-citations.sh`** | **`ops-contracts`** | Every `` `file:line` `` citation in a **live** doc resolves to a line that says what the doc claims. Measured before it existed: **1 of 11** STACK.md dependency citations was still correct, and 12 more used a bare `build.gradle.kts` that resolves to the 22-line **root** file rather than core-java's. `check-doc-versions.sh` cannot see this — it compares version *strings* and has no notion of where a claim points. Scans live-claim docs only (override with `CITATION_DOCS`); historical records under `.planning/phases/**` are excluded by design, since validating them would force a choice between a red gate and rewriting the record. |

Run the whole static set locally before pushing:

```bash
# The complete static set — no cluster, no stack, seconds.
bash k8s/scripts/check-no-plaintext-secrets.sh \
  && bash k8s/scripts/check-connection-math.sh \
  && bash k8s/scripts/check-env-contract.sh \
  && bash k8s/scripts/check-render-invariants.sh \
  && bash k8s/scripts/render-golden.sh \
  && bash scripts/check-terminal-states.sh \
  && bash scripts/check-dependency-horizons.sh \
  && bash scripts/check-alert-rules.sh \
  && bash scripts/check-doc-citations.sh \
  && echo ALL_STATIC_GATES_GREEN
```

`ops-contracts` has two outbound dependencies and they are accepted deliberately:
`check-dependency-horizons.sh` fetches `endoflife.date`, and `check-alert-rules.sh` pulls
`prom/prometheus:v2.48.0` to run `promtool` (it is not installed on hosts, and the image's
ENTRYPOINT is `/bin/prometheus`, so the call must pass `--entrypoint=promtool`). An outage of
either turns the job **red at exit 2**, never green.

**This job reddens on dates, with no code change.** RabbitMQ 4.3's vendor horizon is
2026-11-30 against a 90-day warn window, so expect amber around **2026-09-01** and red on
**2026-12-01**. Resolve it by upgrading the pin or re-dating the exemption in the manifest —
never by weakening the job.

### The runtime set — deliberately NOT in CI

| Gate | What it pins |
|---|---|
| `scripts/check-runtime-freshness.sh` | The running containers are built from this tree (see "Runtime-parity gates"). |
| `scripts/check-alert-liveness.sh` | That the monitoring can **see and tell**: scrape targets are actually up, the thing behind a target is healthy (a target being UP is not evidence — `DatabaseDown` watched `up{job="postgres"}` which was 1 because the *exporter* answered while `pg_up` was 0), and Alertmanager actually delivers. Plan 27-00. |
| `scripts/check-alert-metrics.sh` | That each live rule's **series selector** matches ≥1 real series. Plan 27-03. Its defect: `StompBrokerLag` selected on a `queue` label that the aggregated endpoint does not emit at all, so the rule could never fire and nine real dead messages sat unreported for eleven days — while `promtool check rules` passed the file throughout. |

**Neither runtime alert gate subsumes the other.** Liveness asks *can the pipeline observe and
deliver at all*; metrics asks *does this particular rule's selector match anything*. A rule can
pass liveness (its target is up, Alertmanager delivers) and still be permanently blind because
its selector names a label that does not exist — which is exactly what happened.

They are absent from CI for the reason already recorded for `check-runtime-freshness.sh`: **a
runner has no Prometheus and no running containers, so they could only ever exit 2 (VOID) there,
and a permanently-VOID job invites a `|| true` that turns it into a gate that passes because it
measures nothing.** `infra/load-testing/baseline.sh` is out for a different reason (27-00 D-11):
a shared runner's throughput numbers are noise, and a noisy gate gets disabled.

**Because CI cannot run them, a phase close must.** Every phase SUMMARY records the liveness
gate's result in this literal form, next to its branch-parity and runtime-parity lines:

```
check-alert-liveness.sh: exit=<N> at <ISO-8601>
```

The literal form is the requirement, not a paraphrase of it — a requirement stated only in prose
is satisfied by any prose, and a gate whose only enforcement is a human remembering is not a gate.

**What the alert transport does NOT prove.** A green liveness run proves a message left
Alertmanager and reached the configured sink. It does **not** prove a human reads that sink —
today that sink is a Mailhog dev container with nobody behind it. Delivery is not receipt.

### The two registers an operator maintains

| Register | Maintained how |
|---|---|
| `docs/ops/terminal-states.yaml` | Add a row whenever a new DLQ, poison-outbox migration or terminal `FAILED` state appears: give it an `id`, the alert that detects it, and the runbook section an operator opens. `scripts/check-terminal-states.sh` fails the build if the code has a terminal state the register does not, or the register names an alert that does not exist. |
| `infra/dependency-horizons.yaml` | One row per pinned image/toolchain. Refresh cached vendor dates and pin sites with `bash scripts/check-dependency-horizons.sh --refresh`, then commit the diff. |

**An expired deferral, exemption or `manual_review` is a FAIL by design.** That is the whole
mechanism: a gap is acknowledged, owned and *dated*, and the build goes red the day the date
passes. Re-date it deliberately with a reason, or resolve it — never delete the row. The
`rabbitmq-k8s` row is the live example: the staging/production broker is provisioned outside this
repository, so its row is `pin: unknown` / `owner: UNASSIGNED` with a dated `manual_review`, it is
printed on **every** run, and it goes red the day that review lapses. See
[`docs/runbooks/rabbitmq-broker-upgrade.md`](../docs/runbooks/rabbitmq-broker-upgrade.md) §7.

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
- Technical Lead: devops@olajay.co.uk
- On-Call: Use PagerDuty/OpsGenie
- Documentation: https://github.com/jtoye/oaas-platform/wiki

## Version History
- 2.1.0 (2026-07-06): Image tags aligned to product version 2.1.0; kustomize overlays fixed (each env owns its namespace) and `bases`/`commonLabels`/`patchesStrategicMerge` deprecations cleared.
- v0.8.0 (2026-01-16): Initial production-ready deployment with security enhancements
