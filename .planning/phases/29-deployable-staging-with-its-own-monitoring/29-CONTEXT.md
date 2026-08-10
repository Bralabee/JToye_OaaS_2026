# Phase 29: Deployable Staging, With Its Own Monitoring - Context

**Gathered:** 2026-08-10
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers **the first runtime of this platform outside a laptop**: a staging
environment a human can log into and that **alerts a human when it breaks**.

- **DPLY-01** — a real Keycloak vendor login **through the ingress** to a rendered dashboard,
  on a resolvable public hostname, with real seeded rows. Phase 26-08's L7 proof (one verbatim
  URL carrying `client_id` + encoded `redirect_uri`, landing on a rendered dashboard) is the
  template — now against staging, not minikube.
- **DPLY-02** — the deploy knot closed or per-issue deferred **with a reason recorded per
  issue**: #99, #100, #292, #293, #299, #301, #302, #271 (plus the disposition sweep's #300,
  #304, #592, and the DPLY-01/03-mapped #294, #98, #112).
- **DPLY-03** — Prometheus, Alertmanager and Grafana **running in k8s**, with
  `check-alert-liveness.sh` + `check-alert-metrics.sh` exit 0 **against the staging target**.
  Fails on the current tree by construction: `k8s/` ships zero monitoring manifests
  (Phase 27 `deferred-items.md` §5 — everything Phase 27 built is compose-scoped).
- **DPLY-04** — PITR per #101, restore proven **by row count on a restored database, two-arm**
  (arm A: a zero-row dump must still pass the pipeline's own byte-size and `pg_restore --list`
  checks — the 26-07 L4 precedent). Was blocked on ADR-0002 sign-off — **unblocked in this
  discussion: the owner signed the hybrid proposal** (see D-09).
