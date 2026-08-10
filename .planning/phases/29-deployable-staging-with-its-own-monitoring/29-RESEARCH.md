# Phase 29: Deployable Staging, With Its Own Monitoring — Research

**Researched:** 2026-08-10
**Domain:** Cloud infrastructure provisioning (AKS), Kubernetes platform services (ingress/TLS/identity/broker), observability porting (compose → k8s), managed-datastore integration, CI deploy authentication
**Confidence:** HIGH on measured Azure/repo facts · MEDIUM on cost projections · see Assumptions Log for the rest

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Hosting target (ROADMAP blocker #1 — settled)**
- **D-01 — AKS in `jtoye-rg`** (personal Azure sub `c483d353…`). Managed control plane,
  small node pool. **Network-policy enforcement is enabled at cluster level** (a cluster-create
  option on AKS) so DPLY-05's enforcing CNI comes from the platform, not a hand-rolled Calico
  install. ⚠ The `Prod - HS2 Ltd` subscription is EMPLOYER infrastructure — never touch
  (`azure_deploy_target` memory).
- **D-02 — Keycloak runs in-cluster on AKS.** New Deployment/Service/ingress manifests; realm
  imported from `infra/keycloak/realm-export.template.json`; backed by the managed Postgres
  (its own database on the same server). This makes #296's in-cluster-Keycloak hardening
  conditions live, and supersedes the ISSUE-DISPOSITION note "Phase 29 targets an external
  IdP" (written before a hosting target existed — there is no external Keycloak anywhere).
- **D-03 — ~£150/mo estate ceiling** (nodes + managed datastores + IP/DNS/egress): 2×D2s-class
  or 3×B2ms nodes + small managed Postgres + managed Redis Basic. Staging is **always-on** —
  "alerts a human" is meaningless for a cluster that's off.
- **D-04 — CI deploys with a real gate.** GitHub Actions authenticates to Azure via an **OIDC
  federated credential** (no long-lived kubeconfig secret) and the existing deploy job runs
  kustomize against AKS on merge to main. **#99 closes for real.** First bring-up may be
  manual, but the phase's definition of done includes a green CI deploy.

**Domain, DNS + TLS (ROADMAP blocker #2 — settled)**
- **D-05 — `olajay.co.uk` is the platform domain.** Confirmed by live measurement 2026-08-10:
  registered, zone live at Netlify DNS (NS1 infra, SOA `domains+netlify.netlify.com`), zero A
  records today; `jtoye.co.uk` is Namecheap-parked. Every k8s manifest already says olajay
  (deliberate 2026-07-27 migration) — zero manifest churn.
- **D-06 — Flat `-staging.*` hostname shape kept** exactly as `k8s/staging` patches:
  `api-staging.olajay.co.uk`, `app-staging.olajay.co.uk`, `auth-staging.olajay.co.uk` (new
  ingress for in-cluster Keycloak), plus `grafana-staging.olajay.co.uk` (D-19).
- **D-07 — One Azure static public IP for the ingress controller; DNS records added manually
  once at Netlify DNS; cert-manager Let's Encrypt HTTP-01 per-host certs.** No external-dns,
  no zone migration.
- **D-08 — Staging records ONLY this phase.** Production hosts (`api./app.olajay.co.uk`) stay
  unresolvable until Phase 32. CI: flip `DEPLOY_STAGING_ENABLED` + set staging
  `FRONTEND_PUBLIC_*` values; production flags stay off.

**Datastores — ADR-0002 SIGNED (DPLY-04 unblocked)**
- **D-09 — ADR-0002 hybrid accepted AS PROPOSED, owner-signed 2026-08-10.**
  PostgreSQL → **Azure Database for PostgreSQL Flexible Server**. Redis → **Azure Cache
  Basic**. RabbitMQ → **in-cluster via the RabbitMQ cluster operator**. A plan must flip the
  ADR's Status line to Accepted with the date.
- **D-10 — DPLY-04 proof shape:** provider PITR restore to a new server + row-count
  comparison, AND the existing `pg-backup-cronjob` logical dump stays as the
  provider-independent second line. Arm A (zero-row dump passes the pipeline's own checks)
  runs through the logical-dump path per the 26-07 L4 precedent. The BYPASSRLS `jtoye_backup`
  role bootstrap and the Phase 28 owner/runtime role split both apply to the managed server.
- **D-11 — Media storage = real AWS S3 eu-west-2.** #294's bucket verification is **done in
  this phase**, before first deploy.
- **D-12 — Logical-dump destination = a dedicated AWS S3 backup bucket** (eu-west-2).
- **D-13 — App outbound email in staging = in-cluster Mailhog capture.** SES half of #294
  defers to Phase 32 with that reason recorded. Alert email (D-17) is real and separate.

**Monitoring + alerting (DPLY-03)**
- **D-16 — Plain kustomize manifests, no Helm.** Hand-written Deployments for
  Prometheus/Alertmanager/Grafana + redis/postgres exporters under `k8s/`, mounting the SAME
  rule files and dashboard JSON `infra/monitoring/` already uses. Exporters point at the
  managed Postgres/Redis endpoints.
- **D-17 — "Alerts a human" = real email via Gmail SMTP** (`smtp.gmail.com` app password) to
  the owner's real inbox. Credential injected via the `.env`/secret layer, following
  `docs/runbooks/credential-rotation.md`.
- **D-18 — #112 closed minimally: paging path + ONE honest runbook page.** SLOs + the full
  runbook set defer post-GTM with reasons recorded.
- **D-19 — Grafana alone gets a public hostname** (`grafana-staging.olajay.co.uk`).
  Prometheus + Alertmanager stay cluster-internal; the two alert gates run via port-forward or
  a CI job with cluster access.

### Claude's Discretion
- **DPLY-02 per-issue dispositions** beyond those settled above — #292, #293, #299, #301,
  #302, #271, #300, #304, #592. Each gets closed-or-deferred **with a written reason** — none
  silently dropped.
- Node pool exact SKU/count within the £150 ceiling; managed Postgres tier/version; AKS
  network-policy engine choice (Azure/Calico/Cilium) so long as enforcement is real and the
  DPLY-05 denied-connection proof passes.
- Staging secrets mechanism: plain k8s Secrets via the Phase 26 script pattern vs
  sealed-secrets (#100/#300) — decide in planning against effort; if plain, #100/#300 defer
  with reason.
- The seeded-rows story for DPLY-01 and the denied-connection capture design for DPLY-05.
- Alertmanager routing detail (group_by/repeat_interval), which of the 19 compose alert rules
  apply to staging, Grafana dashboard provisioning mechanics.
- GHCR image pull path (public images vs imagePullSecret) and staging image tag strategy.

### Deferred Ideas (OUT OF SCOPE)
- **SES sending-domain verification + real app email** — Phase 32, reason recorded per D-13.
- **SLOs + full runbook set (#112 remainder)** — post-GTM (D-18).
- **Production DNS records / cutover** — Phase 32 (D-08).
- **Zone migration to Azure DNS / external-dns / wildcard certs** — revisit only if record
  churn becomes real (D-07).
- **ntfy/push alerting beside email** — cheap follow-up if email notice-time proves too slow.
- **Full-catalogue EXIF/WebP media sweep** — carried from Phase 28; NOT folded into this phase.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description (verbatim, `.planning/REQUIREMENTS.md:116-122`) | Research Support |
|----|-------------|------------------|
| **DPLY-01** | A real Keycloak vendor login through the ingress to a rendered dashboard, on a resolvable public hostname, with real seeded rows. | §"Blocker A: the `frontend` OIDC client does not exist", §"Realm parameterisation", §"Ingress + TLS", §"AKS provisioning" |
| **DPLY-02** | The deploy knot closed or per-issue deferred with a reason — #99, #100, #292, #293, #299, #301, #302, #271. | §"The 12+4 DPLY-02 issues — measured dispositions" (4 of them are already CLOSED on the tree) |
| **DPLY-03** | Prometheus, Alertmanager and Grafana running **in k8s**, with `check-alert-liveness.sh` and `check-alert-metrics.sh` exit 0 against staging. | §"Monitoring port", §"Blocker B: check-alert-liveness.sh is docker-bound and Mailhog-bound" |
| **DPLY-04** | PITR (#101) with a restore proven by row count, two-arm — arm A must show a zero-row dump passing the pipeline's own byte and `pg_restore --list` checks. | §"Managed PostgreSQL", §"Blocker C: BYPASSRLS needs PostgreSQL 16 on Flexible Server" |
| **DPLY-05** | NetworkPolicies enforced, not merely rendered (#297 Calico), with a denied connection captured. | §"NetworkPolicy under an enforcing CNI", §"Blocker D: the current egress rules deny every managed datastore" |
</phase_requirements>

---

## Summary

This phase is **provisioning-heavy and code-light**, but it is not code-free: four defects on
the current tree will each independently prevent a success criterion from being met, and none
of them is visible to any gate that exists today. They are the highest-value output of this
research and are named as Blockers A–D below.

The Azure ground truth is measurable and was measured. `jtoye-rg` exists in **uksouth** and is
**not empty** — it holds a live, always-on Azure Container Apps estate (`snackpass-*`: java-core,
go-edge, webapp, python-vision, redis, minio) plus a PostgreSQL Flexible Server and a Log
Analytics workspace. Cost Management reports **£30.09 month-to-date on Container Apps**
(1–10 Aug), i.e. a run-rate near **£95/month against the same £150 ceiling D-03 sets**. No AKS
cluster exists, and `Microsoft.ContainerService`, `Microsoft.Cache` and `Microsoft.Network` are
all **NotRegistered** in the subscription. A costed estate that fits the ceiling exists (≈£147/mo
with 3×B2s nodes, a B2s Flexible Server and a Basic C0 cache) but **only if the snackpass estate
is disposed of first**, and only if the staging overlay gains an HPA scale patch — because the
base HPAs (`minReplicas` 3/5/3) override the overlay's `replicas: 2/2/2` and would demand
~4.3 vCPU / 8.1 GiB of *requests* before a single platform service is scheduled.

The porting work is well-scoped by the repo's own discipline. `infra/monitoring/` holds one rule
corpus of 19 alert rules keyed on `job=` and `service=` labels, and the two DPLY-03 gate scripts
read those labels through hard-coded data blocks (`EXPORTER_GAUGES`, `DIRECT_JOBS`,
`SERVICE_JOB_MAP`) that VOID rather than skip on an unmapped job. That means the k8s Prometheus
must reproduce the **same job names and same target labels** — which it can, since the only
difference is the target address. `check-alert-metrics.sh` needs nothing but `PROM_URL` and works
through a port-forward unchanged. `check-alert-liveness.sh` does not: it carries 13 `docker`
references and requires a Mailhog HTTP API at the alert destination, and against a k8s Prometheus
with a real Gmail sink it exits **2 (VOID)**, not 0. DPLY-03's literal criterion is therefore
unmeetable without extending that script — which is work, not a workaround.

**Primary recommendation:** provision AKS with `--tier free --network-plugin azure
--network-plugin-mode overlay --network-dataplane cilium` (Cilium is the only engine Microsoft
recommends for new clusters and the only one not under a retirement notice), pick **PostgreSQL 16**
on a **B2s** Flexible Server (16 is required for `BYPASSRLS`; B2s is required for the connection
budget), and sequence the phase as: dispose/decide snackpass → register providers → provision →
cert-manager → RabbitMQ operator → platform services → app → monitoring → the four proofs. Treat
Blockers A–D as Wave-0/Wave-1 work with their own falsification arms.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Cluster + node lifecycle, network-policy enforcement | Azure control plane (AKS) | — | D-01 puts enforcement at cluster level; the CNI dataplane is a create-time property, not a manifest |
| Public IP, DNS records, TLS issuance | Azure Network + Netlify DNS + cert-manager (in-cluster) | ingress controller | D-07: IP is an Azure resource, records are manual at NS1, certs are in-cluster HTTP-01 |
| HTTP routing, TLS termination, security headers | Ingress controller (in-cluster) | — | Existing `k8s/base/ingress.yaml` owns hosts/annotations |
| Identity (OIDC issue/validate) | Keycloak (in-cluster, D-02) | managed Postgres (its state) | Keycloak becomes a first-class in-cluster workload for the first time; #296 conditions go live |
| Relational persistence + PITR | Azure Database for PostgreSQL Flexible Server | pg-backup CronJob (in-cluster) → AWS S3 | D-09/D-10: provider PITR is the first line, logical dump the provider-independent second |
| Cache / session store | Azure Cache for Redis Basic | — | D-09 |
| Message broker (AMQP + STOMP) | RabbitmqCluster (in-cluster, operator-managed) | — | D-09; STOMP plugin must be under our control |
| Object storage (media + backups) | AWS S3 eu-west-2 | — | D-11/D-12; deliberately off-Azure |
| Metrics scrape + rule evaluation | Prometheus (in-cluster) | exporters (in-cluster) pointing at managed endpoints | D-16 |
| Alert routing + delivery | Alertmanager (in-cluster) → Gmail SMTP | Mailhog (in-cluster) for app mail only | D-17/D-13: deliberate asymmetry |
| Dashboards | Grafana (in-cluster, public host) | — | D-19: only Grafana is publicly exposed |
| App outbound email capture | Mailhog (in-cluster) | — | D-13 |
| Deploy authentication + apply | GitHub Actions → Entra federated credential → AKS | `scripts/deploy.sh` for manual | D-04 |

---

## Standard Stack

### Core — Azure resources (provisioned once, `az` CLI)

| Resource | Recommended value | Purpose | Why |
|----------|------------------|---------|-----|
| AKS cluster | `--tier free`, K8s 1.33 LTS or 1.34, `--network-plugin azure --network-plugin-mode overlay --network-dataplane cilium` | Managed control plane + enforcing CNI | Free tier has no control-plane charge; Standard Uptime SLA is **£0.0758/hr = £55.33/mo** and would eat a third of the ceiling `[VERIFIED: Azure retail prices API, GBP/uksouth, 2026-08-10]`. Cilium is the only engine Microsoft recommends for new clusters `[CITED: learn.microsoft.com/azure/aks/use-network-policies]` |
| Node pool | 3 × `Standard_B2s` (2 vCPU / 4 GiB) | Workload capacity | £0.0358/hr each = **£78.40/mo** for 3 `[VERIFIED: retail prices API]`. See §"Capacity math" — B2ms is roomier but 2×B2ms = £104.39/mo and 3×B2ms = £156.58/mo which alone breaches D-03 |
| PostgreSQL | Flexible Server **B2s Burstable, version 16**, 32 GiB Premium_LRS, 7-day PITR, public access + firewall rule | Relational store | **Version 16 is not optional** — see Blocker C. B2s default `max_connections` = **429 (414 user)** vs B1ms's **50 (35 user)**; the repo's k8s budget needs **155** `[VERIFIED: check-connection-math.sh live run + learn.microsoft.com/azure/postgresql/configure-maintain/concepts-limits]`. £0.0576/hr = **£42.05/mo** + 32 GiB × £0.1008 = £3.23 |
| Redis | Azure Cache for Redis **Basic C0** (250 MB) | Cache/session store | £0.0212/hr = **£15.48/mo** `[VERIFIED: retail prices API]`. See §"Blocker-adjacent: Redis TLS" |
| Public IP | Standard IPv4 **static**, in the AKS node resource group | Ingress front door | £0.0038/hr = **£2.77/mo** `[VERIFIED: retail prices API]` |
| Managed identity | User-assigned, with a federated credential for GitHub Actions | CI deploy auth (D-04) | No long-lived kubeconfig secret |

### Core — in-cluster platform components (vendored manifests, no Helm)

| Component | Version | Install artefact | Notes |
|-----------|---------|------------------|-------|
| cert-manager | **v1.21.1** (2026-07-29) | `cert-manager.yaml` (1,034,400 bytes) from the GitHub release | `[VERIFIED: GitHub releases API, 2026-08-10]`. **Must be installed FIRST** — the RabbitMQ operator manifest contains `cert-manager.io/v1` `Certificate` + `Issuer` objects |
| RabbitMQ cluster operator | **v2.22.3** (2026-07-17), image `ghcr.io/rabbitmq/cluster-operator:2.22.3` | `cluster-operator.yml` (351,140 bytes), single file, namespace `rabbitmq-system` | `[VERIFIED: downloaded and inspected the manifest, 2026-08-10]`. Contains 15 documents: CRDs, Namespace, RBAC, Deployment, MutatingWebhookConfiguration, and the two cert-manager CRs |
| Ingress controller | ingress-nginx `controller-v1.15.1` (last release 2026-03-19) **or** the AKS application-routing add-on | static manifest / `az aks approuting enable` | **Both are on a clock — see §State of the Art.** ingress-nginx is retired upstream; the AKS add-on is patched by Microsoft only through **November 2026** |
| Keycloak | `quay.io/keycloak/keycloak:24.0.5` (unchanged pin) | hand-written Deployment/Service/Ingress | Same pin as compose so the realm import and issuer behaviour are identical |

### Supporting — monitoring images (reuse the compose pins verbatim, D-16)

| Image | Pinned version | Why keep the old pin |
|-------|---------------|----------------------|
| `prom/prometheus` | `v2.48.0` | Latest is v3.13.2 `[VERIFIED: GitHub releases API]`. Prometheus 3.x changes flag and config defaults; D-16 says reuse the rule corpus **verbatim**, and changing the engine at the same time as changing the runtime makes any failure un-attributable. Its horizon exemption expires **2026-12-31** |
| `prom/alertmanager` | `v0.27.0` | Latest v0.33.1. Exemption expires 2027-01-27 |
| `grafana/grafana` | `10.2.2` | Latest v13.1.3. Exemption expires 2026-12-31 |
| `prometheuscommunity/postgres-exporter` | `v0.15.0` | Latest v0.20.1. Exemption expires 2027-01-27 |
| `oliver006/redis_exporter` | `v1.58.0` | Latest v1.89.0. Exemption expires 2027-01-27 |
| `mailhog/mailhog` | `v1.0.1` | Already pinned; D-13 |

`kube-state-metrics` and `node_exporter` are **not** recommended: none of the 19 existing rules
reads their series, and adding an unmapped scrape job makes `check-alert-liveness.sh` L-1b VOID
by design ("a new exporter job with no row here is VOID, so the mapping cannot fall behind").
Adding them would require editing the gate's data blocks in the same change — a deliberate cost,
not a free win. The two commented-out `DiskSpace*` rules are the only rules that would use them.

### Alternatives Considered

| Instead of | Could use | Tradeoff |
|------------|-----------|----------|
| Cilium dataplane | `--network-policy azure` (NPM) | NPM on Linux is **retired 2028-09-30**; docs already tell you to migrate to Cilium. Also has a documented race condition with policies carrying many `ipBlock` sections — and this repo's `20-core-java.yaml` has an `ipBlock` with three `except` entries `[CITED: learn.microsoft.com/azure/aks/use-network-policies]` |
| Cilium dataplane | `--network-policy calico` | Supported and works on existing clusters, but Microsoft "doesn't test or support" Calico features beyond standard NetworkPolicy. Choose it only if the `ipBlock` limitation below proves fatal |
| 3 × B2s | 2 × B2ms (2 vCPU / 8 GiB) | More RAM headroom, **less CPU** (3.8 vCPU allocatable vs 5.7), and £104.39/mo pushes the estate to ~£172/mo — over D-03's ceiling |
| Self-installed ingress-nginx | AKS application-routing add-on | Add-on is Microsoft-patched to Nov 2026 and needs no vendored manifest, but it restricts which annotations you may set and manages its own controller ConfigMap — the `configuration-snippet` security headers would need re-homing |
| Vendoring operator YAML into `k8s/<dir>/kustomization.yaml` | A bootstrap script applying pinned URLs | Every `k8s/*/kustomization.yaml` is auto-discovered by five CI gates and by `render-golden.sh`; vendoring ~5,900 lines of cert-manager + operator CRDs would balloon `k8s/goldens/*.yaml` (currently 1,578 lines each) for no assurance gain |

**Provisioning commands (all flags verified against the locally installed `az` 2.89.0):**

```bash
# 0. Providers — MEASURED NotRegistered on this subscription 2026-08-10
az provider register -n Microsoft.ContainerService --subscription "$SUB"
az provider register -n Microsoft.Network          --subscription "$SUB"
az provider register -n Microsoft.Cache            --subscription "$SUB"

# 1. Cluster
az aks create -g jtoye-rg -n jtoye-staging-aks --location uksouth \
  --tier free \
  --network-plugin azure --network-plugin-mode overlay \
  --network-dataplane cilium \
  --node-vm-size Standard_B2s --node-count 3 \
  --enable-oidc-issuer \
  --generate-ssh-keys
```

---

## Package Legitimacy Audit

This phase installs **no npm or PyPI packages**. It installs container images and vendored
Kubernetes manifests, for which `slopcheck` (npm/PyPI registry heuristics) is not the right
instrument. `slopcheck` **was** installed successfully during research
(`pip install slopcheck --break-system-packages` → present), so its absence is not the reason
it is unused; it is simply out of domain. The equivalent provenance check for this phase is
"does the artefact come from the project's own release channel, and did I read it".

| Artefact | Registry / source | Provenance check performed | Disposition |
|----------|------------------|---------------------------|-------------|
| `ghcr.io/rabbitmq/cluster-operator:2.22.3` | GitHub releases → `cluster-operator.yml` | Downloaded release asset, read the image reference out of the manifest itself (line 5818) | Approved `[VERIFIED: release asset inspected 2026-08-10]` |
| `cert-manager.yaml` v1.21.1 | cert-manager GitHub release | Release asset listed via the GitHub API, size 1,034,400 bytes | Approved `[VERIFIED: GitHub releases API]` — **download by pinned tag and record the sha256 in the plan**, matching the CI `kustomize` install pattern |
| `registry.k8s.io/ingress-nginx/controller:v1.15.1` | ingress-nginx GitHub release | Latest release tag confirmed, publish date 2026-03-19 | Approved **with a recorded EOL** — see State of the Art |
| `quay.io/keycloak/keycloak:24.0.5` | already pinned in this repo | Existing pin, unchanged | Approved (no new supply-chain surface) |
| `prom/prometheus:v2.48.0`, `prom/alertmanager:v0.27.0`, `grafana/grafana:10.2.2`, `prometheuscommunity/postgres-exporter:v0.15.0`, `oliver006/redis_exporter:v1.58.0`, `mailhog/mailhog:v1.0.1` | already pinned in `infra/monitoring/` + compose | Existing pins with dated exemptions in `infra/dependency-horizons.yaml` | Approved |
| `rabbitmq:4.3.4-management-alpine` (as `RabbitmqCluster.spec.image`) | already pinned in compose | Pin the operator's cluster image **explicitly** rather than accepting the operator default | Approved — this is what resolves the `rabbitmq-k8s` horizon row (`pin: unknown`, **expires 2026-10-26**) |

**Packages removed due to a `[SLOP]` verdict:** none — no npm/PyPI packages in scope.
**Flagged as suspicious:** none.

**Mandatory follow-through:** `infra/dependency-horizons.yaml` is enforced by
`scripts/check-dependency-horizons.sh`. Every new pinned artefact above needs a row in it, and
`rabbitmq-k8s`'s `pin: unknown` row must be replaced with the real pin in the same change.

---

## Architecture Patterns

### System Architecture Diagram

```
                  Internet
                     │
       ┌─────────────┴──────────────┐
       │  Netlify DNS (NS1 zone)    │   4 manual A records → one Azure static IP
       │  *-staging.olajay.co.uk    │   [D-07: no external-dns]
       └─────────────┬──────────────┘
                     │  HTTP-01 challenge answered here too
                     ▼
   ╔═════════════════════════════════════════════════════════════════════════╗
   ║  AKS  (uksouth, --tier free, Cilium dataplane ENFORCING NetworkPolicy)  ║
   ║                                                                         ║
   ║   Azure LB ──► ingress controller Service (LoadBalancer, static PIP)     ║
   ║                     │                                                   ║
   ║        ┌────────────┼────────────┬──────────────┐                       ║
   ║        ▼            ▼            ▼              ▼                       ║
   ║   app-staging   api-staging  auth-staging   grafana-staging             ║
   ║        │            │            │              │                       ║
   ║        ▼            ▼            ▼              ▼                       ║
   ║   frontend ───► core-java ──► keycloak       grafana                    ║
   ║      (SSR)         │  ▲          │              │                       ║
   ║                    │  └──────────┘ OIDC/JWKS    │ queries               ║
   ║                    │     (split-horizon         ▼                       ║
   ║                    │      collapses to 1 host) prometheus ──► alertmanager
   ║                    │                            │  ▲            │       ║
   ║                    │                   scrapes ─┘  │            │ SMTP  ║
   ║                    │                               │            ▼       ║
   ║       ┌────────────┼───────────┬─────────────┐  exporters   Gmail relay ║
   ║       ▼            ▼           ▼             ▼   (pg,redis)      │      ║
   ║  RabbitmqCluster  mailhog   pg-backup   [cert-manager]           │      ║
   ║  (AMQP 5672,      (app mail  CronJob      issues certs           │      ║
   ║   STOMP 61613,     capture)     │         for all 4 hosts)       │      ║
   ║   metrics 15692)                │                                │      ║
   ╚═════════════════════════════════│════════════════════════════════│══════╝
                │  egress            │ egress                         │
                ▼                    ▼                                ▼
     ┌──────────────────┐  ┌──────────────────┐            owner's real inbox
     │ Azure PostgreSQL │  │  AWS S3 eu-west-2│            (the phase's point)
     │ Flexible Server  │  │  media + backups │
     │  (PITR source)   │  └──────────────────┘
     └──────────────────┘
     ┌──────────────────┐
     │ Azure Cache Redis│   ⚠ TLS 6380 only — see Blocker-adjacent
     │   Basic C0       │
     └──────────────────┘
```

The three arrows leaving the cluster box are the ones the current NetworkPolicies **do not
permit**. That is Blocker D.

### Component Responsibilities

| File / artefact | Responsibility |
|-----------------|----------------|
| `k8s/staging/kustomization.yaml` | Namespace, image pins, host patches, replica intent, `db.port` replacement |
| `k8s/staging/scale-patch.yaml` **(does not exist — must be created)** | HPA `minReplicas` + PDB `minAvailable` for staging; mirrors `k8s/local/scale-patch.yaml` |
| `k8s/staging/configmap-patch.yaml` | Staging URLs, realm, `keycloak.client-id` (Blocker A), `db.port` if non-default |
| `k8s/base/networkpolicies/*` | Egress allow-list — must gain out-of-cluster datastore rules (Blocker D) |
| `k8s/monitoring*/` (new) | Prometheus/Alertmanager/Grafana/exporters Deployments + ConfigMaps |
| `k8s/keycloak*/` (new) | Keycloak Deployment/Service/Ingress + realm-import ConfigMap |
| `scripts/staging-bootstrap.sh` (new, suggested) | Applies cert-manager + RabbitMQ operator by pinned URL+digest, outside `k8s/` so goldens stay small |
| `scripts/check-alert-liveness.sh` | Must gain a kubectl/k8s path (Blocker B) |
| `k8s/goldens/staging.yaml` | Regenerated **deliberately** with `k8s/scripts/render-golden.sh --write` in the same PR |

### Pattern 1: One rule corpus, two Prometheus configs

**What:** Keep `infra/monitoring/prometheus/alerts.yml` byte-identical (D-16). Write a *second*
Prometheus scrape config for k8s that reproduces the **same `job_name` values and the same
`labels:` blocks**, changing only the target address.

**Why it is load-bearing:** `check-alert-liveness.sh` maps rules to jobs through three hard-coded
data blocks and **VOIDs on any job it does not recognise**:

```bash
EXPORTER_GAUGES=( "postgres|pg_up" "redis|redis_up" )
DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq" "rabbitmq-queues")
SERVICE_JOB_MAP=( "core-java|core-java" "postgresql|postgres" "redis|redis"
                  "keycloak|keycloak" "rabbitmq|rabbitmq,rabbitmq-queues" "platform|*" )
```

Rename a job and the gate VOIDs. Keep the names and the gate runs unchanged.

**k8s target map (each address changes, nothing else does):**

| job_name | compose target | k8s target | Note |
|----------|---------------|------------|------|
| `prometheus` | `localhost:9090` | `localhost:9090` | unchanged |
| `core-java` | `core-java:${CORE_JAVA_METRICS_PORT:-9090}` | `core-java:9091` | prod profile puts actuator on the **management** port; `k8s/base/core-java-deployment.yaml` already annotates `prometheus.io/port: "9091"` |
| `edge-go` | `jtoye-edge-go:9101` | `edge-go:8080` | k8s leaves `EDGE_MANAGEMENT_PORT` unset, so `/metrics` stays on the app port — the template header already says so |
| `postgres` | `jtoye-postgres-exporter:9187` | `postgres-exporter:9187` | exporter DSN → managed FQDN, `sslmode=require` (already the compose default) |
| `keycloak` | `jtoye-keycloak:8080` | `keycloak:8080` | **First time this target is ever UP in k8s.** KC 24 with `KC_METRICS_ENABLED=true` serves `/metrics` on the main port |
| `redis` | `redis-exporter:9121` | `redis-exporter:9121` | exporter addr → `rediss://<cache>.redis.cache.windows.net:6380` |
| `rabbitmq` | `jtoye-rabbitmq:15692` | `<cluster>:15692` | operator always enables `rabbitmq_prometheus` and exposes 15692 |
| `rabbitmq-queues` | same host, `/metrics/detailed` | same | keep the `family=` params and the SSE-queue `metric_relabel_configs` drop |

### Pattern 2: ConfigMap mount changes what L-0 must assert

**What:** In compose, `alerts.yml` is a **single-file bind mount**, which is why L-0 exists — a
single-file mount is resolved to an inode at container start and silently detaches on edit. In
k8s a ConfigMap is projected as a **directory of symlinks** that the kubelet atomically re-points
on update, so the detach failure mode does not exist; the *new* failure mode is that the running
pod may serve a ConfigMap version older than the one just applied (kubelet sync period, plus
Prometheus needing a reload it does not have enabled).

**Consequence:** L-0's assertion is still needed but its **mechanism must change** — read the
file out of the running pod with `kubectl exec … md5sum` rather than `docker exec`. The script's
own header already anticipates this ("a k8s Prometheus has a different container name and a
different in-container path") and made `PROM_CONTAINER`/`PROM_ALERTS_PATH` overridable — but the
`docker inspect` / `docker exec` calls themselves are unconditional.

### Pattern 3: Third-party manifests live outside `k8s/`

**What:** cert-manager and the RabbitMQ operator are applied by a bootstrap script from pinned
release URLs; only *our* `RabbitmqCluster`, `ClusterIssuer` and `Certificate` CRs live in a
kustomization.

**Why:** `k8s/scripts/*` auto-discover targets with
`find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml'`, and `render-golden.sh` byte-compares
the full render. Vendoring 5,900 lines of upstream CRDs makes every upstream bump a golden diff
that nobody can review meaningfully.

### Anti-Patterns to Avoid

- **Editing `infra/monitoring/prometheus/alerts.yml` to "make it work on k8s."** D-16 says one
  corpus. If a rule genuinely cannot apply, use the `DORMANT_RULES` mechanism in
  `check-alert-metrics.sh` (comment out + list with a wake-trigger), never a silent edit.
- **Adding an `EXPECT_EMPTY`-style allowlist entry to a gate to get it green.** Both alert gates
  have STALE arms that fail when an exemption outlives its reason; the entry is the wrong fix and
  the gate is designed to say so.
- **`kubectl apply -f k8s/base/...` on a real cluster.** `scripts/deploy.sh` already documents
  why this bypassed kustomize and shipped unpinned images; one `apply -k` per environment.
- **Hand-editing `k8s/goldens/*.yaml`.** Regenerate with `render-golden.sh --write`.
- **Letting the `replicas:` transformer stand in for scale control.** It does not touch HPA or
  PDB — Phase 26 measured this and built `k8s/local/scale-patch.yaml`. Staging has no equivalent.

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---------|-------------|-------------|-----|
| TLS certificate issuance + renewal | ACME client scripts, manual certs | **cert-manager** `ClusterIssuer` + `Certificate` | Renewal, ordering, HTTP-01 solver pods, and the SAN-order failure mode the repo already documents in `k8s/base/ingress.yaml` |
| RabbitMQ StatefulSet, PVC, plugin enablement, credentials | Hand-written StatefulSet | **RabbitMQ cluster operator** `RabbitmqCluster` | Verified in the operator source: `additionalPlugins: [rabbitmq_stomp]` automatically adds the **61613** `stomp` port to the client Service; plugin changes need no pod restart |
| Postgres backup + point-in-time recovery | WAL-G / pgBackRest / cron scripts | **Flexible Server automated backups + PITR** | This is the entire content of D-09's managed choice; #101's fix direction is superseded by the ADR |
| Postgres/Redis metrics | Custom exporters, actuator scrapes | `postgres_exporter`, `redis_exporter` | Already pinned, already wired into the rule corpus via `pg_up`/`redis_up` |
| Azure auth in CI | Long-lived kubeconfig or service-principal secret | **Entra federated identity credential + `azure/login`** | D-04. Note: subject strings are exact-match with **no wildcards**, so `main` and `environment:staging` need separate credentials if both are used |
| Denied-connection probe | tcpdump / packet capture | A **client pod + a timeout** | Microsoft's own verification recipe: `agnhost connect <ip>:80 --timeout=3s --protocol=tcp` prints `TIMEOUT` when denied and nothing when allowed — a two-arm proof out of the box |

**Key insight:** almost every "build it" temptation here has already been rejected once in this
repo (WAL-G in #101, hand-rolled Calico in D-01, a second rule corpus in D-16). The genuinely new
build work is *not* infrastructure — it is the four blockers, all of which are repo defects.

---

## Runtime State Inventory

This is a **new-environment** phase rather than a rename, but the same question applies: what
state exists outside the repo that a `kubectl apply` will not create?

| Category | Items found | Action required |
|----------|-------------|------------------|
| **Stored data** | Managed Postgres will be empty. Flyway creates schema; **seeded rows for DPLY-01 do not exist anywhere yet**. Keycloak's own database (D-02) is a second database on the same server. The `postcode_centroid` importer loads 1,748,230 rows from a classpath resource at startup — measure its effect on a B2s/32 GiB server and on startup time | Decide and build the staging seed path (Claude's discretion, per CONTEXT.md); create the `keycloak` database before first Keycloak boot |
| **Live service config (not in git)** | **Netlify DNS zone for olajay.co.uk** — 4 A records added by hand, invisible to CI. **Azure resources** — cluster, IP, server, cache, firewall rules, `azure.extensions` allowlist, `max_connections`: none of these are in the repo. **GitHub repo `vars` and `secrets`** — `DEPLOY_STAGING_ENABLED`, `FRONTEND_PUBLIC_*`, the Entra client/tenant/subscription ids. **Keycloak realm** — imported once, then mutated in the KC database (volume drops are a no-op; `--override true` is the re-import path) | Record every one in a runbook page (D-18's one page is the natural home); no plan step may assume a value it did not create |
| **OS-registered state** | None — no VMs under our management, no cron on a host. The pg-backup CronJob is a k8s object and IS in git | None — verified: `k8s/base/pg-backup-cronjob.yaml` is the only scheduled thing and it is a manifest |
| **Secrets / env vars** | 8 Secrets bootstrapped out-of-band, names verified from `scripts/k8s-local-secrets.sh`: `postgres-credentials` (host, port, database, username, password, backup-username, backup-password), `redis-credentials` (password), `rabbitmq-credentials` (username, password, stomp-login, stomp-passcode), `keycloak-credentials` (admin-username, admin-password, frontend-client-secret), `nextauth` (secret), two S3/MinIO credential Secrets (access-key, secret-key), notification (unsubscribe-signing-secret), stripe (api-key, webhook-secret). **New for this phase:** Gmail app password for Alertmanager, Grafana admin password, `s3-backup-credentials` for the real AWS bucket | Extend the bootstrap script for staging; follow `docs/runbooks/credential-rotation.md` |
| **Build artefacts** | GHCR images `ghcr.io/bralabee/jtoye-{core-java,edge-go,frontend}` — **package visibility on GHCR is not verifiable from this host** and determines whether an `imagePullSecret` is needed | Check the GHCR package visibility before first deploy; if private, create the pull secret in the bootstrap |

**Nothing found in "OS-registered state":** confirmed by inspection of `k8s/base/` — the only
scheduled workload is the pg-backup CronJob, which is a committed manifest.

---

## Common Pitfalls

### Blocker A — The `frontend` OIDC client does not exist in the realm (breaks DPLY-01)

**What goes wrong:** `k8s/base/configmap.yaml:212` sets `keycloak.client-id: "frontend"` and
`k8s/staging/configmap-patch.yaml` **deliberately does not override it**. The realm template has
**no client named `frontend`** — measured, the complete client list is `account`,
`account-console`, `admin-cli`, `broker`, `core-api`, `edge-api`, `realm-management`,
`security-admin-console`, `integration-catalog-ro`, `integration-orders-rw`. The sign-in redirect
therefore starts an OIDC flow against a client that does not exist and Keycloak refuses it.

**Why it happens:** the repo already knows. `k8s/base/configmap.yaml:198` says in as many words:
*"it made the local ingress login IMPOSSIBLE. The dev realm … has NO client named `frontend`."*
Only `k8s/local` patches the key. Staging inherits the broken value. Compounding it,
`infra/keycloak/README.md` documents a `frontend` client that has not existed in the template for
some time — so the README agrees with the ConfigMap and both disagree with the JSON.

**How to avoid:** either patch `keycloak.client-id: "core-api"` into
`k8s/staging/configmap-patch.yaml` (what local does), or add a real `frontend` public client to
the staging realm. Whichever is chosen, `redirectUris` must include
`https://app-staging.olajay.co.uk/*` and `webOrigins` must not stay `*`.

**Warning signs:** a Keycloak login page that renders and then errors on submit; `invalid_client`
in the KC log. Note this is **invisible to every gate** — the render is valid, the ConfigMap key
exists, the env is injected, the value is a non-empty string.

**Fail-direction arm:** point `keycloak.client-id` at a deliberately absent client
(`does-not-exist`) and confirm the login proof goes red. If it does not, the proof is not testing
the login.

---

### Blocker B — `check-alert-liveness.sh` cannot run against k8s (breaks DPLY-03 literally)

**What goes wrong:** DPLY-03's criterion is *"`check-alert-liveness.sh` + `check-alert-metrics.sh`
exit 0 against the staging target"*. Measured: `check-alert-metrics.sh` contains **0** `docker`
references and needs only `PROM_URL` — it works through a port-forward today.
`check-alert-liveness.sh` contains **13**, and two assertions are hard-bound to the compose
runtime:

- **L-0** calls `command -v docker`, `docker inspect "$PROM_CONTAINER"` and
  `docker exec … md5sum`, and each failure path is `void` (exit 2). Against a k8s Prometheus this
  exits **2 (VOID)**, which the script's own doctrine says is *never* clean.
- **L-3** requires the alert destination to be inspectable via the **Mailhog HTTP API**
  (`$MAILHOG_URL/api/v2/search`) and VOIDs if it is not. D-17 makes the real destination **Gmail**,
  which has no such API.

**How to avoid:** two changes, both additive.
1. Give L-0 a runtime-agnostic reader: an env-selected `PROM_EXEC` (default `docker exec`,
   overridable to `kubectl exec -n <ns> deploy/prometheus --`). Keep the byte-exact md5 compare —
   that part is right and the header explains why a semantic compare is worse.
2. Make L-3's destination inspectable **without bypassing the real route**: give the single
   Alertmanager receiver **two `email_configs` entries** — the Gmail relay *and* the in-cluster
   Mailhog. Both are sent by the same route, same grouping, same template, so L-3 still proves
   what it claims, and the script's explicit rejection of "a probe-only route with group_wait 0s"
   is respected. `[ASSUMED — verify that Alertmanager sends to every email_config in a receiver;
   the config schema is a list, which is the basis for this recommendation]`

**Warning signs:** a phase SUMMARY that records `check-alert-liveness.sh` **exit 2** and reads it
as "environment not ready". It is not — it is the criterion failing.

---

### Blocker C — `BYPASSRLS` is impossible on PostgreSQL ≤ 15 Flexible Server (breaks DPLY-04 arm A)

**What goes wrong:** `infra/backups/create-backup-role.sql` runs `CREATE ROLE jtoye_backup LOGIN
BYPASSRLS`, and its own header says *"The BYPASSRLS attribute can only be granted by a
superuser"*. On Azure Flexible Server the admin login is `azure_pg_admin`, a **pseudo-superuser**;
only Microsoft holds the real superuser role. Microsoft documents that on **PostgreSQL 15 and
earlier you cannot create non-admin users with BYPASSRLS**, and that **PostgreSQL 16 removed the
superuser requirement**, so `azure_pg_admin` can create BYPASSRLS roles there.

Without `jtoye_backup`, the logical dump runs as a FORCE-RLS-subject role and captures **zero
rows from every tenant-scoped table** — the exact defect `create-backup-role.sql` was written to
fix, and precisely the shape of DPLY-04's arm A. A green dump over an empty database is what arm
A exists to catch.

**How to avoid:** provision the Flexible Server at **version 16 or later**. The pre-existing
`snackpass-pg` in this very resource group is already version 16 `[VERIFIED: az postgres
flexible-server show, 2026-08-10]`, so this is the default anyway. Record the choice as a
requirement, not a preference. `CLAUDE.md` says the stack targets PostgreSQL 15 and
`infra/dependency-horizons.yaml` pins `postgres:15-alpine` for compose — so this introduces a
**deliberate dev/staging version skew** that must be written down (or compose moved to 16 in a
separate change).

**Two more measured constraints on the same server:**
- `azure.extensions` on the live server is `vector,pgcrypto`. `V1__base_schema.sql:6` runs
  `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"` (the sole exemption in
  `scripts/check-no-create-extension.sh`, which passes today: 61 files, 1 exempted occurrence).
  **`uuid-ossp` must be added to the `azure.extensions` allowlist before the first Flyway run**,
  or V1 fails and nothing else runs.
- Azure reserves **15** connections for replication/monitoring;
  `k8s/scripts/check-connection-math.sh` assumes `RESERVED=3`. On B2s (429 total / 414 user) the
  155-connection budget still fits comfortably; on B1ms (50/35) it does not, by a factor of 3.

**Fail-direction arm (this is arm A):** produce a dump with the tenant tables empty — or run the
dump as `jtoye_app` without the GUC — and confirm it still clears `MIN_BACKUP_BYTES` and
`pg_restore --list`. 26-07's L4 measured exactly that (`products 0` while clearing the byte floor
by 149×).

---

### Blocker D — Under an enforcing CNI, the current NetworkPolicies deny every managed datastore (breaks DPLY-01 *and* DPLY-05 together)

**What goes wrong:** `k8s/base/networkpolicies/20-core-java.yaml` permits exactly three egress
shapes: kube-dns; the `jtoye-infrastructure` **namespace** on the datastore ports; and
`ipBlock: 0.0.0.0/0` **with RFC1918 excluded**, **port 443 only**.

D-09 moves Postgres and Redis **out of the cluster**. Neither is in a `jtoye-infrastructure`
namespace, so those rules do not apply. And:

- a **public-access** Flexible Server is a public IP on port **5432** → the 443-only rule denies it;
- a **private-access** (VNet-injected) server is inside `10.0.0.0/8` → excluded by `except[]`;
- Azure Cache Basic is TLS on **6380** → denied by the same 443-only rule.

Result: every `core-java` replica CrashLoops with a NetworkPolicy denial while the logs point at
the database layer. **This is #271's exact failure shape, recurring through a different door** —
which is precisely why #271 (now CLOSED) is still listed in DPLY-02.

Layer on the **Cilium-specific** limitation: `ipBlock` *cannot select pod or node IPs*, and
Microsoft's FAQ states that even `cidr: 0.0.0.0/0` still blocks them; the documented workaround is
to add `namespaceSelector: {}` / `podSelector: {}` peers alongside the `ipBlock`.

**How to avoid:**
1. Add a dedicated egress rule per out-of-cluster datastore, addressed by `ipBlock` on the
   resolved service IP (or a permissive `0.0.0.0/0` with the RFC1918 `except[]` *removed* only for
   the private-access case) on the specific ports.
2. **Derive the ports from config, exactly as #271 taught.** `db.port` already drives the Postgres
   port through a kustomize `replacements:` block repeated in base *and* each overlay. Redis's
   port must get the same treatment (`redis.port` already exists as an app-config key — the
   `20-core-java.yaml` comment calls routing it "a clean follow-up").
3. **`INV-7` must be updated in the same change.** It asserts the *exact* egress port multiset
   toward `jtoye-infrastructure`:
   `[core-java-allow]="__DB_PORT__ 5672 6379 9000 9093 61613"`,
   `[pg-backup-allow]="__DB_PORT__ 9000"`. Its header states the friction is intentional: *"Adding
   a datastore port to a policy MUST be accompanied by adding it here."*
4. Regenerate `k8s/goldens/staging.yaml` and `production.yaml` deliberately.

**The DPLY-05 proof, and why these are one problem:** a NetworkPolicy set that has been corrected
for the managed endpoints is *also* the thing that makes a denied-connection capture meaningful.
The two-arm proof:

- **Allowed arm:** a probe pod carrying `app=core-java` labels connects to the Postgres FQDN:5432
  and succeeds.
- **Denied arm:** the same probe pod from an *unlabelled* pod (or to a port not in the allow-list)
  returns `TIMEOUT` — Microsoft's own agnhost recipe, whose whole output on success is silence and
  on denial is the literal string `TIMEOUT`.

Run the denied arm **first**: on the pre-Cilium tree (or with `--network-policy none`) it must
*succeed*, proving the probe can connect at all. Otherwise a broken probe reads as a perfect
security posture. This is the `feedback_suspect_the_instrument_first` shape exactly.

---

### Pitfall 5 — `configuration-snippet` security headers are silently dropped on a fresh ingress-nginx

**What goes wrong:** `k8s/base/ingress.yaml` sets HSTS, `X-Frame-Options`, `nosniff`,
`Referrer-Policy` and `Permissions-Policy` through
`nginx.ingress.kubernetes.io/configuration-snippet`. Since v1.9, `allow-snippet-annotations`
defaults to **false** (the CVE-2021-25742 mitigation). A default install therefore serves every
page **without those headers**, while the Ingress object shows them and every HTTP check returns
200.

**How to avoid:** either set `allow-snippet-annotations: "true"` in the controller ConfigMap
(and accept the CVE class in a single-tenant cluster, recorded), or re-express the headers as
first-class annotations / a controller-level `add-headers` ConfigMap.

**Warning signs:** none, by construction. **Falsify it directly:**
`curl -sI https://app-staging.olajay.co.uk | grep -i strict-transport-security` and require a hit.

---

### Pitfall 6 — Staging's `replicas: 2` is a lie; the base HPA wins

**What goes wrong:** `k8s/staging/kustomization.yaml` sets `replicas: 2` for core-java, edge-go
and frontend. The kustomize `replicas:` transformer **does not touch HorizontalPodAutoscaler or
PodDisruptionBudget** — measured in Phase 26 and recorded in `k8s/local/scale-patch.yaml`. The
base HPAs carry `minReplicas: 3` (core-java), `5` (edge-go), `3` (frontend), so staging runs 11
app pods, not 6.

**Cost of getting this wrong:** at base minima the app tier alone requests
**2.6 vCPU / ~4.1 GiB** (core-java 3 × 500m/1Gi, edge-go 5 × 100m/64Mi, frontend 3 × 200m/256Mi).
Add Keycloak, RabbitMQ, Prometheus, Grafana, Alertmanager, two exporters, Mailhog, the ingress
controller, cert-manager and the RabbitMQ operator and the estate needs roughly
**4.3 vCPU / 8.1 GiB of requests** — which does not fit on any node pool inside the £150 ceiling.

**How to avoid:** create `k8s/staging/scale-patch.yaml` mirroring `k8s/local/scale-patch.yaml`
(HPA `minReplicas: 1`, PDB `minAvailable: 1`, and consider lowering `maxReplicas`). That drops app
requests to ~800m / 1.32 GiB and the whole estate to roughly **2.5 vCPU / 5.3 GiB**, which fits
3 × B2s (≈5.7 vCPU / ≈7.2 GiB allocatable after AKS reservations).

**Note the gate blindness:** `check-connection-math.sh` reads `maxReplicas` from
**`k8s/base/core-java-deployment.yaml`** and `max_connections` from
**`docker-compose.full-stack.yml`**. It will stay green regardless of what the staging overlay
does and regardless of the managed server's real limit. Live run today:
`max_connections=200 … 11 replicas x pool 12 + keycloak(20)+backup(1)+exporter(2) = 155 -> OK
(<= 157)`. A **£150-ceiling B1ms server offering 50** would pass that gate and CrashLoop in
production. Extending the gate to read a declared staging value is cheap and closes the class.

---

### Pitfall 7 — Redis Basic disables the non-TLS port, and the app has no TLS switch

**What goes wrong:** `application.yml` configures `spring.data.redis` with `host`, `port`,
`password`, `timeout`, `lettuce.pool` — and **no `ssl:` block**. Azure Cache for Redis serves TLS
on **6380** and the plaintext **6379** port is disabled by default. The k8s Deployment injects only
`REDIS_HOST` and `REDIS_PASSWORD`.

**How to avoid:** add `ssl: { enabled: ${REDIS_SSL:false} }` under `spring.data.redis`, a
`redis.ssl` app-config key, and the matching env — config-injection compliant per GLOBAL_RULE_6,
default off so compose behaviour is byte-identical. Update the `redis_exporter` DSN to
`rediss://…:6380`. Enabling the plaintext port on the cache instead is the wrong fix and Azure
warns against it.

**Why no gate sees this:** `check-env-contract.sh` asserts injected↔read in both directions. A
capability that is *absent from both sides* is invisible to it.

---

### Pitfall 8 — PITR creates a *second* billable server, and copies almost nothing

**What goes wrong:** PITR "always creates a new database server"; it does **not** overwrite.
Server **parameters are not applied** to the new server, **firewall rules are not copied**, and
you **cannot restore across public and private access**. On Burstable, **on-demand backup is not
supported** (so the drill must use a timestamp, not "back up now"). The restored server bills at
the source SKU until deleted.

**How to avoid:** script the drill end-to-end — restore, re-apply firewall rule, connect, count
rows, compare, **delete the restored server** — and put the delete in a `trap`. Budget for a few
hours of a second B2s (~£0.06/hr).

---

### Pitfall 9 — `HighMemoryUsage` / `FrequentGarbageCollection` re-acquire the wrong JVM

**What goes wrong:** finding F-3c in Phase 27 was that these two rules had unqualified `jvm_*`
selectors and bound to **Keycloak's** JVM while carrying `service: core-java`. Phase 27's fix
deleted the static `service:` label so the series' own label wins. In staging, Keycloak is
**in-cluster and scraped** for the first time, so both JVMs are present again. This is exactly
what L-2b (subject correctness) exists to catch — expect it to have opinions, and read them.

---

### Pitfall 10 — `NoOrdersCreated` is blind after every restart

**Recorded trap (`trap_rebuild_reds_alert_metrics`):** the series behind it is a *request* counter
that does not survive a JVM restart, so `check-alert-metrics.sh` M-1 goes red on any freshly
deployed core-java until an order is placed. The committed fix is `scripts/seed-order-metric.sh`.
On staging this must run against the ingress hostname, not localhost. Do not diagnose it; run it.

---

## Code Examples

### Verify network policy enforcement (the DPLY-05 two-arm proof)

```bash
# Source: learn.microsoft.com/en-us/azure/aks/use-network-policies (verification recipe)
kubectl create namespace demo
kubectl run server -n demo --image=k8s.gcr.io/e2e-test-images/agnhost:2.33 \
  --labels="app=server" --port=80 --command -- \
  /agnhost serve-hostname --tcp --http=false --port "80"
kubectl run -it client -n demo --image=k8s.gcr.io/e2e-test-images/agnhost:2.33 --command -- bash

# CONTROL ARM FIRST — with no policy, this must SUCCEED (silence = connected).
/agnhost connect <server-ip>:80 --timeout=3s --protocol=tcp

# Apply a policy admitting only app=client, then re-run from the UNLABELLED client:
#   -> TIMEOUT            <- this string IS the captured denial
# Label the client and re-run:
#   -> (no output)        <- allowed
kubectl label pod client -n demo app=client
```

### RabbitmqCluster with STOMP, pinned image, external credentials

```yaml
# Source: rabbitmq/cluster-operator docs (additionalPlugins, secretBackend.externalSecret)
#         + internal/resource/service.go (stomp -> port 61613 on the client Service)
apiVersion: rabbitmq.com/v1beta1
kind: RabbitmqCluster
metadata:
  name: jtoye-rabbitmq
spec:
  replicas: 1
  image: rabbitmq:4.3.4-management-alpine   # matches compose; resolves the horizon row
  secretBackend:
    externalSecret:
      name: rabbitmq-credentials            # operator waits for it; keys: username, password
  rabbitmq:
    additionalPlugins:
      - rabbitmq_stomp                      # adds port 61613 named "stomp" to the client Service
  resources:
    requests: { cpu: "250m", memory: "512Mi" }
    limits:   { cpu: "1",    memory: "1Gi"   }
```

### cert-manager ClusterIssuer for HTTP-01 behind the ingress

```yaml
# Source: cert-manager v1.21.1 ACME HTTP-01 solver shape
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging          # USE THIS FIRST — see note below
spec:
  acme:
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    email: <owner>
    privateKeySecretRef: { name: letsencrypt-staging-account-key }
    solvers:
      - http01:
          ingress:
            ingressClassName: nginx
```

**The staging-vs-prod issuer question, answered:** Let's Encrypt production enforces rate limits
(notably duplicate-certificate limits per week) and a failed HTTP-01 order burns quota. Issue
against `letsencrypt-staging` until a certificate is actually produced, then switch the
annotation to `letsencrypt-prod`. `k8s/base/ingress.yaml` currently hardcodes
`cert-manager.io/cluster-issuer: "letsencrypt-prod"`, so switching means either an overlay patch
or a config-injected key — the latter is more in keeping with GLOBAL_RULE_6.

**Also non-negotiable, and the repo already says so:** `k8s/base/ingress.yaml`'s comment block
explains that all SANs share one `jtoye-tls` Secret issued as a single order, and **a failed
challenge fails the whole order**. Adding `auth-staging.olajay.co.uk` to the SAN list before its
DNS A record exists would stall issuance for `api-` and `app-` too. Records first, SAN second.

### GitHub Actions → Azure OIDC (replacing `secrets.KUBE_CONFIG_STAGING`)

```yaml
# Source: docs.github.com/actions/.../configuring-openid-connect-in-azure + Azure/login
permissions:
  id-token: write        # WITHOUT THIS no OIDC token is issued and azure/login fails
  contents: read
steps:
  - uses: azure/login@v2
    with:
      client-id:       ${{ secrets.AZURE_CLIENT_ID }}
      tenant-id:       ${{ secrets.AZURE_TENANT_ID }}
      subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}
  - run: az aks get-credentials -g jtoye-rg -n jtoye-staging-aks --overwrite-existing
```

```bash
# Federated credential — subjects are EXACT MATCH, no wildcards.
# deploy-staging runs on main AND declares `environment: staging`, so the
# environment subject is the correct (and tighter) one.
az identity federated-credential create \
  --name gh-deploy-staging --identity-name jtoye-ci --resource-group jtoye-rg \
  --issuer  https://token.actions.githubusercontent.com \
  --subject "repo:Bralabee/JToye_OaaS_2026:environment:staging" \
  --audience api://AzureADTokenExchange
```

The existing `deploy-staging` job's `Configure kubeconfig` step (`echo "${{
secrets.KUBE_CONFIG_STAGING }}" | base64 -d`) is deleted and replaced by the two steps above.
Everything downstream (`kustomize edit set image`, the render assertion, `kubectl apply -k`,
rollout waits, smoke tests, rollback) is already correct and needs no change.

---

## State of the Art

| Old approach | Current approach | When changed | Impact on this phase |
|--------------|------------------|--------------|----------------------|
| ingress-nginx as the default Kubernetes ingress | **Retired.** No releases, no bugfixes, **no security fixes** after March 2026. Its planned successor InGate was also retired. Kubernetes recommends **Gateway API** | announced 2025-11-11, effective 2026-03 | Deploying it on a new public-facing staging in Aug 2026 ships an unpatched edge component. Last release `controller-v1.15.1`, 2026-03-19 `[VERIFIED: GitHub releases API]` |
| — | **AKS application-routing add-on**: Microsoft patches its NGINX **through November 2026**; Gateway-API-based app routing (Istio-powered) planned for H1 2026 | 2025-11-13 AKS blog | Either path needs a dated `infra/dependency-horizons.yaml` row and a recorded migration deferral. Neither is "fine" |
| Azure NPM as the AKS network policy engine | **Cilium** (Azure CNI powered by Cilium). NPM Linux retires **2028-09-30**, NPM Windows **2026-09-30**; kubenet retires **2028-03-31** | announced 2026 | Chooses the CNI at create time. Also brings the `ipBlock` limitation in Blocker D |
| `--enable-ebpf-dataplane` | `--network-dataplane cilium` | aks-preview → GA | Use the current flag; verified present in `az` 2.89.0 |
| BYPASSRLS requires superuser | **PostgreSQL 16 removed the superuser requirement**; Azure exposes it to `azure_pg_admin`-created roles from PG16 | PG16 upstream | Blocker C — decides the server version |
| Azure Cache for Redis Basic/Standard/Premium | **Retires 2028-09-30** in favour of Azure Managed Redis (Enterprise retires 2027-03-30); a CLI migration path lands in phases from Feb 2026 | 2026 | Not blocking. Add a note to ADR-0002's addendum and a horizon row |
| Prometheus 2.x | Prometheus **3.13.2** current | 3.0 in 2024 | Deliberately **not** adopted here — D-16 says one corpus; the exemption expires 2026-12-31 and the upgrade is its own change |
| `docker compose` monitoring | Same corpus, k8s manifests | this phase | The gate scripts, not the manifests, are the hard part |

**Deprecated / outdated in-repo claims corrected by this research:**
- `k8s/base/networkpolicies/50-observability.yaml` says "We do NOT ship those workloads from this
  repo" and ships an inert placeholder policy. D-16 makes that false; the file's own comment says
  to *replace its contents* when the workloads land.
- `k8s/base/ingress.yaml` and #296 both assume Keycloak is an external managed IdP. D-02 makes
  that false; the host rule + TLS SAN come back **with** the Service and Deployment, in that order.
- `infra/keycloak/README.md` documents a `frontend` client that does not exist (Blocker A).
- The ROADMAP lists #292, #293, #302 and #271 as open. All four are **CLOSED** — see below.

---

## The 12+4 DPLY-02 issues — measured dispositions

Measured with `gh issue view` on 2026-08-10. **Four are already closed**, which materially
shrinks DPLY-02.

| # | State | What closing it concretely requires *in this architecture* |
|---|-------|-----------------------------------------------------------|
| **#99** deploy gate is theatre | OPEN | Replace the kubeconfig secret with Azure OIDC (D-04), point at the real AKS, flip `DEPLOY_STAGING_ENABLED=true`, set `vars.FRONTEND_PUBLIC_API_URL=https://api-staging.olajay.co.uk`. The job's other named defects (branch gate, image drift, kustomize bypass, Swagger smoke test) were **already fixed** in Phase 26 — the remaining half is that it has never run. Closing evidence = one green deploy run |
| **#100** sealed secrets half-landed | OPEN | The plaintext template was already removed from the kustomize resources (`secrets-template.yaml.example`, guarded by `check-no-plaintext-secrets.sh`). What remains is committing SealedSecrets + installing the controller. **Recommend defer with reason**: a single-operator staging cluster gains little, and it adds a controller, a keypair to back up, and a second failure mode on day one. Record the reason; keep the bootstrap-script path |
| **#292** one frontend image per env | **CLOSED 2026-08-04** | No work. Record as already-closed with the closure date |
| **#293** CSP omits IdP origin | **CLOSED 2026-08-04** | No work — but **verify the fix on the real ingress**: `auth-staging.olajay.co.uk` must appear in the served `form-action`/`connect-src`. Remember `trap_csp_realm_path_matching`: a CSP source **with a path** matches exactly unless it ends `/` |
| **#299** customer realm unconfigured | OPEN | Needs `CUSTOMER_KC_ISSUER_URI`, `CUSTOMER_JWT_EXPECTED_ISSUER` **and** a `NEXT_PUBLIC_*` (build-time). Its `check-env-contract.sh` allowlist entry already says why half-wiring is worse than absence. **Recommend defer with reason** unless the storefront is in the DPLY-01 proof — the vendor login is |
| **#301** no mcp-server manifests | OPEN | A new Deployment/Service/HPA/PDB/NetworkPolicy + a scoped Keycloak client + an ingress decision + a connection-budget line. **Recommend defer with reason**: it adds a pool to a budget already at 155/157 and an ingress surface DPLY-01 does not need |
| **#302** logback FileAppender | **CLOSED 2026-08-03** | No work. Confirm no `FileNotFoundException` in the staging boot log as a cheap regression check |
| **#271** NetworkPolicy egress vs `DB_PORT` | **CLOSED 2026-08-04** | Closed for the in-cluster case via the `replacements:` + INV-7 mechanism. **Its failure class recurs in this phase through out-of-cluster endpoints — Blocker D.** Do not re-open it; do extend the same mechanism |
| **#300** sealed-secrets for local | OPEN | Sibling of #100; same disposition |
| **#304** stomp-relay.spec.ts | OPEN | Its own acceptance says "passes against a real login, creates an order without edge-go, and **FAILS (not skips)** if the relay is unavailable". The ingress this phase builds unblocks it, but it is a *design* task with four decisions. **Recommend defer with reason** unless the KDS relay is part of the DPLY-01 proof |
| **#592** one-click unsubscribe | OPEN | Genuinely cheap: one `NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL` env beside the existing `NOTIFICATION_UNSUBSCRIBE_BASE_URL` at `core-java-deployment.yaml:446`, from a new app-config key set per overlay to that overlay's `api.url`. **Its own text demands the fail-direction arm first**: extend `UnsubscribeLinkRoutingTest` to read what the manifests supply and confirm it FAILS on the current tree before wiring |
| **#294** SES + bucket unverified | OPEN | **Bucket half closes this phase (D-11).** ⚠ **Measured: no AWS credentials on this host** (`aws sts get-caller-identity` → "Unable to locate credentials"), so it stays UNVERIFIABLE-FROM-THIS-HOST exactly as Phase 26 recorded. The plan needs an explicit operator credential step before the check. SES half defers to Phase 32 per D-13 |
| **#98** observability demo-grade | OPEN | DPLY-03 *is* this issue. Its three acceptance criteria (Prometheus scrapes all three services in a prod-shaped deploy; every alert references an emitted metric; logs aggregated + traces edge→core) are **not all in scope** — logs/tracing are not in D-16. Close the first two, defer the log-aggregation/tracing third with a reason |
| **#112** ops readiness | OPEN | D-18: paging path + one runbook page. SLOs defer |
| **#101** PITR | OPEN | DPLY-04. Also requires correcting `docs/architecture/SYSTEM_DESIGN_V2.md:657`'s false "WAL-G to S3 (PITR)" claim — the issue names that as a separate acceptance criterion |
| **#297** enforce NetworkPolicies | OPEN | DPLY-05. Note its scope is *local minikube Calico*; this phase satisfies the intent on AKS instead. Its warning that the minikube host gateway sits in `192.168.0.0/16` is the same `except[]` trap as Blocker D. Close-or-retarget with a reason |
| **#296** in-cluster Keycloak ingress | OPEN | D-02 makes its condition live: restore the host rule **and** the TLS SAN, **together with** the Service and Deployment, in that order. INV-6 asserts every Ingress backend resolves to a Service in the same render, with an empty allowlist — so getting the order wrong fails CI, which is the design |

---

## Capacity and cost math (measured inputs, computed conclusions)

All unit prices `[VERIFIED: https://prices.azure.com/api/retail/prices, currencyCode=GBP,
armRegionName=uksouth, retrieved 2026-08-10]`. Monthly = hourly × 730.

| Line | Unit | Monthly |
|------|------|---------|
| AKS control plane, **Free** tier | £0 | **£0** (Standard Uptime SLA would be £0.0758/hr = £55.33) |
| 3 × `Standard_B2s` nodes | £0.0358/hr each | **£78.40** |
| PostgreSQL Flexible **B2s** Burstable | £0.0576/hr | **£42.05** |
| Postgres storage, 32 GiB | £0.1008/GiB/mo | **£3.23** |
| Azure Cache for Redis **Basic C0** | £0.0212/hr | **£15.48** |
| Standard static IPv4 | £0.0038/hr | **£2.77** |
| Node OS disks + RabbitMQ PVC (est.) | — | **~£5** |
| **Total new estate** | | **≈ £147/mo** |

**And the finding that changes the plan:** `jtoye-rg` already runs a `snackpass-*` Azure Container
Apps estate — six apps, all `minReplicas: 1`, all `Running` — plus `snackpass-pg` (B1ms, PG16,
32 GiB, 7-day PITR, public access) and a Log Analytics workspace. Cost Management reports for
month-to-date (1–10 Aug):

```
Azure Container Apps            £30.09  GBP
Azure Database for PostgreSQL   £0.00   GBP     <- likely a free-tier offer; verify before relying on it
Log Analytics                   £0.00   GBP
```

≈ **£3.17/day → ≈ £95/month** already committed. **£147 + £95 = £242/mo, well over D-03's £150.**
The plan must therefore open with an explicit disposition of the snackpass estate (delete, or
scale `minReplicas` to 0, or re-budget with the owner). It is a decision, not a cleanup — the app
names (`java-core`, `go-edge`, `webapp`, `redis`, `minio`) suggest a prior Container Apps
deployment of this same platform, so someone chose it once.

**Alternative sizings if the ceiling moves:** 2 × B2ms = £104.39 (more RAM, *less* CPU: 3.8 vCPU
allocatable vs 5.7); 2 × D2as_v5 = £110.67; 2 × D2s_v3 = £128.33. D-03's suggested "2×D2s-class or
3×B2ms" (£128 or £157 for nodes alone) does not leave room for the datastores.

---

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| `az` CLI | all provisioning | ✓ | 2.89.0 (Cilium overlay needs ≥ 2.48.1) | — |
| Azure login / subscription access | provisioning | ✓ | `Azure subscription 1` = `c483d353-5f61-4587-a790-addb9ab5fb94` | — |
| Resource group `jtoye-rg` | D-01 | ✓ | **uksouth** | — |
| `Microsoft.ContainerService` provider | AKS | ✗ | **NotRegistered** | `az provider register` (idempotent, free, minutes) |
| `Microsoft.Cache` provider | Azure Cache | ✗ | **NotRegistered** | same |
| `Microsoft.Network` provider | static IP / VNet | ✗ | **NotRegistered** | same |
| `Microsoft.DBforPostgreSQL` provider | Flexible Server | ✓ | Registered | — |
| Existing AKS cluster | everything | ✗ | none in the subscription | must be created |
| `kubectl` | apply/verify | ✓ | client v1.33.3, **embedded Kustomize v5.6.0** (matches the CI pin exactly) | — |
| standalone `kustomize` | CI parity locally | ✗ | not on PATH | `kubectl kustomize` is v5.6.0 — same version CI installs |
| `helm` | — | ✗ | — | **Not needed** (D-16 forbids it) |
| `jq`, `curl`, `python3`, `openssl`, `dig` | gate scripts | ✓ | 1.7 / 8.5.0 / 3.12.2 / 3.5.7 / — | — |
| `docker` | `check-alert-rules.sh` (pulls promtool), local stack | ✓ | 29.7.2 | — |
| `psql` / `pg_restore` | DPLY-04 drill | ✓ | **16.14** (matches a PG16 server) | — |
| `aws` CLI | #294 bucket check, S3 backup target | ✓ binary / **✗ credentials** | 1.45.46; `sts get-caller-identity` → "Unable to locate credentials" | **Operator must supply credentials** — no fallback |
| Public DNS for `*-staging.olajay.co.uk` | DPLY-01, HTTP-01 | ✗ | zone live at NS1 (`dns1-4.p05.nsone.net`, SOA `domains+netlify.netlify.com`), **no A records**; all four staging names resolve to nothing (re-measured 2026-08-10) | Manual record creation at Netlify DNS — no automation path (D-07) |
| Gmail SMTP app password | D-17 | ✗ | not present | none — operator step |
| GHCR image visibility | image pull | **unverified** | — | `imagePullSecret` if private |
| kube context | — | only `sipbihs2aks` | ⚠ **EMPLOYER cluster — DO NOT TOUCH** | Every `kubectl` in this phase must pass `--context` explicitly or run after `az aks get-credentials`, never against the ambient default |

**Missing with no fallback (block execution):** AWS credentials for #294; DNS A records; the Gmail
app password; an AKS cluster.
**Missing with fallback:** the three unregistered resource providers; standalone `kustomize`.

---

## Validation Architecture

`workflow.nyquist_validation` is **true** in `.planning/config.json`.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Bash gate scripts (exit 0 clean / 1 violation / 2 VOID) + Playwright 1.62.1 for the browser proof + JUnit 5 for `UnsubscribeLinkRoutingTest` (#592) |
| Config files | `k8s/scripts/*.sh`, `scripts/check-*.sh`, `frontend/playwright.config.ts` |
| Quick run (per task) | `k8s/scripts/check-render-invariants.sh && k8s/scripts/render-golden.sh && k8s/scripts/check-connection-math.sh` |
| Full suite (per wave) | the five `k8s-validate` gates + `scripts/check-dependency-horizons.sh` + `scripts/check-no-create-extension.sh` + `scripts/check-gate-enforcement.sh` |
| Phase gate | Both alert gates exit 0 against staging, both PITR arms recorded, the denied-connection capture recorded, the L7 login URL recorded |

### Phase Requirements → Test Map

| Req | Behaviour | Type | Automated command | Exists? |
|-----|-----------|------|-------------------|---------|
| DPLY-01 | One verbatim URL with `client_id` + encoded `redirect_uri` lands on a rendered dashboard with real rows | e2e | `PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk npx playwright test e2e/dashboard-mobile.spec.ts` (its real-Keycloak login helper is the reusable part) | ⚠ exists but never run against a public host |
| DPLY-01 | Security headers actually served | smoke | `curl -sI https://app-staging.olajay.co.uk \| grep -i strict-transport-security` | ❌ Wave 0 (Pitfall 5) |
| DPLY-01 | CSP names the IdP origin | smoke | `curl -sI https://app-staging.olajay.co.uk \| grep -io "form-action[^;]*"` | ❌ Wave 0 |
| DPLY-02 | Each of the 16 issues closed or deferred **with a written reason** | doc | a checklist file the plan asserts against `gh issue view` state | ❌ Wave 0 |
| DPLY-02 | #592 routing oracle reads what the manifests supply | unit | `./gradlew test --tests '*UnsubscribeLinkRoutingTest*'` | ⚠ exists, must be **extended and shown to fail first** |
| DPLY-03 | Rules see live series of the job they claim | live | `PROM_URL=http://localhost:9090 bash scripts/check-alert-metrics.sh` (via port-forward) | ✅ works unchanged |
| DPLY-03 | Targets up, exporters not blind, transport end-to-end | live | `PROM_URL=… ALERTMANAGER_URL=… MAILHOG_URL=… bash scripts/check-alert-liveness.sh` | ❌ **Blocker B — needs a k8s exec path + an inspectable sink** |
| DPLY-03 | Rules still parse (static half, CI-wired) | static | `./scripts/check-alert-rules.sh` | ✅ |
| DPLY-04 | PITR restore has the same row counts as the source | live | scripted: restore → firewall rule → `psql -c "SELECT count(*) …"` per table → compare → delete | ❌ Wave 0 |
| DPLY-04 | **Arm A** — a zero-row dump still clears `MIN_BACKUP_BYTES` and `pg_restore --list` | live | dump with the GUC unset / as a non-BYPASSRLS role, run the pipeline's own checks, record that they PASS | ❌ Wave 0 |
| DPLY-05 | A denied connection is captured | live | agnhost probe, both arms, `TIMEOUT` recorded | ❌ Wave 0 |
| DPLY-05 | Rendered egress port set matches the declared set | static | `k8s/scripts/check-render-invariants.sh` (INV-7) | ✅ — **must be updated in the same change** |
| all | Deployed runtime matches the branch | live | `scripts/check-runtime-freshness.sh` (compose-shaped today) | ⚠ **cannot see a k8s runtime** — see Open Question 3 |

### Sampling Rate

- **Per task commit:** the three quick k8s static gates (seconds, no cluster needed).
- **Per wave merge:** the full `k8s-validate` five + horizons + extension gates.
- **Phase gate:** both alert gates against staging with exit code and ISO-8601 timestamp recorded
  in the SUMMARY (this is already a required SUMMARY field, per both scripts' headers).

### Wave 0 Gaps

- [ ] `k8s/staging/scale-patch.yaml` — HPA/PDB, without which nothing schedules (Pitfall 6)
- [ ] `scripts/check-alert-liveness.sh` — k8s exec path for L-0, inspectable sink for L-3 (Blocker B)
- [ ] `k8s/staging/configmap-patch.yaml` — `keycloak.client-id` (Blocker A)
- [ ] NetworkPolicy egress rules for out-of-cluster datastores + INV-7 update (Blocker D)
- [ ] `scripts/staging-pitr-drill.sh` — both arms, with a `trap` that deletes the restored server
- [ ] `scripts/check-networkpolicy-enforcement.sh` — the agnhost two-arm probe, control arm first
- [ ] `redis.ssl` config key + `spring.data.redis.ssl` block (Pitfall 7)
- [ ] Regenerated `k8s/goldens/{staging,production}.yaml`
- [ ] `infra/dependency-horizons.yaml` rows for cert-manager, the operator, the ingress controller;
      `rabbitmq-k8s`'s `pin: unknown` replaced (**expires 2026-10-26**)

**Every one of these needs its fail direction run and recorded** — the phase's own doctrine, and
the reason Phase 26 found ~22 unfalsifiable criteria.

---

## Security Domain

`security_enforcement` is `null` in `.planning/config.json` → treated as **enabled**.

### Applicable ASVS Categories

| ASVS category | Applies | Standard control in this phase |
|---------------|---------|-------------------------------|
| V1 Architecture | yes | The Architectural Responsibility Map above; ADR-0002 acceptance |
| V2 Authentication | yes | Keycloak 24 in-cluster; realm parameterised per environment; **`sslRequired` must not stay `external` semantics by accident**; admin credential via the rotation runbook |
| V3 Session Management | yes | NextAuth session cookie over TLS-only hosts; HSTS via the ingress (Pitfall 5) |
| V4 Access Control | yes | Grafana is the **only** publicly exposed monitoring surface (D-19); Prometheus and Alertmanager have no authn of their own and must stay cluster-internal — assert there is no Ingress for them |
| V5 Input Validation | n/a-new | No new API surface in this phase |
| V6 Cryptography | yes | TLS everywhere: Let's Encrypt at the edge; `sslmode` to Postgres; `rediss://` to the cache (Pitfall 7); never hand-roll |
| V7 Error Handling / Logging | yes | Staging keeps `log.level: DEBUG` — confirm no secret material reaches Grafana/stdout |
| V9 Communications | yes | NetworkPolicies **enforced** (DPLY-05); egress narrowed to named ports; no `0.0.0.0/0:443` widening as a shortcut for Blocker D |
| V10 Malicious Code | yes | Third-party manifests pinned by tag **and** recorded sha256; images pinned by tag |
| V14 Configuration | yes | No `kind: Secret` in any kustomize build (`check-no-plaintext-secrets.sh`); secrets out-of-band; `#549`'s staging OpenAPI auth requirement must not regress |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard mitigation |
|---------|--------|---------------------|
| A publicly reachable Prometheus/Alertmanager (no auth by design) | Information Disclosure | D-19: no Ingress; cluster-internal Service only; assert it in the render |
| ingress-nginx snippet annotations enabling secret retrieval across namespaces (CVE-2021-25742) | Elevation of Privilege | `allow-snippet-annotations` defaults **false**; if enabled for the security headers, record the acceptance and restrict who can create Ingress objects |
| An unpatched, retired ingress controller on the internet edge | Tampering / DoS | Dated horizon row + a migration deferral with a review date; the AKS add-on buys until Nov 2026 |
| Grafana shipping with the factory admin password | EoP | The compose file's own comment records this exact defect and that Grafana only applies `GF_SECURITY_ADMIN_PASSWORD` when it **first creates** the user — so verify against the **running instance**, not the manifest |
| A managed Postgres with `publicNetworkAccess: Enabled` and a wide firewall rule | Information Disclosure | Restrict the firewall rule to the AKS egress IP; the measured `snackpass-pg` has public access enabled — do not copy that shape blindly |
| Long-lived kubeconfig in a GitHub secret | Spoofing | D-04: OIDC federated credential, exact-match subject, `id-token: write` scoped to the job |
| Realm import carrying `webOrigins: ["*"]` into a public environment | Tampering | Parameterise `webOrigins` and `redirectUris` per environment — the template's `core-api`/`edge-api` clients both carry `"*"` today |
| Cilium `ipBlock` silently not matching pod/node IPs, producing a policy that looks tighter than it is | Repudiation | The two-arm agnhost proof, control arm first |

---

## Project Constraints (from CLAUDE.md)

Directives the planner must honour, extracted verbatim in substance:

1. **Tech stack is fixed** — Spring Boot 3.5.16, Next.js 16, Go 1.26, PostgreSQL 15, JDK 21.
   ⚠ Blocker C requires **PostgreSQL 16** on the managed server. This is a deliberate,
   *staging-only* deviation and must be written down as such, not slipped in.
2. **Multi-tenancy** — all new features respect RLS and TenantContext. Nothing in this phase
   bypasses that except `jtoye_backup`, whose BYPASSRLS is the documented, least-privilege,
   read-only exception.
3. **Testing** — `docs/metrics.json` is the single source of truth, enforced by **two** gates in
   `docs-freshness.yml`. Bash scripts contribute **0** to those counts (`check-alert-liveness.sh`
   header says so), so gate scripts do not move the numbers. New Playwright `test()` blocks or
   JUnit `@Test` methods **do** — regenerate with `--write`, never by arithmetic
   (`trap_docs_freshness_block_counter`).
4. **Docker** — rebuild ALL containers after code changes before E2E. Staging's analogue is the
   runtime-parity doctrine: `docker compose start` never rebuilds; a staging deploy must prove the
   delivered runtime matches the branch.
5. **Compose is canonical for local dev/E2E; kustomize is the staging/prod deploy target.**
   **XOR applies at local runtime only.** This phase does not change that.
6. **Incremental Betterment Doctrine** — enumerate displaced goods. Naming the specific one here:
   `k8s/base/networkpolicies/50-observability.yaml`'s placeholder policy is displaced by real
   policies; the Keycloak-as-external-IdP assumption in `k8s/base/ingress.yaml` and #296 is
   displaced by D-02. Both must be *replaced*, not just deleted.
7. **Five cross-cutting quality contracts.** Web-perf: **N/A** (no page changes) — record it.
   SEO: **N/A** (staging must not be indexed — in fact, add `X-Robots-Tag: noindex` at the ingress
   and treat that as a positive requirement, since D-08's "nothing may look live before it is"
   applies to crawlers too). Agent-readiness: **N/A** (no API surface change). Security: §Security
   Domain. **Falsifiable evidence + runtime parity: fully in scope and the dominant one.**
8. **Proof standards** — every gate shown to FAIL before it is trusted; assert the clean state
   last as well as first; commit before running break arms; `grep -uu` when a count is evidence;
   never `cmd | grep -q X` under `pipefail`; capture `$?` on the same statement.
9. **GSD workflow** — file changes go through a GSD command.

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|-------|---------|---------------|
| A1 | Alertmanager delivers to **every** `email_configs` entry in a receiver, so a dual Gmail+Mailhog receiver keeps L-3 honest | Blocker B | The L-3 fix does not work; fall back to teaching L-3 an IMAP or webhook-receiver sink. **Verify by measurement before planning around it** |
| A2 | AKS node allocatable ≈ 1.9 vCPU / ~2.4 GiB per B2s after kubelet + system reservations | Capacity math | Node count is wrong; 3×B2s may not fit. Measure `kubectl describe node` on the first node and re-derive before sizing the pool |
| A3 | Postgres `£0.00` month-to-date on this subscription is a free-tier offer rather than deferred billing | Cost math | The real datastore cost is higher than modelled; the £147 estimate moves. Check the offer/credits in the portal |
| A4 | The snackpass Container Apps estate is disposable (a prior deployment attempt of this platform) | Cost math | Deleting it destroys something wanted. **Owner decision, not a planner decision** |
| A5 | GHCR packages `ghcr.io/bralabee/jtoye-*` are public | Environment Availability | Every pod hits `ImagePullBackOff`; an `imagePullSecret` is needed in the bootstrap |
| A6 | pgjdbc 42.7 defaults to `sslmode=prefer`, so a Flexible Server with `require_secure_transport=on` connects without a URL change | Pitfall 7 area | Every DB connection fails on first deploy. Cheap insurance: add an explicit, config-injected `DB_SSL_MODE` regardless of the default |
| A7 | The staging realm can be imported with `--import-realm` at pod start from a ConfigMap, as compose does from a bind mount | Realm parameterisation | Realm import needs a Job or an init container instead; KC is Postgres-backed so a volume drop is a no-op (`reference_keycloak_realm_reimport`) |
| A8 | `az aks create --tier free` incurs no control-plane charge (inferred from the absence of a Free line item in the retail price list, and the presence of a charged "Standard Uptime SLA" meter) | Cost math | £55/mo of unexpected spend |
| A9 | `postcode_centroid`'s 1,748,230-row startup import is tolerable on a B2s server + B2s nodes | Runtime State Inventory | Slow or failed startup on first deploy; the readiness probe budget (`failureThreshold: 30`, 5 min) may not be enough |
| A10 | Let's Encrypt HTTP-01 can complete for all four hosts once A records exist and the controller is reachable on :80 | Ingress + TLS | Certificate issuance stalls; note the shared-SAN single-order failure mode the repo already documents |

---

## Open Questions

1. **What is the seeded-rows story for staging?**
   - Known: DPLY-01 requires "real seeded rows"; CONTEXT.md leaves the path to discretion.
   - Unclear: there is no staging seed path anywhere in the repo. Compose seeds via the dev
     realm + local fixtures; `.planning` memory notes E2E baseline work needed seed fixtures first.
   - Recommendation: a one-shot `Job` running the same fixture path the E2E baseline uses,
     gated so it cannot run twice, and asserted by a row count — not by "the page rendered".

2. **Plain Secrets or SealedSecrets for staging (#100/#300)?**
   - Known: `check-no-plaintext-secrets.sh` already forbids `kind: Secret` in any build; the
     bootstrap-script pattern exists and works (`scripts/k8s-local-secrets.sh`).
   - Unclear: whether the operator wants a sealing keypair to back up on day one.
   - Recommendation: plain Secrets via an extended bootstrap script; defer #100/#300 with the
     reason recorded. Revisit when a second operator exists.

3. **How does the runtime-parity doctrine apply to a k8s runtime?**
   - Known: `scripts/check-runtime-freshness.sh` compares per-service Docker
     `.Metadata.LastTagTime` against the newest commit touching that service's build paths, and
     VOIDs (exit 2) if any built service is missing or not running. It is deliberately absent from
     CI because a runner has no containers.
   - Unclear: a k8s staging deploy has no local Docker at all, so the gate can only ever VOID —
     yet the doctrine ("a phase is not done until the delivered runtime matches the branch") is
     exactly what a staging deploy needs.
   - Recommendation: the k8s analogue is already half-built — the deploy job's premortem guard
     asserts the render pins `:${github.sha}` on all three images before apply. Extend it after
     rollout: read the running pods' image digests and assert they equal the pushed digests for
     that SHA. State plainly that `check-runtime-freshness.sh` is **not** the instrument here.

4. **ingress-nginx or the AKS application-routing add-on?**
   - Known: both are unmaintained-or-time-boxed; self-installed gives full annotation control and
     zero manifest churn; the add-on gets Microsoft patches to Nov 2026 but constrains annotations
     and owns its own ConfigMap.
   - Unclear: whether the `configuration-snippet` headers survive under the add-on.
   - Recommendation: self-install `controller-v1.15.1` for annotation fidelity, record a dated
     horizon row and a Gateway-API migration deferral. Revisit at Phase 32 cutover, not now.

5. **Does `k8s/production` need to keep rendering while staging diverges?**
   - Known: `render-golden.sh` byte-compares **both** goldens; `check-render-invariants.sh` runs
     per target; production hosts stay unresolvable (D-08).
   - Unclear: whether NetworkPolicy/monitoring changes land in `base` (affecting both goldens) or
     in the staging overlay only.
   - Recommendation: fix-the-base wherever the fact is environment-invariant (the out-of-cluster
     egress *shape*), overlay only what genuinely varies (the endpoints). Phase 26's doctrine —
     "hiding a production defect behind a local overlay contradicts the fix-the-base doctrine".

---

## Sources

### Primary (HIGH confidence — measured on this machine, 2026-08-10)

- `az account list`, `az group list`, `az resource list -g jtoye-rg`, `az provider show`,
  `az aks list`, `az containerapp list`, `az postgres flexible-server show`,
  `az postgres flexible-server parameter show` — subscription, RG location, existing estate,
  provider registration states, server SKU/version/network/backup, `max_connections=50`,
  `require_secure_transport=on`, `azure.extensions=vector,pgcrypto`
- `az rest` → `Microsoft.CostManagement/query` API — month-to-date spend by service
- `https://prices.azure.com/api/retail/prices` — all GBP/uksouth unit prices quoted
- `dig` — `olajay.co.uk` NS/SOA/A and all four `*-staging` names
- `az aks create --help` (az 2.89.0) — `--tier`, `--network-dataplane`, `--network-plugin-mode`,
  `--enable-oidc-issuer`, `--node-osdisk-type` all confirmed present
- GitHub releases API — cert-manager v1.21.1, rabbitmq/cluster-operator v2.22.3,
  ingress-nginx controller-v1.15.1 (2026-03-19), prometheus v3.13.2, alertmanager v0.33.1,
  grafana v13.1.3, postgres_exporter v0.20.1, redis_exporter v1.89.0
- `cluster-operator.yml` v2.22.3 downloaded and read — `ghcr.io/rabbitmq/cluster-operator:2.22.3`,
  namespace `rabbitmq-system`, 15 documents including `cert-manager.io/v1` `Certificate`+`Issuer`
- `rabbitmq/cluster-operator` `internal/resource/service.go` — `rabbitmq_stomp` → port 61613
- `gh issue view` × 17 — states, closure dates, bodies
- Repo files read in full or in relevant part: `k8s/base/*`, `k8s/staging/*`, `k8s/local/*`,
  `k8s/scripts/*`, `.github/workflows/ci-cd.yaml`, `scripts/deploy.sh`,
  `scripts/check-alert-liveness.sh`, `scripts/check-alert-metrics.sh`,
  `infra/monitoring/*`, `infra/keycloak/*`, `infra/backups/create-backup-role.sql`,
  `infra/dependency-horizons.yaml`, `core-java/src/main/resources/application.yml`
- Live gate runs: `k8s/scripts/check-connection-math.sh` (exit 0, 155/157),
  `scripts/check-no-create-extension.sh` (exit 0, 61 files, 1 exemption)

### Secondary (HIGH — official vendor documentation)

- https://learn.microsoft.com/en-us/azure/aks/use-network-policies — three engines, NPM
  retirement dates, Calico support caveat, the agnhost verification recipe, LoadBalancer/SNAT caveat
- https://learn.microsoft.com/en-us/azure/aks/azure-cni-powered-by-cilium — create flags,
  limitations, the `ipBlock` cannot-select-pod/node-IPs FAQ, no kube-proxy, ACNS feature matrix
- https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-limits —
  max_connections per SKU, 15 reserved connections, no PgBouncer on Burstable, networking limits
- https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore —
  PITR creates a new server, retention 7–35 days, RPO ~5 min, parameters/firewall not copied,
  no public↔private restore, no on-demand backup on Burstable
- https://learn.microsoft.com/en-us/azure/postgresql/security/security-access-control —
  `azure_pg_admin` is a pseudo-superuser; BYPASSRLS impossible on PG≤15, available on PG16+
- https://learn.microsoft.com/en-us/azure/aks/workload-identity-deploy-cluster — OIDC issuer flags
- https://docs.github.com/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-azure
  — subject patterns, exact-match-no-wildcards, `id-token: write`
- https://www.kubernetes.dev/blog/2025/11/12/ingress-nginx-retirement/ and
  https://www.kubernetes.io/blog/2026/01/29/ingress-nginx-statement/ — retirement, InGate retired
- https://blog.aks.azure.com/2025/11/13/ingress-nginx-update — AKS add-on patched to Nov 2026
- https://learn.microsoft.com/en-us/azure/azure-cache-for-redis/retirement-faq — 2028-09-30

### Tertiary (MEDIUM — corroborated but not read from a single authoritative page)

- `allow-snippet-annotations` defaulting to false since v1.9 as the CVE-2021-25742 mitigation —
  multiple sources agree (Kyverno policy library, ingress-nginx issue #11084, the original
  kubernetes-security-announce advisory). **Verify against the deployed controller's ConfigMap.**
- AKS static-IP annotations `service.beta.kubernetes.io/azure-pip-name` and
  `…/azure-load-balancer-resource-group` — corroborated across Microsoft Learn answers and the
  `static-ip` how-to. Confirm the exact spellings against the docs at implementation time.
- pgjdbc default `sslmode=prefer` — see A6; recommend explicit configuration regardless.

---

## Metadata

**Confidence breakdown:**
- Azure ground truth (RG, providers, existing estate, spend, SKU limits): **HIGH** — measured
  directly against the live subscription with read-only commands
- Repo defect findings (Blockers A–D, Pitfalls 5–7): **HIGH** — read from the committed files, and
  in three of four cases the repo's own comments already state the mechanism
- Prices and the £147 estate: **MEDIUM** — unit prices verified live; the total depends on A2/A3/A8
- Component versions and install artefacts: **HIGH** — release API + the manifests themselves
- ingress-nginx / Cilium / Redis retirement dates: **HIGH** (vendor announcements)
- L-3 dual-receiver fix (A1) and node allocatable (A2): **LOW/MEDIUM** — reasoned, not measured

**Research date:** 2026-08-10
**Valid until:** 2026-09-10 for the Azure facts (pricing and provider state drift; **re-measure
DNS and cost before relying on either**); 2026-08-24 for the ingress-controller recommendation
(the retirement landscape is moving)
