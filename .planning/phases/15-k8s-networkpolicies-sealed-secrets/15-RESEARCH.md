---
phase: 15
plan: research
type: research
date: 2026-04-18
---

# Phase 15 Research — K8s NetworkPolicies + Sealed Secrets

This document captures the inventory, tradeoff analysis, and design decisions that
feed Tasks 15-02 through 15-06. It is a pre-implementation research artefact; no
code changes are associated with this commit.

## 1. Audit Scope

- `k8s/base/` — authoritative source of in-cluster workloads
- `k8s/staging/` — overlay (namespace `jtoye-staging`)
- `k8s/production/` — overlay (namespace `jtoye-production`)
- **Note:** The ROADMAP originally referenced `k8s/overlays/staging` / `k8s/overlays/prod`.
  The actual on-disk layout is flat — `k8s/staging` and `k8s/production`. ROADMAP +
  CHANGELOG will be updated in Task 15-06 to reflect the actual paths.

## 2. In-Cluster Pod Inventory

The `k8s/base/` manifests define exactly three Deployments + one CronJob that run
inside the `jtoye-*` namespaces. All other dependencies (Postgres, Redis, RabbitMQ,
Keycloak, MinIO, Alertmanager, Prometheus, Grafana) are either:

1. **Out-of-cluster** managed services (RDS, ElastiCache, MQ Broker) referenced via
   DNS FQDNs in `configmap.yaml` — e.g.
   `postgresql-primary.jtoye-infrastructure.svc.cluster.local`, or
2. **In-neighbouring-namespace** deployments under `jtoye-infrastructure` that this
   repo does NOT manage.

The NetworkPolicies authored in Task 15-02 live in the `jtoye-production` /
`jtoye-staging` namespaces and target ONLY the pods we ship. Egress to the
infrastructure namespace is allowed via namespace selectors + CIDR-as-fallback.

### 2.1 In-cluster pods we ship (source: `k8s/base/`)

| Deployment  | Pod labels                                   | Container port | Service FQDN                                |
|-------------|----------------------------------------------|----------------|---------------------------------------------|
| `core-java` | `app=core-java`, `component=backend`, `version=v1` | 9090           | `core-java.jtoye-{env}.svc.cluster.local:9090` |
| `frontend`  | `app=frontend`, `component=ui`, `version=v1` | 3000           | `frontend.jtoye-{env}.svc.cluster.local:3000`  |
| `edge-go`   | `app=edge-go`, `component=gateway`, `version=v1` | 8080           | `edge-go.jtoye-{env}.svc.cluster.local:8080`   |
| `pg-backup` CronJob | `app.kubernetes.io/name=pg-backup`    | N/A (batch)    | N/A                                          |

Kustomize commonLabels additionally applies `app.kubernetes.io/managed-by=kustomize`
and `app.kubernetes.io/part-of=jtoye-platform` + an overlay-level
`environment={staging|production}` label to every resource. NetworkPolicies we
author will inherit these via kustomize, so our pod selectors use the stable
`app=<name>` label to stay overlay-independent.

### 2.2 External dependencies (egress targets)

