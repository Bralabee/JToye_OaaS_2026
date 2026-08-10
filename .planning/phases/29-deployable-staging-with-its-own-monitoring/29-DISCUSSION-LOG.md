# Phase 29: Deployable Staging, With Its Own Monitoring - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-10
**Phase:** 29-Deployable Staging, With Its Own Monitoring
**Areas discussed:** Hosting target, Domain/DNS/TLS, Datastores (ADR-0002), Monitoring + alerting

---

## Hosting target

| Option | Description | Selected |
|--------|-------------|----------|
| AKS in jtoye-rg | Managed control plane, ~£100-150/mo nodes, network-policy enforcement as a platform option, next to ADR-0002 managed datastores, the Phase 32 shape | ✓ |
| k3s on one Azure VM | ~£30-60/mo, self-managed control plane + hand-rolled Calico, still Azure | |
| Non-Azure VM (Hetzner etc.) | ~£15-40/mo, abandons managed-datastore option, new provider | |

**User's choice:** AKS in jtoye-rg (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| In-cluster Keycloak on AKS | New manifests, realm template import, DB on managed Postgres, one ingress + cert-manager; #296 goes live | ✓ |
| Azure Container Apps | Genuinely external IdP, second compute platform, separate TLS/DNS glue | |
| Defer — reuse dev Keycloak | Tunnel to laptop compose KC; fails the phase's own "outside a laptop" goal | |

**User's choice:** In-cluster on AKS (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| ~£80/mo hard ceiling | Burstable everything, skip managed Redis, tight headroom | |
| ~£150/mo | 2×D2s-class or 3×B2ms + small managed Postgres + Redis Basic; always-on | ✓ |
| ~£250+/mo | Production-adjacent sizing (zone-redundant HA) months before a paying tenant | |

**User's choice:** ~£150/mo (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| CI deploys, real gate | GH Actions → Azure OIDC federated credential → kustomize vs AKS on merge to main; #99 closes for real | ✓ |
| Manual first, CI deferred | scripts/deploy.sh from laptop; deploy knot stays half-tied | |
| CI on manual trigger only | workflow_dispatch middle path | |

**User's choice:** CI deploys, real gate (recommended)

---

## Domain, DNS + TLS

Pre-question live measurement (dig, 2026-08-10): jtoye.co.uk = Namecheap-parked;
olajay.co.uk = registered, zone live at Netlify DNS (NS1), zero A records.

| Option | Description | Selected |
|--------|-------------|----------|
| olajay.co.uk | What every manifest already says (2026-07-27 migration); zone live, just needs records; zero churn | ✓ |
| jtoye.co.uk | Company-name domain, parked; would reverse the July migration across all overlays | |
| Both (split roles) | olajay = product, jtoye = company/landing | |

**User's choice:** olajay.co.uk (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Keep flat -staging.* | api-staging/app-staging/auth-staging.olajay.co.uk as already patched | ✓ |
| Nested *.staging.* | Wildcard-friendly but needs DNS-01 (no Netlify solver) + re-patching every overlay | |

**User's choice:** Keep flat -staging.* (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Static IP + manual DNS, HTTP-01 | One Azure static IP, records added once at Netlify, cert-manager HTTP-01 per host | ✓ |
| Move zone to Azure DNS | IaC records + DNS-01/wildcards, but touches delegation of a zone that may serve the landing site | |
| external-dns + HTTP-01 | Another controller + credentials for records that never change | |

**User's choice:** Static IP + manual DNS, HTTP-01 (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Staging only | Production hosts stay dark until Phase 32; flip DEPLOY_STAGING_ENABLED only | ✓ |
| Staging + production now | Publishes production names months early — refused as untruth | |

**User's choice:** Staging only (recommended)

---

## Datastores (ADR-0002)