- **DPLY-05** — NetworkPolicies **enforced, not merely rendered** (#297), with a denied
  connection captured as the positive proof. Phase 26 recorded enforcement NOT PROVEN; that
  record must not be quietly inherited as a pass.

**The two ROADMAP-named blockers are now settled** (this discussion was the decision venue):
hosting target = AKS in `jtoye-rg`; production domain = `olajay.co.uk`.

**Out of scope:** production cutover, production DNS records, and anything that makes
production hostnames resolve (Phase 32); the money path (Phase 30); SLOs and the full runbook
set (deferred per D-18); SES sending-domain verification for app email (deferred per D-13).

</domain>

<decisions>
## Implementation Decisions

### Hosting target (ROADMAP blocker #1 — settled)
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

### Domain, DNS + TLS (ROADMAP blocker #2 — settled)
- **D-05 — `olajay.co.uk` is the platform domain.** Confirmed by live measurement 2026-08-10:
  registered, zone live at Netlify DNS (NS1 infra, SOA `domains+netlify.netlify.com`), zero A
  records today; `jtoye.co.uk` is Namecheap-parked. Every k8s manifest already says olajay
  (deliberate 2026-07-27 migration) — zero manifest churn. The stale memory claiming olajay
  "resolves to nothing / may not be registered" is corrected: the zone exists, only records
  are missing.
- **D-06 — Flat `-staging.*` hostname shape kept** exactly as `k8s/staging` patches:
  `api-staging.olajay.co.uk`, `app-staging.olajay.co.uk`, `auth-staging.olajay.co.uk` (new
  ingress for in-cluster Keycloak), plus `grafana-staging.olajay.co.uk` (D-19).
- **D-07 — One Azure static public IP for the ingress controller; DNS records added manually
  once at Netlify DNS; cert-manager Let's Encrypt HTTP-01 per-host certs.** No external-dns,
  no zone migration — cert-manager has no Netlify DNS solver, so HTTP-01 is also the path of
  least resistance. Records are stable; automation buys nothing yet.
- **D-08 — Staging records ONLY this phase.** Production hosts (`api./app.olajay.co.uk`) stay
  unresolvable until Phase 32 decides cutover — nothing may look live before it is. CI: flip
  `DEPLOY_STAGING_ENABLED` + set staging `FRONTEND_PUBLIC_*` values; production flags stay off
  (supersedes the blanket "do not flip DEPLOY_*_ENABLED" hold — the staging half unparks when
  staging DNS resolves).

### Datastores — ADR-0002 SIGNED (DPLY-04 unblocked)
- **D-09 — ADR-0002 hybrid accepted AS PROPOSED, owner-signed 2026-08-10 in this discussion.**
  PostgreSQL → **Azure Database for PostgreSQL Flexible Server** (PITR by configuration; the
  restore drill becomes a rehearsed runbook against provider tooling). Redis → **Azure Cache
  Basic**. RabbitMQ → **in-cluster via the RabbitMQ cluster operator** (STOMP plugin under our
  control; version becomes declared in-repo, resolving the `rabbitmq-k8s` unknown-version
  horizon row in `infra/dependency-horizons.yaml` before its 2026-10-26 expiry). A plan must
  flip the ADR's Status line to Accepted with the date.
- **D-10 — DPLY-04 proof shape:** provider PITR restore to a new server + row-count
  comparison, AND the existing `pg-backup-cronjob` logical dump stays as the
  provider-independent second line. Arm A (zero-row dump passes the pipeline's own checks)
  runs through the logical-dump path per the 26-07 L4 precedent. The BYPASSRLS `jtoye_backup`
  role bootstrap and the Phase 28 owner/runtime role split both apply to the managed server.
- **D-11 — Media storage = real AWS S3 eu-west-2** (the bucket base config already names).
  #294's bucket verification (exists, right region, right policy: public-read derivatives,
  private quarantine prefix) is **done in this phase**, before first deploy, as the issue
  demands.
- **D-12 — Logical-dump destination = a dedicated AWS S3 backup bucket** (eu-west-2, beside
  the media bucket). Off-cluster AND off-Azure — a genuinely provider-independent second line.
  The CronJob already speaks S3.
- **D-13 — App outbound email in staging = in-cluster Mailhog capture.** Vendor
  notifications/onboarding mail are captured and inspectable, never delivered — no accidental
  real mail from a rehearsal environment. The SES half of #294 defers to Phase 32 **with that
  reason recorded**. Alert email (D-17) is real and separate.

### Monitoring + alerting (DPLY-03)
- **D-16 — Plain kustomize manifests, no Helm.** Hand-written Deployments for
  Prometheus/Alertmanager/Grafana + redis/postgres exporters under `k8s/`, mounting the SAME
  rule files and dashboard JSON `infra/monitoring/` already uses — one rule corpus, no
  PrometheusRule CRD conversion, and the existing secret/golden/render CI gates see the new
  kustomization automatically. Exporters point at the managed Postgres/Redis endpoints.
- **D-17 — "Alerts a human" = real email via Gmail SMTP** (`smtp.gmail.com` app password) to
  the owner's real inbox. Free, deliverable today, no SES dependency; matches the v2.1
  email-receiver precedent. The app-password credential is injected via the `.env`/secret
  layer and follows `docs/runbooks/credential-rotation.md` (Phase 28).
- **D-18 — #112 closed minimally: paging path + ONE honest runbook page** (how to reach the
  cluster, read Grafana, restart a workload, run the restore drill). SLOs + the full runbook
  set defer post-GTM **with reasons recorded** — SLOs on a zero-traffic staging measure
  nothing real.
- **D-19 — Grafana alone gets a public hostname** (`grafana-staging.olajay.co.uk`, own login,
  admin credential via the rotation path). Prometheus + Alertmanager stay cluster-internal;
  the two alert gates run via port-forward or a CI job with cluster access. Rationale: the
  "alert fires, human checks a dashboard from a phone" moment needs Grafana reachable;
  Prometheus/Alertmanager have no real authn of their own.