| Dependency    | Role                | Location                                                      | Port(s)          | How referenced today                                         |
|---------------|---------------------|---------------------------------------------------------------|------------------|--------------------------------------------------------------|
| PostgreSQL    | Primary data store  | `postgresql-primary.jtoye-infrastructure.svc.cluster.local`   | 5432             | `postgres-credentials` Secret (`host` key) → `core-java`     |
| Redis         | Cache + sessions    | `redis-cluster.jtoye-infrastructure.svc.cluster.local`        | 6379             | `app-config` ConfigMap (`redis.host`)                        |
| RabbitMQ AMQP | Message bus         | `rabbitmq.jtoye-infrastructure.svc.cluster.local`             | 5672             | `app-config` ConfigMap (`rabbitmq.host`)                     |
| RabbitMQ STOMP| WebSocket relay     | `rabbitmq.jtoye-infrastructure.svc.cluster.local`             | 61613            | `app-config` ConfigMap (`stomp.broker.relay-host/port`)      |
| Keycloak      | Identity provider   | `https://auth.jtoye.co.uk/realms/jtoye-prod`                  | 443 (public DNS) | `app-config` ConfigMap (`keycloak.issuer.uri`). Both `core-java` and `frontend` + `edge-go` JWT validation hit this. |
| MinIO / S3    | Image + backup store| Cluster-internal MinIO OR AWS S3 via public endpoint          | 443 / 9000       | `jtoye-secrets` Secret `s3-endpoint`; CronJob also hits this |
| Stripe API    | Payments            | `api.stripe.com` / `webhooks.stripe.com`                      | 443              | Outbound only from `core-java` (Stripe Java SDK)             |
| Ollama        | Local LLM (dev only)| `ollama` cluster-local (not deployed in prod per current manifests) | 11434        | No prod manifest — dev-only via docker-compose              |
| Alertmanager  | Alert routing       | `alertmanager.jtoye-infrastructure.svc.cluster.local`         | 9093             | Added Phase 11 — referenced via `actuator/prometheus` scrape & Spring alert exporter |
| DNS (CoreDNS) | Service discovery   | `kube-dns.kube-system.svc.cluster.local`                      | 53/UDP + 53/TCP  | Every pod; default CoreDNS in `kube-system` namespace        |
| NTP (optional)| Time sync           | `*.pool.ntp.org`                                              | 123/UDP          | Typically handled by kubelet, NOT pod-level egress          |
| Ingress ctrl  | Inbound HTTP        | `ingress-nginx` namespace, `app.kubernetes.io/name=ingress-nginx` | 80/443        | Source of user traffic into `frontend`, `edge-go`           |
| Prometheus    | Metrics scrape      | `prometheus.jtoye-infrastructure` or `monitoring` namespace   | 9090 scrape-out  | Inbound to our pods on port 9090 / 8080 (`/metrics`, `/actuator/prometheus`) |

### 2.3 Routing reality — who talks to whom

```
                  ┌─────────────────────┐
                  │  ingress-nginx      │
                  │  (namespace:        │
                  │   ingress-nginx)    │
                  └─────┬───────┬───────┘
                        │       │
                  api.* │       │ app.*
                        │       │
                        ▼       ▼
                   ┌─────┐    ┌──────────┐
                   │edge │    │ frontend │
                   │ go  │    │          │
                   └──┬──┘    └────┬─────┘
                      │            │
                      └────┬───────┘
                           │
                           ▼
                   ┌───────────────┐
                   │   core-java   │
                   │               │
                   └───────────────┘
                           │
        ┌──────────┬───────┼────────────┬───────────────┐
        ▼          ▼       ▼            ▼               ▼
   ┌────────┐ ┌───────┐ ┌────────┐ ┌──────────┐ ┌──────────────┐
   │postgres│ │ redis │ │rabbit  │ │ minio/s3 │ │ stripe / kc  │
   │        │ │       │ │ AMQP + │ │          │ │ (public)     │
   │        │ │       │ │ STOMP  │ │          │ │              │
   └────────┘ └───────┘ └────────┘ └──────────┘ └──────────────┘

Plus cross-cutting:
 * DNS: every pod -> kube-dns
 * Metrics: prometheus -> core-java:9090, edge-go:8080
 * Health probes: kubelet -> every pod (NOT a pod-to-pod flow — NetworkPolicies
   EXEMPT kubelet probes automatically in K8s >= 1.21)
 * frontend -> keycloak public (browser-initiated + server-side NextAuth)
 * edge-go -> keycloak public (JWKS fetch for JWT validation)
```

## 3. Allowed-Flow Matrix

The baseline is **default-deny for both ingress and egress** at the namespace level,
scoped to our own pods via `podSelector: {}`. Every allowed flow below is an additive
exception expressed as a named NetworkPolicy.