| Option | Description | Selected |
|--------|-------------|----------|
| Sign hybrid as proposed | Postgres → Azure Flexible Server (PITR by config), Redis → Azure Cache Basic, RabbitMQ → in-cluster operator; ADR → Accepted | ✓ |
| Sign amended: Redis in-cluster | Managed Postgres only; Redis self-hosted to free ~£13/mo | |
| Fully in-cluster (reject hybrid) | CloudNativePG + WAL to Blob; own the backup engineering | |

**User's choice:** Sign hybrid as proposed (recommended) — this discussion IS the owner sign-off, 2026-08-10

| Option | Description | Selected |
|--------|-------------|----------|
| Real AWS S3 eu-west-2 | The bucket base config names; #294 bucket verification done in-phase | ✓ |
| In-cluster MinIO | Single-cloud but rehearses a path production won't use | |
| Azure Blob | Not viable without code changes (AWS SDK v2) | |

**User's choice:** Real AWS S3 eu-west-2 (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Mailhog-in-cluster capture | Staging app mail captured, never delivered; SES half of #294 defers with reason | ✓ |
| Real SES, verified domain | Closes #294 fully but staging can mail real inboxes | |
| SES sandbox mode | Domain verified, deliveries fenced, bounces look like bugs | |

**User's choice:** Mailhog-in-cluster capture (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| AWS S3 backup bucket | Off-cluster AND off-Azure — genuinely provider-independent second line | ✓ |
| In-cluster MinIO | Backup inside the failure domain it protects | |
| Azure Blob | Same cloud as primary + no native S3 API | |

**User's choice:** AWS S3 backup bucket (recommended)

---

## Monitoring + alerting

| Option | Description | Selected |
|--------|-------------|----------|
| Plain kustomize manifests | Hand-written Deployments mounting the SAME infra/monitoring rule/dashboard corpus; house gates auto-cover | ✓ |
| kube-prometheus-stack (Helm) | Ecosystem standard but introduces Helm + rule-corpus conversion to CRDs | |
| helm template → committed render | Operator benefits, but goldens own thousands of third-party lines | |

**User's choice:** Plain kustomize manifests (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Email via Gmail SMTP | App password → owner's real inbox; free, deliverable today; v2.1 precedent | ✓ |
| Push via ntfy | Faster notice, third-party in the alert path | |
| Email + push both | Two receivers, best notice-time | |

**User's choice:** Email via Gmail SMTP (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal: page + runbook | Named human + one honest runbook page; SLOs defer (theatre on zero traffic) | ✓ |
| Add SLOs now | SLOs for zero-traffic staging measure nothing real | |
| Defer #112 entirely | "Alerts a human" who has no written path to act | |

**User's choice:** Minimal: page + runbook (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Grafana via ingress only | grafana-staging host, own login; Prometheus/Alertmanager internal | ✓ |
| All three via ingress + auth | basic-auth as the whole wall on public hostnames | |
| Nothing public | Phone-check-during-page requires a laptop | |

**User's choice:** Grafana via ingress only (recommended)

---

## Claude's Discretion

- DPLY-02 per-issue dispositions beyond those settled (#292, #293, #299, #301, #302, #271,
  #300, #304, #592) — each closed or deferred with a written reason
- Node SKU/count within the £150 ceiling; managed Postgres tier/version; AKS network-policy
  engine choice
- Staging secrets: plain k8s Secrets vs sealed-secrets (#100/#300)
- Seeded-rows story for DPLY-01; denied-connection proof design for DPLY-05
- Alertmanager routing detail; which compose alert rules apply to staging; Grafana
  dashboard provisioning mechanics
- GHCR image pull path + staging image tag strategy

## Deferred Ideas

- SES sending-domain verification + real app email → Phase 32 (reason recorded)
- SLOs + full runbook set (#112 remainder) → post-GTM
- Production DNS records / cutover → Phase 32
- Azure DNS zone migration / external-dns / wildcard certs → only if record churn becomes real
- ntfy/push alerting beside email → cheap follow-up if email notice-time proves slow