### Claude's Discretion
- **DPLY-02 per-issue dispositions** beyond those settled above — #292 (staging frontend image
  built with staging `NEXT_PUBLIC_*` build args; the mechanism is forced by D-08 + Phase 26
  D-18), #293 (CSP must include `auth-staging.olajay.co.uk` — remember
  `trap_csp_realm_path_matching`: a CSP source with a path matches exactly unless it ends `/`),
  #299 (customer-storefront realm envs), #301 (mcp-server manifests — build or defer with
  reason), #302 (logback FileAppender boot failure), #271 (NetworkPolicy egress port vs
  Secret-driven `DB_PORT`), #300 (sealed-secrets sibling), #304 (ingress-capable
  stomp-relay.spec.ts rework), #592 (one-click unsubscribe env). Each gets closed-or-deferred
  **with a written reason** — none silently dropped.
- Node pool exact SKU/count within the £150 ceiling; managed Postgres tier/version; AKS
  network-policy engine choice (Azure/Calico/Cilium) so long as enforcement is real and the
  DPLY-05 denied-connection proof passes.
- Staging secrets mechanism: plain k8s Secrets via the Phase 26 script pattern vs
  sealed-secrets (#100/#300) — decide in planning against effort; if plain, #100/#300 defer
  with reason.
- The seeded-rows story for DPLY-01 (which seed path runs against staging) and the
  denied-connection capture design for DPLY-05.
- Alertmanager routing detail (group_by/repeat_interval), which of the 19 compose alert rules
  apply to staging, Grafana dashboard provisioning mechanics.
- GHCR image pull path (public images vs imagePullSecret) and staging image tag strategy.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope + requirements
- `.planning/ROADMAP.md` § "Phase 29: Deployable Staging, With Its Own Monitoring" — goal,
  the 5 success criteria, the 12-issue table.
- `.planning/REQUIREMENTS.md:116-122` — DPLY-01..DPLY-05 verbatim.
- `.planning/ISSUE-DISPOSITION.md` § "Phase 29" — the 12 issues and why each is in scope;
  note #296's conditional is now LIVE (in-cluster Keycloak, D-02).

### The signed ADR + datastore surfaces
- `docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md` — **signed as
  proposed in this discussion (D-09)**; a plan flips Status → Accepted, dated 2026-08-10. Its
  2026-07-29 addendum holds the `rabbitmq-k8s` horizon-row context.
- `infra/dependency-horizons.yaml` — the `rabbitmq-k8s` row (pin unknown, expires 2026-10-26)
  that D-09's in-cluster operator deployment resolves.
- `docs/runbooks/rabbitmq-broker-upgrade.md` — broker version/upgrade constraints (dev moved
  3.12.14 → 4.3.4 by fresh install; that path is for empty brokers only).
- `infra/backups/create-backup-role.sql`, `infra/backups/k8s-backup.sh`,
  `infra/backups/Dockerfile` — the logical-dump second line D-10/D-12 reuse.
- `docs/runbooks/backups.md` — the restore runbook the DPLY-04 drill extends.

### Deploy layer (the manifests this phase changes)
- `k8s/staging/` — the overlay this phase brings to life: `kustomization.yaml`,
  `configmap-patch.yaml` (auth-staging/api-staging/app-staging URLs, `jtoye-staging` realm),
  `ingress-hosts-patch.yaml` + `sse-ingress-hosts-patch.yaml` (JSON6902 index-coupled host
  patches — their header comments explain why).
- `k8s/base/` — ingress.yaml + sse-ingress.yaml (olajay hosts, `jtoye-tls` SAN discipline),
  core-java/frontend/edge-go deployments, configmap.yaml (app-config keys incl. split-horizon
  issuer keys), networkpolicies/ (D-17 kube-dns selector fix already in), pg-backup-cronjob.yaml.
- `k8s/goldens/` + `k8s/scripts/` (`check-no-plaintext-secrets.sh`, `check-connection-math.sh`,
  render-invariant checks) — every new kustomization is auto-discovered; goldens must be
  regenerated deliberately, never drift.
- `scripts/deploy.sh` + `.github/workflows/ci-cd.yaml` (deploy job, `DEPLOY_*_ENABLED` gates,
  `FRONTEND_PUBLIC_*` vars) — the #99 surface D-04 makes real; Azure OIDC federated credential
  replaces any long-lived kubeconfig.
- `k8s/QUICK_START.md`, `k8s/DEPLOYMENT.md` ("Runtime-parity gates" section),
  `docs/runbooks/sealed-secrets.md` — deploy docs to update; PRODUCTION_READINESS_REPORT.md is
  a dated record, append only.

### Monitoring (the corpus being ported)
- `infra/monitoring/docker-compose.monitoring.yml` — the 5 services (prometheus, grafana,
  alertmanager, redis-exporter, postgres-exporter) being re-homed to k8s manifests (D-16);
  note the `sslmode=require` deployed default (Phase 27 deferred-items §8).
- `infra/monitoring/` rule files + dashboard JSON — the ONE rule corpus D-16 mounts verbatim.
- `scripts/check-alert-liveness.sh`, `scripts/check-alert-metrics.sh` — must exit 0 against
  staging (DPLY-03's literal criterion); `trap_rebuild_reds_alert_metrics` memory: a core-java
  rebuild reds the metrics gate until `scripts/seed-order-metric.sh` runs.
- `.planning/phases/27-observability-hardening/deferred-items.md` §5 — the "compose-scoped by
  construction" record DPLY-03 exists to close.

### Identity (in-cluster Keycloak, D-02)
- `infra/keycloak/realm-export.template.json` + `infra/keycloak/README.md` — realm import
  (`--override true`; KC is Postgres-backed, volume drop is a no-op —
  `reference_keycloak_realm_reimport` memory).
- GitHub issue #296 — in-cluster Keycloak hardening conditions, now live.
- `k8s/base/configmap.yaml` split-horizon issuer keys (Phase 26 D-13) — staging sets public
  issuer == pod-reachable issuer (`auth-staging.olajay.co.uk`), so the split collapses to one
  value there; `jwt_issuer_jwks_split_horizon` memory explains the outage class if mishandled.

### Security posture carried from Phase 28
- `docs/runbooks/credential-rotation.md` — Phase 28 wrote it explicitly so "Phase 29's staging
  secrets follow the same path" (D-17's Gmail app password, Grafana admin, staging DB creds).
- Phase 28 owner/runtime DB role split (`jtoye_app` owner/migrator vs runtime DML role) —
  applies to the managed Flexible Server; `DatabaseConfigurationValidator` fail-fasts guard it.
- `docs/security/PENTEST-TRIAGE.md` + #549 — staging OpenAPI endpoints require auth/disabled
  under the staging profile (already fixed; do not regress).

### Memory (session-external, load-bearing)
- `azure_deploy_target` — sub c483d353 / rg `jtoye-rg` is YOURS; `Prod - HS2 Ltd` is the
  EMPLOYER's, never touch. (Its "DNS at NS1" note refers to the olajay zone.)
- `decision_ci_frontend_public_vars` — the DEPLOY-flag hold this phase partially lifts (D-08);
  its "olajay resolves to nothing" claim is now corrected by the D-05 measurement.
- `k8s_kustomize_deploy`, `trap_compose_project_name_from_directory`,
  `trap_stale_containers_after_phase` — deploy/verify traps that carry to any cluster work.
- `trap_csp_realm_path_matching` — for #293's CSP change.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`k8s/staging/` overlay** — already carries the right hostnames, realm name, DEBUG logging,
  env-varying app-config keys (Phase 26 D-13/D-15/D-19 closed the base drift); this phase
  points it at a real cluster rather than rewriting it.
- **`k8s/local` bring-up pair** (`scripts/k8s-local-secrets.sh`, `scripts/k8s-local-up.sh`) —
  the idempotent secret-apply and XOR-guard patterns to mirror for staging bootstrap (minus
  the local-only shims).
- **Phase 26 CI gates** (env-contract, no-plaintext-secrets, connection-math, render
  invariants, goldens) — auto-cover every new kustomization including monitoring + Keycloak.
- **`infra/monitoring/` corpus** — rules, dashboards, exporter configs, alertmanager routing:
  ported, not rewritten (D-16).
- **`pg-backup-cronjob` + `jtoye_backup` BYPASSRLS role + `MIN_BACKUP_BYTES` checks** — the
  second-line pipeline DPLY-04's arm A exercises.
- **Phase 26-08 L7 login proof** — the verbatim-URL ingress login template DPLY-01 reuses.
- **`check-runtime-freshness.sh` / `check-branch-behind-base.sh`** — runtime-parity gates;
  staging deploys must satisfy the same "delivered runtime matches the branch" doctrine.

### Established Patterns
- **Every gate shown to FAIL before it is trusted** — applies to the CI deploy gate (#99), the
  alert gates against staging, the PITR two-arm drill, and the DPLY-05 denied-connection proof.
- **Per-issue deferral with a written reason** (the disposition discipline) — DPLY-02's core
  mechanic; silence is the defect.
- **Overlay owns its namespace; no `kind: Secret` in any kustomize build; config injection
  over literals** — all carry to monitoring + Keycloak manifests.
- **Dated records are appended, never rewritten** — ADR-0002 gets a dated acceptance note;
  PRODUCTION_READINESS_REPORT.md untouched.

### Integration Points
- New `k8s/` monitoring + Keycloak kustomizations → auto-discovered by existing CI gates →
  goldens regenerated deliberately.
- `ci-cd.yaml` deploy job → Azure OIDC federated credential → AKS; `DEPLOY_STAGING_ENABLED`
  flips on; smoke tests must hit the REAL staging hostnames (the ingress-hosts-patch header
  records CI once smoke-testing a third, wrong name).
- Managed Postgres → Flyway as `jtoye_app` (owner) + runtime role + `jtoye_backup` role + a
  `keycloak` database on the same server.
- Alertmanager → Gmail SMTP secret → the owner's inbox; Grafana → ingress + rotated admin
  credential.

</code_context>

<specifics>
## Specific Ideas

- The discussion itself was the sign-off venue: both ROADMAP blockers (hosting, domain) and
  the ADR-0002 sign-off were settled here deliberately — planning must treat them as LOCKED,
  not re-open them.
- "Nothing may look live before it is": staging-only DNS is a product-truth stance (the same
  class of honesty as Phase 33's "no kitchens within 3.1 miles" heading), not a cost measure.
- App email captured (Mailhog) while alert email is real is a deliberate asymmetry: the
  rehearsal environment must never surprise a real inbox, except when the platform itself is
  breaking — that email is the phase's entire point.
- Live DNS measurement 2026-08-10 (dig): `jtoye.co.uk` → Namecheap parking (registrar-servers
  NS, apex 162.255.119.30); `olajay.co.uk` → NS1/Netlify zone (dns1-4.p05.nsone.net, SOA
  domains+netlify.netlify.com), no A records. Re-measure before relying on it — DNS drifts.

</specifics>

<deferred>
## Deferred Ideas

- **SES sending-domain verification + real app email** — Phase 32, reason recorded per D-13
  (#294's SES half; the bucket half closes THIS phase per D-11).
- **SLOs + full runbook set (#112 remainder)** — post-GTM; SLOs on zero-traffic staging are
  theatre (D-18).
- **Production DNS records / cutover** — Phase 32 (D-08).
- **Zone migration to Azure DNS / external-dns / wildcard certs** — revisit only if record
  churn ever becomes real (D-07 rejected them as overkill for 4 stable records).
- **ntfy/push alerting beside email** — cheap follow-up if email notice-time proves too slow.
- **Full-catalogue EXIF/WebP media sweep** — carried from Phase 28's deferred list; its dated
  plan may name Phase 29+ but it was NOT folded into this phase's scope.

</deferred>

---

*Phase: 29-Deployable Staging, With Its Own Monitoring*
*Context gathered: 2026-08-10*