### 3.1 Ingress (who can TALK TO a pod)

| Target pod   | Source pod / selector                                                | Port(s)     | Rationale                                                  |
|--------------|----------------------------------------------------------------------|-------------|------------------------------------------------------------|
| `frontend`   | `namespaceSelector: ingress-nginx`                                   | 3000/TCP    | Public app.* traffic                                        |
| `edge-go`    | `namespaceSelector: ingress-nginx`                                   | 8080/TCP    | Public api.* traffic (the Go gateway)                       |
| `core-java`  | `podSelector: app=frontend` (same namespace)                         | 9090/TCP    | NextAuth server-side callback + internal RSC fetch          |
| `core-java`  | `podSelector: app=edge-go` (same namespace)                          | 9090/TCP    | Request proxying via Gin gateway                            |
| `core-java`  | `namespaceSelector: monitoring` OR `jtoye-infrastructure`            | 9090/TCP    | Prometheus scrape `/actuator/prometheus`                    |
| `edge-go`    | `namespaceSelector: monitoring` OR `jtoye-infrastructure`            | 8080/TCP    | Prometheus scrape `/metrics`                                |

Note on kubelet health probes: K8s >= 1.21 automatically allows the node's kubelet
to reach a pod's probe endpoints regardless of NetworkPolicy. See K8s upstream
issue kubernetes/kubernetes#102610 for history. No explicit rule needed.

### 3.2 Egress (who can TALK OUT from a pod)

| Source pod  | Destination                                                     | Port(s)          | Rationale                                              |
|-------------|-----------------------------------------------------------------|------------------|--------------------------------------------------------|
| ALL pods    | `kube-dns` in `kube-system` (label `k8s-app=kube-dns`)          | 53/UDP, 53/TCP   | DNS resolution; everything depends on it               |
| `frontend`  | `core-java` (same namespace)                                    | 9090/TCP         | Server-side API calls                                  |
| `frontend`  | Public internet (for Keycloak + CDN images)                     | 443/TCP          | `auth.jtoye.co.uk`, S3/MinIO public URLs, image CDNs   |
| `edge-go`   | `core-java` (same namespace)                                    | 9090/TCP         | Proxied requests                                       |
| `edge-go`   | Public internet (Keycloak JWKS fetch)                           | 443/TCP          | `auth.jtoye.co.uk/realms/jtoye-prod/protocol/openid-connect/certs` |
| `core-java` | `postgresql-primary.jtoye-infrastructure`                       | 5432/TCP         | Primary DB                                             |
| `core-java` | `redis-cluster.jtoye-infrastructure`                            | 6379/TCP         | Cache + sessions                                       |
| `core-java` | `rabbitmq.jtoye-infrastructure`                                 | 5672, 61613/TCP  | AMQP publish/consume + STOMP relay                     |
| `core-java` | `minio.jtoye-infrastructure` OR public S3                       | 443, 9000/TCP    | Image upload                                           |
| `core-java` | `alertmanager.jtoye-infrastructure`                             | 9093/TCP         | Business-metric alerts (Phase 11)                      |
| `core-java` | Public internet — Keycloak, Stripe, Ollama (if remote)          | 443/TCP          | Identity + payments + LLM                              |
| `pg-backup` | `postgresql-primary.jtoye-infrastructure`                       | 5432/TCP         | `pg_dump` for nightly backup                           |
| `pg-backup` | S3 / MinIO                                                      | 443, 9000/TCP    | Upload backup artefact                                 |

## 4. Stripe CIDR Tradeoff

**The question:** Should `core-java` egress to Stripe be `0.0.0.0/0:443` or a
Stripe-published CIDR allowlist?

**Decision:** Use `0.0.0.0/0:443` for all public-internet egress from `core-java`,
scoped by the narrow set of egress rules (nothing else on the pod talks out on 443
except to Stripe + Keycloak + image CDNs + S3-public). We accept this broader
surface because:

1. **Stripe does not publish a stable IP allowlist.** Their docs explicitly direct
   customers to use domain-based allowlisting (DNS + SNI) rather than CIDR.
   (<https://support.stripe.com/questions/what-ip-addresses-does-stripe-use>.)
   CIDR allowlists drift and silently break payments on rotation.
2. **Keycloak public endpoint** (`auth.jtoye.co.uk`) already lives on a CloudFront /
   proxied IP; same CIDR-volatility concern.
3. **CDN image hosts** (`*.cloudfront.net`, `*.amazonaws.com`) are also CIDR-volatile.

**Defense-in-depth alternatives** we do NOT block on here:

- **Egress proxy pod** (e.g. Squid or Envoy) that the core-java NetworkPolicy forces
  all 443/TCP traffic through, enforcing SNI-based allowlist at L7. Flagged as
  future work (deferred to v2.3+ if a security incident demands it).
- **Service mesh (Istio/Linkerd) with `ServiceEntry`** — same story, larger op cost.

We document this tradeoff in the NetworkPolicies README so it's visible to a
future reviewer.

## 5. Default-Deny Baseline

Per K8s NetworkPolicy spec:

- A pod with NO NetworkPolicy selecting it accepts ALL traffic (default open).
- A pod with at least one NetworkPolicy selecting it becomes subject to the
  allow-list: everything NOT explicitly allowed is denied.

So the baseline is a single namespace-wide policy that selects all pods and defines
an empty allow-list — every subsequent policy is additive.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
  # No ingress:/egress: keys → deny-all
```

This file is `00-default-deny.yaml`. The `00-` prefix ensures it sorts first when
a human is browsing; K8s order of application does NOT matter semantically (policies
are OR-combined) but alphabetical ordering aids review.

## 6. Namespace + Label Assumptions

- The NetworkPolicies use `namespaceSelector` with `kubernetes.io/metadata.name=<ns>`
  (the auto-applied K8s >= 1.21 namespace label) so they work without extra labelling.
- For `jtoye-infrastructure` egress, we assume that namespace exists and has the
  `kubernetes.io/metadata.name=jtoye-infrastructure` label. This is a hard dependency
  documented in the NetworkPolicies README.
- For `ingress-nginx` ingress, we assume the controller runs in a namespace with
  `kubernetes.io/metadata.name=ingress-nginx`.
- For Prometheus scrape, we allow BOTH `monitoring` and `jtoye-infrastructure` (either
  is a plausible deployment target).

## 7. CI Validation Constraints

- CI runners do NOT have `kubectl` or cluster access. Validation in CI is limited to:
  1. **YAML syntax** via `python3 -c "import yaml; yaml.safe_load_all(...)"`.
  2. **Label-reference consistency** — grep the NetworkPolicy manifests for every
     `matchLabels` value, verify each corresponds to a `labels:` value on some
     Deployment in `k8s/base/`.
- **Live validation** (`kubectl apply --dry-run=server -k k8s/staging/`) is a
  manual step documented in the NetworkPolicies README. The cluster admin runs
  it post-operator-install.

## 8. Sealed Secrets Strategy (INF-02)

**Prereq:** The `bitnami-labs/sealed-secrets` controller runs IN the cluster and
holds a private key; the matching public key is used by the `kubeseal` CLI to
encrypt Secret manifests offline. Only the controller can decrypt them.

**Why a runbook + script, not actual SealedSecret manifests, in this phase:**

- The controller is not yet installed in staging (user lacks cluster-admin here).
- Without the live controller, we have no public key to encrypt against — any
  SealedSecret committed now would be unusable.
- Therefore Task 15-04 ships the conversion TOOL + documentation, and Task 15-05
  flags `secrets-template.yaml` as legacy. Actual conversion happens post-operator
  install, guided by the runbook's 4-step checklist (Task 15-06 SUMMARY).

**Fallback surfaces preserved:**

- `docker-compose.yml` development workflow: uses `.env` files, NOT K8s Secrets.
  No change needed; runbook will state this explicitly.
- Pre-operator cluster bootstrap: `k8s/base/secrets-template.yaml` remains in the
  kustomization for the small window between "cluster exists" and
  "sealed-secrets-controller installed + conversion done". Removing it from the
  overlays is a post-rollout cleanup (step 4 of the checklist).

## 9. Open Questions / Explicitly Deferred

- **Network policy testing on a real cluster** — this phase cannot run
  `kubectl apply --dry-run=server`. Flagged as a Task 15-06 SUMMARY checklist item.
- **Istio / mesh-level enforcement** — not in scope for v2.2; re-evaluate in v2.3
  when OTEL + log aggregation are scoped.
- **Egress CIDR allowlist for Stripe** — deferred (see §4).
- **`pg-backup` CronJob NetworkPolicy** — included in `40-datastores.yaml` because
  its egress surface (Postgres + S3) overlaps core-java's allowed egress; distinct
  pod selector `app.kubernetes.io/name=pg-backup`.

## 10. Task → Artifact Mapping

| Task   | Artefact                                                             |
|--------|----------------------------------------------------------------------|
| 15-01  | This document (`.planning/phases/15-.../15-RESEARCH.md`)              |
| 15-02  | `k8s/base/networkpolicies/{00-default-deny,10-frontend,20-core-java,30-edge-go,40-datastores,50-observability}.yaml` + updated `k8s/base/kustomization.yaml` |
| 15-03  | Python + shell-based YAML + label-reference validation script; live `kubectl --dry-run=server` documented in README |
| 15-04  | `docs/runbooks/sealed-secrets.md` + `k8s/scripts/seal-secrets.sh`     |
| 15-05  | Updated header in `k8s/base/secrets-template.yaml`                   |
| 15-06  | CHANGELOG, 15-01-SUMMARY.md, ROADMAP, REQUIREMENTS, STATE            |

## 11. Threat Register (STRIDE-aligned)

| ID       | Threat                                                                         | Mitigation                                                                                     | Applied in task |
|----------|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|-----------------|
| T-15-01  | Compromised `frontend` pod pivots to Postgres                                  | `frontend` egress policy has no postgres rule — denied by default-deny baseline                | 15-02           |
| T-15-02  | Compromised `edge-go` pod pivots to Postgres / Redis / RabbitMQ                | `edge-go` egress policy allows only core-java + DNS + public 443 (Keycloak JWKS)               | 15-02           |
| T-15-03  | Compromised any pod pivots to `pg-backup`                                      | `pg-backup` CronJob has no ingress rule; nothing needs to call it                              | 15-02 (40-)     |
| T-15-04  | Lateral movement via DNS tunnelling (covert exfil)                             | DNS egress scoped to `kube-system/kube-dns` only, not `0.0.0.0/0:53`                           | 15-02           |
| T-15-05  | Plaintext secrets leak via git history                                         | SealedSecret runbook + script replaces plaintext Secret commits post-rollout                   | 15-04, 15-05    |
| T-15-06  | Controller private-key loss = all SealedSecrets unrecoverable                  | Runbook §Key Rotation mandates off-cluster backup of controller key                            | 15-04           |
| T-15-07  | Re-sealing at key rotation missed for some secrets                             | Runbook §Re-sealing provides an explicit "sweep every *.sealed.yaml" step                      | 15-04           |

All of T-15-01..05 are enforced by the policy manifests + documentation — no
additional code changes needed.

## 12. Deviations anticipated

- **None expected.** This phase is documentation + manifest drafting only.
- If `yq` or `python3-yaml` is unavailable in CI, Task 15-03 validates via a
  Python one-liner using the stdlib (no external deps). Already verified `python3
  --version` returns 3.12.2 in the executor env.

---
*Research completed 2026-04-18 for Phase 15 Task 15-01*
